package com.mrgq.pdfviewer.metronome

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.score.MusicXmlScore

/**
 * 반주 — MusicXML 의 다른 파트 음을 **박 위치**(소수)에 놓은 목록 (P07 6단계). 박 시간표는 메트로놈이 정하므로 빠르기 · 구간별 빠르기 ·
 * 예비박 · 일시정지 후 이어서를 그대로 따른다. 오디오 스레드가 읽으니 불변이다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
class Accompaniment private constructor(
    /** 박 위치 오름차순 */
    val beats: DoubleArray,
    /** 길이 (박) */
    val lengths: DoubleArray,
    val midis: IntArray,
    /** 파트 순번 (음색을 조금씩 달리 한다) */
    val parts: IntArray,
) {
    val size: Int get() = beats.size

    /** 박 위치가 [beat] 이상인 첫 음 (없으면 [size]) */
    fun firstAtOrAfter(beat: Double): Int {
        var lo = 0
        var hi = beats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (beats[mid] < beat) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /**
         * @param follower 이번 연주의 박 시간표 (시작 마디 · 예비박 · 세는 단위)
         * @param playParts 소리 낼 파트 순번 (MusicXML 파트 순서)
         * @param measureNumberOf MusicXML 마디 순서(0부터) → 악보(PDF) 마디 번호 ([MusicXmlMatch.measureNumberOf])
         */
        fun build(score: MusicXmlScore, follower: ScoreFollower, playParts: Set<Int>, measureNumberOf: (Int) -> Int?): Accompaniment {
            data class N(val beat: Double, val length: Double, val midi: Int, val part: Int)
            val list = ArrayList<N>()
            for (p in playParts.sorted()) {
                for (note in score.notes.getOrNull(p).orEmpty()) {
                    val number = measureNumberOf(note.measure) ?: continue
                    val start = follower.beatAt(number, note.onset) ?: continue
                    val end = follower.beatAt(number, note.onset + note.duration) ?: continue
                    if (end > start) list += N(start, end - start, note.midi, p)
                }
            }
            list.sortBy { it.beat }
            return Accompaniment(
                DoubleArray(list.size) { list[it].beat },
                DoubleArray(list.size) { list[it].length },
                IntArray(list.size) { list[it].midi },
                IntArray(list.size) { list[it].part },
            )
        }
    }
}

/**
 * MusicXML 과 악보(PDF 분석)가 같은 마디 구조인가 (P07 §2.7 "쓰기 전에 검사"). 마디 수가 같고, 악보에서 박자표를 읽은 마디는 박자가 같아야 한다.
 * 판이 다르거나 인식이 틀린 MusicXML 을 여기서 거른다. 맞으면 MusicXML 마디 순서 i → 악보 마디 번호(적힌 순서 i 번째).
 */
class MusicXmlMatch private constructor(val ok: Boolean, val reason: String?, private val numbers: List<Int>) {

    fun measureNumberOf(index: Int): Int? = numbers.getOrNull(index)

    companion object {
        fun check(score: MusicXmlScore, measures: List<ScoreMeasure>): MusicXmlMatch {
            val ordered = measures.sortedBy { it.measureNumber }
            if (ordered.isEmpty()) return MusicXmlMatch(false, "악보 마디를 찾지 못했습니다", emptyList())
            if (score.measures.size != ordered.size) {
                return MusicXmlMatch(false, "마디 수가 다릅니다 (악보 ${ordered.size} · MusicXML ${score.measures.size})", emptyList())
            }
            val mismatch = score.measures.indices.firstOrNull { i ->
                val m = ordered[i]
                val x = score.measures[i]
                m.timeSigNumerator != null && (m.timeSigNumerator != x.beats || m.timeSigDenominator != x.beatType)
            }
            if (mismatch != null) {
                val m = ordered[mismatch]
                val x = score.measures[mismatch]
                return MusicXmlMatch(
                    false,
                    "${m.measureNumber}마디 박자가 다릅니다 (악보 ${m.timeSigNumerator}/${m.timeSigDenominator} · MusicXML ${x.beats}/${x.beatType})",
                    emptyList(),
                )
            }
            return MusicXmlMatch(true, null, ordered.map { it.measureNumber })
        }
    }
}
