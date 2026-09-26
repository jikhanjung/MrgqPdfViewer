package com.mrgq.pdfviewer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 사용자에게 그대로 보일 메시지를 담은 업데이트 실패. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * GitHub 릴리스 조회와 APK 다운로드 (#054). 요청이 둘뿐이라 [HttpURLConnection] 으로 충분하다.
 * 다운로드 URL 은 objects.githubusercontent.com 으로 https→https 리다이렉트되며 자동으로 따라간다.
 */
class UpdateClient(
    private val latestReleaseUrl: String = LATEST_RELEASE_URL
) {

    suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val json = try {
            open(latestReleaseUrl, accept = "application/vnd.github+json").use { it.readText() }
        } catch (e: HttpStatusException) {
            throw when (e.code) {
                404 -> UpdateException("공개된 릴리스가 없습니다", e)
                403, 429 -> UpdateException("GitHub 요청 한도를 넘었습니다. 잠시 후 다시 시도하세요", e)
                else -> UpdateException("릴리스 정보를 가져오지 못했습니다 (HTTP ${e.code})", e)
            }
        } catch (e: IOException) {
            throw UpdateException("네트워크에 연결할 수 없습니다", e)
        }
        try {
            ReleaseInfo.parse(json)
        } catch (e: Exception) {
            throw UpdateException("릴리스 정보를 해석하지 못했습니다", e)
        }
    }

    /**
     * [release] 의 APK 를 [dir] 에 받고 SHA-256 을 검증한다. 받는 중에는 `.part` 로 써서
     * 끊긴 파일이 설치 대상이 되지 않게 한다. [onProgress] 는 (받은 바이트, 전체 바이트 또는 -1).
     */
    suspend fun downloadApk(
        release: ReleaseInfo,
        dir: File,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apk = release.apk ?: throw UpdateException("이 릴리스에는 설치할 APK 가 없습니다")
        val expected = apk.sha256 ?: fetchChecksum(release, apk.name)
            ?: throw UpdateException("검증할 체크섬이 없어 설치하지 않습니다")

        dir.mkdirs()
        val part = File(dir, apk.name + ".part")
        val target = File(dir, apk.name)
        part.delete()
        target.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(apk.downloadUrl, accept = "application/octet-stream").use { conn ->
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: apk.size
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            // 64KB 마다 부르면 UI 가 넘친다 — 256KB 단위로
                            if (done - lastReported >= 256 * 1024) {
                                lastReported = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: HttpStatusException) {
            part.delete()
            throw UpdateException("다운로드하지 못했습니다 (HTTP ${e.code})", e)
        } catch (e: IOException) {
            part.delete()
            throw UpdateException("다운로드 중 연결이 끊겼습니다", e)
        } catch (e: Throwable) {
            part.delete()  // 취소 포함
            throw e
        }

        val actual = digest.digest().toHex()
        if (actual != expected) {
            part.delete()
            throw UpdateException("받은 파일이 손상됐습니다 (체크섬 불일치). 다시 시도하세요")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateException("받은 파일을 저장하지 못했습니다")
        }
        target
    }

    private fun fetchChecksum(release: ReleaseInfo, fileName: String): String? {
        val sums = release.checksums ?: return null
        return try {
            open(sums.downloadUrl, accept = "text/plain").use { findSha256(it.readText(), fileName) }
        } catch (e: IOException) {
            null
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** [use] 로 닫을 수 있게 감싼 연결 */
    private class Connection(private val conn: HttpURLConnection) : AutoCloseable {
        val contentLengthLong: Long get() = conn.contentLengthLong
        val inputStream get() = conn.inputStream
        fun readText(): String = conn.inputStream.bufferedReader().use { it.readText() }
        override fun close() = conn.disconnect()
    }

    private fun open(url: String, accept: String): Connection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "MrgqPdfViewer-updater")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpStatusException(code)
        }
        return Connection(conn)
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/jikhanjung/MrgqPdfViewer/releases/latest"

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
