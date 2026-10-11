package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.ParseResult
import com.mrgq.pdfviewer.voice.ParseResult.Reason
import com.mrgq.pdfviewer.voice.PartMatcher
import com.mrgq.pdfviewer.voice.PartNames
import com.mrgq.pdfviewer.voice.PartRef
import com.mrgq.pdfviewer.voice.VoiceCommand
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.GotoPage
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectMeasure
import com.mrgq.pdfviewer.voice.VoiceCommand.SelectParts
import org.junit.Assert.assertEquals
import org.junit.Test

/** 사람 이름 보표를 말로 부르기 (#094) — 악보의 보표 이름이 단원 이름("김지한")일 때 */
class PartNamesTest {

    private val staves = listOf("Violin I", "김지한", "박서연", "이수진", "박지수", "첼로")

    private fun commands(text: String, names: List<String> = staves): List<VoiceCommand> {
        val r = CommandParser.parse(text, names)
        return (r as? ParseResult.Commands)?.commands ?: error("\"$text\" → $r")
    }

    private fun named(vararg names: String) = SelectParts(names.map { PartRef.named(it) })

    @Test
    fun 부를_말은_온이름과_성을_뺀_이름_악기_이름은_빼고() {
        assertEquals(
            setOf("김지한", "지한", "박서연", "서연", "이수진", "수진", "박지수", "지수"),
            PartNames.aliases(staves).map { it.first }.toSet(),
        )
        assertEquals("김지한", PartNames.aliases(staves).first { it.first == "지한" }.second)
    }

    @Test
    fun 이름으로_파트_보기() {
        assertEquals(listOf(named("김지한")), commands("김지한"))
        assertEquals(listOf(named("김지한")), commands("지한 파트"))
        assertEquals(listOf(named("김지한")), commands("김 지한 파트 보여줘"))
        assertEquals(listOf(named("박서연", "김지한")), commands("서연 지한 파트"))
    }

    @Test
    fun 부르는_말과_호칭은_이름에_붙는다() {
        assertEquals(listOf(named("김지한")), commands("지한이 파트"))
        assertEquals(listOf(named("박서연")), commands("서연아"))
        assertEquals(listOf(named("박지수")), commands("지수야 파트"))
        assertEquals(listOf(named("김지한")), commands("지한 씨"))
        // "이"가 수 2 로 남지 않는다
        assertEquals(listOf(named("김지한"), GotoPage(3)), commands("지한이 3쪽"))
    }

    @Test
    fun 성이_수로_읽히는_이름도_쪼개지지_않는다() {
        assertEquals(listOf(named("이수진"), GotoMeasure(57)), commands("이수진 57마디부터"))
    }

    @Test
    fun 악기로_부르기는_그대로() {
        assertEquals(listOf(SelectParts(listOf(PartRef("violin", 2)))), commands("바이올린 이"))
        assertEquals(listOf(SelectParts(listOf(PartRef("cello")))), commands("첼로"))
    }

    @Test
    fun 성을_뺀_이름이_겹치면_온이름만() {
        val twins = listOf("김지한", "이지한", "김산")
        assertEquals(setOf("김지한", "이지한", "김산"), PartNames.aliases(twins).map { it.first }.toSet())
        assertEquals(ParseResult.Unrecognized(Reason.NOTHING), CommandParser.parse("지한", twins))
        assertEquals(listOf(named("이지한")), commands("이지한", twins))
    }

    @Test
    fun 파트는_들었는데_무슨_파트인지_모르면_들린_말과_함께() {
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "지한"), CommandParser.parse("지한 파트"))
        // 이 악보에 없는 이름 — "지환"은 이제 "지한"과 모음 하나 차이라 맞춘다(#109, 아래), 그래서 전혀 다른 이름으로
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "준호"), CommandParser.parse("준호 파트", staves))
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART), CommandParser.parse("파트 보여줘"))
        // 파트를 모르면 나머지(마디)도 실행하지 않는다
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "준호"), CommandParser.parse("준호 파트 57마디부터", staves))
        // "파트" 없이 모르는 말만이면 전처럼
        assertEquals(ParseResult.Unrecognized(Reason.NOTHING), CommandParser.parse("지환"))
    }

    @Test
    fun 다른_후보에_맞는_이름이_있으면_그것() {
        assertEquals("지한 파트" to ParseResult.Commands(listOf(named("김지한"))), CommandParser.parseBest(listOf("준호 파트", "지한 파트"), staves))
    }

    @Test
    fun 모음_하나만_다르게_받아_적은_이름도_그_사람() {
        // 실녹음(#109): 보표 "예완"을 STT 가 "예원"으로 — 첫소리 · 받침이 같고 가운데소리 하나만 다르다
        assertEquals(listOf(named("김지한")), commands("지환 파트"))
        assertEquals(listOf(named("김지한"), GotoMeasure(57)), commands("지환 파트 57마디부터"))
        val ensemble = listOf("은석", "예완", "하진")
        assertEquals(listOf(named("예완")), commands("예원 파트", ensemble))
        // 받침이 다르거나 두 음절이 다르면 맞추지 않는다
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "하지"), CommandParser.parse("하지 파트", ensemble))
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "유원"), CommandParser.parse("유원 파트", ensemble))
        // 가까운 이름이 둘이면 어느 쪽인지 모른다
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "예안"), CommandParser.parse("예안 파트", listOf("예완", "예언")))
        // 명령 낱말은 이름보다 먼저 — 보표 "마다"가 있어도 "57마디"는 마디
        assertEquals(listOf(SelectMeasure(57)), commands("57마디", listOf("마다", "Violin I")))
    }

    @Test
    fun 보표_번호로_부르기() {
        val staff1 = listOf(SelectParts(listOf(PartRef.staff(1))))
        assertEquals(staff1, commands("보표 1 파트"))
        assertEquals(staff1, commands("보표 일 파트"))
        assertEquals(staff1, commands("보표일 파트"))
        assertEquals(staff1, commands("보표 1 파트보"))
        assertEquals(staff1, commands("보표 1 파트만 보여줘"))
        assertEquals(ParseResult.Unrecognized(Reason.UNKNOWN_PART, "준호"), CommandParser.parse("준호 파트보", staves))
        assertEquals(listOf(SelectParts(listOf(PartRef.staff(2)))), commands("보표 이"))
        assertEquals(listOf(SelectParts(listOf(PartRef.staff(3))), GotoMeasure(10)), commands("보표 삼 10마디부터"))
        assertEquals("보표 2", PartRef.staff(2).label)
    }

    @Test
    fun 보표_여럿() {
        val both = listOf(SelectParts(listOf(PartRef.staff(1), PartRef.staff(2))))
        assertEquals(both, commands("보표 1 보표 2 파트보"))
        assertEquals(both, commands("보표 1과 보표 2 파트"))
        assertEquals(both, commands("보표 1, 2 파트보"))
        assertEquals(both, commands("보표 일 이 파트"))
        // 단위가 붙은 수는 보표 번호가 아니다
        assertEquals(listOf(SelectParts(listOf(PartRef.staff(1))), GotoMeasure(57)), commands("보표 1 57마디부터"))
        // 이름 · 악기와 섞어도
        assertEquals(listOf(SelectParts(listOf(PartRef.named("김지한"), PartRef.staff(3)))), commands("지한 보표 3 파트"))
    }

    @Test
    fun 보표_이름으로_보표_찾기() {
        val score = listOf(0 to "김지한", 1 to "박서연", 2 to null, 3 to "Violoncello")
        assertEquals(PartMatcher.Result.Staves(setOf(1, 2)), PartMatcher.match(score, listOf(PartRef.named("박서연"))))
        assertEquals(PartMatcher.Result.Staves(setOf(0, 3)), PartMatcher.match(score, listOf(PartRef.named("김지한"), PartRef("cello"))))
        assertEquals(PartMatcher.Result.Missing(PartRef.named("이수진")), PartMatcher.match(score, listOf(PartRef.named("이수진"))))
    }

    @Test
    fun 보표_번호는_그_보표_하나만() {
        val unnamed = listOf(0 to null, 1 to null, 2 to null)
        assertEquals(PartMatcher.Result.Staves(setOf(1)), PartMatcher.match(unnamed, listOf(PartRef.staff(2))))
        assertEquals(PartMatcher.Result.Staves(setOf(0, 2)), PartMatcher.match(unnamed, listOf(PartRef.staff(1), PartRef.staff(3))))
        assertEquals(PartMatcher.Result.Missing(PartRef.staff(9)), PartMatcher.match(unnamed, listOf(PartRef.staff(9))))
    }
}
