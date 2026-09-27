package com.mrgq.pdfviewer.metronome

import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/**
 * 메트로놈 박 번호를 악보 위치로 옮긴다 (#050).
 *
 * 박 0 부터 **시작 마디의 박자로 예비박 한 마디**를 세고, 그다음부터 각 마디의 박 수만큼 박을 센다.
 * 박자표가 비어 있는 마디는 앞 마디의 박자를 이어 쓴다. 반복 기호는 따르지 않는다 (악보에 적힌 순서대로).
 *
 * 박은 박자표 분모 음표 하나다 — 6/8 이면 한 마디에 8분음표 6박. [dottedBeat] 면 겹박자 마디는 점음표 박으로 센다
 * (6/8 = 2박, #052).
 *
 * **구간별 빠르기** ([sections], #057): 주면 마디마다 자기 구간의 세는 단위와 템포를 쓴다 — 예비박은 시작 마디 구간의 것.
 * 이때 [beatTimes] 가 박마다의 시각을 내고 [barPositionAt] 이 박마다 템포를 싣는다. 비어 있으면 곡 전체가 [dottedBeat] 하나로
 * 세고 박 길이는 엔진 템포로 일정하다 (v0.2.4 까지의 동작).
 *
 * 불변 객체라 오디오 스레드에서 읽어도 된다. Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class ScoreFollower(
    measures: List<ScoreMeasure>,
    startMeasureNumber: Int,
    private val dottedBeat: Boolean = false,
    sections: List<TempoSection> = emptyList(),
) {

    sealed interface Position {
        /** 예비박 [beatInBar] 번째 (0부터) */
        data class CountIn(val beatInBar: Int, val timeSignature: TimeSignature) : Position

        /** [measure] 의 [beatInMeasure] 번째 박. [next] 는 다음 마디 (마지막이면 null) — 미리 넘기기에 쓴다 */
        data class InMeasure(
            val measure: ScoreMeasure,
            val beatInMeasure: Int,
            val beatsInMeasure: Int,
            val timeSignature: TimeSignature,
            val next: ScoreMeasure?,
        ) : Position

        /** 마지막 마디가 끝났다 */
        object Finished : Position
    }

    private val playing: List<ScoreMeasure>
    private val meters: Array<TimeSignature>
    private val dotteds: BooleanArray
    /** 마디마다 템포 (구간별 빠르기가 없으면 null) */
    private val tempos: DoubleArray?
    private val firstBeat: LongArray
    /** 마디 첫 박의 시각 (초, 박 0 기준) — [tempos] 가 있을 때만 */
    private val firstSecond: DoubleArray?
    /** 마지막 마디가 끝나는 시각 (초) */
    private val endSecond: Double
    private val totalBeats: Long

    /** 시작 마디의 박자 — 예비박도 이 박자로 센다 */
    val startTimeSignature: TimeSignature

    /** 시작 마디의 세는 단위 — 예비박도 이것으로 */
    val startDotted: Boolean

    /** 시작 마디의 템포 (구간별 빠르기가 없으면 null) — 예비박도 이 템포 */
    val startBpm: Double?

    /** 예비박 수 = 시작 마디 박 수 */
    val countInBeats: Int get() = startTimeSignature.beatsPerBar(startDotted)

    init {
        val ordered = measures.sortedBy { it.measureNumber }
        val startIndex = ordered.indexOfFirst { it.measureNumber == startMeasureNumber }
        require(startIndex >= 0) { "시작 마디 $startMeasureNumber 가 없다" }

        var carried: TimeSignature? = null
        val written = ordered.map { m ->
            (m.timeSigNumerator?.let { TimeSignature.of(it, m.timeSigDenominator) } ?: carried).also { carried = it }
        }

        playing = ordered.subList(startIndex, ordered.size)
        meters = Array(playing.size) { written[startIndex + it] ?: FALLBACK }
        // 마디가 속한 구간 — 시작 마디가 그 마디 이하인 마지막 구간. 첫 구간 앞(못갖춘마디 등)은 첫 구간으로 본다
        val sorted = sections.sortedBy { it.startMeasure }
        val sectionOf = playing.map { m -> sorted.lastOrNull { it.startMeasure <= m.measureNumber } ?: sorted.firstOrNull() }
        dotteds = BooleanArray(playing.size) { sectionOf[it]?.dotted ?: dottedBeat }
        tempos = if (sorted.isEmpty()) null else DoubleArray(playing.size) { sectionOf[it]!!.bpm }
        startTimeSignature = meters.first()
        startDotted = dotteds.first()
        startBpm = tempos?.first()

        firstBeat = LongArray(playing.size)
        firstSecond = tempos?.let { DoubleArray(playing.size) }
        var beat = countInBeats.toLong()
        var second = startBpm?.let { countInBeats * 60.0 / it } ?: 0.0
        for (i in playing.indices) {
            firstBeat[i] = beat
            firstSecond?.set(i, second)
            val beats = meters[i].beatsPerBar(dotteds[i])
            beat += beats
            tempos?.let { second += beats * 60.0 / it[i] }
        }
        totalBeats = beat
        endSecond = second
    }

    /**
     * 박마다의 시각 — 구간별 빠르기가 있을 때만 (없으면 엔진 템포로 일정하다). 예비박 앞 · 끝 너머는 첫 · 마지막 템포로 잇는다.
     */
    val beatTimes: BeatTimes? = if (tempos == null) null else BeatTimes { beat -> secondsAt(beat) }

    private fun secondsAt(beat: Long): Double {
        val t = tempos!!
        val seconds = firstSecond!!
        return when {
            beat < countInBeats -> beat * 60.0 / startBpm!!
            beat >= totalBeats -> endSecond + (beat - totalBeats) * 60.0 / t.last()
            else -> {
                val i = measureIndexAt(beat)
                seconds[i] + (beat - firstBeat[i]) * 60.0 / t[i]
            }
        }
    }

    /** 박 [beatIndex] 가 든 마디 (예비박 · 끝 너머가 아닐 때) */
    private fun measureIndexAt(beatIndex: Long): Int {
        var lo = 0
        var hi = playing.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (firstBeat[mid] <= beatIndex) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * 마디 나눔(박 번호 → 마디)이 [other] 와 같은가 — 템포만 바뀐 것이라 연주 중에 갈아 끼워도 되는지 본다 (#057).
     * 세는 단위가 바뀌면 박 수가 달라져 지금 박이 가리키는 마디가 바뀌므로 다음 시작부터 적용한다.
     */
    fun sameBeatsAs(other: ScoreFollower): Boolean =
        playing.map { it.measureNumber } == other.playing.map { it.measureNumber } &&
            firstBeat.contentEquals(other.firstBeat) && totalBeats == other.totalBeats &&
            countInBeats == other.countInBeats && meters.contentEquals(other.meters) && dotteds.contentEquals(other.dotteds)

    fun positionAt(beatIndex: Long): Position {
        if (beatIndex < countInBeats) return Position.CountIn(beatIndex.coerceAtLeast(0).toInt(), startTimeSignature)
        if (beatIndex >= totalBeats) return Position.Finished
        val i = measureIndexAt(beatIndex)
        return Position.InMeasure(
            measure = playing[i],
            beatInMeasure = (beatIndex - firstBeat[i]).toInt(),
            beatsInMeasure = meters[i].beatsPerBar(dotteds[i]),
            timeSignature = meters[i],
            next = playing.getOrNull(i + 1),
        )
    }

    /** 강박 위치와 그 마디 박자·박 단위 (구간별 빠르기면 템포도) — [MetronomeEngine.barPosition] 으로 넘긴다. */
    fun barPositionAt(beatIndex: Long): BarPosition = when (val p = positionAt(beatIndex)) {
        is Position.CountIn -> BarPosition(p.beatInBar, p.timeSignature, startDotted, startBpm)
        is Position.InMeasure -> {
            val i = measureIndexAt(beatIndex)
            BarPosition(p.beatInMeasure, p.timeSignature, dotteds[i], tempos?.get(i))
        }
        Position.Finished -> BarPosition(0, meters.last(), dotteds.last(), tempos?.last())
    }

    companion object {
        /** 연동할 수 없는 마디에 쓸 일은 없지만(시작 가능한 마디만 고르게 한다) 방어적으로 둔다. */
        private val FALLBACK = TimeSignature.DEFAULT

        /**
         * 연동을 시작할 수 있는 마디 — 박자를 아는 마디부터. 비어 있으면 연동하지 않고 일반 메트로놈으로 돈다
         * (박자표를 못 읽은 파일, 악보 분석이 안 되는 파일 — 사용자 결정).
         */
        fun startableMeasures(measures: List<ScoreMeasure>): List<ScoreMeasure> {
            val ordered = measures.sortedBy { it.measureNumber }
            val first = ordered.indexOfFirst { it.timeSigNumerator != null }
            return if (first < 0) emptyList() else ordered.subList(first, ordered.size)
        }
    }
}
