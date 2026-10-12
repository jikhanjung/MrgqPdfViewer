package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.scoremate.ManualLink
import org.junit.Assert.assertEquals
import org.junit.Test

/** 설정 → 앱 정보 → 설명서 주소 (P19 4단계 · P20) */
class ManualLinkTest {

    @Test
    fun 한국어_판() {
        assertEquals(
            "https://scoremate.noematica.kr/manual/?lang=ko&v=v0.8.1-beta.2",
            ManualLink.url("https://scoremate.noematica.kr", "ko", "0.8.1-beta.2"),
        )
    }

    @Test
    fun 영어_끝_빗금() {
        assertEquals(
            "http://192.168.0.10:8000/manual/?lang=en&v=v0.8.1",
            ManualLink.url("http://192.168.0.10:8000/", "en", "0.8.1"),
        )
    }

    @Test
    fun 그_밖의_언어는_한국어() {
        assertEquals("https://s/manual/?lang=ko&v=v1.0.0", ManualLink.url("https://s", "ja", "1.0.0"))
    }
}
