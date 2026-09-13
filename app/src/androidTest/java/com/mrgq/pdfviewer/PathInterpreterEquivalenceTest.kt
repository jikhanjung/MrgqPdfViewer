package com.mrgq.pdfviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrgq.pdfviewer.score.PathBox
import com.mrgq.pdfviewer.score.PathContentInterpreter
import com.mrgq.pdfviewer.score.PdfBoxContent
import com.mrgq.pdfviewer.score.PdfBoxXObjects
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 경량 해석기가 PdfBox 엔진(기준 구현)과 **같은 경로 박스**를 내는가 — Moldau 총보 전 페이지.
 *
 * 골든 테스트(ScoreLayoutAnalyzerTest)는 판정 결과만 본다. 이 테스트는 그 앞 단계인 박스를 하나하나 대조해,
 * 해석기가 우연히 같은 마디를 내는 게 아니라 같은 입력을 만든다는 것을 보인다.
 */
@RunWith(AndroidJUnit4::class)
class PathInterpreterEquivalenceTest {

    @Test
    fun 모든_페이지에서_PdfBox_엔진과_같은_박스() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        PDFBoxResourceLoader.init(target)
        val pdf = File(target.cacheDir, "equivalence.pdf")
        instrumentation.context.assets.open("score/Moldau0607.pdf").use { i -> pdf.outputStream().use { i.copyTo(it) } }

        var total = 0
        PDDocument.load(pdf).use { doc ->
            doc.pages.forEachIndexed { index, page ->
                val reference = PdfBoxPathCollector(page).also { it.processPage(page) }.boxes

                val crop = page.cropBox
                val ours = ArrayList<PathBox>()
                PathContentInterpreter(crop.lowerLeftX, crop.lowerLeftY) { ours += it }
                    .run(PdfBoxContent.pageContent(page), PdfBoxXObjects(page.resources))

                val where = "p${index + 1}"
                assertEquals("$where 박스 수", reference.size, ours.size)
                reference.zip(ours).forEachIndexed { k, (r, o) ->
                    assertEquals("$where 박스 $k 곡선 여부", r.curved, o.curved)
                    for ((name, a, b) in listOf(
                        Triple("x0", r.x0, o.x0), Triple("y0", r.y0, o.y0),
                        Triple("x1", r.x1, o.x1), Triple("y1", r.y1, o.y1),
                    )) {
                        assertTrue("$where 박스 $k $name: PdfBox $a vs 해석기 $b", abs(a - b) <= TOLERANCE)
                    }
                }
                total += ours.size
            }
        }
        assertTrue("박스가 하나도 없으면 대조가 공회전한다", total > 1000)
    }

    private companion object {
        /** 숫자 파싱(Float.parseFloat vs 자릿수 누적)과 행렬 곱 순서에서 오는 반올림 차이만 허용 */
        const val TOLERANCE = 0.01f
    }
}
