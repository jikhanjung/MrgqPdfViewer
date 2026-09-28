package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.ServerLayouts
import com.mrgq.pdfviewer.score.TimeSignatureMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 서버 분석 파일 읽기 (P06 §12) — 앱 ScoreLayout 모양 그대로 */
class ServerLayoutsTest {

    private val json = """
        {"format": "scoremate-score-layout", "format_version": 1, "analyzer": "score-layout", "analyzer_version": "1+app.9557497",
         "app_commit": "9557497", "pdf_sha256": "abc", "page_count": 2, "system_count": 1, "measure_count": 2,
         "pages": [
           {"pageIndex": 0, "widthPt": 595.3, "heightPt": 841.9,
            "systems": [{"top": 100, "bottom": 165, "left": 80, "right": 560,
                         "staffBands": [[100, 120], [145, 165]], "barlines": [80, 300, 560], "staffLabels": ["Guitar 1", null]}],
            "timeSignatures": [{"systemIndex": 0, "x": 105.9, "numerator": 9, "denominator": 8}]},
           {"pageIndex": 1, "widthPt": 595.3, "heightPt": 841.9, "systems": [], "timeSignatures": []}
         ],
         "measures": [], "staves": []}
    """.trimIndent()

    @Test
    fun ScoreLayout_으로_읽어_두_테이블로_펼친다() {
        val parsed = ServerLayouts.parse(json)!!
        assertEquals("1+app.9557497", parsed.analyzerVersion)
        assertEquals("abc", parsed.pdfSha256)
        val layout = parsed.layout
        assertEquals(2, layout.pages.size)
        assertEquals(2, layout.measureCount)
        assertEquals(listOf(TimeSignatureMark(0, 105.9f, 9, 8)), layout.pages[0].timeSignatures)
        val measures = layout.toMeasures("f")
        assertEquals(listOf(1, 2), measures.map { it.measureNumber })
        assertEquals(listOf(9, 9), measures.map { it.timeSigNumerator })
        assertEquals(300f, measures[0].rightPt)
        val staves = layout.toStaves("f")
        assertEquals(listOf("Guitar 1", null), staves.map { it.label })
        assertEquals(145f, staves[1].topPt)
    }

    @Test
    fun 깨졌으면_null() {
        assertNull(ServerLayouts.parse("{"))
        assertNull(ServerLayouts.parse("""{"pages": [{"pageIndex": 0}]}"""))
    }
}
