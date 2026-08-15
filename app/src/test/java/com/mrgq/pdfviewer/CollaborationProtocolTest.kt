package com.mrgq.pdfviewer

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 합주 와이어 포맷 계약.
 *
 * 이 계약이 깨지면 **두 기기가 있어야, 그것도 합주 중에만** 드러난다. 기기 없이 고정해 두는
 * 가치가 큰 이유다. 특히 `turn_at` 하위호환(Phase 0)은 지금까지 아무 데도 고정돼 있지 않았다.
 */
class CollaborationProtocolTest {

    private val gson = Gson()
    private fun parse(raw: String): JsonObject = gson.fromJson(raw, JsonObject::class.java)

    /** 지휘자 → 와이어 → 연주자 왕복. 양쪽이 같은 키를 쓰는지 실제로 확인한다. */
    private fun roundTripPage(page: Int, file: String, turnAt: Long?) =
        CollaborationProtocol.parsePageChange(
            parse(CollaborationProtocol.buildPageChange(page, file, turnAt, timestamp = 1L).toString())
        )

    // ── 라운드트립 ──────────────────────────────────────────────────────────

    @Test
    fun `page_change 는 왕복해도 값이 보존된다`() {
        val r = roundTripPage(7, "악보1.pdf", 1_700_000_000_123L)
        assertEquals(7, r.page)
        assertEquals("악보1.pdf", r.file)
        assertEquals(1_700_000_000_123L, r.turnAt)
    }

    @Test
    fun `file_change 는 왕복해도 값이 보존된다`() {
        val built = CollaborationProtocol.buildFileChange(
            "바흐 파르티타.pdf", pageNumber = 3, fileServerUrl = "http://192.168.0.5:8090", timestamp = 1L
        )
        val r = CollaborationProtocol.parseFileChange(parse(built.toString()))
        assertEquals("바흐 파르티타.pdf", r.file)
        assertEquals(3, r.page)
        assertEquals("http://192.168.0.5:8090", r.fileServerUrl)
    }

    @Test
    fun `한글 파일명이 왕복에서 깨지지 않는다`() {
        assertEquals("바흐-무반주 첼로 1번 (사본).pdf",
            roundTripPage(1, "바흐-무반주 첼로 1번 (사본).pdf", null).file)
    }

    @Test
    fun `액션 이름이 지금 값에서 바뀌지 않는다`() {
        // 상대 기기는 문자열로 분기한다. 이 값이 바뀌면 구버전과 통신이 끊긴다.
        assertEquals("page_change",
            CollaborationProtocol.buildPageChange(1, "a.pdf").get("action").asString)
        assertEquals("file_change",
            CollaborationProtocol.buildFileChange("a.pdf").get("action").asString)
        assertEquals("back_to_list",
            CollaborationProtocol.buildBackToList().get("action").asString)
    }

    // ── turn_at 하위호환 (Phase 0) ──────────────────────────────────────────

    @Test
    fun `turn_at 이 null 이면 필드를 아예 넣지 않는다`() {
        // 구버전 연주자는 모르는 필드를 무시하지만, null 을 넣으면 파싱 구현에 따라
        // 예외가 날 수 있다. 아예 안 넣는 게 안전하다.
        val json = CollaborationProtocol.buildPageChange(3, "a.pdf", turnAt = null)
        assertTrue("turn_at 이 없어야 한다", !json.has(CollaborationProtocol.KEY_TURN_AT))
    }

    @Test
    fun `turn_at 이 없으면 즉시 넘김으로 해석된다`() {
        // Phase 0 이전 지휘자가 보내는 메시지 형태.
        val legacy = """{"action":"page_change","page":5,"file":"a.pdf","timestamp":1}"""
        val r = CollaborationProtocol.parsePageChange(parse(legacy))
        assertEquals(5, r.page)
        assertNull("turn_at 이 없으면 null(=즉시 넘김)이어야 한다", r.turnAt)
    }

    @Test
    fun `turn_at 이 JSON null 이어도 즉시 넘김이다`() {
        val raw = """{"action":"page_change","page":5,"file":"a.pdf","turn_at":null}"""
        assertNull(CollaborationProtocol.parsePageChange(parse(raw)).turnAt)
    }

    @Test
    fun `turn_at 은 Int 범위를 넘는 벽시계 밀리초를 견딘다`() {
        // 2026 년 epoch ms 는 약 1.7e12 로 Int 를 훨씬 넘는다. asInt 로 읽으면 깨진다.
        val t = 1_776_000_000_000L
        assertEquals(t, roundTripPage(1, "a.pdf", t).turnAt)
    }

    // ── 결손·이상 입력 (한 필드 때문에 메시지를 통째로 버리지 않는다) ──────────

    @Test
    fun `필수 필드가 없으면 안전한 기본값으로 떨어진다`() {
        val r = CollaborationProtocol.parsePageChange(parse("""{"action":"page_change"}"""))
        assertEquals(1, r.page)
        assertEquals("", r.file)
        assertNull(r.turnAt)
    }

    @Test
    fun `타입이 어긋나도 예외 대신 기본값이 된다`() {
        // 합주 중에 한 필드가 이상하다고 메시지를 버리면 페이지가 안 넘어간다.
        val raw = """{"action":"page_change","page":"세쪽","file":123,"turn_at":"곧"}"""
        val r = CollaborationProtocol.parsePageChange(parse(raw))
        assertEquals(1, r.page)          // 숫자로 못 읽으면 기본값
        assertEquals("123", r.file)      // 숫자는 문자열로 읽힌다
        assertNull(r.turnAt)             // 파싱 실패 → 즉시 넘김으로 안전하게
    }

    @Test
    fun `모르는 필드는 무시한다`() {
        // 상위 버전이 필드를 추가해도 구버전이 죽지 않아야 한다.
        val raw = """{"action":"page_change","page":2,"file":"a.pdf",
                      "tempo":120,"measure":{"index":7},"future":[1,2,3]}"""
        val r = CollaborationProtocol.parsePageChange(parse(raw))
        assertEquals(2, r.page)
        assertEquals("a.pdf", r.file)
    }

    @Test
    fun `file_server_url 이 없으면 null 이다`() {
        val r = CollaborationProtocol.parseFileChange(
            parse("""{"action":"file_change","file":"a.pdf","page":1}""")
        )
        assertNull(r.fileServerUrl)
    }

    @Test
    fun `page 는 0 이나 음수도 그대로 전달한다`() {
        // 방어는 상위 로직(뷰어)의 책임이다. 프로토콜이 조용히 값을 바꾸면 원인 추적이 어려워진다.
        assertEquals(0, roundTripPage(0, "a.pdf", null).page)
        assertEquals(-1, roundTripPage(-1, "a.pdf", null).page)
    }
}
