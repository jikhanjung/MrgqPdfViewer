package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.scoremate.ScoreMateSetlists
import com.mrgq.pdfviewer.scoremate.SetlistItem
import com.mrgq.pdfviewer.scoremate.SyncedScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 세트리스트 해석과 곡 순서 (#064, 서버 `GET /sync/setlists/`) */
class ScoreMateSetlistsTest {

    private val body = """
        {"setlists":[
          {"id":7,"title":"10월 정기연주회","description":"","ensemble":{"id":2,"name":"현악 4중주"},"updated_at":"x",
           "items":[{"score_id":12,"position":2,"notes":"반복 없이"},{"score_id":11,"position":1,"notes":""},{"score_id":13,"position":3,"notes":""}]},
          {"id":8,"title":"","ensemble":null,"items":[]},
          {"title":"id 없음 — 버린다"}
        ]}
    """.trimIndent()

    @Test
    fun 곡_순서대로_읽고_깨진_것은_건너뛴다() {
        val lists = ScoreMateSetlists.parse(body)
        assertEquals(2, lists.size)
        val concert = lists.first()
        assertEquals("현악 4중주", concert.ensembleName)
        assertEquals(listOf(11L, 12L, 13L), concert.items.map { it.scoreId })
        assertEquals("반복 없이", concert.items[1].notes)
        assertEquals("제목이 비면 기본 이름", "세트리스트", lists[1].title)
        assertNull(lists[1].ensembleName)
        assertTrue(ScoreMateSetlists.parse(null).isEmpty())
        assertTrue(ScoreMateSetlists.parse("oops").isEmpty())
    }

    @Test
    fun 곡을_이_TV_의_파일과_잇는다_없거나_숨긴_곡은_경로가_없다() {
        val dir = Files.createTempDirectory("sl").toFile()
        try {
            val a = File(dir, "a.pdf").apply { writeText("x") }
            val gone = File(dir, "gone.pdf")
            val hidden = File(dir, "h.pdf").apply { writeText("x") }
            fun row(id: Long, f: File, hide: Boolean = false) = SyncedScore(id, f.path, 1, "s", "t", "", "", null, null, hide)
            val concert = ScoreMateSetlists.parse(body).first()
            val entries = ScoreMateSetlists.entries(concert, listOf(row(11, a), row(12, gone), row(13, hidden, hide = true)))
            assertEquals(listOf(a.path, null, null), entries.map { it.path })
            assertEquals(SetlistItem(11, 1, ""), entries.first().item)
        } finally {
            dir.deleteRecursively()
        }
    }
}
