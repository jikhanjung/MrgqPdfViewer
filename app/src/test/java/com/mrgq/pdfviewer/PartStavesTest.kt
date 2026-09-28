package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.PartStaves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 파트보 여러 파트 ↔ 저장 마스크 (v17) */
class PartStavesTest {

    @Test
    fun 마스크로_바꾸고_되돌린다() {
        assertEquals(0b1010L, PartStaves.toMask(setOf(1, 3)))
        assertEquals(setOf(1, 3), PartStaves.fromMask(0b1010L))
        assertEquals(setOf(62), PartStaves.fromMask(PartStaves.toMask(setOf(62))))
    }

    @Test
    fun 비었으면_전체_악보() {
        assertNull(PartStaves.toMask(emptySet()))
        assertNull(PartStaves.toMask(null))
        assertNull(PartStaves.fromMask(null))
        assertNull(PartStaves.fromMask(0L))
    }
}
