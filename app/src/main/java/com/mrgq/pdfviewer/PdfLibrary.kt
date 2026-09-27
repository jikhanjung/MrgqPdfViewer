package com.mrgq.pdfviewer

import java.io.File

/**
 * 앱 PDF 폴더의 서재 (#063). **한 번에 한 서재**(사용자 결정): ScoreMate 에 연결된 TV 는 `PDFs/ScoreMate/<앙상블 | 내 악보>/` 아래
 * 파일만, 연결하지 않은 TV 는 `PDFs/` 바로 아래 파일(웹 업로드 · 합주로 받은 파일)만. 다른 쪽 파일은 지우지 않고 보이지 않을 뿐이다.
 * `.part`(받는 중) 는 PDF 확장자가 아니라 빠진다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object PdfLibrary {
    const val SCOREMATE_FOLDER = "ScoreMate"

    fun listPdfFiles(root: File, scoreMate: Boolean): List<File> {
        if (!root.isDirectory) return emptyList()
        if (!scoreMate) return root.listFiles { f -> f.isPdf() }.orEmpty().toList()
        return File(root, SCOREMATE_FOLDER).listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { group -> group.listFiles { f -> f.isPdf() }.orEmpty().toList() }
    }

    /** ScoreMate 폴더 아래 파일이면 그 폴더 이름(앙상블 · "내 악보"), 아니면 null */
    fun scoreMateGroupOf(root: File, file: File): String? {
        val parent = file.parentFile ?: return null
        return if (parent.parentFile?.canonicalPath == File(root, SCOREMATE_FOLDER).canonicalPath) parent.name else null
    }

    private fun File.isPdf() = isFile && extension.equals("pdf", ignoreCase = true)
}
