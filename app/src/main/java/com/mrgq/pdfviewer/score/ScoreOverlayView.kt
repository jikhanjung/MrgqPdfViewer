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
 * PDF ImageView 위에 마디 박스를 그리는 투명 뷰 (악보 분석 확인용).
 *
 * 박스는 표시 비트맵 픽셀 좌표로 받고([ScoreOverlayGeometry]), ImageView 의 imageMatrix 를 그대로
 * 적용해 화면에 맞춘다. 이 뷰는 ImageView 와 같은 위치·크기(match_parent)여야 한다.
 */
class ScoreOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var boxes: List<OverlayBox> = emptyList()
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

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
    }

    fun show(boxes: List<OverlayBox>, imageMatrix: Matrix) {
        this.boxes = boxes
        bitmapToView.set(imageMatrix)
        visibility = if (boxes.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            rect.set(box.left, box.top, box.right, box.bottom)
            bitmapToView.mapRect(rect)
            rect.inset(2f, 2f) // 이웃 마디 박스와 테두리가 겹치지 않게

            val color = SYSTEM_COLORS[box.systemIndex % SYSTEM_COLORS.size]
            boxPaint.color = color
            canvas.drawRect(rect, boxPaint)

            val label = box.measureNumber.toString()
            labelBackground.color = color
            canvas.drawRect(
                rect.left, rect.top,
                rect.left + labelPaint.measureText(label) + 10f, rect.top + labelPaint.textSize + 6f,
                labelBackground,
            )
            canvas.drawText(label, rect.left + 5f, rect.top + labelPaint.textSize, labelPaint)
        }
    }

    private companion object {
        /** 시스템마다 번갈아 — 줄이 바뀌는 곳이 보이게. */
        val SYSTEM_COLORS = intArrayOf(0xCC1E88E5.toInt(), 0xCCE53935.toInt())
    }
}
