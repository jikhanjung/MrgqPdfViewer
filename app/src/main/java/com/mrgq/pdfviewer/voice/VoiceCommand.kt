package com.mrgq.pdfviewer.voice

import com.mrgq.pdfviewer.R

/** 파트 하나 — 악기 키([VoiceLexicon.INSTRUMENTS]) + 번호("바이올린 2"), 번호 없으면 null */
data class PartRef(val instrument: String, val number: Int? = null, val staffName: String? = null, val staffNumber: Int? = null) {
    /** 한국어 설명 — 기록 · 테스트용(화면에는 [text], P18) */
    val label: String get() = when {
        staffNumber != null -> "보표 $staffNumber"
        staffName != null -> staffName
        else -> (VoiceLexicon.INSTRUMENT_LABELS[instrument] ?: instrument) + (number?.let { " $it" } ?: "")
    }

    /** 화면에 보일 이름 — "보표 2" · 보표 이름 그대로 · "바이올린 2" (P18) */
    val text: VoiceText get() = when {
        staffNumber != null -> VoiceText.of(R.string.voice_staff_n, staffNumber)
        staffName != null -> VoiceText.Plain(staffName)
        else -> {
            val name = INSTRUMENT_TEXTS[instrument]?.let { VoiceText.of(it) } ?: VoiceText.Plain(instrument)
            if (number == null) name else VoiceText.of(R.string.voice_part_numbered, name, number)
        }
    }

    companion object {
        /** 악기 키 → 화면 이름 키 ([VoiceLexicon.INSTRUMENT_LABELS] 와 같은 키들) */
        val INSTRUMENT_TEXTS: Map<String, Int> = mapOf(
            "violin" to R.string.voice_instrument_violin, "viola" to R.string.voice_instrument_viola,
            "cello" to R.string.voice_instrument_cello, "bass" to R.string.voice_instrument_bass,
            "flute" to R.string.voice_instrument_flute, "piccolo" to R.string.voice_instrument_piccolo,
            "oboe" to R.string.voice_instrument_oboe, "clarinet" to R.string.voice_instrument_clarinet,
            "bassoon" to R.string.voice_instrument_bassoon, "horn" to R.string.voice_instrument_horn,
            "trumpet" to R.string.voice_instrument_trumpet, "trombone" to R.string.voice_instrument_trombone,
            "tuba" to R.string.voice_instrument_tuba, "timpani" to R.string.voice_instrument_timpani,
            "percussion" to R.string.voice_instrument_percussion, "piano" to R.string.voice_instrument_piano,
            "harp" to R.string.voice_instrument_harp, "guitar" to R.string.voice_instrument_guitar,
            "soprano" to R.string.voice_instrument_soprano, "alto" to R.string.voice_instrument_alto,
            "tenor" to R.string.voice_instrument_tenor,
        )

        /** 보표 이름 그대로 부른 파트 — 사람 이름 보표 (#094) */
        fun named(staffName: String) = PartRef(instrument = "", staffName = staffName)
        /** 위에서 [n] 째 보표 — "보표 2" (#098) */
        fun staff(n: Int) = PartRef(instrument = "", staffNumber = n)
    }
}

/** 음성 명령 (P12 §3.2). 값이 악보 · 상태에 맞는지는 실행하는 쪽이 검사한다(P12 §3.3). */
sealed class VoiceCommand {
    /** 한국어 설명 — 기록 · 테스트용(실녹음 표 `VoiceRecordingsTest` 의 기대값). 화면에는 [text] (P18) */
    abstract val label: String

    /** 화면에 보일 글 — 앱 언어로 (P18) */
    val text: VoiceText get() = when (this) {
        is SelectMeasure -> VoiceText.of(R.string.voice_cmd_select_measure, measure)
        is GotoMeasure -> VoiceText.of(R.string.voice_cmd_goto_measure, measure)
        GotoStart -> VoiceText.of(R.string.voice_cmd_goto_start)
        Restart -> VoiceText.of(R.string.voice_cmd_restart)
        Resume -> VoiceText.of(R.string.voice_cmd_resume)
        is GotoRehearsalMark -> VoiceText.of(R.string.voice_cmd_rehearsal_mark, mark)
        NextPage -> VoiceText.of(R.string.voice_cmd_next_page)
        PreviousPage -> VoiceText.of(R.string.voice_cmd_previous_page)
        is GotoPage -> VoiceText.of(R.string.voice_cmd_goto_page, page)
        LastPage -> VoiceText.of(R.string.voice_cmd_last_page)
        is SetTempo -> VoiceText.of(R.string.voice_cmd_tempo, bpm)
        is SetCountIn -> VoiceText.of(R.string.voice_cmd_count_in, bars)
        is SelectParts -> VoiceText.of(R.string.voice_cmd_select_parts, VoiceText.Joined(parts.map { it.text }))
        ShowFullScore -> VoiceText.of(R.string.voice_cmd_full_score)
        Listen -> VoiceText.of(R.string.voice_cmd_listen)
        ListenFromStart -> VoiceText.of(R.string.voice_cmd_listen_from_start)
        ListenResume -> VoiceText.of(R.string.voice_cmd_listen_resume)
        Stop -> VoiceText.of(R.string.voice_cmd_stop)
        Start -> VoiceText.of(R.string.voice_cmd_start)
    }

    // ── 위치 — 한 발화에 하나만 ──
    /** "57마디" — 그 마디를 시작 마디로 고르기만 한다(시작은 "시작" · 마디 탭) */
    data class SelectMeasure(val measure: Int) : VoiceCommand() { override val label get() = "${measure}마디 선택" }
    /** "57마디부터" · "57마디 시작" — 그 마디에서 예비박 뒤 바로 */
    data class GotoMeasure(val measure: Int) : VoiceCommand() { override val label get() = "${measure}마디부터" }
    object GotoStart : VoiceCommand() { override val label get() = "처음부터" }
    /** 직전 시작 마디부터 */
    object Restart : VoiceCommand() { override val label get() = "다시" }
    /** 멈춘 마디부터 */
    object Resume : VoiceCommand() { override val label get() = "이어서" }
    data class GotoRehearsalMark(val mark: String) : VoiceCommand() { override val label get() = "레터 $mark" }
    object NextPage : VoiceCommand() { override val label get() = "다음 쪽" }
    object PreviousPage : VoiceCommand() { override val label get() = "이전 쪽" }
    data class GotoPage(val page: Int) : VoiceCommand() { override val label get() = "${page}쪽" }
    /** "마지막 페이지" (#109) */
    object LastPage : VoiceCommand() { override val label get() = "마지막 쪽" }

    // ── 설정 ──
    data class SetTempo(val bpm: Int) : VoiceCommand() { override val label get() = "♩=$bpm" }
    /** 악보 연동 예비박 마디 수(1 · 2, 전역 — #060) */
    data class SetCountIn(val bars: Int) : VoiceCommand() { override val label get() = "예비박 ${bars}마디" }
    data class SelectParts(val parts: List<PartRef>) : VoiceCommand() {
        override val label get() = parts.joinToString(" · ") { it.label } + " 파트"
    }
    object ShowFullScore : VoiceCommand() { override val label get() = "총보" }

    /**
     * 🎤 연주 듣고 넘기기 시작 (#087) — 같은 말의 쪽 명령이 있으면 그 쪽 첫 마디, 고른 마디가 있으면 거기, 없으면 이 쪽 첫 마디부터.
     * 듣는 동안은 마이크가 듣기 몫이라 음성 명령을 쓸 수 없다(멈춤은 ↑ 메뉴)
     */
    object Listen : VoiceCommand() { override val label get() = "🎤 연주 듣기" }
    /** "처음부터 듣기" — 곡 첫 마디부터 듣기 (#113) */
    object ListenFromStart : VoiceCommand() { override val label get() = "🎤 처음부터 듣기" }
    /** "이어서 듣기" — 지난번 듣기(없으면 연주)가 멈춘 마디부터 (#113) */
    object ListenResume : VoiceCommand() { override val label get() = "🎤 이어서 듣기" }

    /** 연주(메트로놈 · 악보 연동)를 멈춘다 — 👂 계속 듣기에서 (🎙 단추는 누르는 순간 멈추므로 필요 없었다, #085) */
    object Stop : VoiceCommand() { override val label get() = "정지" }

    /** 고른 마디(없으면 지금 쪽 첫 마디)에서 예비박 뒤 바로 — 재생을 시작하는 위치 명령이 함께 있으면 그쪽이 시작하므로 빠진다 */
    object Start : VoiceCommand() { override val label get() = "시작" }

    val isPosition: Boolean
        get() = this is SelectMeasure || this is GotoMeasure || this == GotoStart || this == Restart || this == Resume ||
            this is GotoRehearsalMark || this == NextPage || this == PreviousPage || this is GotoPage || this == LastPage

    /** 위치 명령 중 메트로놈을 그 자리에서 시작하는 것 (마디 고르기 · 쪽 넘김은 시작하지 않는다) */
    val startsPlayback: Boolean
        get() = this is GotoMeasure || this == GotoStart || this == Restart || this == Resume || this is GotoRehearsalMark
}

/** 발화 하나를 읽은 결과 */
sealed class ParseResult {
    /** 실행할 명령 — 실행 순서(템포 · 파트 → 위치 → 시작). [label] 은 화면에 보일 "57마디 · ♩=72" */
    data class Commands(val commands: List<VoiceCommand>) : ParseResult() {
        /** 한국어 설명 — 기록 · 테스트용 */
        val label: String get() = shown.joinToString(" · ") { it.label }
        /** 화면에 보일 글 — 위치 먼저 */
        val text: VoiceText get() = VoiceText.Joined(shown.map { it.text })
        private val shown: List<VoiceCommand> get() = commands.sortedBy { if (it.isPosition) 0 else 1 }
    }

    /** 수만 들렸다("칠십이") — 마디(고르기)인지 템포인지는 지금 화면이 정한다(P12 §3.2) */
    data class BareNumber(val value: Int) : ParseResult()

    /** [heard] = [Reason.UNKNOWN_PART] 일 때 파트 자리에서 들린 모르는 말("지환") — 없으면 null */
    data class Unrecognized(val reason: Reason, val heard: String? = null) : ParseResult()

    enum class Reason {
        /** 명령으로 읽히는 것이 없다 */
        NOTHING,
        /** "말고 · 아니 · 취소"가 들렸다 */
        NEGATION,
        /** "두 마디 전"처럼 상대 위치 — 1차에 받지 않는다 */
        RELATIVE,
        /** 위치 둘(57마디 · 3쪽), 템포 둘, 파트와 총보를 함께 */
        CONFLICT,
        /** 어디에도 붙지 않은 수가 남았다 — 잘못 실행하느니 묻는다 */
        STRAY_NUMBER,
        /** "파트"는 들렸는데 무슨 파트인지(악기 · 이름 · 보표 번호) 못 찾았다 (#098) */
        UNKNOWN_PART,
        /**
         * 악기 · 이름은 들렸지만 "파트 · 보표"라는 말 없이 모르는 말이 섞였다 — 대화로 본다("여기 첼로가 좀 커요", #117).
         * "첼로" · "첼로 파트" · "첼로 57마디부터"는 그대로 된다
         */
        CHATTER,
    }
}
