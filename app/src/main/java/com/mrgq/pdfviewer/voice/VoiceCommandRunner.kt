package com.mrgq.pdfviewer.voice

import com.mrgq.pdfviewer.metronome.MetronomeClock
import com.mrgq.pdfviewer.voice.ParseResult.Reason
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoPage
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoRehearsalMark
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.SetCountIn
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectParts
import com.mrgq.pdfviewer.voice.VoiceCommand.SetTempo

/**
 * 읽은 결과([ParseResult]) → 명령 층([ViewerCommands]) 실행. 화면에 보일 한 줄을 돌려준다.
 *
 * - 값 범위(템포 20 ~ 240, 마디 · 쪽 1 이상)는 **아무것도 하기 전에** 본다 — 하나라도 벗어나면 하나도 실행하지 않는다
 * - 명령은 [ParseResult.Commands] 의 순서(템포 · 파트 → 위치 → 시작)대로, 하나가 실패하면 거기서 멈추고 앞에서 한 것을 알린다
 * - 수만 들렸으면([ParseResult.BareNumber]) 메트로놈 대화상자가 열려 있을 때는 템포, 아니면 마디 고르기 (P12 §3.2)
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object VoiceCommandRunner {

    data class Report(val ok: Boolean, val message: String)

    suspend fun run(result: ParseResult, target: ViewerCommands, bareNumberIsTempo: Boolean = false): Report = when (result) {
        is ParseResult.Unrecognized -> Report(false, reasonMessage(result.reason))
        is ParseResult.BareNumber -> run(
            ParseResult.Commands(listOf(if (bareNumberIsTempo) SetTempo(result.value) else SelectMeasure(result.value))),
            target,
        )
        is ParseResult.Commands -> execute(result, target)
    }

    private suspend fun execute(result: ParseResult.Commands, target: ViewerCommands): Report {
        result.commands.firstNotNullOfOrNull(::rangeError)?.let { return Report(false, it) }
        val done = mutableListOf<VoiceCommand>()
        for (command in result.commands) {
            val outcome = dispatch(command, target)
            if (outcome is CommandOutcome.Failed) {
                val before = ParseResult.Commands(done).label
                return Report(false, if (done.isEmpty()) outcome.message else "$before 했지만 — ${outcome.message}")
            }
            done += command
        }
        return Report(true, result.label)
    }

    private suspend fun dispatch(command: VoiceCommand, target: ViewerCommands): CommandOutcome = when (command) {
        is SelectMeasure -> target.selectMeasure(command.measure)
        is GotoMeasure -> target.gotoMeasure(command.measure)
        VoiceCommand.GotoStart -> target.gotoStart()
        VoiceCommand.Restart -> target.restart()
        VoiceCommand.Resume -> target.resume()
        is GotoRehearsalMark -> target.gotoRehearsalMark(command.mark)
        VoiceCommand.NextPage -> target.nextPage()
        VoiceCommand.PreviousPage -> target.previousPage()
        is GotoPage -> target.gotoPage(command.page)
        is SetTempo -> target.setTempo(command.bpm)
        is SetCountIn -> target.setCountIn(command.bars)
        is SelectParts -> target.selectParts(command.parts)
        VoiceCommand.ShowFullScore -> target.showFullScore()
        VoiceCommand.Start -> target.start()
    }

    private fun rangeError(command: VoiceCommand): String? = when {
        command is SetTempo && command.bpm !in MetronomeClock.MIN_BPM..MetronomeClock.MAX_BPM ->
            "템포 ${command.bpm} 은 쓸 수 없어요 (${MetronomeClock.MIN_BPM} ~ ${MetronomeClock.MAX_BPM})"
        command is SetCountIn && command.bars !in 1..2 -> "예비박은 한 마디나 두 마디만 돼요"
        command is GotoMeasure && command.measure < 1 -> "${command.measure}마디는 없어요"
        command is SelectMeasure && command.measure < 1 -> "${command.measure}마디는 없어요"
        command is GotoPage && command.page < 1 -> "${command.page}쪽은 없어요"
        else -> null
    }

    fun reasonMessage(reason: Reason): String = when (reason) {
        Reason.NOTHING -> "명령을 찾지 못했어요"
        Reason.NEGATION -> "'말고 · 아니 · 취소'가 있어 실행하지 않았어요"
        Reason.RELATIVE -> "'두 마디 전'처럼 상대 위치는 아직 몰라요 — 마디 번호로 말하세요"
        Reason.CONFLICT -> "위치나 템포가 둘이에요 — 하나만 말하세요"
        Reason.STRAY_NUMBER -> "무엇의 수인지 모르는 수가 있어요 — '57마디'처럼 단위를 붙이세요"
    }
}
