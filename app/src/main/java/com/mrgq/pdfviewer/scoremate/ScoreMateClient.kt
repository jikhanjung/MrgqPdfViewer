package com.mrgq.pdfviewer.scoremate

import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.DeviceCode
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.DeviceInfo
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.PollResult
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 사용자에게 그대로 보일 메시지를 담은 ScoreMate 실패 */
open class ScoreMateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 서버가 이 TV 를 더 이상 받아 주지 않는다(웹에서 해제 · refresh 만료 180일) — 저장한 토큰은 이미 지웠다. 다시 연결해야 한다 */
class ScoreMateUnlinkedException : ScoreMateException("ScoreMate 연결이 해제되었습니다 — 설정에서 다시 연결하세요")

/** 연결 정보 저장소. 구현은 앱 전용 SharedPreferences ([ScoreMateStore]), 테스트는 메모리 */
interface ScoreMateTokenStore {
    val server: String
    val tokens: Tokens?

    /** 갱신된 토큰은 **바로**(디스크까지) 저장해야 한다 — refresh 가 회전하므로 잃으면 다시 연결해야 한다 */
    fun saveTokens(tokens: Tokens)
    fun clearTokens()
}

data class HttpRequest(val method: String, val url: String, val body: String? = null, val bearer: String? = null)
data class HttpResponse(val code: Int, val body: String)

/** 네트워크 한 번. 연결 실패는 [IOException] */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
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

    /**
     * 인증 요청. 401 이면 갱신하고 한 번만 다시 — 그래도 401 이면 해제된 것.
     * 네트워크 오류는 [ScoreMateException], 해제는 [ScoreMateUnlinkedException](토큰은 지웠다).
     */
    internal suspend fun authorized(method: String, path: String, body: String? = null): HttpResponse {
        val tokens = store.tokens ?: throw ScoreMateUnlinkedException()
        val first = send(method, path, body, tokens.access)
        if (first.code != 401) return first
        val access = refreshAfter(tokens.access)
        val second = send(method, path, body, access)
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
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
