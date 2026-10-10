package com.mrgq.pdfviewer.voice

/** 명령 낱말의 종류 (P12 §3.2). 같은 뜻의 여러 말이 한 종류로 모인다. */
enum class Kw {
    MEASURE,      // 마디 · 소절
    PAGE,         // 쪽 · 페이지 · 장
    COUNTER,      // 번 · 번째 · 째 — "57번 마디", "세 번째 마디"
    TEMPO,        // 템포 · 빠르기 · 속도
    BPM,          // bpm — "72 bpm"
    FROM,         // 부터 — "57부터"
    START_OVER,   // 처음 · 맨앞
    AGAIN,        // 다시
    RESUME,       // 이어서 · 계속
    NEXT,         // 다음
    PREV,         // 이전 · 앞 — 쪽 앞에서만("이전 쪽")
    RELATIVE,     // 전 · 뒤 · 후 — "두 마디 전" 같은 상대 위치는 1차에 받지 않는다(마디 번호로 잘못 읽히지 않게)
    TURN,         // 넘겨 — "다음 쪽"과 같다
    START,        // 시작
    METRONOME,    // 메트로놈 — 홀로면 시작, "메트로놈 정지"면 정지를 따른다
    COUNT_IN,     // 예비박 — "예비박 한 마디", "두 마디 예비박"
    STOP,         // 멈춰 · 정지 · 그만 — 👂 계속 듣기에서 연주를 멈추는 말 (#085)
    LISTEN,       // 듣기 · 듣고 넘기기 — 🎤 연주 듣고 넘기기 시작 (#087)
    PART,         // 파트 — 악기 이름 뒤에 붙어도 되고 안 붙어도 된다
    STAFF,        // 보표 — "보표 2" = 위에서 둘째 보표(이름을 못 읽은 보표는 파트 보기 목록에 "보표 n", #098)
    FULL_SCORE,   // 총보
    LETTER,       // 레터 · 리허설 — 리허설 마크
    NEGATION,     // 말고 · 아니 · 취소 — 들리면 아무것도 실행하지 않는다
    BLOCK,        // 악장 · 장조 — "장"(쪽)으로 잘못 읽히지 않게 막는 낱말
}

/**
 * 음성 명령 어휘 (P12 §3.1). 정규화([CommandNormalizer])가 공백을 지운 문자열에서 **가장 긴 낱말부터** 찾는다 —
 * "비올라"가 "비"(레터 B)보다, "이전"이 "이"(2)보다 먼저.
 *
 * 실녹음 평가(P12 §8)에서 STT 가 자주 틀리는 말은 [TYPOS] 에, 새 별칭은 각 표에 더한다.
 */
object VoiceLexicon {

    /** STT 가 자주 틀리게 적는 말 → 바른 말. 공백을 지우기 전, 소문자로 바꾼 뒤에 바꾼다 */
    val TYPOS: List<Pair<String, String>> = listOf(
        "탬포" to "템포",
        "뗌포" to "템포",
        "패이지" to "페이지",
        "비피앰" to "비피엠",
    )

    /**
     * 👂 계속 듣기의 호출어 (#085, 사용자 결정 "메이트 Mate") — 이 말 뒤의 말만 명령으로 읽는다("메이트, 57마디부터").
     * "매이트"는 STT 가 ㅔ/ㅐ 를 바꿔 적는 것. 바꾸거나 더하려면 여기만. 음성 인식 낱말 힌트에도 들어간다
     */
    val WAKE_WORDS: List<String> = listOf("메이트", "매이트", "mate")

    /**
     * 끝 음절이 잘린 단위 — 빨리 말하고 손을 떼면 "50 마디"가 "50 마"로 온다(#084). **수 바로 뒤, 말의 맨 끝**일 때만 단위로 읽는다
     * (가운데의 "마"는 다른 말일 수 있다)
     */
    val CLIPPED_UNITS: Map<String, Kw> = mapOf(
        "마" to Kw.MEASURE,
        "페이" to Kw.PAGE,
    )

    val KEYWORDS: Map<String, Kw> = mapOf(
        "마디" to Kw.MEASURE, "소절" to Kw.MEASURE,
        "쪽" to Kw.PAGE, "페이지" to Kw.PAGE, "장" to Kw.PAGE,
        "번째" to Kw.COUNTER, "째" to Kw.COUNTER, "번" to Kw.COUNTER,
        "템포" to Kw.TEMPO, "빠르기" to Kw.TEMPO, "속도" to Kw.TEMPO,
        "bpm" to Kw.BPM, "비피엠" to Kw.BPM,
        "부터" to Kw.FROM,
        "처음" to Kw.START_OVER, "맨앞" to Kw.START_OVER,
        "다시" to Kw.AGAIN,
        "이어서" to Kw.RESUME, "계속" to Kw.RESUME,
        "다음" to Kw.NEXT,
        "이전" to Kw.PREV, "앞" to Kw.PREV,
        "전" to Kw.RELATIVE, "뒤" to Kw.RELATIVE, "후" to Kw.RELATIVE,
        "넘겨" to Kw.TURN, "넘기기" to Kw.TURN, "넘김" to Kw.TURN,
        "시작" to Kw.START, "메트로놈" to Kw.METRONOME,
        "예비박" to Kw.COUNT_IN, "카운트인" to Kw.COUNT_IN,
        "듣고넘기기" to Kw.LISTEN, "듣고넘겨" to Kw.LISTEN, "듣기" to Kw.LISTEN, "들어줘" to Kw.LISTEN,
        "멈춰" to Kw.STOP, "멈춤" to Kw.STOP, "멈추" to Kw.STOP, "정지" to Kw.STOP, "그만" to Kw.STOP, "스톱" to Kw.STOP,
        "파트" to Kw.PART, "보표" to Kw.STAFF,
        "총보" to Kw.FULL_SCORE, "풀스코어" to Kw.FULL_SCORE, "전체악보" to Kw.FULL_SCORE,
        "레터" to Kw.LETTER, "리허설" to Kw.LETTER, "연습번호" to Kw.LETTER,
        "말고" to Kw.NEGATION, "아니" to Kw.NEGATION, "취소" to Kw.NEGATION, "빼고" to Kw.NEGATION,
        "악장" to Kw.BLOCK, "장조" to Kw.BLOCK,
    )

    /** 파트 번호를 말하는 서수 — "바이올린 세컨", "비올라 둘째" */
    val ORDINALS: Map<String, Int> = mapOf(
        "퍼스트" to 1, "첫째" to 1,
        "세컨드" to 2, "세컨" to 2, "둘째" to 2,
        "써드" to 3, "서드" to 3, "셋째" to 3,
        "넷째" to 4,
    )

    /**
     * 악기 이름 → 악기 키(영어 소문자). 악보의 실제 파트 이름(`Violoncello`, `Vc.`)과 맞추는 것은 Context Resolver(P12 §7 —
     * 악보 데이터가 필요해 다음 단계)가 키로 한다. "베이스"는 콘트라베이스 · 베이스 성부 둘 다라 `bass` 하나로 둔다.
     */
    val INSTRUMENTS: Map<String, String> = mapOf(
        "바이올린" to "violin", "바이얼린" to "violin", "vn" to "violin", "vln" to "violin", "violin" to "violin",
        "비올라" to "viola", "vla" to "viola", "viola" to "viola",
        "첼로" to "cello", "셀로" to "cello", "챌로" to "cello", "vc" to "cello", "cello" to "cello",
        "콘트라베이스" to "bass", "더블베이스" to "bass", "베이스" to "bass",
        "플루트" to "flute", "플룻" to "flute", "피콜로" to "piccolo",
        "오보에" to "oboe", "클라리넷" to "clarinet", "바순" to "bassoon",
        "호른" to "horn", "트럼펫" to "trumpet", "트롬본" to "trombone", "튜바" to "tuba",
        "팀파니" to "timpani", "타악기" to "percussion", "퍼커션" to "percussion",
        "피아노" to "piano", "하프" to "harp", "기타" to "guitar",
        "소프라노" to "soprano", "알토" to "alto", "테너" to "tenor",
    )

    /** 화면에 보일 악기 이름 — 키마다 대표 한국어 */
    val INSTRUMENT_LABELS: Map<String, String> = mapOf(
        "violin" to "바이올린", "viola" to "비올라", "cello" to "첼로", "bass" to "베이스",
        "flute" to "플루트", "piccolo" to "피콜로", "oboe" to "오보에", "clarinet" to "클라리넷", "bassoon" to "바순",
        "horn" to "호른", "trumpet" to "트럼펫", "trombone" to "트롬본", "tuba" to "튜바",
        "timpani" to "팀파니", "percussion" to "타악기", "piano" to "피아노", "harp" to "하프", "guitar" to "기타",
        "soprano" to "소프라노", "alto" to "알토", "tenor" to "테너",
    )

    /** 레터 뒤의 알파벳 읽기 — "레터 에이" = A. 긴 것부터 맞춘다("에이치" > "에이") */
    val LETTERS: Map<String, Char> = mapOf(
        "에이치" to 'H', "에이" to 'A', "비" to 'B', "씨" to 'C', "시" to 'C', "디" to 'D', "이" to 'E',
        "에프" to 'F', "지" to 'G', "쥐" to 'G', "아이" to 'I', "제이" to 'J', "케이" to 'K', "엘" to 'L',
        "엠" to 'M', "엔" to 'N', "오" to 'O', "피" to 'P', "큐" to 'Q', "알" to 'R', "에스" to 'S',
        "티" to 'T', "유" to 'U', "브이" to 'V', "더블유" to 'W', "엑스" to 'X', "와이" to 'Y', "제트" to 'Z',
    )

    /**
     * 뜻 없이 붙는 조사 · 말끝 — 이것만으로 된 조각은 버린다("템포를 72로" = "템포 72").
     * 여기 없는 말은 남아서 앞뒤 낱말이 이어지는 것을 막는다(모르는 말 사이로 수와 단위를 잇지 않는다).
     */
    val PARTICLES: List<String> = listOf(
        "으로", "로", "에서", "에", "을", "를", "은", "는", "이", "가", "도", "만", "좀", "요",
        "까지", "해", "해줘", "해주세요", "주세요", "가자", "갑시다", "하자", "합시다", "할게요", "해볼게요",
        "보여줘", "보여주세요", "보여", "줘", "보기", "보", // "파트보"의 "보"
        "과", "와", "하고", "랑", "이랑", // "보표 1과 보표 2"
    )

    /** 공백을 지운 문자열에서 낱말 찾기 — 긴 것부터 */
    internal val KEYWORDS_BY_LENGTH: List<Pair<String, Token>> = buildList<Pair<String, Token>> {
        KEYWORDS.forEach { (word, kw) -> add(word to Token.Word(kw)) }
        ORDINALS.forEach { (word, n) -> add(word to Token.Ordinal(n)) }
        INSTRUMENTS.forEach { (word, key) -> add(word to Token.Instrument(key)) }
    }.sortedByDescending { it.first.length }

    private val PARTICLES_SORTED = PARTICLES.sortedByDescending { it.length }

    /** [text] 가 조사 · 말끝을 이어 붙인 것뿐인가 ("를좀", "으로요") */
    fun isParticles(text: String): Boolean {
        if (text.isEmpty()) return true
        val ok = BooleanArray(text.length + 1).also { it[0] = true }
        for (i in 0 until text.length) {
            if (!ok[i]) continue
            for (p in PARTICLES_SORTED) if (text.startsWith(p, i)) ok[i + p.length] = true
        }
        return ok[text.length]
    }

    /** 레터 뒤 조각의 앞머리를 알파벳으로 ("비부터" → B). 영문 한 글자도 받는다 */
    fun letterPrefix(text: String): Char? {
        text.firstOrNull()?.takeIf { it in 'a'..'z' }?.let { return it.uppercaseChar() }
        return LETTERS.entries.filter { text.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }
}
