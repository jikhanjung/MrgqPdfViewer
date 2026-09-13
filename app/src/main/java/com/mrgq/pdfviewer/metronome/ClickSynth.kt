package com.mrgq.pdfviewer.metronome

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 메트로놈 클릭음을 코드로 만든다 (16bit PCM, 모노). 음원 파일이 필요 없고 샘플레이트에 맞춰 생성된다.
 *
 * 짧은 사인파에 빠른 감쇠를 씌운 "틱" 소리. 첫 박은 더 높고(A6) 크게, 나머지는 낮고(D6) 작게,
 * 겹박자 묶음의 첫 박(6/8 의 4박)은 그 사이(F6) 세기로.
 * 시작 1ms 는 서서히 올려 클릭 앞머리의 "딱" 하는 잡음을 막는다.
 */
object ClickSynth {

    const val DURATION_MS = 35
    const val ACCENT_HZ = 1760.0
    const val MEDIUM_HZ = 1397.0
    const val NORMAL_HZ = 1175.0

    private const val ACCENT_GAIN = 0.9
    private const val MEDIUM_GAIN = 0.75
    private const val NORMAL_GAIN = 0.6
    private const val DECAY_PER_SEC = 120.0
    private const val ATTACK_SEC = 0.001

    fun render(sampleRate: Int, accent: Accent): ShortArray {
        val length = sampleRate * DURATION_MS / 1000
        val (freq, gain) = when (accent) {
            Accent.STRONG -> ACCENT_HZ to ACCENT_GAIN
            Accent.MEDIUM -> MEDIUM_HZ to MEDIUM_GAIN
            Accent.WEAK -> NORMAL_HZ to NORMAL_GAIN
        }
        val attackSamples = sampleRate * ATTACK_SEC
        return ShortArray(length) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * DECAY_PER_SEC) * min(1.0, i / attackSamples)
            (sin(2 * PI * freq * t) * envelope * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
