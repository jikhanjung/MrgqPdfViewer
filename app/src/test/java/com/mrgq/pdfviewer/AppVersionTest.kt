package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 릴리스 태그 / versionName 해석과 비교 (#054 §1.4). */
class AppVersionTest {

    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun 태그와_versionName_을_같이_읽는다() {
        assertEquals(AppVersion(0, 2, 2), v("v0.2.2"))
        assertEquals(AppVersion(0, 2, 2), v("0.2.2"))
        assertEquals(AppVersion(1, 0, 0), v("v1"))
        assertEquals(AppVersion(1, 2, 0), v("1.2"))
        assertEquals(AppVersion(0, 3, 0, "rc1"), v("v0.3.0-rc1"))
    }

    @Test
    fun 버전이_아니면_null() {
        listOf("", "latest", "v", "0.2.x", "release-0.2.2").forEach { assertNull(it, AppVersion.parse(it)) }
    }

    @Test
    fun 자리별로_숫자_비교() {
        assertTrue(v("0.2.10") > v("0.2.9"))  // 문자열 비교면 틀린다
        assertTrue(v("0.3.0") > v("0.2.99"))
        assertTrue(v("1.0.0") > v("0.9.9"))
        assertEquals(0, v("v0.2.2").compareTo(v("0.2.2")))
    }

    @Test
    fun 접미사는_정식보다_낮다() {
        assertTrue(v("0.3.0-rc1") < v("0.3.0"))
        assertTrue(v("0.3.0-rc1") > v("0.2.2"))
        assertTrue(v("0.3.0-beta") < v("0.3.0-rc1"))
    }

    @Test
    fun 문자열은_태그_형식() {
        assertEquals("v0.2.2", v("0.2.2").toString())
        assertEquals("v0.3.0-rc1", v("0.3.0-rc1").toString())
    }

    @Test
    fun 사전_릴리스는_SemVer_우선순위() {
        val order = listOf("0.5.6", "0.5.7-alpha", "0.5.7-beta.1", "0.5.7-beta.2", "0.5.7-beta.10", "0.5.7-rc.1", "0.5.7", "0.6.0-beta.1", "0.6.0")
        val parsed = order.map { v(it) }
        assertEquals(parsed, parsed.shuffled(java.util.Random(7)).sorted())
        assertTrue("숫자 조각은 숫자로", v("0.5.7-beta.10") > v("0.5.7-beta.2"))
        assertTrue("앞이 같으면 조각이 많은 쪽", v("0.5.7-beta.1") > v("0.5.7-beta"))
        assertTrue("숫자는 글자보다 낮다", v("0.5.7-1") < v("0.5.7-alpha"))
    }

    @Test
    fun 베타를_쓰다_정식만_보면_내려가지_않는다() {
        assertFalse(v("0.5.7") > v("0.6.0-beta.1"))
        assertTrue(v("0.6.0") > v("0.6.0-beta.1"))
    }

    @Test
    fun 시험_태그() {
        assertTrue(v("0.5.7-test").isTest)
        assertTrue(v("0.5.7-beta.1-test").isTest)
        assertFalse(v("0.5.7-beta.1").isTest)
        assertTrue(v("0.5.7-beta.1").isPrerelease)
        assertFalse(v("0.5.7").isPrerelease)
    }
}
