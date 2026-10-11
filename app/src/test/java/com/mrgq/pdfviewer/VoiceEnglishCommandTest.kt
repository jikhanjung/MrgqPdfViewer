package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.VoiceTestStrings.en
import com.mrgq.pdfviewer.VoiceTestStrings.ko
import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.EnglishCommandParser
import com.mrgq.pdfviewer.voice.ParseResult
import com.mrgq.pdfviewer.voice.ParseResult.Reason
import com.mrgq.pdfviewer.voice.PartRef
import com.mrgq.pdfviewer.voice.VoiceCommand
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoPage
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoRehearsalMark
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectParts
import com.mrgq.pdfviewer.voice.VoiceCommand.SetCountIn
import com.mrgq.pdfviewer.voice.VoiceCommand.SetTempo
import com.mrgq.pdfviewer.voice.VoiceCommandRunner
import com.mrgq.pdfviewer.voice.VoiceLanguage
import com.mrgq.pdfviewer.voice.VoiceLanguageSetting
import com.mrgq.pdfviewer.voice.VoiceText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 영어 음성 명령 (P18 8단계): 문장 → 명령 표, 음성 언어 고르기, 화면 글(한국어는 옮기기 전과 같게). */
class VoiceEnglishCommandTest {

    private fun parse(text: String) = EnglishCommandParser.parse(text)

    private fun commands(text: String): List<VoiceCommand> =
        (parse(text) as? ParseResult.Commands)?.commands ?: error("\"$text\" → ${parse(text)}")

    private fun parts(vararg p: PartRef) = SelectParts(p.toList())

    /** 문장 → 기대 명령(실행 순서: 템포 · 파트 → 위치 → 시작) */
    private val table: List<Pair<String, List<VoiceCommand>>> = listOf(
        // 마디 — 고르기만
        "measure 57" to listOf(SelectMeasure(57)),
        "bar 57" to listOf(SelectMeasure(57)),
        "Bar 57." to listOf(SelectMeasure(57)),
        "go to bar 57" to listOf(SelectMeasure(57)),
        "measure number fifty seven" to listOf(SelectMeasure(57)),
        "bar fifty-seven" to listOf(SelectMeasure(57)),
        "the 57th measure" to listOf(SelectMeasure(57)),
        "first bar" to listOf(SelectMeasure(1)),
        "measure one hundred and five" to listOf(SelectMeasure(105)),
        // 마디부터 — 바로 시작
        "from measure 57" to listOf(GotoMeasure(57)),
        "from bar 57" to listOf(GotoMeasure(57)),
        "from 57" to listOf(GotoMeasure(57)),
        "start from measure 57" to listOf(GotoMeasure(57)),
        "play from bar 57" to listOf(GotoMeasure(57)),
        "start at measure 57" to listOf(GotoMeasure(57)),
        "measure 57 start" to listOf(GotoMeasure(57)),
        "let's start from the first bar" to listOf(GotoMeasure(1)),
        "go back to measure 12" to listOf(SelectMeasure(12)),
        // 시작 · 정지 · 다시 · 이어서 · 처음부터
        "start" to listOf(VoiceCommand.Start),
        "play" to listOf(VoiceCommand.Start),
        "metronome" to listOf(VoiceCommand.Start),
        "turn on the metronome" to listOf(VoiceCommand.Start),
        "stop" to listOf(VoiceCommand.Stop),
        "stop the metronome" to listOf(VoiceCommand.Stop),
        "metronome off" to listOf(VoiceCommand.Stop),
        "pause" to listOf(VoiceCommand.Stop),
        "again" to listOf(VoiceCommand.Restart),
        "one more time" to listOf(VoiceCommand.Restart),
        "resume" to listOf(VoiceCommand.Resume),
        "keep going" to listOf(VoiceCommand.Resume),
        "from the top" to listOf(VoiceCommand.GotoStart),
        "start from the beginning" to listOf(VoiceCommand.GotoStart),
        "start over" to listOf(VoiceCommand.GotoStart),
        // 템포
        "tempo 72" to listOf(SetTempo(72)),
        "set tempo to 72" to listOf(SetTempo(72)),
        "set the tempo to seventy two" to listOf(SetTempo(72)),
        "72 bpm" to listOf(SetTempo(72)),
        "tempo one twenty" to listOf(SetTempo(120)),
        "tempo a hundred" to listOf(SetTempo(100)),
        "tempo 72 from bar 57" to listOf(SetTempo(72), GotoMeasure(57)),
        "measure 57 tempo 72" to listOf(SetTempo(72), SelectMeasure(57)),
        "tempo 80 start" to listOf(SetTempo(80), VoiceCommand.Start),
        // 쪽
        "next page" to listOf(VoiceCommand.NextPage),
        "turn the page" to listOf(VoiceCommand.NextPage),
        "previous page" to listOf(VoiceCommand.PreviousPage),
        "go back a page" to listOf(VoiceCommand.PreviousPage),
        "page 3" to listOf(GotoPage(3)),
        "go to page three" to listOf(GotoPage(3)),
        "first page" to listOf(GotoPage(1)),
        "last page" to listOf(VoiceCommand.LastPage),
        "page 3 start" to listOf(GotoPage(3), VoiceCommand.Start),
        // 파트
        "cello" to listOf(parts(PartRef("cello"))),
        "cello part" to listOf(parts(PartRef("cello"))),
        "show me the cello part please" to listOf(parts(PartRef("cello"))),
        "violin 2" to listOf(parts(PartRef("violin", 2))),
        "violin two part" to listOf(parts(PartRef("violin", 2))),
        "second violin" to listOf(parts(PartRef("violin", 2))),
        "violin II" to listOf(parts(PartRef("violin", 2))),
        "1st violin and viola parts" to listOf(parts(PartRef("violin", 1), PartRef("viola"))),
        "double bass part" to listOf(parts(PartRef("bass"))),
        "staff 2" to listOf(parts(PartRef.staff(2))),
        "second part" to listOf(parts(PartRef.staff(2))),
        "cello from measure 57" to listOf(parts(PartRef("cello")), GotoMeasure(57)),
        "violin 2 measure 5" to listOf(parts(PartRef("violin", 2)), SelectMeasure(5)),
        "full score" to listOf(VoiceCommand.ShowFullScore),
        "show the full score" to listOf(VoiceCommand.ShowFullScore),
        // 듣기
        "listen" to listOf(VoiceCommand.Listen),
        "start listening" to listOf(VoiceCommand.Listen),
        "listen from measure 57" to listOf(SelectMeasure(57), VoiceCommand.Listen),
        "listen from page 3" to listOf(GotoPage(3), VoiceCommand.Listen),
        "listen from the beginning" to listOf(VoiceCommand.ListenFromStart),
        "resume listening" to listOf(VoiceCommand.ListenResume),
        // 레터
        "letter B" to listOf(GotoRehearsalMark("B")),
        "rehearsal B" to listOf(GotoRehearsalMark("B")),
        "rehearsal mark C" to listOf(GotoRehearsalMark("C")),
        "letter bee" to listOf(GotoRehearsalMark("B")),
        "from letter A" to listOf(GotoRehearsalMark("A")),
        "rehearsal 5" to listOf(GotoRehearsalMark("5")),
        // 예비박
        "count-in one bar" to listOf(SetCountIn(1)),
        "count in two bars" to listOf(SetCountIn(2)),
        "two bar count-in" to listOf(SetCountIn(2)),
        "count-in two bars from measure 57" to listOf(SetCountIn(2), GotoMeasure(57)),
    )

    @Test
    fun 문장_표() {
        val wrong = table.mapNotNull { (text, expected) ->
            val got = (parse(text) as? ParseResult.Commands)?.commands
            if (got == expected) null else "\"$text\" → ${got ?: parse(text)} (기대 $expected)"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
        assertEquals(true, table.size >= 30)
    }

    /** 실행하지 않는 말 — 한국어와 같은 까닭 */
    private val refused: List<Pair<String, ParseResult>> = listOf(
        "not measure 57" to ParseResult.Unrecognized(Reason.NEGATION),
        "cancel" to ParseResult.Unrecognized(Reason.NEGATION),
        "no, measure 57" to ParseResult.Unrecognized(Reason.NEGATION),
        "two bars back" to ParseResult.Unrecognized(Reason.RELATIVE),
        "previous" to ParseResult.Unrecognized(Reason.RELATIVE),
        "measure 57 page 3" to ParseResult.Unrecognized(Reason.CONFLICT),
        "tempo 72 tempo 80" to ParseResult.Unrecognized(Reason.CONFLICT),
        "stop from measure 57" to ParseResult.Unrecognized(Reason.CONFLICT),
        "cello full score" to ParseResult.Unrecognized(Reason.CONFLICT),
        "measure 57 72" to ParseResult.Unrecognized(Reason.STRAY_NUMBER),
        "hello there" to ParseResult.Unrecognized(Reason.NOTHING),
        "kazoo part" to ParseResult.Unrecognized(Reason.UNKNOWN_PART, "kazoo"),
        "the cello is too loud" to ParseResult.Unrecognized(Reason.CHATTER),
        "seventy two" to ParseResult.BareNumber(72),
        "72" to ParseResult.BareNumber(72),
    )

    @Test
    fun 실행하지_않는_말() {
        val wrong = refused.mapNotNull { (text, expected) -> parse(text).takeIf { it != expected }?.let { "\"$text\" → $it (기대 $expected)" } }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun 영어_수_낱말() {
        val numbers = mapOf(
            "zero" to 0, "seven" to 7, "twelve" to 12, "twenty" to 20, "fifty seven" to 57, "ninety nine" to 99, "hundred" to 100,
            "one hundred" to 100, "one hundred five" to 105, "two hundred and twelve" to 212, "nine hundred ninety nine" to 999,
            "one twenty" to 120, "one fifty five" to 155,
        )
        numbers.forEach { (text, value) ->
            assertEquals(text, ParseResult.BareNumber(value), parse(text))
        }
    }

    @Test
    fun 후보_고르기는_한국어와_같다() {
        assertEquals("measure 57", EnglishCommandParser.parseBest(listOf("major 57", "measure 57"))?.first)
        assertEquals("not measure 57", EnglishCommandParser.parseBest(listOf("not measure 57", "measure 57"))?.first)
        // 범위 밖 템포보다 범위 안 후보
        assertEquals("tempo 80", EnglishCommandParser.parseBest(listOf("tempo 8", "tempo 80"))?.first)
    }

    @Test
    fun 음성_언어_고르기() {
        assertEquals(VoiceLanguage.KOREAN, VoiceLanguage.resolve(VoiceLanguageSetting.FOLLOW_APP, "ko"))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.resolve(VoiceLanguageSetting.FOLLOW_APP, "en"))
        assertEquals(VoiceLanguage.KOREAN, VoiceLanguage.resolve(VoiceLanguageSetting.FOLLOW_APP, "ja"))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.resolve(VoiceLanguageSetting.ENGLISH, "ko"))
        assertEquals(VoiceLanguage.KOREAN, VoiceLanguage.resolve(VoiceLanguageSetting.KOREAN, "en"))
        assertEquals(VoiceLanguageSetting.FOLLOW_APP, VoiceLanguageSetting.of(null))
        assertEquals(VoiceLanguageSetting.FOLLOW_APP, VoiceLanguageSetting.of("?"))
        assertEquals(VoiceLanguageSetting.ENGLISH, VoiceLanguageSetting.of("en"))
        assertEquals("en-US", VoiceLanguage.ENGLISH.recognizerTag)
        assertEquals("ko-KR", VoiceLanguage.KOREAN.recognizerTag)
        // 언어마다 그 언어의 파서
        assertEquals(listOf(GotoMeasure(57)), (VoiceLanguage.ENGLISH.parse("from measure 57") as ParseResult.Commands).commands)
        assertEquals(listOf(GotoMeasure(57)), (VoiceLanguage.KOREAN.parse("57마디부터") as ParseResult.Commands).commands)
    }

    // ── 화면 글 (P18) ──

    /** 한국어 화면 글 = 옮기기 전의 글([VoiceCommand.label]) — 바이트 단위로 같다 */
    @Test
    fun 한국어_화면_글은_그대로() {
        val all = listOf(
            SelectMeasure(57), GotoMeasure(57), VoiceCommand.GotoStart, VoiceCommand.Restart, VoiceCommand.Resume,
            GotoRehearsalMark("B"), VoiceCommand.NextPage, VoiceCommand.PreviousPage, GotoPage(3), VoiceCommand.LastPage,
            SetTempo(72), SetCountIn(2), VoiceCommand.ShowFullScore, VoiceCommand.Listen, VoiceCommand.ListenFromStart,
            VoiceCommand.ListenResume, VoiceCommand.Stop, VoiceCommand.Start,
            parts(PartRef("violin", 2), PartRef("cello")), parts(PartRef.staff(3)), parts(PartRef.named("김지한")),
            parts(PartRef("percussion"), PartRef("tenor")),
        )
        all.forEach { assertEquals(it.label, ko(it.text)) }
        listOf("violin", "viola", "cello", "bass", "flute", "piccolo", "oboe", "clarinet", "bassoon", "horn", "trumpet", "trombone",
            "tuba", "timpani", "percussion", "piano", "harp", "guitar", "soprano", "alto", "tenor")
            .forEach { assertEquals(it, PartRef(it).label, ko(PartRef(it).text)) }
        val joined = CommandParser.parse("57마디부터 템포 72") as ParseResult.Commands
        assertEquals(joined.label, ko(joined.text))
    }

    @Test
    fun 한국어_실패_이유는_그대로() {
        val expected = mapOf(
            Reason.NOTHING to "명령을 찾지 못했어요",
            Reason.NEGATION to "'말고 · 아니 · 취소'가 있어 실행하지 않았어요",
            Reason.RELATIVE to "'두 마디 전'처럼 상대 위치는 아직 몰라요 — 마디 번호로 말하세요",
            Reason.CONFLICT to "위치나 템포가 둘이에요 — 하나만 말하세요",
            Reason.STRAY_NUMBER to "무엇의 수인지 모르는 수가 있어요 — '57마디'처럼 단위를 붙이세요",
            Reason.UNKNOWN_PART to "무슨 파트인지 못 알아들었어요",
            Reason.CHATTER to "대화로 들려 실행하지 않았어요 — 파트를 바꾸려면 '첼로 파트'처럼 말하세요",
        )
        Reason.values().forEach { assertEquals(expected[it], ko(VoiceCommandRunner.reasonText(it))) }
    }

    @Test
    fun 영어_화면_글() {
        val result = parse("count-in two bars tempo 72 from measure 57") as ParseResult.Commands
        assertEquals("From measure 57 · 2-bar count-in · ♩=72", en(result.text))
        assertEquals("Violin 2 · Staff 3 part", en(parts(PartRef("violin", 2), PartRef.staff(3)).text))
        assertEquals("🎤 Listen from the beginning", en(VoiceCommand.ListenFromStart.text))
        assertEquals(
            "Couldn't find the part 'kazoo' in this score — this score: Violin I · Cello",
            en(VoiceCommandRunner.withPartChoices(VoiceText.of(R.string.voice_part_not_found_heard, "kazoo"), listOf("Violin I", "Cello"))),
        )
        assertEquals("Can't use tempo 300 (20–240)", en(VoiceText.of(R.string.voice_tempo_out_of_range, 300, 20, 240)))
    }
}
