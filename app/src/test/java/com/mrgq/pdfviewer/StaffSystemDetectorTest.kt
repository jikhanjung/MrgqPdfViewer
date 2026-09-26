package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.PathBox
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.StaffSystemDetector
import com.mrgq.pdfviewer.score.SystemLayout
import com.mrgq.pdfviewer.score.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시스템·마디선 판정 규칙 (data/segment_score.py 포트). 합성 경로로 규칙 하나씩 확인한다.
 * 실제 악보와의 일치는 계측 테스트 ScoreLayoutAnalyzerTest(골든)가 본다.
 *
 * 좌표는 PDF y-up. 보표 하나 = 아래 오선 y 부터 5pt 간격 5줄(폭 20pt).
 */
class StaffSystemDetectorTest {

    private val pageHeight = 842f

    private fun line(x0: Float, x1: Float, y: Float) = PathBox(x0, y - 0.25f, x1, y + 0.25f, curved = false)
    private fun staff(bottomY: Float, x0: Float = 50f, x1: Float = 500f) = (0 until 5).map { line(x0, x1, bottomY + it * 5f) }
    private fun bar(x: Float, bottomY: Float) = PathBox(x - 0.4f, bottomY, x + 0.4f, bottomY + 20f, curved = false)
    private fun head(x: Float, y: Float) = PathBox(x - 3f, y - 2.5f, x + 3f, y + 2.5f, curved = true)

    /** 한 시스템 = 보표 5개, 보표 사이 25pt. 아래 보표부터. */
    private fun systemStaves(lowestBottom: Float) = (0 until 5).map { lowestBottom + it * 45f }

    private fun systemWithBars(lowestBottom: Float, barXs: List<Float>): List<PathBox> {
        val bottoms = systemStaves(lowestBottom)
        return bottoms.flatMap { staff(it) } + bottoms.flatMap { b -> barXs.map { bar(it, b) } }
    }

    @Test
    fun 오선이_없으면_빈_결과() {
        assertTrue(StaffSystemDetector.detect(emptyList(), pageHeight).isEmpty())
        assertTrue(StaffSystemDetector.detect(listOf(bar(100f, 100f)), pageHeight).isEmpty())
    }

    @Test
    fun 오선이_다섯줄이_안되면_보표가_아니다() {
        val fourLines = (0 until 4).map { line(50f, 500f, 400f + it * 5f) }
        assertTrue(StaffSystemDetector.detect(fourLines, pageHeight).isEmpty())
    }

    @Test
    fun 마디선으로_세_마디를_나눈다() {
        val systems = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 350f, 500f)), pageHeight)
        assertEquals(1, systems.size)
        val s = systems[0]
        assertEquals(listOf(50f, 200f, 350f, 500f), s.barlines)
        assertEquals(3, s.measureCount)
        assertEquals(5, s.staffBands.size)
    }

    @Test
    fun 좌표는_위에서_아래로_뒤집는다() {
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 500f)), pageHeight).single()
        // 가장 위 보표의 위 오선 y = 420 + 4×45 + 20 = 620 → 842 - 620
        assertEquals(222f, s.top, 0.05f)
        // 가장 아래 보표의 아래 오선 y = 420 → 842 - 420
        assertEquals(422f, s.bottom, 0.05f)
        assertEquals(222f to 242f, s.staffBands.first())
    }

    @Test
    fun 끝_마디선이_없으면_오선_끝을_경계로_쓴다() {
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 350f)), pageHeight).single()
        assertEquals(listOf(50f, 200f, 350f, 500f), s.barlines)
    }

    @Test
    fun 끝세로줄은_가짜_마디를_만들지_않는다() {
        // 가는 선(495) + 굵은 선(497~500, 폭 3pt 라 마디선 후보가 아님). 오선 끝은 500.
        // 예전 규칙(4pt 초과면 오선 끝 추가)은 495~500 을 마디로 잡았다 — 실기기 몰다우.pdf 의 가짜 마디 "64"
        val bottoms = systemStaves(420f)
        val thick = bottoms.map { PathBox(497f, it, 500f, it + 20f, curved = false) }
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 350f, 495f)) + thick, pageHeight).single()
        assertEquals(listOf(50f, 200f, 350f, 495f), s.barlines)
        assertEquals(3, s.measureCount)
    }

    @Test
    fun 음표_머리가_붙은_세로선은_기둥이다() {
        val bottoms = systemStaves(420f)
        val stems = bottoms.flatMap { b -> listOf(bar(280f, b), head(280f, b)) }
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 500f)) + stems, pageHeight).single()
        assertEquals("16분음표가 빽빽한 마디에서 기둥이 마디선으로 잡히던 문제(#037)", listOf(50f, 200f, 500f), s.barlines)
    }

    @Test
    fun 일부_보표에만_있는_세로선은_마디선이_아니다() {
        val bottoms = systemStaves(420f)
        // 5개 보표 중 2개에만 — max(3, 5-1) = 4 개 이상이어야 마디선
        val partial = bottoms.take(2).map { bar(280f, it) }
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 500f)) + partial, pageHeight).single()
        assertEquals(listOf(50f, 200f, 500f), s.barlines)
    }

    @Test
    fun 붙어있는_겹세로줄은_더_많은_보표에_있는_쪽_하나만() {
        val bottoms = systemStaves(420f)
        val thin = bottoms.take(4).map { bar(350f, it) }   // 4개 보표
        val thick = bottoms.map { bar(358f, it) }          // 5개 보표, 8pt 옆
        val s = StaffSystemDetector.detect(systemWithBars(420f, listOf(200f, 500f)) + thin + thick, pageHeight).single()
        assertEquals(listOf(50f, 200f, 358f, 500f), s.barlines)
    }

    @Test
    fun 시스템은_페이지_위부터_정렬한다() {
        // 아래 시스템(y 100~300)을 먼저 넣어도 위 시스템(y 420~620)이 먼저 나와야 한다
        val lower = systemWithBars(100f, listOf(300f, 500f))
        val upper = systemWithBars(420f, listOf(200f, 350f, 500f))
        val systems = StaffSystemDetector.detect(lower + upper, pageHeight)
        assertEquals(2, systems.size)
        assertEquals(3, systems[0].measureCount)
        assertEquals(2, systems[1].measureCount)
        assertTrue(systems[0].top < systems[1].top)
    }

    // ── MuseScore 형식 (#056): 오선을 마디마다 끊어 그림 · 마디선이 보표 사이를 이음 · 음표 머리가 글자 · 보표 2개 시스템 ──

    /** 보표 2개(아래 보표 아랫선 [lowBottom], 보표 사이 25pt), 오선은 [bars] 사이마다 끊은 조각 */
    private fun museScoreSystem(lowBottom: Float, bars: List<Float>): List<PathBox> {
        val bottoms = listOf(lowBottom, lowBottom + 45f)
        val edges = listOf(50f) + bars
        val lines = bottoms.flatMap { b -> edges.zipWithNext().flatMap { (x0, x1) -> staff(b, x0, x1) } }
        // 마디선: 위 보표 윗선 → 아래 보표 윗선 (보표 사이를 잇는다), 아래 보표 윗선 → 아랫선
        val barPieces = (listOf(50f) + bars).flatMap { x ->
            listOf(
                PathBox(x - 0.4f, lowBottom + 20f, x + 0.4f, lowBottom + 65f, curved = false),
                PathBox(x - 0.4f, lowBottom, x + 0.4f, lowBottom + 20f, curved = false),
            )
        }
        return lines + barPieces
    }

    @Test
    fun 보표_두_개_시스템의_이어_그린_마디선과_끊어_그린_오선() {
        // 60pt 짜리 짧은 마디 — 조각 하나로는 오선 폭 기준(80pt)에 못 미친다
        val s = StaffSystemDetector.detect(museScoreSystem(420f, listOf(110f, 250f, 400f)), pageHeight).single()
        assertEquals(2, s.staffBands.size)
        assertEquals(listOf(50f, 110f, 250f, 400f), s.barlines)
        assertEquals(3, s.measureCount)
    }

    @Test
    fun 연결선이_있으면_간격이_좁아도_시스템을_나눈다() {
        // 두 시스템 사이 간격 30pt (< 55pt) — 간격 규칙이면 보표 4개가 한 시스템이 된다
        val upper = museScoreSystem(500f, listOf(200f, 400f))
        val lower = museScoreSystem(405f, listOf(300f, 400f))
        val systems = StaffSystemDetector.detect(upper + lower, pageHeight)
        assertEquals(listOf(2, 2), systems.map { it.staffBands.size })
        assertEquals(listOf(2, 2), systems.map { it.measureCount })
    }

    @Test
    fun 떨어진_가로_조각은_합치지_않는다() {
        // 셋잇단 괄호처럼 같은 높이에 띄엄띄엄 있는 짧은 선은 오선이 아니다
        val brackets = (0 until 5).map { k -> line(60f + k * 50f, 100f + k * 50f, 300f) }
        assertTrue(StaffSystemDetector.detect(brackets, pageHeight).isEmpty())
    }

    @Test
    fun 글자로_찍힌_음표_머리가_붙은_세로선은_기둥이다() {
        val boxes = museScoreSystem(420f, listOf(250f, 400f))
        // 두 보표를 모두 덮는 기둥 두 조각 (마디선과 같은 모양) + 위 끝에 SMuFL 음표 머리 글자 (원점 = 왼쪽, 세로 가운데)
        val stem = listOf(
            PathBox(180f - 0.4f, 440f, 180f + 0.4f, 485f, curved = false),
            PathBox(180f - 0.4f, 420f, 180f + 0.4f, 440f, curved = false),
        )
        val heads = listOf(TextRun("\uE0A4", 176f, 485f, 19f), TextRun("\uE0A4", 176f, 440f, 19f))
        assertEquals(
            "음표 머리 글자가 없으면 마디선으로 보인다 — 이 테스트의 기둥이 실제로 속일 수 있는 모양인지 확인",
            listOf(50f, 180f, 250f, 400f),
            StaffSystemDetector.detect(boxes + stem, pageHeight).single().barlines,
        )
        assertEquals(listOf(50f, 250f, 400f), StaffSystemDetector.detect(boxes + stem, pageHeight, heads).single().barlines)
    }

    @Test
    fun 곡선_경로는_오선이_아니다() {
        val curvedLines = (0 until 5).map { PathBox(50f, 400f + it * 5f, 500f, 400.5f + it * 5f, curved = true) }
        assertTrue(StaffSystemDetector.detect(curvedLines, pageHeight).isEmpty())
    }

    @Test
    fun 마디_번호는_페이지와_시스템을_넘어_이어진다() {
        fun system(top: Float, vararg bars: Float) = SystemLayout(top, top + 200f, bars.first(), bars.last(), emptyList(), bars.toList())
        val layout = ScoreLayout(listOf(
            PageLayout(0, 595f, 842f, listOf(system(100f, 50f, 200f, 350f), system(400f, 50f, 300f, 500f, 550f))),
            PageLayout(1, 595f, 842f, emptyList()),
            PageLayout(2, 595f, 842f, listOf(system(100f, 50f, 500f))),
        ))
        val measures = layout.toMeasures("file-1")

        assertEquals(6, layout.measureCount)
        assertEquals((1..6).toList(), measures.map { it.measureNumber })
        assertEquals(listOf(0, 0, 0, 0, 0, 2), measures.map { it.pageIndex })
        assertEquals(listOf(0, 0, 1, 1, 1, 0), measures.map { it.systemIndex })
        val third = measures[2]
        assertEquals(50f, third.leftPt)
        assertEquals(300f, third.rightPt)
        assertEquals(400f, third.topPt)
        assertEquals(600f, third.bottomPt)
    }
}
