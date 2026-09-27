package com.mrgq.pdfviewer

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mrgq.pdfviewer.ensemble.BeatTimeline
import com.mrgq.pdfviewer.ensemble.ClockSync
import com.mrgq.pdfviewer.ensemble.EnsembleRun
import com.mrgq.pdfviewer.ensemble.EnsembleSchedule
import com.mrgq.pdfviewer.metronome.Accent
import com.mrgq.pdfviewer.metronome.BarPosition
import com.mrgq.pdfviewer.metronome.BeatTimes
import com.mrgq.pdfviewer.metronome.TempoRelation
import com.mrgq.pdfviewer.metronome.TempoSectionSetting
import com.mrgq.pdfviewer.metronome.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 합주 메트로놈 동기화 (#055): 시계 동기 · 시간표 · 기기 시계로 옮기기 · 와이어 포맷. */
class EnsembleSyncTest {

    private val ms = 1_000_000L

    // ── ClockSync ───────────────────────────────────────────────────────────

    /** 지휘자 시계 = 내 시계 + [trueOffset]. 가는 길 [up], 오는 길 [down] 만큼 걸린 핑 하나 */
    private fun ClockSync.ping(localSend: Long, trueOffset: Long, up: Long, down: Long) =
        addSample(localSend, localSend + up + trueOffset, localSend + up + down)

    @Test
    fun 대칭_지연이면_offset_이_정확하다() {
        val sync = ClockSync()
        sync.ping(1_000 * ms, trueOffset = 5_000 * ms, up = 3 * ms, down = 3 * ms)
        assertEquals(5_000 * ms, sync.offsetNs)
        assertEquals(6 * ms, sync.bestRttNs)
    }

    @Test
    fun 비대칭_지연의_오차는_RTT_절반_이내() {
        val sync = ClockSync()
        sync.ping(0, trueOffset = -42 * ms, up = 1 * ms, down = 9 * ms)
        val error = sync.offsetNs!! - (-42 * ms)
        assertTrue("오차 $error", kotlin.math.abs(error) <= 5 * ms)
    }

    @Test
    fun RTT_가_튄_표본은_무시한다() {
        val sync = ClockSync()
        sync.ping(0, trueOffset = 100 * ms, up = 2 * ms, down = 2 * ms)
        // Wi-Fi 에서 가는 길만 80ms 걸린 표본 — 그대로 믿으면 offset 이 40ms 틀린다
        sync.ping(1_000 * ms, trueOffset = 100 * ms, up = 80 * ms, down = 2 * ms)
        assertEquals(100 * ms, sync.offsetNs)
    }

    @Test
    fun 작은_변화는_천천히_큰_변화는_바로() {
        val sync = ClockSync(window = 1)
        sync.ping(0, trueOffset = 0, up = 1 * ms, down = 1 * ms)
        sync.ping(0, trueOffset = 5 * ms, up = 1 * ms, down = 1 * ms)
        assertEquals("한 번에 1ms 씩", 1 * ms, sync.offsetNs)
        sync.ping(0, trueOffset = 500 * ms, up = 1 * ms, down = 1 * ms)
        assertEquals("20ms 넘게 다르면 바로", 500 * ms, sync.offsetNs)
    }

    @Test
    fun 음수_RTT_는_버리고_reset_은_비운다() {
        val sync = ClockSync()
        assertNull(sync.addSample(10, 0, 5))
        assertNull(sync.offsetNs)
        sync.ping(0, trueOffset = 1, up = 1, down = 1)
        sync.reset()
        assertNull(sync.offsetNs)
        assertEquals(0, sync.sampleCount)
    }

    // ── BeatTimeline ────────────────────────────────────────────────────────

    @Test
    fun 박_시각과_지금_박이_서로_맞다() {
        val t = BeatTimeline(anchorBeat = 0, anchorNs = 10_000 * ms, bpm = 120)  // 500ms 간격
        assertEquals(10_000 * ms, t.timeOf(0))
        assertEquals(10_500 * ms, t.timeOf(1))
        assertEquals(-1, t.beatAt(9_999 * ms))
        assertEquals(0, t.beatAt(10_000 * ms))
        assertEquals(0, t.beatAt(10_499 * ms))
        assertEquals(1, t.beatAt(10_500 * ms))
        assertEquals(20, t.beatAt(20_000 * ms))
    }

    @Test
    fun 나누어떨어지지_않는_템포도_beatAt_과_timeOf_가_어긋나지_않는다() {
        val t = BeatTimeline(0, 123_456_789L, 97)
        for (k in 0L..2_000L) {
            assertEquals(k, t.beatAt(t.timeOf(k)))
            assertEquals(k - 1, t.beatAt(t.timeOf(k) - 1))
        }
    }

    @Test
    fun 템포를_바꿔도_앞_박들의_시각은_그대로() {
        val t = BeatTimeline(0, 0, 60)
        val r = t.retimed(newBpm = 120, fromBeat = 10)
        assertEquals(t.timeOf(10), r.timeOf(10))
        assertEquals(t.timeOf(10) + 500 * ms, r.timeOf(11))
        assertEquals(0L, r.barBeat)
        assertEquals(10L, t.retimed(120, 10, newBarBeat = 10).barBeat)
    }

    /** 박 0~9 는 1초, 10 부터 0.5초 간격 (구간별 빠르기, #057) */
    private val twoTempos = BeatTimes { beat -> if (beat < 10) beat.toDouble() else 10.0 + (beat - 10) * 0.5 }

    @Test
    fun 구간별_빠르기면_박_간격이_BeatTimes_를_따른다() {
        val t = BeatTimeline(anchorBeat = 0, anchorNs = 1_000 * ms, bpm = 60)
        assertEquals(1_000 * ms, t.timeOf(0, twoTempos))
        assertEquals(10_000 * ms, t.timeOf(9, twoTempos))
        assertEquals("박 12 = 10초 + 0.5초 × 2, 기준 1초 뒤", 12_000 * ms, t.timeOf(12, twoTempos))
        for (k in -20L..200L) {
            assertEquals(k, t.beatAt(t.timeOf(k, twoTempos), twoTempos))
            assertEquals(k - 1, t.beatAt(t.timeOf(k, twoTempos) - 1, twoTempos))
        }
        assertEquals("BeatTimes 가 없으면 bpm 으로", t.timeOf(12), t.timeOf(12, null))
    }

    @Test
    fun 구간별_빠르기를_연주_중에_바꿔도_앞_박들의_시각은_그대로() {
        val t = BeatTimeline(0, 0, 60)
        val faster = BeatTimes { beat -> beat * 0.25 }
        val r = t.retimed(newBpm = 60, fromBeat = 12, oldTimes = twoTempos)
        assertEquals(t.timeOf(12, twoTempos), r.timeOf(12, faster))
        assertEquals(t.timeOf(12, twoTempos) + 250 * ms, r.timeOf(13, faster))
        assertEquals(13, r.beatAt(r.timeOf(13, faster), faster))
    }

    // ── EnsembleSchedule ────────────────────────────────────────────────────

    @Test
    fun 연주자는_offset_만큼_당겨서_같은_순간을_본다() {
        val conductorAnchor = 50_000 * ms
        val offset = 7_000 * ms  // 지휘자 시계가 7초 앞선다
        val schedule = EnsembleSchedule(BeatTimeline(0, conductorAnchor, 120), TimeSignature(4, 4), false) { offset }
        assertEquals(conductorAnchor - offset, schedule.localTimeOf(0))
        assertEquals(3, schedule.beatAtLocal(conductorAnchor - offset + 1_600 * ms))
        assertEquals(-1, schedule.beatAtLocal(conductorAnchor - offset - 1))
    }

    @Test
    fun 강박은_barBeat_부터_센다() {
        val schedule = EnsembleSchedule(BeatTimeline(0, 0, 120, barBeat = 2), TimeSignature(6, 8), false) { 0 }
        assertEquals(listOf(4, 5, 0, 1, 2, 3, 4, 5, 0), (0L..8L).map { schedule.beatInfo(it).indexInBar })
        assertEquals(Accent.MEDIUM, schedule.beatInfo(5).accent)
        assertEquals(120, schedule.beatInfo(5).bpm)
    }

    @Test
    fun 구간별_빠르기면_시간표와_박_템포가_함께_바뀐다() {
        val schedule = EnsembleSchedule(BeatTimeline(0, 0, 60), TimeSignature(4, 4), false, twoTempos) { 0 }
        assertEquals(11_000 * ms, schedule.localTimeOf(12))
        assertEquals(12, schedule.beatAtLocal(11_200 * ms))
        schedule.barPosition = { k -> BarPosition(0, TimeSignature(6, 8), bpm = if (k < 10) 60.0 else 120.0) }
        assertEquals(120, schedule.beatInfo(12).bpm)
        schedule.update(BeatTimeline(0, 0, 60), TimeSignature(4, 4), false, null)
        assertEquals(12_000 * ms, schedule.localTimeOf(12))
    }

    @Test
    fun 악보_연동이면_barPosition_을_따른다() {
        val schedule = EnsembleSchedule(BeatTimeline(0, 0, 72), TimeSignature(4, 4), false) { 0 }
        schedule.barPosition = { k -> BarPosition((k % 2).toInt(), TimeSignature(6, 8), dotted = true) }
        val beat = schedule.beatInfo(3)
        assertEquals(1, beat.indexInBar)
        assertEquals(TimeSignature(6, 8), beat.timeSignature)
        assertTrue(beat.dotted)
        assertEquals(2, beat.beatsPerBar)
    }

    // ── 와이어 포맷 ─────────────────────────────────────────────────────────

    private val gson = Gson()
    private fun wire(json: JsonObject): JsonObject = gson.fromJson(json.toString(), JsonObject::class.java)

    @Test
    fun metronome_run_은_왕복해도_보존된다() {
        val run = EnsembleRun(
            runId = "r1-3", file = "몰다우.pdf", state = EnsembleRun.State.PAUSED,
            timeline = BeatTimeline(anchorBeat = 17, anchorNs = 9_876_543_210_123L, bpm = 72, barBeat = 5),
            timeSignature = TimeSignature(6, 8), dotted = true, startMeasure = 12, focusMeasure = 20,
        )
        assertEquals(run, CollaborationProtocol.parseMetronomeRun(wire(CollaborationProtocol.buildMetronomeRun(run))))
        val plain = run.copy(state = EnsembleRun.State.PLAYING, startMeasure = null, focusMeasure = null)
        assertEquals(plain, CollaborationProtocol.parseMetronomeRun(wire(CollaborationProtocol.buildMetronomeRun(plain))))
        val sectioned = run.copy(
            sections = listOf(TempoSectionSetting(33, TempoRelation.BEAT, 100, true), TempoSectionSetting(57, TempoRelation.SET, 132)),
        )
        assertEquals(sectioned, CollaborationProtocol.parseMetronomeRun(wire(CollaborationProtocol.buildMetronomeRun(sectioned))))
        val empty = run.copy(sections = emptyList())
        assertEquals("빈 구간 목록(박자가 안 바뀌는 곡)도 null 과 구별된다", empty, CollaborationProtocol.parseMetronomeRun(wire(CollaborationProtocol.buildMetronomeRun(empty))))
    }

    @Test
    fun 시간표를_세울_수_없는_metronome_run_은_버린다() {
        fun parse(raw: String) = CollaborationProtocol.parseMetronomeRun(gson.fromJson(raw, JsonObject::class.java))
        val ok = """{"action":"metronome_run","run_id":"a","state":"playing","anchor_beat":0,"anchor_ns":5,"bpm":60}"""
        val parsed = parse(ok)!!
        assertEquals(TimeSignature(4, 4), parsed.timeSignature)
        assertEquals(0L, parsed.timeline.barBeat)
        assertNull(parse(ok.replace("\"bpm\":60", "\"bpm\":0")))
        assertNull(parse(ok.replace("playing", "dancing")))
        assertNull(parse(ok.replace(",\"anchor_ns\":5", "")))
        assertNull(parse(ok.replace("\"run_id\":\"a\",", "")))
        assertNull("구간을 모르는 지휘자(v0.2.4)", parsed.sections)
    }

    @Test
    fun clock_ping_pong_왕복() {
        assertEquals(123L, CollaborationProtocol.parseClockPing(wire(CollaborationProtocol.buildClockPing(123L))))
        val pong = CollaborationProtocol.parseClockPong(wire(CollaborationProtocol.buildClockPong(123L, 456L)))!!
        assertEquals(123L, pong.t0)
        assertEquals(456L, pong.serverNs)
        assertNull(CollaborationProtocol.parseClockPong(gson.fromJson("""{"t0":1}""", JsonObject::class.java)))
    }
}
