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
