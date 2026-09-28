package com.mrgq.pdfviewer.metronome

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 반주 소리 — **단순 합성** (P07 §2.7: 악기 소리 SF2 는 나중에). 배음 몇 개를 담은 한 주기 파형표를 음높이만큼 빠르기를 바꿔 읽고,
 * 짧은 어택 · 천천히 줄어드는 소리 · 끝나면 짧은 릴리스. 파트마다 배음을 조금 달리해 구분되게 한다.
 * 메트로놈 오디오 스레드 하나에서만 부른다 (동기화 없음). 합성기를 바꿀 자리는 이 클래스 하나.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class AccompanimentVoices(private val sampleRate: Int) {

    private val tables = Array(TIMBRES.size) { t -> table(TIMBRES[t]) }

    private val active = BooleanArray(MAX_VOICES)
    private val start = LongArray(MAX_VOICES)
    private val end = LongArray(MAX_VOICES)
    private val phase = DoubleArray(MAX_VOICES)
    private val step = DoubleArray(MAX_VOICES)
    private val timbre = IntArray(MAX_VOICES)

    private val attackFrames = (sampleRate * ATTACK_SEC).toInt().coerceAtLeast(1)
    private val releaseFrames = (sampleRate * RELEASE_SEC).toInt().coerceAtLeast(1)
    private val decayPerFrame = exp(-1.0 / (sampleRate * DECAY_SEC))

    /** 울리는 음 수 (테스트용) */
    val activeCount: Int get() = active.count { it }

    /** 프레임 [startFrame] 부터 [endFrame] 까지 [midi] 음. 빈 자리가 없으면 가장 먼저 시작한 음을 밀어낸다 */
    fun noteOn(startFrame: Long, endFrame: Long, midi: Int, part: Int) {
        var slot = active.indexOfFirst { !it }
        if (slot < 0) slot = start.indices.minByOrNull { start[it] } ?: 0
        active[slot] = true
        start[slot] = startFrame
        end[slot] = maxOf(endFrame, startFrame + attackFrames)
        phase[slot] = 0.0
        step[slot] = 440.0 * 2.0.pow((midi - 69) / 12.0) * TABLE_SIZE / sampleRate
        timbre[slot] = part % TIMBRES.size
    }

    fun clear() {
        active.fill(false)
    }

    /** [out] 의 [count] 프레임(스트림 프레임 [firstFrame] 부터)에 더한다. [gain] 0~1 */
    fun mixInto(out: IntArray, firstFrame: Long, count: Int, gain: Float) {
        if (gain <= 0f) {
            // 소리는 끄되 시간은 흐르게 — 끝난 음은 정리한다
            for (v in 0 until MAX_VOICES) if (active[v] && end[v] + releaseFrames < firstFrame + count) active[v] = false
            return
        }
        val amplitude = VOICE_LEVEL * gain
        for (v in 0 until MAX_VOICES) {
            if (!active[v]) continue
            val table = tables[timbre[v]]
            val s = start[v]
            val e = end[v]
            // 줄어드는 소리는 곱셈으로 이어 간다 (프레임마다 pow 는 비싸다) — 이 청크 첫 프레임의 값만 한 번 계산
            val first = maxOf(firstFrame, s)
            var decay = decayPerFrame.pow(maxOf(0L, minOf(first, e) - s - attackFrames).toDouble())
            val held = decayPerFrame.pow(maxOf(0L, e - s - attackFrames).toDouble()).coerceAtLeast(SUSTAIN_FLOOR)
            var p = phase[v]
            val inc = step[v]
            var i = (first - firstFrame).toInt()
            while (i < count) {
                val frame = firstFrame + i
                val age = frame - s
                val env = when {
                    age < attackFrames -> age.toDouble() / attackFrames
                    frame < e -> {
                        val d = decay.coerceAtLeast(SUSTAIN_FLOOR)
                        decay *= decayPerFrame
                        d
                    }
                    frame < e + releaseFrames -> held * (1.0 - (frame - e).toDouble() / releaseFrames)
                    else -> {
                        active[v] = false
                        break
                    }
                }
                out[i] += (table[p.toInt() and (TABLE_SIZE - 1)] * env * amplitude).toInt()
                p += inc
                if (p >= TABLE_SIZE) p -= TABLE_SIZE
                i++
            }
            phase[v] = p
        }
    }

    private fun table(harmonics: DoubleArray): FloatArray {
        val sum = harmonics.sum()
        return FloatArray(TABLE_SIZE) { i ->
            var x = 0.0
            for ((h, a) in harmonics.withIndex()) x += a * sin(2 * PI * (h + 1) * i / TABLE_SIZE)
            (x / sum * Short.MAX_VALUE).toFloat()
        }
    }

    companion object {
        const val MAX_VOICES = 24
        private const val TABLE_SIZE = 2048
        private const val ATTACK_SEC = 0.008
        private const val RELEASE_SEC = 0.06
        /** 소리가 1/e 로 줄어드는 시간 — 길게 끄는 음도 너무 빨리 사라지지 않게 [SUSTAIN_FLOOR] 에서 멈춘다 */
        private const val DECAY_SEC = 1.2
        private const val SUSTAIN_FLOOR = 0.35
        /** 음 하나의 크기 — 여러 음이 겹쳐도 넘치지 않게 작게 (마지막에 자른다) */
        private const val VOICE_LEVEL = 0.22
        /** 파트마다 배음 (1 · 2 · 3 · 4 배) — 파트 0 은 부드럽게, 1 은 밝게, 2 는 속이 빈 소리 */
        private val TIMBRES = arrayOf(
            doubleArrayOf(1.0, 0.35, 0.12, 0.05),
            doubleArrayOf(1.0, 0.6, 0.35, 0.2),
            doubleArrayOf(1.0, 0.05, 0.4, 0.05),
            doubleArrayOf(1.0, 0.25, 0.25, 0.1),
        )
    }
}
