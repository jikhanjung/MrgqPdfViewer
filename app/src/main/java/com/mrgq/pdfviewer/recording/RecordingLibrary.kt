package com.mrgq.pdfviewer.recording

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 태블릿에 쌓인 녹음 기록(`files/recordings/`) — 마이크 추적(`*_follow`, P10)과 예전 연습 녹음(P08).
 * 설정 → 🎤 녹음 기록 화면이 쓴다: 목록 · 요약 · 지우기.
 */
object RecordingLibrary {

    /** 녹음 하나 — WAV 와 같은 이름의 JSON(있으면) */
    data class Entry(
        val wav: File,
        val json: File?,
        /** 곡 이름 (파일 이름에서 시각 · `_follow` 를 뺀 것) */
        val title: String,
        val recordedAt: Date?,
        val isFollow: Boolean,
        val durationSec: Double,
        val bytes: Long,
        val summary: Summary?,
    )

    /** 추적 기록(JSON)의 요약. 마디는 MusicXML 순서(0부터)의 연속 위치 — 보일 때 +1 */
    data class Summary(
        val quarterBpm: String?,
        val startSec: Double?,
        val startMeasurePos: Double?,
        val pageTurns: Int,
        val halfTurns: Int,
        val relocations: Int,
        val manualAnchors: Int,
        val lastMeasurePos: Double?,
        val lastPage: Int?,
    )

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "recordings")

    private val NAME = Regex("""^(.*)_(\d{8})_(\d{6})(_follow)?$""")

    /** 새것부터 */
    fun list(dir: File): List<Entry> {
        val wavs = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return emptyList()
        return wavs.map { entryOf(it) }.sortedByDescending { it.recordedAt?.time ?: it.wav.lastModified() }
    }

    fun entryOf(wav: File): Entry {
        val base = wav.name.removeSuffix(".wav")
        val json = File(wav.parentFile, "$base.json").takeIf { it.isFile }
        val m = NAME.matchEntire(base)
        val title = m?.groupValues?.get(1) ?: base
        val at = m?.let {
            runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(it.groupValues[2] + it.groupValues[3]) }.getOrNull()
        }
        val bytes = wav.length() + (json?.length() ?: 0)
        val duration = (wav.length() - WavWriter.HEADER_BYTES).coerceAtLeast(0) / (SAMPLE_RATE * 2.0)
        val summary = json?.let { runCatching { summarize(it.readText()) }.getOrNull() }
        return Entry(wav, json, title, at, m?.groupValues?.get(4)?.isNotEmpty() == true, duration, bytes, summary)
    }

    /** JSON 글 → 요약 (추적 기록이 아니면 쪽 넘김만 0) */
    fun summarize(text: String): Summary {
        val root = JsonParser.parseString(text).asJsonObject
        val info = root.getAsJsonObject("info")
        var start: Double? = null
        var startPos: Double? = null
        var pages = 0
        var halves = 0
        var relocs = 0
        var anchors = 0
        var lastPos: Double? = null
        var lastPage: Int? = null
        root.getAsJsonArray("events")?.forEach { e ->
            val o = e.asJsonObject
            val t = o.get("t_ms")?.asDouble ?: 0.0
            val value = o.get("value")?.asString ?: ""
            when (o.get("type")?.asString) {
                "start", "onset" -> if (start == null) {
                    start = t / 1000
                    startPos = o.get("measure_pos")?.asDouble
                }
                "turn" -> {
                    val page = Regex("""page=(\d+)""").find(value)?.groupValues?.get(1)?.toIntOrNull()
                    if (value.startsWith("Page")) {
                        pages++
                        lastPage = page
                    } else halves++
                }
                "reloc" -> relocs++
                "anchor" -> anchors++
                "pos" -> lastPos = value.toDoubleOrNull()
            }
        }
        return Summary(info?.get("quarter_bpm")?.asString, start, startPos, pages, halves, relocs, anchors, lastPos, lastPage)
    }

    fun delete(entry: Entry): Boolean {
        entry.json?.delete()
        return entry.wav.delete()
    }

    private const val SAMPLE_RATE = 44100
}
