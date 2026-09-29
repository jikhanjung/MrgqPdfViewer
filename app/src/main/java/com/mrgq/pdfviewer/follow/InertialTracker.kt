package com.mrgq.pdfviewer.follow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **관성 항법** 위치 거르개 (P10 §9, 사용자 제안 2026-09-29) — 정렬기의 칸별 추정 z 를 그대로 쓰지 않는다.
 * 연주는 대체로 고른 빠르기로 이어진다고 보고, 짧게 헤매는 추정은 무시한다 (서버 부하 그래프의 시간 평균과 비슷한 효과).
 *
 * - 예측 x̂ = x + v (v = 빠르기, 악보 칸 / 녹음 칸, [V_MIN] ~ [V_MAX])
 * - |z − x̂| ≤ 문([gateSec]) → 받아들임: x = x̂ + α(z − x̂), v += β(z − x̂) (α-β 거르개)
 * - 문 밖 → 관성으로 x = x̂. 그런데 z 가 [switchSec] 동안 계속 문 밖에서 **스스로 고른 빠르기로**(직선 맞춤 기울기가 범위 안, 흩어짐이 문 안) 가면 그쪽으로 옮긴다
 *
 * Python `data/inertia_eval.py smooth` 와 같은 계산 — 아르페지오네에서 최대 오차 −7.2/+9.5 → −3.1/+2.0마디, 가장 이른 쪽 넘김 10.8 → 2.0초.
 */
class InertialTracker(
    start: Double,
    frameSec: Double,
    gateSec: Double = 1.0,
    private val alpha: Double = 0.15,
    private val beta: Double = 0.002,
    switchSec: Double = 3.0,
) {
    private val gate = gateSec / frameSec
    private val switchFrames = (switchSec / frameSec).toInt()
    private val recent = DoubleArray(switchFrames)
    private var recentCount = 0
    private var recentHead = 0

    /** 거른 위치 (악보 칸, 실수) */
    var position = start
        private set

    /** 빠르기 (악보 칸 / 녹음 칸 — 1 = 기준 빠르기) */
    var velocity = 1.0
        private set

    private var outside = 0

    /** 옮긴 횟수 */
    var switches = 0
        private set

    /** 첫 칸은 [start] 그대로 — 이후 칸마다 날것 추정 [z] 를 넣는다 */
    fun feed(z: Double): Double {
        push(z)
        val pred = position + velocity
        val r = z - pred
        if (abs(r) <= gate) {
            position = pred + alpha * r
            velocity = (velocity + beta * r).coerceIn(V_MIN, V_MAX)
            outside = 0
        } else {
            position = pred
            outside++
            if (outside >= switchFrames && recentCount >= switchFrames) {
                // 최근 switchFrames 칸 직선 맞춤 (np.polyfit 1차)
                val n = switchFrames
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (k in 0 until n) {
                    val y = recentAt(k)
                    sx += k; sy += y; sxx += k.toDouble() * k; sxy += k * y
                }
                val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
                val icpt = (sy - slope * sx) / n
                var resid = 0.0
                for (k in 0 until n) resid = max(resid, abs(recentAt(k) - (slope * k + icpt)))
                if (slope in V_MIN..V_MAX && resid <= gate) {
                    position = z
                    velocity = slope
                    switches++
                    outside = 0
                }
            }
        }
        return position
    }

    /** 손으로 넘겼을 때 — [at] 에서 다시 (빠르기는 그대로) */
    fun reset(at: Double) {
        position = at
        outside = 0
        recentCount = 0
        recentHead = 0
    }

    private fun push(z: Double) {
        recent[recentHead] = z
        recentHead = (recentHead + 1) % recent.size
        recentCount = min(recentCount + 1, recent.size)
    }

    /** 최근 칸 중 오래된 것부터 k 번째 */
    private fun recentAt(k: Int): Double = recent[(recentHead + k) % recent.size]

    companion object {
        const val V_MIN = 0.5
        const val V_MAX = 2.0
    }
}
