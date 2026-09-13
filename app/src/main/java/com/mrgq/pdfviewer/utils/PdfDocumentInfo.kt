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
     * 규격을 어기고 **BOM 없는 UTF-8 바이트**를 그대로 넣은 문자열을 되살린다.
     *
     * PDF 텍스트 문자열은 PDFDocEncoding 이거나 BOM 붙은 UTF-16BE(PDF 2.0 은 BOM 붙은 UTF-8 도)여야 한다.
     * 그런데 Microsoft Print to PDF 가 한글 작성자 "전예완"을 `EC A0 84 EC 98 88 EC 99 84` 로 그대로 써서
     * PdfBox 가 PDFDocEncoding 으로 읽어 `ì€—ìŸ‹ìŽ—` 가 됐다 (실기기 몰다우.pdf, #049). pdfinfo 도 똑같이 깨진다.
     *
     * @param raw 문자열 객체의 원본 바이트
     * @return UTF-8 로 읽어야 하는 경우 그 문자열. 아니면 null — 호출자는 PdfBox 해석을 그대로 쓴다.
     *   (BOM 없음 + 비ASCII 바이트 있음 + **엄격한 UTF-8 로 끝까지 해석됨**. 라틴 문자 PDFDocEncoding
     *   예: "Café" 의 E9 단독 바이트는 UTF-8 로 해석되지 않아 null)
     */
    fun decodeMisencodedUtf8(raw: ByteArray): String? {
        fun at(i: Int) = raw[i].toInt() and 0xFF
        if (raw.size >= 2 && ((at(0) == 0xFE && at(1) == 0xFF) || (at(0) == 0xFF && at(1) == 0xFE))) return null
        val bytes = if (raw.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
            raw.copyOfRange(3, raw.size) // UTF-8 BOM (PDF 2.0)
        } else {
            raw
        }
        if (bytes.none { it < 0 }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

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
