package com.mrgq.pdfviewer.voice

/**
 * 👂 계속 듣기의 호출어 찾기 (#085). 버튼 없이 들을 때는 "메이트, 57마디부터"처럼 **호출어 뒤의 말만** 명령으로 읽는다 —
 * 악보 이야기("57마디에서 첼로가 커요")가 명령으로 실행되지 않게.
 *
 * - 호출어는 설정에서 바꿀 수 있다(#091) — 쉼표로 여럿, 비우면 기본 [VoiceLexicon.WAKE_WORDS](메이트 · 매이트 · mate)
 * - STT 의 띄어쓰기를 믿지 않으므로 호출어 글자 사이의 공백을 허용한다("메 이트")
 * - 다른 낱말 속의 호출어는 아니다: 앞에 글자가 붙으면("룸메이트", "estimate") 건너뛴다
 * - 호출어가 여럿 들리면 **마지막** 호출어 뒤를 쓴다("메이트 아니 메이트 3쪽" = "3쪽")
 * - 호출어 바로 뒤의 부르는 말 "야"는 뗀다("메이트야 다음 쪽"). 뒤에 한글이 이어지면("야외") 떼지 않는다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object WakeWord {

    private const val LEADING_PUNCTUATION = ",.!?~…·:; "
    private val SEPARATORS = Regex("[,，·/]")

    /** 마지막으로 만든 패턴 — 호출어는 거의 바뀌지 않으므로 하나만 둔다 */
    private var cached: Pair<List<String>, Regex>? = null

    /** 설정 글자("마에스트로, maestro") → 호출어 목록. 비었으면 기본 */
    fun parseSetting(text: String?): List<String> =
        text.orEmpty().split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { VoiceLexicon.WAKE_WORDS }

    /** 목록 → 설정 글자 */
    fun toSetting(words: List<String>): String = words.joinToString(", ")

    /** 호출어로 쓸 수 없는 까닭 — 쓸 수 있으면 null. 너무 짧거나 그 자체가 명령으로 읽히면 안 된다 */
    fun problem(word: String): String? = when {
        word.filterNot { it.isWhitespace() }.length < 2 -> "\"$word\" — 두 글자 이상으로 정하세요"
        CommandParser.parse(word) !is ParseResult.Unrecognized -> "\"$word\" — 명령으로 읽히는 말이에요. 다른 말로 정하세요"
        else -> null
    }

    /** [text] 에 호출어([words])가 있으면 마지막 호출어 뒤의 말(앞 문장부호 · 공백 뗌, 호출어만이면 ""), 없으면 null */
    fun commandAfter(text: String, words: List<String> = VoiceLexicon.WAKE_WORDS): String? {
        val match = patternFor(words).findAll(text).lastOrNull() ?: return null
        return text.substring(match.range.last + 1).trimStart { it in LEADING_PUNCTUATION || it.isWhitespace() }.trimEnd()
    }

    private fun patternFor(words: List<String>): Regex {
        cached?.takeIf { it.first == words }?.let { return it.second }
        val alternatives = words.joinToString("|") { word ->
            word.filterNot { it.isWhitespace() }.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        }
        val pattern = Regex("(?<![가-힣A-Za-z])(?:$alternatives)(?![A-Za-z])\\s*(?:야(?![가-힣]))?", RegexOption.IGNORE_CASE)
        cached = words to pattern
        return pattern
    }
}
