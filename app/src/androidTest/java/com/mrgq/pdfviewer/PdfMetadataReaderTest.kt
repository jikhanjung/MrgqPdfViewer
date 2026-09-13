package com.mrgq.pdfviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrgq.pdfviewer.utils.PdfMetadataReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * PdfBox 로 PDF 문서 정보(제목·작성자)를 읽는 경로 (계측 — PdfBox-Android 는 Android 런타임이 필요하다).
 *
 * 테스트 PDF 는 두 방식으로 만든다:
 *  - PdfBox 로 생성 (암호화 포함)
 *  - **바이트를 직접 조립** — 다른 작성기가 쓰는 표현(UTF-16BE 16진 문자열, 이스케이프된 괄호)을
 *    PdfBox 의 저장 방식에 기대지 않고 재현한다
 */
@RunWith(AndroidJUnit4::class)
class PdfMetadataReaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "pdf-info-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun 한글_제목과_작성자를_읽는다() {
        val file = pdfBoxPdf("korean.pdf") {
            it.documentInformation.title = "몰다우 (Vltava) - 총보"
            it.documentInformation.author = "홍길동"
        }
        val info = PdfMetadataReader.read(file)
        assertNotNull(info)
        assertEquals("몰다우 (Vltava) - 총보", info!!.title)
        assertEquals("홍길동", info.author)
    }

    @Test
    fun 문서정보가_없으면_두_값이_null() {
        // 새로 만든 PDDocument 를 그대로 저장 — Info 딕셔너리가 없는 PDF
        val info = PdfMetadataReader.read(pdfBoxPdf("no-info.pdf"))
        assertNotNull("읽기 자체는 성공해야 한다", info)
        assertNull(info!!.title)
        assertNull(info.author)
    }

    @Test
    fun 공백뿐인_값은_null() {
        val file = pdfBoxPdf("blank.pdf") {
            it.documentInformation.title = "   "
            it.documentInformation.author = ""
        }
        val info = PdfMetadataReader.read(file)!!
        assertNull(info.title)
        assertNull(info.author)
    }

    @Test
    fun UTF16BE_16진_제목과_이스케이프된_작성자를_읽는다() {
        val file = rawPdf(
            "utf16.pdf",
            "<< /Title <${utf16BeHex("몰다우 총보")}> /Author (Kim \\(arr.\\)) /Producer (Test) >>"
        )
        val info = PdfMetadataReader.read(file)
        assertNotNull(info)
        assertEquals("몰다우 총보", info!!.title)
        assertEquals("Kim (arr.)", info.author)
    }

    @Test
    fun PDF가_아니면_null() {
        val file = File(dir, "not-a-pdf.pdf").apply { writeText("<html>업로드 실수</html>") }
        assertNull(PdfMetadataReader.read(file))
    }

    @Test
    fun 사용자_암호가_걸리면_null() {
        val file = pdfBoxPdf("user-password.pdf") {
            it.documentInformation.title = "비밀 악보"
            it.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
        }
        assertNull(PdfMetadataReader.read(file))
    }

    @Test
    fun 소유자_암호만_걸리면_읽는다() {
        // 인쇄·편집 제한만 건 PDF — 빈 사용자 암호로 열리므로 뷰어에서 열 수 있고 문서 정보도 읽힌다
        val file = pdfBoxPdf("owner-only.pdf") {
            it.documentInformation.title = "제한된 악보"
            it.protect(StandardProtectionPolicy("owner", "", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
        }
        assertEquals("제한된 악보", PdfMetadataReader.read(file)?.title)
    }

    private fun pdfBoxPdf(name: String, configure: (PDDocument) -> Unit = {}): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            configure(doc)
            doc.save(file)
        }
        return file
    }

    /** 페이지 1장 + 주어진 Info 딕셔너리로 최소 PDF 를 조립한다 (xref 오프셋 계산 포함, ASCII 전용). */
    private fun rawPdf(name: String, infoDict: String): File {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
            infoDict,
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            val offset = out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
            offset
        }
        val xrefAt = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info ${objects.size} 0 R >>\n")
        out.append("startxref\n$xrefAt\n%%EOF\n")
        return File(dir, name).apply { writeBytes(out.toString().toByteArray(Charsets.US_ASCII)) }
    }

    private fun utf16BeHex(text: String): String =
        "FEFF" + text.toByteArray(Charsets.UTF_16BE).joinToString("") { String.format(Locale.ROOT, "%02X", it) }
}
