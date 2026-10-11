package com.mrgq.pdfviewer.voice

/** 정규화한 명령 조각 */
sealed class Token {
    data class Num(val value: Int) : Token()
    data class Word(val kw: Kw) : Token()
    data class Instrument(val key: String) : Token()
    data class Ordinal(val n: Int) : Token()
    /** 사람 이름으로 부른 보표 — 값은 악보의 보표 이름 그대로 (#094, [PartNames]) */
    data class Person(val staffName: String) : Token()
    /** 어휘에 없는 조각 — 조사 · 말끝이거나 모르는 말 */
    data class Other(val text: String) : Token()
}

/**
 * STT 결과 → 명령 조각 목록 (P12 §3.1). "오십칠 마디부터, 템포 칠십이" → `[57, 마디, 부터, 템포, 72]`.
 *
 * - **공백을 지우고 읽는다**: STT 의 띄어쓰기는 믿을 수 없다("오십 칠 마디", "다음쪽으로"). 단 아라비아 숫자 사이의 공백은
 *   경계로 남긴다("72 57" ≠ 7257)
 * - 한 자리에서 **어휘(긴 것부터) → 아라비아 숫자 → 한국어 수** 순으로 찾는다 — "이전"이 "이"보다, "세컨"이 "세"보다 먼저
 * - **한 글자 한국어 수**("오", "세", "열", "백")는 낱말 조각과 헷갈리므로 앞이 템포 · 악기이거나 바로 뒤가 단위(마디 · 쪽 ·
 *   번째 · bpm)일 때만 수로 읽는다. "이"는 "이 마디"(= 지금 마디)와 헷갈려 악기 뒤("바이올린 이")에서만, 레터 뒤에서는
 *   한 글자를 알파벳으로 남긴다("레터 이" = E). "한번"의 "번"은 단위로 보지 않는다
 * - **끝 음절이 잘린 단위**([VoiceLexicon.CLIPPED_UNITS]): 수 바로 뒤 말의 맨 끝 "마"는 마디("50 마" = 50마디)
 * - **악보의 사람 이름**([PartNames], #094): 이 악보의 보표 이름을 받으면 어휘보다 먼저 찾는다 — "이수진"이 수 2 로 쪼개지지 않게
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object CommandNormalizer {

    private const val BOUNDARY = '|'
    private val PUNCTUATION = Regex("[.,!?~…·'\"()\\[\\]]")
    private val SINGLE_SYLLABLE_UNITS = setOf(Kw.MEASURE, Kw.PAGE, Kw.BPM)
    private val SINGLE_SYLLABLE_COUNTERS = setOf("번째", "째")
    private const val MAX_DIGITS = 4

    fun normalize(text: String, staffNames: List<String> = emptyList()): List<Token> {
        val compact = compact(text)
        val names = PartNames.aliases(staffNames)
        val out = mutableListOf<Token>()
        val other = StringBuilder()
        fun flush() {
            if (other.isNotEmpty()) out += Token.Other(other.toString())
            other.clear()
        }
        var i = 0
        while (i < compact.length) {
            val c = compact[i]
            if (c == BOUNDARY) { flush(); i++; continue }
            val name = names.firstOrNull { compact.startsWith(it.first, i) }
                // 모음 하나만 다르게 받아 적은 이름("예원" ↔ 보표 "예완") — 그렇게 가까운 이름이 하나뿐일 때만 (#109)
                // 명령 낱말이 시작하는 자리는 빼고 — "마디"가 보표 "마다"로 읽히지 않게
                ?: if (other.isEmpty() && keywordAt(compact, i) == null) PartNames.nearAt(compact, i, names) else null
            if (name != null) {
                flush()
                out += Token.Person(name.second)
                i += name.first.length
                i += PartNames.suffixLength(compact, i, name.first)
                continue
            }
            val word = keywordAt(compact, i)
            if (word != null) {
                flush()
                out += word.second
                i += word.first.length
                continue
            }
            if (c in '0'..'9') {
                var j = i
                while (j < compact.length && compact[j] in '0'..'9') j++
                val digits = compact.substring(i, j)
                if (digits.length <= MAX_DIGITS) { flush(); out += Token.Num(digits.toInt()) } else other.append(digits)
                i = j
                continue
            }
            // "기타 원" · "스테프원" — 악기 · 보표 바로 뒤의 영어 수 (#109)
            val last = out.lastOrNull()
            if (other.isEmpty() && (last is Token.Instrument || last == Token.Word(Kw.STAFF))) {
                val english = VoiceLexicon.ENGLISH_NUMBERS.entries.firstOrNull { compact.startsWith(it.key, i) }
                if (english != null) {
                    flush()
                    out += Token.Num(english.value)
                    i += english.key.length
                    continue
                }
            }
            val number = KoreanNumbers.prefixAt(compact, i)
            // "보표 일 이" — 보표 뒤에 이어지는 수는 보표 번호 목록
            val afterStaff = out.asReversed().dropWhile { it is Token.Num }.firstOrNull() == Token.Word(Kw.STAFF)
            if (number != null && (afterStaff && other.isEmpty() || acceptNumber(compact, i, number, out.lastOrNull(), other.isNotEmpty()))) {
                flush()
                out += Token.Num(number.value)
                i += number.length
                continue
            }
            other.append(c)
            i++
        }
        flush()
        return restoreClippedUnit(out)
    }

    /** "50 마" → `[50, 마디]` — 손을 일찍 떼 잘린 끝 음절. 수 바로 뒤, 맨 끝 조각일 때만 */
    private fun restoreClippedUnit(tokens: MutableList<Token>): List<Token> {
        val last = tokens.lastOrNull() as? Token.Other ?: return tokens
        if (tokens.getOrNull(tokens.lastIndex - 1) !is Token.Num) return tokens
        val unit = VoiceLexicon.CLIPPED_UNITS[last.text] ?: return tokens
        tokens[tokens.lastIndex] = Token.Word(unit)
        return tokens
    }

    /** 소문자 · 흔한 오인식 고침 · 문장부호 → 공백, 그리고 공백 지우기(아라비아 숫자 사이만 경계로) */
    internal fun compact(text: String): String {
        var s = text.lowercase()
        VoiceLexicon.TYPOS.forEach { (wrong, right) -> s = s.replace(wrong, right) }
        s = PUNCTUATION.replace(s, " ")
        val sb = StringBuilder()
        var pendingSpace = false
        for (c in s) {
            if (c.isWhitespace()) { pendingSpace = true; continue }
            if (pendingSpace && sb.isNotEmpty() && sb.last() in '0'..'9' && c in '0'..'9') sb.append(BOUNDARY)
            pendingSpace = false
            sb.append(c)
        }
        return sb.toString()
    }

    private fun keywordAt(text: String, i: Int): Pair<String, Token>? =
        VoiceLexicon.KEYWORDS_BY_LENGTH.firstOrNull { text.startsWith(it.first, i) }

    private fun acceptNumber(text: String, i: Int, number: KoreanNumbers.Match, prev: Token?, inWord: Boolean): Boolean {
        if (number.length >= 2) return true
        if (inWord) return false
        if (prev == Token.Word(Kw.LETTER)) return false
        if (prev is Token.Instrument) return true
        if (prev == Token.Word(Kw.COUNT_IN)) return true // "예비박 둘", "예비박 이 마디"
        if (text[i] == '이') return false
        if (prev == Token.Word(Kw.TEMPO)) return true
        val next = keywordAt(text, i + number.length) ?: return false
        val token = next.second
        return (token is Token.Word && token.kw in SINGLE_SYLLABLE_UNITS) || next.first in SINGLE_SYLLABLE_COUNTERS
    }
}
