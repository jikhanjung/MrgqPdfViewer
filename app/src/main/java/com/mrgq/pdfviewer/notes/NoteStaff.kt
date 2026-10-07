package com.mrgq.pdfviewer.notes

import com.mrgq.pdfviewer.database.entity.ScoreStaff
import kotlin.math.abs

/**
 * 메모가 **어느 보표(파트)에 붙는가** (P11 §3.6) — 나중에 파트보에서 그 파트 화면으로 옮기려고 쓸 때 정해 둔다.
 * 긋는 동안 그 보표 띠를 연하게 칠하고 파트 이름을 보여 줘 애매하면 쓰는 사람이 알 수 있게 한다.
 *
 * 규칙 (획의 위 · 아래 가운데 y 로):
 *  - 오선 안(보표 띠 위 ~ 아래 줄)이면 그 보표 — **분명**
 *  - 아니면 가장 가까운 보표. 두 번째로 가까운 보표와 거리가 비슷하면(차이가 둘 합의 [AMBIGUOUS] 미만) **애매**
 *  - 가장 가까운 보표도 보표 높이의 [FAR] 배보다 멀면(쪽 위 제목 · 여백 글씨) 어느 파트도 아님 → null (전체 악보 메모)
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object NoteStaff {

    const val AMBIGUOUS = 0.3f
    const val FAR = 3f

    data class Attachment(val staff: ScoreStaff, val sure: Boolean)

    /** [staves] 는 그 쪽의 보표들 (다른 쪽 것이 섞여 있어도 [page] 로 거른다) */
    fun of(staves: List<ScoreStaff>, page: Int, top: Float, bottom: Float): Attachment? {
        val onPage = staves.filter { it.pageIndex == page }
        if (onPage.isEmpty()) return null
        val y = (top + bottom) / 2
        fun distance(s: ScoreStaff) = when {
            y < s.topPt -> s.topPt - y
            y > s.bottomPt -> y - s.bottomPt
            else -> 0f
        }
        val ranked = onPage.sortedBy { distance(it) }
        val first = ranked[0]
        val d1 = distance(first)
        if (d1 == 0f) return Attachment(first, sure = true)
        val height = (first.bottomPt - first.topPt).coerceAtLeast(1f)
        if (d1 > height * FAR) return null
        val d2 = ranked.getOrNull(1)?.let { distance(it) } ?: return Attachment(first, sure = true)
        return Attachment(first, sure = abs(d2 - d1) >= AMBIGUOUS * (d1 + d2))
    }

    /** 표시 이름 — 보표 이름이 있으면 그것, 없으면 "보표 n" (1부터, 파트 보기 `Pt. n` 과 같은 번호) */
    fun labelOf(staff: ScoreStaff): String = staff.label?.takeIf { it.isNotBlank() } ?: "보표 ${staff.staffIndex + 1}"
}
