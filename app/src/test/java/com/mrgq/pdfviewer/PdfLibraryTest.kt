package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.ensemble.EnsembleFiles
import com.mrgq.pdfviewer.ensemble.EnsembleFiles.Resolution
import com.mrgq.pdfviewer.scoremate.SyncedScore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 연주자가 지휘자 파일을 찾고 받는 곳 (#063) — 연결된 TV: 내용 해시 → 캐시, 연결 안 된 TV: 이름 → PDFs/ */
class PdfLibraryTest {

    private lateinit var root: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pdfs").toFile()
        cache = File(Files.createTempDirectory("cache").toFile(), "ensemble")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        cache.parentFile.deleteRecursively()
    }

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    private fun synced(id: Long, file: File, sha: String, hidden: Boolean = false) =
        SyncedScore(id, file.path, 1, sha, "t", "", "", null, null, hidden)

    private fun resolve(name: String, sha: String?, linked: Boolean, listed: List<File> = emptyList(), synced: List<SyncedScore> = emptyList()) =
        EnsembleFiles.resolve(name, sha, linked, listed.map { it.name to it.path }, synced, root, cache)

    @Test
    fun 연결된_TV_는_같은_내용이면_내_ScoreMate_에서_이름이_달라도() {
        val mine = File(root, "ScoreMate/현악/몰다우.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(Resolution.Open(mine.path), resolve("다른 이름.pdf", shaA, linked = true, synced = listOf(synced(7, mine, shaA))))
        assertEquals("대소문자 무시", Resolution.Open(mine.path), resolve("몰다우.pdf", shaA.uppercase(), linked = true, synced = listOf(synced(7, mine, shaA))))
    }

    @Test
    fun 연결된_TV_에_같은_내용이_없으면_캐시로_내용마다_폴더() {
        val mine = File(root, "ScoreMate/현악/몰다우.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        val cachedB = File(File(cache, shaB.take(16)), "몰다우.pdf")
        // 판이 다름(내용이 다름) · 숨긴 악보 → 캐시
        assertEquals(Resolution.Download(cachedB, cached = true), resolve("몰다우.pdf", shaB, linked = true, synced = listOf(synced(7, mine, shaA))))
        assertEquals(
            Resolution.Download(File(File(cache, shaA.take(16)), "몰다우.pdf"), cached = true),
            resolve("몰다우.pdf", shaA, linked = true, synced = listOf(synced(7, mine, shaA, hidden = true))),
        )
        // 해시를 보내지 않는 옛 지휘자 → 이름으로
        assertEquals(Resolution.Download(File(cache, "몰다우.pdf"), cached = true), resolve("몰다우.pdf", null, linked = true))
        // 받아 둔 캐시가 있으면 연다
        cachedB.apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(Resolution.Open(cachedB.path), resolve("몰다우.pdf", shaB, linked = true))
    }

    @Test
    fun 연결_안_된_TV_는_이름으로_없으면_PDFs_로() {
        val local = File(root, "몰다우.pdf").apply { writeText("x") }
        assertEquals(Resolution.Open(local.path), resolve("몰다우.pdf", shaA, linked = false, listed = listOf(local)))
        assertEquals(Resolution.Download(File(root, "아리랑.pdf"), cached = false), resolve("아리랑.pdf", null, linked = false, listed = listOf(local)))
    }

    @Test
    fun 지휘자가_보낸_이름을_경로로_쓰지_않는다() {
        assertEquals("evil.pdf", EnsembleFiles.safeName("../../evil.pdf"))
        assertEquals("x.pdf", EnsembleFiles.safeName("..\\\\x.pdf"))
        assertEquals("a_b.pdf", EnsembleFiles.safeName("a:b"))
        assertEquals("악보.pdf", EnsembleFiles.safeName("../"))
        val r = resolve("../../../etc/evil.pdf", null, linked = false) as Resolution.Download
        assertEquals(root.path, r.target.parentFile!!.path)
    }

    @Test
    fun 캐시는_상한을_넘으면_오래된_것부터_지운다() {
        cache.mkdirs()
        val old = File(cache, "old.pdf").apply { writeBytes(ByteArray(60)); setLastModified(1_000_000) }
        val mid = File(cache, "mid.pdf").apply { writeBytes(ByteArray(60)); setLastModified(2_000_000) }
        val now = File(cache, "now.pdf").apply { writeBytes(ByteArray(60)); setLastModified(3_000_000) }
        val removed = EnsembleFiles.trimCache(cache, maxBytes = 100, keep = now)
        assertEquals(listOf(old, mid), removed)
        assertFalse(old.exists())
        assertTrue(now.exists())
    }

    @Test
    fun 지휘자_파일_해시는_아는_값을_쓰고_로컬은_한_번_계산() {
        val f = File(root, "a.pdf").apply { writeText("hello") }
        assertEquals(shaA, EnsembleFiles.sha256Of(f, known = shaA.uppercase()))
        val computed = EnsembleFiles.sha256Of(f)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", computed)
        assertEquals(null, EnsembleFiles.sha256Of(File(root, "없음.pdf")))
    }
}
