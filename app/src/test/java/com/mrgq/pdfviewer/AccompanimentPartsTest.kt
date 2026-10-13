package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.metronome.AccompanimentParts
import com.mrgq.pdfviewer.metronome.AccompanimentParts.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 반주 파트 고르기 (P21 1단계) — 자동 · 전체 · 직접 고르기와 파트 수가 바뀐 곡 */
class AccompanimentPartsTest {

    private val mine1: (Int) -> Boolean = { it == 1 }
    private val none: (Int) -> Boolean = { false }

    @Test
    fun 자동은_내_파트를_빼고_파트_보기가_아니면_전체() {
        assertEquals(Mode.AUTO, AccompanimentParts.mode(null, 3))
        assertEquals(setOf(0, 2), AccompanimentParts.resolve(null, 3, mine1))
        assertEquals(setOf(0, 1, 2), AccompanimentParts.resolve(null, 3, none))
    }

    @Test
    fun 전체는_내_파트도_파트가_늘어도_전체() {
        assertEquals(Mode.ALL, AccompanimentParts.mode(AccompanimentParts.ALL, 3))
        assertEquals(setOf(0, 1, 2), AccompanimentParts.resolve(AccompanimentParts.ALL, 3, mine1))
        assertEquals(setOf(0, 1, 2, 3), AccompanimentParts.resolve(AccompanimentParts.ALL, 4, mine1))
    }

    @Test
    fun 직접_고르기는_고른_파트만_내_파트여도() {
        val mask = AccompanimentParts.toMask(setOf(1, 2))
        assertEquals(0b110L, mask)
        assertEquals(Mode.CUSTOM, AccompanimentParts.mode(mask, 3))
        assertEquals(setOf(1, 2), AccompanimentParts.resolve(mask, 3, mine1))
    }

    @Test
    fun 직접_고르기에서_모두_빼면_반주_없음() {
        assertEquals(Mode.CUSTOM, AccompanimentParts.mode(0L, 3))
        assertEquals(emptySet<Int>(), AccompanimentParts.resolve(0L, 3, none))
    }

    @Test
    fun 파트가_줄어_맞지_않는_마스크는_자동으로() {
        val mask = AccompanimentParts.toMask(setOf(0, 3)) // 4 파트일 때 고른 것
        assertEquals(Mode.AUTO, AccompanimentParts.mode(mask, 3))
        assertEquals(setOf(0, 2), AccompanimentParts.resolve(mask, 3, mine1))
        // 파트가 늘면 고른 것 그대로
        assertEquals(setOf(0, 3), AccompanimentParts.resolve(mask, 5, mine1))
    }
}
