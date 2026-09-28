package com.mrgq.pdfviewer.score

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * PDF 한 개의 악보 구조를 분석한다: 페이지마다 콘텐츠 스트림을 [PathContentInterpreter] 로 읽어 경로 박스와 텍스트를
 * 모으고, [StaffSystemDetector] 로 시스템·마디를, [StaffLabelDetector] 로 보표 이름을, [TimeSignatureDetector] 로 박자표를 찾는다.
 *
 * PdfBox 는 문서 열기, 스트림 압축 해제, 글꼴 해독에만 쓴다. 사용 전 `PDFBoxResourceLoader.init` 이 필요하다 (PdfViewerApplication).
 */
object ScoreLayoutAnalyzer {

    private const val TAG = "ScoreLayoutAnalyzer"

    /**
     * 파일을 열 수 없으면 null. 악보 구조를 못 찾은 페이지는 시스템이 빈 [PageLayout] 이다
     * (스캔 PDF, 다른 작성기, 악보가 아닌 문서).
     */
    /**
     * 쪽마다 경로 박스 (crop 기준, 아래→위) — 파트보를 소속에 따라 넓혀 자를 때 쓴다 ([PartClip]). 분석과 같은 해석기다.
     * [pages] 의 쪽만 읽는다. 열 수 없으면 빈 맵. 회전된 쪽은 뺀다 (분석도 하지 않는다)
     */
    fun pathBoxes(file: File, pages: Set<Int>): Map<Int, List<PathBox>> = try {
        PDDocument.load(file).use { doc ->
            pages.filter { it in 0 until doc.numberOfPages }.associateWith { index ->
                val page = doc.getPage(index)
                if (page.rotation % 360 != 0) return@associateWith emptyList<PathBox>()
                val crop = page.cropBox
                val boxes = ArrayList<PathBox>()
                PathContentInterpreter(crop.lowerLeftX, crop.lowerLeftY) { boxes += it }
                    .run(PdfBoxContent.pageContent(page), PdfBoxXObjects(page.resources))
                boxes
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "경로 읽기 실패: ${file.name}", e)
        emptyMap()
    }

    fun analyze(file: File): ScoreLayout? = try {
        val started = System.currentTimeMillis()
        PDDocument.load(file).use { doc ->
            val pages = doc.pages.mapIndexed { index, page ->
                val crop = page.cropBox
                if (page.rotation % 360 != 0) {
                    // 회전된 페이지는 좌표계를 돌려야 하는데 다루지 않는다 — 틀린 박스보다 없는 게 낫다
                    Log.w(TAG, "${file.name} p${index + 1}: 회전(${page.rotation}°) 페이지는 분석하지 않음")
                    PageLayout(index, crop.width, crop.height, emptyList())
                } else {
                    val boxes = ArrayList<PathBox>()
                    val texts = ArrayList<TextRun>()
                    PathContentInterpreter(crop.lowerLeftX, crop.lowerLeftY, textSink = { texts += it }) { boxes += it }
                        .run(PdfBoxContent.pageContent(page), PdfBoxXObjects(page.resources))
                    val systems = StaffLabelDetector.attach(texts, StaffSystemDetector.detect(boxes, crop.height, texts), crop.height)
                    PageLayout(index, crop.width, crop.height, systems, TimeSignatureDetector.detect(texts, systems, crop.height))
                }
            }
            ScoreLayout(pages).also {
                val signatures = pages.flatMap { p -> p.timeSignatures }.joinToString { s -> "${s.numerator}/${s.denominator}" }
                Log.i(TAG, "${file.name}: ${pages.size}쪽, 시스템 ${pages.sumOf { p -> p.systems.size }}개, " +
                    "마디 ${it.measureCount}개, 박자표 [$signatures] (${System.currentTimeMillis() - started}ms)")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "악보 분석 실패: ${file.name}", e)
        null
    }
}
