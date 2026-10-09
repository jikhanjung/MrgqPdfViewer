package com.mrgq.pdfviewer.voice

/**
 * 👂 계속 듣기의 호출어 찾기 (#085). 버튼 없이 들을 때는 "메이트, 57마디부터"처럼 **호출어 뒤의 말만** 명령으로 읽는다 —
 * 악보 이야기("57마디에서 첼로가 커요")가 명령으로 실행되지 않게.
 *
 * - STT 의 띄어쓰기를 믿지 않으므로 호출어 글자 사이의 공백을 허용한다("메 이트")
 * - 다른 낱말 속의 호출어는 아니다: 앞에 글자가 붙으면("룸메이트", "estimate") 건너뛴다
 * - 호출어가 여럿 들리면 **마지막** 호출어 뒤를 쓴다("메이트 아니 메이트 3쪽" = "3쪽")
 * - 호출어 바로 뒤의 부르는 말 "야"는 뗀다("메이트야 다음 쪽"). 뒤에 한글이 이어지면("야외") 떼지 않는다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object WakeWord {

    private val PATTERN: Regex = VoiceLexicon.WAKE_WORDS
        .joinToString("|") { word -> word.map { Regex.escape(it.toString()) }.joinToString("\\s*") }
        .let { Regex("(?<![가-힣A-Za-z])(?:$it)(?![A-Za-z])\\s*(?:야(?![가-힣]))?", RegexOption.IGNORE_CASE) }

    private const val LEADING_PUNCTUATION = ",.!?~…·:; "

    /** [text] 에 호출어가 있으면 마지막 호출어 뒤의 말(앞 문장부호 · 공백 뗌, 호출어만이면 ""), 없으면 null */
    fun commandAfter(text: String): String? {
        val match = PATTERN.findAll(text).lastOrNull() ?: return null
        return text.substring(match.range.last + 1).trimStart { it in LEADING_PUNCTUATION || it.isWhitespace() }.trimEnd()
    }
}
