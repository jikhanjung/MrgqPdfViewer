package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.PartLayout
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.SystemLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 파트보 배치 (P07 §2.2). 쪽 = A4(595×842), 시스템 = 보표 3개(높이 20pt, 보표 사이 40pt → 이웃과 가운데까지 20pt),
 * 마디선 80 · 300 · 560. 쪽마다 시스템 2개.
 */
class PartLayoutTest {

    private fun system(top: Float) = SystemLayout(
        top = top,
        bottom = top + 140f,
        left = 80f,
        right = 560f,
        staffBands = (0 until 3).map { k -> (top + k * 60f) to (top + k * 60f + 20f) },
        barlines = listOf(80f, 300f, 560f),
    )

    private fun score(pages: Int) = ScoreLayout((0 until pages).map { p -> PageLayout(p, 595f, 842f, listOf(system(100f), system(400f))) })

    private fun layout(pages: Int, staff: Int): PartLayout? {
        val s = score(pages)
        return PartLayout.build(s.toStaves("f"), s.toMeasures("f"), staff)
    }

    @Test
    fun 가운데_보표는_이웃과의_가운데까지() {
        val strip = layout(1, 1)!!.strips.first()
        // 보표 1: 160~180, 위 이웃 아래 120 → 가운데 140, 아래 이웃 위 220 → 가운데 200
        assertEquals(140f, strip.srcTop)
        assertEquals(200f, strip.srcBottom)
        assertEquals(80f - PartLayout.LEFT_PAD, strip.srcLeft)
        assertEquals(560f + PartLayout.RIGHT_PAD, strip.srcRight)
    }

    @Test
    fun 맨_위_맨_아래_보표는_반대쪽_간격을_쓴다() {
        val top = layout(1, 0)!!.strips.first()
        assertEquals(80f, top.srcTop)     // 100 - 20
        assertEquals(140f, top.srcBottom) // 120 + 20
        val bottom = layout(1, 2)!!.strips.first()
        assertEquals(200f, bottom.srcTop)
        assertEquals(260f, bottom.srcBottom)
    }

    @Test
    fun 위에서부터_쌓고_넘치면_다음_쪽() {
        // 띠 높이 60 + 간격 4 → 쪽(842, 위 36 · 아래 28)에 12줄: 36 + 12×64 = 804 ≤ 814, 13번째는 넘친다
        val part = layout(7, 1)!!
        assertEquals(14, part.strips.size)
        assertEquals(2, part.pageCount)
        val firstPage = part.strips.filter { it.dstPage == 0 }
        assertEquals(12, firstPage.size)
        assertEquals(PartLayout.TOP_MARGIN, firstPage.first().dstTop)
        assertTrue(firstPage.last().dstBottom <= part.pageHeight - PartLayout.BOTTOM_MARGIN)
        assertEquals(PartLayout.TOP_MARGIN, part.strips[12].dstTop)
        // 순서는 원본 순서 그대로, 쪽 크기는 원본 쪽
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), part.strips.take(3).map { it.srcPage to it.srcSystem })
        assertEquals(595f to 842f, part.pageWidth to part.pageHeight)
    }

    @Test
    fun 조각마다_첫_마디_번호() {
        // 시스템마다 마디 2개 → 1, 3, 5, …
        assertEquals(listOf(1, 3, 5, 7), layout(2, 0)!!.strips.map { it.firstMeasure })
    }

    @Test
    fun 없는_보표나_마디가_없으면_null() {
        assertNull(layout(1, 5))
        assertNull(PartLayout.build(score(1).toStaves("f"), emptyList(), 0))
    }
}
