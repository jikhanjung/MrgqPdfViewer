package com.mrgq.pdfviewer.follow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mrgq.pdfviewer.recording.WavWriter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 태블릿 지휘자의 **마이크 악보 추적** (P10 §3.1). 스레드 하나에서 마이크 → [ChromaExtractor] → [OnlineAligner] → [PageTurnDecider].
 *
 * 0. 위치는 정렬기의 날것이 아니라 [InertialTracker] 로 거른 것 — 짧게 헤매도 고른 빠르기로 이어 간다 (P10 §9)
 * 1. **첫 소리를 기다린다** — 청크(≈ 46ms) 음량이 배경(느린 평균)의 [ONSET_RATIO] 배를 넘고 [ONSET_MIN_RMS] 보다 크면 연주 시작.
 *    그 칸이 정렬의 첫 칸 = 시작 마디 첫머리
 * 2. 칸마다 위치 → [Listener.onPosition](마디 순서가 바뀔 때) · [Listener.onTurn](넘김) — 모두 메인 스레드에서
 * 3. 손으로 넘기면 [anchor] — 다음 칸부터 그 쪽 첫 마디에서 다시 시작(첫 소리 기다림 없이)
 * 4. [recordTo] 가 있으면 소리(WAV)와 사건(JSON: 시작 · 위치 · 넘김 · 재위치 · 다시 맞춤)을 남긴다 — 실제 성적을 P08 스크립트로 재는 자료.
 *    사건 시각 `t_ms` 는 WAV 샘플 수로 센다(소리와 정확히 맞는다)
 */
class MicScoreFollower(
    private val score: ScoreChroma,
    private val pageOf: IntArray,
    lowerHalf: BooleanArray,
    private val startMeasure: Int,
    startPage: Int,
    private val recordTo: File?,
    private val info: Map<String, String>,
    private val listener: Listener,
) {
    interface Listener {
        /** 첫 소리를 들었다 — 이제 따라간다 */
        fun onListening()

        /** 추정 위치 (연속 마디, MusicXML 순서 0부터) — 마디가 바뀔 때마다 */
        fun onPosition(measurePos: Double)

        fun onTurn(turn: PageTurnDecider.Turn)

        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val decider = PageTurnDecider(pageOf, lowerHalf, startPage)
    @Volatile private var running = false
    private var thread: Thread? = null
    private val pendingAnchor = AtomicInteger(-1)

    private val events = JsonArray()
    private var samplesIn = 0L
    private var source = ""

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // 부르는 쪽이 RECORD_AUDIO 를 확인한다
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = open(MediaRecorder.AudioSource.UNPROCESSED, minBuffer)?.also { source = "UNPROCESSED" }
            ?: open(MediaRecorder.AudioSource.MIC, minBuffer)?.also { source = "MIC" }
            ?: return false
        running = true
        record.startRecording()
        thread = Thread({ loop(record) }, "MicScoreFollower").apply { start() }
        Log.i(TAG, "시작: 마디 순서 $startMeasure, 쪽 ${decider.page + 1}, 소스 $source")
        return true
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    /** 손으로 [page] 쪽으로 넘겼다 — 그 쪽 첫 마디(마디 순서 [measure])에서 다시 따라간다 */
    fun anchor(page: Int, measure: Int) {
        pendingAnchor.set(page * 100000 + measure)
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, minBuffer: Int): AudioRecord? = try {
        val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer * 4, HOP * 8))
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release()
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "마이크를 열지 못함 (source=$source)", e)
        null
    }

    private fun loop(record: AudioRecord) {
        val wav = recordTo?.let { WavWriter(it, SAMPLE_RATE) }
        val chunk = ShortArray(HOP)
        var aligner: OnlineAligner? = null
        var tracker: InertialTracker? = null
        var noise = -1.0
        var lastMeasure = -1
        var chunkRms = 0.0
        var lastPosLog = 0L
        val extractor = ChromaExtractor(SAMPLE_RATE, N_FFT, HOP) { raw ->
            val now = samplesIn
            // 손으로 넘김 → 그 자리에서 다시
            val a = pendingAnchor.getAndSet(-1)
            if (a >= 0) {
                val page = a / 100000
                val measure = a % 100000
                aligner = OnlineAligner(score.frames, score.colOfMeasure(measure), score.frameSec)
                tracker = null
                decider.moveTo(page)
                lastMeasure = -1
                mark(now, "anchor", "${page + 1}", mapOf("measure_index" to measure))
            }
            var al = aligner
            if (al == null) {
                // 첫 소리 기다리기
                if (noise < 0) noise = chunkRms
                val onset = chunkRms > noise * ONSET_RATIO && chunkRms > ONSET_MIN_RMS
                if (!onset) {
                    noise = noise * 0.95 + chunkRms * 0.05
                    return@ChromaExtractor
                }
                al = OnlineAligner(score.frames, score.colOfMeasure(startMeasure), score.frameSec)
                aligner = al
                mark(now, "onset", "", mapOf("rms" to chunkRms, "noise" to noise))
                main.post { listener.onListening() }
            }
            val jumpsBefore = al.jumps.size
            val col = al.feed(raw.normalized())
            // 관성 항법 (P10 §9) — 짧게 헤매는 추정은 고른 빠르기 예측으로 넘긴다
            val tr = tracker ?: InertialTracker(col.toDouble(), score.frameSec).also { tracker = it }
            val smooth = if (al.frames == 1) col.toDouble() else tr.feed(col.toDouble())
            if (al.jumps.size > jumpsBefore) {
                val j = al.jumps.last()
                mark(now, "reloc", "", mapOf("from" to score.measurePosOf(j.second), "to" to score.measurePosOf(j.third)))
            }
            val pos = score.measurePosOf(smooth)
            val nowMs = now * 1000 / SAMPLE_RATE
            if (pos.measureIndex() != lastMeasure) {
                lastMeasure = pos.measureIndex()
                main.post { listener.onPosition(pos) }
            }
            if (nowMs - lastPosLog >= 250) {
                lastPosLog = nowMs
                mark(now, "pos", "%.2f".format(java.util.Locale.US, pos), mapOf("raw" to score.measurePosOf(col), "v" to tr.velocity))
            }
            decider.update(pos, nowMs)?.let { turn ->
                mark(now, "turn", turn.toString(), null)
                main.post { listener.onTurn(turn) }
            }
        }
        try {
            while (running) {
                var got = 0
                while (got < HOP && running) {
                    val n = record.read(chunk, got, HOP - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < HOP) continue
                var s = 0.0
                for (v in chunk) s += v.toDouble() * v
                chunkRms = sqrt(s / HOP) / 32768.0
                wav?.write(chunk, HOP)
                extractor.push(chunk, HOP)
                samplesIn += HOP
            }
        } catch (e: Exception) {
            Log.w(TAG, "추적 중 오류", e)
            main.post { listener.onError(e.message ?: "마이크 오류") }
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // 이미 멈춤
            }
            record.release()
            wav?.close()
            recordTo?.let { writeEvents(it) }
            Log.i(TAG, "끝: ${samplesIn / SAMPLE_RATE}초, 재위치 ${aligner?.jumps?.size ?: 0}번")
        }
    }

    private fun mark(samples: Long, type: String, value: String, extra: Map<String, Any>?) {
        if (recordTo == null) return
        events.add(JsonObject().apply {
            addProperty("t_ms", samples * 1000 / SAMPLE_RATE)
            addProperty("type", type)
            addProperty("value", value)
            extra?.forEach { (k, v) -> if (v is Number) addProperty(k, v) else addProperty(k, v.toString()) }
        })
    }

    private fun writeEvents(wavFile: File) {
        val root = JsonObject().apply {
            addProperty("wav", wavFile.name)
            addProperty("sample_rate", SAMPLE_RATE)
            addProperty("source", source)
            addProperty("clock", "samples")
            addProperty("kind", "mic_follow")
            addProperty("frame_sec", score.frameSec)
            addProperty("start_measure_index", startMeasure)
            add("info", JsonObject().apply { info.forEach { (k, v) -> addProperty(k, v) } })
            add("events", events)
        }
        File(wavFile.path.removeSuffix(".wav") + ".json").writeText(root.toString())
    }

    companion object {
        private const val TAG = "MicScoreFollower"
        const val SAMPLE_RATE = 44100
        const val N_FFT = 4096
        const val HOP = 2048
        /** 첫 소리: 배경의 몇 배 (≈ 12dB) */
        const val ONSET_RATIO = 4.0
        /** 첫 소리: 최소 음량 (≈ −54 dBFS) */
        const val ONSET_MIN_RMS = 0.002
        val FRAME_SEC: Double get() = HOP.toDouble() / SAMPLE_RATE
    }
}
