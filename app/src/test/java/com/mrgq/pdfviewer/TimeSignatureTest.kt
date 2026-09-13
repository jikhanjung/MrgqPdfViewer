package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.metronome.Accent
import com.mrgq.pdfviewer.metronome.MetronomeClock
import com.mrgq.pdfviewer.metronome.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 박자표: 겹박자 강세, 범위 자르기, 미리 보이는 박자 목록. */
class TimeSignatureTest {

    private val S = Accent.STRONG
    private val M = Accent.MEDIUM
    private val W = Accent.WEAK

    private fun accents(ts: TimeSignature) = (0 until ts.numerator).map(ts::accentAt)

    @Test
    fun 분자가_6_9_12_면_겹박자() {
        listOf(TimeSignature(6, 8), TimeSignature(9, 8), TimeSignature(12, 8), TimeSignature(6, 4))
            .forEach { assertTrue("$it", it.isCompound) }
        listOf(TimeSignature(3, 8), TimeSignature(3, 4), TimeSignature(4, 4), TimeSignature(2, 4), TimeSignature(5, 4))
            .forEach { assertFalse("$it", it.isCompound) }
    }

    @Test
    fun 겹박자는_묶음마다_중간_세기() {
        assertEquals(listOf(S, W, W, M, W, W), accents(TimeSignature(6, 8)))
        assertEquals(listOf(S, W, W, M, W, W, M, W, W, M, W, W), accents(TimeSignature(12, 8)))
    }

    @Test
    fun 홑박자는_첫_박만_강조() {
        assertEquals(listOf(S, W, W, W), accents(TimeSignature(4, 4)))
        assertEquals(listOf(S, W, W), accents(TimeSignature(3, 8)))
        assertEquals(listOf(S, W, W, W, W), accents(TimeSignature(5, 4)))
    }

    @Test
    fun 비어_있으면_4분의_4() {
        assertEquals(TimeSignature(4, 4), TimeSignature.of(null, null))
        assertEquals("v0.2.0 에서 박 수만 저장된 행 = x/4", TimeSignature(6, 4), TimeSignature.of(6, null))
    }

    @Test
    fun 범위를_벗어나면_가장_가까운_값으로() {
        assertEquals(MetronomeClock.MAX_BEATS, TimeSignature.of(99, 8).numerator)
        assertEquals(MetronomeClock.MIN_BEATS, TimeSignature.of(0, 8).numerator)
        assertEquals(TimeSignature(3, 16), TimeSignature.of(3, 32))
        assertEquals(TimeSignature(2, 2), TimeSignature.of(2, 1))
        assertEquals(TimeSignature(6, 4), TimeSignature.of(6, 3))
        assertEquals(TimeSignature(4, 4), TimeSignature.of(4, 0))
    }

    @Test
    fun 미리_보이는_박자는_4분의_4_부터_중복_없이() {
        val common = TimeSignature.COMMON
        assertEquals(TimeSignature(4, 4), common.first())
        assertTrue(TimeSignature(6, 8) in common)
        assertEquals(common.size, common.toSet().size)
        common.forEach { assertEquals("범위 안의 값이어야 한다: $it", it, it.coerced()) }
    }

    @Test
    fun 표시는_분자_분모() {
        assertEquals("6/8", TimeSignature(6, 8).toString())
        assertEquals("8분음표", TimeSignature(6, 8).beatNoteName)
    }
}
