package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.scoremate.HttpRequest
import com.mrgq.pdfviewer.scoremate.HttpResponse
import com.mrgq.pdfviewer.scoremate.HttpTransport
import com.mrgq.pdfviewer.scoremate.LocalPdfRecords
import com.mrgq.pdfviewer.scoremate.ScoreMateClient
import com.mrgq.pdfviewer.scoremate.ScoreMateNaming
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.Tokens
import com.mrgq.pdfviewer.scoremate.ScoreMateSync
import com.mrgq.pdfviewer.scoremate.ScoreMateTokenStore
import com.mrgq.pdfviewer.scoremate.SyncedScore
import com.mrgq.pdfviewer.scoremate.SyncedScoreStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** ScoreMate 악보 동기화 (P05 C2, 서버 devlog 060) — 임시 폴더 + 가짜 서버 */
class ScoreMateSyncTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pdfs").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private class Tok : ScoreMateTokenStore {
        override val server = "https://sm.test"
        override var tokens: Tokens? = Tokens("a", "r", "d")
        override var syncCursor: String? = null
        override var syncFormat: Int = 0
        override var setlistsBody: String? = null
        override fun saveTokens(tokens: Tokens) {
            this.tokens = tokens
        }
        override fun clearTokens() {
            tokens = null
        }
    }

    private class Db : SyncedScoreStore {
        val rows = LinkedHashMap<Long, SyncedScore>()
        override suspend fun all() = rows.values.toList()
        override suspend fun upsert(score: SyncedScore) {
            rows[score.serverId] = score
        }
        override suspend fun delete(serverId: Long) {
            rows.remove(serverId)
        }
    }

    private class Records : LocalPdfRecords {
        val moves = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        override suspend fun beforeMove(oldPath: String, newPath: String) {
            moves += oldPath to newPath
        }
        override suspend fun afterDelete(path: String) {
            deletes += path
        }
    }

    private data class Score(
        val id: Long, var title: String, var content: String, var ensemble: String? = "현악 4중주",
        var part: String = "", var version: Int = 1, var processing: Boolean = false,
        /** 서버가 인식한 MusicXML (P06 §11). null = 없음 */
        var xml: String? = null,
        /** 서버 분석 파일 (P06 §12). null = 없음 */
        var layout: String? = null,
        var composer: String = "",
        var arranger: String = "",
    )

    /** 서버 흉내: 커서 = 마지막으로 보낸 변경 번호. 받기는 302 → 서명 URL(인증 없이만 받아 준다) */
    private class Server : HttpTransport {
        val scores = LinkedHashMap<Long, Score>()
        val changes = mutableListOf<Long>() // 변경 순서 (id)
        var pageSize = 50
        var downloads = 0
        var xmlDownloads = 0
        var layoutDownloads = 0
        var signedWithAuth = 0

        fun put(score: Score) {
            scores[score.id] = score
            changes += score.id
        }

        fun remove(id: Long) {
            scores.remove(id)
        }

        var setlists = """{"setlists":[]}"""
        var setlistsFail = false

        // ── 메모 API (서버 0.17.0) ──
        var notesEnabled = false
        /** score_id → (revision, notes 배열 JSON) */
        val notesDocs = LinkedHashMap<Long, Pair<Int, String>>()
        /** 목록에서 빼는 악보 (범위 밖 · 동시에 만들어진 직후) */
        var notesHidden = setOf<Long>()
        /** 목록에 거짓 revision 을 싣는다 (서버에 없는데 있는 것처럼) */
        var notesListOverride = mapOf<Long, Int>()
        val notePuts = mutableListOf<Pair<Long, Int>>() // (score, base_revision)
        // 지휘자 겹 — score_id → (revision, notes), 쓸 수 있는 악보, 목록은 쓸 수 있다고 해도 PUT 은 막기(권한이 막 바뀜)
        val conductorDocs = LinkedHashMap<Long, Pair<Int, String>>()
        var conductorWritable = setOf<Long>()
        var conductorForbidden = false
        val conductorPuts = mutableListOf<Pair<Long, Int>>()

        fun notesDoc(id: Long): String {
            val (rev, notes) = notesDocs.getValue(id)
            return """{"layer":"personal","revision":$rev,"pdf_sha256":null,"updated_at":"t","updated_by":"u","format":1,"notes":$notes}"""
        }

        private fun notesApi(request: HttpRequest, path: String): HttpResponse? {
            if (path.startsWith("/api/v1/sync/notes/")) {
                if (!notesEnabled) return HttpResponse(404, "")
                val rows = (notesDocs.mapValues { it.value.first } + notesListOverride).filterKeys { it !in notesHidden }
                    .map { (id, rev) -> """{"score_id":$id,"layer":"personal","revision":$rev,"updated_at":"t","pdf_sha256":null}""" } +
                    conductorDocs.map { (id, d) -> """{"score_id":$id,"layer":"conductor","revision":${d.first},"updated_at":"t","pdf_sha256":null}""" }
                return HttpResponse(200, """{"notes":[${rows.joinToString(",")}],"conductor_writable":[${conductorWritable.joinToString(",")}]}""")
            }
            Regex("/api/v1/scores/([0-9]+)/notes/conductor/").find(path)?.let { c ->
                val id = c.groupValues[1].toLong()
                fun doc() = conductorDocs.getValue(id).let { (rev, notes) ->
                    """{"layer":"conductor","revision":$rev,"pdf_sha256":null,"updated_at":"t","updated_by":"A","format":1,"notes":$notes}"""
                }
                if (request.method == "GET") return if (id in conductorDocs) HttpResponse(200, doc()) else HttpResponse(404, "")
                if (conductorForbidden || id !in conductorWritable) return HttpResponse(403, """{"error":"forbidden"}""")
                val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
                val base = body.get("base_revision").asInt
                conductorPuts += id to base
                val cur = conductorDocs[id]
                if ((cur?.first ?: 0) != base) return HttpResponse(409, """{"error":"conflict","current":${if (cur == null) "null" else doc()}}""")
                // 서버가 쓴 사람을 찍는다 — 보낸 author 는 버리고, 있던 id 는 그대로
                val before = cur?.second?.let { com.google.gson.JsonParser.parseString(it).asJsonArray.associate { e ->
                    e.asJsonObject.get("id").asString to e.asJsonObject.get("author") } }.orEmpty()
                val notes = body.getAsJsonArray("notes")
                notes.forEach { e ->
                    val o = e.asJsonObject
                    o.add("author", before[o.get("id").asString] ?: com.google.gson.JsonParser.parseString("""{"id":7,"name":"리더"}"""))
                }
                conductorDocs[id] = (base + 1) to notes.toString()
                return HttpResponse(200, doc())
            }
            val m = Regex("/api/v1/scores/([0-9]+)/notes/personal/").find(path) ?: return null
            val id = m.groupValues[1].toLong()
            if (request.method == "GET") return if (id in notesDocs) HttpResponse(200, notesDoc(id)) else HttpResponse(404, "")
            val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
            val base = body.get("base_revision").asInt
            notePuts += id to base
            val cur = notesDocs[id]
            if (cur == null && base != 0) return HttpResponse(409, """{"error":"conflict","message":"x","current":null}""")
            if (cur != null && cur.first != base) return HttpResponse(409, """{"error":"conflict","message":"x","current":${notesDoc(id)}}""")
            notesDocs[id] = (base + 1) to body.getAsJsonArray("notes").toString()
            return HttpResponse(200, notesDoc(id))
        }

        override fun execute(request: HttpRequest): HttpResponse {
            val path = request.url.removePrefix("https://sm.test")
            notesApi(request, path)?.let { return it }
            if (path.startsWith("/api/v1/sync/setlists/")) return if (setlistsFail) HttpResponse(500, "") else HttpResponse(200, setlists)
            if (!path.startsWith("/api/v1/sync/scores/")) return HttpResponse(404, "")
            val cursor = Regex("cursor=([0-9]+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
            if (path.contains("cursor=bad")) return HttpResponse(400, """{"cursor":"Invalid cursor"}""")
            val end = minOf(changes.size, cursor + pageSize)
            val page = changes.subList(cursor, end).distinct().mapNotNull { scores[it] }
            val items = page.joinToString(",") { s ->
                val ensemble = s.ensemble?.let { """{"id":${it.hashCode().toLong() and 0xffff},"name":"$it"}""" } ?: "null"
                val sha = if (s.processing) "null" else "\"${sha(s.content)}\""
                """{"id":${s.id},"title":"${s.title}","composer":"${s.composer}","arranger":"${s.arranger}","part_name":"${s.part}","ensemble":$ensemble,
                   "version":{"number":${s.version},"sha256":$sha,"size_bytes":${s.content.length}},
                   "download_url":"http://internal:8000/api/v1/scores/${s.id}/download/",
                   "musicxml":${xmlJson(s)},"layout":${layoutJson(s)}}"""
            }
            val ids = scores.keys.joinToString(",")
            return HttpResponse(200, """{"cursor":"$end","has_more":${end < changes.size},"scores":[$items],"ids":[$ids]}""")
        }

        override fun download(request: HttpRequest, target: File): HttpResponse {
            val path = request.url.removePrefix("https://sm.test")
            Regex("/api/v1/scores/([0-9]+)/download/").find(path)?.let {
                if (request.bearer == null) return HttpResponse(401, "")
                return HttpResponse(302, "", "/signed/${it.groupValues[1]}?sig=x")
            }
            Regex("/api/v1/scores/([0-9]+)/musicxml/").find(path)?.let {
                if (request.bearer == null) return HttpResponse(401, "")
                return HttpResponse(302, "", "/signed-xml/${it.groupValues[1]}?sig=x")
            }
            Regex("/api/v1/scores/([0-9]+)/layout/").find(path)?.let {
                if (request.bearer == null) return HttpResponse(401, "")
                return HttpResponse(302, "", "/signed-layout/${it.groupValues[1]}?sig=x")
            }
            Regex("/signed-layout/([0-9]+)").find(path)?.let {
                if (request.bearer != null) signedWithAuth++
                layoutDownloads++
                target.parentFile?.mkdirs()
                target.writeText(scores.getValue(it.groupValues[1].toLong()).layout!!)
                return HttpResponse(200, "")
            }
            Regex("/signed-xml/([0-9]+)").find(path)?.let {
                if (request.bearer != null) signedWithAuth++
                xmlDownloads++
                target.parentFile?.mkdirs()
                target.writeText(scores.getValue(it.groupValues[1].toLong()).xml!!)
                return HttpResponse(200, "")
            }
            Regex("/signed/([0-9]+)").find(path)?.let {
                if (request.bearer != null) signedWithAuth++
                downloads++
                target.parentFile?.mkdirs()
                target.writeText(scores.getValue(it.groupValues[1].toLong()).content)
                return HttpResponse(200, "")
            }
            return HttpResponse(404, "")
        }

        companion object {
            /** 동기화 응답의 musicxml (P06 §11) */
            fun xmlJson(s: Score): String = s.xml?.let { x ->
                "{\"url\":\"http://internal:8000/api/v1/scores/${s.id}/musicxml/\",\"sha256\":\"${sha(x)}\"," +
                    "\"size_bytes\":${x.length},\"filename\":\"x.musicxml\",\"parts\":[\"A\"],\"measures\":4}"
            } ?: "null"

            /** 동기화 응답의 layout (P06 §12) */
            fun layoutJson(s: Score): String = s.layout?.let { x ->
                "{\"url\":\"http://internal:8000/api/v1/scores/${s.id}/layout/\",\"sha256\":\"${sha(x)}\"," +
                    "\"size_bytes\":${x.length},\"analyzer_version\":\"1+app.x\",\"pdf_sha256\":\"${sha(s.content)}\"}"
            } ?: "null"

            fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private val server = Server()
    private val tokens = Tok()
    private val db = Db()
    private val records = Records()
    private fun sync() = ScoreMateSync(ScoreMateClient(tokens, server), tokens, db, records, root)
    private fun file(vararg parts: String) = File(root, (listOf("ScoreMate") + parts).joinToString("/"))

    @Test
    fun 처음_동기화는_앙상블_폴더에_받는다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        server.put(Score(2, "아리랑", "a", ensemble = null, part = "바이올린 1"))
        val report = sync().sync()
        assertEquals(2, report.downloaded)
        assertEquals("v1", file("현악 4중주", "몰다우.pdf").readText())
        assertEquals("a", file(ScoreMateNaming.PERSONAL_FOLDER, "아리랑 (바이올린 1).pdf").readText())
        assertEquals("2", tokens.syncCursor)
        assertEquals("서명 URL 에는 Authorization 을 싣지 않는다", 0, server.signedWithAuth)
        assertEquals(2, PdfLibrary.listPdfFiles(root, scoreMate = true).size)
    }

    @Test
    fun 두_번째는_받을_것이_없다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        val again = sync().sync()
        assertFalse(again.changed)
        assertEquals(1, server.downloads)
    }

    @Test
    fun 새_판은_같은_자리에_바꾼다() = runBlocking {
        val s = Score(1, "몰다우", "v1")
        server.put(s)
        sync().sync()
        s.content = "v2"
        s.version = 2
        server.put(s)
        val report = sync().sync()
        assertEquals(1, report.downloaded)
        assertEquals("v2", file("현악 4중주", "몰다우.pdf").readText())
        assertEquals(2, db.rows.getValue(1).versionNumber)
        assertFalse(File(file("현악 4중주", "몰다우.pdf").path + ".part").exists())
    }

    @Test
    fun 제목이_바뀌면_레코드를_먼저_옮기고_파일을_옮긴다() = runBlocking {
        val s = Score(1, "몰다우", "v1")
        server.put(s)
        sync().sync()
        s.title = "블타바"
        server.put(s)
        val report = sync().sync()
        assertEquals(1, report.moved)
        assertEquals(0, report.downloaded)
        assertFalse(file("현악 4중주", "몰다우.pdf").exists())
        assertEquals("v1", file("현악 4중주", "블타바.pdf").readText())
        assertEquals(listOf(file("현악 4중주", "몰다우.pdf").path to file("현악 4중주", "블타바.pdf").path), records.moves)
    }

    @Test
    fun 같은_이름이_둘이면_둘_다_id_를_붙인다() = runBlocking {
        server.put(Score(1, "아리랑", "x"))
        server.put(Score(2, "아리랑", "y"))
        sync().sync()
        assertEquals("x", file("현악 4중주", "아리랑 [#1].pdf").readText())
        assertEquals("y", file("현악 4중주", "아리랑 [#2].pdf").readText())
        // 하나가 사라지면 남은 것은 원래 이름으로
        server.remove(2)
        server.put(server.scores.getValue(1))
        sync().sync()
        assertEquals("x", file("현악 4중주", "아리랑.pdf").readText())
        assertFalse(file("현악 4중주", "아리랑 [#2].pdf").exists())
    }

    @Test
    fun 서버에서_사라진_악보는_지운다() = runBlocking {
        (1L..4L).forEach { server.put(Score(it, "곡$it", "c$it")) }
        sync().sync()
        server.remove(4)
        server.put(Score(5, "곡5", "c5"))
        val report = sync().sync()
        assertEquals(1, report.removed)
        assertFalse(file("현악 4중주", "곡4.pdf").exists())
        assertTrue("파일별 설정(레코드)은 남긴다 — 다시 곡목에 들어오면 돌아오게", records.deletes.isEmpty())
        assertNull(db.rows[4])
    }

    @Test
    fun 곡목을_바꿔_한꺼번에_빠져도_묻지_않고_정리한다() = runBlocking {
        (1L..4L).forEach { server.put(Score(it, "곡$it", "c$it")) }
        sync().sync()
        (1L..4L).forEach { server.remove(it) }
        server.put(Score(5, "곡5", "c5"))
        val report = sync().sync()
        assertEquals(4, report.removed)
        assertEquals(1, report.downloaded)
        assertEquals(listOf("곡5.pdf"), PdfLibrary.listPdfFiles(root, scoreMate = true).map { it.name })
        // 빈 목록(고른 세트리스트가 없음)도 그대로 따른다
        server.remove(5)
        assertEquals(1, sync().sync().removed)
        assertFalse(file("현악 4중주").exists())
    }

    @Test
    fun 처리_중인_악보는_건너뛰고_다음에_받는다() = runBlocking {
        val s = Score(1, "몰다우", "v1", processing = true)
        server.put(s)
        assertEquals(1, sync().sync().pending)
        assertFalse(file("현악 4중주", "몰다우.pdf").exists())
        s.processing = false
        server.put(s) // 서버: 처리가 끝나면 updated_at 이 바뀌어 다시 온다
        assertEquals(1, sync().sync().downloaded)
    }

    @Test
    fun TV_에서_지운_악보는_숨기고_다시_받지_않는다() = runBlocking {
        val s = Score(1, "몰다우", "v1")
        server.put(s)
        val sync = sync()
        sync.sync()
        val path = file("현악 4중주", "몰다우.pdf")
        path.delete()
        assertTrue(sync.markHidden(path.path))
        s.content = "v2"
        server.put(s)
        sync.sync()
        assertFalse("숨긴 악보는 새 판도 받지 않는다", path.exists())
        assertEquals(1, sync.unhideAll())
        sync.sync()
        assertEquals("v2", path.readText())
    }

    @Test
    fun 깨진_커서면_처음부터() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        tokens.syncCursor = "bad"
        assertEquals(1, sync().sync().downloaded)
    }

    @Test
    fun 쪽이_여럿이면_끝까지_받는다() = runBlocking {
        server.pageSize = 2
        (1L..5L).forEach { server.put(Score(it, "곡$it", "c$it")) }
        assertEquals(5, sync().sync().downloaded)
        assertEquals("5", tokens.syncCursor)
    }

    @Test
    fun 연결을_끊을_때_남기면_이_기기_파일로_옮기고_레코드도_따라간다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        server.put(Score(2, "아리랑", "a", ensemble = null))
        File(root, "몰다우.pdf").writeText("원래 있던 로컬 파일")
        val sync = sync()
        sync.sync()
        sync.forgetAll(deleteFiles = false)
        assertEquals("원래 있던 로컬 파일", File(root, "몰다우.pdf").readText())
        assertEquals("이름이 겹치면 (2)", "v1", File(root, "몰다우 (2).pdf").readText())
        assertEquals("a", File(root, "아리랑.pdf").readText())
        assertFalse("ScoreMate 폴더는 비면 지운다", file().exists())
        assertTrue(records.moves.contains(file("현악 4중주", "몰다우.pdf").path to File(root, "몰다우 (2).pdf").path))
        assertTrue(db.rows.isEmpty())
        assertEquals(3, PdfLibrary.listPdfFiles(root, scoreMate = false).size)
    }

    @Test
    fun 연결을_끊을_때_지우기() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        val sync = sync()
        sync.sync()
        sync.forgetAll(deleteFiles = true)
        assertTrue(PdfLibrary.listPdfFiles(root, scoreMate = true).isEmpty())
        assertTrue(PdfLibrary.listPdfFiles(root, scoreMate = false).isEmpty())
        assertEquals(1, records.deletes.size)
    }

    @Test
    fun 세트리스트도_받아_저장하고_바뀌면_알린다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        server.setlists = """{"setlists":[{"id":3,"title":"10월 연주회","items":[{"score_id":1,"position":1,"notes":""}]}]}"""
        val first = sync().sync()
        assertTrue(first.setlistsChanged)
        assertEquals("10월 연주회", com.mrgq.pdfviewer.scoremate.ScoreMateSetlists.parse(tokens.setlistsBody).single().title)
        assertFalse("같으면 바뀐 것이 아니다", sync().sync().setlistsChanged)
        // 세트리스트를 못 받아도 악보 동기화는 된 것 — 저장한 세트리스트를 그대로 쓴다
        server.setlistsFail = true
        val failed = sync().sync()
        assertTrue(failed.errors.single().startsWith("세트리스트"))
        assertEquals(1, com.mrgq.pdfviewer.scoremate.ScoreMateSetlists.parse(tokens.setlistsBody).size)
    }

    @Test
    fun MusicXML_은_PDF_옆에_받고_같으면_다시_받지_않는다() = runBlocking {
        val s = Score(1, "몰다우", "v1", xml = "<score-partwise/>")
        server.put(s)
        val report = sync().sync()
        assertEquals(1, report.musicXml)
        assertEquals("<score-partwise/>", file("현악 4중주", "몰다우.musicxml").readText())
        assertEquals("서명 URL 에는 Authorization 을 싣지 않는다", 0, server.signedWithAuth)
        // 목록에는 PDF 만
        assertEquals(1, PdfLibrary.listPdfFiles(root, scoreMate = true).size)
        // 인식이 끝나 악보가 다시 와도(updated_at) 내용이 같으면 받지 않는다
        server.put(s)
        assertEquals(0, sync().sync().musicXml)
        assertEquals(1, server.xmlDownloads)
        // 결과가 바뀌면 다시
        s.xml = "<score-partwise version=\"4.0\"/>"
        server.put(s)
        assertEquals(1, sync().sync().musicXml)
        assertEquals(s.xml, file("현악 4중주", "몰다우.musicxml").readText())
    }

    @Test
    fun 옛_형식_커서면_한_번_처음부터_받아_지나간_MusicXML_을_받는다() = runBlocking {
        // 옛 앱(형식 0)이 인식 끝난 악보까지 커서를 넘겼다 — MusicXML 필드는 몰랐다
        val s = Score(1, "몰다우", "v1", xml = "<a/>")
        server.put(s)
        sync().sync()
        file("현악 4중주", "몰다우.musicxml").delete()
        tokens.syncFormat = 0
        val report = sync().sync()
        assertEquals(1, report.musicXml)
        assertEquals(0, report.downloaded) // PDF 는 sha 가 같아 다시 받지 않는다
        assertEquals(ScoreMateSync.SYNC_FORMAT, tokens.syncFormat)
        // 다음부터는 커서대로 — 다시 처음부터 받지 않는다
        assertEquals(0, sync().sync().musicXml)
    }

    @Test
    fun 분석_파일도_PDF_옆에_받고_바뀌면_다시_없어지면_지우고_PDF_를_따라간다() = runBlocking {
        val s = Score(1, "몰다우", "v1", layout = "{\"analyzer_version\":\"1\"}")
        server.put(s)
        val report = sync().sync()
        assertEquals(1, report.layouts)
        assertEquals(s.layout, file("현악 4중주", "몰다우.layout.json").readText())
        // 분석기 버전이 올라 파일이 바뀌면 다시
        s.layout = "{\"analyzer_version\":\"2\"}"
        server.put(s)
        assertEquals(1, sync().sync().layouts)
        assertEquals(2, server.layoutDownloads)
        // 이름이 바뀌면 따라 옮긴다
        s.title = "블타바"
        server.put(s)
        sync().sync()
        assertEquals(s.layout, file("현악 4중주", "블타바.layout.json").readText())
        assertFalse(file("현악 4중주", "몰다우.layout.json").exists())
        // 새 판이라 아직 분석 전이면 지운다
        s.content = "v2"; s.version = 2; s.layout = null
        server.put(s)
        sync().sync()
        assertFalse(file("현악 4중주", "블타바.layout.json").exists())
        assertEquals(1, PdfLibrary.listPdfFiles(root, scoreMate = true).size)
    }

    @Test
    fun 작곡가_편곡자를_저장하고_바뀌면_받지_않고_고친다() = runBlocking {
        val s = Score(1, "K488", "v1", composer = "Mozart", arranger = "전예완")
        server.put(s)
        sync().sync()
        assertEquals("Mozart" to "전예완", db.rows.getValue(1).let { it.composer to it.arranger })
        s.arranger = "전예완 · 김하진"
        server.put(s)
        val report = sync().sync()
        assertEquals("전예완 · 김하진", db.rows.getValue(1).arranger)
        assertEquals(0, report.downloaded) // 곡 정보만 바뀌면 파일은 다시 받지 않는다
        assertEquals(1, report.updated)    // 그래도 바뀐 것이라 목록을 다시 그린다
        assertTrue(report.changed)
        assertEquals(1, server.downloads)
    }

    @Test
    fun 새_판이라_MusicXML_이_없어지면_지운다() = runBlocking {
        val s = Score(1, "몰다우", "v1", xml = "<a/>")
        server.put(s)
        sync().sync()
        s.content = "v2"; s.version = 2; s.xml = null
        server.put(s)
        sync().sync()
        assertEquals("v2", file("현악 4중주", "몰다우.pdf").readText())
        assertFalse(file("현악 4중주", "몰다우.musicxml").exists())
    }

    @Test
    fun PDF_를_옮기거나_지우면_MusicXML_도_따라간다() = runBlocking {
        val s = Score(1, "몰다우", "v1", xml = "<a/>")
        server.put(s)
        sync().sync()
        s.title = "블타바"
        server.put(s)
        sync().sync()
        assertFalse(file("현악 4중주", "몰다우.musicxml").exists())
        assertEquals("<a/>", file("현악 4중주", "블타바.musicxml").readText())
        assertEquals(1, server.xmlDownloads) // 옮겼을 뿐 다시 받지 않았다
        server.remove(1)
        sync().sync()
        assertFalse(file("현악 4중주", "블타바.pdf").exists())
        assertFalse(file("현악 4중주", "블타바.musicxml").exists())
    }

    @Test
    fun 이름_규칙() {
        assertEquals("a_b_c", ScoreMateNaming.sanitize("a/b:c"))
        assertEquals("_", ScoreMateNaming.sanitize(" ... "))
        assertEquals("제목 없음", ScoreMateNaming.baseName("", ""))
        assertEquals(ScoreMateNaming.PERSONAL_FOLDER, ScoreMateNaming.folderName(null))
        assertEquals(80, ScoreMateNaming.sanitize("가".repeat(200)).length)
        assertEquals("/api/v1/scores/3/download/", ScoreMateSync.pathOf("http://internal:8000/api/v1/scores/3/download/"))
    }

    @Test
    fun 서재는_연결되면_ScoreMate_아니면_로컬_파일만() {
        File(root, "a.pdf").writeText("x")
        File(root, "other/b.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        file("앙상블", "c.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        file("앙상블", "d.pdf.part").writeText("x")
        assertEquals(listOf("c.pdf"), PdfLibrary.listPdfFiles(root, scoreMate = true).map { it.name })
        assertEquals(listOf("a.pdf"), PdfLibrary.listPdfFiles(root, scoreMate = false).map { it.name })
        assertEquals("앙상블", PdfLibrary.scoreMateGroupOf(root, file("앙상블", "c.pdf")))
        assertNull(PdfLibrary.scoreMateGroupOf(root, File(root, "a.pdf")))
    }

    // ── 메모 곁 파일 (P11) — 사용자 것이라 받거나 지우지 않고 PDF 를 따라다닌다 ──

    private fun notesOf(pdf: File) = File(pdf.parentFile, pdf.nameWithoutExtension + ".notes.json")

    @Test
    fun 메모는_이름이_바뀌어도_새_판이_와도_PDF_를_따라간다() = runBlocking {
        val s = Score(1, "몰다우", "v1", xml = "<x/>")
        server.put(s)
        sync().sync()
        notesOf(file("현악 4중주", "몰다우.pdf")).writeText("{\"notes\":[]}")
        s.title = "블타바"
        server.put(s)
        sync().sync()
        assertEquals("{\"notes\":[]}", notesOf(file("현악 4중주", "블타바.pdf")).readText())
        assertFalse(notesOf(file("현악 4중주", "몰다우.pdf")).exists())
        // 새 판 + MusicXML 없어짐 → MusicXML 은 지우고 메모는 남는다
        s.content = "v2"; s.version = 2; s.xml = null
        server.put(s)
        sync().sync()
        assertFalse(file("현악 4중주", "블타바.musicxml").exists())
        assertTrue(notesOf(file("현악 4중주", "블타바.pdf")).exists())
        // 새 판 + 새 이름 → 메모는 새 이름으로
        s.content = "v3"; s.version = 3; s.title = "몰다우 강"
        server.put(s)
        sync().sync()
        assertTrue(notesOf(file("현악 4중주", "몰다우 강.pdf")).exists())
        assertFalse(notesOf(file("현악 4중주", "블타바.pdf")).exists())
    }

    @Test
    fun 곡목에서_빠지면_메모는_보관했다가_다시_받으면_돌려_놓는다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        server.put(Score(2, "아리랑", "a"))
        sync().sync()
        notesOf(file("현악 4중주", "몰다우.pdf")).writeText("mine")
        server.remove(1)
        server.put(server.scores.getValue(2))
        sync().sync()
        assertFalse(file("현악 4중주", "몰다우.pdf").exists())
        assertFalse(notesOf(file("현악 4중주", "몰다우.pdf")).exists())
        assertEquals("mine", File(root, ".ScoreMateNotes/1.notes.json").readText())
        assertEquals("보관함은 목록에 보이지 않는다", listOf("아리랑.pdf"), PdfLibrary.listPdfFiles(root, scoreMate = true).map { it.name })
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        assertEquals("mine", notesOf(file("현악 4중주", "몰다우.pdf")).readText())
        assertFalse(File(root, ".ScoreMateNotes").exists())
    }

    @Test
    fun TV_에서_지운_악보의_메모도_보관하고_연결을_끊으며_파일을_지우면_함께_지운다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        val pdf = file("현악 4중주", "몰다우.pdf")
        notesOf(pdf).writeText("mine")
        pdf.delete()
        val engine = sync()
        assertTrue(engine.markHidden(pdf.path))
        assertTrue(File(root, ".ScoreMateNotes/1.notes.json").exists())
        engine.forgetAll(deleteFiles = true)
        assertFalse(File(root, ".ScoreMateNotes").exists())
    }

    @Test
    fun 연결을_끊을_때_남기면_메모도_로컬로_따라간다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        notesOf(file("현악 4중주", "몰다우.pdf")).writeText("mine")
        sync().forgetAll(deleteFiles = false)
        assertEquals("mine", File(root, "몰다우.notes.json").readText())
    }

    // ── 메모 서버 동기화 (P11 §6, 서버 0.17.0) — 개인 메모 ──

    private fun ink(id: String) = com.mrgq.pdfviewer.notes.ScoreNote.Ink(id, 0, 0xFF000000.toInt(), 1.5f, listOf(listOf(1f, 2f, 3f, 4f)))
    private val molPdf get() = file("현악 4중주", "몰다우.pdf")
    private fun localNotes() = com.mrgq.pdfviewer.notes.ScoreNotesFile.read(com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(molPdf))
    private fun writeLocal(vararg ids: String, sync: com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState? = localNotes()?.sync) =
        com.mrgq.pdfviewer.notes.ScoreNotesFile.write(
            com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(molPdf),
            com.mrgq.pdfviewer.notes.ScoreNotesFile.Loaded(ids.map { ink(it) }, null, sync = sync),
        )
    private fun serverIds(id: Long = 1) = com.google.gson.JsonParser.parseString(server.notesDocs.getValue(id).second).asJsonArray
        .map { it.asJsonObject.get("id").asString }.toSet()
    private fun inkJson(vararg ids: String) = ids.joinToString(",", "[", "]") {
        """{"id":"$it","page":0,"type":"ink","color":"#FF000000","width":1.5,"strokes":[[1,2,3,4]]}"""
    }

    private fun setUpNotes() = runBlocking {
        server.notesEnabled = true
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
    }

    @Test
    fun 메모를_모르는_서버면_조용히_건너뛴다() = runBlocking {
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        writeLocal("a")
        val report = sync().sync()
        assertTrue(report.errors.isEmpty())
        assertEquals(0, report.notesUploaded)
    }

    @Test
    fun 처음_올리기는_base_0_이고_맞춘_상태를_적는다() = runBlocking {
        setUpNotes()
        writeLocal("a")
        val report = sync().sync()
        assertEquals(1, report.notesUploaded)
        assertEquals(listOf(1L to 0), server.notePuts)
        assertEquals(setOf("a"), serverIds())
        assertEquals(com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState(1, setOf("a")), localNotes()!!.sync)
        // 바뀐 게 없으면 다시 올리지 않는다
        assertEquals(0, sync().sync().notesUploaded)
        assertEquals(1, server.notePuts.size)
    }

    @Test
    fun 다른_기기의_메모를_받는다() = runBlocking {
        setUpNotes()
        server.notesDocs[1] = 3 to inkJson("b")
        val report = sync().sync()
        assertEquals(1, report.notesDownloaded)
        assertEquals(setOf("b"), localNotes()!!.ids)
        assertEquals(3, localNotes()!!.sync!!.revision)
        assertTrue(server.notePuts.isEmpty())
    }

    @Test
    fun 양쪽이_고치면_서버_더하기_내_추가_빼기_내_삭제() = runBlocking {
        setUpNotes()
        writeLocal("a")
        sync().sync()                              // 서버 rev 1 = {a}
        server.notesDocs[1] = 2 to inkJson("a", "b") // 다른 기기가 b 를 더함
        writeLocal("c")                            // 나는 a 를 지우고 c 를 더함
        sync().sync()
        assertEquals(setOf("b", "c"), serverIds())
        assertEquals(setOf("b", "c"), localNotes()!!.ids)
        assertEquals(3, localNotes()!!.sync!!.revision)
    }

    @Test
    fun 동시에_처음_만들면_409_current_와_합쳐_다시_올린다() = runBlocking {
        setUpNotes()
        server.notesDocs[1] = 1 to inkJson("b") // 다른 기기가 막 만들었다 — 아직 내 목록에는 없다
        server.notesHidden = setOf(1L)
        writeLocal("a")
        sync().sync()
        assertEquals(listOf(1L to 0, 1L to 1), server.notePuts)
        assertEquals(setOf("a", "b"), serverIds())
        assertEquals(setOf("a", "b"), localNotes()!!.ids)
    }

    @Test
    fun 서버에_없는데_base_가_있으면_409_null_뒤_0_으로_다시() = runBlocking {
        setUpNotes()
        writeLocal("a", sync = com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState(2, emptySet()))
        server.notesListOverride = mapOf(1L to 2) // 목록은 있다고 하지만 문서는 없다
        sync().sync()
        assertEquals(listOf(1L to 2, 1L to 0), server.notePuts)
        assertEquals(setOf("a"), serverIds())
    }

    @Test
    fun 다_지우면_빈_문서를_올리고_곁_파일은_남긴다() = runBlocking {
        setUpNotes()
        writeLocal("a")
        sync().sync()
        writeLocal()
        assertTrue("맞춘 적이 있으면 비어도 남는다", com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(molPdf).exists())
        sync().sync()
        assertTrue(serverIds().isEmpty())
        assertEquals(2, server.notesDocs.getValue(1).first)
    }

    @Test
    fun 목록에서_빠지면_메모를_건드리지_않는다() = runBlocking {
        setUpNotes()
        writeLocal("a")
        sync().sync()
        server.notesHidden = setOf(1L)
        writeLocal("a", "c")
        sync().sync()
        assertEquals(setOf("a", "c"), localNotes()!!.ids)
        assertEquals("올리지 않는다", 1, server.notePuts.size)
    }

    // ── 지휘자 메모 (conductor, 앙상블 악보) ──

    private fun conductorNotes() = com.mrgq.pdfviewer.notes.ScoreNotesFile.read(com.mrgq.pdfviewer.notes.ScoreNotesFile.conductorFileOf(molPdf))
    private fun writeConductor(vararg ids: String, sync: com.mrgq.pdfviewer.notes.ScoreNotesFile.SyncState? = conductorNotes()?.sync) =
        com.mrgq.pdfviewer.notes.ScoreNotesFile.write(
            com.mrgq.pdfviewer.notes.ScoreNotesFile.conductorFileOf(molPdf),
            com.mrgq.pdfviewer.notes.ScoreNotesFile.Loaded(ids.map { ink(it) }, null, sync = sync),
        )

    @Test
    fun 멤버는_지휘자_메모를_받기만_한다() = runBlocking {
        setUpNotes()
        server.conductorDocs[1] = 2 to inkJson("c1")
        sync().sync()
        val c = conductorNotes()!!
        assertEquals(setOf("c1"), c.ids)
        assertFalse(c.sync!!.writable)
        assertTrue(server.conductorPuts.isEmpty())
    }

    @Test
    fun 리더는_지휘자_메모를_올리고_서버가_쓴_사람을_찍는다() = runBlocking {
        setUpNotes()
        server.conductorWritable = setOf(1L)
        sync().sync()
        assertTrue("쓸 수 있으면 빈 곁 파일이라도 만들어 뷰어가 쓰기 겹을 보인다", conductorNotes()!!.sync!!.writable)
        writeConductor("x")
        sync().sync()
        assertEquals(listOf(1L to 0), server.conductorPuts)
        val x = conductorNotes()!!.notes.single()
        assertEquals("리더", x.author)
        assertEquals(1, conductorNotes()!!.sync!!.revision)
    }

    @Test
    fun 리더가_아니게_되면_올리지_못한_지휘자_메모를_보관하고_서버_것으로() = runBlocking {
        setUpNotes()
        server.conductorWritable = setOf(1L)
        sync().sync()
        writeConductor("x") // 아직 못 올림
        server.conductorWritable = emptySet()
        val report = sync().sync()
        assertTrue(report.errors.any { it.contains("보관") })
        assertTrue(conductorNotes()!!.ids.isEmpty())
        assertFalse(conductorNotes()!!.sync!!.writable)
        assertEquals(1, File(root, ".ScoreMateNotes").listFiles()!!.count { it.name.startsWith("1.conductor-unsent-") })
        // 다음 동기화에는 다시 보관하지 않는다
        assertTrue(sync().sync().errors.isEmpty())
    }

    @Test
    fun 올리다_403_이면_쓰기를_거두고_보관한다() = runBlocking {
        setUpNotes()
        server.conductorWritable = setOf(1L)
        sync().sync()
        writeConductor("x")
        server.conductorForbidden = true // 목록은 아직 쓸 수 있다고 한다
        sync().sync()
        assertTrue("403 은 PUT 기록 전에 막힌다", server.conductorPuts.isEmpty())
        assertFalse(conductorNotes()!!.sync!!.writable)
        assertTrue(conductorNotes()!!.ids.isEmpty())
        assertTrue(File(root, ".ScoreMateNotes").listFiles()!!.any { it.name.startsWith("1.conductor-unsent-") })
    }

    @Test
    fun 곡목에서_빠질_때_올리지_못한_지휘자_메모는_보관했다가_돌려_놓는다() = runBlocking {
        setUpNotes()
        server.conductorWritable = setOf(1L)
        sync().sync()
        writeConductor("x")
        server.remove(1)
        server.put(Score(2, "아리랑", "a"))
        server.notesEnabled = false // 이번에는 메모를 맞추지 않고 악보만 빠진다
        sync().sync()
        assertTrue(File(root, ".ScoreMateNotes/1.conductor.notes.json").exists())
        server.notesEnabled = true
        server.put(Score(1, "몰다우", "v1"))
        sync().sync()
        assertEquals("x 는 다시 받은 뒤 올라간다", setOf("x"), com.google.gson.JsonParser.parseString(server.conductorDocs.getValue(1).second)
            .asJsonArray.map { it.asJsonObject.get("id").asString }.toSet())
    }
}
