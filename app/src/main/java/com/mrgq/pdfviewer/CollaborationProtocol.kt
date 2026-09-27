package com.mrgq.pdfviewer

import com.google.gson.JsonObject
import com.mrgq.pdfviewer.ensemble.BeatTimeline
import com.mrgq.pdfviewer.ensemble.EnsembleRun
import com.mrgq.pdfviewer.metronome.TempoSections
import com.mrgq.pdfviewer.metronome.TimeSignature

/**
 * 합주 메시지의 **와이어 포맷 단일 출처**.
 *
 * ## 왜 뽑았나
 *
 * 지휘자(서버)가 만들고 연주자(클라이언트)가 읽는 JSON 인데, 키 문자열이 두 파일에 각각
 * 하드코딩돼 있었다. 한쪽만 바꾸면 **컴파일은 통과하고 런타임에 조용히 무시된다** —
 * 합주 중에만, 그것도 두 기기가 있어야 드러나는 종류의 고장이다.
 *
 * 여기 모아두면 라운드트립 테스트(`CollaborationProtocolTest`)로 기기 없이 계약을 고정할 수 있다.
 *
 * ## 하위호환 계약
 *
 * - **`turn_at` 이 없거나 null 이면 "즉시 넘김"** — Phase 0 이전 버전 지휘자와 섞여도 동작한다.
 *   이 계약이 깨지면 구버전 기기가 페이지를 못 넘긴다.
 * - 모르는 필드는 무시한다 (상위 버전이 필드를 추가해도 구버전이 죽지 않는다).
 * - 필수 필드가 없으면 안전한 기본값으로 떨어진다 (`page`→1, `file`→"").
 */
object CollaborationProtocol {

    // ── 키 (양쪽이 공유하는 유일한 정의) ─────────────────────────────────────
    const val KEY_ACTION = "action"
    const val KEY_PAGE = "page"
    const val KEY_FILE = "file"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_TURN_AT = "turn_at"
    const val KEY_FILE_SERVER_URL = "file_server_url"

    // 합주 메트로놈 (#055)
    const val KEY_T0 = "t0"
    const val KEY_SERVER_NS = "server_ns"
    const val KEY_RUN_ID = "run_id"
    const val KEY_STATE = "state"
    const val KEY_ANCHOR_BEAT = "anchor_beat"
    const val KEY_ANCHOR_NS = "anchor_ns"
    const val KEY_BAR_BEAT = "bar_beat"
    const val KEY_BPM = "bpm"
    const val KEY_NUMERATOR = "num"
    const val KEY_DENOMINATOR = "den"
    const val KEY_DOTTED = "dotted"
    const val KEY_START_MEASURE = "start_measure"
    const val KEY_FOCUS_MEASURE = "focus_measure"
    /** 구간별 빠르기 (#057) — 악보 연동일 때만. 없으면 구간을 모르는 지휘자 */
    const val KEY_SECTIONS = "tempo_sections"
    /** 예비박 마디 수 (#060). 없으면 1 */
    const val KEY_COUNT_IN_BARS = "count_in_bars"

    // ── 액션 ────────────────────────────────────────────────────────────────
    const val ACTION_PAGE_CHANGE = "page_change"
    const val ACTION_FILE_CHANGE = "file_change"
    const val ACTION_BACK_TO_LIST = "back_to_list"
    const val ACTION_CLOCK_PING = "clock_ping"
    const val ACTION_CLOCK_PONG = "clock_pong"
    const val ACTION_METRONOME_RUN = "metronome_run"

    // ── 빌드 (지휘자) ───────────────────────────────────────────────────────

    /**
     * @param turnAt 지정하면 모든 기기가 이 절대 시각(벽시계)에 동시에 넘긴다.
     *               null 이면 필드를 **넣지 않는다** — 수신 측의 "즉시 넘김" 경로를 탄다.
     */
    fun buildPageChange(
        pageNumber: Int,
        fileName: String,
        turnAt: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): JsonObject = JsonObject().apply {
        addProperty(KEY_ACTION, ACTION_PAGE_CHANGE)
        addProperty(KEY_PAGE, pageNumber)
        addProperty(KEY_FILE, fileName)
        addProperty(KEY_TIMESTAMP, timestamp)
        turnAt?.let { addProperty(KEY_TURN_AT, it) }
    }

    fun buildFileChange(
        fileName: String,
        pageNumber: Int = 1,
        fileServerUrl: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): JsonObject = JsonObject().apply {
        addProperty(KEY_ACTION, ACTION_FILE_CHANGE)
        addProperty(KEY_FILE, fileName)
        addProperty(KEY_PAGE, pageNumber)
        addProperty(KEY_TIMESTAMP, timestamp)
        fileServerUrl?.let { addProperty(KEY_FILE_SERVER_URL, it) }
    }

    fun buildBackToList(timestamp: Long = System.currentTimeMillis()): JsonObject =
        JsonObject().apply {
            addProperty(KEY_ACTION, ACTION_BACK_TO_LIST)
            addProperty(KEY_TIMESTAMP, timestamp)
        }

    // ── 합주 메트로놈 (#055) ────────────────────────────────────────────────
    // 시각은 모두 각 기기의 System.nanoTime() (단조 시계). 기기끼리 직접 비교하지 않고 clock_ping/pong 으로 잰 offset 으로 옮긴다.

    /** 연주자 → 지휘자. [t0] = 보낸 순간의 연주자 시계 */
    fun buildClockPing(t0: Long): JsonObject = JsonObject().apply {
        addProperty(KEY_ACTION, ACTION_CLOCK_PING)
        addProperty(KEY_T0, t0)
    }

    /** 지휘자 → 연주자. [serverNs] = 핑을 받은 순간의 지휘자 시계 */
    fun buildClockPong(t0: Long, serverNs: Long): JsonObject = JsonObject().apply {
        addProperty(KEY_ACTION, ACTION_CLOCK_PONG)
        addProperty(KEY_T0, t0)
        addProperty(KEY_SERVER_NS, serverNs)
    }

    data class ClockPong(val t0: Long, val serverNs: Long)

    fun parseClockPing(json: JsonObject): Long? = json.optLong(KEY_T0)

    fun parseClockPong(json: JsonObject): ClockPong? {
        val t0 = json.optLong(KEY_T0) ?: return null
        val serverNs = json.optLong(KEY_SERVER_NS) ?: return null
        return ClockPong(t0, serverNs)
    }

    fun buildMetronomeRun(run: EnsembleRun): JsonObject = JsonObject().apply {
        addProperty(KEY_ACTION, ACTION_METRONOME_RUN)
        addProperty(KEY_RUN_ID, run.runId)
        addProperty(KEY_FILE, run.file)
        addProperty(KEY_STATE, run.state.wire)
        addProperty(KEY_ANCHOR_BEAT, run.timeline.anchorBeat)
        addProperty(KEY_ANCHOR_NS, run.timeline.anchorNs)
        addProperty(KEY_BAR_BEAT, run.timeline.barBeat)
        addProperty(KEY_BPM, run.timeline.bpm)
        addProperty(KEY_NUMERATOR, run.timeSignature.numerator)
        addProperty(KEY_DENOMINATOR, run.timeSignature.denominator)
        addProperty(KEY_DOTTED, run.dotted)
        run.startMeasure?.let { addProperty(KEY_START_MEASURE, it) }
        run.focusMeasure?.let { addProperty(KEY_FOCUS_MEASURE, it) }
        run.sections?.let { add(KEY_SECTIONS, TempoSections.toJson(it)) }
        addProperty(KEY_COUNT_IN_BARS, run.countInBars)
    }

    /**
     * 시간표를 세울 수 없는 메시지(필수 필드 없음, bpm ≤ 0, 모르는 상태)는 null — 반쯤 맞는 시간표로
     * 틀린 박을 치느니 따라가지 않는 편이 낫다. 박자 · 점음표는 없으면 4/4 · false.
     */
    fun parseMetronomeRun(json: JsonObject): EnsembleRun? {
        val runId = json.optStringOrNull(KEY_RUN_ID) ?: return null
        val state = EnsembleRun.State.fromWire(json.optStringOrNull(KEY_STATE)) ?: return null
        val anchorBeat = json.optLong(KEY_ANCHOR_BEAT) ?: return null
        val anchorNs = json.optLong(KEY_ANCHOR_NS) ?: return null
        val bpm = json.optInt(KEY_BPM, 0).takeIf { it > 0 } ?: return null
        val barBeat = json.optLong(KEY_BAR_BEAT) ?: anchorBeat
        return EnsembleRun(
            runId = runId,
            file = json.optStringOrNull(KEY_FILE) ?: "",
            state = state,
            timeline = BeatTimeline(anchorBeat, anchorNs, bpm, barBeat),
            timeSignature = TimeSignature.of(
                json.optInt(KEY_NUMERATOR, TimeSignature.DEFAULT.numerator),
                json.optInt(KEY_DENOMINATOR, TimeSignature.DEFAULT.denominator),
            ),
            dotted = json.optBoolean(KEY_DOTTED),
            startMeasure = json.optLong(KEY_START_MEASURE)?.toInt(),
            focusMeasure = json.optLong(KEY_FOCUS_MEASURE)?.toInt(),
            sections = json.get(KEY_SECTIONS)?.takeIf { it.isJsonArray }?.let { TempoSections.fromJson(it) },
            countInBars = json.optInt(KEY_COUNT_IN_BARS, 1).coerceIn(1, 2),
        )
    }

    // ── 파싱 (연주자) ───────────────────────────────────────────────────────

    data class PageChange(val page: Int, val file: String, val turnAt: Long?)

    data class FileChange(val file: String, val page: Int, val fileServerUrl: String?)

    fun parsePageChange(json: JsonObject) = PageChange(
        page = json.optInt(KEY_PAGE, 1),
        file = json.optStringOrNull(KEY_FILE) ?: "",
        turnAt = json.optLong(KEY_TURN_AT),
    )

    fun parseFileChange(json: JsonObject) = FileChange(
        file = json.optStringOrNull(KEY_FILE) ?: "",
        page = json.optInt(KEY_PAGE, 1),
        fileServerUrl = json.optStringOrNull(KEY_FILE_SERVER_URL),
    )

    // ── 안전한 필드 접근 ────────────────────────────────────────────────────
    // JSON null 과 필드 부재를 같게 다루고, 타입이 어긋나도 예외 대신 기본값으로 떨어진다.
    // 한 필드가 이상하다고 메시지 전체를 버리면 합주 중에 페이지가 안 넘어간다.

    private fun JsonObject.present(key: String) =
        has(key) && !get(key).isJsonNull

    private fun JsonObject.optInt(key: String, fallback: Int): Int =
        if (present(key)) runCatching { get(key).asInt }.getOrDefault(fallback) else fallback

    private fun JsonObject.optLong(key: String): Long? =
        if (present(key)) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.optBoolean(key: String): Boolean =
        present(key) && runCatching { get(key).asBoolean }.getOrDefault(false)

    // 주의: 오버로드 두 개(String / String?)는 JVM 시그니처가 같아 충돌한다. 하나만 둔다.
    private fun JsonObject.optStringOrNull(key: String): String? =
        if (present(key)) runCatching { get(key).asString }.getOrNull() else null
}
