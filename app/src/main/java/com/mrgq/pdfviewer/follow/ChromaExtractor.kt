package com.mrgq.pdfviewer.follow

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 마이크 소리 → **STFT 크로마**(12음 성분) — P10 §3.1, 0단계에서 CQT 대신 채택(P10 §6).
 *
 * Python `data/score_follow_dtw.py stft_chroma(weight="mag")` 와 같은 계산: Hann(주기형) 창 [nFft], 걸음 [hop],
 * [fmin] ~ [fmax] 의 빈마다 음높이 p = 69 + 12·log2(f/440), 가장 가까운 반음 n 에 무게 max(0, 1 − 2|p − n|) × 세기.
 * 44.1kHz · 4096 · 2048 = 22.05kHz · 2048 · 1024 와 같은 칸(≈ 46ms)이다.
 *
 * 소리는 [push] 로 조금씩 넣는다. 창이 찰 때마다 [onFrame] 으로 정규화하지 않은 크로마(12)를 낸다.
 */
class ChromaExtractor(
    val sampleRate: Int = 44100,
    val nFft: Int = 4096,
    val hop: Int = 2048,
    fmin: Double = 60.0,
    fmax: Double = 4000.0,
    private val onFrame: (FloatArray) -> Unit,
) {
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) }
    private val bins: IntArray
    private val pcs: IntArray
    private val weights: DoubleArray
    private val fft = Fft(nFft)
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val buffer = FloatArray(nFft)
    private var filled = 0

    /** 지금까지 낸 칸 수 */
    var frames = 0L
        private set

    val frameSec: Double get() = hop.toDouble() / sampleRate

    init {
        val b = ArrayList<Int>()
        val p = ArrayList<Int>()
        val w = ArrayList<Double>()
        for (k in 0..nFft / 2) {
            val f = k.toDouble() * sampleRate / nFft
            if (f < fmin || f > fmax) continue
            val pitch = 69 + 12 * ln(f / 440.0) / ln(2.0)
            val near = round(pitch) // numpy round 와 같이 반올림은 짝수 쪽 (kotlin.math.round = half-even)
            val weight = max(0.0, 1 - 2 * abs(pitch - near))
            if (weight <= 0) continue
            b += k
            p += Math.floorMod(near.toInt(), 12)
            w += weight
        }
        bins = b.toIntArray()
        pcs = p.toIntArray()
        weights = w.toDoubleArray()
    }

    /** 16비트 소리 [count] 개 */
    fun push(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            buffer[filled++] = samples[i] / 32768f
            if (filled == nFft) {
                onFrame(frame())
                System.arraycopy(buffer, hop, buffer, 0, nFft - hop)
                filled = nFft - hop
            }
        }
    }

    /** 실수 소리 (테스트 · 파일용) */
    fun push(samples: FloatArray) {
        for (s in samples) {
            buffer[filled++] = s
            if (filled == nFft) {
                onFrame(frame())
                System.arraycopy(buffer, hop, buffer, 0, nFft - hop)
                filled = nFft - hop
            }
        }
    }

    private fun frame(): FloatArray {
        for (i in 0 until nFft) {
            re[i] = buffer[i] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val out = FloatArray(12)
        for (n in bins.indices) {
            val k = bins[n]
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            out[pcs[n]] += (mag * weights[n]).toFloat()
        }
        frames++
        return out
    }
}

/** 제자리 복소 FFT (기수 2, 반복형). 크기는 2의 거듭제곱 */
class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val levels = Integer.numberOfTrailingZeros(n)

    init {
        require(n > 1 && n and (n - 1) == 0) { "FFT 크기는 2의 거듭제곱: $n" }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val tr = re[l] * cosT[k] - im[l] * sinT[k]
                    val ti = re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/** 크로마 한 칸을 길이 1 로 (모두 0 이면 그대로) — Python `normalize` (c / (|c| + 1e-9)) */
fun FloatArray.normalized(): FloatArray {
    var s = 0.0
    for (v in this) s += v.toDouble() * v
    val norm = sqrt(s) + 1e-9
    return FloatArray(size) { (this[it] / norm).toFloat() }
}
