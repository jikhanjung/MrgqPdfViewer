package com.mrgq.pdfviewer.scoremate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** 서버 악보 한 개 (`GET /sync/scores/` 의 scores[i], 서버 devlog 060 §1) */
data class RemoteScore(
    val id: Long,
    val title: String,
    val composer: String,
    val partName: String,
    val ensembleId: Long?,
    val ensembleName: String?,
    val versionNumber: Int,
    /** null = 서버가 아직 처리 중 — 이번엔 건너뛴다. 처리가 끝나면 updated_at 이 바뀌어 다음 동기화에 다시 온다 */
    val sha256: String?,
    val sizeBytes: Long,
    val downloadUrl: String,
)

data class SyncPage(val cursor: String?, val hasMore: Boolean, val scores: List<RemoteScore>, val ids: Set<Long>)

/** 이 TV 가 받아 둔 서버 악보 (DB `server_scores`, v14). [hidden] 이면 파일이 없다 — TV 에서 지웠으니 다시 받지 않는다 */
data class SyncedScore(
    val serverId: Long,
    val filePath: String,
    val versionNumber: Int,
    val sha256: String,
    val title: String,
    val composer: String,
    val partName: String,
    val ensembleId: Long?,
    val ensembleName: String?,
    val hidden: Boolean = false,
    val syncedAt: Long = 0L,
)

/** 받아 둔 서버 악보 저장소 (Room 구현은 repository, 테스트는 메모리) */
interface SyncedScoreStore {
    suspend fun all(): List<SyncedScore>
    suspend fun upsert(score: SyncedScore)
    suspend fun delete(serverId: Long)
}

/**
 * 파일이 옮겨지거나 지워졌을 때 앱의 파일 레코드(`pdf_files` — 경로로 찾는다)를 따라가게 한다.
 * **옮기기 전에** [beforeMove] 를 부른다 — 파일만 옮기면 새 레코드가 생겨 파일별 설정(표시 · 메트로놈 · 구간)이 사라진다 (P05 §6)
 */
interface LocalPdfRecords {
    suspend fun beforeMove(oldPath: String, newPath: String)
    suspend fun afterDelete(path: String)
}

/** 동기화 결과 — 화면에 요약으로 보인다 */
data class SyncReport(
    val downloaded: Int = 0,
    val moved: Int = 0,
    val removed: Int = 0,
    /** 서버가 아직 처리 중이라 건너뛴 악보 */
    val pending: Int = 0,
    /** 안전장치로 지우지 않은 악보 수 — 사용자 확인 뒤 [ScoreMateSync.sync] 를 allowLargeRemoval 로 다시 */
    val removalBlocked: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val changed: Boolean get() = downloaded + moved + removed > 0
}

/**
 * 악보 동기화 (P05 C2, 서버 devlog 060). 받은 악보는 `PDFs/ScoreMate/<앙상블 | 내 악보>/<제목>[ (파트)].pdf`.
 *
 * 1. 커서부터 `has_more` 가 끝날 때까지 쪽을 모두 받는다 (마지막 쪽의 `ids` = 지금 볼 수 있는 악보 전부)
 * 2. 받은 악보를 반영한다 — 판(sha256)이 다르거나 파일이 없으면 받기(`.part` → SHA-256 검증 → 교체),
 *    제목 · 파트 · 앙상블 이름이 바뀌어 경로가 달라지면 옮기기(레코드 먼저). 같은 내용의 파일이 이미 그 자리에 있으면 받지 않고 쓴다
 * 3. `ids` 에 없는 악보는 지운다 — **안전장치**: 목록이 비었거나 절반 넘게 사라지면 지우지 않고 [SyncReport.removalBlocked]
 * 4. 모두 성공했을 때만 커서를 저장한다 — 중간에 실패하면 다음에 같은 자리부터 다시 (이미 받은 것은 sha 가 같아 건너뛴다)
 *
 * 합주 중 · 악보를 보는 중에는 부르지 않는다 (파일 목록 화면에서만 — 열린 파일을 바꾸지 않게).
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상 (임시 폴더 + 가짜 서버).
 */
class ScoreMateSync(
    private val client: ScoreMateClient,
    private val tokens: ScoreMateTokenStore,
    private val store: SyncedScoreStore,
    private val records: LocalPdfRecords,
    /** `PDFs` — 받은 악보는 그 아래 `ScoreMate/` */
    private val pdfRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val scoreMateRoot: File get() = File(pdfRoot, FOLDER)

    suspend fun sync(allowLargeRemoval: Boolean = false): SyncReport {
        // 1. 쪽을 모두 받는다. 깨진 커서면 한 번 처음부터
        val pages = try {
            fetchAll(tokens.syncCursor)
        } catch (e: ScoreMateBadCursorException) {
            tokens.syncCursor = null
            fetchAll(null)
        }
        val incoming = LinkedHashMap<Long, RemoteScore>()
        pages.forEach { page -> page.scores.forEach { incoming[it.id] = it } }
        val ids = pages.last().ids

        val errors = mutableListOf<String>()
        var downloaded = 0
        var moved = 0
        var removed = 0
        var pending = 0

        // 3 먼저 판단: 지울 것 (안전장치)
        val local = store.all().associateBy { it.serverId }
        val gone = local.values.filter { it.serverId !in ids }
        val blocked = !allowLargeRemoval && gone.isNotEmpty() &&
            (ids.isEmpty() || gone.size > maxOf(2, local.size / 2))

        // 2. 이름 — 남을 악보 전부(받아 둔 것 + 새로 온 것)로 정해야 겹침을 안다
        val keep = local.filterKeys { it in ids || blocked }
        val names = assignPaths(keep, incoming)

        for (remote in incoming.values) {
            val target = names[remote.id] ?: continue
            val existing = keep[remote.id]
            if (existing?.hidden == true) {
                // TV 에서 지운 악보 — 메타데이터만 따라간다
                store.upsert(existing.copy(title = remote.title, partName = remote.partName, composer = remote.composer,
                    ensembleId = remote.ensembleId, ensembleName = remote.ensembleName))
                continue
            }
            val sha = remote.sha256?.lowercase()
            if (sha == null) {
                pending++
                continue
            }
            try {
                val changed = applyScore(remote, sha, existing, File(target))
                if (changed == Change.DOWNLOADED) downloaded++
                if (changed == Change.MOVED) moved++
            } catch (e: ScoreMateUnlinkedException) {
                throw e
            } catch (e: Exception) {
                errors += "${remote.title}: ${e.message}"
            }
        }

        // 제목만 바뀌지 않았어도 겹침이 풀리거나 생기면 옮겨야 한다 — 이번에 오지 않은 악보도 새 이름으로
        for (existing in keep.values) {
            if (existing.serverId in incoming || existing.hidden) continue
            val target = names[existing.serverId] ?: continue
            if (target == existing.filePath) continue
            try {
                moveTo(existing.copy(ensembleName = existing.ensembleId?.let { folderSource(it, incoming) } ?: existing.ensembleName), File(target))
                moved++
            } catch (e: Exception) {
                errors += "${existing.title}: ${e.message}"
            }
        }

        if (!blocked) {
            for (score in gone) {
                if (!score.hidden) {
                    File(score.filePath).delete()
                    records.afterDelete(score.filePath)
                }
                store.delete(score.serverId)
                removed++
            }
        }
        removeEmptyFolders()

        // 4. 모두 됐을 때만 커서를 넘긴다
        if (errors.isEmpty()) tokens.syncCursor = pages.last().cursor ?: tokens.syncCursor
        return SyncReport(downloaded, moved, removed, pending, if (blocked) gone.size else 0, errors)
    }

    /** TV 에서 지운 서버 악보 — 목록에서 빼고 다시 받지 않는다. 파일은 호출한 쪽이 지웠다 */
    suspend fun markHidden(filePath: String): Boolean {
        val score = store.all().firstOrNull { it.filePath == filePath && !it.hidden } ?: return false
        store.upsert(score.copy(hidden = true))
        return true
    }

    /** 숨긴 악보를 다음 동기화에 다시 받게 한다 — 커서를 처음으로 돌려 전부 다시 받는다(sha 가 같은 것은 건너뛴다) */
    suspend fun unhideAll(): Int {
        val hidden = store.all().filter { it.hidden }
        hidden.forEach { store.delete(it.serverId) }
        tokens.syncCursor = null
        return hidden.size
    }

    /**
     * 연결을 끊을 때 — [deleteFiles] 면 받은 악보 파일도 지운다. 남기면 **`PDFs/` 바로 아래로 옮긴다** — 연결을 끊은 TV 의 서재는
     * 로컬 파일뿐이라(#063) 그대로 두면 보이지 않는다. 레코드를 먼저 옮겨 파일별 설정이 따라간다. 이름이 겹치면 ` (2)`
     */
    suspend fun forgetAll(deleteFiles: Boolean) {
        for (score in store.all()) {
            if (!score.hidden) {
                val file = File(score.filePath)
                if (deleteFiles) {
                    file.delete()
                    records.afterDelete(score.filePath)
                } else if (file.isFile) {
                    moveToLocal(file)
                }
            }
            store.delete(score.serverId)
        }
        removeEmptyFolders()
        scoreMateRoot.takeIf { it.isDirectory && it.listFiles()?.isEmpty() == true }?.delete()
    }

    private suspend fun moveToLocal(file: File) {
        var target = File(pdfRoot, file.name)
        var n = 2
        while (target.exists()) {
            target = File(pdfRoot, "${file.nameWithoutExtension} ($n).${file.extension}")
            n++
        }
        records.beforeMove(file.path, target.path)
        if (!file.renameTo(target)) records.beforeMove(target.path, file.path) // 되돌린다 — 파일은 ScoreMate 폴더에 남는다
    }

    private enum class Change { NONE, DOWNLOADED, MOVED }

    private suspend fun applyScore(remote: RemoteScore, sha: String, existing: SyncedScore?, target: File): Change {
        val record = SyncedScore(
            serverId = remote.id, filePath = target.path, versionNumber = remote.versionNumber, sha256 = sha,
            title = remote.title, composer = remote.composer, partName = remote.partName,
            ensembleId = remote.ensembleId, ensembleName = remote.ensembleName, syncedAt = now(),
        )
        val currentFile = existing?.let { File(it.filePath) }
        val haveContent = existing != null && existing.sha256 == sha && currentFile!!.isFile

        if (haveContent) {
            if (currentFile!!.path == target.path) {
                if (existing != record.copy(syncedAt = existing.syncedAt)) store.upsert(record)
                return Change.NONE
            }
            moveTo(existing, target)
            store.upsert(record)
            return Change.MOVED
        }

        // 같은 내용이 이미 그 자리에 있다 (연결을 끊을 때 남긴 파일 등) — 받지 않고 쓴다
        if (target.isFile && sha256Of(target) == sha) {
            if (currentFile != null && currentFile.path != target.path) {
                currentFile.delete()
                records.afterDelete(currentFile.path)
            }
            store.upsert(record)
            return Change.DOWNLOADED
        }

        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        client.downloadScore(remote.downloadUrl, part)
        val got = sha256Of(part)
        if (got != sha) {
            part.delete()
            throw ScoreMateException("받은 파일이 서버 판과 다릅니다 (SHA-256 불일치)")
        }
        if (currentFile != null && currentFile.path != target.path && currentFile.isFile) {
            // 새 판 + 새 이름: 레코드를 새 경로로 옮긴 뒤 옛 파일을 지운다 (파일별 설정 유지)
            records.beforeMove(currentFile.path, target.path)
            currentFile.delete()
        }
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) throw ScoreMateException("받은 파일을 저장하지 못했습니다")
        }
        store.upsert(record)
        return Change.DOWNLOADED
    }

    /** 레코드를 먼저 옮기고 파일을 옮긴다. 대상 자리에 다른 파일이 있으면 실패 — 이름 규칙이 겹침을 피한다 */
    private suspend fun moveTo(existing: SyncedScore, target: File) {
        val from = File(existing.filePath)
        if (!from.isFile) return
        if (target.exists()) throw ScoreMateException("${target.name} 자리에 다른 파일이 있습니다")
        target.parentFile?.mkdirs()
        records.beforeMove(from.path, target.path)
        if (!from.renameTo(target)) {
            records.beforeMove(target.path, from.path) // 되돌린다
            throw ScoreMateException("파일을 옮기지 못했습니다")
        }
        store.upsert(existing.copy(filePath = target.path))
    }

    private suspend fun fetchAll(start: String?): List<SyncPage> {
        val pages = mutableListOf<SyncPage>()
        var cursor = start
        do {
            val page = client.fetchSyncPage(cursor)
            pages += page
            cursor = page.cursor
            if (pages.size > MAX_PAGES) throw ScoreMateException("동기화 쪽이 너무 많습니다")
        } while (page.hasMore)
        return pages
    }

    /**
     * 남을 악보마다 경로. 같은 폴더에 같은 이름이 둘 이상이면 모두 ` [#id]` 를 붙인다 — 어느 TV 에서나 같은 규칙.
     * 이미 그 자리에 우리 것이 아닌 파일(사용자가 넣은 파일)이 있어도 ` [#id]`.
     */
    private fun assignPaths(keep: Map<Long, SyncedScore>, incoming: Map<Long, RemoteScore>): Map<Long, String> {
        data class Entry(val id: Long, val folder: String, val base: String)
        // 앙상블 폴더 이름은 앙상블마다 하나 — 이번에 받은 이름을 우선. 서버는 앙상블 이름을 바꿔도 악보의 updated_at 을 올리지 않아
        // 그 앙상블 악보가 하나라도 오기 전까지는 옛 이름 그대로다 (서버 요청 문서 참고). 악보마다 이름을 쓰면 폴더가 둘로 갈라진다
        val ensembleNames = HashMap<Long, String?>()
        keep.values.forEach { l -> l.ensembleId?.let { ensembleNames[it] = l.ensembleName } }
        incoming.values.forEach { r -> r.ensembleId?.let { ensembleNames[it] = r.ensembleName } }
        val entries = (keep.keys + incoming.keys).map { id ->
            val r = incoming[id]
            val l = keep[id]
            val ensembleId = if (r != null) r.ensembleId else l?.ensembleId
            Entry(
                id,
                ScoreMateNaming.folderName(ensembleId?.let { ensembleNames[it] }),
                ScoreMateNaming.baseName(r?.title ?: l!!.title, r?.partName ?: l!!.partName),
            )
        }
        val ours = keep.values.map { it.filePath }.toSet()
        val result = HashMap<Long, String>()
        entries.groupBy { it.folder to it.base.lowercase() }.forEach { (_, group) ->
            for (e in group) {
                val dir = File(scoreMateRoot, e.folder)
                val plain = File(dir, "${e.base}.pdf")
                // 그 자리의 파일이 우리 것이 아니어도 내용이 이 악보 판과 같으면(연결을 끊을 때 남긴 파일) 우리 것으로 본다
                val sameContent = { incoming[e.id]?.sha256?.lowercase()?.let { sha -> sha256Of(plain) == sha } == true }
                val taken = plain.exists() && plain.path !in ours && keep[e.id]?.filePath != plain.path && !sameContent()
                val name = if (group.size > 1 || taken) ScoreMateNaming.withId(e.base, e.id) else e.base
                result[e.id] = File(dir, "$name.pdf").path
            }
        }
        return result
    }

    /** 이번에 받은 같은 앙상블 악보의 앙상블 이름 */
    private fun folderSource(ensembleId: Long, incoming: Map<Long, RemoteScore>): String? =
        incoming.values.firstOrNull { it.ensembleId == ensembleId }?.ensembleName

    private fun removeEmptyFolders() {
        scoreMateRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    companion object {
        /** `PDFs/` 아래 서버 악보 폴더 */
        const val FOLDER = "ScoreMate"
        private const val MAX_PAGES = 100

        fun parsePage(body: String): SyncPage? {
            val o = try {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: RuntimeException) {
                null
            } ?: return null
            val scoresJson = o.get("scores")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val idsJson = o.get("ids")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            return SyncPage(
                cursor = o.get("cursor")?.takeIf { it.isJsonPrimitive }?.asString,
                hasMore = o.get("has_more")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                scores = scoresJson.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::parseScore) },
                ids = idsJson.mapNotNull { runCatching { it.asLong }.getOrNull() }.toSet(),
            )
        }

        private fun parseScore(o: JsonObject): RemoteScore? = try {
            val version = o.getAsJsonObject("version")
            val ensemble = o.get("ensemble")?.takeIf { it.isJsonObject }?.asJsonObject
            RemoteScore(
                id = o.get("id").asLong,
                title = o.str("title") ?: "",
                composer = o.str("composer") ?: "",
                partName = o.str("part_name") ?: "",
                ensembleId = ensemble?.get("id")?.asLong,
                ensembleName = ensemble?.str("name"),
                versionNumber = version?.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
                sha256 = version?.str("sha256")?.takeIf { it.isNotBlank() },
                sizeBytes = version?.get("size_bytes")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                downloadUrl = o.str("download_url") ?: "/api/v1/scores/${o.get("id").asLong}/download/",
            )
        } catch (e: RuntimeException) {
            null
        }

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

        /** 절대 URL 이면 경로(+쿼리)만 */
        fun pathOf(url: String): String {
            if (url.startsWith("/")) return url
            val afterScheme = url.substringAfter("://", url)
            val slash = afterScheme.indexOf('/')
            return if (slash < 0) "/" else afterScheme.substring(slash)
        }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 받은 악보의 폴더 · 파일 이름 규칙 (P05 §6) — 모든 TV 가 같은 규칙을 쓴다 */
object ScoreMateNaming {
    /** 개인 악보 폴더 */
    const val PERSONAL_FOLDER = "내 악보"
    private const val MAX_LENGTH = 80
    private val FORBIDDEN = Regex("""[/\\:*?"<>|\u0000-\u001f]""")

    fun sanitize(text: String): String {
        val cleaned = FORBIDDEN.replace(text, "_").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return cleaned.take(MAX_LENGTH).trim().ifEmpty { "_" }
    }

    fun folderName(ensembleName: String?): String =
        if (ensembleName.isNullOrBlank()) PERSONAL_FOLDER else sanitize(ensembleName)

    fun baseName(title: String, partName: String): String {
        val base = sanitize(title.ifBlank { "제목 없음" })
        return if (partName.isBlank()) base else sanitize("$base ($partName)")
    }

    fun withId(base: String, id: Long): String = "$base [#$id]"
}

/** 동기화 결과를 사람이 읽을 한 줄로 */
object ScoreMateSyncText {
    /** 알릴 것이 없으면 null */
    fun summary(report: SyncReport): String? {
        val parts = mutableListOf<String>()
        if (report.downloaded > 0) parts += "악보 ${report.downloaded}개 받음"
        if (report.moved > 0) parts += "${report.moved}개 이름 바뀜"
        if (report.removed > 0) parts += "${report.removed}개 지움"
        if (report.pending > 0) parts += "${report.pending}개는 서버 처리 중"
        if (report.errors.isNotEmpty()) parts += "${report.errors.size}개 실패 (${report.errors.first()})"
        return if (parts.isEmpty()) null else "ScoreMate: " + parts.joinToString(", ")
    }
}
