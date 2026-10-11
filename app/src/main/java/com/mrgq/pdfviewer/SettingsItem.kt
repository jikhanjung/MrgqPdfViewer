package com.mrgq.pdfviewer

data class SettingsItem(
    val id: String,
    val icon: String,
    val title: String,
    val subtitle: String = "",
    val arrow: String = "",
    val type: SettingsType = SettingsType.CATEGORY,
    val enabled: Boolean = true,
    val action: (() -> Unit)? = null,
    /** 켜기 · 끄기 줄의 지금 값 — 태블릿 · 휴대폰은 스위치로 보인다 (P17 5단계). null = 스위치 없음 */
    val checked: Boolean? = null,
)

enum class SettingsType {
    CATEGORY,
    TOGGLE,
    ACTION,
    INPUT,
    INFO,
    /** 오른쪽 칸의 묶음 제목 (#122) — [SettingsItem.title] 만, 포커스 · 누름 없음 */
    HEADER,
    /** 누르면 바로 무언가를 하는 줄 — 단추 모양으로 보인다(제목 = 단추 글, 설명은 아래) (#123) */
    BUTTON
}