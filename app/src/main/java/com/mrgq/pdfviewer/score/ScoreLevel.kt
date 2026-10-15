package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.metronome.ScoreFollower

/**
 * 곡 목록의 별 (사용자 요청 2026-10-15) — 이 악보로 무엇까지 되나:
 * - ★ = 벡터 PDF 라 마디를 찾았고 박자표도 읽어 **메트로놈 악보 연동**(자동 넘김)이 된다([ScoreFollower.startableMeasures] 가 비지 않음)
 * - ★★ = 거기에 PDF 옆 `.musicxml` 이 있어 **파트 보기 · 반주 · 🎤 듣고 넘기기**까지
 *
 * MusicXML 만 있고 마디를 못 찾은 악보(스캔 등)는 별이 없다 — 위 기능은 모두 PDF 의 마디 자리가 있어야 돈다
 */
object ScoreLevel {
    const val NONE = 0
    const val FOLLOW = 1
    const val MUSICXML = 2

    fun of(followable: Boolean, hasMusicXml: Boolean): Int = when {
        !followable -> NONE
        hasMusicXml -> MUSICXML
        else -> FOLLOW
    }

    fun of(measures: List<ScoreMeasure>, hasMusicXml: Boolean): Int =
        of(ScoreFollower.startableMeasures(measures).isNotEmpty(), hasMusicXml)
}
