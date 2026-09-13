package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.metronome.BarPosition
import com.mrgq.pdfviewer.metronome.ScoreFollower
import com.mrgq.pdfviewer.metronome.ScoreFollower.Position
import com.mrgq.pdfviewer.metronome.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 메트로놈 박 번호 → 악보 위치 (예비박, 박자 바뀜, 끝). */
class ScoreFollowerTest {

    private fun measure(number: Int, numerator: Int?, denominator: Int? = numerator?.let { 8 }, page: Int = 0) =
        ScoreMeasure("f", number, page, 0, 0f, 0f, 10f, 10f, 595f, 842f, numerator, denominator)

    private fun measureOf(p: Position) = (p as Position.InMeasure).measure.measureNumber

    @Test
    fun 시작_마디_박자로_예비박_한_마디를_센다() {
        val follower = ScoreFollower(listOf(measure(1, 6), measure(2, 6), measure(3, 6)), startMeasureNumber = 1)
        assertEquals(6, follower.countInBeats)
        assertEquals(TimeSignature(6, 8), follower.startTimeSignature)
        for (beat in 0L until 6) {
            assertEquals(Position.CountIn(beat.toInt(), TimeSignature(6, 8)), follower.positionAt(beat))
        }
        val first = follower.positionAt(6) as Position.InMeasure
        assertEquals(1, first.measure.measureNumber)
        assertEquals(0, first.beatInMeasure)
        assertEquals(6, first.beatsInMeasure)
        assertEquals(2, first.next?.measureNumber)
        assertEquals(1, measureOf(follower.positionAt(11)))
        assertEquals(2, measureOf(follower.positionAt(12)))
    }

    @Test
    fun 박자가_바뀌면_마디_길이도_바뀐다() {
        val follower = ScoreFollower(listOf(measure(1, 3, 4), measure(2, 2, 4), measure(3, 2, 4)), startMeasureNumber = 1)
        assertEquals(3, follower.countInBeats)
        assertEquals(1, measureOf(follower.positionAt(3)))   // 예비박 3 박 뒤
        assertEquals(1, measureOf(follower.positionAt(5)))
        assertEquals(2, measureOf(follower.positionAt(6)))
        assertEquals(3, measureOf(follower.positionAt(8)))
        assertEquals(Position.Finished, follower.positionAt(10))
    }

    @Test
    fun 중간_마디부터_시작하면_그_마디_박자로_예비박() {
        val follower = ScoreFollower(listOf(measure(1, 3, 4), measure(2, 3, 4), measure(3, 2, 4), measure(4, 2, 4)), startMeasureNumber = 3)
        assertEquals(2, follower.countInBeats)
        assertEquals(3, measureOf(follower.positionAt(2)))
        assertEquals(4, measureOf(follower.positionAt(4)))
    }

    @Test
    fun 박자표가_빈_마디는_앞_마디_박자를_잇는다() {
        val follower = ScoreFollower(listOf(measure(1, 6), measure(2, null), measure(3, null)), startMeasureNumber = 2)
        assertEquals("시작 마디(2)는 박자표가 없지만 1 의 6/8 을 잇는다", 6, follower.countInBeats)
        assertEquals("분모도 잇는다", TimeSignature(6, 8), follower.startTimeSignature)
        assertEquals(3, measureOf(follower.positionAt(12)))
    }

    @Test
    fun 마지막_마디가_끝나면_끝이다() {
        val follower = ScoreFollower(listOf(measure(1, 4, 4), measure(2, 4, 4)), startMeasureNumber = 2)
        val last = follower.positionAt(4) as Position.InMeasure
        assertEquals(2, last.measure.measureNumber)
        assertEquals("마지막 마디는 다음이 없다", null, last.next)
        assertEquals(Position.Finished, follower.positionAt(8))
    }

    @Test
    fun 강박_위치는_예비박과_마디_안_박() {
        val follower = ScoreFollower(listOf(measure(1, 3, 4), measure(2, 2, 4)), startMeasureNumber = 1)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1), (0L until 8).map { follower.barPositionAt(it).indexInBar })
    }

    @Test
    fun 마디_위치는_그_마디의_박자를_함께_준다() {
        // 3/4 한 마디 뒤 6/8 — 6/8 마디의 4박이 중간 세기가 되려면 분모까지 넘어가야 한다
        val follower = ScoreFollower(listOf(measure(1, 3, 4), measure(2, 6, 8), measure(3, null)), startMeasureNumber = 1)
        assertEquals(BarPosition(0, TimeSignature(3, 4)), follower.barPositionAt(0))       // 예비박
        assertEquals(BarPosition(2, TimeSignature(3, 4)), follower.barPositionAt(5))       // 1마디 3박
        assertEquals(BarPosition(3, TimeSignature(6, 8)), follower.barPositionAt(9))       // 2마디 4박
        assertEquals(TimeSignature(6, 8), (follower.positionAt(12) as Position.InMeasure).timeSignature) // 3마디: 이어받음
    }

    @Test
    fun 점음표_박이면_겹박자_마디는_두_박_홑박자_마디는_그대로() {
        val follower = ScoreFollower(
            listOf(measure(1, 6), measure(2, 6), measure(3, 3, 4)),
            startMeasureNumber = 1,
            dottedBeat = true,
        )
        assertEquals("예비박도 점4분음표 두 박", 2, follower.countInBeats)
        assertEquals(1, measureOf(follower.positionAt(2)))
        assertEquals(2, measureOf(follower.positionAt(4)))
        val third = follower.positionAt(6) as Position.InMeasure
        assertEquals(3, third.measure.measureNumber)
        assertEquals("3/4 마디는 4분음표 3박", 3, third.beatsInMeasure)
        assertEquals(Position.Finished, follower.positionAt(9))
        assertEquals(BarPosition(1, TimeSignature(6, 8), dotted = true), follower.barPositionAt(5))
    }

    @Test
    fun 박자를_아는_마디부터만_시작할_수_있다() {
        assertTrue("박자표를 하나도 못 읽으면 연동 안 함", ScoreFollower.startableMeasures(listOf(measure(1, null), measure(2, null))).isEmpty())
        val startable = ScoreFollower.startableMeasures(listOf(measure(2, null), measure(1, null), measure(3, 3, 4), measure(4, null)))
        assertEquals(listOf(3, 4), startable.map { it.measureNumber })
    }
}
