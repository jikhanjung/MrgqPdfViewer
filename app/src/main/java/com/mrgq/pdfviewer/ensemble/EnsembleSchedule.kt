package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.metronome.BarPosition
import com.mrgq.pdfviewer.metronome.Beat
import com.mrgq.pdfviewer.metronome.BeatSchedule
import com.mrgq.pdfviewer.metronome.BeatTimes
import com.mrgq.pdfviewer.metronome.TimeSignature

/**
 * [BeatTimeline](지휘자 시계)을 이 기기 시계로 옮겨 [BeatSchedule] 로 내놓는다 (#055 §4.3).
 *
 * 이 기기 시각 = 지휘자 시각 − [offsetNs] (지휘자 시계 − 내 시계). 지휘자 자신은 0 이다.
 * offset 은 시계 동기가 조금씩 고쳐 가므로 매번 새로 읽는다. 시간표 · 박자는 템포 변경 때 통째로 바뀐다.
 * 구간별 빠르기(#057)면 [BeatTimes] 도 시간표와 함께 바뀐다 — 둘이 어긋난 순간이 없게 한 덩어리로 갈아 끼운다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class EnsembleSchedule(
    timeline: BeatTimeline,
    timeSignature: TimeSignature,
    dotted: Boolean,
    times: BeatTimes? = null,
    private val offsetNs: () -> Long,
) : BeatSchedule {

    private class State(
        val timeline: BeatTimeline,
        val timeSignature: TimeSignature,
        val dotted: Boolean,
        val times: BeatTimes?,
    )

    @Volatile private var state = State(timeline, timeSignature.coerced(), dotted, times)

    /** 악보 연동이면 박 번호 → 마디 안 위치 ([com.mrgq.pdfviewer.metronome.ScoreFollower.barPositionAt]) */
    @Volatile var barPosition: ((Long) -> BarPosition)? = null

    val timeline: BeatTimeline get() = state.timeline
    val timeSignature: TimeSignature get() = state.timeSignature
    val dotted: Boolean get() = state.dotted
    val times: BeatTimes? get() = state.times

    fun update(timeline: BeatTimeline, timeSignature: TimeSignature, dotted: Boolean, times: BeatTimes? = state.times) {
        state = State(timeline, timeSignature.coerced(), dotted, times)
    }

    /** 박 [beat] 의 지휘자 시계 시각 */
    fun timeOf(beat: Long): Long = state.let { it.timeline.timeOf(beat, it.times) }

    override fun localTimeOf(beat: Long): Long = timeOf(beat) - offsetNs()

    override fun beatAtLocal(localNs: Long): Long = state.let { it.timeline.beatAt(localNs + offsetNs(), it.times) }

    override fun beatInfo(beat: Long): Beat {
        val s = state
        val position = barPosition?.invoke(beat)
        return if (position != null) {
            val meter = position.timeSignature.coerced()
            val inBar = position.indexInBar.coerceIn(0, meter.beatsPerBar(position.dotted) - 1)
            Beat(0L, inBar, meter, position.bpm?.let { Math.round(it).toInt() } ?: s.timeline.bpm, beat, position.dotted)
        } else {
            val beats = s.timeSignature.beatsPerBar(s.dotted)
            Beat(0L, Math.floorMod(beat - s.timeline.barBeat, beats.toLong()).toInt(), s.timeSignature, s.timeline.bpm, beat, s.dotted)
        }
    }
}
