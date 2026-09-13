package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.PageGeometry
import com.mrgq.pdfviewer.TwoPageOffsets
import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/** 화면에 그릴 마디 박스 — **ImageView 에 들어간 비트맵의 픽셀 좌표**. */
data class OverlayBox(
    val measureNumber: Int,
    val systemIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 마디 박스를 PDF 포인트 → 표시 비트맵 픽셀로 옮긴다. 렌더러와 **같은 공식**([PageGeometry],
 * [TwoPageOffsets])을 써서, 클리핑·두 페이지 배치·중앙 여백이 바뀌어도 박스가 악보에서 어긋나지 않는다.
 *
 * 비트맵 → 화면 단계는 여기서 계산하지 않는다. ImageView 에 실제로 걸린 imageMatrix 를
 * [ScoreOverlayView] 가 그대로 적용한다 (행렬을 만드는 코드가 두 벌이라 다시 계산하면 드리프트한다).
 *
 * ```
 * PDF pt ─(fitScale, 위 클리핑)→ 페이지 비트맵 ─(두 페이지면 좌/우 오프셋)→ 표시 비트맵 ─(imageMatrix)→ 화면
 * ```
 */
object ScoreOverlayGeometry {

    /**
     * PDF 포인트 크기를 `PdfRenderer.Page.getWidth()/getHeight()` 와 같은 정수로. 렌더러가 쓰는 크기와
     * 같아야 fitScale 이 일치한다 — 절삭이 맞는지는 `ScoreLayoutAnalyzerTest` 가 실제 PdfRenderer 와 대조한다.
     */
    fun rendererPoints(pt: Float): Int = pt.toInt()

    fun boxes(
        measures: List<ScoreMeasure>,
        leftPageIndex: Int,
        twoPageMode: Boolean,
        pageCount: Int,
        screenWidth: Int,
        screenHeight: Int,
        topClipping: Float,
        bottomClipping: Float,
        centerPadding: Float,
    ): List<OverlayBox> {
        if (measures.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val byPage = measures.groupBy { it.pageIndex }
        val anyMeasure = measures.first()

        // 마디가 없는 페이지(표지 등)의 크기는 모르므로 다른 페이지 크기로 대신한다 — 두 페이지 모드에서
        // 옆 페이지 배치를 계산하는 데만 쓰인다.
        fun sizeOf(page: Int): Pair<Int, Int> {
            val m = byPage[page]?.first() ?: anyMeasure
            return rendererPoints(m.pageWidthPt) to rendererPoints(m.pageHeightPt)
        }
        fun geometryOf(page: Int, twoPage: Boolean): PageGeometry {
            val (w, h) = sizeOf(page)
            return PageGeometry.compute(w, h, screenWidth, screenHeight, topClipping, bottomClipping, centerPadding, twoPage)
        }

        if (!twoPageMode) {
            return mapPage(byPage[leftPageIndex], geometryOf(leftPageIndex, false), sizeOf(leftPageIndex).second, 0, 0, topClipping)
        }

        val rightPageIndex = (leftPageIndex + 1).takeIf { it < pageCount }
        val left = geometryOf(leftPageIndex, true)
        val right = rightPageIndex?.let { geometryOf(it, true) }
        // PdfViewerActivity.combineTwoPagesUnified 와 같은 캔버스·배치
        val offsets = TwoPageOffsets.compute(
            canvasWidth = screenWidth,
            canvasHeight = maxOf(left.displayHeight, right?.displayHeight ?: 0),
            centerPadding = centerPadding,
            leftWidth = left.displayWidth,
            leftHeight = left.displayHeight,
            rightWidth = right?.displayWidth ?: 0,
            rightHeight = right?.displayHeight ?: 0,
        )
        val leftBoxes = mapPage(byPage[leftPageIndex], left, sizeOf(leftPageIndex).second, offsets.leftX, offsets.leftY, topClipping)
        val rightBoxes = if (rightPageIndex != null && right != null) {
            mapPage(byPage[rightPageIndex], right, sizeOf(rightPageIndex).second, offsets.rightX, offsets.rightY, topClipping)
        } else {
            emptyList()
        }
        return leftBoxes + rightBoxes
    }

    private fun mapPage(
        measures: List<ScoreMeasure>?,
        geometry: PageGeometry,
        pdfHeight: Int,
        offsetX: Int,
        offsetY: Int,
        topClipping: Float,
    ): List<OverlayBox> {
        if (measures.isNullOrEmpty()) return emptyList()
        // PageGeometry.compute 와 같은 상한으로 자른 클리핑
        val clippedTopPt = pdfHeight * topClipping.coerceIn(0f, PageGeometry.MAX_CLIPPING)
        val scale = geometry.fitScale
        val minX = offsetX.toFloat()
        val minY = offsetY.toFloat()
        val maxX = minX + geometry.displayWidth
        val maxY = minY + geometry.displayHeight

        return measures.mapNotNull { m ->
            val left = (minX + m.leftPt * scale).coerceIn(minX, maxX)
            val right = (minX + m.rightPt * scale).coerceIn(minX, maxX)
            val top = (minY + (m.topPt - clippedTopPt) * scale).coerceIn(minY, maxY)
            val bottom = (minY + (m.bottomPt - clippedTopPt) * scale).coerceIn(minY, maxY)
            // 클리핑으로 완전히 잘려 나간 마디는 그리지 않는다
            if (right - left < 1f || bottom - top < 1f) null
            else OverlayBox(m.measureNumber, m.systemIndex, left, top, right, bottom)
        }
    }
}
