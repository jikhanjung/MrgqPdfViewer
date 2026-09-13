package com.mrgq.pdfviewer.score

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * PDF 한 개의 악보 구조를 분석한다: PdfBox 로 경로를 모으고([PdfPathCollector])
 * 페이지마다 [StaffSystemDetector] 로 시스템·마디를 찾는다.
 *
 * PdfBox 는 사용 전 `PDFBoxResourceLoader.init` 이 필요하다 (PdfViewerApplication).
 */
object ScoreLayoutAnalyzer {

    private const val TAG = "ScoreLayoutAnalyzer"

    /**
     * 파일을 열 수 없으면 null. 악보 구조를 못 찾은 페이지는 시스템이 빈 [PageLayout] 이다
     * (스캔 PDF, 다른 작성기, 악보가 아닌 문서).
     */
    fun analyze(file: File): ScoreLayout? = try {
        val started = System.currentTimeMillis()
        PDDocument.load(file).use { doc ->
            val pages = doc.pages.mapIndexed { index, page ->
                val crop = page.cropBox
                val systems = if (page.rotation % 360 != 0) {
                    // 회전된 페이지는 좌표계를 돌려야 하는데 다루지 않는다 — 틀린 박스보다 없는 게 낫다
                    Log.w(TAG, "${file.name} p${index + 1}: 회전(${page.rotation}°) 페이지는 분석하지 않음")
                    emptyList()
                } else {
                    val collector = PdfPathCollector(page)
                    collector.processPage(page)
                    StaffSystemDetector.detect(collector.boxes, crop.height)
                }
                PageLayout(index, crop.width, crop.height, systems)
            }
            ScoreLayout(pages).also {
                Log.i(TAG, "${file.name}: ${pages.size}쪽, 시스템 ${pages.sumOf { p -> p.systems.size }}개, " +
                    "마디 ${it.measureCount}개 (${System.currentTimeMillis() - started}ms)")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "악보 분석 실패: ${file.name}", e)
        null
    }
}
