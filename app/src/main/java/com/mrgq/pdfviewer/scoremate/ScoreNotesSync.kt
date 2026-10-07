package com.mrgq.pdfviewer.scoremate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.notes.ScoreNotesFile
import com.mrgq.pdfviewer.notes.ScoreNotesFile.Loaded
import com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState
import java.io.File

/**
 * 악보 메모 서버 동기화 (P11 §6, 서버 0.17.0 · 서버 devlog 076) — **개인 메모(personal)** 를 올리고 받는다.
 * 악보 동기화([ScoreMateSync.sync]) 바로 뒤에 한 번. 지휘자 메모(conductor)는 아직 — 보이기 · 쓰기 화면과 함께.
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
class ScoreNotesSync(private val client: ScoreMateClient) {

    data class Result(val uploaded: Int = 0, val downloaded: Int = 0, val errors: List<String> = emptyList())

    /** [scores] = 이 기기에 있는 ScoreMate 악보 (숨긴 것 빼고) */
    suspend fun sync(scores: List<SyncedScore>): Result {
        val listBody = client.fetchNotesList() ?: return Result() // 메모를 모르는 서버
        val remoteRevisions = parseList(listBody)
        var up = 0
        var down = 0
        val errors = mutableListOf<String>()
        for (score in scores) {
            if (score.hidden) continue
            val pdf = File(score.filePath)
            if (!pdf.isFile) continue
            try {
                when (syncOne(score, pdf, remoteRevisions[score.serverId])) {
                    Outcome.UPLOADED -> up++
                    Outcome.DOWNLOADED -> down++
                    Outcome.NONE -> Unit
                }
            } catch (e: ScoreMateUnlinkedException) {
                throw e
            } catch (e: Exception) {
                errors += "${score.title} 메모: ${e.message}"
            }
        }
        return Result(up, down, errors)
    }

    private enum class Outcome { NONE, UPLOADED, DOWNLOADED }

    private suspend fun syncOne(score: SyncedScore, pdf: File, remoteRevision: Int?): Outcome {
        val file = ScoreNotesFile.fileOf(pdf)
        val local = synchronized(ScoreNotesFile.lock) {
            if (file.exists()) ScoreNotesFile.read(file) ?: throw ScoreMateException("메모 파일을 읽지 못했습니다")
            else Loaded(emptyList(), null)
        }
        val base = local.sync ?: SyncState(0, emptySet())
        val dirty = local.ids != base.baseIds

        if (remoteRevision == null) {
            // 서버에 없음 — 처음 만들기. 맞춘 적이 있는데 목록에 없으면(범위 밖 등) 건드리지 않는다
            if (base.revision != 0 || !dirty) return Outcome.NONE
            return upload(score, file, local, local, base, baseRevision = 0)
        }
        if (remoteRevision == base.revision) {
            return if (dirty) upload(score, file, local, local, base, baseRevision = base.revision) else Outcome.NONE
        }
        // 다른 기기가 고쳤다 — 받아서 합친다
        val response = client.getNotes(score.serverId, LAYER)
        if (response.code == 404) {
            return if (dirty) upload(score, file, local, local, base, baseRevision = 0) else Outcome.NONE
        }
        if (response.code != 200) throw ScoreMateException("메모를 받지 못했습니다 (HTTP ${response.code})")
        val remote = parseDoc(response.body)
        val merged = ScoreNotesFile.merge(remote.loaded, local, base.baseIds)
        if (merged.ids == remote.loaded.ids) {
            return if (writeLocal(file, local, remote.loaded, remote.revision)) Outcome.DOWNLOADED else Outcome.NONE
        }
        return upload(score, file, local, merged, base, baseRevision = remote.revision)
    }

    /** [doc] 을 올린다. 409 면 서버 것과 다시 합쳐 몇 번까지 */
    private suspend fun upload(score: SyncedScore, file: File, local: Loaded, doc: Loaded, base: SyncState, baseRevision: Int): Outcome {
        var body = doc
        var revision = baseRevision
        repeat(MAX_ATTEMPTS) {
            val request = ScoreNotesFile.formatJson(body.copy(pdfSha256 = score.sha256, sync = null), withSync = false)
            request.addProperty("base_revision", revision)
            val response = client.putNotes(score.serverId, LAYER, request.toString())
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
        ScoreNotesFile.write(file, doc.copy(pdfSha256 = doc.pdfSha256 ?: now.pdfSha256, sync = SyncState(revision, doc.ids)))
        true
    }

    private class Doc(val loaded: Loaded, val revision: Int)

    private fun parseDoc(body: String): Doc = docOf(JsonParser.parseString(body).asJsonObject)

    private fun docOf(o: JsonObject): Doc = Doc(ScoreNotesFile.parse(o.toString()).copy(sync = null), o.get("revision").asInt)

    companion object {
        const val LAYER = "personal"
        private const val MAX_ATTEMPTS = 3

        /** 목록에서 개인 메모의 score_id → revision */
        fun parseList(body: String): Map<Long, Int> {
            val root = JsonParser.parseString(body).asJsonObject
            return root.getAsJsonArray("notes")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                if (o.get("layer")?.asString != LAYER) return@mapNotNull null
                o.get("score_id").asLong to o.get("revision").asInt
            }?.toMap().orEmpty()
        }
    }
}
