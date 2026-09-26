package com.mrgq.pdfviewer.ensemble

import kotlin.math.abs

/**
 * 지휘자 시계와 내 시계의 차이(offset)를 핑퐁 표본으로 추정한다 (#055 §3.1).
 *
 * 표본 하나: 내가 `t0` 에 보내고, 지휘자가 자기 시계 `serverNs` 에 받아 답하고, 내가 `t3` 에 받는다.
 * 전송 지연이 양방향 같다고 보면 `offset = serverNs − (t0 + t3) / 2` 이고, 오차는 최대 `rtt / 2` 다.
 * 그래서 **최근 [window] 개 중 RTT 가 가장 작은 표본**을 믿는다 — Wi-Fi 에서 가끔 튀는 지연을 걸러낸다.
 *
 * 새 추정이 조금 다르면 한 번에 [maxStepNs] 씩만 옮긴다 (연주 중 박이 튀지 않게, 드리프트 따라가기엔 충분).
 * [jumpNs] 보다 크게 다르면(첫 추정 직후 · 재연결) 바로 옮긴다.
 *
 * 시계는 양쪽 모두 `System.nanoTime()` (단조 시계). Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class ClockSync(
    private val window: Int = 16,
    private val maxStepNs: Long = 1_000_000L,
    private val jumpNs: Long = 20_000_000L,
) {
    data class Sample(val rttNs: Long, val offsetNs: Long)

    private val samples = ArrayDeque<Sample>()

    /** 지휘자 시계 − 내 시계 (ns). 표본이 없으면 null */
    @Volatile var offsetNs: Long? = null
        private set

    /** 지금 쓰는 표본의 RTT — 추정 오차는 이것의 절반 이하 */
    @Volatile var bestRttNs: Long? = null
        private set

    @Synchronized
    fun addSample(t0: Long, serverNs: Long, t3: Long): Sample? {
        val rtt = t3 - t0
        if (rtt < 0) return null
        // (t0 + t3) / 2 를 넘치지 않게
        val sample = Sample(rtt, serverNs - (t0 + rtt / 2))
        samples.addLast(sample)
        while (samples.size > window) samples.removeFirst()

        val best = samples.minByOrNull { it.rttNs }!!
        bestRttNs = best.rttNs
        val current = offsetNs
        offsetNs = if (current == null || abs(best.offsetNs - current) > jumpNs) {
            best.offsetNs
        } else {
            current + (best.offsetNs - current).coerceIn(-maxStepNs, maxStepNs)
        }
        return sample
    }

    @Synchronized
    fun reset() {
        samples.clear()
        offsetNs = null
        bestRttNs = null
    }

    val sampleCount: Int @Synchronized get() = samples.size
}
