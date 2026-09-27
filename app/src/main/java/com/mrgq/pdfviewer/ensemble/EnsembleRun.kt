package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.metronome.TempoSectionSetting
import com.mrgq.pdfviewer.metronome.TimeSignature

/**
 * 지휘자 메트로놈의 상태 한 장 (#055 §3.2). **변경분이 아니라 전체 상태**라서, 늦게 들어온 연주자 · 재연결 ·
 * 다시 합류가 이 메시지 하나로 해결된다. 와이어 포맷은 `CollaborationProtocol.buildMetronomeRun`.
 *
 * @param runId 시작할 때마다 새로 — 일시정지 후 이어서도 새 연주다 (예비박부터)
 * @param startMeasure 악보 연동이면 시작 마디, 일반 메트로놈이면 null
 * @param focusMeasure 연주자 화면에 파란 상자로 보일 마디 — [State.PAUSED] 면 멈춘 마디, [State.SELECTING] 이면 지휘자의 커서
 *   ([State.SELECTING] 에서는 [timeline] 을 쓰지 않는다)
 * @param sections 구간별 빠르기 (#057) — 악보 연동일 때 둘째 구간부터의 설정. 첫 구간은 [timeline] 의 bpm · [dotted].
 *   연주자는 이것과 자기 악보로 지휘자와 같은 박 시각을 만든다. null 이면 구간을 모르는 지휘자(v0.2.4) — 박 길이 일정
 * @param countInBars 악보 연동의 예비박 마디 수 (#060) — 연주자가 같은 수로 세야 박 번호가 같은 마디를 가리킨다.
 *   필드가 없으면 1 (예비박을 고를 수 없던 지휘자, v0.2.5 까지)
 */
data class EnsembleRun(
    val runId: String,
    val file: String,
    val state: State,
    val timeline: BeatTimeline,
    val timeSignature: TimeSignature,
    val dotted: Boolean,
    val startMeasure: Int?,
    val focusMeasure: Int? = null,
    val sections: List<TempoSectionSetting>? = null,
    val countInBars: Int = 1,
) {
    enum class State(val wire: String) {
        PLAYING("playing"), PAUSED("paused"), SELECTING("selecting"), STOPPED("stopped");

        companion object {
            fun fromWire(value: String?): State? = values().firstOrNull { it.wire == value }
        }
    }

    val isFollowingScore: Boolean get() = startMeasure != null
}
