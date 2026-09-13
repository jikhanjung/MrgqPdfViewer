package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.SystemLayout
import com.mrgq.pdfviewer.score.TextRun
import com.mrgq.pdfviewer.score.TimeSignatureDetector
import com.mrgq.pdfviewer.score.TimeSignatureMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 박자표 찾기 규칙과 마디 적용. 실제 악보(Moldau 6/8)는 계측 골든 테스트가 본다.
 *
 * 시스템 = 보표 5개(높이 20pt, 간격 25pt), 위→아래 좌표. 텍스트는 해석기처럼 PDF y-up 으로 넣는다.
 */
class TimeSignatureDetectorTest {

    private val pageHeight = 842f

    private fun system(top: Float, barlines: List<Float> = listOf(50f, 200f, 350f, 500f)) = SystemLayout(
        top = top,
        bottom = top + 200f,
        left = barlines.first(),
        right = barlines.last(),
        staffBands = (0 until 5).map { k -> (top + k * 45f) to (top + k * 45f + 20f) },
        barlines = barlines,
    )

    /** 보표 [staff] 에 분자·분모를 Moldau 처럼 x 가 같게, 보표 높이 절반 간격으로 놓는다. */
    private fun signatureOn(system: SystemLayout, staff: Int, x: Float, numerator: Int, denominator: Int): List<TextRun> {
        val (top, _) = system.staffBands[staff]
        return listOf(
            TextRun(numerator.toString(), x, pageHeight - (top + 8f), 16f),
            TextRun(denominator.toString(), x, pageHeight - (top + 18f), 16f),
        )
    }

    private fun onAllStaves(system: SystemLayout, x: Float, numerator: Int, denominator: Int) =
        (0 until 5).flatMap { signatureOn(system, it, x, numerator, denominator) }

    @Test
    fun 모든_보표에_있는_6_8_을_찾는다() {
        val s = system(100f)
        val marks = TimeSignatureDetector.detect(onAllStaves(s, 70f, 6, 8), listOf(s), pageHeight)
        assertEquals(listOf(TimeSignatureMark(0, 70f, 6, 8)), marks)
    }

    @Test
    fun 보표_과반에_없으면_박자표가_아니다() {
        val s = system(100f)
        val twoStaves = signatureOn(s, 0, 70f, 3, 4) + signatureOn(s, 1, 70f, 3, 4)
        assertTrue(TimeSignatureDetector.detect(twoStaves, listOf(s), pageHeight).isEmpty())
    }

    @Test
    fun 분모가_2의_거듭제곱이_아니면_박자표가_아니다() {
        val s = system(100f)
        assertTrue(TimeSignatureDetector.detect(onAllStaves(s, 70f, 6, 5), listOf(s), pageHeight).isEmpty())
    }

    @Test
    fun 혼자_있는_마디_번호나_운지는_무시한다() {
        val s = system(100f)
        val numbers = listOf(
            TextRun("5", 50f, pageHeight - 90f, 12f),   // 보표 위 마디 번호
            TextRun("0", 140f, pageHeight - 125f, 8f),  // 운지
            TextRun("pizz.", 60f, pageHeight - 310f, 10f),
        )
        assertTrue(TimeSignatureDetector.detect(numbers + onAllStaves(s, 70f, 6, 8), listOf(s), pageHeight)
            .all { it.numerator == 6 && it.denominator == 8 })
        assertTrue(TimeSignatureDetector.detect(numbers, listOf(s), pageHeight).isEmpty())
    }

    @Test
    fun 시스템_중간의_박자_바뀜도_찾는다() {
        val s = system(100f)
        val marks = TimeSignatureDetector.detect(onAllStaves(s, 70f, 6, 8) + onAllStaves(s, 210f, 3, 4), listOf(s), pageHeight)
        assertEquals(listOf(TimeSignatureMark(0, 70f, 6, 8), TimeSignatureMark(0, 210f, 3, 4)), marks)
    }

    @Test
    fun 두_번째_시스템의_박자표는_그_시스템_번호로() {
        val upper = system(100f)
        val lower = system(450f)
        val marks = TimeSignatureDetector.detect(onAllStaves(lower, 70f, 2, 4), listOf(upper, lower), pageHeight)
        assertEquals(listOf(TimeSignatureMark(1, 70f, 2, 4)), marks)
    }

    @Test
    fun 박자표는_놓인_마디부터_다음_박자표_전까지_적용된다() {
        val first = system(100f)                                   // 마디 1~3
        val second = system(450f)                                  // 마디 4~6
        val layout = ScoreLayout(listOf(
            PageLayout(0, 595f, 842f, listOf(first, second), listOf(
                TimeSignatureMark(0, 70f, 6, 8),                    // 마디 1 안
                TimeSignatureMark(1, 210f, 3, 4),                   // 마디 5 (200~350) 안
            )),
            PageLayout(1, 595f, 842f, listOf(system(100f))),       // 마디 7~9: 이어서 3/4
        ))
        val signatures = layout.toMeasures("f").map { it.timeSigNumerator to it.timeSigDenominator }
        assertEquals(
            listOf(6 to 8, 6 to 8, 6 to 8, 6 to 8, 3 to 4, 3 to 4, 3 to 4, 3 to 4, 3 to 4),
            signatures
        )
    }

    @Test
    fun 첫_박자표_이전_마디는_박자를_모른다() {
        val s = system(100f)
        val layout = ScoreLayout(listOf(PageLayout(0, 595f, 842f, listOf(s), listOf(TimeSignatureMark(0, 210f, 3, 4)))))
        val signatures = layout.toMeasures("f").map { it.timeSigNumerator }
        assertEquals(listOf(null, 3, 3), signatures)
    }
}
