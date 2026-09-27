package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.scoremate.SyncedScore
import java.io.File

/**
 * 연주자가 지휘자의 파일을 어디서 찾고 어디에 받을지 (#063, 사용자 결정 2026-09-27).
 *
 * - **ScoreMate 에 연결된 TV**: 서재는 ScoreMate 악보뿐이다. 지휘자가 보낸 `score_id` 가 내 ScoreMate 에 있으면 그것을 연다.
 *   없으면(다른 계정 · 지휘자가 로컬 파일을 연 경우) 지휘자에게서 받아 **캐시에만** 둔다 — 파일 목록에는 나오지 않는다.
 *   캐시 파일도 경로가 일정해 파일별 설정(두 페이지 · 클리핑 · 메트로놈)은 저장된다
 * - **연결하지 않은 TV**: 서재는 로컬 파일(`PDFs/`)뿐이다. 이름으로 찾고, 없으면 `PDFs/` 에 받는다 (지금까지와 같다)
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
     * @param listed 지금 서재의 파일 (이름, 경로) — 연결하지 않은 TV 에서 이름으로 찾는다
     * @param synced 받아 둔 ScoreMate 악보 — 연결된 TV 에서 `score_id` 로 찾는다
     */
    fun resolve(
        fileName: String,
        scoreId: Long?,
        linked: Boolean,
        listed: List<Pair<String, String>>,
        synced: List<SyncedScore>,
        pdfRoot: File,
        cacheDir: File,
    ): Resolution {
        val name = safeName(fileName)
        if (linked) {
            if (scoreId != null) {
                synced.firstOrNull { it.serverId == scoreId && !it.hidden && File(it.filePath).isFile }
                    ?.let { return Resolution.Open(it.filePath) }
            }
            val cached = File(cacheDir, name)
            return if (cached.isFile) Resolution.Open(cached.path) else Resolution.Download(cached, cached = true)
        }
        listed.firstOrNull { it.first == fileName || it.first == name }?.let { return Resolution.Open(it.second) }
        return Resolution.Download(File(pdfRoot, name), cached = false)
    }

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

    /** [maxBytes] 를 넘으면 오래 안 쓴 파일부터 지운다. [keep] 은 지우지 않는다(지금 연 파일). @return 지운 파일 */
    fun trimCache(cacheDir: File, maxBytes: Long = CACHE_MAX_BYTES, keep: File? = null): List<File> {
        val files = cacheDir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        val removed = mutableListOf<File>()
        for (f in files) {
            if (total <= maxBytes) break
            if (keep != null && f.path == keep.path) continue
            val size = f.length()
            if (f.delete()) {
                total -= size
                removed += f
            }
        }
        return removed
    }
}
