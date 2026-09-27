package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.PdfLibrary.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 파일 목록 탭 (#062) */
class PdfLibraryTest {

    @Test
    fun 탭마다_보이는_파일() {
        assertTrue(Source.ALL.includes(true) && Source.ALL.includes(false))
        assertTrue(Source.DEVICE.includes(fromScoreMate = false))
        assertFalse(Source.DEVICE.includes(fromScoreMate = true))
        assertTrue(Source.SCOREMATE.includes(fromScoreMate = true))
        assertFalse(Source.SCOREMATE.includes(fromScoreMate = false))
    }

    @Test
    fun 저장된_탭_이름이_이상하면_전체() {
        assertEquals(Source.DEVICE, Source.fromName("DEVICE"))
        assertEquals(Source.ALL, Source.fromName(null))
        assertEquals(Source.ALL, Source.fromName("WEB"))
    }
}
