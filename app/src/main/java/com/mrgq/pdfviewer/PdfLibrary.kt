package com.mrgq.pdfviewer

import java.io.File

/**
 * 앱 PDF 폴더에서 보여 줄 파일 (P05 C2). `PDFs/` 바로 아래 파일(웹 업로드 · 합주로 받은 파일)과
 * `PDFs/ScoreMate/<앙상블 | 내 악보>/` 아래 파일(ScoreMate 동기화). 그 밖의 하위 폴더는 보지 않는다.
 * `.part`(받는 중) 는 PDF 확장자가 아니라 빠진다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object PdfLibrary {
    const val SCOREMATE_FOLDER = "ScoreMate"

    fun listPdfFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val files = root.listFiles { f -> f.isPdf() }.orEmpty().toMutableList()
        File(root, SCOREMATE_FOLDER).listFiles { f -> f.isDirectory }?.forEach { group ->
            files += group.listFiles { f -> f.isPdf() }.orEmpty()
        }
        return files
    }

    /** ScoreMate 폴더 아래 파일이면 그 폴더 이름(앙상블 · "내 악보"), 아니면 null */
    fun scoreMateGroupOf(root: File, file: File): String? {
        val parent = file.parentFile ?: return null
        return if (parent.parentFile?.canonicalPath == File(root, SCOREMATE_FOLDER).canonicalPath) parent.name else null
    }

    private fun File.isPdf() = isFile && extension.equals("pdf", ignoreCase = true)
}
