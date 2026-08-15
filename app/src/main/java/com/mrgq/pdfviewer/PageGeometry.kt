package com.mrgq.pdfviewer

/**
 * PDF 페이지를 화면에 앉히는 좌표 계산 — **단일 출처**.
 *
 * ## 왜 뽑아냈나
 *
 * 이 공식은 원래 **세 곳에 똑같이 복제**돼 있었다:
 * `PageCache.renderPageToTargetBitmap`(프리렌더/캐시 경로),
 * `PdfViewerActivity.renderPageAtSinglePageTarget`(캐시 미스 단일),
 * `PdfViewerActivity.renderPageAtTwoPageTarget`(캐시 미스 두 페이지).
 *
 * 세 벌이 드리프트하면 **같은 페이지가 캐시 히트냐 미스냐에 따라 다르게 렌더된다.**
 * P01(v0.1.11)이 실제로 겪은 증상이고, 그때 "PageCache 와 동일 공식을 쓰게 해서" 고쳤지만
 * 공식 자체는 여전히 세 벌로 남아 있었다. 여기로 합쳐 그 재발 가능성을 없앤다.
 *
 * 이 프로젝트에서 **가장 자주 깨진 코드**이기도 하다 — P01(두께 불균일) → P2(dropout) →
 * #041(감마) → #042(oversample 제거·정수 좌표). 네 번 다 실기기에서 눈으로 보고서야 알았다.
 * 그래서 순수 함수로 만들어 `PageGeometryTest` 가 값을 고정한다.
 *
 * ## 파이프라인
 *
 * ```
 * PDF (pt) ──fitScale──> 표시 크기          ← 캐시·ImageView 가 다루는 크기
 *          ──renderScale(= fitScale × oversample)──> 렌더 크기(transient)
 *                                          ← 렌더 직후 표시 크기로 다운스케일
 * ```
 *
 * 크롭(위/아래 클리핑)은 **vector 변환 단계에 흡수**한다(`clipTranslateY`). 래스터화 후
 * 비트맵을 잘라내면 fractional scaling 이 한 번 더 끼어 오선 두께가 들쭉날쭉해진다(P01).
 */
data class PageGeometry(
    /** PDF 포인트 → 표시 픽셀 배율. */
    val fitScale: Float,
    /** 캐시·ImageView 가 받는 최종 비트맵 크기. */
    val displayWidth: Int,
    val displayHeight: Int,
    /** 실제 래스터화 배율 (`fitScale × 유효 oversample`). */
    val renderScale: Float,
    /** transient 렌더 비트맵 크기. oversample 1× 면 표시 크기와 같다. */
    val renderWidth: Int,
    val renderHeight: Int,
    /** 위쪽 클리핑을 vector 단계에서 잘라내기 위한 Matrix 세로 이동량 (0 이하). */
    val clipTranslateY: Float,
) {
    /** oversample 이 걸리지 않아 다운스케일 단계가 no-op 인가. */
    val isNativeScale: Boolean
        get() = renderWidth == displayWidth && renderHeight == displayHeight

    companion object {
        /** 위/아래 각각의 클리핑 상한. 둘 다 최대면 남는 높이가 10%. */
        const val MAX_CLIPPING = 0.45f

        /** 두 페이지 모드 중앙 여백 상한 (화면 폭 기준). */
        const val MAX_CENTER_PADDING = 0.5f

        /** 클리핑을 다 먹여도 이만큼은 남긴다 (0 나눗셈·빈 비트맵 방지). */
        const val MIN_VISIBLE_FRACTION = 0.1f

        /**
         * @param pdfWidth PDF 페이지 폭 (포인트, `PdfRenderer.Page.getWidth()`)
         * @param twoPageMode 두 페이지 모드면 화면 절반에서 중앙 여백의 절반을 뺀 폭에 맞춘다
         */
        fun compute(
            pdfWidth: Int,
            pdfHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
            topClipping: Float = 0f,
            bottomClipping: Float = 0f,
            centerPadding: Float = 0f,
            twoPageMode: Boolean = false,
        ): PageGeometry {
            val top = topClipping.coerceIn(0f, MAX_CLIPPING)
            val bottom = bottomClipping.coerceIn(0f, MAX_CLIPPING)
            val padding = centerPadding.coerceIn(0f, MAX_CENTER_PADDING)

            val visibleFraction = (1f - top - bottom).coerceAtLeast(MIN_VISIBLE_FRACTION)
            val visiblePdfHeight = pdfHeight * visibleFraction

            val fitScale = if (twoPageMode) {
                val halfScreenW = screenWidth / 2f
                val halfPadPx = screenWidth * padding / 2f
                val availW = (halfScreenW - halfPadPx).coerceAtLeast(1f)
                minOf(availW / pdfWidth, screenHeight / visiblePdfHeight)
            } else {
                minOf(screenWidth / pdfWidth.toFloat(), screenHeight / visiblePdfHeight)
            }

            val displayW = (pdfWidth * fitScale).toInt().coerceAtLeast(1)
            val displayH = (visiblePdfHeight * fitScale).toInt().coerceAtLeast(1)

            val renderScale = fitScale * PageCache.effectiveOversampleFactor(displayW, displayH)

            return PageGeometry(
                fitScale = fitScale,
                displayWidth = displayW,
                displayHeight = displayH,
                renderScale = renderScale,
                renderWidth = (pdfWidth * renderScale).toInt().coerceAtLeast(1),
                renderHeight = (visiblePdfHeight * renderScale).toInt().coerceAtLeast(1),
                clipTranslateY = -pdfHeight * top * renderScale,
            )
        }
    }
}

/**
 * 두 페이지 결합 시 좌/우 비트맵을 놓을 좌표.
 *
 * ⚠️ **정수다.** 소수점 좌표로 blit 하면 Canvas 가 재샘플링해서, oversample 1× 로 어렵게 얻은
 * device-pixel 스냅이 도로 뭉개진다 (#042). 중앙 여백이 홀수 픽셀일 때 실제로 `.5` 가 나왔다.
 */
data class TwoPageOffsets(
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
) {
    companion object {
        fun compute(
            canvasWidth: Int,
            canvasHeight: Int,
            centerPadding: Float,
            leftWidth: Int,
            leftHeight: Int,
            rightWidth: Int = 0,
            rightHeight: Int = 0,
        ): TwoPageOffsets {
            val centerPadPx = (canvasWidth * centerPadding.coerceIn(0f, PageGeometry.MAX_CENTER_PADDING)).toInt()
            val halfWidth = canvasWidth / 2
            val halfPadPx = centerPadPx / 2
            val areaWidth = halfWidth - halfPadPx
            return TwoPageOffsets(
                leftX = (areaWidth - leftWidth) / 2,
                leftY = (canvasHeight - leftHeight) / 2,
                rightX = halfWidth + halfPadPx + (areaWidth - rightWidth) / 2,
                rightY = (canvasHeight - rightHeight) / 2,
            )
        }
    }
}
