package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.CommandNormalizer
import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.KoreanNumbers
import com.mrgq.pdfviewer.voice.Kw
import com.mrgq.pdfviewer.voice.ParseResult
import com.mrgq.pdfviewer.voice.ParseResult.Reason
import com.mrgq.pdfviewer.voice.PartRef
import com.mrgq.pdfviewer.voice.Token
import com.mrgq.pdfviewer.voice.VoiceCommand
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoPage
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoRehearsalMark
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.SetCountIn
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectParts
import com.mrgq.pdfviewer.voice.VoiceCommand.SetTempo
import com.mrgq.pdfviewer.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 음성 명령 1단계(P12): 한국어 수 · 정규화 · 규칙. 발화 문자열 → 기대 명령. */
class VoiceCommandTest {

    private fun commands(text: String): List<VoiceCommand> {
        val r = CommandParser.parse(text)
        return (r as? ParseResult.Commands)?.commands ?: error("\"$text\" → $r")
    }

    private fun assertCommands(text: String, vararg expected: VoiceCommand) =
        assertEquals(text, expected.toList(), commands(text))

    private fun assertUnrecognized(text: String, reason: Reason) =
        assertEquals(text, ParseResult.Unrecognized(reason), CommandParser.parse(text))

    // ── 한국어 수 ──

    @Test
    fun 한자어_수() {
        mapOf("일" to 1, "십" to 10, "십일" to 11, "오십칠" to 57, "칠십이" to 72, "백" to 100, "백이십" to 120,
            "이백오" to 205, "천" to 1000, "영" to 0)
            .forEach { (text, value) -> assertEquals(text, value, KoreanNumbers.parse(text)) }
    }

    @Test
    fun 고유어_수() {
        mapOf("하나" to 1, "두" to 2, "세" to 3, "열" to 10, "열두" to 12, "스물" to 20, "스물두" to 22,
            "쉰일곱" to 57, "일흔둘" to 72, "아흔아홉" to 99, "일곱" to 7, "여덟" to 8)
            .forEach { (text, value) -> assertEquals(text, value, KoreanNumbers.parse(text)) }
    }

    @Test
    fun 붙여_쓴_수는_자릿수가_내려가는_데까지() {
        assertEquals(KoreanNumbers.Match(72, 3), KoreanNumbers.prefixAt("칠십이오십칠", 0))
        assertEquals(KoreanNumbers.Match(57, 3), KoreanNumbers.prefixAt("칠십이오십칠", 3))
        assertEquals("두 자리 숫자를 붙이지 않는다", KoreanNumbers.Match(5, 1), KoreanNumbers.prefixAt("오칠", 0))
        assertEquals("천천히", KoreanNumbers.Match(1000, 1), KoreanNumbers.prefixAt("천천히", 0))
        assertNull(KoreanNumbers.parse("사이"))
        assertNull(KoreanNumbers.parse("다시"))
    }

    // ── 정규화 ──

    @Test
    fun 공백을_지우고_낱말로() {
        assertEquals(
            listOf(Token.Num(57), Token.Word(Kw.MEASURE), Token.Word(Kw.FROM), Token.Word(Kw.TEMPO), Token.Num(72)),
            CommandNormalizer.normalize("오십 칠 마디부터, 템포 칠십이"),
        )
    }

    @Test
    fun 아라비아_숫자_사이_공백은_경계() {
        assertEquals(listOf(Token.Num(72), Token.Num(57)), CommandNormalizer.normalize("72 57"))
    }

    @Test
    fun 긴_낱말이_먼저() {
        assertEquals(listOf(Token.Word(Kw.PREV), Token.Word(Kw.PAGE)), CommandNormalizer.normalize("이전 쪽"))
        assertEquals(listOf(Token.Ordinal(2), Token.Instrument("violin")), CommandNormalizer.normalize("세컨 바이올린"))
        assertEquals(listOf(Token.Instrument("viola")), CommandNormalizer.normalize("비올라"))
        assertEquals(listOf(Token.Word(Kw.RESUME)), CommandNormalizer.normalize("이어서"))
    }

    @Test
    fun 한_글자_수는_단위_템포_악기_곁에서만() {
        assertEquals(listOf(Token.Num(3), Token.Word(Kw.PAGE)), CommandNormalizer.normalize("삼 쪽"))
        assertEquals(listOf(Token.Num(3), Token.Word(Kw.COUNTER), Token.Word(Kw.MEASURE)), CommandNormalizer.normalize("세 번째 마디"))
        assertEquals(listOf(Token.Word(Kw.TEMPO), Token.Num(100)), CommandNormalizer.normalize("템포 백"))
        assertEquals(listOf(Token.Instrument("violin"), Token.Num(2)), CommandNormalizer.normalize("바이올린 이"))
        assertEquals("세게 — 세(3)가 아니다", listOf(Token.Other("세게")), CommandNormalizer.normalize("세게"))
        assertEquals(listOf(Token.Other("일시"), Token.Word(Kw.STOP)), CommandNormalizer.normalize("일시정지")) // "일"은 수가 아니다
        assertEquals(listOf(Token.Other("한"), Token.Word(Kw.COUNTER), Token.Other("더")), CommandNormalizer.normalize("한 번 더"))
    }

    @Test
    fun 이_마디는_지금_마디라_수가_아니다() {
        assertEquals(listOf(Token.Other("이"), Token.Word(Kw.MEASURE), Token.Word(Kw.FROM)), CommandNormalizer.normalize("이 마디부터"))
    }

    @Test
    fun 오인식_고침과_영문_소문자() {
        assertEquals(listOf(Token.Word(Kw.TEMPO), Token.Num(84)), CommandNormalizer.normalize("탬포 84"))
        assertEquals(listOf(Token.Num(72), Token.Word(Kw.BPM)), CommandNormalizer.normalize("72 BPM"))
    }

    // ── 규칙: 위치 ──

    @Test
    fun 마디부터는_시작() {
        assertCommands("57마디부터", GotoMeasure(57))
        assertCommands("오십칠 마디부터", GotoMeasure(57))
        assertCommands("57부터", GotoMeasure(57))
        assertCommands("57마디에서부터", GotoMeasure(57))
        assertCommands("백이십 소절부터 해볼게요", GotoMeasure(120))
    }

    @Test
    fun 마디만_말하면_고르기만() {
        assertCommands("57마디", SelectMeasure(57))
        assertCommands("쉰일곱 마디", SelectMeasure(57))
        assertCommands("57번 마디에서", SelectMeasure(57))
        assertCommands("세 번째 마디", SelectMeasure(3))
        assertCommands("57마디로", SelectMeasure(57))
    }

    @Test
    fun 끝_음절이_잘린_마디() {
        assertCommands("50 마", SelectMeasure(50))
        assertCommands("115 마", SelectMeasure(115))
        assertCommands("오십 마", SelectMeasure(50))
        assertCommands("3 페이", GotoPage(3))
        // 가운데의 "마"나 수 없는 "마"는 단위가 아니다
        assertUnrecognized("50 마 템포 72", Reason.STRAY_NUMBER)
        assertUnrecognized("마", Reason.NOTHING)
    }

    @Test
    fun 마디와_템포를_함께_템포_먼저() {
        assertCommands("57마디부터 템포 72", SetTempo(72), GotoMeasure(57))
        assertCommands("오십칠 마디부터, 템포 칠십이", SetTempo(72), GotoMeasure(57))
        assertCommands("템포 칠십이 오십칠 마디부터", SetTempo(72), GotoMeasure(57))
    }

    @Test
    fun 처음_다시_이어서() {
        assertCommands("처음부터", VoiceCommand.GotoStart)
        assertCommands("맨 앞부터", VoiceCommand.GotoStart)
        assertCommands("처음부터 다시", VoiceCommand.GotoStart)
        assertCommands("다시", VoiceCommand.Restart)
        assertCommands("57마디부터 다시", GotoMeasure(57))
        assertCommands("57마디 다시", GotoMeasure(57))
        assertCommands("이어서", VoiceCommand.Resume)
        assertCommands("계속", VoiceCommand.Resume)
    }

    @Test
    fun 쪽_넘기기() {
        assertCommands("다음 쪽", VoiceCommand.NextPage)
        assertCommands("다음 장으로", VoiceCommand.NextPage)
        assertCommands("넘겨줘", VoiceCommand.NextPage)
        assertCommands("이전 페이지", VoiceCommand.PreviousPage)
        assertCommands("앞 쪽", VoiceCommand.PreviousPage)
        assertCommands("3쪽", GotoPage(3))
        assertCommands("삼 페이지", GotoPage(3))
    }

    @Test
    fun 리허설_마크() {
        assertCommands("레터 B", GotoRehearsalMark("B"))
        assertCommands("레터 비부터", GotoRehearsalMark("B"))
        assertCommands("레터 에이", GotoRehearsalMark("A"))
        assertCommands("레터 에이치", GotoRehearsalMark("H"))
        assertCommands("레터 이", GotoRehearsalMark("E"))
        assertCommands("리허설 삼십", GotoRehearsalMark("30"))
    }

    // ── 규칙: 설정 ──

    @Test
    fun 템포() {
        assertCommands("템포 84", SetTempo(84))
        assertCommands("템포를 백이십으로", SetTempo(120))
        assertCommands("빠르기 72", SetTempo(72))
        assertCommands("72 bpm", SetTempo(72))
        assertCommands("템포 백", SetTempo(100))
    }

    @Test
    fun 파트() {
        assertCommands("첼로 파트", SelectParts(listOf(PartRef("cello"))))
        assertCommands("첼로", SelectParts(listOf(PartRef("cello"))))
        assertCommands("바이올린 이", SelectParts(listOf(PartRef("violin", 2))))
        assertCommands("바이올린 2 파트", SelectParts(listOf(PartRef("violin", 2))))
        assertCommands("세컨 바이올린", SelectParts(listOf(PartRef("violin", 2))))
        assertCommands("비올라 둘째", SelectParts(listOf(PartRef("viola", 2))))
        assertCommands("첼로 비올라 파트", SelectParts(listOf(PartRef("cello"), PartRef("viola"))))
        assertCommands("총보", VoiceCommand.ShowFullScore)
    }

    @Test
    fun 악기_뒤_수가_마디면_파트_번호가_아니다() {
        assertCommands("바이올린 3마디부터", SelectParts(listOf(PartRef("violin"))), GotoMeasure(3))
        assertCommands("바이올린 3마디", SelectParts(listOf(PartRef("violin"))), SelectMeasure(3))
    }

    @Test
    fun 시작은_위치_명령이_있으면_빠진다() {
        assertCommands("시작", VoiceCommand.Start)
        assertCommands("메트로놈 시작", VoiceCommand.Start)
        assertCommands("57마디부터 시작", GotoMeasure(57))
        assertCommands("다음 쪽 시작", VoiceCommand.NextPage, VoiceCommand.Start)
    }

    @Test
    fun 쪽_넘기고_시작() {
        assertCommands("1페이지 시작", GotoPage(1), VoiceCommand.Start)
        assertCommands("3쪽부터 시작", GotoPage(3), VoiceCommand.Start)
    }

    @Test
    fun 정지() {
        assertCommands("멈춰", VoiceCommand.Stop)
        assertCommands("정지", VoiceCommand.Stop)
        assertCommands("그만", VoiceCommand.Stop)
        assertCommands("일시정지", VoiceCommand.Stop)
        assertCommands("메트로놈 정지", VoiceCommand.Stop)
        assertCommands("메트로놈 멈춰 줘", VoiceCommand.Stop)
        assertCommands("메트로놈", VoiceCommand.Start)
        assertUnrecognized("멈춰 57마디부터", Reason.CONFLICT)
        assertUnrecognized("정지 시작", Reason.CONFLICT)
    }

    // ── 👂 호출어 ──

    @Test
    fun 호출어_뒤의_말만() {
        assertEquals("57마디부터", WakeWord.commandAfter("메이트, 57마디부터"))
        assertEquals("1페이지 시작.", WakeWord.commandAfter("Mate, 1페이지 시작."))
        assertEquals("다음 쪽", WakeWord.commandAfter("mate 다음 쪽"))
        assertEquals("다음 쪽", WakeWord.commandAfter("메 이트 다음 쪽"))
        assertEquals("다음 쪽", WakeWord.commandAfter("매이트야 다음 쪽"))
        assertEquals("3쪽", WakeWord.commandAfter("메이트 아니 메이트 3쪽"))
        assertEquals("", WakeWord.commandAfter("메이트"))
        assertEquals("", WakeWord.commandAfter("메이트야!"))
    }

    @Test
    fun 호출어가_없거나_다른_낱말_속이면_명령이_아니다() {
        assertNull(WakeWord.commandAfter("57마디에서 첼로가 커요"))
        assertNull(WakeWord.commandAfter("룸메이트가 57마디부터 하재"))
        assertNull(WakeWord.commandAfter("estimate 57"))
        assertNull(WakeWord.commandAfter("mates 다음 쪽"))
    }

    @Test
    fun 마디_고르기에_시작이_붙으면_그_마디부터() {
        assertCommands("57마디 시작", GotoMeasure(57))
        assertCommands("57마디에서 시작", GotoMeasure(57))
        assertCommands("57마디 템포 72 시작", SetTempo(72), GotoMeasure(57))
    }

    @Test
    fun 화면_표시는_위치_먼저() {
        assertEquals("57마디부터 · ♩=72", (CommandParser.parse("57마디부터 템포 72") as ParseResult.Commands).label)
        assertEquals("57마디 선택 · ♩=72", (CommandParser.parse("57마디 템포 72") as ParseResult.Commands).label)
    }

    @Test
    fun 예비박() {
        assertCommands("예비박 한 마디", SetCountIn(1))
        assertCommands("예비박 두마디", SetCountIn(2))
        assertCommands("예비박 2마디", SetCountIn(2))
        assertCommands("예비박 한 마디로", SetCountIn(1))
        assertCommands("두 마디 예비박", SetCountIn(2))
        assertCommands("예비박 둘", SetCountIn(2))
        assertCommands("예비 박 이 마디", SetCountIn(2))
        assertCommands("예비박 한 마디 57마디부터", SetCountIn(1), GotoMeasure(57))
        assertCommands("57마디 예비박 두 마디", SetCountIn(2), SelectMeasure(57))
        assertUnrecognized("예비박", Reason.NOTHING)
        assertUnrecognized("예비박 한 마디 예비박 두 마디", Reason.CONFLICT)
    }

    // ── 수만 ──

    @Test
    fun 수만_들리면_화면이_정한다() {
        assertEquals(ParseResult.BareNumber(72), CommandParser.parse("칠십이"))
        assertEquals(ParseResult.BareNumber(72), CommandParser.parse("72요"))
        assertEquals(ParseResult.BareNumber(120), CommandParser.parse("백이십으로"))
    }

    // ── 실행하지 않는 것 ──

    @Test
    fun 모르면_실행하지_않는다() {
        assertUnrecognized("", Reason.NOTHING)
        assertUnrecognized("좋아요", Reason.NOTHING)
        assertUnrecognized("이 마디부터", Reason.NOTHING)
        assertUnrecognized("한 번 더", Reason.NOTHING)
        assertUnrecognized("다음 곡", Reason.NOTHING)
        assertUnrecognized("칠", Reason.NOTHING)
    }

    @Test
    fun 부정이_들리면_실행하지_않는다() {
        assertUnrecognized("57마디 말고 58마디", Reason.NEGATION)
        assertUnrecognized("아니 처음부터", Reason.NEGATION)
        assertUnrecognized("취소", Reason.NEGATION)
    }

    @Test
    fun 상대_위치는_받지_않는다() {
        assertUnrecognized("두 마디 전부터", Reason.RELATIVE)
        assertUnrecognized("57마디 앞부터", Reason.RELATIVE)
        assertUnrecognized("세 마디 뒤", Reason.RELATIVE)
    }

    @Test
    fun 위치나_템포가_둘이면_실행하지_않는다() {
        assertUnrecognized("57마디 58마디", Reason.CONFLICT)
        assertUnrecognized("57마디 3쪽", Reason.CONFLICT)
        assertUnrecognized("템포 72 템포 84", Reason.CONFLICT)
        assertUnrecognized("첼로 총보", Reason.CONFLICT)
        assertCommands("57마디 57마디", SelectMeasure(57))
    }

    @Test
    fun 붙지_않은_수가_남으면_실행하지_않는다() {
        assertUnrecognized("템포 72 57", Reason.STRAY_NUMBER)
        assertUnrecognized("2악장 57마디", Reason.STRAY_NUMBER)
        assertUnrecognized("5 7마디", Reason.STRAY_NUMBER)
    }
}
