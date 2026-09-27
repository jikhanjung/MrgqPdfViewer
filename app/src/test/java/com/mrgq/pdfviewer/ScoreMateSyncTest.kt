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
    )

    /** 서버 흉내: 커서 = 마지막으로 보낸 변경 번호. 받기는 302 → 서명 URL(인증 없이만 받아 준다) */
    private class Server : HttpTransport {
        val scores = LinkedHashMap<Long, Score>()
        val changes = mutableListOf<Long>() // 변경 순서 (id)
        var pageSize = 50
        var downloads = 0
        var signedWithAuth = 0

        fun put(score: Score) {
            scores[score.id] = score
            changes += score.id
        }

        fun remove(id: Long) {
            scores.remove(id)
        }

        override fun execute(request: HttpRequest): HttpResponse {
            val path = request.url.removePrefix("https://sm.test")
            if (!path.startsWith("/api/v1/sync/scores/")) return HttpResponse(404, "")
            val cursor = Regex("cursor=([0-9]+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
            if (path.contains("cursor=bad")) return HttpResponse(400, """{"cursor":"Invalid cursor"}""")
            val end = minOf(changes.size, cursor + pageSize)
            val page = changes.subList(cursor, end).distinct().mapNotNull { scores[it] }
            val items = page.joinToString(",") { s ->
                val ensemble = s.ensemble?.let { """{"id":${it.hashCode().toLong() and 0xffff},"name":"$it"}""" } ?: "null"
                val sha = if (s.processing) "null" else "\"${sha(s.content)}\""
                """{"id":${s.id},"title":"${s.title}","composer":"","part_name":"${s.part}","ensemble":$ensemble,
                   "version":{"number":${s.version},"sha256":$sha,"size_bytes":${s.content.length}},
                   "download_url":"http://internal:8000/api/v1/scores/${s.id}/download/"}"""
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
}
