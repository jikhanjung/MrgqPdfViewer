package com.mrgq.pdfviewer.score

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import kotlin.math.max
import kotlin.math.min

/**
 * 페이지 콘텐츠 스트림을 실행하며, 칠하거나 끝낸 경로마다 바운딩 박스를 모은다.
 *
 * PdfBox 가 CTM(q/Q/cm)을 적용한 좌표로 콜백하므로, 파이썬 버전(`segment_score.py`)이 직접 하던
 * 행렬 스택 추적이 필요 없다. 파이썬과 같게:
 *  - 곡선은 끝점만 박스에 넣는다
 *  - 칠(f·S·B…)뿐 아니라 `n`(클립 뒤 경로 끝내기)도 박스로 남긴다
 * 파이썬과 다르게 `re` 사각형과 Form XObject 안의 경로도 본다 (파이썬 파서는 둘 다 건너뛰었다).
 */
internal class PdfPathCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {

    val boxes = ArrayList<PathBox>()

    private val originX = page.cropBox.lowerLeftX
    private val originY = page.cropBox.lowerLeftY
    private val currentPoint = PointF()

    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var hasPoints = false
    private var curved = false

    private fun add(x: Float, y: Float) {
        val px = x - originX
        val py = y - originY
        if (!hasPoints) {
            minX = px; maxX = px; minY = py; maxY = py
            hasPoints = true
        } else {
            minX = min(minX, px); maxX = max(maxX, px)
            minY = min(minY, py); maxY = max(maxY, py)
        }
    }

    private fun finishPath() {
        if (hasPoints) boxes += PathBox(minX, minY, maxX, maxY, curved)
        hasPoints = false
        curved = false
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        add(p0.x, p0.y); add(p1.x, p1.y); add(p2.x, p2.y); add(p3.x, p3.y)
        currentPoint.set(p0.x, p0.y)
    }

    override fun moveTo(x: Float, y: Float) {
        add(x, y)
        currentPoint.set(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        add(x, y)
        currentPoint.set(x, y)
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        curved = true
        add(x3, y3)
        currentPoint.set(x3, y3)
    }

    override fun getCurrentPoint(): PointF = PointF(currentPoint.x, currentPoint.y)

    override fun closePath() {}

    override fun endPath() = finishPath()

    override fun strokePath() = finishPath()

    override fun fillPath(windingRule: Path.FillType) = finishPath()

    override fun fillAndStrokePath(windingRule: Path.FillType) = finishPath()

    // W 뒤에는 n(endPath)이 오고 거기서 박스를 남긴다
    override fun clip(windingRule: Path.FillType) {}

    override fun drawImage(pdImage: PDImage) {}

    override fun shadingFill(shadingName: COSName) {}
}
