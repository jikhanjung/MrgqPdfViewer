package com.mrgq.pdfviewer.voice

import com.mrgq.pdfviewer.R
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
 * 읽은 결과([ParseResult]) → 명령 층([ViewerCommands]) 실행. 화면에 보일 한 줄을 돌려준다 — 글이 아니라 키([VoiceText]),
 * 화면이 `report.text.resolve(context)` 로 앱 언어의 글을 만든다 (P18).
 *
 * - 값 범위(템포 20 ~ 240, 마디 · 쪽 1 이상)는 **아무것도 하기 전에** 본다 — 하나라도 벗어나면 하나도 실행하지 않는다
 * - 명령은 [ParseResult.Commands] 의 순서(템포 · 파트 → 위치 → 시작)대로, 하나가 실패하면 거기서 멈추고 앞에서 한 것을 알린다
 * - 수만 들렸으면([ParseResult.BareNumber]) 메트로놈 대화상자가 열려 있을 때는 템포, 아니면 마디 고르기 (P12 §3.2)
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object VoiceCommandRunner {

    /** [text] = 화면에 보일 한 줄 — `text.resolve(context)` */
    data class Report(val ok: Boolean, val text: VoiceText)

    suspend fun run(result: ParseResult, target: ViewerCommands, bareNumberIsTempo: Boolean = false): Report = when (result) {
        is ParseResult.Unrecognized -> Report(false, unrecognizedText(result, target))
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
                val before = ParseResult.Commands(done).text
                return Report(false, if (done.isEmpty()) outcome.text else VoiceText.of(R.string.voice_done_but, before, outcome.text))
            }
            done += command
        }
        return Report(true, result.text)
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
        VoiceCommand.LastPage -> target.lastPage()
        is SetTempo -> target.setTempo(command.bpm)
        is SetCountIn -> target.setCountIn(command.bars)
        is SelectParts -> target.selectParts(command.parts)
        VoiceCommand.ShowFullScore -> target.showFullScore()
        VoiceCommand.Start -> target.start()
        VoiceCommand.Stop -> target.stop()
        VoiceCommand.Listen -> target.listen()
        VoiceCommand.ListenFromStart -> target.listen(ListenFrom.START)
        VoiceCommand.ListenResume -> target.listen(ListenFrom.RESUME)
    }

    /** 값이 모두 쓸 수 있는 범위인가 — 음성 인식 후보 고르기가 쓴다("템포 8" · "템포 80" 이면 80, #109) */
    fun inRange(result: ParseResult): Boolean =
        result !is ParseResult.Commands || result.commands.none { rangeError(it) != null }

    private fun rangeError(command: VoiceCommand): VoiceText? = when {
        command is SetTempo && command.bpm !in MetronomeClock.MIN_BPM..MetronomeClock.MAX_BPM ->
            VoiceText.of(R.string.voice_tempo_out_of_range, command.bpm, MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
        command is SetCountIn && command.bars !in 1..2 -> VoiceText.of(R.string.voice_count_in_range)
        command is GotoMeasure && command.measure < 1 -> VoiceText.of(R.string.voice_no_measure, command.measure)
        command is SelectMeasure && command.measure < 1 -> VoiceText.of(R.string.voice_no_measure, command.measure)
        command is GotoPage && command.page < 1 -> VoiceText.of(R.string.voice_no_page, command.page)
        else -> null
    }

    private suspend fun unrecognizedText(result: ParseResult.Unrecognized, target: ViewerCommands): VoiceText {
        if (result.reason != Reason.UNKNOWN_PART) return reasonText(result.reason)
        val what = result.heard?.let { VoiceText.of(R.string.voice_part_not_found_heard, it) } ?: VoiceText.of(R.string.voice_part_not_heard)
        return withPartChoices(what, target.partNames())
    }

    /** "[message] — 이 악보: 김지한 · 박서연 · 보표 3" (파트 이름을 모르면 [message] 그대로) */
    fun withPartChoices(message: VoiceText, names: List<String>): VoiceText =
        if (names.isEmpty()) message else VoiceText.of(R.string.voice_with_part_choices, message, names.joinToString(" · "))

    /** 말한 파트가 악보에 없다([PartMatcher.Result.Missing]) — "첼로 파트가 없어요 — 이 악보: …" */
    fun partMissing(part: PartRef, names: List<String>): VoiceText =
        withPartChoices(VoiceText.of(R.string.voice_part_missing, part.text), names)

    /** 번호 없이 말한 파트가 여럿([PartMatcher.Result.Ambiguous]) — "바이올린 파트가 여럿이에요 (Violin I, Violin II) — 번호까지 말하세요" */
    fun partAmbiguous(part: PartRef, names: List<String>): VoiceText =
        VoiceText.of(R.string.voice_part_ambiguous, part.text, names.joinToString())

    fun reasonText(reason: Reason): VoiceText = VoiceText.of(
        when (reason) {
            Reason.NOTHING -> R.string.voice_reason_nothing
            Reason.NEGATION -> R.string.voice_reason_negation
            Reason.RELATIVE -> R.string.voice_reason_relative
            Reason.CONFLICT -> R.string.voice_reason_conflict
            Reason.STRAY_NUMBER -> R.string.voice_reason_stray_number
            Reason.UNKNOWN_PART -> R.string.voice_part_not_heard
            Reason.CHATTER -> R.string.voice_reason_chatter
        },
    )
}
