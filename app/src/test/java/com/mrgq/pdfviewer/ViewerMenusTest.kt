package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.menu.MenuAction
import com.mrgq.pdfviewer.menu.ViewerMenus
import com.mrgq.pdfviewer.menu.ViewerMenus.Accompaniment
import com.mrgq.pdfviewer.menu.ViewerMenus.PerformerState
import com.mrgq.pdfviewer.menu.ViewerMenus.PlayState
import com.mrgq.pdfviewer.menu.ViewerMenus.ViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 악보 화면 메뉴 내용 (P17 0단계) — TV 목록의 글 · 순서는 `docs/Viewer_Menus.md` 그대로, 못 쓰는 줄은 숨김 (P13) */
class ViewerMenusTest {

    private val idle = PlayState(
        micCapable = false, micListening = false, selectedMeasure = null, paused = false, pausedMeasure = null,
        running = false, countInBars = 2, accompaniment = null,
    )

    private val view = ViewState(
        conductor = false, partViewName = null, measureBoxes = false, notesVisible = true, notesWritable = false,
        noteEditMode = false, twoPageCapable = true, twoPage = false, phoneRotation = null,
    )

    @Test
    fun 연주_멈춤_TV() {
        val menu = ViewerMenus.play(idle)
        assertEquals("연주", menu.title)
        assertEquals(listOf("시작 — 악보에서 마디 고르기", "메트로놈 설정…"), menu.items.map { it.text })
        assertFalse(menu.closeButton)
    }

    @Test
    fun 연주_마디_고르는_중_태블릿() {
        val menu = ViewerMenus.play(idle.copy(micCapable = true, selectedMeasure = 57))
        assertEquals(
            listOf("🎤 57마디부터 연주 듣고 넘기기", "▶ 57마디부터 시작 (예비박 2마디 뒤)", "마디 고르기 취소", "메트로놈 설정…"),
            menu.items.map { it.text },
        )
        assertEquals(MenuAction.MIC_FROM_SELECTION, menu.items[0].action)
    }

    @Test
    fun 연주_일시정지와_반주() {
        val menu = ViewerMenus.play(
            idle.copy(paused = true, pausedMeasure = 12, countInBars = 1, accompaniment = Accompaniment(true, 60)),
        )
        assertEquals("연주 — 일시정지", menu.title)
        assertEquals(
            listOf(
                "이어서 — 12번 마디부터 (예비박 1마디 뒤)", "마디 골라 다시 시작", "정지", "메트로놈 설정…",
                "반주 (MusicXML): 켜짐 · 크기 60%",
            ),
            menu.items.map { it.text },
        )
        assertEquals("반주 (MusicXML): 꺼짐", ViewerMenus.play(idle.copy(accompaniment = Accompaniment(false, 60))).items.last().text)
    }

    @Test
    fun 연주_도는_중_듣는_중() {
        assertEquals(listOf("정지", "메트로놈 설정…"), ViewerMenus.play(idle.copy(running = true)).items.map { it.text })
        val listening = ViewerMenus.play(idle.copy(micCapable = true, micListening = true, running = true))
        assertEquals(listOf(MenuAction.MIC_STOP, MenuAction.STOP, MenuAction.METRONOME_SETTINGS), listening.items.map { it.action })
        assertEquals(
            "🎤 연주 듣고 넘기기 — 이 쪽 첫 마디부터",
            ViewerMenus.play(idle.copy(micCapable = true)).items.first().text,
        )
    }

    @Test
    fun 합주_연주자() {
        val following = ViewerMenus.performer(PerformerState(following = true, canRejoin = false, soundOn = false))
        assertEquals("연주 — 지휘자를 따라가는 중", following.title)
        assertEquals(listOf("이 기기만 빠지기", "메트로놈 소리: 끔"), following.items.map { it.text })
        assertEquals(false, following.items.last().checked)

        val detached = ViewerMenus.performer(PerformerState(following = false, canRejoin = true, soundOn = true))
        assertEquals("연주 — 이 기기는 빠져 있음", detached.title)
        assertEquals(listOf("지휘자 연주에 다시 합류 — 지금 연주 중인 마디로", "메트로놈 소리: 켬"), detached.items.map { it.text })

        val waiting = ViewerMenus.performer(PerformerState(following = false, canRejoin = false, soundOn = false))
        assertEquals("연주 — 지휘자가 시작하면 따라갑니다", waiting.title)
        assertEquals(listOf(MenuAction.ENSEMBLE_SOUND), waiting.items.map { it.action })
    }

    @Test
    fun 보기_TV_가로() {
        val menu = ViewerMenus.view(view)
        assertEquals("보기", menu.title)
        assertTrue(menu.closeButton)
        assertEquals(
            listOf("파트 보기: 전체 악보", "마디 박스: 꺼짐", "메모 보이기: 켜짐", "두 쪽 보기: 한 쪽", "위/아래 자르기…"),
            menu.items.map { it.text },
        )
        assertEquals(listOf(false, true), menu.items.filter { it.checked != null }.map { it.checked })
        assertEquals(
            listOf("두 쪽 보기: 두 쪽", "자르기 · 여백…"),
            ViewerMenus.view(view.copy(twoPage = true)).items.takeLast(2).map { it.text },
        )
    }

    @Test
    fun 보기_지휘자는_파트_보기_없음() {
        val actions = ViewerMenus.view(view.copy(conductor = true, partViewName = "Violin I")).items.map { it.action }
        assertFalse(MenuAction.PART_VIEW in actions)
        assertEquals("파트 보기: Violin I", ViewerMenus.view(view.copy(partViewName = "Violin I")).items.first().text)
    }

    @Test
    fun 보기_태블릿과_휴대폰() {
        val tablet = ViewerMenus.view(view.copy(notesWritable = true, twoPageCapable = false, twoPage = true))
        assertEquals(
            listOf("파트 보기: 전체 악보", "마디 박스: 꺼짐", "메모 보이기: 켜짐", "✏️ 메모 쓰기", "위/아래 자르기…"),
            tablet.items.map { it.text },
        )
        val phone = ViewerMenus.view(
            view.copy(notesWritable = true, noteEditMode = true, twoPageCapable = false, phoneRotation = "기기 회전"),
        )
        assertEquals(listOf("✏️ 메모 끝", "위/아래 자르기…", "회전: 기기 회전"), phone.items.takeLast(3).map { it.text })
    }

    @Test
    fun 한_항목은_한_메뉴에만() {
        val play = ViewerMenus.play(idle.copy(micCapable = true, accompaniment = Accompaniment(true, 60))).items.map { it.action }
        val viewActions = ViewerMenus.view(view.copy(notesWritable = true, phoneRotation = "가로")).items.map { it.action }
        assertTrue(play.intersect(viewActions.toSet()).isEmpty())
    }
}
