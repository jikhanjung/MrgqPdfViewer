package com.mrgq.pdfviewer.follow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 녹음 칸이 하나씩 들어올 때마다 **악보의 어디인지**(기준 크로마 칸)를 내는 온라인 정렬 (P10 §3.1, 실험은 P08 §7 · §7-2).
 * Python `data/score_follow_dtw.py oltw(reloc=…)` 를 그대로 옮겼다 — 0단계 성적(P10 §6)이 이 계산의 것이다.
 *
 * 1. **OLTW** (Dixon MATCH 식): 누적 비용 D(i,j) = min(D(i−1,j) + d, D(i,j−1) + d, D(i−1,j−1) + 2d) 한 줄을 지금 위치 ±[bandSec] 띠 안에서만.
 *    지금 위치 = 띠 안에서 D / (걸음 수) 가 가장 작은 곳, 앞으로만 (d = 1 − 코사인)
 * 2. **주기적 재위치**: [relocEverySec] 마다 최근 [relocWindowSec] 녹음을 지금 위치 ±[relocRangeSec] 악보에 부분 DTW 로 다시 맞춘다.
 *    후보(끝 비용이 최선의 1.1배 안인 국소 최소 — 똑같은 반복이면 여럿) 중 **시작부터의 평균 빠르기로 예상한 곳**에 가까운 것을 고르고,
 *    끝 [relocTailSec] 의 평균 거리가 지금 경로보다 [relocGain] 배 좋고 1초 넘게 다르면 거기로 뛰어 OLTW 를 다시 시작
 *
 * [feed] 에 **정규화한** 녹음 크로마를 넣는다. 첫 칸이 시작 칸([startCol])에 놓인다 — 앱은 시작 마디와 첫 소리 시각을 안다.
 */
class OnlineAligner(
    private val score: Array<FloatArray>,
    private val startCol: Int,
    frameSec: Double,
    bandSec: Double = 3.0,
    relocEverySec: Double = 1.0,
    relocWindowSec: Double = 30.0,
    relocRangeSec: Double = 40.0,
    relocTailSec: Double = 5.0,
    private val relocGain: Double = 1.15,
    private val relocStruct: Double = 0.5,
    private val reloc: Boolean = true,
) {
    private val m = score.size
    private val band = (bandSec / frameSec).toInt()
    private val rEvery = (relocEverySec / frameSec).toInt()
    private val rWin = (relocWindowSec / frameSec).toInt()
    private val rRange = (relocRangeSec / frameSec).toInt()
    private val rTail = min(rWin, (relocTailSec / frameSec).toInt())
    private val oneSecond = 1.0 / frameSec

    private val rec = ArrayList<FloatArray>()
    private var path = IntArray(1024)
    private var prev = DoubleArray(m) { INF }
    private var cur = DoubleArray(m) { INF }
    private var prevLo = 0
    private var prevHi = 0
    private var curLo = 0
    private var curHi = 0
    private var est = startCol
    private var restart = 0
    private var restartCol = startCol

    // 재위치용 (가장 큰 크기로 한 번)
    private var dtwD = DoubleArray(0)
    private var dtwSteps = ByteArray(0)

    /** 재위치로 뛴 기록: (녹음 칸, 전 칸, 새 칸) */
    val jumps = ArrayList<Triple<Int, Int, Int>>()

    /** 지금까지 넣은 칸 수 */
    val frames: Int get() = rec.size

    /** 마지막 추정 (악보 칸) */
    val position: Int get() = est

    /** 녹음 칸 [i] 에서 추정했던 악보 칸 */
    fun positionAt(i: Int): Int = path[i]

    /** 정규화한 녹음 크로마 한 칸 → 지금 악보 칸 */
    fun feed(chroma: FloatArray): Int {
        val i = rec.size
        rec += chroma
        if (i >= path.size) path = path.copyOf(path.size * 2)
        if (i == 0) {
            prev[startCol] = 2 * cost(0, startCol)
            prevLo = startCol
            prevHi = startCol + 1
            est = startCol
            path[0] = est
            return est
        }
        if (reloc && i >= rWin && rEvery > 0 && i % rEvery == 0) relocate(i)

        val lo = max(0, est - band)
        val hi = min(m, est + band)
        // cur 를 비운다 (지난번에 쓴 곳만)
        for (j in curLo until curHi) cur[j] = INF
        var bestNorm = INF
        var bestJ = lo
        for (j in lo until hi) {
            val d = cost(i, j)
            val a = prevAt(j) + d // 녹음만 진행
            val diag = if (j > 0) prevAt(j - 1) + 2 * d else INF
            var v = min(a, diag)
            if (j > lo) v = min(v, cur[j - 1] + d) // 악보만 진행 (같은 칸 안)
            cur[j] = v
            val steps = (i - restart) + (j - restartCol) + 1
            val norm = v / max(steps, 1)
            if (norm < bestNorm) {
                bestNorm = norm
                bestJ = j
            }
        }
        curLo = lo
        curHi = hi
        est = if (i - 1 != restart) max(est, bestJ) else bestJ
        path[i] = est
        // 바꾸기
        val t = prev; prev = cur; cur = t
        val tl = prevLo; prevLo = curLo; curLo = tl
        val th = prevHi; prevHi = curHi; curHi = th
        return est
    }

    private fun prevAt(j: Int): Double = if (j in prevLo until prevHi) prev[j] else INF

    private fun cost(i: Int, j: Int): Double {
        val a = rec[i]
        val b = score[j]
        var dot = 0.0
        for (k in 0 until 12) dot += a[k].toDouble() * b[k]
        return 1 - dot
    }

    private fun relocate(i: Int) {
        val a = i - rWin
        val here = path[i - 1]
        val loR = max(0, here - rRange)
        val hiR = min(m, here + rRange)
        val w = hiR - loR
        if (w < 3) return
        val rows = rWin
        if (dtwD.size < rows * w) {
            dtwD = DoubleArray(rows * w)
            dtwSteps = ByteArray(rows * w)
        }
        val d = dtwD
        val st = dtwSteps
        // 부분 DTW (librosa sequence.dtw subseq=True, 걸음 (1,1) · (0,1) · (1,0), 같으면 이 순서)
        for (c in 0 until w) d[c] = cost(a, loR + c)
        for (r in 1 until rows) {
            val base = r * w
            val up = (r - 1) * w
            for (c in 0 until w) {
                val cc = cost(a + r, loR + c)
                var best = if (c > 0) d[up + c - 1] else INF // (1,1) 대각선
                var step: Byte = 0
                if (c > 0 && d[base + c - 1] < best) { best = d[base + c - 1]; step = 1 } // (0,1) 같은 녹음 칸, 악보만
                if (d[up + c] < best) { best = d[up + c]; step = 2 } // (1,0) 녹음만
                d[base + c] = cc + best
                st[base + c] = step
            }
        }
        val lastBase = (rows - 1) * w
        var minLast = INF
        for (c in 0 until w) minLast = min(minLast, d[lastBase + c])
        // 시작부터의 평균 빠르기로 "지금쯤 여기"
        val tempo = if (i > 5 * rEvery) {
            ((path[i - 1] - startCol).toDouble() / max(1, i - 1)).coerceIn(0.5, 2.0)
        } else 1.0
        val expected = startCol + (i - 1) * tempo
        fun score(col: Int, cost: Double) = cost * (1 + relocStruct * abs(col - expected) / rRange)

        var bestScore = INF
        var bestCol = -1
        for (k in 1 until w - 1) {
            val v = d[lastBase + k]
            if (v > d[lastBase + k - 1] || v > d[lastBase + k + 1] || v > minLast * 1.1) continue
            // 되짚기 — 끝 rTail 칸의 평균 거리
            var r = rows - 1
            var c = k
            var sum = 0.0
            var cnt = 0
            while (true) {
                if (r >= rows - rTail) { sum += cost(a + r, loR + c); cnt++ }
                if (r <= 0) break
                when (st[r * w + c].toInt()) {
                    0 -> { r -= 1; c -= 1 }
                    1 -> c -= 1
                    else -> r -= 1
                }
                if (r < 0 || c < 0) break
            }
            val s = score(loR + k, sum / max(1, cnt))
            if (s < bestScore) { bestScore = s; bestCol = loR + k }
        }
        if (bestCol < 0) return
        var curSum = 0.0
        for (r in i - rTail until i) curSum += cost(r, path[r])
        val curCost = curSum / rTail
        if (bestScore * relocGain < score(here, curCost) && abs(bestCol - here) > oneSecond) {
            jumps += Triple(i, here, bestCol)
            est = bestCol
            for (j in prevLo until prevHi) prev[j] = INF
            prev[est] = 2 * cost(i - 1, est)
            prevLo = est
            prevHi = est + 1
            restart = i - 1
            restartCol = est
        }
    }

    companion object {
        private const val INF = Double.POSITIVE_INFINITY
    }
}
