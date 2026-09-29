package com.mrgq.pdfviewer.follow

/**
 * 추정 위치 → **반 쪽 넘김 · 쪽 넘김** 결정 (P10 §3.1 넘김 판단 · §3.3.1). 순수 로직.
 *
 * - 쪽 넘김: 추정 위치가 **지금 쪽 밖**(다음 쪽 마디 — 곧 지금 쪽 마지막 마디 끝을 지남)에 [holdMs] 머무르면 그 쪽으로.
 *   P08 §7-2 "쪽 끝 + 1초 머무름" — 일찍 넘겨 치던 마디를 가리지 않게 늦은 쪽으로 기운 규칙. 재위치로 멀리 뛴 경우도 같은 규칙
 * - 반 쪽 넘김: 추정 위치가 지금 쪽 **아래 절반**(시스템 위끝이 쪽 높이 절반 아래) 첫 마디 이후에 [holdMs] 머무르면 위 절반을 다음 쪽으로
 *
 * 마디는 **MusicXML 마디 순서**(0부터, [ScoreChroma] 와 같다)로 받는다.
 */
class PageTurnDecider(
    /** 마디 순서 → 쪽 (0부터) */
    private val pageOf: IntArray,
    /** 마디 순서 → 아래 절반 시스템에 있는가 */
    private val lowerHalf: BooleanArray,
    startPage: Int,
    private val holdMs: Long = 1000L,
) {
    sealed class Turn {
        /** 위 절반만 [nextPage] 의 위 절반으로 (아래는 [page] 그대로) */
        data class Half(val page: Int, val nextPage: Int) : Turn()
        /** 온전히 [page] 로 */
        data class Page(val page: Int) : Turn()
    }

    var page = startPage
        private set
    var halfShown = false
        private set

    private var pendingPage = -1
    private var pendingSince = 0L
    private var halfSince = -1L

    /** 첫 · 마지막 쪽 */
    private val lastPage = pageOf.maxOrNull() ?: 0

    /** 쪽 [p] 의 아래 절반 첫 마디 순서 (없으면 -1) */
    private fun halfPointOf(p: Int): Int {
        for (i in pageOf.indices) if (pageOf[i] == p && lowerHalf[i]) return i
        return -1
    }

    private var halfPoint = halfPointOf(page)

    /**
     * 추정 위치 [measurePos](연속 마디, 0부터) 를 시각 [nowMs] 에 → 할 넘김 (없으면 null)
     */
    fun update(measurePos: Double, nowMs: Long): Turn? {
        val index = measurePos.measureIndex().coerceIn(0, pageOf.size - 1)
        val target = pageOf[index]
        if (target != page) {
            halfSince = -1
            if (target != pendingPage) {
                pendingPage = target
                pendingSince = nowMs
            }
            if (nowMs - pendingSince >= holdMs) {
                moveTo(target)
                return Turn.Page(target)
            }
            return null
        }
        pendingPage = -1
        if (!halfShown && halfPoint >= 0 && page < lastPage && index >= halfPoint) {
            if (halfSince < 0) halfSince = nowMs
            if (nowMs - halfSince >= holdMs) {
                halfShown = true
                return Turn.Half(page, page + 1)
            }
        } else if (!halfShown) {
            halfSince = -1
        }
        return null
    }

    /** 손으로 넘겼거나 다시 맞출 때 — [p] 쪽 온전히 */
    fun moveTo(p: Int) {
        page = p
        halfShown = false
        halfPoint = halfPointOf(p)
        pendingPage = -1
        halfSince = -1
    }
}
