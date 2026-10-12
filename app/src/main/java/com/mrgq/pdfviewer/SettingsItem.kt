package com.mrgq.pdfviewer

data class SettingsItem(
    val id: String,
    val icon: String,
    /** 이모지 대신 그릴 그림(벡터) — 있으면 [icon] 글 대신. 첫 화면과 같은 합주 그림처럼 */
    @androidx.annotation.DrawableRes val iconRes: Int? = null,
    val title: String,
    val arrow: String = "",
    /** 줄 오른쪽에 보일 지금 값(포트 · 언어 · 버전 등) — 설명 줄 대신 (#127). 있으면 [arrow] 대신 */
    val value: String = "",
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
    /** 누르면 바로 무언가를 하는 줄 — 단추 모양으로 보인다(제목 = 단추 글) (#123) */
    BUTTON
}