package com.mrgq.pdfviewer

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.ScoreLayoutAnalyzer
import com.mrgq.pdfviewer.score.ScoreOverlayGeometry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 악보 구조 분석 골든 테스트 — Kotlin(PdfBox) 결과가 파이썬(`data/segment_score.py`) 결과와 같은가.
 *
 * 픽스처는 `data/` 에서 복사한 것이다:
 *  - `score/Moldau0607.pdf` — Sibelius → Microsoft Print to PDF 5파트 총보, 13쪽
 *  - `score/Moldau0607_layout.json` — 파이썬 분석 결과. 26 시스템 / 81 마디이고,
 *    검출 마디 번호가 인쇄된 번호와 일치함을 육안 대조로 확인한 데이터다 (#037)
 */
@RunWith(AndroidJUnit4::class)
class ScoreLayoutAnalyzerTest {

    companion object {
        private const val TOL = 0.6f

        private lateinit var pdf: File
        private lateinit var layout: ScoreLayout
        private lateinit var golden: JSONObject

        /** 13쪽 분석은 한 번만 한다. */
        @BeforeClass
        @JvmStatic
        fun analyzeOnce() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            PDFBoxResourceLoader.init(target)

            pdf = File(target.cacheDir, "Moldau0607.pdf")
            instrumentation.context.assets.open("score/Moldau0607.pdf").use { input ->
                pdf.outputStream().use { input.copyTo(it) }
            }
            golden = JSONObject(
                instrumentation.context.assets.open("score/Moldau0607_layout.json").bufferedReader().use { it.readText() }
            )
            layout = requireNotNull(ScoreLayoutAnalyzer.analyze(pdf)) { "분석이 null — PDF 를 열지 못했다" }
        }
    }

    private fun goldenSystems(pageIndex: Int): JSONArray = golden.getJSONArray((pageIndex + 1).toString())

    private fun floats(array: JSONArray) = (0 until array.length()).map { array.getDouble(it).toFloat() }

    @Test
    fun 전체_26_시스템_81_마디() {
        assertEquals(13, layout.pages.size)
        assertEquals(26, layout.pages.sumOf { it.systems.size })
        assertEquals(81, layout.measureCount)
    }

    @Test
    fun 페이지별_시스템_수와_시스템별_마디_수가_파이썬과_같다() {
        for (page in layout.pages) {
            val expected = goldenSystems(page.pageIndex)
            assertEquals("p${page.pageIndex + 1} 시스템 수", expected.length(), page.systems.size)
            for ((i, system) in page.systems.withIndex()) {
                assertEquals(
                    "p${page.pageIndex + 1} 시스템 ${i + 1} 마디 수",
                    expected.getJSONObject(i).getInt("n_measures"),
                    system.measureCount
                )
            }
        }
    }

    @Test
    fun 마디선_좌표가_파이썬과_같다() {
        for (page in layout.pages) {
            for ((i, system) in page.systems.withIndex()) {
                val expected = floats(goldenSystems(page.pageIndex).getJSONObject(i).getJSONArray("barlines"))
                assertEquals("p${page.pageIndex + 1} s${i + 1} 마디선 개수", expected.size, system.barlines.size)
                expected.zip(system.barlines).forEachIndexed { k, (e, a) ->
                    assertEquals("p${page.pageIndex + 1} s${i + 1} 마디선 $k", e, a, TOL)
                }
            }
        }
    }

    @Test
    fun 시스템_세로_범위와_보표_밴드가_파이썬과_같다() {
        for (page in layout.pages) {
            for ((i, system) in page.systems.withIndex()) {
                val expected = goldenSystems(page.pageIndex).getJSONObject(i)
                val where = "p${page.pageIndex + 1} s${i + 1}"
                assertEquals("$where y_top", expected.getDouble("y_top").toFloat(), system.top, TOL)
                assertEquals("$where y_bot", expected.getDouble("y_bot").toFloat(), system.bottom, TOL)
                assertEquals("$where x_left", expected.getDouble("x_left").toFloat(), system.left, TOL)
                assertEquals("$where x_right", expected.getDouble("x_right").toFloat(), system.right, TOL)

                val bands = expected.getJSONArray("staff_bands")
                assertEquals("$where 보표 수", bands.length(), system.staffBands.size)
                for (k in 0 until bands.length()) {
                    val (eTop, eBottom) = floats(bands.getJSONArray(k))
                    assertEquals("$where 보표 ${k + 1} 위", eTop, system.staffBands[k].first, TOL)
                    assertEquals("$where 보표 ${k + 1} 아래", eBottom, system.staffBands[k].second, TOL)
                }
            }
        }
    }

    @Test
    fun 마디_번호가_인쇄된_번호와_이어진다() {
        // 인쇄본의 시스템 첫 마디 번호: 1, 5, 8, 11, ... (#037 에서 육안 대조)
        val firstNumbers = layout.toMeasures("moldau")
            .groupBy { it.pageIndex to it.systemIndex }
            .values.map { it.first().measureNumber }
        assertEquals(listOf(1, 5, 8, 11, 14, 17), firstNumbers.take(6))
        assertEquals(81, layout.toMeasures("moldau").last().measureNumber)
    }

    @Test
    fun 박자표_6_8_을_읽어_모든_마디에_적용한다() {
        val marks = layout.pages.flatMap { page -> page.timeSignatures.map { page.pageIndex to it } }
        assertEquals("박자표는 1쪽 첫 시스템에 하나", 1, marks.size)
        val (pageIndex, mark) = marks.single()
        assertEquals(0, pageIndex)
        assertEquals(0, mark.systemIndex)
        assertEquals(6, mark.numerator)
        assertEquals(8, mark.denominator)
        assertTrue("박자표는 첫 마디선(212pt) 왼쪽: ${mark.x}", mark.x < 212f)

        val measures = layout.toMeasures("moldau")
        assertTrue("81 마디 전부 6/8", measures.all { it.timeSigNumerator == 6 && it.timeSigDenominator == 8 })
    }

    @Test
    fun 오버레이가_쓰는_페이지_크기가_PdfRenderer_와_같다() {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val analyzed = layout.pages[0]
                    assertEquals("폭", page.width, ScoreOverlayGeometry.rendererPoints(analyzed.widthPt))
                    assertEquals("높이", page.height, ScoreOverlayGeometry.rendererPoints(analyzed.heightPt))
                }
            }
        }
    }

    @Test
    fun 악보가_아닌_PDF_는_마디가_없다() {
        val plain = File(pdf.parentFile, "not-a-score.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.addRect(50f, 50f, 300f, 0.5f) // 오선처럼 가늘고 긴 선 하나 — 5줄이 안 된다
                content.fill()
                content.addRect(100f, 100f, 1f, 200f)
                content.fill()
            }
            doc.save(plain)
        }
        val result = ScoreLayoutAnalyzer.analyze(plain)
        assertNotNull(result)
        assertEquals(1, result!!.pages.size)
        assertTrue(result.pages[0].systems.isEmpty())
        assertEquals(0, result.measureCount)
    }
}
