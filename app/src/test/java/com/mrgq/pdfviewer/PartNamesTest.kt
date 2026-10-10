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
    fun 이름을_모르면_전처럼() {
        assertEquals(ParseResult.Unrecognized(Reason.NOTHING), CommandParser.parse("지한 파트"))
    }

    @Test
    fun 보표_이름으로_보표_찾기() {
        val score = listOf(0 to "김지한", 1 to "박서연", 2 to null, 3 to "Violoncello")
        assertEquals(PartMatcher.Result.Staves(setOf(1, 2)), PartMatcher.match(score, listOf(PartRef.named("박서연"))))
        assertEquals(PartMatcher.Result.Staves(setOf(0, 3)), PartMatcher.match(score, listOf(PartRef.named("김지한"), PartRef("cello"))))
        assertEquals(PartMatcher.Result.Missing(PartRef.named("이수진")), PartMatcher.match(score, listOf(PartRef.named("이수진"))))
    }
}
