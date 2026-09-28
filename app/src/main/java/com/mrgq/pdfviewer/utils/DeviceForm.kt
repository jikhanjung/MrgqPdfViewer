package com.mrgq.pdfviewer.utils

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * 이 기기가 TV 인가 · 화면 방향 (세로 태블릿, 2026-09-28).
 * TV 는 가로 그대로(매니페스트), TV 가 아니면(태블릿 · 휴대폰) **세로가 기본** — 악보 한 쪽이 화면을 채운다 (사용자 결정).
 * 거꾸로 세워도 되게 sensorPortrait.
 */
object DeviceForm {

    fun isTv(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** 화면 문구의 기기 이름 — "이 TV 연결" · "이 태블릿 연결" */
    fun noun(context: Context): String = when {
        isTv(context) -> "TV"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "태블릿"
        else -> "휴대폰"
    }

    /** 액티비티 onCreate 에서 — TV 가 아니면 세로로 */
    fun applyOrientation(activity: Activity) {
        if (!isTv(activity)) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
}
