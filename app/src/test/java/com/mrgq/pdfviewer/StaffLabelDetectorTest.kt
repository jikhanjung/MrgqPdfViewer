package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.StaffLabelDetector
import com.mrgq.pdfviewer.score.SystemLayout
import com.mrgq.pdfviewer.score.TextRun
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 보표 이름 읽기 (P07). 시스템 = 보표 4개(높이 20pt, 간격 45pt), 왼쪽 끝 x=80. 텍스트는 해석기처럼 PDF y-up 으로 넣는다.
 */
class StaffLabelDetectorTest {

    private val pageHeight = 842f

    private val system = SystemLayout(
        top = 100f,
        bottom = 255f,
        left = 80f,
        right = 560f,
        staffBands = (0 until 4).map { k -> (100f + k * 45f) to (120f + k * 45f) },
        barlines = listOf(80f, 300f, 560f),
    )

    /** 보표 [staff] 가운데 높이에 기준선을 둔 텍스트 */
    private fun label(text: String, staff: Int, x: Float = 20f, dy: Float = 0f): TextRun {
        val (top, bottom) = system.staffBands[staff]
        return TextRun(text, x, pageHeight - ((top + bottom) / 2 + 4f + dy), 12f)
    }

    private fun labels(vararg runs: TextRun) = StaffLabelDetector.attach(runs.toList(), listOf(system), pageHeight).single().staffLabels

    @Test
    fun 보표마다_왼쪽_이름을_읽는다() {
        assertEquals(
            listOf("Violin I", "Violin II", "Viola", "Cello"),
            labels(label("Violin I", 0), label("Violin II", 1), label("Viola", 2), label("Cello", 3)),
        )
    }

    @Test
    fun 못_읽은_보표는_null() {
        assertEquals(listOf("진호", null, null, "은석"), labels(label("진호", 0), label("은석", 3)))
    }

    @Test
    fun 시스템_안의_글자와_숫자는_이름이_아니다() {
        val inSystem = label("pizz.", 1, x = 120f)
        val measureNumber = TextRun("5", 60f, pageHeight - 92f, 10f) // 시스템 왼쪽 위 마디 번호
        val digitsBesideStaff = label("12", 2)
        assertEquals(listOf(null, null, null, null), labels(inSystem, measureNumber, digitsBesideStaff))
    }

    @Test
    fun 여러_조각은_위에서_아래_왼쪽에서_오른쪽으로_잇는다() {
        // 두 줄 이름: "Clarinet" / "in B♭", 같은 줄은 조각이 둘
        val runs = arrayOf(
            label("in", 2, x = 22f, dy = 6f),
            label("B♭", 2, x = 34f, dy = 6f),
            label("Clarinet", 2, x = 20f, dy = -6f),
        )
        assertEquals("Clarinet in B♭", labels(*runs)[2])
    }

    @Test
    fun 한_글자씩_찍힌_이름은_붙여_읽고_띄어_쓴_곳만_띄운다() {
        // MuseScore: "Guitar 1" 을 글자마다 따로 (글꼴 12pt, 글자 간격 6pt, 띄어쓰기는 한 칸 더)
        val xs = listOf(20f, 26f, 32f, 38f, 44f, 50f, 62f)
        val runs = "Guitar1".mapIndexed { i, c -> label(c.toString(), 1, x = xs[i]) }.toTypedArray()
        assertEquals("Guitar 1", labels(*runs)[1])
    }

    @Test
    fun 보표_사이의_이름은_가까운_보표로() {
        // 보표 0 과 1 사이(가운데 y 132.5)보다 조금 위 → 보표 0
        val between = TextRun("Horn", 20f, pageHeight - (128f + 4f), 12f)
        assertEquals(listOf("Horn", null, null, null), labels(between))
    }

    @Test
    fun 보표가_없는_시스템은_빈_목록() {
        val empty = system.copy(staffBands = emptyList())
        assertEquals(emptyList<String?>(), StaffLabelDetector.attach(listOf(label("x", 0)), listOf(empty), pageHeight).single().staffLabels)
    }
}
