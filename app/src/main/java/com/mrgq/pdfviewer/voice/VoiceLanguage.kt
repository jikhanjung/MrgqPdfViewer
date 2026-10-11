package com.mrgq.pdfviewer.voice

import android.content.Context
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.utils.AppLanguage

/**
 * 음성 명령 언어 (P18 8단계) — 인식 언어와 명령 문법(파서)을 함께 고른다. 앱 언어와 **따로** 둔다(한국어 화면 + 영어로 말하기도 있다):
 * 설정 "음성 명령 언어" = 앱 언어 따름(기본) · 한국어 · English([VoiceLanguageSetting]).
 *
 * 한국어 문법([CommandParser])이 정본이고, 영어([EnglishCommandParser])는 좁은 범위 — 사람 이름 보표는 한국어만.
 */
enum class VoiceLanguage(val recognizerTag: String) {
    KOREAN("ko-KR"),
    ENGLISH("en-US");

    /** 발화 하나 → 명령. [staffNames] (사람 이름 보표)는 한국어만 쓴다 */
    fun parse(text: String, staffNames: List<String> = emptyList(), staffCount: Int? = null): ParseResult = when (this) {
        KOREAN -> CommandParser.parse(text, staffNames, staffCount)
        ENGLISH -> EnglishCommandParser.parse(text, staffCount)
    }

    /** 음성 인식 후보 여럿 → 쓸 후보와 결과 ([CommandParser.parseBest] 와 같은 고르기) */
    fun parseBest(candidates: List<String>, staffNames: List<String> = emptyList(), staffCount: Int? = null): Pair<String, ParseResult>? =
        when (this) {
            KOREAN -> CommandParser.parseBest(candidates, staffNames, staffCount)
            ENGLISH -> EnglishCommandParser.parseBest(candidates, staffCount)
        }

    /** 인식 힌트(Android 13+)에 넣을 악기 이름 — 이 언어로 */
    fun instrumentHints(): List<String> = when (this) {
        KOREAN -> VoiceLexicon.INSTRUMENT_LABELS.values.toList()
        ENGLISH -> EnglishCommandParser.INSTRUMENT_HINTS
    }

    companion object {
        /** prefs(`pdf_viewer_prefs`) 키 — 값은 [VoiceLanguageSetting.value], 없으면 앱 언어 따름 */
        const val PREF_VOICE_LANGUAGE = "voice_language"
        private const val PREFS = "pdf_viewer_prefs"

        /** 설정 + 앱 언어(`"en"` · `"ko"` …) → 음성 언어. JVM 테스트 대상 */
        fun resolve(setting: VoiceLanguageSetting, appLanguage: String): VoiceLanguage = when (setting) {
            VoiceLanguageSetting.KOREAN -> KOREAN
            VoiceLanguageSetting.ENGLISH -> ENGLISH
            VoiceLanguageSetting.FOLLOW_APP -> if (appLanguage.lowercase().startsWith("en")) ENGLISH else KOREAN
        }

        fun setting(context: Context): VoiceLanguageSetting =
            VoiceLanguageSetting.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_VOICE_LANGUAGE, null))

        fun setSetting(context: Context, setting: VoiceLanguageSetting) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (setting == VoiceLanguageSetting.FOLLOW_APP) editor.remove(PREF_VOICE_LANGUAGE) else editor.putString(PREF_VOICE_LANGUAGE, setting.value)
            editor.apply()
        }

        /** 지금 쓸 음성 언어 — [context] 는 액티비티(앱 언어가 걸린 Context) */
        fun current(context: Context): VoiceLanguage = resolve(setting(context), AppLanguage.locale(context).language)

        /** 설정 줄 · 대화상자에 보일 이름 — 언어 이름은 그 언어로("한국어" · "English") */
        fun label(context: Context, setting: VoiceLanguageSetting): String = when (setting) {
            VoiceLanguageSetting.FOLLOW_APP -> context.getString(R.string.voice_language_follow_app)
            VoiceLanguageSetting.KOREAN -> context.getString(R.string.language_name_ko)
            VoiceLanguageSetting.ENGLISH -> context.getString(R.string.language_name_en)
        }

        /** 설정 줄 부제 — "앱 언어 따름 (지금 한국어)" · "English" */
        fun summary(context: Context): String {
            val setting = setting(context)
            if (setting != VoiceLanguageSetting.FOLLOW_APP) return label(context, setting)
            val now = label(context, if (current(context) == ENGLISH) VoiceLanguageSetting.ENGLISH else VoiceLanguageSetting.KOREAN)
            return context.getString(R.string.voice_language_follow_app_now, now)
        }
    }
}

/** 설정 "음성 명령 언어"의 값 — [value] 는 prefs 에 저장하는 글자 */
enum class VoiceLanguageSetting(val value: String) {
    FOLLOW_APP("app"),
    KOREAN("ko"),
    ENGLISH("en");

    companion object {
        fun of(value: String?): VoiceLanguageSetting = values().firstOrNull { it.value == value } ?: FOLLOW_APP
    }
}
