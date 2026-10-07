package com.mrgq.pdfviewer.scoremate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.notes.ScoreNotesFile
import com.mrgq.pdfviewer.notes.ScoreNotesFile.Loaded
import com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState
import java.io.File

/**
 * 악보 메모 서버 동기화 (P11 §6, 서버 0.17.0 · 서버 devlog 076) — 악보 동기화([ScoreMateSync.sync]) 바로 뒤에 한 번. 두 겹:
 *  - **개인 메모(personal)** — `.notes.json`, 늘 올리고 받는다
 *  - **지휘자 메모(conductor)** — 앙상블 악보만, `.conductor.notes.json`. 받기는 모두, 올리기는 이 사용자가 owner · leader 일 때만
 *    (`conductor_writable`). 쓸 수 있는지는 곁 파일 `sync.writable` 에 적어 뷰어가 쓰기 겹을 보인다. 서버가 새 메모에 쓴 사람(author)을 찍는다
 *
 * 곁 파일 `.notes.json` 의 `sync` = 서버와 마지막으로 맞춘 revision 과 그때의 id. 악보마다:
 *  - 서버 목록의 revision 이 내 것과 같으면 — 내가 바꿨을 때만(id 집합이 base 와 다름) 올린다
 *  - 다르면(다른 기기가 고침) — 받아서 **합치기**(서버 ∪ 내가 더한 것 − 내가 지운 것). 합친 것이 서버와 같으면 받기만, 다르면 올린다
 *  - 서버에 없으면 — 내가 쓴 메모가 있을 때 `base_revision` 0 으로 처음 만든다. 두 기기가 동시에 처음 만들어 409 + current 면
 *    평소처럼 합쳐 다시 (current 가 null 이면 0 으로 다시)
 *  - **목록에서 빠짐 ≠ 삭제**(서버 · 반장 합의) — 서버에 있던 문서가 목록에 없으면 아무것도 하지 않는다. 삭제는 빈 notes 로만 온다
 *
 * 곁 파일 읽기 · 쓰기는 [ScoreNotesFile.lock] 안에서. 네트워크 동안 뷰어가 고쳤으면(id 가 달라졌으면) 이번에는 쓰지 않는다 — 다음 동기화에.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상(가짜 서버).
 */
class ScoreNotesSync(
    private val client: ScoreMateClient,
    /** 쓰기 권한을 잃은 지휘자 메모의 올리지 못한 판을 남겨 두는 곳 (`PDFs/.ScoreMateNotes`) */
    private val keptDir: File? = null,
) {

    data class Result(val uploaded: Int = 0, val downloaded: Int = 0, val errors: List<String> = emptyList())

    /** [scores] = 이 기기에 있는 ScoreMate 악보 (숨긴 것 빼고) */
    suspend fun sync(scores: List<SyncedScore>): Result {
        val listBody = client.fetchNotesList() ?: return Result() // 메모를 모르는 서버
        val list = parseList(listBody)
        var up = 0
        var down = 0
        val errors = mutableListOf<String>()
        for (score in scores) {
            if (score.hidden) continue
            val pdf = File(score.filePath)
            if (!pdf.isFile) continue
            val layers = buildList {
                add(Triple(LAYER, ScoreNotesFile.fileOf(pdf), true))
                if (score.ensembleId != null) add(Triple(LAYER_CONDUCTOR, ScoreNotesFile.conductorFileOf(pdf), score.serverId in list.conductorWritable))
            }
            for ((layer, file, writable) in layers) {
                try {
                    if (layer == LAYER_CONDUCTOR) setWritable(file, writable)
                    when (syncOne(score, layer, file, list.revisions[score.serverId to layer], writable)) {
                        Outcome.UPLOADED -> up++
                        Outcome.DOWNLOADED -> down++
                        Outcome.NONE -> Unit
                    }
                } catch (e: ScoreMateUnlinkedException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${score.title} ${if (layer == LAYER_CONDUCTOR) "지휘자 메모" else "메모"}: ${e.message}"
                }
            }
        }
        return Result(up, down, errors)
    }

    /**
     * 주고받을 것이 있나 — [sync] 가 무언가 할지를 같은 규칙으로 미리 본다(받지는 않는다): 올릴 것(받은 뒤와 다름),
     * 서버 판이 다름, 지휘자 메모를 쓸 수 있는지가 바뀜. 메모를 모르는 서버면 false
     */
    suspend fun needsSync(scores: List<SyncedScore>): Boolean {
        val list = parseList(client.fetchNotesList() ?: return false)
        for (score in scores) {
            if (score.hidden) continue
            val pdf = File(score.filePath)
            if (!pdf.isFile) continue
            val layers = buildList {
                add(Triple(LAYER, ScoreNotesFile.fileOf(pdf), true))
                if (score.ensembleId != null) add(Triple(LAYER_CONDUCTOR, ScoreNotesFile.conductorFileOf(pdf), score.serverId in list.conductorWritable))
            }
            for ((layer, file, writable) in layers) {
                val local = synchronized(ScoreNotesFile.lock) { ScoreNotesFile.read(file) }
                val base = local?.sync ?: SyncState(0, emptySet())
                if (layer == LAYER_CONDUCTOR && writable != base.writable && (local != null || writable)) return true
                val dirty = writable && local != null && local.ids != base.baseIds
                val remote = list.revisions[score.serverId to layer]
                // 서버에 없으면 처음 만들 때만 (맞춘 적이 있는데 목록에 없으면 sync 도 건드리지 않는다)
                if (if (remote == null) dirty && base.revision == 0 else remote != base.revision || dirty) return true
            }
        }
        return false
    }

    private enum class Outcome { NONE, UPLOADED, DOWNLOADED }

    /** [writable] 이 아니면 받기만 (지휘자 메모 — 멤버) */
    private suspend fun syncOne(score: SyncedScore, layer: String, file: File, remoteRevision: Int?, writable: Boolean): Outcome {
        val local = synchronized(ScoreNotesFile.lock) {
            if (file.exists()) ScoreNotesFile.read(file) ?: throw ScoreMateException("메모 파일을 읽지 못했습니다")
            else Loaded(emptyList(), null)
        }
        val base = local.sync ?: SyncState(0, emptySet())
        if (layer == LAYER_CONDUCTOR && !writable && local.ids != base.baseIds && local.ids.isNotEmpty()) {
            // 리더였다가 아니게 됐는데 올리지 못한 지휘자 메모가 있다 — 서버 것으로 바뀌기 전에 보관 (반장 검토)
            keepUnsent(score, file)
            resetToServer(score, layer, file, local)
            throw ScoreMateException("지휘자 메모를 쓸 권한이 없어 올리지 못한 메모를 보관해 두었습니다")
        }
        val dirty = writable && local.ids != base.baseIds

        if (remoteRevision == null) {
            // 서버에 없음 — 처음 만들기. 맞춘 적이 있는데 목록에 없으면(범위 밖 등) 건드리지 않는다
            if (base.revision != 0 || !dirty) return Outcome.NONE
            return upload(score, layer, file, local, local, base, baseRevision = 0)
        }
        if (remoteRevision == base.revision) {
            return if (dirty) upload(score, layer, file, local, local, base, baseRevision = base.revision) else Outcome.NONE
        }
        // 다른 기기가 고쳤다 — 받아서 합친다
        val response = client.getNotes(score.serverId, layer)
        if (response.code == 404) {
            return if (dirty) upload(score, layer, file, local, local, base, baseRevision = 0) else Outcome.NONE
        }
        if (response.code != 200) throw ScoreMateException("메모를 받지 못했습니다 (HTTP ${response.code})")
        val remote = parseDoc(response.body)
        // 쓸 수 없으면 서버 것 그대로 (내 쪽에서 바꿀 수 없다)
        val merged = if (writable) ScoreNotesFile.merge(remote.loaded, local, base.baseIds) else remote.loaded
        if (merged.ids == remote.loaded.ids) {
            return if (writeLocal(file, local, remote.loaded, remote.revision)) Outcome.DOWNLOADED else Outcome.NONE
        }
        return upload(score, layer, file, local, merged, base, baseRevision = remote.revision)
    }

    /** [doc] 을 올린다. 409 면 서버 것과 다시 합쳐 몇 번까지 */
    private suspend fun upload(score: SyncedScore, layer: String, file: File, local: Loaded, doc: Loaded, base: SyncState, baseRevision: Int): Outcome {
        var body = doc
        var revision = baseRevision
        repeat(MAX_ATTEMPTS) {
            val request = ScoreNotesFile.formatJson(body.copy(pdfSha256 = score.sha256, sync = null), withSync = false)
            request.addProperty("base_revision", revision)
            val response = client.putNotes(score.serverId, layer, request.toString())
            when (response.code) {
                200 -> {
                    val saved = parseDoc(response.body)
                    writeLocal(file, local, saved.loaded, saved.revision)
                    return Outcome.UPLOADED
                }
                409 -> {
                    val current = JsonParser.parseString(response.body).asJsonObject.get("current")
                        ?.takeIf { it.isJsonObject }?.asJsonObject
                    if (current == null) {
                        revision = 0 // 서버에 문서가 없는데 base > 0 으로 보냈다
                    } else {
                        val remote = docOf(current)
                        body = ScoreNotesFile.merge(remote.loaded, local, base.baseIds)
                        revision = remote.revision
                    }
                }
                // 403: owner · leader 가 아니게 됐다 — 쓰기를 거두고 올리지 못한 것은 보관(다음 동기화에 서버 것으로 바뀐다). 다시 시도하지 않는다
                403 -> {
                    if (layer == LAYER_CONDUCTOR) {
                        keepUnsent(score, file)
                        setWritable(file, false)
                        resetToServer(score, layer, file, local)
                    }
                    throw ScoreMateException("지휘자 메모를 쓸 권한이 없습니다 — 올리지 못한 메모는 보관해 두었습니다")
                }
                else -> throw ScoreMateException("메모를 올리지 못했습니다 (HTTP ${response.code})")
            }
        }
        throw ScoreMateException("메모가 계속 엇갈려 올리지 못했습니다")
    }

    /**
     * 서버 문서로 곁 파일을 바꾼다 — 읽은 뒤 뷰어가 고쳤으면(id 가 [readBefore] 와 다름) 쓰지 않는다(false).
     * pdf_sha256 은 서버 것이 없으면 내 것
     */
    private fun writeLocal(file: File, readBefore: Loaded, doc: Loaded, revision: Int): Boolean = synchronized(ScoreNotesFile.lock) {
        val now = if (file.exists()) ScoreNotesFile.read(file) ?: return false else Loaded(emptyList(), null)
        if (now.ids != readBefore.ids) return false
        val writable = now.sync?.writable ?: false
        ScoreNotesFile.write(file, doc.copy(pdfSha256 = doc.pdfSha256 ?: now.pdfSha256, sync = SyncState(revision, doc.ids, writable)))
        true
    }

    /** 곁 파일을 서버 문서 그대로로 (없으면 빈 것) — 보관한 뒤, 같은 것을 다시 보관하지 않게 */
    private suspend fun resetToServer(score: SyncedScore, layer: String, file: File, local: Loaded) {
        val response = client.getNotes(score.serverId, layer)
        when (response.code) {
            200 -> parseDoc(response.body).let { writeLocal(file, local, it.loaded, it.revision) }
            404 -> writeLocal(file, local, Loaded(emptyList(), null), 0)
        }
    }

    /** 올리지 못한 지휘자 메모를 보관함에 그대로 복사 (`<score_id>.conductor-unsent-<시각>.notes.json`) */
    private fun keepUnsent(score: SyncedScore, file: File) = synchronized(ScoreNotesFile.lock) {
        val dir = keptDir ?: return@synchronized
        if (!file.isFile) return@synchronized
        dir.mkdirs()
        file.copyTo(File(dir, "${score.serverId}.conductor-unsent-${System.currentTimeMillis()}${ScoreNotesFile.SUFFIX}"), overwrite = true)
    }

    /** 지휘자 메모를 쓸 수 있는지 곁 파일에 — 쓸 수 있는데 파일이 없으면 빈 것으로 만든다(뷰어가 쓰기 겹을 보이게) */
    private fun setWritable(file: File, writable: Boolean) = synchronized(ScoreNotesFile.lock) {
        if (!file.exists()) {
            if (writable) ScoreNotesFile.write(file, Loaded(emptyList(), null, sync = SyncState(0, emptySet(), writable = true)))
            return@synchronized
        }
        val now = ScoreNotesFile.read(file) ?: return@synchronized
        val sync = now.sync ?: SyncState(0, emptySet())
        if (sync.writable != writable) ScoreNotesFile.write(file, now.copy(sync = sync.copy(writable = writable)))
    }

    private class Doc(val loaded: Loaded, val revision: Int)

    private fun parseDoc(body: String): Doc = docOf(JsonParser.parseString(body).asJsonObject)

    private fun docOf(o: JsonObject): Doc = Doc(ScoreNotesFile.parse(o.toString()).copy(sync = null), o.get("revision").asInt)

    /** 서버 메모 목록 — (score_id, 겹) → revision, 지휘자 메모를 쓸 수 있는 악보 */
    data class NotesList(val revisions: Map<Pair<Long, String>, Int>, val conductorWritable: Set<Long>)

    companion object {
        const val LAYER = "personal"
        const val LAYER_CONDUCTOR = "conductor"
        private const val MAX_ATTEMPTS = 3

        /** 올리지 못한 변경이 남았나 — 맞춘 뒤 id 가 바뀌었거나(쓸 수 있을 때), 맞춘 적 없이 메모가 있다 */
        fun hasUnsent(file: File): Boolean = synchronized(ScoreNotesFile.lock) {
            val loaded = ScoreNotesFile.read(file) ?: return@synchronized false
            val sync = loaded.sync ?: return@synchronized loaded.ids.isNotEmpty()
            sync.writable && loaded.ids != sync.baseIds
        }

        /** 곡 목록 표시 — 메모가 있나 · 서버에 올리지 못한 것이 있나 (개인 + 지휘자 겹) */
        data class Badge(val hasNotes: Boolean, val unsent: Boolean)

        fun badgeOf(pdf: File): Badge = synchronized(ScoreNotesFile.lock) {
            val personal = ScoreNotesFile.read(ScoreNotesFile.fileOf(pdf))
            val conductor = ScoreNotesFile.read(ScoreNotesFile.conductorFileOf(pdf))
            // 개인 메모는 늘 쓸 수 있다 — 받은 뒤(base)와 다르면 올릴 것이 있다
            val personalUnsent = personal != null && personal.ids != (personal.sync?.baseIds ?: emptySet<String>())
            val conductorUnsent = conductor != null && conductor.sync?.writable == true && conductor.ids != conductor.sync.baseIds
            Badge(
                hasNotes = personal?.ids.orEmpty().isNotEmpty() || conductor?.ids.orEmpty().isNotEmpty(),
                unsent = personalUnsent || conductorUnsent,
            )
        }

        fun parseList(body: String): NotesList {
            val root = JsonParser.parseString(body).asJsonObject
            val revisions = root.getAsJsonArray("notes")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val layer = o.get("layer")?.asString ?: return@mapNotNull null
                (o.get("score_id").asLong to layer) to o.get("revision").asInt
            }?.toMap().orEmpty()
            val writable = root.getAsJsonArray("conductor_writable")?.map { it.asLong }?.toSet().orEmpty()
            return NotesList(revisions, writable)
        }
    }
}
