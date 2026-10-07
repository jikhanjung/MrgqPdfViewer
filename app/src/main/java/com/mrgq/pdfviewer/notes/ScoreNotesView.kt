package com.mrgq.pdfviewer.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mrgq.pdfviewer.score.PagePlacement

/**
 * 악보 메모 그리기 (P11) — pdfView 와 같은 위치 · 크기. 저장된 메모는 **쪽 pt → 표시 비트맵 px([PagePlacement]) → imageMatrix**
 * 로 옮겨 그린다. 쪽 비트맵에 굽지 않으므로 쪽 캐시를 건드리지 않고, 켜고 끄는 데 다시 렌더할 필요가 없다.
 *
 * 지금 긋는 획은 화면 좌표 그대로 그리다가(지연 0) 손을 떼면 pt 로 바꿔 저장 목록에 들어간다.
 * 긋는 동안에는 **붙을 보표 띠**를 연하게 칠하고 이름을 보인다(P11 §3.6) — 애매하면 주황 · "?".
 */
class ScoreNotesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Drawn(val path: Path, val paint: Paint)
    private class Placed(val placement: PagePlacement, val drawn: List<Drawn>)

    private var placed: List<Placed> = emptyList()
    private val bitmapToView = Matrix()

    private val livePath = Path()
    private var liveActive = false
    private val livePaint = strokePaint()

    private var staffRect: RectF? = null
    private var staffLabel: String? = null
    private var staffSure = true
    private val staffFill = Paint().apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBackground = Paint().apply { style = Paint.Style.FILL }

    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadius = 0f
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xAA757575.toInt()
    }
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
    }

    /** 지금 화면의 쪽들과 그 쪽 메모. [imageMatrix] = pdfView 에 실제로 걸린 행렬 */
    fun show(placements: List<PagePlacement>, notesOf: (Int) -> List<ScoreNote>, imageMatrix: Matrix) {
        bitmapToView.set(imageMatrix)
        placed = placements.map { p -> Placed(p, notesOf(p.pageIndex).mapNotNull { drawnOf(it, p) }) }
        invalidate()
    }

    fun clear() {
        placed = emptyList()
        invalidate()
    }

    /** 지금 긋는 획 (화면 좌표). [widthPx] 화면 px */
    fun liveStroke(points: List<Float>, color: Int, widthPx: Float) {
        livePaint.color = color
        livePaint.strokeWidth = widthPx
        smoothPath(livePath, points)
        liveActive = true
        invalidate()
    }

    fun endLiveStroke() {
        liveActive = false
        livePath.reset()
        invalidate()
    }

    /** 붙을 보표 띠 (표시 비트맵 px) — null 이면 지운다 */
    fun staffHint(bitmapRect: RectF?, label: String?, sure: Boolean) {
        staffRect = bitmapRect
        staffLabel = label
        staffSure = sure
        invalidate()
    }

    /** 지우개 자리 (화면 좌표) — 반지름 0 이면 숨김 */
    fun eraserAt(x: Float, y: Float, radiusPx: Float) {
        eraserX = x
        eraserY = y
        eraserRadius = radiusPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        staffRect?.let { band ->
            rect.set(band)
            bitmapToView.mapRect(rect)
            staffFill.color = if (staffSure) STAFF_SURE else STAFF_UNSURE
            canvas.drawRect(rect, staffFill)
            staffLabel?.let { label ->
                val text = if (staffSure) label else "$label ?"
                val w = labelPaint.measureText(text)
                val x = rect.left + 8f
                val y = (rect.top - 8f).coerceAtLeast(labelPaint.textSize + 8f)
                labelBackground.color = if (staffSure) LABEL_SURE else LABEL_UNSURE
                canvas.drawRect(x - 6f, y - labelPaint.textSize, x + w + 6f, y + 8f, labelBackground)
                canvas.drawText(text, x, y, labelPaint)
            }
        }
        if (placed.isNotEmpty()) {
            canvas.save()
            canvas.concat(bitmapToView)
            for (p in placed) {
                if (p.drawn.isEmpty()) continue
                canvas.save()
                canvas.clipRect(p.placement.left, p.placement.top, p.placement.right, p.placement.bottom)
                for (d in p.drawn) canvas.drawPath(d.path, d.paint)
                canvas.restore()
            }
            canvas.restore()
        }
        if (liveActive) canvas.drawPath(livePath, livePaint)
        if (eraserRadius > 0f) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
    }

    private fun drawnOf(note: ScoreNote, p: PagePlacement): Drawn? = when (note) {
        is ScoreNote.Ink -> {
            val path = Path()
            val pts = note.points
            val mapped = ArrayList<Float>(pts.size)
            for (i in pts.indices step 2) {
                mapped += p.toBitmapX(pts[i])
                mapped += p.toBitmapY(pts[i + 1])
            }
            smoothPath(path, mapped)
            Drawn(path, strokePaint().apply {
                color = note.color
                strokeWidth = (note.widthPt * p.fitScale).coerceAtLeast(1f)
            })
        }
    }

    companion object {
        private const val STAFF_SURE = 0x2242A5F5
        private const val STAFF_UNSURE = 0x33FFA726
        private const val LABEL_SURE = 0xCC1E88E5.toInt()
        private const val LABEL_UNSURE = 0xCCEF6C00.toInt()

        private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        /** 점 사이 가운데를 잇는 2차 곡선 — 손 떨림 꺾임이 덜 보인다. 점 하나면 점 */
        private fun smoothPath(path: Path, pts: List<Float>) {
            path.reset()
            val n = pts.size / 2
            if (n == 0) return
            path.moveTo(pts[0], pts[1])
            if (n == 1) {
                path.lineTo(pts[0] + 0.1f, pts[1])
                return
            }
            for (i in 1 until n - 1) {
                val x = pts[2 * i]
                val y = pts[2 * i + 1]
                val nx = pts[2 * i + 2]
                val ny = pts[2 * i + 3]
                path.quadTo(x, y, (x + nx) / 2, (y + ny) / 2)
            }
            path.lineTo(pts[2 * n - 2], pts[2 * n - 1])
        }
    }
}
