package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.VoiceText
import java.io.File
import java.util.Locale

/**
 * 단위 테스트에서 [VoiceText] 를 화면 글로 (P18) — `values/strings_voice.xml`(한국어) · `values-en/strings_voice.xml`(영어)을 직접 읽어
 * Android 와 같은 자리표(`%1$s` · `%1$d`)로 채운다. 한국어 화면 글이 옮기기 전과 바이트 단위로 같은지 지키는 데 쓴다.
 */
object VoiceTestStrings {

    private fun file(dir: String): File =
        listOf(File("src/main/res/$dir/strings_voice.xml"), File("app/src/main/res/$dir/strings_voice.xml")).firstOrNull { it.exists() }
            ?: error("strings_voice.xml 을 찾지 못함 ($dir, 작업 폴더 ${File(".").absolutePath})")

    private val STRING = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun load(dir: String): Map<String, String> =
        STRING.findAll(file(dir).readText()).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private fun unescape(s: String): String =
        s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("&lt;", "<").replace("&amp;", "&")

    /** R.string 의 정수 → 이름 */
    private val names: Map<Int, String> by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }

    val korean: Map<String, String> by lazy { load("values") }
    val english: Map<String, String> by lazy { load("values-en") }

    fun name(id: Int): String = names[id] ?: error("R.string 에 없는 키 $id")

    fun render(text: VoiceText, strings: Map<String, String>): String = text.resolve { id, args ->
        val pattern = strings[name(id)] ?: error("strings_voice.xml 에 없는 키 ${name(id)}")
        if (args.isEmpty()) pattern else String.format(Locale.ROOT, pattern, *args)
    }

    fun ko(text: VoiceText): String = render(text, korean)
    fun en(text: VoiceText): String = render(text, english)
}
