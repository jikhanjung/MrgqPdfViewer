package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.metronome.ScoreFollower
import com.mrgq.pdfviewer.metronome.SectionSpan
import com.mrgq.pdfviewer.metronome.TempoRelation
import com.mrgq.pdfviewer.metronome.TempoSectionSetting
import com.mrgq.pdfviewer.metronome.TempoSections
import com.mrgq.pdfviewer.metronome.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 구간별 빠르기 (#057): 악보 박자로 구간 나누기, 앞 구간에서 빠르기 이어받기, 저장 형식, 악보 연동의 박 시각. */
class TempoSectionsTest {

    private fun measure(number: Int, numerator: Int?, denominator: Int? = numerator?.let { 4 }) =
        ScoreMeasure("f", number, 0, 0, 0f, 0f, 10f, 10f, 595f, 842f, numerator, denominator)

    /** 1~4 마디 4/4, 5~6 마디 6/8 (5 에만 박자표), 7 마디 4/4 */
    private val mixed = listOf(
        measure(1, 4), measure(2, null), measure(3, 4), measure(4, null),
        measure(5, 6, 8), measure(6, null), measure(7, 4, 4),
    )

    // ── 구간 나누기 ─────────────────────────────────────────────────────────

    @Test
    fun 박자가_바뀌는_마디마다_구간이_생긴다() {
        assertEquals(
            listOf(
                SectionSpan(1, 4, 4, TimeSignature(4, 4)),
                SectionSpan(5, 6, 2, TimeSignature(6, 8)),
                SectionSpan(7, 7, 1, TimeSignature(4, 4)),
            ),
            TempoSections.spans(mixed),
        )
    }

    @Test
    fun 같은_박자표를_다시_적어도_구간은_나뉘지_않는다() {
        assertEquals(1, TempoSections.spans(listOf(measure(1, 3), measure(2, 3), measure(3, 3))).size)
    }

    @Test
    fun 박자를_모르는_앞_마디는_구간에_들지_않는다() {
        val spans = TempoSections.spans(listOf(measure(1, null), measure(2, 3), measure(3, null)))
        assertEquals(listOf(SectionSpan(2, 3, 2, TimeSignature(3, 4))), spans)
        assertTrue(TempoSections.spans(listOf(measure(1, null))).isEmpty())
    }

    // ── 빠르기 이어받기 ─────────────────────────────────────────────────────

    @Test
    fun 기본은_음표_길이_그대로() {
        val sections = TempoSections.resolve(TempoSections.spans(mixed), 96, false, emptyList())
        assertEquals(96.0, sections[0].bpm, 1e-9)
        assertEquals(TempoRelation.NOTE, sections[1].relation)
        assertEquals("4분음표 96 → 8분음표 192", 192.0, sections[1].bpm, 1e-9)
        assertEquals("다시 4/4 — 4분음표 96", 96.0, sections[2].bpm, 1e-9)
    }

    @Test
    fun 점음표로_세면_음표_길이_그대로가_점음표_템포가_된다() {
        val settings = listOf(TempoSectionSetting(5, TempoRelation.NOTE, dotted = true))
        val sections = TempoSections.resolve(TempoSections.spans(mixed), 96, false, settings)
        assertEquals("점4분음표 = 8분음표 3개 → 64", 64.0, sections[1].bpm, 1e-9)
        assertEquals("점4분음표 64 에서 4분음표로 돌아오면 96", 96.0, sections[2].bpm, 1e-9)
    }

    @Test
    fun 박_길이_그대로는_박_템포를_잇는다() {
        val settings = listOf(TempoSectionSetting(5, TempoRelation.BEAT, dotted = true))
        val sections = TempoSections.resolve(TempoSections.spans(mixed), 96, false, settings)
        assertEquals("4분음표 96 = 점4분음표 96", 96.0, sections[1].bpm, 1e-9)
        assertEquals("점4분음표 96 → 음표 길이 그대로 4분음표 144", 144.0, sections[2].bpm, 1e-9)
    }

    @Test
    fun 직접_입력은_앞_구간과_상관없다_뒤_구간은_그것을_잇는다() {
        val settings = listOf(TempoSectionSetting(5, TempoRelation.SET, bpm = 150))
        val sections = TempoSections.resolve(TempoSections.spans(mixed), 96, false, settings)
        assertEquals(150.0, sections[1].bpm, 1e-9)
        assertEquals("8분음표 150 → 4분음표 75", 75.0, sections[2].bpm, 1e-9)
    }

    @Test
    fun 첫_구간_템포를_바꾸면_이어받는_구간이_따라간다() {
        val spans = TempoSections.spans(mixed)
        assertEquals(240.0, TempoSections.resolve(spans, 120, false, emptyList())[1].bpm, 1e-9)
        assertEquals(160.0, TempoSections.resolve(spans, 80, false, emptyList())[1].bpm, 1e-9)
    }

    @Test
    fun 구간이_바뀌어_맞지_않는_설정은_버린다() {
        val settings = listOf(TempoSectionSetting(99, TempoRelation.SET, bpm = 40))
        val sections = TempoSections.resolve(TempoSections.spans(mixed), 96, false, settings)
        assertEquals(TempoRelation.NOTE, sections[1].relation)
    }

    @Test
    fun 박자가_한_번도_바뀌지_않으면_악보_연동은_구간_없이() {
        assertTrue(TempoSections.forFollowing(listOf(measure(1, 3), measure(2, 3)), 90, false, emptyList()).isEmpty())
        assertEquals(3, TempoSections.forFollowing(mixed, 90, false, emptyList()).size)
    }

    // ── 저장 · 방송 형식 ────────────────────────────────────────────────────

    @Test
    fun 설정은_JSON_으로_왕복한다() {
        val settings = listOf(
            TempoSectionSetting(5, TempoRelation.BEAT, 120, dotted = true),
            TempoSectionSetting(7, TempoRelation.SET, 88, dotted = false),
        )
        assertEquals(settings, TempoSections.decode(TempoSections.encode(settings)))
        assertNull("비어 있으면 null 로 저장", TempoSections.encode(emptyList()))
    }

    @Test
    fun 깨진_설정은_건너뛰고_나머지는_살린다() {
        assertTrue(TempoSections.decode("not json").isEmpty())
        assertTrue(TempoSections.decode(null).isEmpty())
        val decoded = TempoSections.decode("""[{"m":"x"}, {"m":5,"rel":"???","bpm":999}, 3]""")
        assertEquals(listOf(TempoSectionSetting(5, TempoRelation.NOTE, 240, false)), decoded)
    }

    // ── 악보 연동의 박 시각 ─────────────────────────────────────────────────

    @Test
    fun 구간마다_박_간격이_다르다() {
        val sections = TempoSections.forFollowing(mixed, 60, false, emptyList())
        val follower = ScoreFollower(mixed, startMeasureNumber = 4, sections = sections)
        val times = follower.beatTimes!!
        // 예비박 4박(4/4, ♩=60) + 4 마디 4박 = 8박 = 8초. 그다음 6/8 ♪=120 → 박마다 0.5초
        assertEquals(4, follower.countInBeats)
        assertEquals(0.0, times.secondsAt(0), 1e-9)
        assertEquals(4.0, times.secondsAt(4), 1e-9)
        assertEquals(8.0, times.secondsAt(8), 1e-9)
        assertEquals(8.5, times.secondsAt(9), 1e-9)
        // 5 · 6 마디 12박 = 6초 → 7 마디(♩=60) 는 14초에 시작, 끝 너머도 같은 템포로
        assertEquals(14.0, times.secondsAt(20), 1e-9)
        assertEquals(19.0, times.secondsAt(25), 1e-9)
        assertEquals("예비박 앞은 시작 템포로", -1.0, times.secondsAt(-1), 1e-9)
        assertEquals(120.0, follower.barPositionAt(9).bpm!!, 1e-9)
        assertEquals(60.0, follower.barPositionAt(0).bpm!!, 1e-9)
    }

    @Test
    fun 예비박은_시작_마디_구간의_템포와_세는_단위로() {
        val settings = listOf(TempoSectionSetting(5, TempoRelation.NOTE, dotted = true))
        val sections = TempoSections.forFollowing(mixed, 60, false, settings)
        val follower = ScoreFollower(mixed, startMeasureNumber = 5, sections = sections)
        assertEquals("6/8 을 점4분음표로 — 예비박 2박", 2, follower.countInBeats)
        assertTrue(follower.startDotted)
        assertEquals("점4분음표 = 4분음표 60 의 음표 길이 그대로 → 40", 40.0, follower.startBpm!!, 1e-9)
        assertEquals(1.5, follower.beatTimes!!.secondsAt(1), 1e-9)
    }

    @Test
    fun 템포만_바뀌면_마디_나눔이_같다() {
        val a = ScoreFollower(mixed, 1, sections = TempoSections.forFollowing(mixed, 60, false, emptyList()))
        val b = ScoreFollower(mixed, 1, sections = TempoSections.forFollowing(mixed, 90, false, emptyList()))
        val dotted = ScoreFollower(
            mixed, 1,
            sections = TempoSections.forFollowing(mixed, 60, false, listOf(TempoSectionSetting(5, dotted = true))),
        )
        assertTrue(a.sameBeatsAs(b))
        assertFalse("세는 단위가 바뀌면 박 수가 달라진다", a.sameBeatsAs(dotted))
    }

    @Test
    fun 구간이_없으면_박_시각을_내지_않는다() {
        assertNull(ScoreFollower(mixed, 1).beatTimes)
        assertNull(ScoreFollower(mixed, 1).barPositionAt(5).bpm)
    }
}
