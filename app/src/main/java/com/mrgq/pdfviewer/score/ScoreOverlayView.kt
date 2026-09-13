package com.mrgq.pdfviewer.score

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * PDF ImageView 위에 마디 박스를 그리는 투명 뷰.
 *
 * - 모든 마디 박스 (악보 분석 확인용, PDF 표시 옵션에서 켬)
 * - **강조 마디 하나** — 메트로놈 악보 연동의 시작 마디 커서([FocusStyle.CURSOR])와 연주 중 현재 마디([FocusStyle.CURRENT])
 *
 * 박스는 표시 비트맵 픽셀 좌표로 받고([ScoreOverlayGeometry]), ImageView 의 imageMatrix 를 그대로
 * 적용해 화면에 맞춘다. 이 뷰는 ImageView 와 같은 위치·크기(match_parent)여야 한다.
 */
class ScoreOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class FocusStyle {
        /** 시작 마디 고르기·예비박 — 파란 테두리 */
        CURSOR,
        /** 연주 중 현재 마디 — 노란 채움 */
        CURRENT,
    }

    private var boxes: List<OverlayBox> = emptyList()
    private var focus: OverlayBox? = null
    private var focusStyle = FocusStyle.CURRENT
    private val bitmapToView = Matrix()
    private val rect = RectF()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBackground = Paint().apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val focusFill = Paint().apply { style = Paint.Style.FILL }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
    }

    fun show(
        boxes: List<OverlayBox>,
        imageMatrix: Matrix,
        focus: OverlayBox? = null,
        focusStyle: FocusStyle = FocusStyle.CURRENT,
    ) {
        this.boxes = boxes
        this.focus = focus
        this.focusStyle = focusStyle
        bitmapToView.set(imageMatrix)
        visibility = if (boxes.isEmpty() && focus == null) GONE else VISIBLE
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        focus = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            mapToView(box)
            val color = SYSTEM_COLORS[box.systemIndex % SYSTEM_COLORS.size]
            boxPaint.color = color
            canvas.drawRect(rect, boxPaint)
            drawLabel(canvas, box.measureNumber, color)
        }

        val current = focus ?: return
        mapToView(current)
        when (focusStyle) {
            FocusStyle.CURSOR -> {
                focusFill.color = CURSOR_FILL
                focusStroke.color = CURSOR_STROKE
            }
            FocusStyle.CURRENT -> {
                focusFill.color = CURRENT_FILL
                focusStroke.color = CURRENT_STROKE
            }
        }
        canvas.drawRect(rect, focusFill)
        canvas.drawRect(rect, focusStroke)
        drawLabel(canvas, current.measureNumber, focusStroke.color)
    }

    private fun mapToView(box: OverlayBox) {
        rect.set(box.left, box.top, box.right, box.bottom)
        bitmapToView.mapRect(rect)
        rect.inset(2f, 2f) // 이웃 마디 박스와 테두리가 겹치지 않게
    }

    private fun drawLabel(canvas: Canvas, number: Int, color: Int) {
        val label = number.toString()
        labelBackground.color = color
        canvas.drawRect(
            rect.left, rect.top,
            rect.left + labelPaint.measureText(label) + 10f, rect.top + labelPaint.textSize + 6f,
            labelBackground,
        )
        canvas.drawText(label, rect.left + 5f, rect.top + labelPaint.textSize, labelPaint)
    }

    private companion object {
        /** 시스템마다 번갈아 — 줄이 바뀌는 곳이 보이게. */
        val SYSTEM_COLORS = intArrayOf(0xCC1E88E5.toInt(), 0xCCE53935.toInt())
        val CURSOR_FILL = 0x331E88E5
        val CURSOR_STROKE = 0xFF1E88E5.toInt()
        val CURRENT_FILL = 0x4DFFC107
        val CURRENT_STROKE = 0xFFFF9800.toInt()
    }
}
