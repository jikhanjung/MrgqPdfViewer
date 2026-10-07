package com.mrgq.pdfviewer.update

import kotlinx.coroutines.CancellationException
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
 * GitHub 릴리스 조회와 APK 다운로드 (#054). 요청이 몇 개뿐이라 [HttpURLConnection] 으로 충분하다.
 *
 * 최신 버전은 API 가 아니라 웹 주소 `…/releases/latest` 의 리다이렉트(Location 의 태그)로 안다 — API 의 비인증 한도
 * (IP 당 시간 60회)를 쓰지 않아 10분마다 확인해도 된다. 변경 내용은 알릴 때만 그 태그의 `CHANGELOG.md` 에서 받는다.
 * 다운로드 URL 은 GitHub 파일 서버로 https→https 리다이렉트되며 자동으로 따라간다.
 */
class UpdateClient(
    private val repoUrl: String = REPO_URL,
    private val rawUrl: String = RAW_URL,
) {

    /**
     * 받을 릴리스. [includePrerelease](설정 "사전 릴리스 받기")면 릴리스 피드에서 정식 · 사전 릴리스 중 가장 높은 것 —
     * 피드를 못 읽으면 조용히 정식(`releases/latest`)으로. 아니면 지금처럼 정식만
     */
    suspend fun fetchLatestRelease(includePrerelease: Boolean = false): ReleaseInfo {
        if (includePrerelease) {
            try {
                newestFromFeed()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 정식으로 — 아래
            }
        }
        return fetchLatestStable()
    }

    /** 릴리스 피드에서 가장 높은 버전 — 릴리스(파일)가 실제로 있는 것만 (피드에는 릴리스 없는 태그도 온다). 앞의 몇 개만 본다 */
    private suspend fun newestFromFeed(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val tags = open("$repoUrl/releases.atom", accept = "application/atom+xml").use { ReleaseInfo.tagsFromAtom(it.readText()) }
        ReleaseInfo.candidates(tags, includePrerelease = true).take(MAX_FEED_CANDIDATES).firstNotNullOfOrNull { tag ->
            ensureActive()
            val info = try { ReleaseInfo.fromTag(repoUrl, tag) } catch (e: IllegalArgumentException) { null }
            info?.takeIf { hasFile(it.checksums!!.downloadUrl) }
        }
    }

    /** 릴리스 파일 주소가 있는가 — GitHub 은 있으면 파일 서버로 302, 없으면 404 (따라가지 않고 머리만) */
    private fun hasFile(url: String): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun fetchLatestStable(): ReleaseInfo = withContext(Dispatchers.IO) {
        val location = try {
            val conn = URL("$repoUrl/releases/latest").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", USER_AGENT)
                when (val code = conn.responseCode) {
                    in 300..399 -> conn.getHeaderField("Location")
                    404 -> null
                    429 -> throw UpdateException("GitHub 요청 한도를 넘었습니다. 잠시 후 다시 시도하세요")
                    else -> throw UpdateException("릴리스 정보를 가져오지 못했습니다 (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw UpdateException("네트워크에 연결할 수 없습니다", e)
        }
        val tag = ReleaseInfo.tagFromLocation(location) ?: throw UpdateException("공개된 릴리스가 없습니다")
        try {
            ReleaseInfo.fromTag(repoUrl, tag)
        } catch (e: IllegalArgumentException) {
            throw UpdateException("릴리스 정보를 해석하지 못했습니다", e)
        }
    }

    /** [release] 의 변경 내용 — 그 태그의 CHANGELOG 섹션. 못 받으면 빈 문자열 (알림은 그대로 띄운다) */
    suspend fun fetchReleaseNotes(release: ReleaseInfo): String = withContext(Dispatchers.IO) {
        try {
            open("$rawUrl/${release.tag}/CHANGELOG.md", accept = "text/plain").use {
                ReleaseInfo.changelogSection(it.readText(), release.tag)
            }
        } catch (e: IOException) {
            ""
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
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpStatusException(code)
        }
        return Connection(conn)
    }

    companion object {
        const val REPO_URL = "https://github.com/jikhanjung/MrgqPdfViewer"
        const val RAW_URL = "https://raw.githubusercontent.com/jikhanjung/MrgqPdfViewer"
        private const val USER_AGENT = "MrgqPdfViewer-updater"
        /** 피드에서 릴리스가 있는지 확인해 볼 후보 수 (높은 버전부터) */
        private const val MAX_FEED_CANDIDATES = 3

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
