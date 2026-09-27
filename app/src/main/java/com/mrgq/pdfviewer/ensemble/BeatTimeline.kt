package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.metronome.BeatTimes
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 합주 메트로놈의 시간표 — 박 번호 ↔ 지휘자 시계 시각 (#055 §3.2).
 *
 * 박 [anchorBeat] 이 지휘자 시계 [anchorNs] 에 울리고, 그 뒤 박 간격은 [bpm] 으로 일정하다.
 * 템포가 바뀌면 바뀌는 박을 새 기준으로 [retimed] 한다 — 앞 박들의 시각은 그대로다.
 * [barBeat] 는 마디 첫 박인 박 번호 하나 (악보 연동이 아닐 때 강박 위치를 센다).
 *
 * **구간별 빠르기** (#057): 악보 연동에서 구간마다 템포가 다르면 박 간격이 일정하지 않다. 그때는 [BeatTimes] 를 함께 넘긴다 —
 * 박 [anchorBeat] 가 [anchorNs] 인 것은 같고, 그 뒤 박 시각은 [BeatTimes] 의 경과 시간 차이로 정한다 ([bpm] 은 쓰지 않는다).
 * [BeatTimes] 는 방송하지 않는다 — 연주자가 받은 구간 설정과 자기 악보로 똑같이 만든다.
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

    /** 구간별 빠르기: 박 [beat] 의 시각. [times] 가 null 이면 [timeOf] 와 같다 */
    fun timeOf(beat: Long, times: BeatTimes?): Long {
        if (times == null) return timeOf(beat)
        return anchorNs + ((times.secondsAt(beat) - times.secondsAt(anchorBeat)) * 1e9).roundToLong()
    }

    /**
     * 구간별 빠르기: [timeNs] 에 들리고 있는 박. 박 간격이 일정하지 않아 기준 박에서 두 배씩 넓혀 범위를 잡고 반으로 좁힌다
     * ([BeatTimes] 는 앞뒤 끝 너머에도 값을 내므로 늘 끝난다).
     */
    fun beatAt(timeNs: Long, times: BeatTimes?): Long {
        if (times == null) return beatAt(timeNs)
        fun at(beat: Long) = timeOf(beat, times)
        var lo: Long
        var hi: Long // at(lo) <= timeNs < at(hi)
        var step = 1L
        if (at(anchorBeat) <= timeNs) {
            lo = anchorBeat
            while (at(lo + step) <= timeNs) {
                lo += step
                step *= 2
            }
            hi = lo + step
        } else {
            hi = anchorBeat
            while (at(hi - step) > timeNs) {
                hi -= step
                step *= 2
            }
            lo = hi - step
        }
        while (hi - lo > 1) {
            val mid = lo + (hi - lo) / 2
            if (at(mid) <= timeNs) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * 구간별 빠르기: 박 [fromBeat] 부터 새 박 시각표로 (연주 중 빠르기를 바꿈) — 새 시간표는 새 [BeatTimes] 와 함께 쓴다.
     * 앞 박들의 시각은 [oldTimes] 그대로다. [newBpm] 은 첫 구간 템포 (연주자가 구간을 다시 만들 때 · 옛 버전 연주자용).
     */
    fun retimed(newBpm: Int, fromBeat: Long, oldTimes: BeatTimes?): BeatTimeline =
        BeatTimeline(fromBeat, timeOf(fromBeat, oldTimes), newBpm, barBeat)
}
