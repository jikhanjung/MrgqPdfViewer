package com.mrgq.pdfviewer

import android.graphics.Path
import android.graphics.PointF
import com.mrgq.pdfviewer.score.PathBox
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import kotlin.math.max
import kotlin.math.min

/**
 * **기준 구현** — v046 에서 실제로 쓰던 PdfBox 엔진 기반 경로 수집기 그대로다 (골든 데이터와 일치가 확인된 코드).
 *
 * 앱은 할당 폭주(900MB) 때문에 [com.mrgq.pdfviewer.score.PathContentInterpreter] 로 바꿨고(#049),
 * 이 클래스는 두 구현이 같은 박스를 내는지 대조하는 데만 쓴다 (PathInterpreterEquivalenceTest).
 */
class PdfBoxPathCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {

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

    override fun clip(windingRule: Path.FillType) {}

    override fun drawImage(pdImage: PDImage) {}

    override fun shadingFill(shadingName: COSName) {}
}
