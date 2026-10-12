package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.EnglishCommandParser
import com.mrgq.pdfviewer.voice.ParseResult
import com.mrgq.pdfviewer.voice.VoiceHelp
import com.mrgq.pdfviewer.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🎙 도움말 (#128): 페이지의 문장이 실제로 명령이 되는지, 한 · 영이 같은 명령인지, "도움말"을 알아듣는지 */
class VoiceHelpTest {

    private fun commands(result: ParseResult, text: String) =
        (result as? ParseResult.Commands)?.commands ?: error("\"$text\" → $result")

    @Test
    fun 도움말의_문장은_모두_명령이고_한영이_같다() {
        for (entry in VoiceHelp.SECTIONS.flatMap { it.entries }) {
            val korean = commands(CommandParser.parse(entry.korean), entry.korean)
            val english = commands(EnglishCommandParser.parse(entry.english), entry.english)
            assertEquals("\"${entry.korean}\" ≠ \"${entry.english}\"", korean, english)
        }
    }

    @Test
    fun 도움말의_문장은_도움말로_읽히지_않는다() {
        for (entry in VoiceHelp.SECTIONS.flatMap { it.entries }) {
            assertFalse(entry.korean, VoiceHelp.isRequest(entry.korean))
            assertFalse(entry.english, VoiceHelp.isRequest(entry.english))
        }
    }

    @Test
    fun 도움말을_부르는_말() {
        listOf("도움말", "도움 말", "도움말 보여 줘", "사용법", "사용법 알려줘", "사용 방법", "헬프", "help", "Help.", "help me", "how to use", "What can I say?")
            .forEach { assertTrue(it, VoiceHelp.isRequest(it)) }
        listOf("57마디부터", "첼로 파트", "measure 57", "helpful", "도움이 필요해").forEach { assertFalse(it, VoiceHelp.isRequest(it)) }
    }

    @Test
    fun 부를_말과_예시는_음성_언어로() {
        assertEquals("도움말", VoiceHelp.word(VoiceLanguage.KOREAN))
        assertEquals("help", VoiceHelp.word(VoiceLanguage.ENGLISH))
        val entry = VoiceHelp.SECTIONS.first().entries.first()
        assertEquals(entry.korean, entry.say(VoiceLanguage.KOREAN))
        assertEquals(entry.english, entry.say(VoiceLanguage.ENGLISH))
        // 사람 이름 보표는 한국어만
        assertEquals(VoiceHelp.tips(VoiceLanguage.ENGLISH).size + 1, VoiceHelp.tips(VoiceLanguage.KOREAN).size)
    }
}
