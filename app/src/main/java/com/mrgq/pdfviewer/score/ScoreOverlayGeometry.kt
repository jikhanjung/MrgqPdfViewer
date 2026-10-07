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
        val right = if (twoPageMode) (leftPageIndex + 1).takeIf { it < pageCount } else null
        return placements(
            leftPageIndex, right, twoPageMode, ::sizeOf, screenWidth, screenHeight, topClipping, bottomClipping, centerPadding,
        ).flatMap { mapPage(byPage[it.pageIndex], it) }
    }

    /**
     * 지금 화면에 놓인 쪽들 — 쪽 pt ↔ 표시 비트맵 px 변환 (마디 박스 · 메모 P11 이 함께 쓴다).
     * 한 쪽이면 하나, 두 쪽이면 왼 · 오(오른쪽이 없으면 왼쪽만). [rightPageIndex] 는 보통 왼쪽 + 1 이지만
     * 차례 넘김(P10 §3.3 — 3 | 2 처럼)이면 다른 쪽일 수 있다. [sizeOf] 는 `PdfRenderer` 쪽 크기(정수 pt), 모르면 null → 빠진다
     */
    fun placements(
        leftPageIndex: Int,
        rightPageIndex: Int?,
        twoPageMode: Boolean,
        sizeOf: (Int) -> Pair<Int, Int>?,
        screenWidth: Int,
        screenHeight: Int,
        topClipping: Float,
        bottomClipping: Float,
        centerPadding: Float,
    ): List<PagePlacement> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val clip = topClipping.coerceIn(0f, PageGeometry.MAX_CLIPPING)
        fun placed(page: Int, size: Pair<Int, Int>, g: PageGeometry, x: Int, y: Int) =
            PagePlacement(page, g.fitScale, size.second * clip, x, y, g.displayWidth, g.displayHeight)
        fun geometryOf(size: Pair<Int, Int>, twoPage: Boolean) =
            PageGeometry.compute(size.first, size.second, screenWidth, screenHeight, topClipping, bottomClipping, centerPadding, twoPage)

        val leftSize = sizeOf(leftPageIndex) ?: return emptyList()
        if (!twoPageMode) return listOf(placed(leftPageIndex, leftSize, geometryOf(leftSize, false), 0, 0))

        val left = geometryOf(leftSize, true)
        val rightSize = rightPageIndex?.let(sizeOf)
        val right = rightSize?.let { geometryOf(it, true) }
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
        return listOfNotNull(
            placed(leftPageIndex, leftSize, left, offsets.leftX, offsets.leftY),
            if (rightPageIndex != null && rightSize != null && right != null) placed(rightPageIndex, rightSize, right, offsets.rightX, offsets.rightY) else null,
        )
    }

    private fun mapPage(measures: List<ScoreMeasure>?, p: PagePlacement): List<OverlayBox> {
        if (measures.isNullOrEmpty()) return emptyList()
        return measures.mapNotNull { m ->
            val left = p.toBitmapX(m.leftPt).coerceIn(p.left, p.right)
            val right = p.toBitmapX(m.rightPt).coerceIn(p.left, p.right)
            val top = p.toBitmapY(m.topPt).coerceIn(p.top, p.bottom)
            val bottom = p.toBitmapY(m.bottomPt).coerceIn(p.top, p.bottom)
            // 클리핑으로 완전히 잘려 나간 마디는 그리지 않는다
            if (right - left < 1f || bottom - top < 1f) null
            else OverlayBox(m.measureNumber, m.systemIndex, left, top, right, bottom)
        }
    }
}

/**
 * 표시 비트맵에 놓인 쪽 하나 — 쪽 pt(왼 위 원점, `PdfRenderer` 크기) ↔ 표시 비트맵 px.
 * 렌더러와 같은 공식([PageGeometry] fitScale · 위 클리핑, [TwoPageOffsets] 배치)에서 나온다.
 */
data class PagePlacement(
    val pageIndex: Int,
    val fitScale: Float,
    /** 위 클리핑으로 잘려 나간 높이 (pt) */
    val clippedTopPt: Float,
    val offsetX: Int,
    val offsetY: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val left: Float get() = offsetX.toFloat()
    val top: Float get() = offsetY.toFloat()
    val right: Float get() = (offsetX + displayWidth).toFloat()
    val bottom: Float get() = (offsetY + displayHeight).toFloat()

    fun toBitmapX(xPt: Float): Float = offsetX + xPt * fitScale
    fun toBitmapY(yPt: Float): Float = offsetY + (yPt - clippedTopPt) * fitScale
    fun toPageX(x: Float): Float = (x - offsetX) / fitScale
    fun toPageY(y: Float): Float = (y - offsetY) / fitScale + clippedTopPt

    /** 표시 비트맵 px 가 이 쪽 안인가 */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
