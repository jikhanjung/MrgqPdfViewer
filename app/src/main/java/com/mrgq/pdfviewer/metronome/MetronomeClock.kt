package com.mrgq.pdfviewer.metronome

/**
 * 박 하나.
 *
 * @param frame 오디오 스트림 시작부터의 샘플(프레임) 위치 — 이 위치에서 클릭이 시작된다
 * @param indexInBar 마디 안에서 몇 번째 박인가 (0 = 첫 박, 강조)
 * @param timeSignature 이 박이 속한 마디의 박자 — 클릭 세기([accent])를 정한다
 * @param index 시작부터 몇 번째 박인가 (0부터) — 악보 연동이 이 번호로 마디를 센다
 * @param dotted 겹박자를 점음표 박으로 세는 중인가 ([TimeSignature.beatsPerBar])
 */
data class Beat(
    val frame: Long,
    val indexInBar: Int,
    val timeSignature: TimeSignature,
    val bpm: Int,
    val index: Long = 0,
    val dotted: Boolean = false,
) {
    val beatsPerBar: Int get() = timeSignature.beatsPerBar(dotted)
    val accent: Accent get() = timeSignature.accentAt(indexInBar, dotted)
    val isAccent: Boolean get() = indexInBar == 0
}

/** 악보 연동에서 박 번호가 가리키는 마디 안 위치와 그 마디의 박자·박 단위. */
data class BarPosition(val indexInBar: Int, val timeSignature: TimeSignature, val dotted: Boolean = false)

/**
 * 박 위치를 **샘플 단위로** 계산한다. 타이머(Handler·delay)가 아니라 오디오 스트림의 프레임 수로
 * 박을 놓기 때문에 흔들림(jitter)이 없다.
 *
 * - 박 간격을 실수로 누적하고 박마다 반올림한다 → 97 BPM 처럼 나누어떨어지지 않는 템포도 누적 오차가 없다
 * - 템포·박자 변경은 **다음 박부터** 적용된다. 이미 놓인 박을 옮기지 않으니 연주 중 조절해도 박이 튀지 않는다
 * - 박자를 줄여서 현재 위치가 마디를 넘으면 새 마디의 첫 박부터 센다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class MetronomeClock(private val sampleRate: Int, startFrame: Long = 0) {

    private var nextFrame = startFrame.toDouble()
    private var nextIndexInBar = 0
    private var nextBeatIndex = 0L

    /**
     * 다음 박을 돌려주고 그다음 박으로 전진한다. [bpm]·[timeSignature] 는 범위로 잘린다.
     *
     * @param dotted 겹박자를 점음표 박으로 센다 — [bpm] 도 점음표 기준
     * @param barPosition 박 번호 → 마디 안 위치와 그 마디 박자·박 단위. 주면 [timeSignature]·[dotted] 로 세는 대신 이것을 쓴다 —
     *   악보 연동에서 박자표가 바뀌는 마디의 강박을 악보대로 맞춘다 ([ScoreFollower.barPositionAt]).
     */
    fun next(
        bpm: Int,
        timeSignature: TimeSignature,
        dotted: Boolean = false,
        barPosition: ((Long) -> BarPosition)? = null,
    ): Beat {
        val tempo = bpm.coerceIn(MIN_BPM, MAX_BPM)
        val index = nextBeatIndex++

        val meter: TimeSignature
        val beatDotted: Boolean
        val inBar: Int
        if (barPosition != null) {
            val position = barPosition(index)
            meter = position.timeSignature.coerced()
            beatDotted = position.dotted
            inBar = position.indexInBar.coerceIn(0, meter.beatsPerBar(beatDotted) - 1)
        } else {
            meter = timeSignature.coerced()
            beatDotted = dotted
            val beats = meter.beatsPerBar(beatDotted)
            if (nextIndexInBar >= beats) nextIndexInBar = 0
            inBar = nextIndexInBar
            nextIndexInBar = (inBar + 1) % beats
        }

        val beat = Beat(Math.round(nextFrame), inBar, meter, tempo, index, beatDotted)
        nextFrame += sampleRate * 60.0 / tempo
        return beat
    }

    companion object {
        /** 느린 겹박자를 점음표로 셀 때(8분음표 60 = 점4분음표 20)도 빠르기가 유지되게 20 부터 (#052) */
        const val MIN_BPM = 20
        const val MAX_BPM = 240
        const val DEFAULT_BPM = 120
        const val MIN_BEATS = 1
        const val MAX_BEATS = 12
        const val DEFAULT_BEATS = 4
    }
}
