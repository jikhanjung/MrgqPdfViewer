package com.mrgq.pdfviewer.score

/**
 * 파트보 보기에서 고른 보표들 ↔ 저장 값(비트 마스크, `user_preferences.partStaff`, v17). 보표 k = 1 shl k — 63개까지.
 * 빈 집합은 전체 악보(null)로 저장한다.
 */
object PartStaves {
    const val MAX_STAVES = 63

    fun toMask(staves: Set<Int>?): Long? =
        staves?.filter { it in 0 until MAX_STAVES }?.fold(0L) { mask, k -> mask or (1L shl k) }?.takeIf { it != 0L }

    fun fromMask(mask: Long?): Set<Int>? =
        mask?.let { m -> (0 until MAX_STAVES).filter { m and (1L shl it) != 0L }.toSet() }?.takeIf { it.isNotEmpty() }
}
