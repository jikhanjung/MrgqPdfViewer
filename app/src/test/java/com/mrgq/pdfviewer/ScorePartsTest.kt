package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreStaff
import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.ScorePart
import com.mrgq.pdfviewer.score.ScoreParts
import com.mrgq.pdfviewer.score.SystemLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** 총보의 파트 목록 (P07 §2.1) — 파트 = 보표 순번, 시스템마다 보표 수가 같을 때만 */
class ScorePartsTest {

    private fun system(top: Float, staves: Int, labels: List<String?> = emptyList()) = SystemLayout(
        top = top,
        bottom = top + staves * 45f,
        left = 80f,
        right = 560f,
        staffBands = (0 until staves).map { k -> (top + k * 45f) to (top + k * 45f + 20f) },
        barlines = listOf(80f, 300f, 560f),
        staffLabels = labels,
    )

    private fun staves(vararg pages: List<SystemLayout>): List<ScoreStaff> =
        ScoreLayout(pages.mapIndexed { i, systems -> PageLayout(i, 595f, 842f, systems) }).toStaves("f")

    @Test
    fun 보표_수가_같으면_순번이_파트_이름은_앞쪽_시스템에서() {
        val result = ScoreParts.of(staves(
            listOf(system(100f, 3, listOf("Violin I", null, "Cello")), system(400f, 3, listOf("Vn. I", "Vn. II", "Vc."))),
            listOf(system(100f, 3)),
        ))
        assertEquals(
            ScoreParts.Result.Parts(listOf(
                ScorePart(0, "Violin I", named = true),
                ScorePart(1, "Vn. II", named = true), // 첫 시스템에 없으면 다음 시스템의 짧은 이름
                ScorePart(2, "Cello", named = true),
            )),
            result,
        )
    }

    @Test
    fun 이름을_못_읽으면_보표_n() {
        val result = ScoreParts.of(staves(listOf(system(100f, 2), system(400f, 2))))
        assertEquals(
            ScoreParts.Result.Parts(listOf(ScorePart(0, "보표 1", named = false), ScorePart(1, "보표 2", named = false))),
            result,
        )
    }

    @Test
    fun 시스템마다_보표_수가_다르면_지원하지_않는다() {
        // 빈 보표 숨기기 — 둘째 시스템은 쉬는 파트가 빠졌다
        assertEquals(ScoreParts.Result.VaryingStaves(setOf(4, 3)), ScoreParts.of(staves(listOf(system(100f, 4), system(400f, 3)))))
    }

    @Test
    fun 보표가_하나면_떼어_볼_파트가_없다() {
        assertEquals(ScoreParts.Result.SingleStaff, ScoreParts.of(staves(listOf(system(100f, 1), system(300f, 1)))))
    }

    @Test
    fun 보표가_없으면_NoStaves() {
        assertEquals(ScoreParts.Result.NoStaves, ScoreParts.of(emptyList()))
    }

    @Test
    fun 보표_좌표와_이름을_그대로_저장한다() {
        val saved = staves(emptyList(), listOf(system(100f, 2, listOf("A", null))))
        assertEquals(
            listOf(
                ScoreStaff("f", 1, 0, 0, 100f, 120f, "A"),
                ScoreStaff("f", 1, 0, 1, 145f, 165f, null),
            ),
            saved,
        )
    }
}
