package com.mrgq.pdfviewer.voice

/**
 * 한국어로 읽은 수 — 한자어("오십칠", "백이십")와 고유어("쉰일곱", "스물두")를 값으로 (P12 §3.1).
 *
 * [prefixAt] 은 문자열의 한 자리에서 **가장 길게 읽히는 수**를 찾는다 — STT 가 띄어쓰기를 믿을 수 없어 정규화가 공백을 지우고
 * 읽으므로, "칠십이오십칠"은 72 와 57 로 끊겨야 한다(한자어는 자릿수가 내려가야만 이어진다: 7 · 십 · 2 다음 5 는 새 수).
 * 한 글자 수("이", "세", "열")는 낱말 조각과 헷갈리므로 받을지는 [CommandNormalizer] 가 앞뒤를 보고 정한다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object KoreanNumbers {

    /** 읽은 수 — 값, 차지한 글자 수 */
    data class Match(val value: Int, val length: Int)

    private val SINO_DIGITS = mapOf(
        '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4,
        '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )
    private val SINO_PLACES = mapOf('십' to 10, '백' to 100, '천' to 1000)

    /** 고유어 십 단위 — "스무"는 단위가 붙지 않을 때(스무 마디)만 쓰지만 굳이 가리지 않는다 */
    private val NATIVE_TENS = listOf(
        "열" to 10, "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40,
        "쉰" to 50, "예순" to 60, "일흔" to 70, "여든" to 80, "아흔" to 90,
    )

    /** 고유어 한 자리 — 셀 때 줄어드는 꼴(한 · 두 · 세 · 네, 석 · 넉 · 닷 · 엿)까지 */
    private val NATIVE_UNITS = listOf(
        "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "석" to 3,
        "넷" to 4, "네" to 4, "넉" to 4, "다섯" to 5, "닷" to 5, "여섯" to 6, "엿" to 6,
        "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )

    /** [text] 의 [start] 에서 가장 길게 읽히는 수. 한자어 · 고유어 중 긴 쪽(같으면 한자어 — "일"은 1). 없으면 null */
    fun prefixAt(text: String, start: Int): Match? {
        val sino = sinoAt(text, start)
        val native = nativeAt(text, start)
        return when {
            sino == null -> native
            native == null -> sino
            native.length > sino.length -> native
            else -> sino
        }
    }

    /** 문자열 전체가 수 하나일 때만 값 */
    fun parse(text: String): Int? = prefixAt(text, 0)?.takeIf { it.length == text.length }?.value

    private fun sinoAt(text: String, start: Int): Match? {
        var total = 0
        var pending: Int? = null
        var lastPlace = Int.MAX_VALUE
        var i = start
        var consumed = start
        while (i < text.length) {
            val c = text[i]
            val digit = SINO_DIGITS[c]
            val place = SINO_PLACES[c]
            when {
                digit != null -> {
                    // 한 자리 숫자 둘이 붙을 수 없고("오칠"), 영은 홀로만, 일의 자리 뒤에는 아무것도 오지 않는다
                    if (pending != null || lastPlace == 1) break
                    if (digit == 0 && i != start) break
                    pending = digit
                    i++
                    consumed = i
                    if (digit == 0) { lastPlace = 1; break }
                }
                place != null && place < lastPlace -> {
                    if (pending == 0) break
                    total += (pending ?: 1) * place
                    pending = null
                    lastPlace = place
                    i++
                    consumed = i
                }
                else -> break
            }
        }
        if (consumed == start) return null
        return Match(total + (pending ?: 0), consumed - start)
    }

    private fun nativeAt(text: String, start: Int): Match? {
        val tens = NATIVE_TENS.filter { text.startsWith(it.first, start) }.maxByOrNull { it.first.length }
        val afterTens = start + (tens?.first?.length ?: 0)
        val unit = NATIVE_UNITS.filter { text.startsWith(it.first, afterTens) }.maxByOrNull { it.first.length }
        if (tens == null && unit == null) return null
        val length = (tens?.first?.length ?: 0) + (unit?.first?.length ?: 0)
        return Match((tens?.second ?: 0) + (unit?.second ?: 0), length)
    }
}
