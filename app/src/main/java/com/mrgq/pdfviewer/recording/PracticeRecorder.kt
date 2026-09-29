package com.mrgq.pdfviewer.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/**
 * 연습 녹음 (P08 A단계 — 마이크 악보 추적 실험용 자료). 태블릿 마이크를 **WAV(16비트 모노 44.1kHz)** 로 받고,
 * 녹음하는 동안의 **사건**(쪽 넘김 · 메트로놈 시작/정지 · 악보 연동의 박 · 마디)을 녹음 시작 기준 시각과 함께 옆 `.json` 에 적는다.
 * 메트로놈 악보 연동을 켠 채 연주하면 그 박 · 마디 기록이 "몇 초에 몇 마디"라는 **정답**이 된다.
 *
 * 시각 기준: **WAV 첫 샘플이 마이크에 들어온 순간 = 0** (`System.nanoTime`). `AudioRecord.getTimestamp()` 로 프레임 ↔ 시각을
 * 대응시켜 구한다 — 첫 버퍼가 도착한 순간을 0 으로 잡으면 입력 지연만큼(태블릿 실측 ~190ms) 사건이 늦게 적힌다.
 * 타임스탬프를 못 얻는 기기면 첫 버퍼 도착 시각에서 버퍼 길이를 뺀다 (`clock` 에 어느 쪽인지 적는다).
 */
class PracticeRecorder(
    private val wavFile: File,
    /** 녹음에 대한 설명 — 파일 · 파트 · 템포 등 (json 머리에) */
    private val info: Map<String, String>,
) {
    private val events = JsonArray()
    private val lock = Any()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var startNanos = 0L
    private var dataBytes = 0L
    /** 실제로 연 마이크 소스 — UNPROCESSED 는 기기에 따라 소리가 아주 작다 (json 에 적어 비교) */
    private var sourceName = ""

    val isRecording: Boolean get() = running

    /** 녹음 시작부터 지난 시간 (ms) — 화면 표시용 */
    val elapsedMs: Long get() = if (startNanos == 0L) 0 else (System.nanoTime() - startNanos) / 1_000_000
    private var clockSource = ""

    /** @return 시작했으면 true (마이크를 열지 못하면 false) */
    @SuppressLint("MissingPermission") // 부르는 쪽이 RECORD_AUDIO 를 확인한다
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        // 먼저 가공하지 않은 소리(UNPROCESSED — 소음 제거 · 자동 음량 없이)를, 안 되는 기기면 일반 마이크를
        val record = open(MediaRecorder.AudioSource.UNPROCESSED, minBuffer)?.also { sourceName = "UNPROCESSED" }
            ?: open(MediaRecorder.AudioSource.MIC, minBuffer)?.also { sourceName = "MIC" }
            ?: return false

        val out = WavWriter(wavFile, SAMPLE_RATE)
        running = true
        record.startRecording()
        thread = Thread({
            val buffer = ShortArray(minBuffer)
            var first = true
            try {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    if (first) {
                        val arrived = System.nanoTime()
                        val stamp = android.media.AudioTimestamp()
                        startNanos = if (record.getTimestamp(stamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            clockSource = "timestamp"
                            stamp.nanoTime - stamp.framePosition * 1_000_000_000L / SAMPLE_RATE
                        } else {
                            clockSource = "first_buffer"
                            arrived - n * 1_000_000_000L / SAMPLE_RATE
                        }
                        mark("start", "", mapOf("first_buffer_ms" to (arrived - startNanos) / 1_000_000.0), atNanos = startNanos)
                        first = false
                    }
                    out.write(buffer, n)
                    dataBytes += n * 2
                }
            } catch (e: Exception) {
                Log.w(TAG, "녹음 중 오류", e)
            } finally {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    // 이미 멈춤
                }
                record.release()
                out.close()
            }
        }, "PracticeRecorder").apply { start() }
        Log.i(TAG, "녹음 시작: ${wavFile.name} (source=$sourceName)")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, minBuffer: Int): AudioRecord? = try {
        val record = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        if (record.state == AudioRecord.STATE_INITIALIZED) record else {
            record.release()
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "마이크를 열지 못함 (source=$source)", e)
        null
    }

    /** 사건 하나 — [type] 예: page · measure · beat · metronome */
    fun mark(type: String, value: String, extra: Map<String, Any>? = null, atNanos: Long? = null) {
        if (startNanos == 0L) return // 첫 버퍼 전에는 시각 기준이 없다
        val t = ((atNanos ?: System.nanoTime()) - startNanos) / 1_000_000.0
        synchronized(lock) {
            events.add(JsonObject().apply {
                addProperty("t_ms", Math.round(t * 10) / 10.0)
                addProperty("type", type)
                addProperty("value", value)
                extra?.forEach { (k, v) -> if (v is Number) addProperty(k, v) else addProperty(k, v.toString()) }
            })
        }
    }

    /** 멈추고 WAV 머리 · 사건 json 을 마무리한다. @return 사건 json 파일 */
    fun stop(): File {
        mark("stop", "")
        running = false
        thread?.join(2000)
        thread = null
        val json = File(wavFile.path.removeSuffix(".wav") + ".json")
        val root = JsonObject().apply {
            addProperty("wav", wavFile.name)
            addProperty("sample_rate", SAMPLE_RATE)
            addProperty("source", sourceName)
            addProperty("clock", clockSource)
            addProperty("duration_ms", dataBytes * 1000 / (SAMPLE_RATE * 2))
            add("info", JsonObject().apply { info.forEach { (k, v) -> addProperty(k, v) } })
            synchronized(lock) { add("events", events) }
        }
        json.writeText(root.toString())
        Log.i(TAG, "녹음 끝: ${wavFile.name} (${dataBytes / (SAMPLE_RATE * 2)}초, 사건 ${events.size()}개)")
        return json
    }

    companion object {
        private const val TAG = "PracticeRecorder"
        const val SAMPLE_RATE = 44100
    }
}
