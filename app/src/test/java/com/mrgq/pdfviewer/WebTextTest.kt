package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.server.UploadPage
import com.mrgq.pdfviewer.server.WebLang
import com.mrgq.pdfviewer.server.WebText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웹 업로드 페이지 언어 — 브라우저 `Accept-Language` 로 고르기 · 두 사전 (P18 7단계). */
class WebTextTest {

    private fun pick(header: String?) = WebText.pickLanguage(header)

    @Test
    fun 한국어가_먼저면_한국어() {
        assertEquals(WebLang.KO, pick("ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"))
        assertEquals(WebLang.KO, pick("ko"))
        assertEquals(WebLang.KO, pick("ko-KR, en"))
    }

    @Test
    fun 영어가_먼저거나_한국어가_없으면_영어() {
        assertEquals(WebLang.EN, pick("en-US,en;q=0.9,ko;q=0.8"))
        assertEquals(WebLang.EN, pick("en-US"))
        assertEquals(WebLang.EN, pick("ja,zh-CN;q=0.8"))
        assertEquals(WebLang.EN, pick("de-DE,de;q=0.9,en;q=0.5,ko;q=0.3"))
    }

    @Test
    fun q_값이_적힌_순서보다_먼저() {
        assertEquals(WebLang.KO, pick("en;q=0.5,ko"))
        assertEquals(WebLang.EN, pick("ko;q=0.4,en;q=0.6"))
        // 같은 q 면 적힌 순
        assertEquals(WebLang.EN, pick("en;q=0.8,ko;q=0.8"))
        // q=0 은 "받지 않음"
        assertEquals(WebLang.EN, pick("en,ko;q=0"))
        assertEquals(WebLang.KO, pick("en;q=0,ko"))
    }

    @Test
    fun 머리글이_없으면_한국어_그대로() {
        assertEquals(WebLang.KO, pick(null))
        assertEquals(WebLang.KO, pick(""))
        assertEquals(WebLang.KO, pick("  "))
    }

    @Test
    fun 두_사전의_키가_같다() {
        assertEquals(WebText.ko.keys, WebText.en.keys)
        WebLang.values().forEach { lang ->
            WebText.of(lang).forEach { (key, value) -> assertTrue("$lang $key", value.isNotBlank()) }
        }
    }

    @Test
    fun 페이지는_언어와_글을_맞춘다() {
        val ko = UploadPage.html(WebLang.KO)
        val en = UploadPage.html(WebLang.EN)
        assertTrue(ko.contains("<html lang=\"ko\">"))
        assertTrue(en.contains("<html lang=\"en\">"))
        assertTrue(ko.contains("<title>PDF 업로드</title>"))
        assertTrue(en.contains("<title>PDF upload</title>"))
        // 영어 페이지에 한글이 남지 않는다
        assertFalse(Regex("[가-힣]").containsMatchIn(en))
        // JS 문자열의 작은따옴표 · 줄바꿈은 이스케이프
        assertTrue(en.contains("confirm('Delete all PDF files?\\nThis can\\'t be undone.')"))
        assertTrue(ko.contains("confirm('모든 PDF 파일을 삭제하시겠습니까?\\n이 작업은 되돌릴 수 없습니다.')"))
    }

    @Test
    fun 이스케이프() {
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot; &#39;d&#39;", UploadPage.escapeHtml("a & <b> \"c\" 'd'"))
        assertEquals("it\\'s \\\"x\\\" \\\\ \\n \\x3C/script>", UploadPage.escapeJs("it's \"x\" \\ \n </script>"))
        assertEquals("\\` \\\${x} \\\\", UploadPage.escapeTemplate("` \${x} \\"))
    }
}
