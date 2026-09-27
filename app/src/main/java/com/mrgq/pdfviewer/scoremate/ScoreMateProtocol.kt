package com.mrgq.pdfviewer.scoremate

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * ScoreMate 서버 규약 — TV 기기 연결 (RFC 8628, 서버 devlog 059). 응답 해석만 하고 네트워크는 모른다.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object ScoreMateProtocol {

    const val DEFAULT_SERVER = "https://scoremate.noematica.kr"

    const val PATH_DEVICE_CODE = "/api/v1/device/code"
    const val PATH_DEVICE_TOKEN = "/api/v1/device/token"
    const val PATH_REFRESH = "/api/v1/auth/token/refresh/"
    const val PATH_ME = "/api/v1/devices/me/"
    const val PATH_HEARTBEAT = "/api/v1/devices/me/heartbeat/"
    fun pathDevice(deviceId: String) = "/api/v1/devices/$deviceId/"

    /** 코드 받기 응답 */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    data class Tokens(val access: String, val refresh: String, val deviceId: String)

    /** 토큰 폴링 결과 (서버 devlog 059 §1) */
    sealed interface PollResult {
        /** 아직 휴대폰에서 연결하지 않았다 — 같은 간격으로 계속 */
        object Pending : PollResult
        /** 너무 자주 물었다 — 간격 +5초 */
        object SlowDown : PollResult
        /** 휴대폰에서 거절했다 — 처음부터 */
        object Denied : PollResult
        /** 10분 지났다 — 새 코드 */
        object Expired : PollResult
        /** 없는 코드 · 이미 토큰으로 바꾼 코드 — 처음부터 */
        object Invalid : PollResult
        data class Linked(val tokens: Tokens) : PollResult
        data class Failed(val message: String) : PollResult
    }

    /** 이 TV 의 정보 (`GET /devices/me/`) */
    data class DeviceInfo(val id: String, val name: String, val lastSeenAt: String?)

    fun deviceCodeRequest(name: String, model: String, appVersion: String): String = JsonObject().apply {
        addProperty("name", name.take(100))
        addProperty("model", model.take(100))
        addProperty("app_version", appVersion.take(50))
    }.toString()

    fun tokenRequest(deviceCode: String): String = JsonObject().apply {
        addProperty("device_code", deviceCode)
        addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
    }.toString()

    fun refreshRequest(refresh: String): String = JsonObject().apply { addProperty("refresh", refresh) }.toString()

    fun heartbeatRequest(appVersion: String, model: String): String = JsonObject().apply {
        addProperty("app_version", appVersion.take(50))
        addProperty("model", model.take(100))
    }.toString()

    /** 코드 받기 응답. 필수 필드가 없으면 null */
    fun parseDeviceCode(body: String): DeviceCode? {
        val o = parseObject(body) ?: return null
        val uri = o.string("verification_uri") ?: return null
        return DeviceCode(
            deviceCode = o.string("device_code") ?: return null,
            userCode = o.string("user_code") ?: return null,
            verificationUri = uri,
            verificationUriComplete = o.string("verification_uri_complete") ?: uri,
            expiresInSec = o.int("expires_in") ?: 600,
            intervalSec = (o.int("interval") ?: 5).coerceAtLeast(1),
        )
    }

    fun parsePoll(code: Int, body: String): PollResult {
        val o = parseObject(body)
        return when (code) {
            200 -> {
                val access = o?.string("access_token")
                val refresh = o?.string("refresh_token")
                val deviceId = o?.string("device_id")
                if (access != null && refresh != null && deviceId != null) {
                    PollResult.Linked(Tokens(access, refresh, deviceId))
                } else {
                    PollResult.Failed("서버 응답에 토큰이 없습니다")
                }
            }
            400 -> when (o?.string("error")) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "access_denied" -> PollResult.Denied
                "expired_token" -> PollResult.Expired
                "invalid_grant" -> PollResult.Invalid
                else -> PollResult.Failed("연결 오류: ${o?.string("error") ?: "HTTP 400"}")
            }
            // 요청 제한 — 천천히 다시
            429 -> PollResult.SlowDown
            else -> PollResult.Failed("서버 오류 (HTTP $code)")
        }
    }

    /** 폴링 간격 — slow_down 이면 5초 늘린다 (RFC 8628 §3.5) */
    fun nextIntervalSec(current: Int, result: PollResult): Int =
        if (result is PollResult.SlowDown) current + 5 else current

    /** 갱신 응답 `{access, refresh}` — 필드 이름이 폴링 응답과 다르다. refresh 가 없으면(회전 안 함) 이전 것을 쓴다 */
    fun parseRefresh(body: String, previousRefresh: String): Pair<String, String>? {
        val o = parseObject(body) ?: return null
        val access = o.string("access") ?: return null
        return access to (o.string("refresh") ?: previousRefresh)
    }

    fun parseDeviceInfo(body: String): DeviceInfo? {
        val o = parseObject(body) ?: return null
        return DeviceInfo(
            id = o.string("id") ?: return null,
            name = o.string("name") ?: "",
            lastSeenAt = o.string("last_seen_at"),
        )
    }

    /** 사람이 입력한 서버 주소를 다듬는다 — 끝의 / 제거, 스킴이 없으면 https. 쓸 수 없으면 null */
    fun normalizeServer(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = s.substringAfter("://").trimEnd('/')
        val host = rest.substringBefore('/')
        if (host.isEmpty() || host.startsWith(':') || host.contains(' ')) return null
        return "$scheme://$rest"
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: RuntimeException) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (e: NumberFormatException) {
        null
    }
}
