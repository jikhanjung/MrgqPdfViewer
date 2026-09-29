package com.mrgq.pdfviewer.follow

import com.mrgq.pdfviewer.score.MusicXmlScore
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 악보(MusicXML) → 녹음과 같은 칸의 **기준 크로마** (P10 §3.1). 합성하지 않고 음표에서 바로:
 * 음마다 시작 칸부터 끝 칸까지 그 음이름에 친 뒤 [DECAY_SEC] 로 줄어드는 무게를 더하고, 칸마다 정규화.
 * Python `data/score_follow_dtw.py score_chroma` 와 같은 계산.
 *
 * 시간 축: 4분음표 단위 위치 q → 초 = q × [secPerQuarter] (기준 빠르기) → 칸 = 초 / [frameSec].
 * 마디 길이는 박자표 (분자 × 4 / 분모 4분음표). 마디 번호 대신 **MusicXML 마디 순서**(0부터)로 다룬다.
 */
class ScoreChroma private constructor(
    /** 칸마다 정규화한 12음 */
    val frames: Array<FloatArray>,
    val frameSec: Double,
    val secPerQuarter: Double,
    /** 마디 i 의 시작 (4분음표). 길이 = 마디 수 + 1 (마지막은 곡 끝) */
    val measureStartQ: DoubleArray,
) {
    val size: Int get() = frames.size
    val measureCount: Int get() = measureStartQ.size - 1

    fun quarterOf(col: Int): Double = col * frameSec / secPerQuarter

    fun colOfQuarter(q: Double): Int = (q * secPerQuarter / frameSec).toInt().coerceIn(0, size - 1)

    /** 칸 → 연속 마디 위치 (마디 순서 0부터 + 마디 안 비율) */
    fun measurePosOf(col: Int): Double = measurePosOfQuarter(quarterOf(col))

    /** 실수 칸(거른 위치) → 연속 마디 위치 */
    fun measurePosOf(col: Double): Double = measurePosOfQuarter(col * frameSec / secPerQuarter)

    fun measurePosOfQuarter(q: Double): Double {
        var lo = 0
        var hi = measureCount - 1
        while (lo < hi) { // q 이하에서 시작하는 마지막 마디
            val mid = (lo + hi + 1) / 2
            if (measureStartQ[mid] <= q) lo = mid else hi = mid - 1
        }
        val len = measureStartQ[lo + 1] - measureStartQ[lo]
        return lo + if (len > 0) ((q - measureStartQ[lo]) / len).coerceIn(0.0, 1.0) else 0.0
    }

    /** 마디 순서 [index] 첫머리의 칸 */
    fun colOfMeasure(index: Int): Int = colOfQuarter(measureStartQ[index.coerceIn(0, measureCount)])

    companion object {
        const val DECAY_SEC = 0.6

        /**
         * @param quarterBpm 기준 빠르기 (4분음표 기준 BPM). 실제 연주가 0.5 ~ 2배 안이면 정렬이 따라간다
         * @param parts 쓸 파트 (null = 모두) — 지휘자는 총보 전체
         */
        fun build(score: MusicXmlScore, quarterBpm: Double, frameSec: Double, parts: Set<Int>? = null): ScoreChroma {
            val starts = DoubleArray(score.measures.size + 1)
            for ((i, m) in score.measures.withIndex()) {
                starts[i + 1] = starts[i] + m.beats * 4.0 / m.beatType
            }
            val secPerQ = 60.0 / quarterBpm
            val total = starts.last()
            val n = (total * secPerQ / frameSec).toInt() + 1
            val raw = Array(n) { FloatArray(12) }
            for ((p, notes) in score.notes.withIndex()) {
                if (parts != null && p !in parts) continue
                for (note in notes) {
                    if (note.measure !in score.measures.indices) continue
                    val onset = starts[note.measure] + note.onset
                    val a = (onset * secPerQ / frameSec).toInt()
                    val b = max(a + 1, ((onset + note.duration) * secPerQ / frameSec).toInt())
                    val pc = Math.floorMod(note.midi, 12)
                    for (k in a until min(b, n)) {
                        raw[k][pc] += exp(-(k - a) * frameSec / DECAY_SEC).toFloat()
                    }
                }
            }
            return ScoreChroma(Array(n) { raw[it].normalized() }, frameSec, secPerQ, starts)
        }

        /** 테스트용 — 이미 만든 칸들로 */
        internal fun of(frames: Array<FloatArray>, frameSec: Double, secPerQuarter: Double, measureStartQ: DoubleArray) =
            ScoreChroma(frames, frameSec, secPerQuarter, measureStartQ)
    }
}

/** 연속 마디 위치 → 마디 순서 (0부터) */
fun Double.measureIndex(): Int = floor(this).toInt()
