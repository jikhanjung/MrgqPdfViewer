package com.mrgq.pdfviewer.score

/** 잘라 낼 사각형 하나 — PDF 포인트, 쪽 좌상단 원점, 위→아래 */
data class ClipRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 파트보 조각을 **소속에 따라 넓히기** (P07). 기본 띠는 이웃 보표와의 가운데에서 자르는데, 그러면 가운데선을 걸친 슬러 · 붙임줄 · 빔과
 * 덧줄이 많은 음이 잘린다. 몰다우 총보(42쪽)에서 가운데선을 걸친 그림이 415개(슬러 250 · 붙임줄 70 · 빔 64 …), 가운데선을 넘는
 * 덧줄 음이 11개였다 (2026-09-28, PyMuPDF 로 셈).
 *
 * 규칙 — 넓힌 부분만 [ClipRect] 로 내놓는다 (기본 띠와 합쳐 자른다):
 *  - **걸친 그림**: 띠 경계선을 가로지르는 경로는 **가운데가 띠 쪽에 있으면** 이 보표 것 — 그 경로 영역을 더한다.
 *    가운데가 바깥이면 이웃 것이라 더하지 않는다 (이웃 띠에서 넓혀진다)
 *  - **덧줄 기둥**: 보표 맨 위 · 맨 아래 줄에서 오선 간격마다 이어지는 짧은 가로선은 이 보표의 덧줄 — 끝 덧줄 너머 음표 머리(한 칸)까지 더한다
 *  - 넓혀도 **이웃 보표의 오선은 넘지 않는다** (이웃 오선이 들어오면 파트보가 아니다)
 *
 * 경로 박스는 해석기([PathContentInterpreter])가 낸 그대로(쪽 crop 기준, 아래→위)를 받는다. 텍스트(셈여림 글자 · 가사)는 보지 않는다.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object PartClip {

    /** 이보다 큰 경로는 보지 않는다 — 마디선 · 괄호처럼 여러 보표에 걸친 것 */
    private const val MAX_HEIGHT = 60f
    private const val MAX_WIDTH = 400f
    private const val PAD = 1f
    /** 덧줄: 오선 두께 정도의 짧은 가로선 */
    private const val LEDGER_MAX_HEIGHT = 1.5f
    private const val LEDGER_MIN_WIDTH = 4f
    private const val LEDGER_MAX_WIDTH = 20f
    /** 덧줄 높이 허용 오차 (오선 간격 배수) */
    private const val LEDGER_Y_TOL = 0.2f

    /**
     * @param boxes 이 쪽의 경로 박스 (crop 기준, 아래→위)
     * @param pageHeight 쪽 높이 (crop)
     * @param bands 이 시스템의 보표 띠 (위→아래, 위 보표부터)
     * @param firstStaff 조각의 첫 보표 · [lastStaff] 끝 보표 (이웃한 파트 여럿을 한 조각으로 이으면 다르다) — 순번
     * @param top 기본 띠 위 · [bottom] 아래 · [left] 왼쪽 · [right] 오른쪽 (위→아래 좌표)
     */
    fun extras(
        boxes: List<PathBox>,
        pageHeight: Float,
        bands: List<Pair<Float, Float>>,
        firstStaff: Int,
        lastStaff: Int,
        top: Float,
        bottom: Float,
        left: Float,
        right: Float,
    ): List<ClipRect> {
        val upperLimit = if (firstStaff > 0) bands[firstStaff - 1].second + PAD else 0f
        val lowerLimit = if (lastStaff < bands.size - 1) bands[lastStaff + 1].first - PAD else pageHeight
        val out = ArrayList<ClipRect>()

        // 위→아래로 바꾼 박스 (시스템 가로 범위 안, 너무 큰 것 빼고)
        data class Box(val x0: Float, val t: Float, val x1: Float, val b: Float)
        val candidates = boxes.mapNotNull { box ->
            if (box.height >= MAX_HEIGHT || box.width >= MAX_WIDTH || box.x1 < left || box.x0 > right) null
            else Box(box.x0, pageHeight - box.y1, box.x1, pageHeight - box.y0)
        }

        // 걸친 그림
        for (box in candidates) {
            val center = (box.t + box.b) / 2
            if (box.t < top && box.b > top && center >= top) {
                out += ClipRect(box.x0 - PAD, maxOf(box.t - PAD, upperLimit), box.x1 + PAD, top)
            }
            if (box.t < bottom && box.b > bottom && center <= bottom) {
                out += ClipRect(box.x0 - PAD, bottom, box.x1 + PAD, minOf(box.b + PAD, lowerLimit))
            }
        }

        // 덧줄 기둥
        val space = (bands[firstStaff].second - bands[firstStaff].first) / 4
        if (space > 0f) {
            val ledgers = candidates.filter {
                it.b - it.t < LEDGER_MAX_HEIGHT && (it.x1 - it.x0) in LEDGER_MIN_WIDTH..LEDGER_MAX_WIDTH &&
                    bands.none { (bt, bb) -> (it.t + it.b) / 2 in (bt - space * LEDGER_Y_TOL)..(bb + space * LEDGER_Y_TOL) }
            }
            fun ledgerAt(y: Float, x0: Float, x1: Float) = ledgers.firstOrNull {
                kotlin.math.abs((it.t + it.b) / 2 - y) < space * LEDGER_Y_TOL && it.x0 < x1 && it.x1 > x0
            }
            for (direction in intArrayOf(-1, 1)) {
                val edge = if (direction < 0) bands[firstStaff].first else bands[lastStaff].second
                for (first in ledgers) {
                    if (kotlin.math.abs((first.t + first.b) / 2 - (edge + direction * space)) >= space * LEDGER_Y_TOL) continue
                    var x0 = first.x0
                    var x1 = first.x1
                    var n = 1
                    while (true) {
                        val next = ledgerAt(edge + direction * (n + 1) * space, x0, x1) ?: break
                        x0 = minOf(x0, next.x0)
                        x1 = maxOf(x1, next.x1)
                        n++
                    }
                    // 끝 덧줄 너머 칸의 음표 머리까지
                    val far = edge + direction * (n + 1) * space
                    if (direction < 0 && far < top) {
                        out += ClipRect(x0 - PAD, maxOf(far - PAD, upperLimit), x1 + PAD, top)
                    } else if (direction > 0 && far > bottom) {
                        out += ClipRect(x0 - PAD, bottom, x1 + PAD, minOf(far + PAD, lowerLimit))
                    }
                }
            }
        }
        return out.filter { it.bottom > it.top && it.right > it.left }
    }
}
