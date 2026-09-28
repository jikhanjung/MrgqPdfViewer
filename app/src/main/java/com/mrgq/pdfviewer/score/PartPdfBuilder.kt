package com.mrgq.pdfviewer.score

import android.util.Log
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * [PartLayout] 을 **벡터 PDF** 로 만든다 (P07 §2.3). 원본 쪽을 폼(Form XObject)으로 한 번씩 가져와, 조각마다 사각형으로 잘라(clip)
 * 제자리로 옮겨(translate) 그린다 — 래스터로 바꾸지 않아 선명도가 원본과 같다(#042 의 1× · 정수 좌표 원칙은 뷰어 렌더가 그대로 지킨다).
 * 만든 PDF 는 앱 캐시에만 둔다 — 자기 총보를 자기 기기에서 다르게 보는 것일 뿐, 어디에도 보내지 않는다 (P07 §0 저작권).
 *
 * 사용 전 `PDFBoxResourceLoader.init` 이 필요하다 (PdfViewerApplication).
 */
object PartPdfBuilder {

    private const val TAG = "PartPdfBuilder"
    private const val MEASURE_NUMBER_SIZE = 7f

    /** [source] 의 파트 [layout] 을 [out] 에 쓴다. 쓰는 중에는 `.part` 로 두어 끊긴 파일이 쓰이지 않게 한다 */
    fun build(source: File, layout: PartLayout, out: File) {
        val started = System.currentTimeMillis()
        out.parentFile?.mkdirs()
        val temp = File(out.path + ".part")
        PDDocument.load(source).use { src ->
            PDDocument().use { dst ->
                val layer = LayerUtility(dst)
                val forms = HashMap<Int, PDFormXObject>()
                val pages = (0 until layout.pageCount).map {
                    PDPage(PDRectangle(layout.pageWidth, layout.pageHeight)).also { page -> dst.addPage(page) }
                }
                for ((pageIndex, page) in pages.withIndex()) {
                    PDPageContentStream(dst, page).use { cs ->
                        for (strip in layout.strips.filter { it.dstPage == pageIndex }) {
                            val srcPage = src.getPage(strip.srcPage)
                            val crop = srcPage.cropBox
                            val form = forms.getOrPut(strip.srcPage) { layer.importPageAsForm(src, strip.srcPage) }
                            // 위→아래 좌표를 PDF(아래→위)로: 원본 조각 아래 = crop 높이 − srcBottom, 가상 쪽 조각 아래 = 쪽 높이 − dstBottom
                            val dstBottomUp = layout.pageHeight - strip.dstBottom
                            val srcBottomUp = crop.height - strip.srcBottom
                            cs.saveGraphicsState()
                            cs.addRect(strip.srcLeft, dstBottomUp, strip.srcRight - strip.srcLeft, strip.height)
                            cs.clip()
                            // importPageAsForm 의 폼 행렬이 crop 원점을 (0,0) 으로 옮겨 둔다 — 조각 높이만큼만 옮긴다
                            cs.transform(Matrix.getTranslateInstance(0f, dstBottomUp - srcBottomUp))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()

                            strip.firstMeasure?.let { number ->
                                cs.beginText()
                                cs.setFont(PDType1Font.HELVETICA, MEASURE_NUMBER_SIZE)
                                cs.newLineAtOffset(strip.srcLeft, layout.pageHeight - strip.dstTop - MEASURE_NUMBER_SIZE)
                                cs.showText(number.toString())
                                cs.endText()
                            }
                        }
                    }
                }
                dst.save(temp)
            }
        }
        if (!temp.renameTo(out)) {
            temp.delete()
            throw java.io.IOException("파트보를 저장하지 못했습니다: $out")
        }
        Log.i(TAG, "${source.name} 보표 ${layout.staffIndex + 1}: ${layout.strips.size}줄 → ${layout.pageCount}쪽 (${System.currentTimeMillis() - started}ms)")
    }

    /**
     * 캐시 자리 — 원본이 바뀌면(크기 · 수정 시각) 다른 이름이 된다. [FORMAT] 은 배치 규칙이 바뀌면 올린다.
     * 같은 원본 · 보표의 옛 파일은 [prune] 이 지운다.
     */
    fun cacheFile(cacheDir: File, pdfFileId: String, source: File, staffIndex: Int): File =
        File(File(cacheDir, "parts"), "${pdfFileId}_${source.length()}_${source.lastModified()}_s${staffIndex}_f$FORMAT.pdf")

    /** [keep] 과 같은 파일의 다른 캐시(옛 원본 · 옛 형식)를 지운다 — 보표가 다른 것은 둔다 */
    fun prune(keep: File, pdfFileId: String, staffIndex: Int) {
        keep.parentFile?.listFiles()?.forEach { f ->
            if (f != keep && f.name.startsWith("${pdfFileId}_") && f.name.contains("_s${staffIndex}_")) f.delete()
        }
    }

    private const val FORMAT = 1
}
