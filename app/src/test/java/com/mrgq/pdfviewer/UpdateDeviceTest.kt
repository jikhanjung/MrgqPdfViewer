package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.update.ReleaseInfo
import com.mrgq.pdfviewer.update.UpdateClient
import com.mrgq.pdfviewer.update.UpdateDevice
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
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

    // ── 실제 HTTP: 확인 · 받기 첫 요청에만 머리글, 302 뒤 저장소 요청에는 없음 ─────────────

    private lateinit var http: HttpServer
    private lateinit var base: String
    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val apkBytes = ByteArray(1000) { it.toByte() }
    private lateinit var dir: File

    @Before
    fun startServer() {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${http.address.port}"
        val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
        fun record(path: String, ex: com.sun.net.httpserver.HttpExchange) {
            seen += path to ex.requestHeaders.getFirst("X-Mrgq-Install")
        }
        http.createContext("${ReleaseInfo.SCOREMATE_RELEASES_PATH}latest/") { ex ->
            record("latest", ex)
            val body = """{"version":"v0.7.7-beta.2","prerelease":true,"apk":"a.apk","size":${apkBytes.size},
                "sha256":"$sha","notes":"n","download_url":"$base${ReleaseInfo.SCOREMATE_RELEASES_PATH}v0.7.7-beta.2/apk/"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        http.createContext("${ReleaseInfo.SCOREMATE_RELEASES_PATH}v0.7.7-beta.2/apk/") { ex ->
            record("apk", ex)
            ex.responseHeaders.add("Location", "/storage/a.apk?sig=x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        http.createContext("/storage/") { ex ->
            record("storage", ex)
            ex.sendResponseHeaders(200, apkBytes.size.toLong())
            ex.responseBody.use { it.write(apkBytes) }
        }
        http.start()
        dir = Files.createTempDirectory("updates").toFile()
    }

    @After
    fun stopServer() {
        http.stop(0)
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
