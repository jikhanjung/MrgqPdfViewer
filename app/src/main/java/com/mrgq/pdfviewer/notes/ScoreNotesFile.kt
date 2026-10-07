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
 *  "notes": [{"id": "9f2c41d07ab35e88", "page": 0, "type": "ink", "color": "#FFE53935", "width": 1.5, "staff": 0, "points": [x, y, …]}]}
 * ```
 * 좌표 · 굵기는 원본 PDF pt(소수 한 자리). `pdf_sha256` 은 쓴 때의 PDF 내용 — 다르면 새 판이라 자리가 어긋날 수 있다.
 * 모르는 `type`(뒤 판에서 더할 글자 등)은 읽을 때 [Loaded.unknown] 으로 그대로 들고 있다가 쓸 때 돌려 넣는다 — 옛 앱이 지우지 않게.
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object ScoreNotesFile {

    const val FORMAT = 1
    const val SUFFIX = ".notes.json"

    fun fileOf(pdf: File): File = File(pdf.parentFile, pdf.nameWithoutExtension + SUFFIX)

    data class Loaded(
        val notes: List<ScoreNote>,
        val pdfSha256: String?,
        val unknown: List<JsonObject> = emptyList(),
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
                    points = o.getAsJsonArray("points").map { it.asFloat },
                    staff = o.get("staff")?.takeIf { !it.isJsonNull }?.asInt,
                )
                else -> null
            }
            if (note != null) notes += note else unknown += o
        }
        val sha = root.get("pdf_sha256")?.takeIf { !it.isJsonNull }?.asString
        return Loaded(notes, sha, unknown)
    }

    fun format(loaded: Loaded): String {
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
                    o.add("points", JsonArray().apply { note.points.forEach { add(round1(it)) } })
                }
            }
            array.add(o)
        }
        loaded.unknown.forEach { array.add(it) }
        root.add("notes", array)
        return root.toString()
    }

    /** 다른 이름으로 쓴 뒤 바꿔 끼운다 — 쓰다 죽어도 앞의 것이 남는다. 메모가 하나도 없으면 파일을 지운다 */
    fun write(file: File, loaded: Loaded) {
        if (loaded.notes.isEmpty() && loaded.unknown.isEmpty()) {
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
    private val shaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [broken] = 곁 파일이 있는데 읽지 못함 — 덮어쓰지 않는다. [newEdition] = 메모를 쓴 뒤 PDF 가 바뀜(새 판) */
    data class Opened(val loaded: Loaded, val newEdition: Boolean, val broken: Boolean)

    /** [done] 은 이 스레드에서 불린다 */
    fun openAsync(pdf: File, done: (Opened) -> Unit) = io.execute {
        val file = fileOf(pdf)
        val loaded = read(file)
        if (loaded == null) {
            done(Opened(Loaded(emptyList(), null), newEdition = false, broken = file.exists()))
            return@execute
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

    fun saveAsync(pdf: File, notes: List<ScoreNote>, unknown: List<JsonObject>, onError: (Exception) -> Unit) = io.execute {
        try {
            write(fileOf(pdf), Loaded(notes, currentSha(pdf), unknown))
        } catch (e: Exception) {
            onError(e)
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

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

    private fun formatColor(argb: Int): String = "#%08X".format(argb)

    private fun parseColor(s: String): Int = s.removePrefix("#").toLong(16).let {
        if (s.removePrefix("#").length <= 6) (it or 0xFF000000L).toInt() else it.toInt()
    }
}
