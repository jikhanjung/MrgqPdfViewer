package com.mrgq.pdfviewer.voice

/**
 * 말한 파트([PartRef]) → 악보의 보표 순번. 보표 이름은 PDF 에서 읽었거나 MusicXML 에서 온 것("Violin I", "Vln. 2", "Violoncello",
 * "Vc.", "바이올린 1") — 이름에서 **마지막 악기 낱말**(악기 키)과 번호(1 · I · 1st)를 찾는다. "Bass Clarinet" 은 클라리넷이다.
 *
 * - 번호 없이 말했는데 같은 악기가 번호로 여럿이면(Violin I · II) [Result.Ambiguous] — 둘 다 고르지 않고 묻는다
 * - 이름 없는 보표(null)가 맞은 보표 바로 아래에 이어지면 함께 — 피아노 · 하프의 아래 보표
 * - 보표 이름으로 부른 파트([PartRef.staffName], 사람 이름 — #094)는 그 이름의 보표
 * - 보표 번호로 부른 파트([PartRef.staffNumber], "보표 2" — #098)는 위에서 그 번째 보표 하나만(아래 이름 없는 보표를 붙이지 않는다)
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object PartMatcher {

    sealed class Result {
        data class Staves(val staves: Set<Int>) : Result()
        data class Missing(val part: PartRef) : Result()
        data class Ambiguous(val part: PartRef, val names: List<String>) : Result()
    }

    /** 보표 이름에 흔한 약어 · 복수 · 이탈리아어 (말하는 쪽 별칭은 [VoiceLexicon.INSTRUMENTS]) */
    private val NAME_ALIASES: Map<String, String> = mapOf(
        "violins" to "violin", "violino" to "violin", "violini" to "violin", "vl" to "violin", "vni" to "violin",
        "violas" to "viola", "va" to "viola", "viole" to "viola",
        "violoncello" to "cello", "violoncelli" to "cello", "cellos" to "cello", "vcl" to "cello", "vlc" to "cello",
        "contrabass" to "bass", "contrabasses" to "bass", "kontrabass" to "bass", "basses" to "bass", "bass" to "bass",
        "cb" to "bass", "db" to "bass",
        "flutes" to "flute", "flute" to "flute", "fl" to "flute", "picc" to "piccolo", "piccolo" to "piccolo",
        "oboe" to "oboe", "oboes" to "oboe", "ob" to "oboe",
        "clarinet" to "clarinet", "clarinets" to "clarinet", "cl" to "clarinet", "clar" to "clarinet",
        "bassoon" to "bassoon", "bassoons" to "bassoon", "bsn" to "bassoon", "fg" to "bassoon", "fagott" to "bassoon",
        "horn" to "horn", "horns" to "horn", "hn" to "horn", "hrn" to "horn",
        "trumpet" to "trumpet", "trumpets" to "trumpet", "tpt" to "trumpet", "trp" to "trumpet",
        "trombone" to "trombone", "trombones" to "trombone", "tbn" to "trombone", "trb" to "trombone",
        "tuba" to "tuba", "tba" to "tuba",
        "timpani" to "timpani", "timp" to "timpani", "pauken" to "timpani",
        "percussion" to "percussion", "perc" to "percussion",
        "piano" to "piano", "pianoforte" to "piano", "pno" to "piano", "pf" to "piano",
        "harp" to "harp", "hp" to "harp", "guitar" to "guitar", "gtr" to "guitar",
        "soprano" to "soprano", "sop" to "soprano", "alto" to "alto", "tenor" to "tenor", "ten" to "tenor",
    )

    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4)
    private val ORDINAL_ENDINGS = setOf("st", "nd", "rd", "th")
    private val ORDINAL_SUFFIX = Regex("^([1-4])(st|nd|rd|th)$")

    /** 보표 이름 → (악기 키, 번호). 악기를 모르면 null */
    fun identify(name: String): Pair<String, Int?>? {
        val raw = name.lowercase()
            .replace(Regex("[.,:;()\\[\\]_/-]"), " ")
            .replace(Regex("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})"), " ") // "violin1" → "violin 1"
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val words = mutableListOf<String>()
        for (w in raw) {
            // 위에서 갈라진 "1st" 를 다시 붙인다
            if (w in ORDINAL_ENDINGS && words.lastOrNull()?.all(Char::isDigit) == true) words[words.lastIndex] += w else words += w
        }
        val key = words.lastOrNull { instrumentOf(it) != null }?.let(::instrumentOf) ?: return null
        return key to words.firstNotNullOfOrNull(::numberOf)
    }

    private fun instrumentOf(word: String): String? = NAME_ALIASES[word] ?: VoiceLexicon.INSTRUMENTS[word]

    private fun numberOf(word: String): Int? =
        word.toIntOrNull()?.takeIf { it in 1..4 } ?: ROMAN[word] ?: ORDINAL_SUFFIX.find(word)?.groupValues?.get(1)?.toInt()

    /** [staves] = 위부터 (보표 순번, 이름 — 모르면 null) */
    fun match(staves: List<Pair<Int, String?>>, parts: List<PartRef>): Result {
        val known = staves.map { (index, name) -> Triple(index, name, name?.let(::identify)) }
        val chosen = mutableSetOf<Int>()
        for (part in parts) {
            val hits = known.filter { (index, name, id) ->
                when {
                    part.staffNumber != null -> index == part.staffNumber - 1
                    part.staffName != null -> name == part.staffName
                    else -> id != null && id.first == part.instrument && (part.number == null || id.second == part.number)
                }
            }
            if (hits.isEmpty()) return Result.Missing(part)
            if (part.staffNumber != null) {
                chosen += hits.map { it.first }
                continue
            }
            if (part.staffName == null && part.number == null && hits.mapNotNull { it.third?.second }.distinct().size > 1) {
                return Result.Ambiguous(part, hits.mapNotNull { it.second })
            }
            for (hit in hits) {
                chosen += hit.first
                // 이름 없는 아래 보표들 (피아노 왼손)
                var k = known.indexOf(hit) + 1
                while (k < known.size && known[k].second == null) chosen += known[k++].first
            }
        }
        return Result.Staves(chosen)
    }
}
