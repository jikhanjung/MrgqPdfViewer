package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.ClipRect
import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.PartClip
import com.mrgq.pdfviewer.score.PartLayout
import com.mrgq.pdfviewer.score.PathBox
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.SystemLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 파트보 소속에 따라 넓혀 자르기 (P07). 보표 3개 (100~120, 160~180, 220~240, 오선 간격 5pt), 고른 보표 1 의 기본 띠 = 140~200.
 * 경로 박스는 해석기처럼 아래→위(쪽 높이 842)로 넣는다.
 */
class PartClipTest {

    private val h = 842f
    private val bands = listOf(100f to 120f, 160f to 180f, 220f to 240f)

    /** 위→아래 좌표로 만든 경로 박스 */
    private fun box(x0: Float, top: Float, x1: Float, bottom: Float, curved: Boolean = false) = PathBox(x0, h - bottom, x1, h - top, curved)

    private fun extras(vararg boxes: PathBox) = PartClip.extras(boxes.toList(), h, bands, 1, 1, 140f, 200f, 72f, 564f)

    @Test
    fun 가운데가_띠_쪽인_걸친_슬러는_넓힌다() {
        // 보표 1 아래로 처진 슬러: 190~206 (가운데 198 ≤ 경계 200)
        assertEquals(listOf(ClipRect(99f, 200f, 161f, 207f)), extras(box(100f, 190f, 160f, 206f, curved = true)))
        // 보표 1 위로 솟은 빔: 136~142 (가운데 139 < 140 이라 바깥 — 이웃 것), 138~150 (가운데 144 ≥ 140 — 이 보표 것)
        assertEquals(listOf(ClipRect(299f, 137f, 351f, 140f)), extras(box(300f, 136f, 350f, 142f), box(300f, 138f, 350f, 150f)))
    }

    @Test
    fun 가운데가_바깥인_걸친_그림은_이웃_것() {
        // 보표 2 위로 솟은 슬러: 195~215 (가운데 205 > 경계 200)
        assertEquals(emptyList<ClipRect>(), extras(box(100f, 195f, 160f, 215f, curved = true)))
    }

    @Test
    fun 덧줄_기둥은_끝_덧줄_너머_음표_머리까지() {
        // 보표 1 위 덧줄 5개: 155 · 150 · 145 · 140 · 135 → 음표 머리는 130 칸까지
        val ledgers = (1..5).map { n -> box(300f, 160f - n * 5 - 0.5f, 310f, 160f - n * 5 + 0.5f) }.toTypedArray()
        val out = extras(*ledgers)
        assertTrue("넓힌 곳: $out", ClipRect(299f, 129f, 311f, 140f) in out)
    }

    @Test
    fun 넓혀도_이웃_오선은_넘지_않는다() {
        // 덧줄 7개(125 까지) → 음표 머리 120 칸이지만 위 보표 아래 오선(120) + 1 에서 멈춘다
        val ledgers = (1..7).map { n -> box(300f, 160f - n * 5 - 0.5f, 310f, 160f - n * 5 + 0.5f) }.toTypedArray()
        val tallest = extras(*ledgers).minOf { it.top }
        assertEquals(121f, tallest)
    }

    @Test
    fun 띠_안에_끝나는_덧줄_두어_개는_넓히지_않는다() {
        // 덧줄 2개(155 · 150) → 음표 머리 145 칸, 기본 띠(140~) 안이다
        val ledgers = (1..2).map { n -> box(300f, 160f - n * 5 - 0.5f, 310f, 160f - n * 5 + 0.5f) }.toTypedArray()
        assertEquals(emptyList<ClipRect>(), extras(*ledgers))
    }

    @Test
    fun 넓힌_만큼_조각이_높아지고_배치를_저장했다_되읽는다() {
        val system = SystemLayout(100f, 240f, 80f, 560f, bands, listOf(80f, 300f, 560f))
        val score = ScoreLayout(listOf(PageLayout(0, 595f, h, listOf(system))))
        val slur = box(100f, 190f, 160f, 206f, curved = true)
        val layout = PartLayout.build(score.toStaves("f"), score.toMeasures("f"), setOf(1), mapOf(0 to listOf(slur)))!!
        val strip = layout.strips.single()
        assertEquals(140f, strip.srcTop)
        assertEquals(207f, strip.srcBottom) // 200 → 슬러 끝 + 1
        assertEquals(2, strip.clips.size)   // 기본 띠 + 슬러
        assertEquals(layout, PartLayout.decode(PartLayout.encode(layout)))
        assertEquals(null, PartLayout.decode("{깨진"))
    }
}
