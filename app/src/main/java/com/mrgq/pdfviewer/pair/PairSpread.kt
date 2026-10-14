package com.mrgq.pdfviewer.pair

import com.mrgq.pdfviewer.follow.RollingTurns.Spread

/** 짝에서 이 기기의 자리 (P22) — 왼쪽은 펼침의 [Spread.left], 오른쪽은 [Spread.right] */
enum class PairSide(val wire: String) {
    LEFT("left"), RIGHT("right");

    val other: PairSide get() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(wire: String?): PairSide? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 두 대로 펼쳐 보기의 펼침 계산 (P22) — TV 두 쪽 모드와 같은 짝(1 | 2 · 3 | 4, 쪽은 순번 0부터)을 두 기기가 한 쪽씩 맡는다.
 * 주 기기만 펼침을 정하고 짝 기기는 받은 펼침의 자기 자리만 그린다. Android 에 의존하지 않는다 — JVM 단위 테스트 대상
 */
object PairSpread {

    /** [spread] 에서 [side] 자리의 쪽 — 홀수 쪽 끝의 오른쪽이면 없음(빈 화면) */
    fun pageOf(spread: Spread, side: PairSide): Int? = if (side == PairSide.LEFT) spread.left else spread.right

    /**
     * [page] 쪽을 보이려 할 때의 펼침 — 지금 펼침 [current] 에 이미 있으면 그대로(차례 넘김 3 | 2 를 깨지 않는다), 없으면 그 쪽이 든 보통의 짝
     */
    fun toShow(page: Int, current: Spread?, pageCount: Int): Spread =
        current?.takeIf { it.shows(page) } ?: Spread.pairOf(page, pageCount)

    /** → : 지금 펼침에서 가장 뒤 쪽의 짝 다음 짝 (TV 의 `pageIndex + 2`), 끝이면 없음 */
    fun next(current: Spread, pageCount: Int): Spread? {
        val last = maxOf(current.left, current.right ?: current.left)
        val start = last - last % 2 + 2
        return if (start < pageCount) Spread.pairOf(start, pageCount) else null
    }

    /** ← : 지금 펼침에서 가장 뒤 쪽의 짝 앞 짝 (차례 넘김 3 | 2 면 1 | 2), 처음이면 없음 */
    fun previous(current: Spread, pageCount: Int): Spread? {
        val last = maxOf(current.left, current.right ?: current.left)
        val start = last - last % 2 - 2
        return if (start >= 0) Spread.pairOf(start, pageCount) else null
    }
}
