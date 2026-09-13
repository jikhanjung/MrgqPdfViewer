package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.utils.PdfDocumentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 문서 정보 값 정리 · 목록 부제목 · 재분석 판정.
 * 실제 PDF 읽기(PdfBox)는 계측 테스트 PdfMetadataReaderTest 가 본다.
 */
class PdfDocumentInfoTest {

    @Test
    fun normalize_null_과_빈값은_null() {
        assertNull(PdfDocumentInfo.normalize(null))
        assertNull(PdfDocumentInfo.normalize(""))
        assertNull(PdfDocumentInfo.normalize("   "))
        assertNull("NUL 로만 채운 값", PdfDocumentInfo.normalize("\u0000\u0000"))
    }

    @Test
    fun normalize_앞뒤_공백만_걷고_안쪽_공백은_남긴다() {
        assertEquals("Die Moldau (Vltava) - Full Score", PdfDocumentInfo.normalize("  Die Moldau (Vltava) - Full Score \n"))
        assertEquals("몰다우 총보", PdfDocumentInfo.normalize("몰다우 총보\u0000"))
    }

    @Test
    fun subtitle_제목과_작성자를_잇는다() {
        assertEquals(
            "Die Moldau (Vltava) - Full Score · 홍길동",
            PdfDocumentInfo.subtitle("Moldau0607.pdf", "Die Moldau (Vltava) - Full Score", "홍길동")
        )
    }

    @Test
    fun subtitle_한쪽만_있으면_그것만() {
        assertEquals("몰다우", PdfDocumentInfo.subtitle("a.pdf", "몰다우", null))
        assertEquals("홍길동", PdfDocumentInfo.subtitle("a.pdf", "  ", "홍길동"))
    }

    @Test
    fun subtitle_둘_다_없으면_null() {
        // 문서 정보가 없는 PDF → 카드 줄을 숨긴다
        assertNull(PdfDocumentInfo.subtitle("scan.pdf", null, null))
    }

    @Test
    fun subtitle_파일명과_같은_제목은_중복이라_뺀다() {
        // 실제 사례: data/parts 의 파트보는 ImageMagick 이 파일명+공백을 Title 로 넣었다
        assertNull(PdfDocumentInfo.subtitle("Moldau_1_Jinho.pdf", "Moldau_1_Jinho ", null))
        assertNull(PdfDocumentInfo.subtitle("악보1.pdf", "악보1", null))
        assertNull(PdfDocumentInfo.subtitle("악보1.pdf", "악보1.PDF", null))
        assertEquals("홍길동", PdfDocumentInfo.subtitle("Score.pdf", "score", "홍길동"))
        // 파일명의 일부일 뿐이면 다른 정보다
        assertEquals("악보", PdfDocumentInfo.subtitle("악보1.pdf", "악보", null))
    }

    @Test
    fun needsRescan_한번도_안_읽었으면_다시_읽는다() {
        // v4 이전에 만들어진 레코드 (docInfoReadAt 컬럼이 null)
        assertTrue(PdfDocumentInfo.needsRescan(null, analyzedMtime = 1000, fileMtime = 1000))
    }

    @Test
    fun needsRescan_파일이_그대로면_다시_읽지_않는다() {
        assertFalse(PdfDocumentInfo.needsRescan(5000, analyzedMtime = 1000, fileMtime = 1000))
    }

    @Test
    fun needsRescan_mtime_이_달라지면_늘든_줄든_다시_읽는다() {
        assertTrue("웹 업로드로 덮어씀", PdfDocumentInfo.needsRescan(5000, analyzedMtime = 1000, fileMtime = 9000))
        assertTrue("원본 mtime 을 보존하는 복사", PdfDocumentInfo.needsRescan(5000, analyzedMtime = 1000, fileMtime = 500))
    }
}
