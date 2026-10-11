package com.mrgq.pdfviewer.voice

/**
 * 사람 이름으로 된 보표 부르기 (#094). 합주 악보는 보표 이름이 악기가 아니라 단원 이름("김지한")인 경우가 있다 —
 * 그 이름을 말하면 그 보표의 파트 보기("지한이 파트", "김지한 57마디부터").
 *
 * - 악기로 읽히는 이름([PartMatcher.identify])은 빼고, **한글만 남겨** 두 글자 이상인 것만 쓴다
 * - 부를 말 = 온이름 + 성을 뺀 이름(세 · 네 글자면 끝 두 글자, "김지한" → "지한"). 성을 뺀 이름이 다른 보표와 겹치거나
 *   명령 낱말과 같으면 온이름만
 * - 이름 바로 뒤의 부르는 말 · 호칭은 이름에 붙인다: "지한이" · "지한아" · "지수야" · "지한 씨" · "지한님" — 남으면
 *   "이"가 수(2)로 읽힐 수 있다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object PartNames {

    private const val GIVEN_NAME_LENGTH = 2

    /** 보표 이름들 → (부를 말, 보표 이름), 긴 말부터 */
    fun aliases(staffNames: List<String>): List<Pair<String, String>> {
        val people = staffNames.distinct().mapNotNull { name ->
            val hangul = name.filter { it in '가'..'힣' }
            name.takeIf { hangul.length >= 2 && PartMatcher.identify(name) == null }?.let { hangul to it }
        }
        val fullNames = people.map { it.first }.toSet()
        val given = people.mapNotNull { (hangul, name) ->
            hangul.takeIf { it.length in 3..4 }?.takeLast(GIVEN_NAME_LENGTH)?.let { it to name }
        }
        val givenCounts = given.groupingBy { it.first }.eachCount()
        val keywords = VoiceLexicon.KEYWORDS_BY_LENGTH.map { it.first }.toSet()
        val usableGiven = given.filter { (alias, _) -> givenCounts[alias] == 1 && alias !in fullNames && alias !in keywords }
        return (people + usableGiven).sortedByDescending { it.first.length }
    }

    /**
     * [text] 의 [at] 에서 부를 말 하나와 **모음 한 글자만 다른** 말 — (그 자리의 말, 보표 이름). STT 가 이름의 모음을 바꿔 적는다
     * ("예원" ↔ 보표 "예완", 실녹음 #109). 첫소리 · 받침은 같고 한 음절의 가운데소리만 다를 때, 그런 보표가 하나뿐일 때만
     * (둘 이상이면 어느 쪽인지 모르므로 부르지 않는다). 두 글자 이상 이름만
     */
    fun nearAt(text: String, at: Int, aliases: List<Pair<String, String>>): Pair<String, String>? {
        val hits = aliases.filter { (alias, _) ->
            alias.length >= 2 && at + alias.length <= text.length && oneVowelApart(text.substring(at, at + alias.length), alias)
        }
        return hits.distinctBy { it.second }.singleOrNull()?.let { (alias, staff) -> text.substring(at, at + alias.length) to staff }
    }

    private fun oneVowelApart(a: String, b: String): Boolean {
        var diff = 0
        for (k in a.indices) {
            val x = a[k]
            val y = b[k]
            if (x == y) continue
            if (x !in '가'..'힣' || y !in '가'..'힣') return false
            val dx = x - '가'
            val dy = y - '가'
            // 음절 = (첫소리 × 21 + 가운데소리) × 28 + 받침
            if (dx / (21 * 28) != dy / (21 * 28) || dx % 28 != dy % 28) return false
            diff++
        }
        return diff == 1
    }

    /** 음성 인식 낱말 힌트 — 부를 말 모두 */
    fun hints(staffNames: List<String>): List<String> = aliases(staffNames).map { it.first }

    /** [text] 의 [at] 에서 이름 [alias] 가 끝난 뒤 붙은 부르는 말 · 호칭의 길이 (없으면 0) */
    fun suffixLength(text: String, at: Int, alias: String): Int {
        val rest = text.substring(at)
        if (rest.startsWith("씨") || rest.startsWith("님")) return 1
        val last = alias.last()
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return when {
            hasFinal && (rest.startsWith("이") || rest.startsWith("아")) -> 1
            !hasFinal && rest.startsWith("야") -> 1
            else -> 0
        }
    }
}
