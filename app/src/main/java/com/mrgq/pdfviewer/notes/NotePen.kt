package com.mrgq.pdfviewer.notes

import android.os.SystemClock
import android.view.MotionEvent

/**
 * 악보 메모 터치 입력 (P11 §3.2). 뷰어의 `dispatchTouchEvent` 앞단에서 이벤트를 먼저 보고, 먹은 것([handle] = true)은
 * 넘김 몸짓으로 보내지 않는다.
 *
 *  - **펜**(`TOOL_TYPE_STYLUS`)은 메모 모드가 아니어도 바로 긋는다 (사용자 결정 2026-10-07). 펜 지우개 끝 · 펜 단추 = 지우개
 *  - **손가락**은 메모 모드에서만 긋는다(아니면 지금처럼 넘김 · 메뉴). 메모 모드에서도 화면 가장자리 띠([EDGE]) 탭은 넘김
 *  - 손바닥 무시: 펜이 닿아 있거나 [PALM_MS] 안에 닿았으면 손가락은 버린다
 *  - 손가락으로 긋다가 두 번째 손가락이 닿으면 그 획은 버린다 (1차는 확대 없음)
 *
 * 좌표는 화면(창) 좌표로 모으고, 쪽 pt 로 바꾸는 것은 [Host] 가 한다 (렌더러와 같은 공식, `PagePlacement`).
 */
class NotePen(private val host: Host) {

    interface Host {
        /** 메모를 쓸 수 있는 화면인가 (파트 보기 · 반 쪽 넘김 · 애니메이션 중이면 아님) */
        fun canDraw(): Boolean
        /** 화면 좌표의 쪽 — 쪽 밖이면 null */
        fun pageAt(x: Float, y: Float): Int?
        /** 화면 좌표 → 그 쪽의 pt (x, y) */
        fun toPage(page: Int, x: Float, y: Float): Pair<Float, Float>?
        /** 그 쪽의 화면 px / pt — 굵기 · 지우개 크기 */
        fun pxPerPt(page: Int): Float
        fun screenWidth(): Int
        /** 다 그은 획 (pt, 줄이기 전) */
        fun onStroke(page: Int, points: List<Float>, color: Int, widthPt: Float)
        /** 지우개가 (pt) 를 지남 — 한 번 문지르는 동안 여러 번 */
        fun onErase(page: Int, x: Float, y: Float, radiusPt: Float)
        fun onEraseEnd()
        /** 지금 긋는 획 (화면 좌표) · 그 쪽 pt 의 위 · 아래 — 붙을 보표 표시. points 가 비면 끝 */
        fun onLive(page: Int, points: List<Float>, color: Int, widthPx: Float, topPt: Float, bottomPt: Float)
        fun onLiveEnd()
        fun onEraserCursor(x: Float, y: Float, radiusPx: Float)
    }

    enum class Tool { PEN, ERASER }

    var editMode = false
    var tool = Tool.PEN
    var color = COLORS[0]
    var widthPt = WIDTHS[1]

    private enum class Mode { NONE, PASS, IGNORE, DRAW, ERASE }

    private var mode = Mode.NONE
    private var page = -1
    private var byFinger = false
    private val points = ArrayList<Float>()
    private var lastStylusAt = 0L

    /** true = 메모가 먹었다 (몸짓으로 보내지 않는다) */
    fun handle(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> mode = begin(ev)
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.DRAW && byFinger || mode == Mode.ERASE && byFinger) {
                cancel()
                mode = Mode.IGNORE
            }
            MotionEvent.ACTION_MOVE -> if (mode == Mode.DRAW || mode == Mode.ERASE) {
                for (h in 0 until ev.historySize) add(ev.getHistoricalX(0, h), ev.getHistoricalY(0, h))
                add(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW || mode == Mode.ERASE) {
                    add(ev.x, ev.y)
                    finish()
                }
                return endSequence()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel()
                return endSequence()
            }
        }
        if (isStylus(ev)) lastStylusAt = SystemClock.uptimeMillis()
        return mode != Mode.NONE && mode != Mode.PASS
    }

    private fun endSequence(): Boolean {
        val consumed = mode != Mode.NONE && mode != Mode.PASS
        mode = Mode.NONE
        return consumed
    }

    private fun begin(ev: MotionEvent): Mode {
        val stylus = isStylus(ev)
        if (stylus) lastStylusAt = SystemClock.uptimeMillis()
        if (!host.canDraw()) return Mode.PASS
        if (!stylus) {
            if (!editMode) return Mode.PASS
            if (SystemClock.uptimeMillis() - lastStylusAt < PALM_MS) return Mode.IGNORE
            val edge = host.screenWidth() * EDGE
            if (ev.x < edge || ev.x > host.screenWidth() - edge) return Mode.PASS
        }
        val p = host.pageAt(ev.x, ev.y) ?: return if (stylus || editMode) Mode.IGNORE else Mode.PASS
        page = p
        byFinger = !stylus
        points.clear()
        val erase = ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            (stylus && ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) ||
            (editMode && tool == Tool.ERASER)
        val mode = if (erase) Mode.ERASE else Mode.DRAW
        this.mode = mode
        add(ev.x, ev.y)
        return mode
    }

    private fun add(x: Float, y: Float) {
        if (mode == Mode.ERASE) {
            val scale = host.pxPerPt(page)
            val radiusPt = ERASER_PX / scale
            host.toPage(page, x, y)?.let { (px, py) -> host.onErase(page, px, py, radiusPt) }
            host.onEraserCursor(x, y, ERASER_PX)
            return
        }
        val n = points.size
        if (n >= 2 && points[n - 2] == x && points[n - 1] == y) return
        points += x
        points += y
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        // 보표 표시는 처음 · 끝 · 극값만 있으면 되지만 획이 짧아 그냥 다 본다
        for (i in 1 until points.size step 2) {
            top = minOf(top, points[i])
            bottom = maxOf(bottom, points[i])
        }
        val topPt = host.toPage(page, points[0], top)?.second ?: return
        val bottomPt = host.toPage(page, points[0], bottom)?.second ?: return
        host.onLive(page, points, color, widthPt * host.pxPerPt(page), topPt, bottomPt)
    }

    private fun finish() {
        when (mode) {
            Mode.DRAW -> {
                val pt = ArrayList<Float>(points.size)
                for (i in points.indices step 2) {
                    val (x, y) = host.toPage(page, points[i], points[i + 1]) ?: continue
                    pt += x
                    pt += y
                }
                host.onLiveEnd()
                if (pt.isNotEmpty()) host.onStroke(page, pt, color, widthPt)
            }
            Mode.ERASE -> {
                host.onEraserCursor(0f, 0f, 0f)
                host.onEraseEnd()
            }
            else -> Unit
        }
        points.clear()
    }

    /** 화면이 바뀌기 직전 — 긋던 획은 지금 화면 기준으로 마저 저장하고, 이번 터치의 나머지는 버린다 */
    fun flush() {
        if (mode != Mode.DRAW && mode != Mode.ERASE) return
        finish()
        mode = Mode.IGNORE
    }

    /** 긋던 것을 버린다 (두 손가락 · 파일 바뀜) */
    fun cancel() {
        when (mode) {
            Mode.DRAW -> host.onLiveEnd()
            Mode.ERASE -> {
                host.onEraserCursor(0f, 0f, 0f)
                host.onEraseEnd()
            }
            else -> Unit
        }
        points.clear()
        if (mode != Mode.NONE) mode = Mode.IGNORE
    }

    private fun isStylus(ev: MotionEvent): Boolean {
        val type = ev.getToolType(0)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    companion object {
        /** 펜이 떨어진 뒤 이 시간 동안은 손가락(손바닥)을 버린다 */
        const val PALM_MS = 500L
        /** 메모 모드에서도 넘김 탭으로 남겨 두는 왼 · 오 가장자리 (화면 폭 비율) */
        const val EDGE = 0.08f
        /** 지우개 반지름 (화면 px) */
        const val ERASER_PX = 24f

        /** 검정 · 빨강 · 파랑 */
        val COLORS = intArrayOf(0xFF212121.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt())
        /** 가늘게 · 보통 · 굵게 (pt) — A4 악보의 오선 간격이 보통 6 ~ 8pt */
        val WIDTHS = floatArrayOf(0.8f, 1.5f, 3f)
    }
}
