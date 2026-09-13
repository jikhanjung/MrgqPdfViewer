package com.mrgq.pdfviewer.metronome

import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/**
 * 메트로놈 박 번호를 악보 위치로 옮긴다 (#050).
 *
 * 박 0 부터 **시작 마디의 박자로 예비박 한 마디**를 세고, 그다음부터 각 마디의 박자표 분자만큼 박을 센다.
 * 박자표가 비어 있는 마디는 앞 마디의 박자를 이어 쓴다. 반복 기호는 따르지 않는다 (악보에 적힌 순서대로).
 *
 * 박은 박자표 분모 음표 하나다 — 6/8 이면 한 마디에 8분음표 6박. 불변 객체라 오디오 스레드에서 읽어도 된다.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class ScoreFollower(measures: List<ScoreMeasure>, startMeasureNumber: Int) {

    sealed interface Position {
        /** 예비박 [beatInBar] 번째 (0부터) */
        data class CountIn(val beatInBar: Int, val timeSignature: TimeSignature) : Position

        /** [measure] 의 [beatInMeasure] 번째 박. [next] 는 다음 마디 (마지막이면 null) — 미리 넘기기에 쓴다 */
        data class InMeasure(
            val measure: ScoreMeasure,
            val beatInMeasure: Int,
            val timeSignature: TimeSignature,
            val next: ScoreMeasure?,
        ) : Position {
            val beatsInMeasure: Int get() = timeSignature.numerator
        }

        /** 마지막 마디가 끝났다 */
        object Finished : Position
    }

    private val playing: List<ScoreMeasure>
    private val meters: Array<TimeSignature>
    private val firstBeat: LongArray
    private val totalBeats: Long

    /** 시작 마디의 박자 — 예비박도 이 박자로 센다 */
    val startTimeSignature: TimeSignature

    /** 예비박 수 = 시작 마디 박자 */
    val countInBeats: Int get() = startTimeSignature.numerator

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
        startTimeSignature = meters.first()

        firstBeat = LongArray(playing.size)
        var beat = countInBeats.toLong()
        for (i in playing.indices) {
            firstBeat[i] = beat
            beat += meters[i].numerator
        }
        totalBeats = beat
    }

    fun positionAt(beatIndex: Long): Position {
        if (beatIndex < countInBeats) return Position.CountIn(beatIndex.coerceAtLeast(0).toInt(), startTimeSignature)
        if (beatIndex >= totalBeats) return Position.Finished
        var lo = 0
        var hi = playing.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (firstBeat[mid] <= beatIndex) lo = mid else hi = mid - 1
        }
        return Position.InMeasure(
            measure = playing[lo],
            beatInMeasure = (beatIndex - firstBeat[lo]).toInt(),
            timeSignature = meters[lo],
            next = playing.getOrNull(lo + 1),
        )
    }

    /** 강박 위치와 그 마디 박자 — [MetronomeEngine.barPosition] 으로 넘긴다. */
    fun barPositionAt(beatIndex: Long): BarPosition = when (val p = positionAt(beatIndex)) {
        is Position.CountIn -> BarPosition(p.beatInBar, p.timeSignature)
        is Position.InMeasure -> BarPosition(p.beatInMeasure, p.timeSignature)
        Position.Finished -> BarPosition(0, meters.last())
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
