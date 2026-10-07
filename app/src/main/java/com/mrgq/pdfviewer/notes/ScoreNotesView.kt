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

    private fun interface Drawn {
        fun draw(canvas: Canvas)
    }
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

    /**
     * 지금 화면의 쪽들과 그 쪽 메모. [conductorOf] = 지휘자 메모(앙상블, P11 §6) — 개인 메모 아래에, 연보라 빛 테두리를 둘러
     * 누구 것인지 한눈에 다르게. [imageMatrix] = pdfView 에 실제로 걸린 행렬
     */
    fun show(
        placements: List<PagePlacement>,
        notesOf: (Int) -> List<ScoreNote>,
        conductorOf: (Int) -> List<ScoreNote>,
        imageMatrix: Matrix,
    ) {
        bitmapToView.set(imageMatrix)
        placed = placements.map { p ->
            Placed(p, conductorOf(p.pageIndex).mapNotNull { drawnOf(it, p, halo = true) } + notesOf(p.pageIndex).mapNotNull { drawnOf(it, p) })
        }
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
                for (d in p.drawn) d.draw(canvas)
                canvas.restore()
            }
            canvas.restore()
        }
        selected?.let { (_, box) ->
            rect.set(box)
            bitmapToView.mapRect(rect)
            rect.inset(-8f, -8f)
            canvas.drawRect(rect, selectPaint)
        }
        if (liveActive) canvas.drawPath(livePath, livePaint)
        if (eraserRadius > 0f) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
    }

    private var selected: Pair<PagePlacement, RectF>? = null
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF1E88E5.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    /** 잡은 메모 (옮기기 · 고치기) — 점선 테두리. null 이면 지운다 */
    fun select(placement: PagePlacement?, note: ScoreNote?) {
        selected = if (placement != null && note != null) {
            val b = boundsPt(note)
            placement to RectF(placement.toBitmapX(b.left), placement.toBitmapY(b.top), placement.toBitmapX(b.right), placement.toBitmapY(b.bottom))
        } else null
        invalidate()
    }

    /** [halo] = 지휘자 메모 — 같은 모양을 굵은 연보라로 먼저 깔고 그 위에 */
    private fun drawnOf(note: ScoreNote, p: PagePlacement, halo: Boolean = false): Drawn? = when (note) {
        is ScoreNote.Text -> {
            val paint = textPaint(note.sizePt * p.fitScale).apply { color = note.color }
            val haloPaint = if (halo) textPaint(note.sizePt * p.fitScale).apply {
                color = HALO
                style = Paint.Style.STROKE
                strokeWidth = HALO_PX
                strokeJoin = Paint.Join.ROUND
            } else null
            val x = p.toBitmapX(note.x)
            val lines = TextLayout.lines(note.text)
            Drawn { canvas ->
                lines.forEachIndexed { i, line ->
                    val y = p.toBitmapY(TextLayout.baseline(note, i))
                    haloPaint?.let { canvas.drawText(line, x, y, it) }
                    canvas.drawText(line, x, y, paint)
                }
            }
        }
        is ScoreNote.Ink -> {
            val path = Path()
            for (pts in note.strokes) {
                val mapped = ArrayList<Float>(pts.size)
                for (i in pts.indices step 2) {
                    mapped += p.toBitmapX(pts[i])
                    mapped += p.toBitmapY(pts[i + 1])
                }
                smoothPath(path, mapped, reset = false)
            }
            val paint = strokePaint().apply {
                color = note.color
                strokeWidth = (note.widthPt * p.fitScale).coerceAtLeast(1f)
            }
            val haloPaint = if (halo) strokePaint().apply {
                color = HALO
                strokeWidth = paint.strokeWidth + HALO_PX
            } else null
            Drawn { canvas ->
                haloPaint?.let { canvas.drawPath(path, it) }
                canvas.drawPath(path, paint)
            }
        }
    }

    companion object {
        /** 지휘자 메모 테두리 빛 — 연보라 반투명 (비트맵 px, 화면 배율로 함께 커진다) */
        private const val HALO = 0x667E57C2
        private const val HALO_PX = 5f
        private const val STAFF_SURE = 0x2242A5F5
        private const val STAFF_UNSURE = 0x33FFA726
        private const val LABEL_SURE = 0xCC1E88E5.toInt()
        private const val LABEL_UNSURE = 0xCCEF6C00.toInt()

        private fun textPaint(sizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            typeface = Typeface.DEFAULT_BOLD
        }

        /** 가장 긴 줄의 폭 (pt) — 글자 크기를 pt 로 재면 결과도 pt */
        fun textWidthPt(note: ScoreNote.Text): Float {
            val paint = textPaint(note.sizePt)
            return TextLayout.lines(note.text).maxOf { paint.measureText(it) }
        }

        /** 메모가 차지하는 사각형 (pt) */
        fun boundsPt(note: ScoreNote): RectF = when (note) {
            is ScoreNote.Text -> RectF(note.x, note.y, note.x + textWidthPt(note), note.y + TextLayout.height(note))
            is ScoreNote.Ink -> {
                val all = note.strokes.flatten()
                val xs = all.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = all.filterIndexed { i, _ -> i % 2 == 1 }
                val h = note.widthPt / 2
                RectF(xs.min() - h, ys.min() - h, xs.max() + h, ys.max() + h)
            }
        }

        private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        /** 점 사이 가운데를 잇는 2차 곡선 — 손 떨림 꺾임이 덜 보인다. 점 하나면 점 */
        private fun smoothPath(path: Path, pts: List<Float>, reset: Boolean = true) {
            if (reset) path.reset()
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
