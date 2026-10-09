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
 * 시작하는 명령(마디부터 · 처음부터 · 다시 · 이어서 · 시작)은 **마디 고르기 없이 바로** 그 마디에서 악보 연동을 시작한다(예비박 뒤).
 * 마디만 말하면([selectMeasure]) 시작 마디로 고르기만 한다.
 */
interface ViewerCommands {
    /** 시작 마디로 고르기만 — 이미 고르는 중이면 커서를 옮긴다 */
    suspend fun selectMeasure(measure: Int): CommandOutcome
    /** 그 마디에서 예비박 뒤 바로 */
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
    /** 악보 연동 예비박 마디 수(1 · 2) — 전역 설정, 다음 시작부터 */
    suspend fun setCountIn(bars: Int): CommandOutcome
    suspend fun selectParts(parts: List<PartRef>): CommandOutcome
    suspend fun showFullScore(): CommandOutcome
    /** 고른 마디(고르는 중이 아니면 지금 쪽 첫 마디)에서 예비박 뒤 바로. 마디를 못 읽은 악보면 악보 연동 없이 메트로놈만 */
    suspend fun start(): CommandOutcome
    /** 연주를 멈춘다 — 합주 지휘자면 연주자도, 연주자면 이 기기만 빠진다. 시작 마디를 고르는 중이면 고르기를 접는다 */
    suspend fun stop(): CommandOutcome
}
