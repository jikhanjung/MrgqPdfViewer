package com.mrgq.pdfviewer

data class SettingsItem(
    val id: String,
    val icon: String,
    val title: String,
    val subtitle: String = "",
    val arrow: String = "",
    val type: SettingsType = SettingsType.CATEGORY,
    val enabled: Boolean = true,
    val action: (() -> Unit)? = null
)

enum class SettingsType {
    CATEGORY,
    TOGGLE,
    ACTION,
    INPUT,
    INFO
}