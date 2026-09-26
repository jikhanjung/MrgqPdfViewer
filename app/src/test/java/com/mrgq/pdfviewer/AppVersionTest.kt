package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.update.AppVersion
import org.junit.Assert.assertEquals
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
}
