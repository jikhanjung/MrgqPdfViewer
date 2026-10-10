package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.voice.CommandOutcome
import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.PartMatcher
import com.mrgq.pdfviewer.voice.PartRef
import com.mrgq.pdfviewer.voice.ViewerCommands
import com.mrgq.pdfviewer.voice.VoiceCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 명령 명령 층(P12 §5): 읽은 결과 → 실행 순서 · 범위 검사 · 실패 보고, 파트 이름 맞추기. */
class VoiceCommandRunnerTest {

    /** 부른 명령을 적어 두는 가짜 악보 화면. [failOn] 에 든 명령은 실패한다 */
    private class FakeViewer(private val failOn: Set<String> = emptySet(), private val parts: List<String> = emptyList()) : ViewerCommands {
        val calls = mutableListOf<String>()
        private fun call(name: String): CommandOutcome {
            calls += name
            return if (failOn.any { name.startsWith(it) }) CommandOutcome.Failed("$name 실패") else CommandOutcome.Done
        }
        override suspend fun selectMeasure(measure: Int) = call("select $measure")
        override suspend fun gotoMeasure(measure: Int) = call("measure $measure")
        override suspend fun gotoStart() = call("start-over")
        override suspend fun restart() = call("restart")
        override suspend fun resume() = call("resume")
        override suspend fun gotoRehearsalMark(mark: String) = call("letter $mark")
        override suspend fun nextPage() = call("next")
        override suspend fun previousPage() = call("prev")
        override suspend fun gotoPage(page: Int) = call("page $page")
        override suspend fun setTempo(bpm: Int) = call("tempo $bpm")
        override suspend fun setCountIn(bars: Int) = call("count-in $bars")
        override suspend fun selectParts(parts: List<PartRef>) = call("parts ${parts.joinToString { it.label }}")
        override suspend fun showFullScore() = call("full")
        override suspend fun start() = call("start")
        override suspend fun stop() = call("stop")
        override suspend fun listen() = call("listen")
        override suspend fun partNames() = parts
    }

    private fun run(text: String, viewer: FakeViewer = FakeViewer(), bareNumberIsTempo: Boolean = false) =
        runBlocking { VoiceCommandRunner.run(CommandParser.parse(text), viewer, bareNumberIsTempo) } to viewer.calls

    @Test
    fun 템포를_먼저_마디는_뒤에() {
        val (report, calls) = run("오십칠 마디부터 템포 칠십이")
        assertEquals(listOf("tempo 72", "measure 57"), calls)
        assertTrue(report.ok)
        assertEquals("57마디부터 · ♩=72", report.message)
    }

    @Test
    fun 파트를_못_찾으면_들린_말과_이_악보의_파트를_알린다() {
        val viewer = FakeViewer(parts = listOf("김지한", "박서연", "보표 3"))
        val (report, calls) = run("지환 파트 57마디부터", viewer)
        assertFalse(report.ok)
        assertEquals(emptyList<String>(), calls)
        assertEquals("'지환' 파트를 이 악보에서 찾지 못했어요 — 이 악보: 김지한 · 박서연 · 보표 3", report.message)
        assertEquals("무슨 파트인지 못 알아들었어요", run("파트 보여줘").first.message)
    }

    @Test
    fun 파트를_고른_뒤에_마디로() {
        val (_, calls) = run("첼로 57마디부터")
        assertEquals(listOf("parts 첼로", "measure 57"), calls)
    }

    @Test
    fun 수만_들리면_마디_고르기_대화상자가_열려_있으면_템포() {
        assertEquals(listOf("select 72"), run("칠십이").second)
        assertEquals(listOf("tempo 72"), run("칠십이", bareNumberIsTempo = true).second)
    }

    @Test
    fun 범위를_벗어나면_하나도_실행하지_않는다() {
        val (report, calls) = run("템포 삼백 57마디")
        assertFalse(report.ok)
        assertTrue(calls.isEmpty())
        assertTrue(report.message, report.message.contains("20 ~ 240"))
        assertTrue(run("템포 10").second.isEmpty())
        assertEquals(listOf("tempo 20"), run("템포 이십").second)
    }

    @Test
    fun 못_알아들으면_아무것도_부르지_않는다() {
        for (text in listOf("57마디 말고", "두 마디 전부터", "2악장 57마디", "57마디 3쪽", "안녕하세요")) {
            val (report, calls) = run(text)
            assertFalse(text, report.ok)
            assertTrue(text, calls.isEmpty())
        }
    }

    @Test
    fun 실패하면_거기서_멈추고_앞에서_한_것을_알린다() {
        val viewer = FakeViewer(failOn = setOf("measure"))
        val (report, calls) = run("57마디 템포 72 시작", viewer)
        assertEquals(listOf("tempo 72", "measure 57"), calls)
        assertFalse(report.ok)
        assertEquals("♩=72 했지만 — measure 57 실패", report.message)

        val first = FakeViewer(failOn = setOf("tempo"))
        val (r2, c2) = run("템포 72 57마디", first)
        assertEquals(listOf("tempo 72"), c2)
        assertEquals("tempo 72 실패", r2.message)
    }

    @Test
    fun 마디만이면_고르고_시작이_붙으면_그_마디부터() {
        assertEquals(listOf("select 57"), run("57마디").second)
        assertEquals(listOf("select 50"), run("50 마").second)
        assertEquals(listOf("measure 57"), run("57마디 시작").second)
        assertEquals(listOf("start"), run("시작").second)
        assertEquals(listOf("tempo 72", "select 57"), run("57마디 템포 72").second)
    }

    @Test
    fun 예비박은_한_마디나_두_마디() {
        assertEquals(listOf("count-in 1", "measure 57"), run("예비박 한 마디 57마디부터").second)
        val (report, calls) = run("예비박 세 마디")
        assertFalse(report.ok)
        assertTrue(calls.isEmpty())
        assertEquals("예비박은 한 마디나 두 마디만 돼요", report.message)
    }

    @Test
    fun 듣기는_마디를_고른_뒤에() {
        assertEquals(listOf("select 57", "listen"), run("57마디부터 듣기").second)
        assertEquals(listOf("tempo 72", "page 3", "listen"), run("템포 72 3쪽 듣기").second)
        assertEquals(listOf("listen"), run("듣기 시작").second)
    }

    @Test
    fun 쪽_넘기고_시작_정지() {
        assertEquals(listOf("page 1", "start"), run("1페이지 시작").second)
        assertEquals(listOf("stop"), run("메트로놈 정지").second)
        assertEquals(listOf("tempo 72", "stop"), run("템포 72 멈춰").second)
    }

    @Test
    fun 쪽_넘기기_다시_이어서() {
        assertEquals(listOf("next"), run("다음 쪽").second)
        assertEquals(listOf("prev"), run("이전 페이지").second)
        assertEquals(listOf("page 3"), run("삼 쪽").second)
        assertEquals(listOf("restart"), run("다시").second)
        assertEquals(listOf("resume"), run("이어서 해 주세요").second)
        assertEquals(listOf("start-over"), run("처음부터 다시").second)
        assertEquals(listOf("tempo 80", "start"), run("템포 팔십 시작").second)
    }

    // ── 음성 인식 후보 고르기 ──

    @Test
    fun 첫_후보가_명령이_아니면_다음_후보() {
        val best = CommandParser.parseBest(listOf("다섯시 반부터", "57마디부터 템포 72", "57마디"))
        assertEquals("57마디부터 템포 72", best?.first)
        assertEquals(listOf("tempo 72", "measure 57"), run(best!!.first).second)
        assertEquals("72", CommandParser.parseBest(listOf("안녕", "72"))?.first)
    }

    @Test
    fun 일부러_한_말로_막히면_다음_후보로_가지_않는다() {
        assertEquals("57마디 말고", CommandParser.parseBest(listOf("57마디 말고", "57마디"))?.first)
        assertEquals("두 마디 전부터", CommandParser.parseBest(listOf("두 마디 전부터", "두 마디부터"))?.first)
        assertEquals("안녕", CommandParser.parseBest(listOf("안녕", "반가워"))?.first)
        assertNull(CommandParser.parseBest(listOf("", " ")))
    }

    // ── 파트 이름 맞추기 ──

    @Test
    fun 보표_이름에서_악기와_번호() {
        assertEquals("violin" to 1, PartMatcher.identify("Violin I"))
        assertEquals("violin" to 2, PartMatcher.identify("Vln. 2"))
        assertEquals("violin" to 2, PartMatcher.identify("Vn. II"))
        assertEquals("violin" to 1, PartMatcher.identify("1st Violin"))
        assertEquals("violin" to 2, PartMatcher.identify("Violin2"))
        assertEquals("cello" to null, PartMatcher.identify("Violoncello"))
        assertEquals("cello" to null, PartMatcher.identify("Vc."))
        assertEquals("bass" to null, PartMatcher.identify("Double Bass"))
        assertEquals("bass" to null, PartMatcher.identify("Contrabass"))
        assertEquals("clarinet" to null, PartMatcher.identify("Bass Clarinet"))
        assertEquals("clarinet" to 1, PartMatcher.identify("Clarinet in B♭ 1"))
        assertEquals("violin" to 1, PartMatcher.identify("바이올린 1"))
        assertEquals("piano" to null, PartMatcher.identify("Pno."))
        assertNull(PartMatcher.identify("보표 3"))
        assertNull(PartMatcher.identify("Solo"))
    }

    private val quartet = listOf(0 to "Violin I", 1 to "Violin II", 2 to "Viola", 3 to "Violoncello")

    @Test
    fun 말한_파트의_보표() {
        assertEquals(PartMatcher.Result.Staves(setOf(3)), PartMatcher.match(quartet, listOf(PartRef("cello"))))
        assertEquals(PartMatcher.Result.Staves(setOf(1)), PartMatcher.match(quartet, listOf(PartRef("violin", 2))))
        assertEquals(
            PartMatcher.Result.Staves(setOf(0, 2)),
            PartMatcher.match(quartet, listOf(PartRef("violin", 1), PartRef("viola"))),
        )
    }

    @Test
    fun 번호_없이_여럿이면_묻는다_없으면_없다() {
        val ambiguous = PartMatcher.match(quartet, listOf(PartRef("violin")))
        assertEquals(PartMatcher.Result.Ambiguous(PartRef("violin"), listOf("Violin I", "Violin II")), ambiguous)
        assertEquals(PartMatcher.Result.Missing(PartRef("flute")), PartMatcher.match(quartet, listOf(PartRef("flute"))))
        assertEquals(PartMatcher.Result.Missing(PartRef("violin", 3)), PartMatcher.match(quartet, listOf(PartRef("violin", 3))))
    }

    @Test
    fun 이름_없는_아래_보표는_함께() {
        val sonata = listOf(0 to "Cello", 1 to "Piano", 2 to null)
        assertEquals(PartMatcher.Result.Staves(setOf(1, 2)), PartMatcher.match(sonata, listOf(PartRef("piano"))))
        assertEquals(PartMatcher.Result.Staves(setOf(0)), PartMatcher.match(sonata, listOf(PartRef("cello"))))
    }
}
