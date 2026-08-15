package com.mrgq.pdfviewer

import com.google.gson.JsonObject

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

    // ── 액션 ────────────────────────────────────────────────────────────────
    const val ACTION_PAGE_CHANGE = "page_change"
    const val ACTION_FILE_CHANGE = "file_change"
    const val ACTION_BACK_TO_LIST = "back_to_list"

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

    // 주의: 오버로드 두 개(String / String?)는 JVM 시그니처가 같아 충돌한다. 하나만 둔다.
    private fun JsonObject.optStringOrNull(key: String): String? =
        if (present(key)) runCatching { get(key).asString }.getOrNull() else null
}
