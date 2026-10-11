package com.mrgq.pdfviewer.menu

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
    PART_VIEW, MEASURE_BOXES, SHOW_NOTES, NOTE_MODE, TWO_PAGE, CLIPPING, PHONE_ROTATION,
}

/**
 * 메뉴 줄 하나. [value] 가 있으면 TV 는 "이름: 값" 으로 보인다. [checked] 가 있으면 켜기 · 끄기 줄 — 터치 기기는 스위치로 그린다
 */
data class MenuItem(
    val action: MenuAction,
    val label: String,
    val value: String? = null,
    val checked: Boolean? = null,
) {
    /** TV 목록 대화상자에 쓰는 글 — 지금 모습 그대로 */
    val text: String get() = if (value != null) "$label: $value" else label
}

/** [closeButton] = 아래 "닫기" (보기 메뉴) */
data class Menu(val title: String, val items: List<MenuItem>, val closeButton: Boolean = false)

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

    /** PDF 옆에 MusicXML 이 있을 때만 */
    data class Accompaniment(val on: Boolean, val volumePercent: Int)

    data class PerformerState(val following: Boolean, val canRejoin: Boolean, val soundOn: Boolean)

    /** [twoPageCapable] = TV 가로(세로 화면 · 휴대폰은 늘 한 쪽). [phoneRotation] = 휴대폰이면 지금 회전 모드 이름 */
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
    )

    fun play(s: PlayState): Menu {
        val items = mutableListOf<MenuItem>()
        if (s.micCapable) {
            items += when {
                s.micListening -> MenuItem(MenuAction.MIC_STOP, "🎤 듣기 멈춤")
                s.selectedMeasure != null -> MenuItem(MenuAction.MIC_FROM_SELECTION, "🎤 ${s.selectedMeasure}마디부터 연주 듣고 넘기기")
                else -> MenuItem(MenuAction.MIC_FROM_PAGE, "🎤 연주 듣고 넘기기 — 이 쪽 첫 마디부터")
            }
        }
        when {
            s.selectedMeasure != null -> {
                items += MenuItem(MenuAction.START_SELECTED, "▶ ${s.selectedMeasure}마디부터 시작 (예비박 ${s.countInBars}마디 뒤)")
                items += MenuItem(MenuAction.CANCEL_SELECTION, "마디 고르기 취소")
            }
            s.paused -> {
                items += MenuItem(MenuAction.RESUME, "이어서 — ${s.pausedMeasure}번 마디부터 (예비박 ${s.countInBars}마디 뒤)")
                items += MenuItem(MenuAction.RESELECT, "마디 골라 다시 시작")
                items += MenuItem(MenuAction.STOP, "정지")
            }
            s.running -> items += MenuItem(MenuAction.STOP, "정지")
            else -> items += MenuItem(MenuAction.START, "시작 — 악보에서 마디 고르기")
        }
        // 연주(소리 · 시간)만 — 파트 보기 · 메모는 보기 메뉴에 (P13)
        items += MenuItem(MenuAction.METRONOME_SETTINGS, "메트로놈 설정…")
        s.accompaniment?.let {
            items += MenuItem(MenuAction.ACCOMPANIMENT, "반주 (MusicXML)", if (it.on) "켜짐 · 크기 ${it.volumePercent}%" else "꺼짐")
        }
        return Menu(if (s.paused) "연주 — 일시정지" else "연주", items)
    }

    /** 합주 연주자의 연주 메뉴 — 템포 · 박자 · 시작은 지휘자를 따른다. 소리는 누르면 바로 켬 ↔ 끔 */
    fun performer(s: PerformerState): Menu {
        val items = mutableListOf<MenuItem>()
        when {
            s.following -> items += MenuItem(MenuAction.DETACH, "이 기기만 빠지기")
            s.canRejoin -> items += MenuItem(MenuAction.REJOIN, "지휘자 연주에 다시 합류 — 지금 연주 중인 마디로")
        }
        items += MenuItem(MenuAction.ENSEMBLE_SOUND, "메트로놈 소리", if (s.soundOn) "켬" else "끔", checked = s.soundOn)
        val title = when {
            s.following -> "연주 — 지휘자를 따라가는 중"
            s.canRejoin -> "연주 — 이 기기는 빠져 있음"
            else -> "연주 — 지휘자가 시작하면 따라갑니다"
        }
        return Menu(title, items)
    }

    /** 보기 메뉴 — 화면에 무엇을 어떻게 보일지만 */
    fun view(s: ViewState): Menu {
        val items = mutableListOf<MenuItem>()
        // 지휘자는 늘 총보 (P07 4단계)
        if (!s.conductor) items += MenuItem(MenuAction.PART_VIEW, "파트 보기", s.partViewName ?: "전체 악보")
        items += MenuItem(MenuAction.MEASURE_BOXES, "마디 박스", onOff(s.measureBoxes), checked = s.measureBoxes)
        items += MenuItem(MenuAction.SHOW_NOTES, "메모 보이기", onOff(s.notesVisible), checked = s.notesVisible)
        if (s.notesWritable) items += MenuItem(MenuAction.NOTE_MODE, if (s.noteEditMode) "✏️ 메모 끝" else "✏️ 메모 쓰기")
        if (s.twoPageCapable) items += MenuItem(MenuAction.TWO_PAGE, "두 쪽 보기", if (s.twoPage) "두 쪽" else "한 쪽")
        items += MenuItem(MenuAction.CLIPPING, if (s.twoPageCapable && s.twoPage) "자르기 · 여백…" else "위/아래 자르기…")
        s.phoneRotation?.let { items += MenuItem(MenuAction.PHONE_ROTATION, "회전", it) }
        return Menu("보기", items, closeButton = true)
    }

    private fun onOff(on: Boolean) = if (on) "켜짐" else "꺼짐"
}
