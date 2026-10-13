package com.mrgq.pdfviewer.metronome

/**
 * 반주 파트 고르기 (P21 1단계) — 곡마다 저장하는 값(`user_preferences.accompanimentParts`, v19):
 * null = **자동**(파트 보기면 내 파트 빼고, 아니면 전체), [ALL] = **전체**(내 파트도 — 파트가 늘어도 전체),
 * 그 밖 = MusicXML 파트 순번 비트 마스크(**직접 고르기**). 직접 고른 마스크에 지금 MusicXML 에 없는 파트가 있으면
 * (동기화로 파트가 줄었다) 자동으로 돌아간다. 빈 마스크(0)는 "이 곡은 반주 없음".
 */
object AccompanimentParts {
    const val ALL = -1L

    enum class Mode { AUTO, ALL, CUSTOM }

    fun mode(choice: Long?, partCount: Int): Mode = when {
        choice == null -> Mode.AUTO
        choice == ALL -> Mode.ALL
        !fits(choice, partCount) -> Mode.AUTO
        else -> Mode.CUSTOM
    }

    /** 반주할 MusicXML 파트 순번. [isMine] = 보고 있는 파트(파트 보기가 아니면 늘 false) */
    fun resolve(choice: Long?, partCount: Int, isMine: (Int) -> Boolean): Set<Int> = when (mode(choice, partCount)) {
        Mode.AUTO -> (0 until partCount).filterNot(isMine).toSet()
        Mode.ALL -> (0 until partCount).toSet()
        Mode.CUSTOM -> (0 until partCount).filter { (choice!! shr it) and 1L == 1L }.toSet()
    }

    fun toMask(parts: Set<Int>): Long = parts.fold(0L) { mask, p -> mask or (1L shl p) }

    private fun fits(mask: Long, partCount: Int) = partCount in 1..63 && mask >= 0 && (mask shr partCount) == 0L
}
