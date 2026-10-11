package com.mrgq.pdfviewer.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mrgq.pdfviewer.R
import java.util.Locale

/**
 * 앱 언어 (P18) — 설정 → 앱 정보 → 언어. AppCompat 앱별 언어(`setApplicationLocales`)로 고르고,
 * Android 12 이하는 매니페스트의 `autoStoreLocales` 가 저장 · 복원한다. 바꾸면 떠 있는 액티비티가 다시 만들어진다.
 * 기본(`values/`)은 한국어, 영어는 `values-en/` — 번역이 빠진 줄은 한국어로 보인다.
 */
object AppLanguage {

    /** [tag] 빈 문자열 = 시스템 따름 */
    enum class Choice(val tag: String) { SYSTEM(""), KOREAN("ko"), ENGLISH("en") }

    fun current(): Choice {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return Choice.values().firstOrNull { it.tag.isNotEmpty() && tag.startsWith(it.tag) } ?: Choice.SYSTEM
    }

    fun set(choice: Choice) {
        AppCompatDelegate.setApplicationLocales(
            if (choice == Choice.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(choice.tag),
        )
    }

    /** 화면에 보이는 언어 이름 — 그 언어로 */
    fun label(context: Context, choice: Choice): String = when (choice) {
        Choice.SYSTEM -> context.getString(R.string.settings_language_system)
        Choice.KOREAN -> context.getString(R.string.language_name_ko)
        Choice.ENGLISH -> context.getString(R.string.language_name_en)
    }

    /** 지금 화면 글의 언어 — 날짜 · 개수 형식에 (숫자 소수점은 `Locale.US` 그대로) */
    fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}
