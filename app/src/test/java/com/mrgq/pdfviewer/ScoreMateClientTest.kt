package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.scoremate.HttpRequest
import com.mrgq.pdfviewer.scoremate.HttpResponse
import com.mrgq.pdfviewer.scoremate.HttpTransport
import com.mrgq.pdfviewer.scoremate.ScoreMateClient
import com.mrgq.pdfviewer.scoremate.ScoreMateException
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.PollResult
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.Tokens
import com.mrgq.pdfviewer.scoremate.ScoreMateTokenStore
import com.mrgq.pdfviewer.scoremate.ScoreMateUnlinkedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** ScoreMate 기기 연결 규약 해석과 토큰 갱신 (P05 C1, 서버 devlog 059). */
class ScoreMateClientTest {

    private class MemoryStore(override var tokens: Tokens? = null) : ScoreMateTokenStore {
        override val server = "https://example.test/"
        override var syncCursor: String? = null
        override var setlistsBody: String? = null
        override fun saveTokens(tokens: Tokens) {
            this.tokens = tokens
        }
        override fun clearTokens() {
            tokens = null
        }
    }

    // ── 응답 해석 ───────────────────────────────────────────────────────────

    @Test
    fun 코드_응답() {
        val code = ScoreMateProtocol.parseDeviceCode(
            """{"device_code":"dc","user_code":"BCDF-GHJK","verification_uri":"https://s/activate/",
               "verification_uri_complete":"https://s/activate/?code=BCDF-GHJK","expires_in":600,"interval":5}"""
        )!!
        assertEquals("BCDF-GHJK", code.userCode)
        assertEquals("https://s/activate/?code=BCDF-GHJK", code.verificationUriComplete)
        assertEquals(5, code.intervalSec)
        assertNull(ScoreMateProtocol.parseDeviceCode("""{"user_code":"X"}"""))
        assertNull(ScoreMateProtocol.parseDeviceCode("<html>"))
    }

    @Test
    fun 폴링_응답() {
        fun poll(code: Int, body: String) = ScoreMateProtocol.parsePoll(code, body)
        assertEquals(PollResult.Pending, poll(400, """{"error":"authorization_pending"}"""))
        assertEquals(PollResult.SlowDown, poll(400, """{"error":"slow_down"}"""))
        assertEquals(PollResult.Denied, poll(400, """{"error":"access_denied"}"""))
        assertEquals(PollResult.Expired, poll(400, """{"error":"expired_token"}"""))
        assertEquals(PollResult.Invalid, poll(400, """{"error":"invalid_grant"}"""))
        assertEquals(PollResult.SlowDown, poll(429, ""))
        assertEquals(
            PollResult.Linked(Tokens("a", "r", "d-1")),
            poll(200, """{"access_token":"a","refresh_token":"r","token_type":"Bearer","expires_in":3600,"device_id":"d-1"}"""),
        )
        assertTrue(poll(200, """{"access_token":"a"}""") is PollResult.Failed)
        assertTrue(poll(500, "oops") is PollResult.Failed)
    }

    @Test
    fun slow_down_이면_간격을_5초_늘린다() {
        assertEquals(10, ScoreMateProtocol.nextIntervalSec(5, PollResult.SlowDown))
        assertEquals(5, ScoreMateProtocol.nextIntervalSec(5, PollResult.Pending))
    }

    @Test
    fun 갱신_응답은_필드_이름이_다르다() {
        assertEquals("a2" to "r2", ScoreMateProtocol.parseRefresh("""{"access":"a2","refresh":"r2"}""", "r1"))
        assertEquals("회전하지 않으면 이전 refresh", "a2" to "r1", ScoreMateProtocol.parseRefresh("""{"access":"a2"}""", "r1"))
        assertNull(ScoreMateProtocol.parseRefresh("""{"access_token":"a2"}""", "r1"))
    }

    @Test
    fun 서버_주소_다듬기() {
        assertEquals("https://scoremate.noematica.kr", ScoreMateProtocol.normalizeServer(" scoremate.noematica.kr/ "))
        assertEquals("http://192.168.0.5:8000", ScoreMateProtocol.normalizeServer("http://192.168.0.5:8000/"))
        assertNull(ScoreMateProtocol.normalizeServer("  "))
        assertNull(ScoreMateProtocol.normalizeServer("https://"))
    }

    // ── 클라이언트 ──────────────────────────────────────────────────────────

    /** 서버 흉내: access 는 현재 것만 받고, refresh 는 회전(이전 것은 무효 — 쓰면 401) */
    private class FakeServer(var access: String = "a1", var refresh: String = "r1", var revoked: Boolean = false) : HttpTransport {
        val refreshCalls = AtomicInteger()
        val requests = mutableListOf<HttpRequest>()

        @Synchronized
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val path = request.url.removePrefix("https://example.test")
            if (path == ScoreMateProtocol.PATH_REFRESH) {
                refreshCalls.incrementAndGet()
                Thread.sleep(50) // 그 사이 다른 요청이 들어오게
                if (revoked || !request.body!!.contains("\"$refresh\"")) return HttpResponse(401, """{"code":"token_not_valid"}""")
                val n = refreshCalls.get() + 1
                access = "a$n"
                refresh = "r$n"
                return HttpResponse(200, """{"access":"$access","refresh":"$refresh"}""")
            }
            if (revoked || request.bearer != access) return HttpResponse(401, """{"code":"token_not_valid"}""")
            return HttpResponse(200, """{"id":"d-1","name":"거실 TV","last_seen_at":null}""")
        }
    }

    @Test
    fun 만료된_access_는_갱신하고_한_번_다시() = runBlocking {
        val server = FakeServer(access = "a-new-only-after-refresh")
        val store = MemoryStore(Tokens("a-old", "r1", "d-1"))
        server.access = "x" // 저장된 a-old 는 만료
        val info = ScoreMateClient(store, server).me()
        assertEquals("거실 TV", info.name)
        assertEquals(1, server.refreshCalls.get())
        assertEquals("회전된 refresh 를 저장", server.refresh, store.tokens!!.refresh)
        assertEquals(server.access, store.tokens!!.access)
    }

    @Test
    fun 동시에_401_이어도_갱신은_한_번만() = runBlocking {
        val server = FakeServer()
        val store = MemoryStore(Tokens("a-old", "r1", "d-1"))
        val client = ScoreMateClient(store, server)
        val results = (1..3).map { async { client.me() } }.awaitAll()
        assertEquals(3, results.size)
        assertEquals("회전하는 refresh 를 두 번 쓰면 두 번째가 401 로 연결이 끊긴다", 1, server.refreshCalls.get())
        assertTrue(client.isLinked)
    }

    @Test
    fun 갱신이_401_이면_해제된_것_토큰을_지운다() = runBlocking {
        val server = FakeServer(revoked = true)
        val store = MemoryStore(Tokens("a1", "r1", "d-1"))
        try {
            ScoreMateClient(store, server).me()
            fail("해제돼야 한다")
        } catch (e: ScoreMateUnlinkedException) {
            // 기대
        }
        assertNull(store.tokens)
    }

    @Test
    fun 네트워크_오류는_토큰을_지우지_않는다() = runBlocking {
        val store = MemoryStore(Tokens("a1", "r1", "d-1"))
        val client = ScoreMateClient(store) { throw IOException("no route") }
        try {
            client.me()
            fail("실패해야 한다")
        } catch (e: ScoreMateUnlinkedException) {
            fail("오프라인은 해제가 아니다")
        } catch (e: ScoreMateException) {
            // 기대
        }
        assertTrue(client.isLinked)
    }

    @Test
    fun 폴링에서_연결되면_토큰을_저장한다() = runBlocking {
        val store = MemoryStore()
        val client = ScoreMateClient(store) {
            HttpResponse(200, """{"access_token":"a","refresh_token":"r","device_id":"d-9"}""")
        }
        assertTrue(client.pollToken("dc") is PollResult.Linked)
        assertEquals(Tokens("a", "r", "d-9"), store.tokens)
    }

    @Test
    fun 해제는_서버에_알리고_오프라인이어도_이_TV_에서는_해제한다() = runBlocking {
        val server = FakeServer()
        val store = MemoryStore(Tokens("a1", "r1", "d-1"))
        val deleting = ScoreMateClient(store) { request ->
            if (request.method == "DELETE") HttpResponse(204, "") else server.execute(request)
        }
        assertTrue(deleting.unlink())
        assertNull(store.tokens)

        val offlineStore = MemoryStore(Tokens("a1", "r1", "d-1"))
        assertFalse(ScoreMateClient(offlineStore) { throw IOException("offline") }.unlink())
        assertNull(offlineStore.tokens)
    }

    @Test
    fun 요청은_서버_주소_뒤_경로로_Bearer_를_싣는다() = runBlocking {
        val server = FakeServer()
        ScoreMateClient(MemoryStore(Tokens("a1", "r1", "d-1")), server).me()
        assertEquals("https://example.test/api/v1/devices/me/", server.requests.single().url)
        assertEquals("a1", server.requests.single().bearer)
    }
}
