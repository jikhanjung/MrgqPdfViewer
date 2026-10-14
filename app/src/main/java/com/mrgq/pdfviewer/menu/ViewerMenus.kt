package com.mrgq.pdfviewer.menu

import androidx.annotation.StringRes
import com.mrgq.pdfviewer.R

/**
 * 악보 화면 메뉴의 **내용** (P17 0단계) — 어떤 줄이 어떤 순서로, 어떤 값으로 보이는가. 그리는 것은 기기마다 따로:
 * TV 는 목록 대화상자(지금 모습, `docs/Viewer_Menus.md`), 터치 기기는 하단 시트(P17 1단계).
 * 줄을 누르면 무엇을 할지는 [MenuAction] 으로만 알린다 — 실제 동작은 악보 화면이 한다.
 *
 * P13 원칙("한 항목은 한 메뉴에만", "못 쓰는 줄은 숨김")을 여기 한 곳에서 지킨다.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
enum class MenuAction {
    // 연주
    MIC_STOP, MIC_FROM_SELECTION, MIC_FROM_PAGE,
    START_SELECTED, CANCEL_SELECTION,
    RESUME, RESELECT, STOP, START,
    METRONOME_SETTINGS, ACCOMPANIMENT,
    // 연주 — 합주 연주자
    DETACH, REJOIN, ENSEMBLE_SOUND,
    // 보기
    PART_VIEW, MEASURE_BOXES, SHOW_NOTES, NOTE_MODE, TWO_PAGE, PAIR, CLIPPING, PHONE_ROTATION,
}

/**
 * 화면 글 (P18) — 리소스 키 + 인자. 이 파일은 Android 에 의존하지 않으므로 글 대신 키를 담고, 화면 쪽이 `resolve(context)` 로 글을 만든다.
 * [Plain] = 사용자 데이터(파트 이름 등) — 번역하지 않는다. 인자에 [Text] 가 있으면 그것도 글로 바꿔 끼운다
 */
sealed class Text {
    data class Res(@StringRes val id: Int, val args: List<Any?> = emptyList()) : Text()
    data class Plain(val value: String) : Text()

    companion object {
        fun of(@StringRes id: Int, vararg args: Any?): Text = Res(id, args.toList())
    }
}

/**
 * 메뉴 줄 하나. [value] 가 있으면 TV 는 "이름: 값" 으로 보인다(`viewer_menu_label_value`). [checked] 가 있으면 켜기 · 끄기 줄 — 터치 기기는 스위치로 그린다
 */
data class MenuItem(
    val action: MenuAction,
    val label: Text,
    val value: Text? = null,
    val checked: Boolean? = null,
)

/** [closeButton] = 아래 "닫기" (보기 메뉴) */
data class Menu(val title: Text, val items: List<MenuItem>, val closeButton: Boolean = false)

object ViewerMenus {

    /** 연주 메뉴(↑ · 터치 ▶)가 보는 지금 상태. [selectedMeasure] = 시작 마디를 고르는 중이면 커서의 마디 */
    data class PlayState(
        val micCapable: Boolean,
        val micListening: Boolean,
        val selectedMeasure: Int?,
        val paused: Boolean,
        val pausedMeasure: Int?,
        val running: Boolean,
        val countInBars: Int,
        val accompaniment: Accompaniment?,
    )

    /** PDF 옆에 MusicXML 이 있을 때만. [dynamics] = 셈여림 따르기(#115) — 끈 때만 줄에 보인다 */
    data class Accompaniment(val on: Boolean, val volumePercent: Int, val dynamics: Boolean = true)

    data class PerformerState(val following: Boolean, val canRejoin: Boolean, val soundOn: Boolean)

    /**
     * [twoPageCapable] = TV 가로(세로 화면 · 휴대폰은 늘 한 쪽). [phoneRotation] = 휴대폰이면 지금 회전 모드 이름.
     * [pair] = 두 대로 펼쳐 보기의 지금 값(한 쪽만 보는 터치 기기만, P22) — null 이면 줄을 숨긴다
     */
    data class ViewState(
        val conductor: Boolean,
        val partViewName: String?,
        val measureBoxes: Boolean,
        val notesVisible: Boolean,
        val notesWritable: Boolean,
        val noteEditMode: Boolean,
        val twoPageCapable: Boolean,
        val twoPage: Boolean,
        val phoneRotation: String?,
        val pair: Text? = null,
    )

    fun play(s: PlayState): Menu {
        val items = mutableListOf<MenuItem>()
        if (s.micCapable) {
            items += when {
                s.micListening -> MenuItem(MenuAction.MIC_STOP, Text.of(R.string.viewer_menu_mic_stop))
                s.selectedMeasure != null -> MenuItem(MenuAction.MIC_FROM_SELECTION, Text.of(R.string.viewer_menu_mic_from_measure, s.selectedMeasure))
                else -> MenuItem(MenuAction.MIC_FROM_PAGE, Text.of(R.string.viewer_menu_mic_from_page))
            }
        }
        when {
            s.selectedMeasure != null -> {
                items += MenuItem(MenuAction.START_SELECTED, Text.of(R.string.viewer_menu_start_selected, s.selectedMeasure, s.countInBars))
                items += MenuItem(MenuAction.CANCEL_SELECTION, Text.of(R.string.viewer_menu_cancel_selection))
            }
            s.paused -> {
                items += MenuItem(MenuAction.RESUME, Text.of(R.string.viewer_menu_resume, s.pausedMeasure, s.countInBars))
                items += MenuItem(MenuAction.RESELECT, Text.of(R.string.viewer_menu_reselect))
                items += MenuItem(MenuAction.STOP, Text.of(R.string.viewer_menu_stop))
            }
            s.running -> items += MenuItem(MenuAction.STOP, Text.of(R.string.viewer_menu_stop))
            else -> items += MenuItem(MenuAction.START, Text.of(R.string.viewer_menu_start))
        }
        // 연주(소리 · 시간)만 — 파트 보기 · 메모는 보기 메뉴에 (P13)
        items += MenuItem(MenuAction.METRONOME_SETTINGS, Text.of(R.string.viewer_menu_metronome_settings))
        s.accompaniment?.let {
            val value = when {
                !it.on -> Text.of(R.string.viewer_menu_off)
                it.dynamics -> Text.of(R.string.viewer_menu_accompaniment_on, it.volumePercent)
                else -> Text.of(R.string.viewer_menu_accompaniment_on_no_dynamics, it.volumePercent)
            }
            items += MenuItem(MenuAction.ACCOMPANIMENT, Text.of(R.string.viewer_menu_accompaniment), value)
        }
        return Menu(Text.of(if (s.paused) R.string.viewer_menu_play_title_paused else R.string.viewer_menu_play_title), items)
    }

    /** 합주 연주자의 연주 메뉴 — 템포 · 박자 · 시작은 지휘자를 따른다. 소리는 누르면 바로 켬 ↔ 끔 */
    fun performer(s: PerformerState): Menu {
        val items = mutableListOf<MenuItem>()
        when {
            s.following -> items += MenuItem(MenuAction.DETACH, Text.of(R.string.viewer_menu_detach))
            s.canRejoin -> items += MenuItem(MenuAction.REJOIN, Text.of(R.string.viewer_menu_rejoin))
        }
        items += MenuItem(
            MenuAction.ENSEMBLE_SOUND, Text.of(R.string.viewer_menu_ensemble_sound),
            Text.of(if (s.soundOn) R.string.viewer_menu_sound_on else R.string.viewer_menu_sound_off), checked = s.soundOn,
        )
        val title = when {
            s.following -> R.string.viewer_menu_performer_title_following
            s.canRejoin -> R.string.viewer_menu_performer_title_detached
            else -> R.string.viewer_menu_performer_title_waiting
        }
        return Menu(Text.of(title), items)
    }

    /**
     * 두 대로 펼쳐 보기 **짝 기기**의 연주 메뉴(P22) — 연주는 주 기기가 하고 여기서는 명령만 보낸다. [playing] = 주 기기가 하는 것
     * (`PairProtocol.PLAYING_*` — metronome · mic · selecting, 없으면 멈춤). 시작은 지금 펼침의 첫 마디부터
     */
    fun pairPartnerPlay(playing: String?, micCapable: Boolean): Menu {
        val items = when (playing) {
            "mic" -> listOf(MenuItem(MenuAction.MIC_STOP, Text.of(R.string.viewer_menu_mic_stop)))
            "metronome", "selecting" -> listOf(MenuItem(MenuAction.STOP, Text.of(R.string.viewer_menu_stop)))
            else -> listOfNotNull(
                MenuItem(MenuAction.START, Text.of(R.string.viewer_menu_pair_start)),
                if (micCapable) MenuItem(MenuAction.MIC_FROM_PAGE, Text.of(R.string.viewer_menu_pair_listen)) else null,
            )
        }
        return Menu(Text.of(R.string.viewer_menu_pair_play_title), items)
    }

    /** 보기 메뉴 — 화면에 무엇을 어떻게 보일지만 */
    fun view(s: ViewState): Menu {
        val items = mutableListOf<MenuItem>()
        // 지휘자는 늘 총보 (P07 4단계)
        if (!s.conductor) {
            val part = s.partViewName?.let { Text.Plain(it) } ?: Text.of(R.string.viewer_menu_full_score)
            items += MenuItem(MenuAction.PART_VIEW, Text.of(R.string.viewer_menu_part_view), part)
        }
        items += MenuItem(MenuAction.MEASURE_BOXES, Text.of(R.string.viewer_menu_measure_boxes), onOff(s.measureBoxes), checked = s.measureBoxes)
        items += MenuItem(MenuAction.SHOW_NOTES, Text.of(R.string.viewer_menu_show_notes), onOff(s.notesVisible), checked = s.notesVisible)
        if (s.notesWritable) {
            items += MenuItem(MenuAction.NOTE_MODE, Text.of(if (s.noteEditMode) R.string.viewer_menu_note_mode_end else R.string.viewer_menu_note_mode_start))
        }
        if (s.twoPageCapable) {
            items += MenuItem(
                MenuAction.TWO_PAGE, Text.of(R.string.viewer_menu_two_page),
                Text.of(if (s.twoPage) R.string.viewer_menu_two_page_double else R.string.viewer_menu_two_page_single),
            )
        }
        s.pair?.let { items += MenuItem(MenuAction.PAIR, Text.of(R.string.viewer_menu_pair), it) }
        items += MenuItem(
            MenuAction.CLIPPING,
            Text.of(if (s.twoPageCapable && s.twoPage) R.string.viewer_menu_clipping_padding else R.string.viewer_menu_clipping),
        )
        s.phoneRotation?.let { items += MenuItem(MenuAction.PHONE_ROTATION, Text.of(R.string.viewer_menu_rotation), Text.Plain(it)) }
        return Menu(Text.of(R.string.viewer_menu_view_title), items, closeButton = true)
    }

    private fun onOff(on: Boolean) = Text.of(if (on) R.string.viewer_menu_on else R.string.viewer_menu_off)
}
