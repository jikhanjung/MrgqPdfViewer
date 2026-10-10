package com.mrgq.pdfviewer.voice

/** 파트 하나 — 악기 키([VoiceLexicon.INSTRUMENTS]) + 번호("바이올린 2"), 번호 없으면 null */
data class PartRef(val instrument: String, val number: Int? = null, val staffName: String? = null, val staffNumber: Int? = null) {
    val label: String get() = when {
        staffNumber != null -> "보표 $staffNumber"
        staffName != null -> staffName
        else -> (VoiceLexicon.INSTRUMENT_LABELS[instrument] ?: instrument) + (number?.let { " $it" } ?: "")
    }

    companion object {
        /** 보표 이름 그대로 부른 파트 — 사람 이름 보표 (#094) */
        fun named(staffName: String) = PartRef(instrument = "", staffName = staffName)
        /** 위에서 [n] 째 보표 — "보표 2" (#098) */
        fun staff(n: Int) = PartRef(instrument = "", staffNumber = n)
    }
}

/** 음성 명령 (P12 §3.2). 값이 악보 · 상태에 맞는지는 실행하는 쪽이 검사한다(P12 §3.3). */
sealed class VoiceCommand {
    abstract val label: String

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

    /** 연주(메트로놈 · 악보 연동)를 멈춘다 — 👂 계속 듣기에서 (🎙 단추는 누르는 순간 멈추므로 필요 없었다, #085) */
    object Stop : VoiceCommand() { override val label get() = "정지" }

    /** 고른 마디(없으면 지금 쪽 첫 마디)에서 예비박 뒤 바로 — 재생을 시작하는 위치 명령이 함께 있으면 그쪽이 시작하므로 빠진다 */
    object Start : VoiceCommand() { override val label get() = "시작" }

    val isPosition: Boolean
        get() = this is SelectMeasure || this is GotoMeasure || this == GotoStart || this == Restart || this == Resume ||
            this is GotoRehearsalMark || this == NextPage || this == PreviousPage || this is GotoPage

    /** 위치 명령 중 메트로놈을 그 자리에서 시작하는 것 (마디 고르기 · 쪽 넘김은 시작하지 않는다) */
    val startsPlayback: Boolean
        get() = this is GotoMeasure || this == GotoStart || this == Restart || this == Resume || this is GotoRehearsalMark
}

/** 발화 하나를 읽은 결과 */
sealed class ParseResult {
    /** 실행할 명령 — 실행 순서(템포 · 파트 → 위치 → 시작). [label] 은 화면에 보일 "57마디 · ♩=72" */
    data class Commands(val commands: List<VoiceCommand>) : ParseResult() {
        val label: String get() = commands.sortedBy { if (it.isPosition) 0 else 1 }.joinToString(" · ") { it.label }
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
    }
}
