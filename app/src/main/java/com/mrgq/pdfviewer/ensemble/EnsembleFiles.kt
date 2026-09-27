package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.scoremate.ScoreMateSync
import com.mrgq.pdfviewer.scoremate.SyncedScore
import java.io.File

/**
 * 연주자가 지휘자의 파일을 어디서 찾고 어디에 받을지 (#063, 사용자 결정 2026-09-27). 지휘자는 `file_change` 에 파일 **내용의 SHA-256** 을 싣는다 —
 * 서버 · 계정과 상관없고 판(버전)까지 맞는 식별자다 (`score_id` 는 서버마다 따로 매기는 번호이고 판이 달라도 같아 쓰지 않는다).
 *
 * - **ScoreMate 에 연결된 TV**: 서재는 ScoreMate 악보뿐이다. 내 ScoreMate 에 **같은 내용**이 있으면 그것을 연다(계정이 달라도).
 *   없으면 지휘자에게서 받아 **캐시에만** 둔다 — 파일 목록에는 나오지 않는다. 캐시는 `ensemble/<해시 앞 16자>/<이름>` 이라
 *   경로가 내용마다 일정해 파일별 설정(두 페이지 · 클리핑 · 메트로놈)이 저장된다
 * - **연결하지 않은 TV**: 서재는 로컬 파일(`PDFs/`)뿐이다. 이름으로 찾고, 없으면 `PDFs/` 에 받는다 (지금까지와 같다)
 * - 지휘자가 해시를 보내지 않으면(v0.2.6 이하) 캐시도 이름으로 (`ensemble/<이름>`)
 *
 * 파일 이름은 지휘자가 보낸 값이라 **경로로 그대로 쓰지 않는다** — `../` 로 앱 폴더 밖에 쓰지 못하게 이름만 남긴다.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object EnsembleFiles {

    sealed interface Resolution {
        /** 이미 있다 — 이 파일을 연다 */
        data class Open(val path: String) : Resolution

        /** 지휘자에게서 [target] 으로 받는다. [cached] 면 캐시(목록에 없음) */
        data class Download(val target: File, val cached: Boolean) : Resolution
    }

    /** 캐시 상한 — 넘으면 오래 안 쓴 것부터 지운다 */
    const val CACHE_MAX_BYTES = 200L * 1024 * 1024

    /**
     * @param sha256 지휘자 파일의 내용 해시 (없으면 이름으로)
     * @param listed 지금 서재의 파일 (이름, 경로) — 연결하지 않은 TV 에서 이름으로 찾는다
     * @param synced 받아 둔 ScoreMate 악보 — 연결된 TV 에서 해시로 찾는다
     */
    fun resolve(
        fileName: String,
        sha256: String?,
        linked: Boolean,
        listed: List<Pair<String, String>>,
        synced: List<SyncedScore>,
        pdfRoot: File,
        cacheDir: File,
    ): Resolution {
        val name = safeName(fileName)
        if (linked) {
            if (sha256 != null) {
                synced.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) && !it.hidden && File(it.filePath).isFile }
                    ?.let { return Resolution.Open(it.filePath) }
            }
            val cached = cacheFile(cacheDir, name, sha256)
            return if (cached.isFile) Resolution.Open(cached.path) else Resolution.Download(cached, cached = true)
        }
        listed.firstOrNull { it.first == fileName || it.first == name }?.let { return Resolution.Open(it.second) }
        return Resolution.Download(File(pdfRoot, name), cached = false)
    }

    /** 캐시 자리 — 내용마다 폴더를 달리해 이름이 같은 다른 악보와 섞이지 않고 설정도 내용별로 */
    fun cacheFile(cacheDir: File, name: String, sha256: String?): File =
        if (sha256 != null) File(File(cacheDir, sha256.lowercase().take(16)), name) else File(cacheDir, name)

    /** 경로로 쓸 수 있는 이름 — 디렉터리 부분을 버리고 금지 문자를 바꾼다. PDF 확장자를 보장한다 */
    fun safeName(fileName: String): String {
        val base = fileName.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("""[:*?"<>|\u0000-\u001f]"""), "_")
            .trim().trimStart('.')
            .ifEmpty { "악보" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    /** 캐시 파일을 방금 쓴 것으로 — 오래된 것부터 지울 때 순서 */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** [maxBytes] 를 넘으면 오래 안 쓴 파일부터 지운다(하위 폴더까지). [keep] 은 지우지 않는다(지금 연 파일). @return 지운 파일 */
    fun trimCache(cacheDir: File, maxBytes: Long = CACHE_MAX_BYTES, keep: File? = null): List<File> {
        val files = cacheDir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        val removed = mutableListOf<File>()
        for (f in files) {
            if (total <= maxBytes) break
            if (keep != null && f.path == keep.path) continue
            val size = f.length()
            if (f.delete()) {
                total -= size
                removed += f
                f.parentFile?.takeIf { it != cacheDir && it.listFiles()?.isEmpty() == true }?.delete()
            }
        }
        return removed
    }

    // ── 지휘자: 연 파일의 해시 ─────────────────────────────────────────────

    private data class HashKey(val path: String, val length: Long, val modified: Long)
    private val hashes = object : LinkedHashMap<HashKey, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HashKey, String>?) = size > 256
    }

    /**
     * 지휘자가 연 파일의 SHA-256. ScoreMate 악보면 받을 때 검증한 값([known])을 쓰고, 로컬 파일은 한 번 계산해 기억한다
     * (경로 · 크기 · 수정 시각이 같으면 다시 계산하지 않는다). 읽지 못하면 null — 연주자는 이름으로 찾는다
     */
    fun sha256Of(file: File, known: String? = null): String? {
        if (known != null) return known.lowercase()
        if (!file.isFile) return null
        val key = HashKey(file.path, file.length(), file.lastModified())
        synchronized(hashes) { hashes[key]?.let { return it } }
        val sha = try {
            ScoreMateSync.sha256Of(file)
        } catch (e: java.io.IOException) {
            return null
        }
        synchronized(hashes) { hashes[key] = sha }
        return sha
    }
}
