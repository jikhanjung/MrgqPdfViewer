package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.menu.MenuAction
import com.mrgq.pdfviewer.menu.MenuItem
import com.mrgq.pdfviewer.menu.Text
import com.mrgq.pdfviewer.menu.ViewerMenus
import com.mrgq.pdfviewer.menu.ViewerMenus.Accompaniment
import com.mrgq.pdfviewer.menu.ViewerMenus.PerformerState
import com.mrgq.pdfviewer.menu.ViewerMenus.PlayState
import com.mrgq.pdfviewer.menu.ViewerMenus.ViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 악보 화면 메뉴 내용 (P17 0단계) — TV 목록의 글 · 순서는 `docs/Viewer_Menus.md` 그대로, 못 쓰는 줄은 숨김 (P13).
 * 글은 리소스 키 + 인자로 (P18) — 한국어 글은 `values/strings_viewer_menu.xml`
 */
class ViewerMenusTest {

    private fun t(id: Int, vararg args: Any?): Text = Text.of(id, *args)

    /** TV 한 줄 "이름: 값" 대신 (이름, 값) */
    private val MenuItem.pair: Pair<Text, Text?> get() = label to value

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
        assertEquals(t(R.string.viewer_menu_play_title), menu.title)
        assertEquals(listOf(t(R.string.viewer_menu_start), t(R.string.viewer_menu_metronome_settings)), menu.items.map { it.label })
        assertFalse(menu.closeButton)
    }

    @Test
    fun 연주_마디_고르는_중_태블릿() {
        val menu = ViewerMenus.play(idle.copy(micCapable = true, selectedMeasure = 57))
        assertEquals(
            listOf(
                t(R.string.viewer_menu_mic_from_measure, 57), t(R.string.viewer_menu_start_selected, 57, 2),
                t(R.string.viewer_menu_cancel_selection), t(R.string.viewer_menu_metronome_settings),
            ),
            menu.items.map { it.label },
        )
        assertEquals(MenuAction.MIC_FROM_SELECTION, menu.items[0].action)
    }

    @Test
    fun 연주_일시정지와_반주() {
        val menu = ViewerMenus.play(
            idle.copy(paused = true, pausedMeasure = 12, countInBars = 1, accompaniment = Accompaniment(true, 60)),
        )
        assertEquals(t(R.string.viewer_menu_play_title_paused), menu.title)
        assertEquals(
            listOf(
                t(R.string.viewer_menu_resume, 12, 1), t(R.string.viewer_menu_reselect), t(R.string.viewer_menu_stop),
                t(R.string.viewer_menu_metronome_settings), t(R.string.viewer_menu_accompaniment),
            ),
            menu.items.map { it.label },
        )
        assertEquals(t(R.string.viewer_menu_accompaniment_on, 60), menu.items.last().value)
        assertEquals(
            t(R.string.viewer_menu_off),
            ViewerMenus.play(idle.copy(accompaniment = Accompaniment(false, 60))).items.last().value,
        )
        // 셈여림 따르기를 끈 때만 줄에 (#115)
        assertEquals(
            t(R.string.viewer_menu_accompaniment_on_no_dynamics, 60),
            ViewerMenus.play(idle.copy(accompaniment = Accompaniment(true, 60, dynamics = false))).items.last().value,
        )
    }

    @Test
    fun 연주_도는_중_듣는_중() {
        assertEquals(
            listOf(t(R.string.viewer_menu_stop), t(R.string.viewer_menu_metronome_settings)),
            ViewerMenus.play(idle.copy(running = true)).items.map { it.label },
        )
        val listening = ViewerMenus.play(idle.copy(micCapable = true, micListening = true, running = true))
        assertEquals(listOf(MenuAction.MIC_STOP, MenuAction.STOP, MenuAction.METRONOME_SETTINGS), listening.items.map { it.action })
        assertEquals(t(R.string.viewer_menu_mic_from_page), ViewerMenus.play(idle.copy(micCapable = true)).items.first().label)
    }

    @Test
    fun 합주_연주자() {
        val following = ViewerMenus.performer(PerformerState(following = true, canRejoin = false, soundOn = false))
        assertEquals(t(R.string.viewer_menu_performer_title_following), following.title)
        assertEquals(
            listOf(t(R.string.viewer_menu_detach) to null, t(R.string.viewer_menu_ensemble_sound) to t(R.string.viewer_menu_sound_off)),
            following.items.map { it.pair },
        )
        assertEquals(false, following.items.last().checked)

        val detached = ViewerMenus.performer(PerformerState(following = false, canRejoin = true, soundOn = true))
        assertEquals(t(R.string.viewer_menu_performer_title_detached), detached.title)
        assertEquals(
            listOf(t(R.string.viewer_menu_rejoin) to null, t(R.string.viewer_menu_ensemble_sound) to t(R.string.viewer_menu_sound_on)),
            detached.items.map { it.pair },
        )

        val waiting = ViewerMenus.performer(PerformerState(following = false, canRejoin = false, soundOn = false))
        assertEquals(t(R.string.viewer_menu_performer_title_waiting), waiting.title)
        assertEquals(listOf(MenuAction.ENSEMBLE_SOUND), waiting.items.map { it.action })
    }

    @Test
    fun 보기_TV_가로() {
        val menu = ViewerMenus.view(view)
        assertEquals(t(R.string.viewer_menu_view_title), menu.title)
        assertTrue(menu.closeButton)
        assertEquals(
            listOf(
                t(R.string.viewer_menu_part_view) to t(R.string.viewer_menu_full_score),
                t(R.string.viewer_menu_measure_boxes) to t(R.string.viewer_menu_off),
                t(R.string.viewer_menu_show_notes) to t(R.string.viewer_menu_on),
                t(R.string.viewer_menu_two_page) to t(R.string.viewer_menu_two_page_single),
                t(R.string.viewer_menu_clipping) to null,
            ),
            menu.items.map { it.pair },
        )
        assertEquals(listOf(false, true), menu.items.filter { it.checked != null }.map { it.checked })
        assertEquals(
            listOf(
                t(R.string.viewer_menu_two_page) to t(R.string.viewer_menu_two_page_double),
                t(R.string.viewer_menu_clipping_padding) to null,
            ),
            ViewerMenus.view(view.copy(twoPage = true)).items.takeLast(2).map { it.pair },
        )
    }

    @Test
    fun 보기_지휘자는_파트_보기_없음() {
        val actions = ViewerMenus.view(view.copy(conductor = true, partViewName = "Violin I")).items.map { it.action }
        assertFalse(MenuAction.PART_VIEW in actions)
        assertEquals(
            t(R.string.viewer_menu_part_view) to Text.Plain("Violin I"),
            ViewerMenus.view(view.copy(partViewName = "Violin I")).items.first().pair,
        )
    }

    @Test
    fun 보기_태블릿과_휴대폰() {
        val tablet = ViewerMenus.view(view.copy(notesWritable = true, twoPageCapable = false, twoPage = true))
        assertEquals(
            listOf(
                t(R.string.viewer_menu_part_view) to t(R.string.viewer_menu_full_score),
                t(R.string.viewer_menu_measure_boxes) to t(R.string.viewer_menu_off),
                t(R.string.viewer_menu_show_notes) to t(R.string.viewer_menu_on),
                t(R.string.viewer_menu_note_mode_start) to null,
                t(R.string.viewer_menu_clipping) to null,
            ),
            tablet.items.map { it.pair },
        )
        val phone = ViewerMenus.view(
            view.copy(notesWritable = true, noteEditMode = true, twoPageCapable = false, phoneRotation = "기기 회전"),
        )
        assertEquals(
            listOf(
                t(R.string.viewer_menu_note_mode_end) to null,
                t(R.string.viewer_menu_clipping) to null,
                t(R.string.viewer_menu_rotation) to Text.Plain("기기 회전"),
            ),
            phone.items.takeLast(3).map { it.pair },
        )
    }

    @Test
    fun 보기_두_대로_펼쳐_보기는_값이_있을_때만_P22() {
        assertFalse(ViewerMenus.view(view.copy(twoPageCapable = false)).items.any { it.action == MenuAction.PAIR })
        val value = t(R.string.viewer_menu_pair_connected, "왼쪽")
        val items = ViewerMenus.view(view.copy(twoPageCapable = false, pair = value)).items
        assertEquals(
            listOf(t(R.string.viewer_menu_pair) to value, t(R.string.viewer_menu_clipping) to null),
            items.takeLast(2).map { it.pair },
        )
    }

    @Test
    fun 한_항목은_한_메뉴에만() {
        val play = ViewerMenus.play(idle.copy(micCapable = true, accompaniment = Accompaniment(true, 60))).items.map { it.action }
        val viewActions = ViewerMenus.view(view.copy(notesWritable = true, phoneRotation = "가로")).items.map { it.action }
        assertTrue(play.intersect(viewActions.toSet()).isEmpty())
    }
}
