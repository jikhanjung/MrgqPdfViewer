package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.metronome.Accompaniment
import com.mrgq.pdfviewer.metronome.AccompanimentVoices
import com.mrgq.pdfviewer.metronome.MusicXmlMatch
import com.mrgq.pdfviewer.metronome.ScoreFollower
import com.mrgq.pdfviewer.score.MusicXmlReader
import com.mrgq.pdfviewer.score.XmlNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MusicXML 읽기 · 악보와 맞추기 · 반주 박 위치 · 합성 (P07 5 · 6단계) */
class MusicXmlAccompanimentTest {

    /** 2파트. 파트 1: 6/8, 마디 1 = 점4분 D5 · 8분 화음(F#4+A4) · 8분 쉼표 · 8분 E5(붙임줄 시작) / 마디 2 = 8분 E5(붙임줄 끝) · 꾸밈음 · 점4분 G4 + 둘째 성부 */
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
        <score-partwise version="4.0">
          <part-list>
            <score-part id="P1"><part-name>하진</part-name></score-part>
            <score-part id="P2"><part-name>예원</part-name></score-part>
          </part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>2</divisions><time><beats>6</beats><beat-type>8</beat-type></time></attributes>
              <note><pitch><step>D</step><octave>5</octave></pitch><duration>3</duration></note>
              <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>1</duration></note>
              <note><chord/><pitch><step>A</step><octave>4</octave></pitch><duration>1</duration></note>
              <note><rest/><duration>1</duration></note>
              <note><pitch><step>E</step><octave>5</octave></pitch><duration>1</duration><tie type="start"/></note>
            </measure>
            <measure number="2">
              <note><pitch><step>E</step><octave>5</octave></pitch><duration>1</duration><tie type="stop"/></note>
              <note><grace slash="yes"/><pitch><step>A</step><octave>4</octave></pitch></note>
              <note><pitch><step>G</step><octave>4</octave></pitch><duration>5</duration></note>
              <backup><duration>6</duration></backup>
              <note><pitch><step>B</step><octave>3</octave></pitch><duration>6</duration></note>
            </measure>
          </part>
          <part id="P2">
            <measure number="1">
              <attributes><divisions>1</divisions><time><beats>6</beats><beat-type>8</beat-type></time></attributes>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>3</duration></note>
            </measure>
            <measure number="2">
              <attributes><time><beats>2</beats><beat-type>4</beat-type></time></attributes>
              <note><pitch><step>C</step><octave>3</octave></pitch><duration>2</duration></note>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    private val score = MusicXmlReader.read(xml.byteInputStream())!!

    private fun measure(number: Int, num: Int?, den: Int?) =
        ScoreMeasure("f", number, 0, 0, 0f, 0f, 1f, 1f, 595f, 842f, num, den)

    @Test
    fun 파트_마디_박자를_읽는다() {
        assertEquals(listOf("하진", "예원"), score.parts.map { it.name })
        assertEquals(2, score.measures.size)
        assertEquals(6 to 8, score.measures[0].beats to score.measures[0].beatType)
    }

    @Test
    fun 화음_붙임줄_성부_꾸밈음() {
        val p1 = score.notes[0]
        assertEquals(
            listOf(
                XmlNote(0, 0.0, 1.5, 74), // D5 점4분
                XmlNote(0, 1.5, 0.5, 66), // F#4
                XmlNote(0, 1.5, 0.5, 69), // A4 (화음 — 같은 시각)
                XmlNote(0, 2.5, 1.0, 76), // E5 8분 + 붙임줄로 이어진 8분 = 4분 하나
                XmlNote(1, 0.5, 2.5, 67), // G4 (꾸밈음은 빠지고 박을 먹지 않는다)
                XmlNote(1, 0.0, 3.0, 59), // B3 둘째 성부 (backup 으로 마디 첫머리)
            ),
            p1,
        )
    }

    @Test
    fun 악보와_마디_구조가_같아야_쓴다() {
        assertTrue(MusicXmlMatch.check(score, listOf(measure(1, 6, 8), measure(2, null, null))).ok)
        val fewer = MusicXmlMatch.check(score, listOf(measure(1, 6, 8)))
        assertFalse(fewer.ok)
        assertTrue(fewer.reason!!.contains("마디 수"))
        val otherMeter = MusicXmlMatch.check(score, listOf(measure(1, 3, 4), measure(2, 6, 8)))
        assertFalse(otherMeter.ok)
        assertTrue(otherMeter.reason!!.contains("1마디"))
    }

    @Test
    fun 반주_음은_박_위치에_놓인다_예비박_뒤_세는_단위대로() {
        val measures = listOf(measure(1, 6, 8), measure(2, 6, 8))
        val match = MusicXmlMatch.check(score, measures)
        // 8분음표 박 (한 마디 6박), 예비박 1마디 = 6박 → 마디 1 = 박 6
        val eighths = Accompaniment.build(score, ScoreFollower(measures, 1, dottedBeat = false), setOf(0), match::measureNumberOf)
        assertEquals(6.0, eighths.beats.first(), 1e-9)
        // 점음표 박 (한 마디 2박), 예비박 1마디 = 2박 → F# (4분음표 1.5) = 박 2 + 1.5/1.5 = 3
        val dotted = Accompaniment.build(score, ScoreFollower(measures, 1, dottedBeat = true), setOf(0), match::measureNumberOf)
        assertEquals(listOf(2.0, 3.0, 3.0), dotted.beats.take(3).toList())
        assertEquals(1.0, dotted.lengths[0], 1e-9) // 점4분 = 1박
        // 시작 마디가 2면 마디 1 음은 없다
        val fromTwo = Accompaniment.build(score, ScoreFollower(measures, 2, dottedBeat = true), setOf(0), match::measureNumberOf)
        assertEquals(2, fromTwo.size)
        assertEquals(2, fromTwo.firstAtOrAfter(99.0))
        assertEquals(0, fromTwo.firstAtOrAfter(0.0))
    }

    @Test
    fun 파트가_차지하는_보표() {
        assertEquals(0..0, score.stavesOf(0))
        assertEquals(1..1, score.stavesOf(1))
        assertEquals(2, score.staffCount)
    }

    @Test
    fun 보표_이름은_파트_이름_보표가_여럿이면_번호를_붙인다() {
        assertEquals("하진", score.staffName(0))
        assertEquals("예원", score.staffName(1))
        assertNull(score.staffName(2))
        val piano = score.copy(parts = listOf(score.parts[0], score.parts[1].copy(name = "피아노", staves = 2)))
        assertEquals(listOf("하진", "피아노 1", "피아노 2"), (0..2).map { piano.staffName(it) })
    }

    @Test
    fun 읽을_수_없으면_null() {
        assertNull(MusicXmlReader.read("<html/>".byteInputStream()))
        assertNull(MusicXmlReader.read("깨진".byteInputStream()))
    }

    @Test
    fun 합성은_시작_전엔_조용하고_울리다_끝나면_사라진다() {
        val voices = AccompanimentVoices(8000)
        voices.noteOn(100, 900, 69, 0)
        val out = IntArray(1000)
        voices.mixInto(out, 0, 1000, 1f)
        assertTrue((0 until 100).all { out[it] == 0 })
        assertTrue((150 until 900).any { out[it] != 0 })
        assertTrue(out.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
        val later = IntArray(1000)
        voices.mixInto(later, 1000, 1000, 1f) // 900 + 릴리스(480) 너머까지
        voices.mixInto(IntArray(1000), 2000, 1000, 1f)
        assertEquals(0, voices.activeCount)
    }
}
