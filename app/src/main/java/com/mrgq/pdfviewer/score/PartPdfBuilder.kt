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
    private const val MEASURE_NUMBER_SIZE = 11f
    private const val PAGE_NUMBER_SIZE = 9f
    /** 여러 파트일 때 보표 앞 파트 번호 (Pt. 2) */
    private const val PART_LABEL_SIZE = 10f
    /** 왼쪽 여백의 번호와 조각 사이 */
    private const val LABEL_GAP = 3f

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
                        for (strip in layout.strips) {
                            if (strip.dstPage != pageIndex) continue
                            val srcPage = src.getPage(strip.srcPage)
                            val crop = srcPage.cropBox
                            val form = forms.getOrPut(strip.srcPage) { layer.importPageAsForm(src, strip.srcPage) }
                            // 위→아래 좌표를 PDF(아래→위)로: 원본 조각 아래 = crop 높이 − srcBottom, 가상 쪽 조각 아래 = 쪽 높이 − dstBottom
                            val dstBottomUp = layout.pageHeight - strip.dstBottom
                            val srcBottomUp = crop.height - strip.srcBottom
                            cs.saveGraphicsState()
                            // 잘라 낼 모양 = 사각형들의 합 (같은 방향 re 들의 nonzero → 합집합). 원본 좌표 → 가상 쪽 좌표는 세로로만 옮긴다
                            for (clip in strip.clips) {
                                val bottomUp = layout.pageHeight - (strip.dstTop + (clip.bottom - strip.srcTop))
                                cs.addRect(clip.left, bottomUp, clip.right - clip.left, clip.bottom - clip.top)
                            }
                            cs.clip()
                            // importPageAsForm 의 폼 행렬이 crop 원점을 (0,0) 으로 옮겨 둔다 — 조각 높이만큼만 옮긴다
                            cs.transform(Matrix.getTranslateInstance(0f, dstBottomUp - srcBottomUp))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()

                        }
                        // 번호는 시스템(같은 원본 줄의 조각들)마다 — 여러 파트면 조각이 둘 이상이다
                        val onPage = layout.strips.withIndex().filter { it.value.dstPage == pageIndex }
                        for (group in onPage.groupBy { it.value.srcPage to it.value.srcSystem }.values) {
                            drawLabels(cs, layout, group.map { it.value }, showPage = layout.showsSourcePage(group.first().index))
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
        Log.i(TAG, "${source.name} 보표 ${layout.staves.joinToString { (it + 1).toString() }}: ${layout.strips.size}줄 → ${layout.pageCount}쪽 (${System.currentTimeMillis() - started}ms)")
    }

    /**
     * 시스템([group] = 같은 원본 줄의 조각들) 왼쪽 여백(원래 악기 이름이 있던 자리)에 번호를 오른쪽 맞춤으로 적는다.
     *  - 파트 하나: **원본 마디 번호**를 보표 가운데 조금 위에, 원본 쪽이 바뀌는 줄이면 그 아래 **원본 쪽 번호**(`p.7`)
     *  - 여러 파트 (사용자 요청 2026-09-28): 보표마다 앞에 **파트 번호**(`Pt. 2`, 굵게, 보표 가운데), 마디 · 쪽 번호는 **첫째와 둘째 보표 사이**
     *    (사이선 위에 마디, 아래에 쪽) — 파트 번호와 겹치지 않게
     * 여백이 좁으면 첫 조각 왼쪽 위 안쪽에 한 줄로.
     */
    private fun drawLabels(cs: PDPageContentStream, layout: PartLayout, group: List<PartStrip>, showPage: Boolean) {
        val first = group.first()
        val measure = first.firstMeasure?.toString()
        val page = if (showPage) "p.${first.srcPage + 1}" else null
        val font = PDType1Font.HELVETICA
        val bold = PDType1Font.HELVETICA_BOLD
        fun width(text: String, size: Float, f: PDType1Font = font) = f.getStringWidth(text) / 1000f * size
        fun draw(text: String, size: Float, x: Float, yDown: Float, f: PDType1Font = font) {
            cs.beginText()
            cs.setFont(f, size)
            cs.newLineAtOffset(x, layout.pageHeight - yDown)
            cs.showText(text)
            cs.endText()
        }
        // 보표들 (가상 쪽 좌표, 위→아래)
        data class Staff(val index: Int, val top: Float, val bottom: Float)
        val staves = group.flatMap { strip ->
            strip.staffMarks.map { Staff(it.index, strip.dstTop + (it.top - strip.srcTop), strip.dstTop + (it.bottom - strip.srcTop)) }
        }
        val multi = layout.staves.size > 1 && staves.size > 1
        val partLabels = if (multi) staves.map { "Pt. ${it.index + 1}" } else emptyList()
        val widest = listOfNotNull(
            measure?.let { width(it, MEASURE_NUMBER_SIZE) },
            page?.let { width(it, PAGE_NUMBER_SIZE) },
            partLabels.maxOfOrNull { width(it, PART_LABEL_SIZE, bold) },
        ).maxOrNull() ?: return
        if (widest + LABEL_GAP * 2 > first.srcLeft || staves.isEmpty()) {
            val text = listOfNotNull(measure, page?.let { "($it)" }).joinToString(" ")
            if (text.isNotEmpty()) draw(text, PAGE_NUMBER_SIZE, first.srcLeft, first.dstTop + PAGE_NUMBER_SIZE)
            return
        }
        val right = first.srcLeft - LABEL_GAP
        // 마디 번호 기준선 · 쪽 번호 기준선 — 파트 하나면 보표 가운데, 여럿이면 첫째 · 둘째 보표 사이
        val middle = if (multi) (staves[0].bottom + staves[1].top) / 2 else (staves[0].top + staves[0].bottom) / 2
        measure?.let { draw(it, MEASURE_NUMBER_SIZE, right - width(it, MEASURE_NUMBER_SIZE), middle - 1f) }
        page?.let { draw(it, PAGE_NUMBER_SIZE, right - width(it, PAGE_NUMBER_SIZE), middle + PAGE_NUMBER_SIZE + 1f) }
        for ((staff, label) in staves.zip(partLabels)) {
            draw(label, PART_LABEL_SIZE, right - width(label, PART_LABEL_SIZE, bold), (staff.top + staff.bottom) / 2 + PART_LABEL_SIZE / 3, bold)
        }
    }

    /**
     * 캐시 자리 — 원본이 바뀌면(크기 · 수정 시각) 다른 이름이 된다. [FORMAT] 은 배치 규칙이 바뀌면 올린다.
     * 같은 원본 · 보표의 옛 파일은 [prune] 이 지운다.
     */
    fun cacheFile(cacheDir: File, pdfFileId: String, source: File, staves: Set<Int>): File =
        File(File(cacheDir, "parts"), "${pdfFileId}_${source.length()}_${source.lastModified()}_s${key(staves)}_f$FORMAT.pdf")

    /** 캐시 이름의 보표 부분 — "1-3" */
    private fun key(staves: Set<Int>) = staves.sorted().joinToString("-")

    /** 파트 PDF 옆에 두는 배치 ([PartLayout.encode]) */
    fun layoutFile(pdf: File): File = File(pdf.path.removeSuffix(".pdf") + ".json")

    /** [keep] 과 같은 파일의 다른 캐시(옛 원본 · 옛 형식, 배치 json 포함)를 지운다 — 보표가 다른 것은 둔다 */
    fun prune(keep: File, pdfFileId: String, staves: Set<Int>) {
        keep.parentFile?.listFiles()?.forEach { f ->
            if (f != keep && f != layoutFile(keep) && f.name.startsWith("${pdfFileId}_") && f.name.contains("_s${key(staves)}_")) f.delete()
        }
    }

    /** 2: 왼쪽 여백에 원본 마디 · 쪽 번호, 3: 번호를 키움(11 · 9pt), 4: 소속에 따라 넓혀 자르기(PartClip), 5: 위 여백 64pt(박 표시), 6: 64pt 는 왼쪽 쪽(짝수)만, 7: 여러 파트, 8: 여러 파트 번호(Pt. n · 사이에 마디) */
    private const val FORMAT = 8
}
