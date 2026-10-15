package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.score.ScoreLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 곡 목록의 별 — ★ 악보 연동 · ★★ MusicXML 까지 */
class ScoreLevelTest {

    private fun measure(number: Int, numerator: Int?) =
        ScoreMeasure("f", number, 0, 0, 0f, 0f, 10f, 10f, 595f, 842f, numerator, numerator?.let { 4 })

    @Test
    fun 박자표를_읽은_마디가_있으면_별_하나() {
        assertEquals(ScoreLevel.FOLLOW, ScoreLevel.of(listOf(measure(1, null), measure(2, 3), measure(3, null)), hasMusicXml = false))
    }

    @Test
    fun MusicXML_까지_있으면_별_둘() {
        assertEquals(ScoreLevel.MUSICXML, ScoreLevel.of(listOf(measure(1, 4)), hasMusicXml = true))
    }

    @Test
    fun 마디를_못_찾았거나_박자표가_없으면_MusicXML_이_있어도_별이_없다() {
        assertEquals(ScoreLevel.NONE, ScoreLevel.of(emptyList(), hasMusicXml = true))
        assertEquals(ScoreLevel.NONE, ScoreLevel.of(listOf(measure(1, null), measure(2, null)), hasMusicXml = true))
        assertEquals(ScoreLevel.NONE, ScoreLevel.of(emptyList(), hasMusicXml = false))
    }
}
