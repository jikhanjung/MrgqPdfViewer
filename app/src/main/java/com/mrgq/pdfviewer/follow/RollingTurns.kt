package com.mrgq.pdfviewer.follow

/**
 * 두 쪽으로 보는 연주자의 **차례 넘김** (P10 §3.3) — 지휘자가 마이크 추적으로 넘긴 쪽(`page_change.roll`)을 받으면
 * 다 친 쪽 자리만 그 쪽 + 2 로 바꾼다. 사람의 넘김을 흉내 내지 않는다(사용자, 2026-09-29).
 *
 * 자리 = 쪽 홀짝: 왼쪽은 늘 홀수 쪽(순번 0 · 2 · 4 …), 오른쪽은 짝수 쪽(순번 1 · 3 …). 그래서 1 | 2 → 3 | 2 → 3 | 4 → 5 | 4 …
 * 쪽은 모두 **순번(0부터)**.
 */
object RollingTurns {
    /** 화면의 두 쪽 — [left] 는 짝수 순번, [right] 는 홀수 순번 (마지막 쪽이면 없음) */
    data class Spread(val left: Int, val right: Int?) {
        fun shows(page: Int) = page == left || page == right

        /** 보통의 짝(1-2 · 3-4 …)인가 */
        val isPair: Boolean get() = right == left + 1 || (right == null && left % 2 == 0)

        companion object {
            /** [page] 가 든 보통의 짝 */
            fun pairOf(page: Int, pageCount: Int): Spread {
                val left = page - page % 2
                return Spread(left, (left + 1).takeIf { it < pageCount })
            }
        }
    }

    sealed class Action {
        /** 화면에 없는 쪽 — 바로 그 쪽이 든 짝으로 (건너뜀 · 되돌아감 · 처음) */
        data class Immediate(val spread: Spread) : Action()

        /** 조금 기다렸다가 다 친 쪽 자리만 바꾼다 */
        data class Delayed(val spread: Spread) : Action()

        object None : Action()
    }

    /**
     * 지휘자가 [page] 쪽에서 듣고 넘기기를 **시작**했다 (#088) — 기다리지 않고 바로 보일 펼침. 그 쪽이 왼쪽 자리(짝수 순번)면 보통의 짝,
     * 오른쪽 자리면 왼쪽 자리는 이미 지난 쪽이니 다음 쪽으로(순번 2 | 3 에서 3 시작 → 4 | 3). 다음 쪽이 없으면 보통의 짝
     */
    fun startSpread(page: Int, pageCount: Int): Spread {
        if (page % 2 == 0) return Spread.pairOf(page, pageCount)
        val next = page + 1
        return if (next < pageCount) Spread(next, page) else Spread.pairOf(page, pageCount)
    }

    /** 지금 화면 [current] 에서 지휘자가 [page] 쪽에 들어섰다 */
    fun onPage(current: Spread, page: Int, pageCount: Int): Action {
        if (page !in 0 until pageCount) return Action.None
        if (!current.shows(page)) return Action.Immediate(Spread.pairOf(page, pageCount))
        val other = if (page == current.left) current.right else current.left
        // 다른 자리가 이 쪽보다 앞(다 친 쪽)일 때만 그 자리를 이 쪽 + 1 로. 뒤(아직 칠 쪽)면 그대로
        if (other == null || other > page) return Action.None
        val next = page + 1
        if (next >= pageCount) return Action.None
        return Action.Delayed(if (next % 2 == 0) Spread(next, current.right) else Spread(current.left, next))
    }
}
