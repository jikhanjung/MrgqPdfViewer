package com.mrgq.pdfviewer.follow

import kotlin.math.min

/**
 * **연주 시작 찾기** (P10 §11) — 첫 소리가 아니라 **악보 첫머리와 맞는 소리**에서 추적을 시작한다.
 * 첫 소리로 시작하면 연주 전 소리(말 · 조율 · 소음)에 정렬이 끌려가 처음 몇십 초를 헤맸다(사용자 보고 2026-09-29).
 *
 * 1초마다 최근 [winSec] 녹음을 (가) 시작 칸부터 [headSec] 의 악보, (나) 곡 곳곳의 같은 길이 창 [samples] 개에
 * 부분 DTW(걸음 (1,1) · (1,2) · (2,1) — 빠르기 0.5 ~ 2배)로 맞춰 **비율 = (가) / (나 중앙값)**.
 * 연주 전 소리는 ≈ 1.0, 첫머리를 치면 0.5 ~ 0.65. 비율이 [ratio] 아래로 [need] 번 이어지면 시작 — 정렬은 (가) 경로 끝 칸에서.
 *
 * Python `data/start_detect.py detect` 와 같은 계산.
 */
class StartDetector(
    private val score: Array<FloatArray>,
    private val startCol: Int,
    frameSec: Double,
    winSec: Double = 4.0,
    headSec: Double = 12.0,
    private val ratio: Double = 0.75,
    private val need: Int = 2,
    samples: Int = 10,
) {
    private val win = (winSec / frameSec).toInt()
    private val head = (headSec / frameSec).toInt()
    private val step = (1.0 / frameSec).toInt()
    private val recent = ArrayDeque<FloatArray>()
    private var frames = 0
    private var hits = 0
    private val others: IntArray

    /** 마지막 비율 (기록용) */
    var lastRatio = Double.NaN
        private set

    init {
        val far = startCol + head + (60.0 / frameSec).toInt()
        val last = score.size - head - 1
        others = if (last > far) {
            IntArray(samples) { k -> (far + (last - far).toDouble() * k / (samples - 1)).toInt() } // np.linspace → int
        } else intArrayOf(0)
    }

    /**
     * 정규화한 녹음 크로마 한 칸. 시작을 찾았으면 (그 칸 번호 — 넣은 순서 0부터, 정렬을 시작할 악보 칸), 아니면 null.
     * 칸 번호 i 는 Python 의 i 와 같다: 최근 창 = [i − win, i) — 곧 **이번 칸은 아직 창에 들지 않는다**
     */
    fun feed(chroma: FloatArray): Pair<Int, Int>? {
        val i = frames // 이번 칸의 번호 = 지금까지 넣은 칸 수
        var found: Pair<Int, Int>? = null
        if (i >= win && (i - win) % step == 0) { // Python: range(W, n, step)
            val window = recent.toList()
            val (c0, j) = subseqMin(window, startCol, min(score.size, startCol + head))
            val costs = others.map { st -> subseqMin(window, st, min(score.size, st + head)).first }.sorted()
            val median = if (costs.size % 2 == 1) costs[costs.size / 2] else (costs[costs.size / 2 - 1] + costs[costs.size / 2]) / 2
            lastRatio = c0 / median
            hits = if (lastRatio < ratio) hits + 1 else 0
            if (hits >= need) found = i to (startCol + j)
        }
        recent.addLast(chroma)
        if (recent.size > win) recent.removeFirst()
        frames++
        return found
    }

    /** 부분 DTW (librosa subseq, 걸음 (1,1) · (1,2) · (2,1)) — 마지막 줄 최소 / 창 길이, 그 칸 (구간 안 순번) */
    private fun subseqMin(q: List<FloatArray>, a: Int, b: Int): Pair<Double, Int> {
        val w = b - a
        if (w <= 0) return Double.POSITIVE_INFINITY to 0
        val n = q.size
        val d = Array(n) { DoubleArray(w) { INF } }
        for (c in 0 until w) d[0][c] = cost(q[0], a + c)
        for (r in 1 until n) {
            for (c in 0 until w) {
                var best = if (c >= 1) d[r - 1][c - 1] else INF // (1,1)
                if (c >= 2 && d[r - 1][c - 2] < best) best = d[r - 1][c - 2] // (1,2)
                if (r >= 2 && c >= 1 && d[r - 2][c - 1] < best) best = d[r - 2][c - 1] // (2,1)
                d[r][c] = if (best == INF) INF else cost(q[r], a + c) + best
            }
        }
        var minV = INF
        var minC = 0
        for (c in 0 until w) if (d[n - 1][c] < minV) { minV = d[n - 1][c]; minC = c }
        return minV / n to minC
    }

    private fun cost(x: FloatArray, col: Int): Double {
        val y = score[col]
        var dot = 0.0
        for (k in 0 until 12) dot += x[k].toDouble() * y[k]
        return 1 - dot
    }

    companion object {
        private const val INF = Double.POSITIVE_INFINITY
    }
}
