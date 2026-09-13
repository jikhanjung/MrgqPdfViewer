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

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun BOM_없는_UTF8_은_UTF8_로_읽는다() {
        // 실기기 몰다우.pdf 의 /Author 원본 바이트
        assertEquals("전예완", PdfDocumentInfo.decodeMisencodedUtf8(bytes(0xEC, 0xA0, 0x84, 0xEC, 0x98, 0x88, 0xEC, 0x99, 0x84)))
    }

    @Test
    fun UTF8_BOM_이_있으면_떼고_읽는다() {
        assertEquals("전예완", PdfDocumentInfo.decodeMisencodedUtf8(bytes(0xEF, 0xBB, 0xBF, 0xEC, 0xA0, 0x84, 0xEC, 0x98, 0x88, 0xEC, 0x99, 0x84)))
    }

    @Test
    fun 규격대로인_문자열은_건드리지_않는다() {
        assertNull("ASCII", PdfDocumentInfo.decodeMisencodedUtf8("Die Moldau".toByteArray()))
        assertNull("UTF-16BE BOM", PdfDocumentInfo.decodeMisencodedUtf8(bytes(0xFE, 0xFF, 0xC8, 0x04)))
        assertNull("PDFDocEncoding 라틴 문자 Café", PdfDocumentInfo.decodeMisencodedUtf8(bytes(0x43, 0x61, 0x66, 0xE9)))
        assertNull("잘린 UTF-8", PdfDocumentInfo.decodeMisencodedUtf8(bytes(0xEC, 0xA0)))
        assertNull("빈 값", PdfDocumentInfo.decodeMisencodedUtf8(ByteArray(0)))
    }
}
