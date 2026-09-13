package com.mrgq.pdfviewer.utils

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/**
 * PDF 문서 정보(Info 딕셔너리)의 제목·작성자를 읽는다 (PdfBox-Android).
 *
 * PdfRenderer 는 렌더링만 해서 이 값에 접근할 수 없다. PdfBox 는 사용 전
 * `PDFBoxResourceLoader.init` 이 필요하다 — [com.mrgq.pdfviewer.PdfViewerApplication] 에서 한다.
 */
object PdfMetadataReader {

    private const val TAG = "PdfMetadataReader"

    data class DocumentInfo(val title: String?, val author: String?)

    /**
     * 읽지 못하면 null (손상된 파일, 사용자 암호가 걸린 파일).
     * 읽었는데 Info 가 없으면 두 값이 모두 null 인 [DocumentInfo].
     * 소유자 암호만 걸린 파일은 빈 사용자 암호로 열리므로 읽힌다.
     */
    fun read(file: File): DocumentInfo? = try {
        PDDocument.load(file).use { doc ->
            val info = doc.documentInformation
            DocumentInfo(
                title = PdfDocumentInfo.normalize(info.title),
                author = PdfDocumentInfo.normalize(info.author),
            )
        }
    } catch (e: InvalidPasswordException) {
        Log.w(TAG, "암호가 걸린 PDF 라 문서 정보를 읽지 않음: ${file.name}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "문서 정보 읽기 실패: ${file.name}", e)
        null
    }
}
