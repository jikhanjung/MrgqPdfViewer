package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.follow.ChromaExtractor
import com.mrgq.pdfviewer.follow.Fft
import com.mrgq.pdfviewer.follow.OnlineAligner
import com.mrgq.pdfviewer.follow.PageTurnDecider
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
