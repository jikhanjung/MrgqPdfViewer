package com.mrgq.pdfviewer.metronome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 메트로놈 박 표시 — 화면 모서리의 작은 알약 모양: "♩ 120" + 마디당 박 수만큼의 점.
 * 지금 박의 점이 켜진다 (첫 박은 주황, 나머지는 초록). 악보를 가리지 않게 작고 반투명하다.
 */
class MetronomeBeatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bpm = MetronomeClock.DEFAULT_BPM
    private var beatsPerBar = MetronomeClock.DEFAULT_BEATS
    private var currentIndex = -1

    private val density = resources.displayMetrics.density
    private val dotRadius = 7f * density
    private val dotGap = 8f * density
    private val padding = 12f * density
    private val heightPx = 40f * density

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val idleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x99FFFFFF.toInt()
    }
    private val activeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
        // 박이 많은 박자(6/8 등)에서는 알약이 길어져 악보 왼쪽 위(제목 등)를 가린다 — 반투명으로 비치게 (사용자 결정)
        alpha = VIEW_ALPHA
    }

    /** 바뀐 것이 있을 때만 다시 그린다 (매 프레임 불린다). */
    fun update(beat: Beat?, bpm: Int, beatsPerBar: Int) {
        val index = beat?.indexInBar ?: -1
        if (index == currentIndex && bpm == this.bpm && beatsPerBar == this.beatsPerBar) return
        val sizeChanged = beatsPerBar != this.beatsPerBar || bpm.toString().length != this.bpm.toString().length
        currentIndex = index
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        if (sizeChanged) requestLayout()
        invalidate()
    }

    private fun label() = "♩ $bpm"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = padding + textPaint.measureText(label()) + padding +
            beatsPerBar * (dotRadius * 2) + (beatsPerBar - 1) * dotGap + padding
        setMeasuredDimension(width.toInt(), heightPx.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, background)

        val text = label()
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, padding, baseline, textPaint)

        var cx = padding + textPaint.measureText(text) + padding + dotRadius
        val cy = height / 2f
        for (i in 0 until beatsPerBar) {
            if (i == currentIndex) {
                activeDot.color = if (i == 0) ACCENT_COLOR else BEAT_COLOR
                canvas.drawCircle(cx, cy, dotRadius, activeDot)
            } else {
                canvas.drawCircle(cx, cy, dotRadius - idleDot.strokeWidth / 2f, idleDot)
            }
            cx += dotRadius * 2 + dotGap
        }
    }

    private companion object {
        const val VIEW_ALPHA = 0.6f
        val ACCENT_COLOR = 0xFFFF9800.toInt()
        val BEAT_COLOR = 0xFF4CAF50.toInt()
    }
}
