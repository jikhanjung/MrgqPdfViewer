package com.mrgq.pdfviewer.voice

import com.mrgq.pdfviewer.voice.ParseResult.Reason
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoPage
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoRehearsalMark
import com.mrgq.pdfviewer.voice.VoiceCommand.SetCountIn
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectParts
import com.mrgq.pdfviewer.voice.VoiceCommand.SetTempo

/**
 * 정규화한 조각 → 명령 (P12 §3.2). 원칙은 **모르면 실행하지 않는다** — 잘못 실행(다른 마디로 감)이 못 알아듣는 것보다 훨씬 나쁘다.
 *
 * - 수는 바로 뒤 낱말에 붙는다: `57 마디` = 마디 고르기, `57 마디 부터` · `57 부터` = 그 마디부터 시작, `3 쪽` = 쪽,
 *   `72 bpm` · `템포 72` = 템포, `예비박 2 마디` · `2 마디 예비박` = 예비박. 사이에 "번 · 번째"는 건너뛴다
 * - 조사 · 말끝만인 조각은 버리고, 모르는 말은 남겨 앞뒤가 이어지지 않게 한다
 * - "말고 · 아니 · 취소" 나 "전 · 뒤 · 후"(상대 위치)가 들리면, 어디에도 붙지 않은 수가 남으면, 위치 · 템포가 둘이면 → 실행하지 않음
 * - 마디 고르기에 "시작 · 다시"가 붙으면 그 마디부터 시작("57마디 시작" = 57마디부터)
 * - "듣기"가 있으면 메트로놈 대신 🎤 연주 듣기로 시작: "57마디부터 듣기" = 57마디 고르고 듣기, "듣기 시작" = 듣기
 * - "다시"는 다른 위치 명령이 있으면 빠진다("처음부터 다시" = 처음부터), "시작"은 재생을 시작하는 위치 명령이 있으면 빠진다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object CommandParser {

    /** [staffNames] = 이 악보의 보표 이름 — 사람 이름 보표를 부를 수 있게 (#094) */
    fun parse(text: String, staffNames: List<String> = emptyList()): ParseResult =
        parse(CommandNormalizer.normalize(text, staffNames))

    /**
     * 음성 인식 후보 여럿(가능성 높은 순) → 쓸 후보와 그 결과. 첫 후보가 명령이 아니면 다음 후보를 보되, 첫 후보가 **일부러 한 말로
     * 막힌 것**(부정 · 상대 위치 · 둘)이면 그대로 둔다 — "57마디 말고"를 둘째 후보 "57마디"로 실행하지 않게. 후보가 없으면 null
     */
    fun parseBest(candidates: List<String>, staffNames: List<String> = emptyList()): Pair<String, ParseResult>? {
        val parsed = candidates.filter { it.isNotBlank() }.map { it to parse(it, staffNames) }
        val first = parsed.firstOrNull() ?: return null
        val top = first.second
        if (top is ParseResult.Unrecognized && top.reason !in RETRYABLE) return first
        return parsed.firstOrNull { it.second is ParseResult.Commands }
            ?: parsed.firstOrNull { it.second is ParseResult.BareNumber }
            ?: first
    }

    /** 잘못 들었을 수 있는 실패 — 다음 후보를 본다 */
    private val RETRYABLE = setOf(Reason.NOTHING, Reason.STRAY_NUMBER, Reason.UNKNOWN_PART)

    /** 수 뒤에 붙으면 그 수가 마디 · 쪽 · 빠르기인 말 */
    private val UNITS = setOf(Kw.MEASURE, Kw.FROM, Kw.PAGE, Kw.BPM)

    fun parse(raw: List<Token>): ParseResult {
        if (raw.any { it == Token.Word(Kw.NEGATION) }) return ParseResult.Unrecognized(Reason.NEGATION)
        if (raw.any { it == Token.Word(Kw.RELATIVE) }) return ParseResult.Unrecognized(Reason.RELATIVE)
        val tokens = dropParticles(raw)

        val found = mutableListOf<VoiceCommand>()
        val parts = mutableListOf<PartRef>()
        var strayNumbers = 0
        var metronome = false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                t is Token.Num -> {
                    val j = skipCounters(tokens, i + 1)
                    when ((tokens.getOrNull(j) as? Token.Word)?.kw) {
                        Kw.MEASURE -> {
                            // "두 마디 예비박" — 단 "57마디 예비박 두 마디"의 57 은 마디(뒤 수가 예비박 몫)
                            if (tokens.getOrNull(j + 1) == Token.Word(Kw.COUNT_IN) && tokens.getOrNull(j + 2) !is Token.Num) {
                                found += SetCountIn(t.value)
                                i = j + 2
                                continue
                            }
                            // "57마디부터" = 시작, "57마디" = 고르기만
                            val from = tokens.getOrNull(j + 1) == Token.Word(Kw.FROM)
                            found += if (from) GotoMeasure(t.value) else SelectMeasure(t.value)
                            i = j + if (from) 2 else 1
                            continue
                        }
                        Kw.FROM -> { found += GotoMeasure(t.value); i = j + 1; continue }
                        Kw.PAGE -> { found += GotoPage(t.value); i = j + 1; continue }
                        Kw.BPM -> { found += SetTempo(t.value); i = j + 1; continue }
                        else -> strayNumbers++
                    }
                }
                t == Token.Word(Kw.TEMPO) && next is Token.Num -> { found += SetTempo(next.value); i += 2; continue }
                // "예비박 한 마디", "예비박 둘" — 뒤의 마디는 이 수의 단위
                t == Token.Word(Kw.COUNT_IN) && next is Token.Num -> {
                    found += SetCountIn(next.value)
                    val j = skipCounters(tokens, i + 2)
                    i = if (tokens.getOrNull(j) == Token.Word(Kw.MEASURE)) j + 1 else i + 2
                    continue
                }
                t == Token.Word(Kw.NEXT) && next == Token.Word(Kw.PAGE) -> { found += VoiceCommand.NextPage; i += 2; continue }
                t == Token.Word(Kw.PREV) -> {
                    // "앞 · 이전"은 쪽 앞에서만 — "57마디 앞"은 상대 위치라 받지 않는다
                    if (next != Token.Word(Kw.PAGE)) return ParseResult.Unrecognized(Reason.RELATIVE)
                    found += VoiceCommand.PreviousPage; i += 2; continue
                }
                t == Token.Word(Kw.TURN) -> found += VoiceCommand.NextPage
                t == Token.Word(Kw.START_OVER) -> found += VoiceCommand.GotoStart
                t == Token.Word(Kw.AGAIN) -> found += VoiceCommand.Restart
                t == Token.Word(Kw.RESUME) -> found += VoiceCommand.Resume
                t == Token.Word(Kw.START) -> found += VoiceCommand.Start
                t == Token.Word(Kw.STOP) -> found += VoiceCommand.Stop
                t == Token.Word(Kw.LISTEN) -> found += VoiceCommand.Listen
                t == Token.Word(Kw.METRONOME) -> metronome = true
                t == Token.Word(Kw.FULL_SCORE) -> found += VoiceCommand.ShowFullScore
                // "세컨 바이올린" — 서수가 앞에
                t is Token.Ordinal && next is Token.Instrument -> {
                    parts += PartRef(next.key, t.n)
                    i += 2
                    continue
                }
                t is Token.Instrument -> {
                    val (number, used) = partNumber(tokens, i + 1)
                    parts += PartRef(t.key, number)
                    i += 1 + used
                    continue
                }
                t is Token.Person -> parts += PartRef.named(t.staffName)
                // "보표 2", "보표 1, 2" — 이름을 못 읽은 보표(파트 보기 목록의 "보표 n"). 단위가 붙은 수("57마디")는 보표 번호가 아니다
                t == Token.Word(Kw.STAFF) && next is Token.Num -> {
                    var j = i + 1
                    while (true) {
                        val n = tokens.getOrNull(j) as? Token.Num ?: break
                        if ((tokens.getOrNull(skipCounters(tokens, j + 1)) as? Token.Word)?.kw in UNITS) break
                        parts += PartRef.staff(n.value)
                        j++
                    }
                    i = j
                    continue
                }
                t == Token.Word(Kw.LETTER) -> {
                    val mark = when (next) {
                        is Token.Other -> VoiceLexicon.letterPrefix(next.text)?.toString()
                        is Token.Num -> next.value.toString()
                        else -> null
                    }
                    if (mark != null) { found += GotoRehearsalMark(mark); i += 2; continue }
                }
            }
            i++
        }
        // "파트"는 들렸는데 무슨 파트인지 모른다 — 나머지(마디 등)만 실행하지 않고, 들린 말을 알려 준다 (#098)
        if (parts.isEmpty() && Token.Word(Kw.PART) in tokens && VoiceCommand.ShowFullScore !in found) {
            val heard = tokens.filterIsInstance<Token.Other>().joinToString(" ") { it.text }.ifEmpty { null }
            return ParseResult.Unrecognized(Reason.UNKNOWN_PART, heard)
        }
        if (parts.isNotEmpty()) found += SelectParts(parts.distinct())
        // "메트로놈"만 = 시작, "메트로놈 정지" = 정지
        if (metronome && VoiceCommand.Stop !in found) found += VoiceCommand.Start
        // 수 하나만 들렸다("칠십이요") — 무엇의 수인지는 지금 화면이 정한다
        val only = tokens.singleOrNull()
        if (found.isEmpty() && only is Token.Num) return ParseResult.BareNumber(only.value)
        return resolve(found.distinct(), strayNumbers)
    }

    /** 조사 · 말끝만인 조각을 버린다. 레터 바로 뒤 조각은 알파벳 읽기("레터 이" = E)라 남긴다 */
    private fun dropParticles(tokens: List<Token>): List<Token> = tokens.filterIndexed { index, t ->
        t !is Token.Other || tokens.getOrNull(index - 1) == Token.Word(Kw.LETTER) || !VoiceLexicon.isParticles(t.text)
    }

    private fun skipCounters(tokens: List<Token>, from: Int): Int {
        var j = from
        while (tokens.getOrNull(j) == Token.Word(Kw.COUNTER)) j++
        return j
    }

    /** 악기 뒤 파트 번호 — 서수("세컨") 또는 1 ~ 4 의 수. 수 뒤에 마디 · 쪽 · bpm 이 오면 그 수는 파트 번호가 아니다 */
    private fun partNumber(tokens: List<Token>, at: Int): Pair<Int?, Int> = when (val t = tokens.getOrNull(at)) {
        is Token.Ordinal -> t.n to 1
        is Token.Num -> {
            val end = skipCounters(tokens, at + 1)
            val after = (tokens.getOrNull(end) as? Token.Word)?.kw
            if (t.value !in 1..4 || after in NUMBER_UNITS) null to 0 else t.value to (end - at)
        }
        else -> null to 0
    }

    private val NUMBER_UNITS = setOf(Kw.MEASURE, Kw.FROM, Kw.PAGE, Kw.BPM)

    private fun resolve(found: List<VoiceCommand>, strayNumbers: Int): ParseResult {
        var commands = found
        // "57마디부터 듣기", "듣기 시작" — 메트로놈이 아니라 🎤 연주 듣기로 시작한다: 마디는 고르기로, "시작"은 듣기에 든다
        if (VoiceCommand.Listen in commands) {
            commands = commands.map { if (it is GotoMeasure) SelectMeasure(it.measure) else it } - VoiceCommand.Start
            // "처음부터 듣기" · "이어서 듣기" · "듣기 멈춰"는 아직 받지 않는다
            if (VoiceCommand.Stop in commands || commands.any { it.startsPlayback }) return ParseResult.Unrecognized(Reason.CONFLICT)
        }
        // "57마디 시작", "57마디 다시" — 고르기에 시작이 붙으면 그 마디부터
        val select = commands.filterIsInstance<SelectMeasure>()
        if (select.isNotEmpty() && (VoiceCommand.Start in commands || VoiceCommand.Restart in commands)) {
            commands = commands.map { if (it is SelectMeasure) GotoMeasure(it.measure) else it } - VoiceCommand.Start - VoiceCommand.Restart
        }
        // "처음부터 다시", "57마디부터 다시" — 다시는 다른 위치 명령에 양보
        if (commands.any { it.isPosition && it != VoiceCommand.Restart }) commands = commands - VoiceCommand.Restart
        // "57마디부터 시작" — 위치 명령이 이미 시작한다
        if (commands.any { it.startsPlayback }) commands = commands - VoiceCommand.Start

        if (commands.isEmpty()) return ParseResult.Unrecognized(Reason.NOTHING)
        if (strayNumbers > 0) return ParseResult.Unrecognized(Reason.STRAY_NUMBER)
        if (commands.count { it.isPosition } > 1) return ParseResult.Unrecognized(Reason.CONFLICT)
        if (commands.count { it is SetTempo } > 1) return ParseResult.Unrecognized(Reason.CONFLICT)
        if (commands.count { it is SetCountIn } > 1) return ParseResult.Unrecognized(Reason.CONFLICT)
        // "멈춰 57마디부터" — 멈추고 시작하라는 말은 서로 어긋난다
        if (VoiceCommand.Stop in commands && commands.any { it.startsPlayback || it == VoiceCommand.Start }) {
            return ParseResult.Unrecognized(Reason.CONFLICT)
        }
        if (commands.any { it is SelectParts } && commands.contains(VoiceCommand.ShowFullScore)) {
            return ParseResult.Unrecognized(Reason.CONFLICT)
        }
        return ParseResult.Commands(commands.sortedBy(::order))
    }

    private fun order(c: VoiceCommand): Int = when {
        c is SetTempo || c is SetCountIn -> 0
        c is SelectParts || c == VoiceCommand.ShowFullScore -> 1
        c.isPosition -> 2
        else -> 3
    }
}
