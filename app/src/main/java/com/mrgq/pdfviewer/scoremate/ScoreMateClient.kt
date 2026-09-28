package com.mrgq.pdfviewer.scoremate

import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.DeviceCode
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.DeviceInfo
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.PollResult
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 사용자에게 그대로 보일 메시지를 담은 ScoreMate 실패 */
open class ScoreMateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 서버가 이 TV 를 더 이상 받아 주지 않는다(웹에서 해제 · refresh 만료 180일) — 저장한 토큰은 이미 지웠다. 다시 연결해야 한다 */
class ScoreMateUnlinkedException : ScoreMateException("ScoreMate 연결이 해제되었습니다 — 설정에서 다시 연결하세요")

/** 서버가 커서를 받지 않는다 — 버리고 처음부터 */
class ScoreMateBadCursorException : ScoreMateException("동기화 커서가 맞지 않습니다")

/** 연결 정보 저장소. 구현은 앱 전용 SharedPreferences ([ScoreMateStore]), 테스트는 메모리 */
interface ScoreMateTokenStore {
    val server: String
    val tokens: Tokens?

    /** 악보 동기화 커서 (P05 C2, 서버가 준 불투명 값). 연결을 끊으면 함께 지운다 */
    var syncCursor: String?

    /**
     * 커서를 쌓을 때의 앱 동기화 형식 ([ScoreMateSync.SYNC_FORMAT]). 앱이 응답의 새 필드를 알게 되면 형식을 올리고, 옛 형식으로 쌓은 커서는
     * 한 번 처음부터 다시 받는다 — 옛 앱이 이미 지나간 변경(예: MusicXML 인식 끝)을 새 앱이 놓치지 않게. 0 = 처음(v0.2.x)
     */
    var syncFormat: Int

    /** 받은 세트리스트 (`GET /sync/setlists/` 응답 그대로, #064). 연결을 끊으면 함께 지운다 */
    var setlistsBody: String?

    /** 갱신된 토큰은 **바로**(디스크까지) 저장해야 한다 — refresh 가 회전하므로 잃으면 다시 연결해야 한다 */
    fun saveTokens(tokens: Tokens)
    fun clearTokens()
}

data class HttpRequest(val method: String, val url: String, val body: String? = null, val bearer: String? = null)
/** [location] 은 3xx 의 `Location` 헤더 */
data class HttpResponse(val code: Int, val body: String, val location: String? = null)

/** 네트워크 한 번. 연결 실패는 [IOException] */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse

    /** 2xx 면 본문을 [target] 에 쓴다. 리다이렉트는 따라가지 않고 그대로 돌려준다 ([HttpResponse.location]) */
    fun download(request: HttpRequest, target: File): HttpResponse =
        throw UnsupportedOperationException("download")
}

/**
 * ScoreMate 서버와의 통신 (P05 C1). 기기 연결(코드 · 폴링), 토큰 갱신, 인증 요청(heartbeat · 기기 정보 · 해제).
 *
 * **토큰 갱신은 한 번에 하나** (P05 §3.1): refresh 가 매번 새로 바뀌므로(회전 · 이전 것 무효) 동시에 두 요청이 갱신하면 두 번째가 무효 토큰으로
 * 401 → 연결이 끊긴다. [Mutex] 로 묶고, 기다린 요청은 이미 갱신된 access 를 쓴다.
 * **401**: access 만료일 수 있으니 갱신 후 한 번만 다시. **갱신 자체가 401** 이거나 갱신 뒤에도 401 이면 해제된 것 → 토큰을 지운다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상 (전송은 [HttpTransport] 로 바꿔 끼운다).
 */
class ScoreMateClient(
    private val store: ScoreMateTokenStore,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    private val refreshMutex = Mutex()

    val isLinked: Boolean get() = store.tokens != null

    // ── 기기 연결 (인증 없음) ─────────────────────────────────────────────

    suspend fun requestDeviceCode(name: String, model: String, appVersion: String): DeviceCode {
        val response = send("POST", ScoreMateProtocol.PATH_DEVICE_CODE, ScoreMateProtocol.deviceCodeRequest(name, model, appVersion))
        if (response.code == 429) throw ScoreMateException("연결 코드를 너무 많이 받았습니다. 잠시 후 다시 시도하세요")
        if (response.code != 200) throw ScoreMateException("연결 코드를 받지 못했습니다 (HTTP ${response.code})")
        return ScoreMateProtocol.parseDeviceCode(response.body)
            ?: throw ScoreMateException("서버 응답을 해석하지 못했습니다")
    }

    /** 한 번 묻는다. [PollResult.Linked] 면 토큰을 저장한 뒤 돌려준다 */
    suspend fun pollToken(deviceCode: String): PollResult {
        val response = try {
            send("POST", ScoreMateProtocol.PATH_DEVICE_TOKEN, ScoreMateProtocol.tokenRequest(deviceCode))
        } catch (e: ScoreMateException) {
            return PollResult.Failed(e.message ?: "네트워크 오류")
        }
        val result = ScoreMateProtocol.parsePoll(response.code, response.body)
        if (result is PollResult.Linked) store.saveTokens(result.tokens)
        return result
    }

    // ── 인증 요청 ─────────────────────────────────────────────────────────

    suspend fun me(): DeviceInfo {
        val response = authorized("GET", ScoreMateProtocol.PATH_ME)
        if (response.code != 200) throw ScoreMateException("기기 정보를 받지 못했습니다 (HTTP ${response.code})")
        return ScoreMateProtocol.parseDeviceInfo(response.body) ?: throw ScoreMateException("서버 응답을 해석하지 못했습니다")
    }

    /** 앱을 켤 때 — 웹 "TV" 목록에 앱 버전 · 모델 · 마지막 접속 */
    suspend fun heartbeat(appVersion: String, model: String): DeviceInfo? {
        val response = authorized("POST", ScoreMateProtocol.PATH_HEARTBEAT, ScoreMateProtocol.heartbeatRequest(appVersion, model))
        if (response.code == 429) return null // 요청 제한 — 다음에
        if (response.code != 200) throw ScoreMateException("서버 오류 (HTTP ${response.code})")
        return ScoreMateProtocol.parseDeviceInfo(response.body)
    }

    /**
     * 연결 해제 — 서버에 알리고 토큰을 지운다. 서버에 닿지 못해도(오프라인) 이 TV 에서는 해제한다
     * (웹 "TV" 화면에서 따로 해제할 수 있다). @return 서버에도 해제했으면 true
     */
    suspend fun unlink(): Boolean {
        val deviceId = store.tokens?.deviceId ?: return true
        val notified = try {
            val response = authorized("DELETE", ScoreMateProtocol.pathDevice(deviceId))
            response.code == 204 || response.code == 404
        } catch (e: ScoreMateUnlinkedException) {
            true // 이미 해제돼 있었다
        } catch (e: ScoreMateException) {
            false
        }
        store.clearTokens()
        return notified
    }

    // ── 악보 동기화 (P05 C2, 서버 devlog 060) ──────────────────────────────

    /** 한 쪽. 400 `{"cursor": …}` 면 [ScoreMateBadCursorException] — 커서를 버리고 처음부터 */
    suspend fun fetchSyncPage(cursor: String?): SyncPage {
        val query = if (cursor.isNullOrEmpty()) "" else "?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")
        val response = authorized("GET", ScoreMateProtocol.PATH_SYNC_SCORES + query)
        if (response.code == 400 && response.body.contains("\"cursor\"")) throw ScoreMateBadCursorException()
        if (response.code == 429) throw ScoreMateException("동기화 요청이 너무 많습니다. 잠시 후 다시")
        if (response.code != 200) throw ScoreMateException("동기화 목록을 받지 못했습니다 (HTTP ${response.code})")
        return ScoreMateSync.parsePage(response.body) ?: throw ScoreMateException("동기화 응답을 해석하지 못했습니다")
    }

    /** 세트리스트 전부 (서버가 이 TV 에 주는 것 — 0.7.0 부터 고른 곡목만). 응답 본문을 그대로 돌려준다 (#064) */
    suspend fun fetchSetlists(): String {
        val response = authorized("GET", ScoreMateProtocol.PATH_SYNC_SETLISTS)
        if (response.code != 200) throw ScoreMateException("세트리스트를 받지 못했습니다 (HTTP ${response.code})")
        return response.body
    }

    /**
     * 악보 파일을 [target] 에 받는다. `download_url` 은 302 → 서명 URL(5분, 인증 불필요)이다 — **리다이렉트는 직접**:
     * 서명 URL 에 Authorization 이 함께 가면 S3 계열은 거부할 수 있다(P05 §3.1). 호스트는 늘 설정한 서버로 — 응답의
     * 절대 URL(프록시 뒤라 http 일 수 있다)이 아니라 경로만 쓴다.
     */
    suspend fun downloadScore(downloadUrl: String, target: File) {
        val path = ScoreMateSync.pathOf(downloadUrl)
        val first = authorizedWith { bearer -> transportDownload(HttpRequest("GET", url(path), bearer = bearer), target) }
        val final = if (first.code in 300..399) {
            val location = first.location ?: throw ScoreMateException("받기 주소가 없습니다 (HTTP ${first.code})")
            val signed = if (location.startsWith("/")) url(location) else location
            transportDownload(HttpRequest("GET", signed), target)
        } else {
            first
        }
        if (final.code !in 200..299) {
            target.delete()
            throw ScoreMateException("악보를 받지 못했습니다 (HTTP ${final.code})")
        }
    }

    private suspend fun transportDownload(request: HttpRequest, target: File): HttpResponse = withContext(Dispatchers.IO) {
        try {
            transport.download(request, target)
        } catch (e: IOException) {
            target.delete()
            throw ScoreMateException("악보를 받는 중 연결이 끊겼습니다", e)
        }
    }

    private fun url(path: String) = store.server.trimEnd('/') + path

    /**
     * 인증 요청. 401 이면 갱신하고 한 번만 다시 — 그래도 401 이면 해제된 것.
     * 네트워크 오류는 [ScoreMateException], 해제는 [ScoreMateUnlinkedException](토큰은 지웠다).
     */
    internal suspend fun authorized(method: String, path: String, body: String? = null): HttpResponse =
        authorizedWith { bearer -> send(method, path, body, bearer) }

    private suspend fun authorizedWith(call: suspend (String) -> HttpResponse): HttpResponse {
        val tokens = store.tokens ?: throw ScoreMateUnlinkedException()
        val first = call(tokens.access)
        if (first.code != 401) return first
        val access = refreshAfter(tokens.access)
        val second = call(access)
        if (second.code == 401) {
            store.clearTokens()
            throw ScoreMateUnlinkedException()
        }
        return second
    }

    /** [failedAccess] 로 401 을 받았다 — 다른 요청이 이미 갱신했으면 그 access, 아니면 여기서 갱신 */
    private suspend fun refreshAfter(failedAccess: String): String = refreshMutex.withLock {
        val current = store.tokens ?: throw ScoreMateUnlinkedException()
        if (current.access != failedAccess) return@withLock current.access
        val response = send("POST", ScoreMateProtocol.PATH_REFRESH, ScoreMateProtocol.refreshRequest(current.refresh))
        when (response.code) {
            200 -> {
                val (access, refresh) = ScoreMateProtocol.parseRefresh(response.body, current.refresh)
                    ?: throw ScoreMateException("토큰 갱신 응답을 해석하지 못했습니다")
                store.saveTokens(current.copy(access = access, refresh = refresh))
                access
            }
            // simplejwt: 무효 · 만료 · 해제된 기기의 refresh 는 401. 400 은 refresh 가 비었을 때뿐 — 둘 다 다시 연결해야 한다
            400, 401 -> {
                store.clearTokens()
                throw ScoreMateUnlinkedException()
            }
            else -> throw ScoreMateException("토큰을 갱신하지 못했습니다 (HTTP ${response.code})")
        }
    }

    private suspend fun send(method: String, path: String, body: String? = null, bearer: String? = null): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                transport.execute(HttpRequest(method, store.server.trimEnd('/') + path, body, bearer))
            } catch (e: IOException) {
                throw ScoreMateException("ScoreMate 서버에 연결할 수 없습니다", e)
            }
        }
}

/** [HttpURLConnection] 전송. JSON 을 보내고 받는다. 리다이렉트는 따라가지 않는다(API 는 리다이렉트하지 않는다 — 받기는 C2 에서 따로) */
class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = request.method
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MrgqPdfViewer-scoremate")
            request.bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (request.body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    override fun download(request: HttpRequest, target: File): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "MrgqPdfViewer-scoremate")
            request.bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            if (code in 200..299) {
                target.parentFile?.mkdirs()
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            return HttpResponse(code, "", conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }
}
