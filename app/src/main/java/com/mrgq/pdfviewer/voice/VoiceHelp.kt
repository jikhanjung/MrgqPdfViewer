package com.mrgq.pdfviewer.voice

import androidx.annotation.StringRes
import com.mrgq.pdfviewer.R

/**
 * 🎙 음성 명령 도움말 (#128) — "도움말" · "사용법"(영어 "help")이라고 말하면 악보 화면이 띄우는 안내 페이지의 내용.
 * 말할 문장은 **음성 언어**로([Entry.say]), 뜻은 **앱 언어**로(리소스 키 [Entry.meaning]) — 한국어 화면에 영어로 말하기도 있다.
 *
 * 문장은 실제 파서가 읽는 것만 둔다 — 단위 테스트(`VoiceHelpTest`)가 한 · 영 문장이 같은 명령이 되는지 지킨다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object VoiceHelp {

    /** 한 줄 — [korean] · [english] 는 같은 명령이 되는 문장 */
    data class Entry(val korean: String, val english: String, @StringRes val meaning: Int) {
        fun say(language: VoiceLanguage): String = if (language == VoiceLanguage.ENGLISH) english else korean
    }

    data class Section(@StringRes val title: Int, val entries: List<Entry>)

    val SECTIONS: List<Section> = listOf(
        Section(R.string.voice_help_sec_position, listOf(
            Entry("57마디", "measure 57", R.string.voice_help_select_measure),
            Entry("57마디부터", "from measure 57", R.string.voice_help_goto_measure),
            Entry("시작", "start", R.string.voice_help_start),
            Entry("처음부터", "from the top", R.string.voice_help_goto_start),
            Entry("다시", "again", R.string.voice_help_restart),
            Entry("이어서", "resume", R.string.voice_help_resume),
            Entry("레터 B", "letter B", R.string.voice_help_letter),
            Entry("멈춰", "stop", R.string.voice_help_stop),
        )),
        Section(R.string.voice_help_sec_page, listOf(
            Entry("다음 쪽", "next page", R.string.voice_help_next_page),
            Entry("이전 쪽", "previous page", R.string.voice_help_previous_page),
            Entry("5쪽", "page 5", R.string.voice_help_goto_page),
            Entry("마지막 페이지", "last page", R.string.voice_help_last_page),
        )),
        Section(R.string.voice_help_sec_metronome, listOf(
            Entry("템포 72", "tempo 72", R.string.voice_help_tempo),
            Entry("예비박 한 마디", "count-in one bar", R.string.voice_help_count_in),
            Entry("메트로놈", "metronome", R.string.voice_help_metronome),
        )),
        Section(R.string.voice_help_sec_parts, listOf(
            Entry("첼로 파트", "cello part", R.string.voice_help_part),
            Entry("바이올린 2 파트", "violin 2 part", R.string.voice_help_part_numbered),
            Entry("첼로 비올라 파트", "cello and viola parts", R.string.voice_help_parts),
            Entry("보표 2", "staff 2", R.string.voice_help_staff),
            Entry("총보", "full score", R.string.voice_help_full_score),
        )),
        Section(R.string.voice_help_sec_listen, listOf(
            Entry("듣기", "listen", R.string.voice_help_listen),
            Entry("57마디부터 듣기", "listen from measure 57", R.string.voice_help_listen_from),
            Entry("처음부터 듣기", "listen from the beginning", R.string.voice_help_listen_start),
            Entry("이어서 듣기", "resume listening", R.string.voice_help_listen_resume),
        )),
        Section(R.string.voice_help_sec_combined, listOf(
            Entry("템포 80 57마디부터", "tempo 80 from measure 57", R.string.voice_help_combo_tempo_measure),
            Entry("첼로 파트 레터 C", "cello part letter C", R.string.voice_help_combo_part_letter),
            Entry("예비박 한 마디 처음부터", "count-in one bar from the top", R.string.voice_help_combo_count_in_start),
            Entry("비올라 파트 3쪽", "viola part page 3", R.string.voice_help_combo_part_page),
        )),
    )

    /** 도움말을 부르는 말 — 안내 글에 넣는다("“도움말” 이라고 말하면 …") */
    fun word(language: VoiceLanguage): String = if (language == VoiceLanguage.ENGLISH) "help" else "도움말"

    /** 인식 힌트(Android 13+) */
    fun hints(language: VoiceLanguage): List<String> =
        if (language == VoiceLanguage.ENGLISH) listOf("help", "how to use") else listOf("도움말", "사용법")

    /** 도움말을 부르는 말이 든 발화인가 — 음성 언어와 관계없이 두 언어 다 받는다 */
    fun isRequest(text: String): Boolean {
        val squeezed = text.lowercase().replace(NOT_LETTER, "")
        if (KOREAN_WORDS.any { it in squeezed }) return true
        val words = text.lowercase().split(NOT_LETTER).filter { it.isNotEmpty() }
        return "help" in words || ENGLISH_PHRASES.any { it in squeezed }
    }

    private val NOT_LETTER = Regex("[^\\p{L}\\p{N}]+")
    private val KOREAN_WORDS = listOf("도움말", "사용법", "사용방법", "헬프")
    private val ENGLISH_PHRASES = listOf("howtouse", "whatcanisay")

    /** 맨 아래 "알아 둘 것" — 예시 문장은 음성 언어로. 사람 이름 보표는 한국어만(#094) */
    fun tips(language: VoiceLanguage): List<VoiceText> = listOfNotNull(
        VoiceText.of(R.string.voice_help_tip_hold),
        VoiceText.of(R.string.voice_help_tip_number),
        VoiceText.of(R.string.voice_help_tip_blocked, if (language == VoiceLanguage.ENGLISH) "not measure 57" else "57마디 말고"),
        VoiceText.of(R.string.voice_help_tip_person, "민준이 파트").takeIf { language == VoiceLanguage.KOREAN },
    )
}
