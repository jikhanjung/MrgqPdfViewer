package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.score.ScoreOverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 마디 박스 PDF pt → 표시 비트맵 px. 렌더러와 같은 공식(PageGeometry, TwoPageOffsets)을 거치는지 본다.
 * 화면은 실사용 기기와 같은 1920×1080, 페이지는 A4(595×841pt).
 */
class ScoreOverlayGeometryTest {

    private val screenW = 1920
    private val screenH = 1080

    private fun measure(number: Int, page: Int, left: Float, top: Float, right: Float, bottom: Float, system: Int = 0) =
        ScoreMeasure("file-1", number, page, system, left, top, right, bottom, pageWidthPt = 595.32f, pageHeightPt = 841.92f)

    private fun boxes(
        measures: List<ScoreMeasure>,
        leftPage: Int = 0,
        twoPage: Boolean = false,
        pageCount: Int = 13,
        top: Float = 0f,
        bottom: Float = 0f,
        padding: Float = 0f,
    ) = ScoreOverlayGeometry.boxes(measures, leftPage, twoPage, pageCount, screenW, screenH, top, bottom, padding)

    @Test
    fun PdfRenderer_와_같은_정수_포인트로_자른다() {
        assertEquals(595, ScoreOverlayGeometry.rendererPoints(595.32f))
        assertEquals(841, ScoreOverlayGeometry.rendererPoints(841.92f))
    }

    @Test
    fun 단일_페이지는_fitScale_만_곱한다() {
        val g = PageGeometry.compute(595, 841, screenW, screenH)
        val box = boxes(listOf(measure(1, 0, 75.1f, 132.8f, 212.1f, 410.9f))).single()
        assertEquals(75.1f * g.fitScale, box.left, 0.01f)
        assertEquals(132.8f * g.fitScale, box.top, 0.01f)
        assertEquals(212.1f * g.fitScale, box.right, 0.01f)
        assertEquals(410.9f * g.fitScale, box.bottom, 0.01f)
    }

    @Test
    fun 다른_페이지의_마디는_그리지_않는다() {
        val measures = listOf(measure(1, 0, 75f, 133f, 212f, 411f), measure(8, 1, 54f, 97f, 240f, 382f))
        assertEquals(listOf(1), boxes(measures, leftPage = 0).map { it.measureNumber })
        assertEquals(listOf(8), boxes(measures, leftPage = 1).map { it.measureNumber })
    }

    @Test
    fun 위_클리핑만큼_올리고_배율도_렌더러와_같다() {
        val g = PageGeometry.compute(595, 841, screenW, screenH, topClipping = 0.05f, bottomClipping = 0.05f)
        val box = boxes(listOf(measure(1, 0, 75.1f, 132.8f, 212.1f, 410.9f)), top = 0.05f, bottom = 0.05f).single()
        assertEquals((132.8f - 841 * 0.05f) * g.fitScale, box.top, 0.01f)
        assertEquals(75.1f * g.fitScale, box.left, 0.01f)
    }

    @Test
    fun 클리핑으로_잘린_부분은_페이지_안으로_자르고_완전히_잘리면_뺀다() {
        val g = PageGeometry.compute(595, 841, screenW, screenH, topClipping = 0.15f)
        val measures = listOf(
            measure(1, 0, 75f, 20f, 212f, 60f),     // 위 126pt 안에 완전히 들어가 잘림
            measure(2, 0, 75f, 100f, 212f, 300f),   // 위쪽 일부만 잘림
        )
        val result = boxes(measures, top = 0.15f)
        assertEquals(listOf(2), result.map { it.measureNumber })
        assertEquals(0f, result.single().top, 0.01f)
        assertEquals((300f - 841 * 0.15f) * g.fitScale, result.single().bottom, 0.01f)
    }

    @Test
    fun 두_페이지_모드는_좌우_배치_오프셋을_더한다() {
        val g = PageGeometry.compute(595, 841, screenW, screenH, centerPadding = 0.02f, twoPageMode = true)
        val offsets = TwoPageOffsets.compute(screenW, g.displayHeight, 0.02f, g.displayWidth, g.displayHeight, g.displayWidth, g.displayHeight)
        val measures = listOf(measure(1, 0, 75f, 133f, 212f, 411f), measure(8, 1, 54f, 97f, 240f, 382f))

        val result = boxes(measures, leftPage = 0, twoPage = true, padding = 0.02f)

        val left = result.first { it.measureNumber == 1 }
        val right = result.first { it.measureNumber == 8 }
        assertEquals(offsets.leftX + 75f * g.fitScale, left.left, 0.01f)
        assertEquals(offsets.leftY + 133f * g.fitScale, left.top, 0.01f)
        assertEquals(offsets.rightX + 54f * g.fitScale, right.left, 0.01f)
        assertTrue("오른쪽 페이지 박스는 화면 절반보다 오른쪽", right.left > screenW / 2f)
    }

    @Test
    fun 두_페이지_모드의_마지막_홀수_페이지는_왼쪽에만() {
        val measures = listOf(measure(81, 12, 54f, 484f, 553f, 769f))
        val result = boxes(measures, leftPage = 12, twoPage = true, pageCount = 13)
        assertEquals(listOf(81), result.map { it.measureNumber })
        assertTrue(result.single().right < screenW / 2f)
    }

    @Test
    fun 마디가_없으면_빈_결과() {
        assertTrue(boxes(emptyList()).isEmpty())
        assertTrue(ScoreOverlayGeometry.boxes(listOf(measure(1, 0, 1f, 1f, 2f, 2f)), 0, false, 1, 0, 0, 0f, 0f, 0f).isEmpty())
    }
}
