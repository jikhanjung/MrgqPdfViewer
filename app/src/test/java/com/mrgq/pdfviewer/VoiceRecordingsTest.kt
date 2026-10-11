package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.ParseResult
import com.mrgq.pdfviewer.voice.ParseResult.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 실녹음 평가 (P12 3단계, #109) — 태블릿 `files/voice/commands.jsonl` 115건(2026-10-09 ~ 10-11)에서 가져온 음성 인식 후보 그대로.
 * 처음 판에서 실패했거나 잘못 실행된 것, 고친 뒤에도 그대로여야 하는 것을 고정한다. 기대값은 화면에 보이는 글([ParseResult.Commands.label]),
 * 실행하지 않아야 하면 그 까닭.
 */
class VoiceRecordingsTest {

    /** 블타바(몰다우) 합주 악보의 사람 이름 보표 — 기록에서 성공한 이름들 */
    private val moldau = listOf("은석", "예완", "하진")

    private fun heard(vararg candidates: String, names: List<String> = emptyList(), staffCount: Int? = null): Any? =
        when (val r = CommandParser.parseBest(candidates.toList(), names, staffCount)?.second) {
            is ParseResult.Commands -> r.label
            is ParseResult.Unrecognized -> r.reason
            else -> r
        }

    @Test
    fun 잘못_실행되던_것() {
        // "메트로놈 꺼 줘"가 "꺼"를 몰라 메트로놈만 남아 시작했다(v0.7.0)
        assertEquals("정지", heard("메트로놈 꺼 줘"))
        assertEquals("시작", heard("메트로놈 켜 줘"))
        assertEquals("시작", heard("메트로놈 시작"))
    }

    @Test
    fun 범위_밖_후보보다_범위_안_후보() {
        assertEquals("♩=80", heard("템포 8", "템포 80"))
        assertEquals("♩=90", heard("템포 90", "템포 9", "템퍼 90", "템퍼 9"))
        assertEquals("♩=140", heard("템포 140", "템퍼 140", "템퍼 1"))
    }

    @Test
    fun 총보_보표_비슷한_말() {
        assertEquals("총보", heard("총복", "충북"))
        assertEquals("총보", heard("충북", "총복"))
        assertEquals("총보", heard("청보", "총보", "충보"))
        assertEquals("보표 2 파트", heard("도표이 파트", "도표 2 파트"))
        assertEquals("보표 1 파트", heard("스테프원 파트보", "스테프원 파트버", "스테프원 파트복"))
        assertEquals("보표 1 파트", heard("보표 1 파트", "보표일 파트", "보표 1 p"))
        assertEquals("보표 1 · 보표 2 파트", heard("보표 1 2 파트복", "보표 1 2 파트보", "보표 12 파트복"))
    }

    @Test
    fun 붙여_적은_보표_번호는_보표_수로_나눈다() {
        // 아르페지오네(보표 3): "목표 23 파트" = 보표 2 · 3 (#110)
        assertEquals("보표 2 · 보표 3 파트", heard("목표 23 파트", "목표 2 3 파트", staffCount = 3))
        assertEquals("보표 2 · 보표 3 파트", heard("보표 23 파트", staffCount = 3))
        // 보표 수를 모르거나, 그만큼 보표가 있거나, 나눠도 없는 번호면 그대로
        assertEquals("보표 23 파트", heard("보표 23 파트"))
        assertEquals("보표 12 파트", heard("보표 12 파트", staffCount = 20))
        assertEquals("보표 45 파트", heard("보표 45 파트", staffCount = 3))
        assertEquals("보표 22 파트", heard("보표 22 파트", staffCount = 3))
    }

    @Test
    fun 몇_번째_파트() {
        assertEquals("보표 2 파트", heard("두 번째 파트", "두번째 파트"))
        assertEquals("보표 2 파트", heard("세컨드 파트 보", "세컨드 파트보", "세컨드 파트 보고", "세컨드 파트 보험"))
        assertEquals("기타 1 파트", heard("기타 원 파트 보", "기타 원 파트보", "기타 1 파트 보", "기타 1 파트보"))
        assertEquals("기타 1 파트", heard("기타 퍼스트 파트보", "기타 퍼스트 파트 보", "기타포스트 파트보"))
        assertEquals("기타 2 파트", heard("기타 2 파트 복", "기타 2 파트보", "기타 2 파트복"))
        // 파트 없이 서수만은 무엇인지 모른다
        assertEquals(Reason.NOTHING, heard("둘째"))
    }

    @Test
    fun 사람_이름_보표() {
        assertEquals("예완 파트", heard("예원 파트", "예원파트", "예원 파크", names = moldau))
        assertEquals("예완 파트", heard("예원 파트복", "예원 파트보", "예원 파트고", "예원 파트보험", names = moldau))
        assertEquals("하진 파트", heard("하진 하트", "하진 파트", "하진 파크", names = moldau))
        assertEquals("은석 파트", heard("은석 파트복", "은석 파트 복", "은석 파트 보", names = moldau))
        assertEquals("하진 · 예완 파트", heard("하진 예완 파트", "하진 예원 파트", names = moldau))
        // 이름이 없는 악보(아르페지오네)에서 사람 이름 — 실행하지 않고 못 찾았다고
        assertEquals(Reason.UNKNOWN_PART, heard("예완 파트복", "애완 파트복", "예완 파트 복"))
    }

    @Test
    fun 쪽_마디_예비박() {
        assertEquals("마지막 쪽", heard("마지막 페이지", "마지막 페인트", "마지막 페인"))
        assertEquals("115마디 선택", heard("115 마", "115마", "115m"))
        assertEquals("52마디 선택", heard("52 마", "52마", "52 mo", "52 m"))
        assertEquals("50마디 선택", heard("50마디이", "50 마디이"))
        assertEquals("70마디부터 · 예비박 1마디", heard("예비박 한마디 70마디 시작", "예비박 한 마디 70마디 시작"))
        assertEquals("3쪽 · 예비박 2마디 · 시작", heard("예비박 두 마디 3페이지 시작", "예비 밥 두 마디 3페이지 시작"))
        assertEquals("처음부터", heard("처음으로 이동"))
        assertEquals("130마디 선택 · 🎤 연주 듣기", heard("130마디부터 듣고 넘기기", "130마디부터 듣고 넘기"))
        assertEquals("이전 쪽", heard("이전 페이지", "이전 홈페이지"))
    }

    @Test
    fun 대화는_실행하지_않는다() {
        assertEquals(Reason.RELATIVE, heard("누르고 있다가 그 전에는 안 하고 아", "누르고 있다가 그 전에는 안 하고"))
    }
}
