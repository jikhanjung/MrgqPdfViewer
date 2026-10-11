package com.mrgq.pdfviewer.voice

/**
 * 영어 음성 명령 (P18 8단계, 계획 §7) — 한국어 파서와 **같은 명령 층**. 영어 낱말을 한국어 문법의 조각([Token])으로 읽어
 * 한국어 어순(수 → 단위)으로 놓은 뒤 [CommandParser.parse] 의 규칙(시작 · 듣기 · 둘이면 막기 · 실행 순서)을 그대로 쓴다.
 * 그래서 "start from measure 57" 과 "57마디부터 시작"은 같은 명령이 되고, 막는 규칙(부정 · 상대 위치 · 둘 · 모르는 수)도 같다.
 *
 * 범위는 좁다(§7): 마디 · 시작 · 정지 · 템포 · 쪽 · 파트(악기 이름 · full score · staff N) · 듣기 · 레터 · 예비박 · 다시 · 이어서.
 * 사람 이름 보표는 한국어만. 수는 숫자("57")와 영어 수 낱말("fifty seven", 0 ~ 999, "one twenty" = 120) 둘 다.
 *
 * 영어 어순 → 한국어 어순:
 * - `measure 57` · `bar 57` → `[57, 마디]`(고르기), `from measure 57` · `from 57` → `[57, 마디, 부터]`(그 마디부터)
 * - `page 3` → `[3, 쪽]`, `bpm 72` → `[72, bpm]`, `first page` · `2nd bar` → `[1, 쪽]` · `[2, 마디]`
 * - `two bars count-in` → `[예비박, 2, 마디]`
 * - 나머지(`tempo 72` · `count-in one bar` · `violin 2` · `staff 2` · `letter B`)는 어순이 같다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object EnglishCommandParser {

    /** 영어 문장 하나 → 명령. [staffCount] 는 한국어와 같다("staff 23" → 보표 2 · 3, #110) */
    fun parse(text: String, staffCount: Int? = null): ParseResult = CommandParser.parse(normalize(text), staffCount)

    /** 음성 인식 후보 여럿 → 쓸 후보와 결과 — 한국어와 같은 고르기([CommandParser.pickBest]) */
    fun parseBest(candidates: List<String>, staffCount: Int? = null): Pair<String, ParseResult>? =
        CommandParser.pickBest(candidates) { parse(it, staffCount) }

    /** 영어 문장 → 한국어 어순의 조각 */
    fun normalize(text: String): List<Token> = reorder(lex(words(text)))

    /** 인식 힌트(Android 13+) — 명령 낱말 */
    val BASE_HINTS: List<String> = listOf(
        "measure", "bar", "from measure", "start from measure", "tempo", "page", "next page", "previous page", "last page",
        "from the top", "again", "resume", "part", "staff", "full score", "letter", "start", "stop", "listen", "count-in",
    )

    /** 인식 힌트 — 악기 이름 */
    val INSTRUMENT_HINTS: List<String> = listOf(
        "violin", "viola", "cello", "double bass", "flute", "piccolo", "oboe", "clarinet", "bassoon", "horn", "trumpet",
        "trombone", "tuba", "timpani", "percussion", "piano", "harp", "guitar", "soprano", "alto", "tenor",
    )

    // ── 어휘 ──

    private fun w(kw: Kw) = Token.Word(kw)
    private fun inst(key: String) = Token.Instrument(key)

    /** 한 낱말 → 조각 */
    private val WORDS: Map<String, Token> = buildMap<String, Token> {
        listOf("measure", "measures", "bar", "bars").forEach { put(it, w(Kw.MEASURE)) }
        listOf("page", "pages").forEach { put(it, w(Kw.PAGE)) }
        listOf("tempo", "speed").forEach { put(it, w(Kw.TEMPO)) }
        put("bpm", w(Kw.BPM))
        put("from", w(Kw.FROM))
        put("beginning", w(Kw.START_OVER))
        listOf("again", "restart", "repeat").forEach { put(it, w(Kw.AGAIN)) }
        listOf("resume", "continue").forEach { put(it, w(Kw.RESUME)) }
        put("next", w(Kw.NEXT))
        listOf("previous", "prev").forEach { put(it, w(Kw.PREV)) }
        listOf("last", "final").forEach { put(it, w(Kw.LAST)) }
        // "two bars back" 같은 상대 위치 — 한국어 "전 · 뒤"처럼 받지 않는다(마디 번호로 잘못 읽히지 않게)
        listOf("back", "ago", "before", "after", "earlier", "later", "ahead", "forward", "forwards", "backward", "backwards", "behind")
            .forEach { put(it, w(Kw.RELATIVE)) }
        listOf("start", "starting", "play", "playing", "begin").forEach { put(it, w(Kw.START)) }
        listOf("stop", "pause", "halt", "off").forEach { put(it, w(Kw.STOP)) }
        listOf("metronome", "click").forEach { put(it, w(Kw.METRONOME)) }
        listOf("countin", "countoff").forEach { put(it, w(Kw.COUNT_IN)) }
        listOf("listen", "listening", "follow").forEach { put(it, w(Kw.LISTEN)) }
        listOf("part", "parts").forEach { put(it, w(Kw.PART)) }
        listOf("staff", "staves", "stave").forEach { put(it, w(Kw.STAFF)) }
        listOf("score", "tutti").forEach { put(it, w(Kw.FULL_SCORE)) }
        listOf("letter", "rehearsal").forEach { put(it, w(Kw.LETTER)) }
        listOf("not", "no", "dont", "cancel", "never", "nevermind", "instead", "except").forEach { put(it, w(Kw.NEGATION)) }
        listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4).forEach { (word, n) -> put(word, Token.Ordinal(n)) }
        mapOf(
            "violin" to "violin", "violins" to "violin", "fiddle" to "violin",
            "viola" to "viola", "violas" to "viola",
            "cello" to "cello", "cellos" to "cello", "chello" to "cello", "violoncello" to "cello",
            "bass" to "bass", "basses" to "bass", "base" to "bass", "contrabass" to "bass", "doublebass" to "bass",
            "flute" to "flute", "flutes" to "flute", "piccolo" to "piccolo",
            "oboe" to "oboe", "oboes" to "oboe", "clarinet" to "clarinet", "clarinets" to "clarinet",
            "bassoon" to "bassoon", "bassoons" to "bassoon",
            "horn" to "horn", "horns" to "horn", "trumpet" to "trumpet", "trumpets" to "trumpet",
            "trombone" to "trombone", "trombones" to "trombone", "tuba" to "tuba",
            "timpani" to "timpani", "timps" to "timpani", "percussion" to "percussion", "drums" to "percussion",
            "piano" to "piano", "keyboard" to "piano", "harp" to "harp", "guitar" to "guitar", "guitars" to "guitar",
            "soprano" to "soprano", "sopranos" to "soprano", "alto" to "alto", "altos" to "alto", "tenor" to "tenor", "tenors" to "tenor",
        ).forEach { (word, key) -> put(word, inst(key)) }
    }

    /** 여러 낱말 → 조각들 (빈 목록 = 뜻 없는 말). 긴 것부터 맞춘다 */
    private val PHRASES: List<Pair<List<String>, List<Token>>> = listOf(
        "count in" to listOf(w(Kw.COUNT_IN)),
        "count off" to listOf(w(Kw.COUNT_IN)),
        "from the very beginning" to listOf(w(Kw.START_OVER)),
        "from the top" to listOf(w(Kw.START_OVER)),
        "from the beginning" to listOf(w(Kw.START_OVER)),
        "from the start" to listOf(w(Kw.START_OVER)),
        "from top" to listOf(w(Kw.START_OVER)),
        "from start" to listOf(w(Kw.START_OVER)),
        "start over" to listOf(w(Kw.START_OVER)),
        "one more time" to listOf(w(Kw.AGAIN)),
        "once more" to listOf(w(Kw.AGAIN)),
        "keep going" to listOf(w(Kw.RESUME)),
        "carry on" to listOf(w(Kw.RESUME)),
        "turn the page" to listOf(w(Kw.TURN)),
        "turn page" to listOf(w(Kw.TURN)),
        "turn pages" to listOf(w(Kw.TURN)),
        "turn over" to listOf(w(Kw.TURN)),
        "back a page" to listOf(w(Kw.PREV), w(Kw.PAGE)),
        "back one page" to listOf(w(Kw.PREV), w(Kw.PAGE)),
        "one page back" to listOf(w(Kw.PREV), w(Kw.PAGE)),
        "page back" to listOf(w(Kw.PREV), w(Kw.PAGE)),
        "forward a page" to listOf(w(Kw.NEXT), w(Kw.PAGE)),
        "forward one page" to listOf(w(Kw.NEXT), w(Kw.PAGE)),
        // "go back to measure 57" = 57마디 — "back"(상대 위치)으로 막지 않는다
        "back to" to emptyList<Token>(),
        "listen and turn the pages" to listOf(w(Kw.LISTEN)),
        "listen and turn the page" to listOf(w(Kw.LISTEN)),
        "listen and turn pages" to listOf(w(Kw.LISTEN)),
        "listen and turn" to listOf(w(Kw.LISTEN)),
        "follow me" to listOf(w(Kw.LISTEN)),
        "follow along" to listOf(w(Kw.LISTEN)),
        "turn on" to listOf(w(Kw.START)),
        "switch on" to listOf(w(Kw.START)),
        "turn off" to listOf(w(Kw.STOP)),
        "switch off" to listOf(w(Kw.STOP)),
        "beats per minute" to listOf(w(Kw.BPM)),
        "full score" to listOf(w(Kw.FULL_SCORE)),
        "whole score" to listOf(w(Kw.FULL_SCORE)),
        "all the parts" to listOf(w(Kw.FULL_SCORE)),
        "all parts" to listOf(w(Kw.FULL_SCORE)),
        "all staves" to listOf(w(Kw.FULL_SCORE)),
        "rehearsal mark" to listOf(w(Kw.LETTER)),
        "rehearsal letter" to listOf(w(Kw.LETTER)),
        "rehearsal number" to listOf(w(Kw.LETTER)),
        "double bass" to listOf(inst("bass")),
        "string bass" to listOf(inst("bass")),
        "upright bass" to listOf(inst("bass")),
        "contra bass" to listOf(inst("bass")),
        "bass clarinet" to listOf(inst("clarinet")),
        "french horn" to listOf(inst("horn")),
        "never mind" to listOf(w(Kw.NEGATION)),
    ).map { (phrase, tokens) -> phrase.split(" ") to tokens }.sortedByDescending { it.first.size }

    /** 뜻 없이 붙는 말 — 버린다("set the tempo to 72" = "tempo 72"). 여기 없는 말은 남아 앞뒤를 잇지 않는다(한국어 조사와 같다) */
    private val FILLERS: Set<String> = setOf(
        "the", "a", "an", "to", "at", "on", "in", "of", "for", "please", "go", "lets", "let", "us", "me", "my", "can", "could",
        "would", "will", "you", "we", "i", "id", "im", "set", "change", "switch", "show", "jump", "move", "now", "ok", "okay",
        "hey", "and", "then", "number", "just", "it", "take", "with", "is", "be", "want", "wanna", "like", "gonna", "view",
        "only", "up", "here", "there", "this", "that", "very", "do", "make", "try", "oh", "um", "uh",
    )

    /** 레터 뒤의 알파벳 읽기 — 인식기가 "letter bee" · "letter see" 로 적기도 한다 */
    private val LETTER_NAMES: Map<String, Char> = mapOf(
        "ay" to 'A', "bee" to 'B', "be" to 'B', "see" to 'C', "sea" to 'C', "dee" to 'D', "ee" to 'E', "ef" to 'F', "eff" to 'F',
        "gee" to 'G', "aitch" to 'H', "eye" to 'I', "jay" to 'J', "kay" to 'K', "el" to 'L', "ell" to 'L', "em" to 'M', "en" to 'N',
        "oh" to 'O', "pee" to 'P', "pea" to 'P', "cue" to 'Q', "queue" to 'Q', "are" to 'R', "ar" to 'R', "ess" to 'S', "tee" to 'T',
        "tea" to 'T', "you" to 'U', "vee" to 'V', "ex" to 'X', "why" to 'Y', "zee" to 'Z', "zed" to 'Z',
    )

    /** 악기 바로 뒤의 로마 숫자 — "violin ii" */
    private val ROMAN: Map<String, Int> = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4)

    private val ONES: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS: Map<String, Int> = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private const val MAX_DIGITS = 4
    private val DIGITS = Regex("\\d+")
    private val DIGITS_ORDINAL = Regex("(\\d+)(st|nd|rd|th)")

    // ── 읽기 ──

    /** 소문자 · 따옴표 지우기("let's" = "lets") · 문장부호와 하이픈은 공백("count-in", "fifty-seven") · 글자 뒤에 붙은 숫자 떼기("bar57") */
    internal fun words(text: String): List<String> = text.lowercase()
        .replace(Regex("['’`]"), "")
        .replace(Regex("(?<=[a-z])(?=\\d)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotEmpty() }

    private fun lex(words: List<String>): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val prev = out.lastOrNull()
            // "letter B" · "rehearsal bee" — 레터 바로 뒤 한 글자 · 알파벳 이름은 알파벳(조각으로 남겨 한국어 파서가 읽는다)
            val letter = if (prev == w(Kw.LETTER)) letterOf(word) else null
            if (letter != null) {
                out += Token.Other(letter.lowercaseChar().toString())
                i++
                continue
            }
            // "violin ii"
            val roman = if (prev is Token.Instrument) ROMAN[word] else null
            if (roman != null) {
                out += Token.Num(roman)
                i++
                continue
            }
            val phrase = PHRASES.firstOrNull { (phraseWords, _) -> matchesAt(words, i, phraseWords) }
            if (phrase != null) {
                out += phrase.second
                i += phrase.first.size
                continue
            }
            val number = readNumber(words, i)
            if (number != null) {
                out += Token.Num(number.first)
                i += number.second
                continue
            }
            // 뜻 없는 말(FILLERS)은 넣지 않는다
            val token = digitsToken(word) ?: WORDS[word] ?: if (word in FILLERS) null else Token.Other(word)
            if (token != null) out += token
            i++
        }
        return out
    }

    private fun matchesAt(words: List<String>, i: Int, phrase: List<String>): Boolean =
        i + phrase.size <= words.size && phrase.indices.all { words[i + it] == phrase[it] }

    private fun letterOf(word: String): Char? =
        if (word.length == 1 && word[0] in 'a'..'z') word[0].uppercaseChar() else LETTER_NAMES[word]

    /** "57" → 57, "1st" → 첫째(서수), "57th" → 57. 다섯 자리 이상은 수가 아니다 */
    private fun digitsToken(word: String): Token? {
        if (DIGITS.matches(word)) return if (word.length <= MAX_DIGITS) Token.Num(word.toInt()) else Token.Other(word)
        val ordinal = DIGITS_ORDINAL.matchEntire(word) ?: return null
        val digits = ordinal.groupValues[1]
        if (digits.length > MAX_DIGITS) return Token.Other(word)
        val n = digits.toInt()
        return if (n in 1..4) Token.Ordinal(n) else Token.Num(n)
    }

    /** 0 ~ 99 수 낱말 — (값, 쓴 낱말 수) */
    private fun below100(words: List<String>, at: Int): Pair<Int, Int>? {
        val word = words.getOrNull(at) ?: return null
        TEENS[word]?.let { return it to 1 }
        val tens = TENS[word]
        if (tens != null) {
            val ones = words.getOrNull(at + 1)?.let { ONES[it] }
            return if (ones != null && ones > 0) (tens + ones) to 2 else tens to 1
        }
        return ONES[word]?.let { it to 1 }
    }

    /**
     * 영어 수 낱말 → (값, 쓴 낱말 수). "fifty seven" = 57, "one hundred and five" = 105, "hundred" = 100,
     * "one twenty" = 120(템포를 이렇게 읽는다 — 한 자리 수 뒤에 10 ~ 99)
     */
    internal fun readNumber(words: List<String>, i: Int): Pair<Int, Int>? {
        val lead = below100(words, i)
        var k = i
        var hundreds = 0
        if (words.getOrNull(i) == "hundred") {
            hundreds = 1
            k = i + 1
        } else if (lead != null && lead.first in 1..9 && words.getOrNull(i + lead.second) == "hundred") {
            hundreds = lead.first
            k = i + lead.second + 1
        }
        if (hundreds > 0) {
            if (words.getOrNull(k) == "and" && below100(words, k + 1) != null) k++
            val rest = below100(words, k) ?: return hundreds * 100 to (k - i)
            return (hundreds * 100 + rest.first) to (k + rest.second - i)
        }
        if (lead == null) return null
        if (lead.second == 1 && lead.first in 1..9 && words[i] in ONES) {
            val next = words.getOrNull(i + 1)
            val rest = below100(words, i + 1)
            if (rest != null && next != null && (next in TEENS || next in TENS)) return (lead.first * 100 + rest.first) to (1 + rest.second)
        }
        return lead
    }

    // ── 어순 ──

    private val UNITS_FIRST = setOf(Kw.MEASURE, Kw.PAGE, Kw.BPM)

    /** 영어 어순(단위 → 수) → 한국어 어순(수 → 단위) */
    private fun reorder(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val after = tokens.getOrNull(i + 2)
            when {
                // "two bars count-in" → 예비박 2 마디
                t is Token.Num && next == w(Kw.MEASURE) && after == w(Kw.COUNT_IN) -> {
                    out += listOf(w(Kw.COUNT_IN), t, w(Kw.MEASURE))
                    i += 3
                }
                // "first page", "2nd bar"
                t is Token.Ordinal && (next == w(Kw.MEASURE) || next == w(Kw.PAGE)) -> {
                    out += listOf(Token.Num(t.n), next as Token)
                    i += 2
                }
                // "measure 57", "page 3", "bpm 72"
                t is Token.Word && t.kw in UNITS_FIRST && next is Token.Num -> {
                    out += listOf(next, t)
                    i += 2
                }
                t == w(Kw.FROM) -> {
                    when {
                        // "from 57"
                        next is Token.Num -> { out += listOf(next, w(Kw.FROM)); i += 2 }
                        // "from measure 57"
                        next == w(Kw.MEASURE) && after is Token.Num -> { out += listOf(after, w(Kw.MEASURE), w(Kw.FROM)); i += 3 }
                        // "from the first bar"
                        next is Token.Ordinal && after == w(Kw.MEASURE) -> {
                            out += listOf(Token.Num(next.n), w(Kw.MEASURE), w(Kw.FROM))
                            i += 3
                        }
                        // "from page 3" · "from letter B" · "from here" — 뒤의 말이 스스로 위치다
                        else -> i++
                    }
                }
                else -> {
                    out += t
                    i++
                }
            }
        }
        return out
    }
}
