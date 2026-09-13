package com.mrgq.pdfviewer.metronome

/**
 * 박 하나.
 *
 * @param frame 오디오 스트림 시작부터의 샘플(프레임) 위치 — 이 위치에서 클릭이 시작된다
 * @param indexInBar 마디 안에서 몇 번째 박인가 (0 = 첫 박, 강조)
 * @param index 시작부터 몇 번째 박인가 (0부터) — 악보 연동이 이 번호로 마디를 센다
 */
data class Beat(
    val frame: Long,
    val indexInBar: Int,
    val beatsPerBar: Int,
    val bpm: Int,
    val index: Long = 0,
) {
    val isAccent: Boolean get() = indexInBar == 0
}

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
     * 다음 박을 돌려주고 그다음 박으로 전진한다. [bpm]·[beatsPerBar] 는 범위로 잘린다.
     *
     * @param barPosition 박 번호 → 마디 안 위치. 주면 [beatsPerBar] 로 세는 대신 이것을 쓴다 — 악보 연동에서
     *   박자표가 바뀌는 마디의 강박을 악보대로 맞춘다 ([ScoreFollower.beatInBarAt]).
     */
    fun next(bpm: Int, beatsPerBar: Int, barPosition: ((Long) -> Int)? = null): Beat {
        val tempo = bpm.coerceIn(MIN_BPM, MAX_BPM)
        val beats = beatsPerBar.coerceIn(MIN_BEATS, MAX_BEATS)
        val index = nextBeatIndex++

        val inBar = if (barPosition != null) {
            barPosition(index).coerceAtLeast(0)
        } else {
            if (nextIndexInBar >= beats) nextIndexInBar = 0
            nextIndexInBar.also { nextIndexInBar = (it + 1) % beats }
        }

        val beat = Beat(Math.round(nextFrame), inBar, beats, tempo, index)
        nextFrame += sampleRate * 60.0 / tempo
        return beat
    }

    companion object {
        const val MIN_BPM = 30
        const val MAX_BPM = 240
        const val DEFAULT_BPM = 120
        const val MIN_BEATS = 1
        const val MAX_BEATS = 12
        const val DEFAULT_BEATS = 4
    }
}
