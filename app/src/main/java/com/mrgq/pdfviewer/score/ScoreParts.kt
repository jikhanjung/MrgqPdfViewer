package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreStaff

/** 파트 하나 — 보표 순번 [staffIndex] (위부터 0). [named] 가 false 면 이름을 못 읽어 "보표 n" 으로 부른 것 */
data class ScorePart(val staffIndex: Int, val name: String, val named: Boolean)

/**
 * 총보의 파트 목록 (P07 §2.1, 1차: **파트 = 보표 순번**). 파트보 보기는 이것으로 파트를 고른다.
 *
 * 모든 시스템의 보표 수가 같아야 순번이 곧 파트다. "빈 보표 숨기기"로 조판해 시스템마다 보표 수가 다르면
 * [Result.VaryingStaves] — 1차에서는 파트 보기를 켜지 않는다(보표 이름으로 맞추는 건 P07 §4 열린 질문 1).
 * 보표가 하나뿐이면 이미 파트보라 [Result.SingleStaff].
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object ScoreParts {

    sealed interface Result {
        /** 분석한 보표가 없다 (악보가 아니거나 분석하지 못한 PDF) */
        data object NoStaves : Result

        /** 보표가 하나 — 떼어 볼 파트가 없다 */
        data object SingleStaff : Result

        /** 시스템마다 보표 수가 다르다 ([counts] 는 나온 보표 수들) */
        data class VaryingStaves(val counts: Set<Int>) : Result

        data class Parts(val parts: List<ScorePart>) : Result
    }

    /** 로그용 한 줄 */
    fun Result.summary(): String = when (this) {
        Result.NoStaves -> "보표 없음"
        Result.SingleStaff -> "보표 하나"
        is Result.VaryingStaves -> "시스템마다 보표 수가 다름 ${counts.sorted()}"
        is Result.Parts -> "${parts.size}개 [${parts.joinToString { it.name }}]"
    }

    fun of(staves: List<ScoreStaff>): Result {
        if (staves.isEmpty()) return Result.NoStaves
        val systems = staves.groupBy { it.pageIndex to it.systemIndex }
        val counts = systems.values.map { it.size }.toSet()
        if (counts.size > 1) return Result.VaryingStaves(counts)
        val count = counts.single()
        if (count == 1) return Result.SingleStaff
        // 이름은 문서 앞쪽 시스템부터 — 첫 시스템의 긴 이름을 짧은 이름보다 먼저 쓴다
        val ordered = staves.sortedWith(compareBy({ it.pageIndex }, { it.systemIndex }, { it.staffIndex }))
        return Result.Parts((0 until count).map { k ->
            val label = ordered.firstOrNull { it.staffIndex == k && !it.label.isNullOrBlank() }?.label
            ScorePart(k, label ?: "보표 ${k + 1}", named = label != null)
        })
    }
}
