package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.metronome.Accent
import com.mrgq.pdfviewer.metronome.BarPosition
import com.mrgq.pdfviewer.metronome.ClickSynth
import com.mrgq.pdfviewer.metronome.MetronomeClock
import com.mrgq.pdfviewer.metronome.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** 메트로놈 박 위치(샘플 단위)와 클릭음 생성. */
class MetronomeClockTest {

    private val rate = 44100

    private fun ts(numerator: Int, denominator: Int = 4) = TimeSignature(numerator, denominator)

    @Test
    fun 템포_120_은_반초마다_박() {
        val clock = MetronomeClock(rate)
        val frames = (0 until 5).map { clock.next(120, ts(4)).frame }
        assertEquals(listOf(0L, 22050L, 44100L, 66150L, 88200L), frames)
    }

    @Test
    fun 첫_박만_강조하고_마디마다_다시_센다() {
        val clock = MetronomeClock(rate)
        val beats = (0 until 9).map { clock.next(90, ts(3)) }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1, 2), beats.map { it.indexInBar })
        assertEquals(listOf(true, false, false, true, false, false, true, false, false), beats.map { it.isAccent })
    }

    @Test
    fun 팔분의_육박자는_1박_강_4박_중간() {
        val clock = MetronomeClock(rate)
        val beats = (0 until 12).map { clock.next(180, ts(6, 8)) }
        val S = Accent.STRONG
        val M = Accent.MEDIUM
        val W = Accent.WEAK
        assertEquals(listOf(S, W, W, M, W, W, S, W, W, M, W, W), beats.map { it.accent })
        assertEquals("BPM 은 분모 음표 기준 — 8분음표 180 = 박당 1/3초", 14700L, beats[1].frame)
        assertEquals(ts(6, 8), beats[0].timeSignature)
    }

    @Test
    fun 나누어떨어지지_않는_템포도_누적_오차가_없다() {
        // 97 BPM = 박당 27278.35… 프레임. 정수로 잘라 누적하면 1000박 뒤 350프레임(8ms) 밀린다
        val clock = MetronomeClock(rate)
        var last = 0L
        repeat(1001) { last = clock.next(97, ts(4)).frame }
        val exact = 1000 * rate * 60.0 / 97
        assertTrue("1000박 뒤 오차 ${last - exact}", abs(last - exact) <= 1.0)
    }

    @Test
    fun 시작_여유만큼_첫_박을_늦춘다() {
        val clock = MetronomeClock(rate, startFrame = 6615)
        assertEquals(6615L, clock.next(120, ts(4)).frame)
        assertEquals(6615L + 22050L, clock.next(120, ts(4)).frame)
    }

    @Test
    fun 템포_변경은_다음_박부터() {
        val clock = MetronomeClock(rate)
        assertEquals(0L, clock.next(120, ts(4)).frame)       // 다음 박 = 0.5초 뒤로 이미 정해짐
        assertEquals(22050L, clock.next(60, ts(4)).frame)    // 이 박은 그대로, 그 다음부터 1초 간격
        assertEquals(66150L, clock.next(60, ts(4)).frame)
        assertEquals(60, clock.next(60, ts(4)).bpm)
    }

    @Test
    fun 박자를_줄여_마디를_넘으면_새_마디_첫_박부터() {
        val clock = MetronomeClock(rate)
        repeat(3) { clock.next(120, ts(4)) }                 // 0,1,2 박 — 다음은 3
        val beat = clock.next(120, ts(3))                     // 3박자로 줄이면 3 은 없는 박
        assertEquals(0, beat.indexInBar)
        assertTrue(beat.isAccent)
    }

    @Test
    fun 범위를_벗어난_값은_자른다() {
        val clock = MetronomeClock(rate)
        val beat = clock.next(1000, ts(99, 5))
        assertEquals(MetronomeClock.MAX_BPM, beat.bpm)
        assertEquals(MetronomeClock.MAX_BEATS, beat.beatsPerBar)
        assertEquals(4, beat.timeSignature.denominator)
        assertEquals(MetronomeClock.MIN_BPM, clock.next(0, ts(0)).bpm)
    }

    @Test
    fun 박_번호는_0부터_이어진다() {
        val clock = MetronomeClock(rate)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), (0 until 5).map { clock.next(120, ts(3)).index })
    }

    @Test
    fun 마디_위치를_주면_박자_대신_그것으로_강박을_정한다() {
        // 악보 연동: 박자표가 바뀌는 곳의 강박을 악보대로 — 여기선 4/4 를 넘겨도 3박마다 강박
        val clock = MetronomeClock(rate)
        val beats = (0 until 7).map { clock.next(120, ts(4)) { index -> BarPosition((index % 3).toInt(), ts(3)) } }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), beats.map { it.indexInBar })
        assertEquals(22050L * 6, beats.last().frame)
        assertEquals(ts(3), beats.last().timeSignature)
    }

    @Test
    fun 마디_위치의_박자로_중간_강세를_정한다() {
        // 대화상자는 4/4 여도 악보 마디가 6/8 이면 4박이 중간 세기
        val clock = MetronomeClock(rate)
        val beats = (0 until 6).map { clock.next(120, ts(4)) { index -> BarPosition(index.toInt(), ts(6, 8)) } }
        assertEquals(Accent.MEDIUM, beats[3].accent)
    }

    @Test
    fun 클릭음은_35ms_이고_잘리지_않는다() {
        val accent = ClickSynth.render(rate, Accent.STRONG)
        assertEquals(rate * ClickSynth.DURATION_MS / 1000, accent.size)
        assertTrue("첫 박 클릭이 너무 작다", accent.maxOf { abs(it.toInt()) } > 20000)
        assertEquals("시작은 0 에서 — 앞머리 잡음 방지", 0, accent[0].toInt())
    }

    @Test
    fun 클릭음은_끝으로_갈수록_사라진다() {
        val click = ClickSynth.render(rate, Accent.STRONG)
        fun rms(part: List<Short>) = sqrt(part.sumOf { it.toDouble() * it } / part.size)
        val head = rms(click.slice(0 until click.size / 10))
        val tail = rms(click.slice(click.size * 9 / 10 until click.size))
        assertTrue("끝 RMS $tail 가 앞 RMS $head 의 5% 를 넘는다", tail < head * 0.05)
    }

    @Test
    fun 첫_박은_더_높고_크다() {
        val accent = ClickSynth.render(rate, Accent.STRONG)
        val normal = ClickSynth.render(rate, Accent.WEAK)
        fun crossings(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }
        assertTrue(crossings(accent) > crossings(normal))
        assertFalse(normal.maxOf { abs(it.toInt()) } >= accent.maxOf { abs(it.toInt()) })
    }

    @Test
    fun 중간_세기는_높이와_크기가_강박과_약박_사이() {
        val strong = ClickSynth.render(rate, Accent.STRONG)
        val medium = ClickSynth.render(rate, Accent.MEDIUM)
        val weak = ClickSynth.render(rate, Accent.WEAK)
        fun crossings(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }
        fun peak(s: ShortArray) = s.maxOf { abs(it.toInt()) }
        assertTrue(crossings(weak) < crossings(medium) && crossings(medium) < crossings(strong))
        assertTrue(peak(weak) < peak(medium) && peak(medium) < peak(strong))
    }
}
