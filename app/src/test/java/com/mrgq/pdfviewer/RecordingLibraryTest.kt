package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.recording.RecordingLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 설정 → 녹음 기록: 파일 이름 · 요약 (P10) */
class RecordingLibraryTest {

    private val followJson = """
        {"wav":"x.wav","info":{"quarter_bpm":"77.0"},"events":[
          {"t_ms":3000,"type":"start_check","value":"0.980"},
          {"t_ms":9891,"type":"start","value":"","ratio":0.64,"measure_pos":1.44},
          {"t_ms":10000,"type":"pos","value":"1.50"},
          {"t_ms":40000,"type":"turn","value":"Half(page=0, nextPage=1)"},
          {"t_ms":50000,"type":"turn","value":"Page(page=1)"},
          {"t_ms":55000,"type":"reloc","value":""},
          {"t_ms":60000,"type":"anchor","value":"3"},
          {"t_ms":70000,"type":"turn","value":"Page(page=2)"},
          {"t_ms":80000,"type":"pos","value":"25.25"}
        ]}
    """.trimIndent()

    @Test
    fun `추적 기록 요약`() {
        val s = RecordingLibrary.summarize(followJson)
        assertEquals("77.0", s.quarterBpm)
        assertEquals(9.891, s.startSec!!, 1e-9)
        assertEquals(1.44, s.startMeasurePos!!, 1e-9)
        assertEquals(2, s.pageTurns)
        assertEquals(1, s.halfTurns)
        assertEquals(1, s.relocations)
        assertEquals(1, s.manualAnchors)
        assertEquals(2, s.lastPage)
        assertEquals(25.25, s.lastMeasurePos!!, 1e-9)
    }

    @Test
    fun `파일 이름에서 곡 · 시각 · 추적 여부, 새것부터`() {
        val dir = Files.createTempDirectory("rec").toFile()
        try {
            File(dir, "Sonate für Arpeggione (Full score)_20260929_115721_follow.wav").writeBytes(ByteArray(44 + 44100 * 2 * 3))
            File(dir, "Sonate für Arpeggione (Full score)_20260929_115721_follow.json").writeText(followJson)
            File(dir, "Die Moldau_20260928_195355.wav").writeBytes(ByteArray(44 + 100))
            File(dir, "memo.txt").writeText("x")
            val list = RecordingLibrary.list(dir)
            assertEquals(2, list.size)
            val first = list[0]
            assertEquals("Sonate für Arpeggione (Full score)", first.title)
            assertTrue(first.isFollow)
            assertEquals(3.0, first.durationSec, 1e-9)
            assertEquals(2, first.summary!!.pageTurns)
            val second = list[1]
            assertEquals("Die Moldau", second.title)
            assertFalse(second.isFollow)
            assertEquals(null, second.json)
            assertTrue(RecordingLibrary.delete(first))
            assertEquals(1, RecordingLibrary.list(dir).size)
            assertFalse(File(dir, "Sonate für Arpeggione (Full score)_20260929_115721_follow.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
