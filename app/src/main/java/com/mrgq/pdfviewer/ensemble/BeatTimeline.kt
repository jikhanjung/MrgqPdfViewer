package com.mrgq.pdfviewer.ensemble

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 합주 메트로놈의 시간표 — 박 번호 ↔ 지휘자 시계 시각 (#055 §3.2).
 *
 * 박 [anchorBeat] 이 지휘자 시계 [anchorNs] 에 울리고, 그 뒤 박 간격은 [bpm] 으로 일정하다.
 * 템포가 바뀌면 바뀌는 박을 새 기준으로 [retimed] 한다 — 앞 박들의 시각은 그대로다.
 * [barBeat] 는 마디 첫 박인 박 번호 하나 (악보 연동이 아닐 때 강박 위치를 센다).
 *
 * 불변 객체라 오디오 스레드에서 읽어도 된다. Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
data class BeatTimeline(
    val anchorBeat: Long,
    val anchorNs: Long,
    val bpm: Int,
    val barBeat: Long = anchorBeat,
) {
    init {
        require(bpm > 0) { "bpm $bpm" }
    }

    private val periodNs: Double get() = 60_000_000_000.0 / bpm

    /** 박 [beat] 의 시각 (지휘자 시계, ns). 앞 박에도 같은 식을 쓴다 */
    fun timeOf(beat: Long): Long = anchorNs + ((beat - anchorBeat) * periodNs).roundToLong()

    /** [timeNs] 에 들리고 있는 박 — 시각이 [timeNs] 이하인 마지막 박. 첫 박 전이면 [anchorBeat] 보다 작다 */
    fun beatAt(timeNs: Long): Long {
        var beat = anchorBeat + floor((timeNs - anchorNs) / periodNs).toLong()
        // 반올림한 시각과 어긋나지 않게 맞춘다
        while (timeOf(beat + 1) <= timeNs) beat++
        while (timeOf(beat) > timeNs) beat--
        return beat
    }

    /** 박 [fromBeat] 부터 [newBpm] 으로. [newBarBeat] 를 주면 거기서 마디를 새로 센다 (박자가 바뀐 경우) */
    fun retimed(newBpm: Int, fromBeat: Long, newBarBeat: Long = barBeat): BeatTimeline =
        BeatTimeline(fromBeat, timeOf(fromBeat), newBpm, newBarBeat)
}
