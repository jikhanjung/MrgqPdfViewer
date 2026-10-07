package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreStaff
import com.mrgq.pdfviewer.notes.NoteGeometry
import com.mrgq.pdfviewer.notes.NoteStaff
import com.mrgq.pdfviewer.notes.ScoreNote
import com.mrgq.pdfviewer.notes.ScoreNotes
import com.mrgq.pdfviewer.notes.ScoreNotesFile
import com.mrgq.pdfviewer.notes.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 악보 메모 (P11) — 되돌리기 · 획 줄이기 · 지우개 · 붙는 보표 · 곁 파일 */
class ScoreNotesTest {

    private fun ink(id: String, page: Int = 0, vararg pts: Float, staff: Int? = null) =
        ScoreNote.Ink(id, page, 0xFF212121.toInt(), 1.5f, listOf(pts.toList()), staff)

    // ── 되돌리기 ──

    @Test
    fun 더하고_지우고_되돌리고_다시() {
        val notes = ScoreNotes()
        val a = ink(notes.nextId(), 0, 1f, 1f)
        notes.add(a)
        val b = ink(notes.nextId(), 0, 2f, 2f)
        notes.add(b)
        assertTrue(notes.remove(listOf(a, b)))
        assertTrue(notes.notes.isEmpty())
        assertTrue("지우개 한 번 = 되돌리기 한 번", notes.undo())
        assertEquals(setOf(a.id, b.id), notes.notes.map { it.id }.toSet())
        notes.undo()
        notes.undo()
        assertTrue(notes.notes.isEmpty())
        assertFalse(notes.canUndo)
        notes.redo()
        assertEquals(listOf(a.id), notes.notes.map { it.id })
        // 새 동작이면 다시 쌓인 것을 버린다
        notes.add(ink(notes.nextId(), 0, 3f, 3f))
        assertFalse(notes.canRedo)
    }

    @Test
    fun id_는_무작위_16자이고_겹치지_않는다() {
        val notes = ScoreNotes()
        val ids = (1..1000).map { notes.nextId() }
        assertTrue(ids.all { it.length == 16 && it.all { c -> c in "0123456789abcdef" } })
        assertEquals(1000, ids.toSet().size)
    }

    @Test
    fun 없는_것을_지우면_false() {
        val notes = ScoreNotes(listOf(ink("1", 0, 1f, 1f)))
        assertFalse(notes.remove(listOf(ink("2", 0, 1f, 1f))))
        assertFalse(notes.canUndo)
    }

    @Test
    fun 쪽별로_꺼낸다() {
        val notes = ScoreNotes(listOf(ink("1", 0, 1f, 1f), ink("2", 3, 1f, 1f)))
        assertEquals(listOf("2"), notes.onPage(3).map { it.id })
    }

    // ── 획 계산 ──

    @Test
    fun 줄이기는_끝점을_남기고_일직선의_가운데를_뺀다() {
        val line = (0..100).flatMap { listOf(it.toFloat(), 50f + (it % 2) * 0.05f) }
        val out = NoteGeometry.simplify(line, 0.25f)
        assertEquals(listOf(0f, 50f), out.take(2))
        assertEquals(listOf(100f, 50f), out.takeLast(2))
        assertEquals(4, out.size)
    }

    @Test
    fun 줄이기는_꺾인_곳을_남긴다() {
        val vee = listOf(0f, 0f, 5f, 5f, 10f, 10f, 15f, 5f, 20f, 0f)
        assertEquals(listOf(0f, 0f, 10f, 10f, 20f, 0f), NoteGeometry.simplify(vee, 0.25f))
    }

    @Test
    fun 지우개는_굵기의_절반까지_넓혀_맞힌다() {
        val stroke = ink("1", 0, 0f, 0f, 100f, 0f)
        assertTrue(NoteGeometry.hits(stroke, 50f, 3f, 2.5f))   // 2.5 + 0.75 = 3.25
        assertFalse(NoteGeometry.hits(stroke, 50f, 4f, 2.5f))
        assertFalse("선분 끝 너머", NoteGeometry.hits(stroke, 110f, 0f, 2.5f))
        assertTrue("점 하나", NoteGeometry.hits(ink("2", 0, 10f, 10f), 11f, 11f, 2f))
    }

    // ── 붙는 보표 (P11 §3.6) ──

    /** 시스템 두 개 × 보표 두 개 (오선 높이 24pt, 보표 사이 40pt, 시스템 사이 80pt) */
    private val staves = listOf(
        ScoreStaff("f", 0, 0, 0, 100f, 124f, "Vn."),
        ScoreStaff("f", 0, 0, 1, 164f, 188f, "Vc."),
        ScoreStaff("f", 0, 1, 0, 268f, 292f, null),
        ScoreStaff("f", 0, 1, 1, 332f, 356f, null),
        ScoreStaff("f", 1, 0, 0, 100f, 124f, null),
    )

    @Test
    fun 오선_안이면_그_보표_분명() {
        val a = NoteStaff.of(staves, 0, 105f, 115f)!!
        assertEquals(0 to 0, a.staff.systemIndex to a.staff.staffIndex)
        assertTrue(a.sure)
        assertEquals(1, NoteStaff.of(staves, 0, 160f, 200f)!!.staff.staffIndex) // 가운데 180 은 아래 보표 오선 안
    }

    @Test
    fun 보표_사이는_가까운_쪽_가운데면_애매() {
        val near = NoteStaff.of(staves, 0, 128f, 132f)!! // 위 보표에서 6, 아래에서 34
        assertEquals(0, near.staff.staffIndex)
        assertTrue(near.sure)
        val middle = NoteStaff.of(staves, 0, 142f, 146f)!! // 20 · 20
        assertFalse(middle.sure)
    }

    @Test
    fun 시스템_사이도_가까운_보표() {
        val a = NoteStaff.of(staves, 0, 252f, 258f)!! // 다음 시스템 첫 보표에서 13
        assertEquals(1, a.staff.systemIndex)
        assertEquals(0, a.staff.staffIndex)
    }

    @Test
    fun 멀면_어느_파트도_아님() {
        assertNull("쪽 위 제목", NoteStaff.of(staves, 0, 10f, 20f))
        assertNull("분석 없는 쪽", NoteStaff.of(staves, 5, 100f, 110f))
    }

    @Test
    fun 보표_이름() {
        assertEquals("Vn.", NoteStaff.labelOf(staves[0]))
        assertEquals("보표 2", NoteStaff.labelOf(staves[3]))
    }

    // ── 곁 파일 ──

    @Test
    fun 곁_파일_이름은_PDF_옆() {
        assertEquals(File("/x/ScoreMate/몰다우.notes.json"), ScoreNotesFile.fileOf(File("/x/ScoreMate/몰다우.pdf")))
    }

    @Test
    fun 쓰고_읽으면_같다_좌표는_소수_한_자리() {
        val loaded = ScoreNotesFile.Loaded(
            listOf(ink("1", 2, 10.04f, 20.06f, 30f, 40f, staff = 1), ScoreNote.Ink("2", 0, 0xFFE53935.toInt(), 3f, listOf(listOf(1f, 2f)))),
            "abc",
        )
        val back = ScoreNotesFile.parse(ScoreNotesFile.format(loaded))
        assertEquals("abc", back.pdfSha256)
        val first = back.notes[0] as ScoreNote.Ink
        assertEquals(listOf(listOf(10f, 20.1f, 30f, 40f)), first.strokes)
        assertEquals(1, first.staff)
        assertEquals(2, first.page)
        assertEquals(0xFFE53935.toInt(), back.notes[1].color)
        assertNull(back.notes[1].staff)
    }

    @Test
    fun 모르는_종류는_그대로_들고_있다가_돌려_쓴다() {
        val text = """{"format":1,"notes":[{"id":"a1","page":0,"type":"ink","color":"#FF000000","width":1,"points":[1,2]},""" +
            """{"id":"b2","page":0,"type":"stamp","x":5,"y":6,"glyph":"fermata"}]}"""
        val loaded = ScoreNotesFile.parse(text)
        assertEquals(1, loaded.notes.size)
        assertEquals(1, loaded.unknown.size)
        val again = ScoreNotesFile.parse(ScoreNotesFile.format(loaded))
        assertEquals("fermata", again.unknown.single().get("glyph").asString)
    }

    @Test
    fun 메모가_없으면_파일을_지우고_깨진_파일은_null() {
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            val f = File(dir, "a.notes.json")
            ScoreNotesFile.write(f, ScoreNotesFile.Loaded(listOf(ink("1", 0, 1f, 1f)), null))
            assertEquals(1, ScoreNotesFile.read(f)!!.notes.size)
            assertFalse(File(dir, "a.notes.json.tmp").exists())
            ScoreNotesFile.write(f, ScoreNotesFile.Loaded(emptyList(), null))
            assertFalse(f.exists())
            f.writeText("{broken")
            assertNull(ScoreNotesFile.read(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 2단계: 글자 · 옮기기 ──

    private fun text(id: String, x: Float = 72f, y: Float = 140f, t: String = "rit.") =
        ScoreNote.Text(id, 0, 0xFF1E88E5.toInt(), 11f, x, y, t, staff = 1)

    @Test
    fun 글자_곁_파일_왕복() {
        val back = ScoreNotesFile.parse(ScoreNotesFile.format(ScoreNotesFile.Loaded(listOf(text("t1", t = "숨\n4")), null)))
        val t = back.notes.single() as ScoreNote.Text
        assertEquals("숨\n4", t.text)
        assertEquals(72f, t.x)
        assertEquals(11f, t.sizePt)
        assertEquals(1, t.staff)
        assertTrue(back.unknown.isEmpty())
    }

    @Test
    fun 고치기_옮기기는_새_id_로_바꿔_끼우고_되돌리기_한_번() {
        val old = text("t1")
        val notes = ScoreNotes(listOf(old))
        val moved = old.movedBy(10f, -5f, notes.nextId(), staff = 0) as ScoreNote.Text
        assertTrue(notes.replace(old, moved))
        assertEquals(listOf(moved.id), notes.notes.map { it.id })
        assertEquals(82f, moved.x)
        assertEquals(135f, moved.y)
        assertEquals(0, moved.staff)
        assertTrue(moved.id != old.id)
        notes.undo()
        assertEquals(listOf("t1"), notes.notes.map { it.id })
        assertFalse("없는 것은 못 바꾼다", notes.replace(moved, old))
    }

    @Test
    fun 획_옮기기는_모든_점을_옮긴다() {
        val moved = ink("a", 0, 1f, 2f, 3f, 4f).movedBy(10f, 20f, "b", null) as ScoreNote.Ink
        assertEquals(listOf(listOf(11f, 22f, 13f, 24f)), moved.strokes)
        assertEquals("b", moved.id)
    }

    @Test
    fun 글자_상자_맞히기와_줄_배치() {
        val t = text("t1", t = "a\nb")
        assertEquals(26.4f, TextLayout.height(t), 0.001f)       // 11 × 1.2 × 2줄
        assertEquals(140f + 11f * 2.0f, TextLayout.baseline(t, 1), 0.001f) // 0.8 + 1.2
        assertTrue(NoteGeometry.hitsText(t, 20f, 80f, 150f, 0f))
        assertFalse(NoteGeometry.hitsText(t, 20f, 95f, 150f, 0f))
        assertTrue("반지름만큼 넓혀", NoteGeometry.hitsText(t, 20f, 95f, 150f, 4f))
    }

    // ── 한 번에 그린 획 여럿 = 메모 하나 ──

    private val group = ScoreNote.Ink("g", 0, 0xFF212121.toInt(), 1.5f, listOf(listOf(0f, 0f, 10f, 0f), listOf(50f, 50f, 60f, 60f)))

    @Test
    fun 묶음은_어느_획을_맞혀도_하나로_지운다() {
        assertTrue(NoteGeometry.hits(group, 5f, 0.5f, 1f))
        assertTrue(NoteGeometry.hits(group, 55f, 55f, 1f))
        assertFalse(NoteGeometry.hits(group, 30f, 30f, 1f))
        val notes = ScoreNotes(listOf(group))
        assertTrue(notes.remove(listOf(group)))
        assertTrue(notes.notes.isEmpty())
    }

    @Test
    fun 묶음_옮기기와_곁_파일_왕복() {
        val moved = group.movedBy(1f, 2f, "h", null) as ScoreNote.Ink
        assertEquals(listOf(listOf(1f, 2f, 11f, 2f), listOf(51f, 52f, 61f, 62f)), moved.strokes)
        val back = ScoreNotesFile.parse(ScoreNotesFile.format(ScoreNotesFile.Loaded(listOf(group), null)))
        assertEquals(group.strokes, (back.notes.single() as ScoreNote.Ink).strokes)
    }

    @Test
    fun 옛_points_한_획도_읽는다() {
        val old = """{"format":1,"notes":[{"id":"a","page":0,"type":"ink","color":"#FF000000","width":1,"points":[1,2,3,4]}]}"""
        assertEquals(listOf(listOf(1f, 2f, 3f, 4f)), (ScoreNotesFile.parse(old).notes.single() as ScoreNote.Ink).strokes)
    }

    // ── 서버 동기화 상태 · 합치기 ──

    @Test
    fun 합치기는_서버_더하기_내가_더한_것_빼기_내가_지운_것() {
        val remote = ScoreNotesFile.Loaded(listOf(ink("a"), ink("b")), "s")
        val local = ScoreNotesFile.Loaded(listOf(ink("c"), ink("b")), null)
        val merged = ScoreNotesFile.merge(remote, local, baseIds = setOf("a", "b"))
        assertEquals(setOf("b", "c"), merged.ids) // a 는 내가 지움, c 는 내가 더함
        assertEquals("s", merged.pdfSha256)
    }

    @Test
    fun 동기화_상태는_곁_파일에_남고_서버로는_빼고_보낸다() {
        val loaded = ScoreNotesFile.Loaded(listOf(ink("a")), null, sync = ScoreNotesFile.SyncState(4, setOf("a")))
        assertEquals(loaded.sync, ScoreNotesFile.parse(ScoreNotesFile.format(loaded)).sync)
        assertFalse(ScoreNotesFile.formatJson(loaded, withSync = false).has("sync"))
    }
}
