package com.mrgq.pdfviewer.notes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * 메모 곁 파일 (P11 §3.4) — PDF 옆 같은 이름에 `.notes.json`. `.musicxml` · `.layout.json` 처럼 **악보 파일을 따라다닌다**:
 * ScoreMate 동기화가 PDF 를 옮기면 함께 옮기고, 사용자가 PDF 를 지우면 함께 지운다. 곡목에서 빠져 동기화가 지울 때는
 * 다시 받으면 돌아오게 맡겨 둔다(`ScoreMateSync` 의 보관함).
 *
 * ```json
 * {"format": 1, "pdf_sha256": "…",
 *  "notes": [{"id": "9f2c41d07ab35e88", "page": 0, "type": "ink", "color": "#FFE53935", "width": 1.5, "staff": 0, "strokes": [[x, y, …], …]},
 *            {"id": "…", "page": 0, "type": "text", "color": "#FF1E88E5", "size": 11, "x": 72, "y": 140, "text": "rit.", "staff": 1}]}
 * ```
 * 글자: x, y = 상자 왼 위, size = 글자 크기(pt), 여러 줄은 \n, 첫 줄 기준선 y + 0.8 × size · 줄 간격 1.2 × size ([TextLayout]).
 * 좌표 · 굵기는 원본 PDF pt(소수 한 자리). `pdf_sha256` 은 쓴 때의 PDF 내용 — 다르면 새 판이라 자리가 어긋날 수 있다.
 * 모르는 `type`(뒤 판에서 더할 것)은 읽을 때 [Loaded.unknown] 으로 그대로 들고 있다가 쓸 때 돌려 넣는다 — 옛 앱이 지우지 않게.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object ScoreNotesFile {

    const val FORMAT = 1
    const val SUFFIX = ".notes.json"

    fun fileOf(pdf: File): File = File(pdf.parentFile, pdf.nameWithoutExtension + SUFFIX)

    const val CONDUCTOR_SUFFIX = ".conductor.notes.json"

    /** 지휘자 메모 (앙상블 악보, 서버 conductor 겹) — 개인 메모와 따로 */
    fun conductorFileOf(pdf: File): File = File(pdf.parentFile, pdf.nameWithoutExtension + CONDUCTOR_SUFFIX)

    data class Loaded(
        val notes: List<ScoreNote>,
        val pdfSha256: String?,
        val unknown: List<JsonObject> = emptyList(),
        /** 서버와 마지막으로 맞춘 상태 — 이 기기만의 것(서버로 보내지 않는다). 동기화 전이면 null */
        val sync: SyncState? = null,
    ) {
        /** 모든 메모의 id (모르는 type 포함) */
        val ids: Set<String> get() = notes.map { it.id }.toSet() + unknown.mapNotNull { it.get("id")?.takeIf { e -> e.isJsonPrimitive }?.asString }
    }

    /**
     * ScoreMate 개인 메모 동기화 상태(P11 §6) — 서버 문서의 [revision] 과 그때 있던 메모 id([baseIds]).
     * 합치기 = 서버 ∪ (지금 − base: 내가 더한 것) − (base − 지금: 내가 지운 것). 메모는 불변이라 id 집합만으로 된다
     */
    data class SyncState(
        val revision: Int,
        val baseIds: Set<String>,
        /** 지휘자 메모: 이 기기 사용자가 쓸 수 있나 (서버 `conductor_writable`, 동기화마다 갱신) */
        val writable: Boolean = false,
    )

    /** 없거나 읽지 못하면 null (깨진 파일은 덮어쓰지 않게 호출한 쪽이 판단) */
    fun read(file: File): Loaded? {
        if (!file.isFile) return null
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String): Loaded {
        val root = JsonParser.parseString(text).asJsonObject
        val notes = ArrayList<ScoreNote>()
        val unknown = ArrayList<JsonObject>()
        root.getAsJsonArray("notes")?.forEach { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val note = when (o.get("type")?.asString) {
                "ink" -> ScoreNote.Ink(
                    id = o.get("id").asString,
                    page = o.get("page").asInt,
                    color = parseColor(o.get("color").asString),
                    widthPt = o.get("width").asFloat,
                    // "strokes": [[x, y, …], …]. v0.6.0-beta.1 · beta.2 는 획 하나를 "points" 로 썼다 — 그대로 읽는다
                    strokes = o.getAsJsonArray("strokes")?.map { s -> s.asJsonArray.map { it.asFloat } }
                        ?: listOf(o.getAsJsonArray("points").map { it.asFloat }),
                    staff = o.get("staff")?.takeIf { !it.isJsonNull }?.asInt,
                    author = authorOf(o),
                )
                "text" -> ScoreNote.Text(
                    id = o.get("id").asString,
                    page = o.get("page").asInt,
                    color = parseColor(o.get("color").asString),
                    sizePt = o.get("size").asFloat,
                    x = o.get("x").asFloat,
                    y = o.get("y").asFloat,
                    text = o.get("text").asString,
                    staff = o.get("staff")?.takeIf { !it.isJsonNull }?.asInt,
                    author = authorOf(o),
                )
                else -> null
            }
            if (note != null) notes += note else unknown += o
        }
        val sha = root.get("pdf_sha256")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
        val sync = root.get("sync")?.takeIf { it.isJsonObject }?.asJsonObject?.let { o ->
            SyncState(
                o.get("revision").asInt,
                o.getAsJsonArray("base_ids")?.map { it.asString }?.toSet().orEmpty(),
                o.get("writable")?.asBoolean ?: false,
            )
        }
        return Loaded(notes, sha, unknown, sync)
    }

    /** 곁 파일 내용. [withSync] 가 아니면(서버로 보낼 때) 동기화 상태는 빼고 */
    fun format(loaded: Loaded, withSync: Boolean = true): String = formatJson(loaded, withSync).toString()

    fun formatJson(loaded: Loaded, withSync: Boolean = true): JsonObject {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        loaded.pdfSha256?.let { root.addProperty("pdf_sha256", it) }
        val array = JsonArray()
        for (note in loaded.notes) {
            val o = JsonObject()
            o.addProperty("id", note.id)
            o.addProperty("page", note.page)
            when (note) {
                is ScoreNote.Ink -> {
                    o.addProperty("type", "ink")
                    o.addProperty("color", formatColor(note.color))
                    o.addProperty("width", round1(note.widthPt))
                    note.staff?.let { o.addProperty("staff", it) }
                    o.add("strokes", JsonArray().apply {
                        note.strokes.forEach { s -> add(JsonArray().apply { s.forEach { add(round1(it)) } }) }
                    })
                }
                is ScoreNote.Text -> {
                    o.addProperty("type", "text")
                    o.addProperty("color", formatColor(note.color))
                    o.addProperty("size", round1(note.sizePt))
                    o.addProperty("x", round1(note.x))
                    o.addProperty("y", round1(note.y))
                    o.addProperty("text", note.text)
                    note.staff?.let { o.addProperty("staff", it) }
                }
            }
            note.author?.let { name -> o.add("author", JsonObject().apply { addProperty("name", name) }) }
            array.add(o)
        }
        loaded.unknown.forEach { array.add(it) }
        root.add("notes", array)
        if (withSync) loaded.sync?.let { st ->
            root.add("sync", JsonObject().apply {
                addProperty("revision", st.revision)
                if (st.writable) addProperty("writable", true)
                add("base_ids", JsonArray().apply { st.baseIds.sorted().forEach { add(it) } })
            })
        }
        return root
    }

    /** 합치기 (P11 §6) — [remote] ∪ (local − base) − (base − local). 같은 id 는 서버 것(메모는 불변이라 내용이 같다) */
    fun merge(remote: Loaded, local: Loaded, baseIds: Set<String>): Loaded {
        val localIds = local.ids
        val added = localIds - baseIds
        val deleted = baseIds - localIds
        val remoteIds = remote.ids
        fun JsonObject.id() = get("id")?.takeIf { it.isJsonPrimitive }?.asString
        return remote.copy(
            notes = remote.notes.filter { it.id !in deleted } + local.notes.filter { it.id in added && it.id !in remoteIds },
            unknown = remote.unknown.filter { it.id() !in deleted } + local.unknown.filter { it.id() in added && it.id() !in remoteIds },
        )
    }

    /**
     * 다른 이름으로 쓴 뒤 바꿔 끼운다 — 쓰다 죽어도 앞의 것이 남는다. 메모가 하나도 없으면 파일을 지운다 —
     * 단 서버와 맞춘 적이 있으면 남긴다(빈 문서를 올려야 서버에서도 지워진다)
     */
    fun write(file: File, loaded: Loaded) {
        if (loaded.notes.isEmpty() && loaded.unknown.isEmpty() && loaded.sync == null) {
            file.delete()
            return
        }
        val temp = File(file.path + ".tmp")
        temp.writeText(format(loaded))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                throw java.io.IOException("메모를 저장하지 못했습니다: ${file.name}")
            }
        }
    }

    // ── 열기 · 저장 — 한 줄로 세운 스레드 하나 (순서가 지켜지고, 뷰어를 닫아도 마저 쓴다) ──────────────
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "score-notes").apply { isDaemon = true } }

    /** 곁 파일을 읽고 쓰는 일은 이 잠금 안에서 — 뷰어의 저장 스레드와 동기화가 겹치지 않게 */
    val lock = Any()
    private val shaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [broken] = 곁 파일이 있는데 읽지 못함 — 덮어쓰지 않는다. [newEdition] = 메모를 쓴 뒤 PDF 가 바뀜(새 판) */
    data class Opened(val loaded: Loaded, val newEdition: Boolean, val broken: Boolean)

    /** [done] 은 이 스레드에서 불린다 */
    fun openAsync(pdf: File, done: (Opened) -> Unit) = openAsync(fileOf(pdf), pdf, done)

    fun openAsync(file: File, pdf: File, done: (Opened) -> Unit) = io.execute { synchronized(lock) { open(file, pdf, done) } }

    private fun open(file: File, pdf: File, done: (Opened) -> Unit) {
        val loaded = read(file)
        if (loaded == null) {
            done(Opened(Loaded(emptyList(), null), newEdition = false, broken = file.exists()))
            return
        }
        val now = loaded.pdfSha256?.let { currentSha(pdf) }
        if (now != null && now != loaded.pdfSha256) {
            // 알렸으니 지금 판으로 적어 둔다 — 다음에 또 묻지 않게
            runCatching { write(file, loaded.copy(pdfSha256 = now)) }
            done(Opened(loaded.copy(pdfSha256 = now), newEdition = true, broken = false))
        } else {
            done(Opened(loaded, newEdition = false, broken = false))
        }
    }

    /** 뷰어의 저장 — 메모만 바꾸고 동기화 상태(이 파일에 있던 것)는 그대로 둔다 */
    fun saveAsync(pdf: File, notes: List<ScoreNote>, unknown: List<JsonObject>, onError: (Exception) -> Unit) =
        saveAsync(fileOf(pdf), pdf, notes, unknown, onError)

    /** [file] = 개인([fileOf]) 또는 지휘자([conductorFileOf]) 곁 파일 */
    fun saveAsync(file: File, pdf: File, notes: List<ScoreNote>, unknown: List<JsonObject>, onError: (Exception) -> Unit) = io.execute {
        synchronized(lock) {
            try {
                write(file, Loaded(notes, currentSha(pdf), unknown, read(file)?.sync))
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /** 지금 PDF 의 SHA-256 — 경로 · 크기 · 수정 시각이 같으면 다시 계산하지 않는다 */
    private fun currentSha(pdf: File): String? = try {
        shaCache.getOrPut("${pdf.path}|${pdf.length()}|${pdf.lastModified()}") { sha256Of(pdf) }
    } catch (e: Exception) {
        null
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

    /** 서버가 찍은 쓴 사람 `{"id", "name"}` 의 이름 */
    private fun authorOf(o: JsonObject): String? =
        o.get("author")?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.takeIf { it.isJsonPrimitive }?.asString

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

    private fun formatColor(argb: Int): String = "#%08X".format(argb)

    private fun parseColor(s: String): Int = s.removePrefix("#").toLong(16).let {
        if (s.removePrefix("#").length <= 6) (it or 0xFF000000L).toInt() else it.toInt()
    }
}
