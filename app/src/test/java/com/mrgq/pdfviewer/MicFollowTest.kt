package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.follow.ChromaExtractor
import com.mrgq.pdfviewer.follow.Fft
import com.mrgq.pdfviewer.follow.OnlineAligner
import com.mrgq.pdfviewer.follow.PageTurnDecider
import com.mrgq.pdfviewer.follow.RollingTurns
import com.mrgq.pdfviewer.follow.ScoreChroma
import com.mrgq.pdfviewer.follow.normalized
import com.mrgq.pdfviewer.score.MusicXmlScore
import com.mrgq.pdfviewer.score.XmlMeasure
import com.mrgq.pdfviewer.score.XmlNote
import com.mrgq.pdfviewer.score.XmlPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 마이크 악보 추적 핵심 (P10 1단계) — FFT · 크로마 · 악보 크로마 · 온라인 정렬 · 넘김 판단 */
class MicFollowTest {

    @Test
    fun `FFT 는 느린 DFT 와 같다`() {
        val n = 64
        val rnd = Random(1)
        val x = DoubleArray(n) { rnd.nextDouble() - 0.5 }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                r += x[t] * cos(2 * PI * k * t / n)
                i -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(r, re[k], 1e-9)
            assertEquals(i, im[k], 1e-9)
        }
    }

    @Test
    fun `사인파의 크로마는 그 음이름이 가장 크다`() {
        for ((freq, pc) in listOf(440.0 to 9, 261.63 to 0, 196.0 to 7, 1318.5 to 4)) {
            val frames = ArrayList<FloatArray>()
            val ex = ChromaExtractor { frames += it }
            ex.push(FloatArray(44100) { (0.5 * sin(2 * PI * freq * it / 44100)).toFloat() })
            assertTrue(frames.size > 10)
            val c = frames[5]
            assertEquals("$freq Hz", pc, c.indices.maxByOrNull { c[it] })
        }
        val ex = ChromaExtractor {}
        assertEquals(2048.0 / 44100, ex.frameSec, 1e-12)
    }

    private fun score(): MusicXmlScore {
        // 4/4 두 마디, 3/4 한 마디. 파트 0: 마디 0 = C4 한 박 · E4 세 박, 마디 1 = G4 온음표, 마디 2 = A4 점2분
        val measures = listOf(XmlMeasure(0, "1", 4, 4), XmlMeasure(1, "2", 4, 4), XmlMeasure(2, "3", 3, 4))
        val notes = listOf(
            XmlNote(0, 0.0, 1.0, 60), XmlNote(0, 1.0, 3.0, 64), XmlNote(1, 0.0, 4.0, 67), XmlNote(2, 0.0, 3.0, 69),
        )
        return MusicXmlScore(listOf(XmlPart("P1", "기타", 1)), measures, listOf(notes))
    }

    @Test
    fun `악보 크로마 — 마디 시작 · 칸마다 음이름 · 위치 바꾸기`() {
        val sc = ScoreChroma.build(score(), quarterBpm = 60.0, frameSec = 0.1)
        assertEquals(listOf(0.0, 4.0, 8.0, 11.0), sc.measureStartQ.toList())
        assertEquals(111, sc.size) // 11초 / 0.1 + 1
        fun top(col: Int) = sc.frames[col].indices.maxByOrNull { sc.frames[col][it] }
        assertEquals(0, top(5)) // 0.5초 = C
        assertEquals(4, top(20)) // 2초 = E
        assertEquals(7, top(50)) // 5초 = G
        assertEquals(9, top(95)) // 9.5초 = A
        assertEquals(1.0, sc.measurePosOf(40), 1e-9) // 4초 = 마디 1 첫머리
        assertEquals(2.5, sc.measurePosOfQuarter(9.5), 1e-9) // 마디 2 가운데 (3박 중 1.5박)
        assertEquals(80, sc.colOfMeasure(2))
    }

    @Test
    fun `온라인 정렬은 빠르기가 달라도 따라간다`() {
        // 무작위 악보 크로마(겹치지 않게), 녹음 = 1.25배 느리게 + 잡음 — 추정이 실제 위치 근처
        val rnd = Random(7)
        val m = 1200
        val score = Array(m / 10) { FloatArray(12) { rnd.nextFloat() }.normalized() }.let { blocks ->
            Array(m) { blocks[it / 10] } // 0.5초(10칸)마다 바뀌는 화음
        }
        val frameSec = 0.05
        val aligner = OnlineAligner(score, startCol = 0, frameSec = frameSec)
        var maxErr = 0
        for (i in 0 until (m * 1.25).toInt() - 1) {
            val truth = (i / 1.25).toInt()
            val noisy = FloatArray(12) { score[truth][it] + 0.15f * rnd.nextFloat() }.normalized()
            val est = aligner.feed(noisy)
            if (i > 100) maxErr = maxOf(maxErr, abs(est - truth))
        }
        assertTrue("최대 오차 $maxErr 칸", maxErr <= 15) // 0.75초 안
    }

    @Test
    fun `넘김 판단 — 아래 절반에서 머무르면 반 쪽, 다음 쪽에서 머무르면 쪽`() {
        // 마디 0 ~ 7: 쪽 0 (4 ~ 7 이 아래 절반), 마디 8 ~ 15: 쪽 1
        val pageOf = IntArray(16) { it / 8 }
        val lower = BooleanArray(16) { it % 8 >= 4 }
        val d = PageTurnDecider(pageOf, lower, startPage = 0, holdMs = 1000)
        assertNull(d.update(3.9, 0))
        assertNull(d.update(4.1, 100)) // 아래 절반 — 머무르기 시작
        assertNull(d.update(4.2, 900))
        assertEquals(PageTurnDecider.Turn.Half(0, 1), d.update(4.3, 1100))
        assertNull(d.update(5.0, 1500)) // 한 번만
        assertNull(d.update(8.1, 2000)) // 다음 쪽 — 머무르기 시작
        assertNull(d.update(7.9, 2500)) // 되돌아옴 → 취소
        assertNull(d.update(8.2, 3000))
        assertEquals(PageTurnDecider.Turn.Page(1), d.update(8.4, 4000))
        assertEquals(1, d.page)
        // 마지막 쪽은 반 쪽 넘김이 없다
        assertNull(d.update(13.0, 5000))
        assertNull(d.update(13.0, 7000))
        // 손으로 옮김
        d.moveTo(0)
        assertEquals(0, d.page)
    }

    // ── Python 과 같은가 (data/recordings/fixtures — 저장소 밖, 없으면 건너뜀) ─────────────────

    private val fixtures = File("../data/recordings/fixtures")

    private fun floats(name: String): FloatArray {
        val bytes = File(fixtures, name).readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @Test
    fun `크로마 계산이 Python stft_chroma 와 같다`() {
        assumeTrue(File(fixtures, "arp_60s_22050.f32").exists())
        val samples = floats("arp_60s_22050.f32")
        val expected = floats("arp_60s_chroma.f32")
        val frames = ArrayList<FloatArray>()
        ChromaExtractor(sampleRate = 22050, nFft = 2048, hop = 1024) { frames += it.normalized() }.push(samples)
        val n = expected.size / 12
        assertEquals(n, frames.size)
        var worst = 0.0
        for (i in 0 until n) for (k in 0 until 12) worst = maxOf(worst, abs(frames[i][k] - expected[i * 12 + k]).toDouble())
        assertTrue("최대 차이 $worst", worst < 1e-3)
    }

    @Test
    fun `정렬 경로가 Python oltw 와 같다 (아르페지오네 915초)`() {
        assumeTrue(File(fixtures, "arp_meta.json").exists())
        val metaText = File(fixtures, "arp_meta.json").readText() // JVM 테스트엔 org.json 이 없다 — 숫자만 읽는다
        fun num(key: String) = Regex("\"$key\":\\s*([0-9.eE+-]+)").find(metaText)!!.groupValues[1]
        val rec = floats("arp_rec_chroma.f32")
        val sc = floats("arp_score_chroma.f32")
        val score = Array(sc.size / 12) { j -> FloatArray(12) { sc[j * 12 + it] } }
        val bytes = File(fixtures, "arp_path.i32").readBytes()
        val ib = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val pyPath = IntArray(ib.remaining()).also { ib.get(it) }
        val aligner = OnlineAligner(score, num("startCol").toInt(), num("frameSec").toDouble())
        var same = 0
        var near = 0
        val n = rec.size / 12
        for (i in 0 until n) {
            val est = aligner.feed(FloatArray(12) { rec[i * 12 + it] })
            if (est == pyPath[i]) same++
            if (abs(est - pyPath[i]) <= 22) near++ // 1초 안
        }
        File(fixtures, "kt_path.i32").writeBytes(ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb ->
            for (i in 0 until n) bb.putInt(aligner.positionAt(i))
        }.array())
        println("Python 과 같은 칸 ${same * 100 / n}%, 1초 안 ${near * 100 / n}%, 재위치 ${aligner.jumps.size}번 (Python ${num("jumps")})")
        assertTrue("1초 안 ${near * 100.0 / n}%", near >= n * 0.98)
    }
}

/** 두 쪽 연주자의 차례 넘김 (P10 §3.3) — 쪽은 순번(0부터): 1쪽 = 0 */
class RollingTurnsTest {
    private val n = 10

    @Test
    fun `지휘자가 오른쪽 쪽에 들어서면 왼쪽을 다음 쪽으로, 그다음엔 오른쪽을`() {
        val s12 = RollingTurns.Spread.pairOf(0, n)
        assertEquals(RollingTurns.Spread(0, 1), s12)
        // 1쪽(0)을 치는 중 — 바꿀 것 없음
        assertEquals(RollingTurns.Action.None, RollingTurns.onPage(s12, 0, n))
        // 2쪽(1)에 들어섬 → 왼쪽을 3쪽(2)으로: 3 | 2
        val a = RollingTurns.onPage(s12, 1, n) as RollingTurns.Action.Delayed
        assertEquals(RollingTurns.Spread(2, 1), a.spread)
        // 3쪽(2)에 들어섬 → 오른쪽을 4쪽(3)으로: 3 | 4
        val b = RollingTurns.onPage(a.spread, 2, n) as RollingTurns.Action.Delayed
        assertEquals(RollingTurns.Spread(2, 3), b.spread)
        assertTrue(b.spread.isPair)
        // 4쪽(3) → 5 | 4
        assertEquals(RollingTurns.Spread(4, 3), (RollingTurns.onPage(b.spread, 3, n) as RollingTurns.Action.Delayed).spread)
    }

    @Test
    fun `화면에 없는 쪽은 바로 그 짝으로, 되돌아가면 그대로`() {
        val s32 = RollingTurns.Spread(2, 1)
        assertEquals(RollingTurns.Action.Immediate(RollingTurns.Spread(6, 7)), RollingTurns.onPage(s32, 6, n))
        assertEquals(RollingTurns.Action.Immediate(RollingTurns.Spread(0, 1)), RollingTurns.onPage(s32, 0, n))
        // 3 | 4 에서 3쪽으로 되돌아옴 — 4쪽은 아직 칠 쪽이라 그대로
        assertEquals(RollingTurns.Action.None, RollingTurns.onPage(RollingTurns.Spread(2, 3), 2, n))
    }

    @Test
    fun `마지막 쪽 근처`() {
        // 9 | 10 (8, 9) 에서 10쪽(9) — 다음 쪽 없음
        assertEquals(RollingTurns.Action.None, RollingTurns.onPage(RollingTurns.Spread(8, 9), 9, n))
        // 쪽이 홀수 개(9쪽): 8 | 9 → 9쪽(8)에 들어섬, 오른쪽 자리에 둘 쪽 없음
        assertEquals(RollingTurns.Action.None, RollingTurns.onPage(RollingTurns.Spread(8, 7), 8, 9))
        assertEquals(RollingTurns.Spread(8, null), RollingTurns.Spread.pairOf(8, 9))
    }
}

/** 관성 항법 거르개 (P10 §9) */
class InertialTrackerTest {
    @Test
    fun `짧게 헤매는 추정은 무시하고 고른 빠르기로 간다`() {
        val t = com.mrgq.pdfviewer.follow.InertialTracker(0.0, frameSec = 0.05)
        var z = 0.0
        for (i in 1..200) { z += 1.0; t.feed(z) }
        assertEquals(200.0, t.position, 1.0)
        // 1초(20칸) 동안 +100칸 튄 추정 — 관성으로 계속
        for (i in 1..20) { z += 1.0; t.feed(z + 100) }
        assertEquals(220.0, t.position, 2.0)
        for (i in 1..20) { z += 1.0; t.feed(z) }
        assertEquals(240.0, t.position, 2.0)
        assertEquals(0, t.switches)
    }

    @Test
    fun `다른 곳에서 고르게 이어지면 3초 뒤 옮긴다`() {
        val t = com.mrgq.pdfviewer.follow.InertialTracker(0.0, frameSec = 0.05)
        var z = 0.0
        for (i in 1..100) { z += 1.0; t.feed(z) }
        z += 400.0 // 진짜로 다른 곳 (재위치)
        for (i in 1..70) { z += 1.0; t.feed(z) }
        assertEquals(1, t.switches)
        assertEquals(z, t.position, 3.0)
    }

    @Test
    fun `거른 경로가 Python inertia_eval 과 같다 (아르페지오네)`() {
        val dir = File("../data/recordings/fixtures")
        assumeTrue(File(dir, "arp_inertia.f64").exists())
        val zb = ByteBuffer.wrap(File(dir, "arp_path.i32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val z = IntArray(zb.remaining()).also { zb.get(it) }
        val xb = ByteBuffer.wrap(File(dir, "arp_inertia.f64").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()
        val expected = DoubleArray(xb.remaining()).also { xb.get(it) }
        val t = com.mrgq.pdfviewer.follow.InertialTracker(z[0].toDouble(), frameSec = 1024.0 / 22050)
        var near = 0
        for (i in 1 until z.size) {
            val x = t.feed(z[i].toDouble())
            if (abs(x - expected[i]) <= 0.5) near++
        }
        println("Python 과 0.5칸 안 ${near * 100 / (z.size - 1)}%, 옮김 ${t.switches}번")
        assertTrue(near >= (z.size - 1) * 0.99)
    }
}

/** 연주 시작 찾기 (P10 §11) */
class StartDetectorTest {
    @Test
    fun `시작 칸 · 악보 칸이 Python start_detect 와 같다 (태블릿 기록, 연주 전 11초)`() {
        val dir = File("../data/recordings/fixtures")
        assumeTrue(File(dir, "start_meta.json").exists())
        fun floats(name: String): FloatArray {
            val fb = ByteBuffer.wrap(File(dir, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
        val meta = File(dir, "start_meta.json").readText()
        fun num(key: String) = Regex("\"$key\":\\s*([0-9.eE+-]+)").find(meta)!!.groupValues[1]
        val rec = floats("start_rec.f32")
        val sc = floats("start_score.f32")
        val score = Array(sc.size / 12) { j -> FloatArray(12) { sc[j * 12 + it] } }
        val d = com.mrgq.pdfviewer.follow.StartDetector(score, 0, num("frameSec").toDouble())
        var found: Pair<Int, Int>? = null
        for (i in 0 until rec.size / 12) {
            found = d.feed(FloatArray(12) { rec[i * 12 + it] })
            if (found != null) break
        }
        assertEquals(num("frame").toInt(), found!!.first)
        assertEquals(num("col").toInt(), found.second)
    }

    @Test
    fun `소리가 악보와 안 맞으면 시작하지 않는다`() {
        val rnd = Random(3)
        val score = Array(3000) { FloatArray(12) { rnd.nextFloat() }.normalized() }
        val d = com.mrgq.pdfviewer.follow.StartDetector(score, 0, 0.05)
        var found: Pair<Int, Int>? = null
        for (i in 0 until 400) found = found ?: d.feed(FloatArray(12) { rnd.nextFloat() }.normalized())
        assertNull(found)
    }
}
