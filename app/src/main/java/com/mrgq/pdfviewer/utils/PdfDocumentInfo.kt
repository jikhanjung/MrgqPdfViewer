package com.mrgq.pdfviewer.utils

/**
 * PDF 문서 정보(Info 딕셔너리) 값 정리와 표시·재분석 규칙.
 *
 * Android·PdfBox 에 의존하지 않는 순수 함수만 둔다 — JVM 단위 테스트 대상.
 * 실제 읽기는 [PdfMetadataReader].
 */
object PdfDocumentInfo {

    /** 앞뒤 공백과 NUL 문자를 걷어내고, 비면 null. 빈 문자열이나 NUL 로 채우는 작성기가 있다. */
    fun normalize(value: String?): String? =
        value?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 목록 카드의 부제목 ("제목 · 작성자").
     * 제목이 파일명(확장자 유무 무관)과 같으면 중복이라 뺀다. 둘 다 없으면 null.
     */
    fun subtitle(fileName: String, title: String?, author: String?): String? {
        val name = fileName.trim()
        val shownTitle = normalize(title)?.takeUnless {
            it.equals(name, ignoreCase = true) ||
                it.equals(name.substringBeforeLast('.'), ignoreCase = true)
        }
        return listOfNotNull(shownTitle, normalize(author))
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 파일을 다시 분석해야 하나.
     *
     * @param docInfoReadAt 문서 정보를 읽은 시각. null 이면 한 번도 안 읽은 것(v4 이전 레코드)
     * @param analyzedMtime 분석 당시의 file.lastModified() (PdfFile.createdAt 에 저장된다)
     * @param fileMtime 지금의 file.lastModified()
     *
     * mtime 은 크기가 아니라 **같은지**로 본다. 웹 업로드로 덮어쓰면 늘지만,
     * adb push 처럼 원본 mtime 을 보존하는 복사는 줄어들 수도 있다.
     */
    fun needsRescan(docInfoReadAt: Long?, analyzedMtime: Long, fileMtime: Long): Boolean =
        docInfoReadAt == null || analyzedMtime != fileMtime
}
