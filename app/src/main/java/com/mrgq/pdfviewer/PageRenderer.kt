package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer

/**
 * PDF 페이지 → 표시 크기 비트맵. **렌더 경로의 단일 출처**.
 *
 * 좌표 계산은 [PageGeometry] 가, 실제 래스터화는 여기가 담당한다. 프리렌더(PageCache)든
 * 캐시 미스(PdfViewerActivity)든 단일/두 페이지 모드든 **전부 이 함수 하나를 지난다** —
 * 경로마다 다른 비트맵이 나오는 일(P01 이 겪은 캐시 히트/미스 불일치)을 구조적으로 막는다.
 *
 * 단계:
 * 1. `renderScale` 로 transient 비트맵에 래스터화. 크롭은 Matrix 로 vector 단계에 흡수한다
 *    (래스터 후 자르면 fractional scaling 이 한 번 더 끼어 오선이 들쭉날쭉해진다 — P01).
 * 2. oversample 이 걸려 있으면 표시 크기로 다운스케일. 1× 면 이 단계는 no-op 이다(#042).
 * 3. 다운스케일로 희석된 잉크를 톤 커브로 보정 (oversample 1× 면 [InkGamma] 가 알아서 패스).
 */
object PageRenderer {

    fun render(page: PdfRenderer.Page, geometry: PageGeometry): Bitmap {
        val target = Bitmap.createBitmap(
            geometry.renderWidth, geometry.renderHeight, Bitmap.Config.ARGB_8888
        )
        target.eraseColor(Color.WHITE)

        val matrix = Matrix().apply {
            setScale(geometry.renderScale, geometry.renderScale)
            postTranslate(0f, geometry.clipTranslateY)
        }
        page.render(target, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        val display = if (geometry.isNativeScale) {
            target
        } else {
            Bitmap.createScaledBitmap(target, geometry.displayWidth, geometry.displayHeight, true)
        }
        if (display !== target) {
            target.recycle()
        }
        return InkGamma.apply(display)
    }
}
