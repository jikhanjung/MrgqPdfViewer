package com.mrgq.pdfviewer.voice

/** 명령 하나를 실행한 결과 — 실패면 화면에 보일 이유("57마디가 없어요 (1 ~ 120)") */
sealed class CommandOutcome {
    object Done : CommandOutcome()
    data class Failed(val message: String) : CommandOutcome()
}

/**
 * 명령 층 (P12 §5) — 악보 화면이 할 수 있는 일, **명령 하나 = 함수 하나**. 음성 · 글자 입력(그리고 나중의 에이전트)이 같은 길로
 * 들어온다. 값이 지금 악보 · 상태에 맞는지(마디가 있나, 합주 연주자인가 — P12 §3.3)는 구현(악보 화면)이 검사해 [CommandOutcome.Failed] 로 돌려준다.
 *
 * 마디로 가는 명령은 멈춰 있어도 **마디 고르기 없이 바로** 그 마디에서 악보 연동을 시작한다(예비박 뒤).
 */
interface ViewerCommands {
    suspend fun gotoMeasure(measure: Int): CommandOutcome
    /** 첫 시작 가능 마디부터 */
    suspend fun gotoStart(): CommandOutcome
    /** 이 곡에서 마지막으로 시작한 마디부터 */
    suspend fun restart(): CommandOutcome
    /** 멈춘 마디부터 */
    suspend fun resume(): CommandOutcome
    suspend fun gotoRehearsalMark(mark: String): CommandOutcome
    suspend fun nextPage(): CommandOutcome
    suspend fun previousPage(): CommandOutcome
    /** [page] 는 원본 쪽 번호(1부터) — 파트 보기면 그 쪽이 놓인 파트 쪽으로 */
    suspend fun gotoPage(page: Int): CommandOutcome
    suspend fun setTempo(bpm: Int): CommandOutcome
    suspend fun selectParts(parts: List<PartRef>): CommandOutcome
    suspend fun showFullScore(): CommandOutcome
    /** 메트로놈 시작 — 지금처럼 시작 마디 고르기로 */
    suspend fun start(): CommandOutcome
}
