package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.update.ReleaseInfo
import com.mrgq.pdfviewer.update.UpdateClient
import com.mrgq.pdfviewer.update.UpdateDevice
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

/** 업데이트 요청에 붙이는 기기 정보 (#120) — ScoreMate 서버에만, 서명 URL 로는 따라가지 않는다 */
class UpdateDeviceTest {

    private val device = UpdateDevice(
        appVersion = "0.7.7-beta.1", form = "tv", model = "Z18TV Pro", android = "14",
        installId = "3f1c2b9e-0000-4000-8000-000000000001", scoreMateDeviceId = "42",
    )

    @Test
    fun 머리글() {
        assertEquals("MrgqPdfViewer/0.7.7-beta.1 (tv; Z18TV Pro; Android 14)", device.userAgent)
        assertEquals(
            mapOf(
                "User-Agent" to device.userAgent,
                "X-Mrgq-Install" to "3f1c2b9e-0000-4000-8000-000000000001",
                "X-ScoreMate-Device" to "42",
            ),
            device.headers,
        )
        // 연결하지 않은 기기는 device_id 없이
        assertFalse("X-ScoreMate-Device" in device.copy(scoreMateDeviceId = null).headers)
    }

    @Test
    fun 머리글은_ASCII_만_형식을_깨는_글자는_뺀다() {
        val odd = device.copy(model = "샤오신 Pad (2025); Pro", appVersion = "0.7.7 beta", android = "")
        assertEquals("MrgqPdfViewer/0.7.7_beta (tv; Pad 2025 Pro; Android ?)", odd.userAgent)
        assertTrue(odd.userAgent.all { it in ' '..'~' })
    }

    @Test
    fun 모델_이름() {
        assertEquals("Lenovo TB371FC", UpdateDevice.modelName("Lenovo", "TB371FC"))
        assertEquals("Google Pixel 8", UpdateDevice.modelName("Google", "Google Pixel 8"))
    }

    @Test
    fun 에뮬레이터() {
        // CI 계측 에뮬레이터 (API 30 · 34)
        assertTrue(UpdateDevice.isEmulator("ranchu", "sdk_gphone64_x86_64", "google/sdk_gphone64_x86_64/emu64x:14/..."))
        assertTrue(UpdateDevice.isEmulator("ranchu", "sdk_gphone_x86", "google/sdk_gphone_x86/generic_x86_arm:11/..."))
        assertTrue(UpdateDevice.isEmulator("goldfish", "sdk_google_atv_x86", "generic/sdk_google_atv_x86/..."))
        // 실기기
        assertFalse(UpdateDevice.isEmulator("qcom", "TB371FC", "Lenovo/TB371FC/TB371FC:13/..."))
        assertFalse(UpdateDevice.isEmulator("amlogic", "sabrina", "google/sabrina/sabrina:14/..."))
    }

    // ── 실제 HTTP: 확인 · 받기 첫 요청에만 머리글, 302 뒤 저장소 요청에는 없음 ─────────────

    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val apkBytes = ByteArray(1000) { it.toByte() }
    private lateinit var dir: File
    private lateinit var base: String

    /** 앱 서버 흉내 — latest · 판 받기(302 → 저장소) · 저장소. 단위 테스트에서 JDK HttpServer 는 못 쓰므로 앱의 NanoHTTPD 로 */
    private val http = object : NanoHTTPD("127.0.0.1", 0) {
        val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

        override fun serve(session: IHTTPSession): Response {
            val install = session.headers["x-mrgq-install"]
            val releases = ReleaseInfo.SCOREMATE_RELEASES_PATH
            return when {
                session.uri.startsWith("${releases}latest/") -> {
                    seen += "latest" to install
                    newFixedLengthResponse(
                        Response.Status.OK, "application/json",
                        """{"version":"v0.7.7-beta.2","prerelease":true,"apk":"a.apk","size":${apkBytes.size},
                        "sha256":"$sha","notes":"n","download_url":"$base${releases}v0.7.7-beta.2/apk/"}""",
                    )
                }
                session.uri.startsWith("${releases}v0.7.7-beta.2/apk/") -> {
                    seen += "apk" to install
                    newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                        addHeader("Location", "/storage/a.apk?sig=x")
                    }
                }
                session.uri.startsWith("/storage/") -> {
                    seen += "storage" to install
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(apkBytes), apkBytes.size.toLong())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
    }

    @Before
    fun startServer() {
        http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        base = "http://127.0.0.1:${http.listeningPort}"
        dir = Files.createTempDirectory("updates").toFile()
    }

    @After
    fun stopServer() {
        http.stop()
        dir.deleteRecursively()
    }

    @Test
    fun 서버_요청에만_기기_정보() = runBlocking {
        val client = UpdateClient(server = { base }, device = { device })
        val release = client.fetchLatestRelease(includePrerelease = true)
        assertEquals("ScoreMate", client.lastSource)
        val apk = client.downloadApk(release, dir) { _, _ -> }
        assertTrue(apk.readBytes().contentEquals(apkBytes))
        assertEquals(
            listOf("latest" to device.installId, "apk" to device.installId, "storage" to null),
            seen.toList(),
        )
    }

    @Test
    fun 기기_정보를_못_만들어도_업데이트는_그대로() = runBlocking {
        val client = UpdateClient(server = { base }, device = { error("prefs 없음") })
        val release = client.fetchLatestRelease(includePrerelease = true)
        assertEquals("ScoreMate", client.lastSource)
        client.downloadApk(release, dir) { _, _ -> }
        assertEquals(listOf<String?>(null, null, null), seen.map { it.second })
        assertNull(seen.firstOrNull { it.second != null })
    }
}
