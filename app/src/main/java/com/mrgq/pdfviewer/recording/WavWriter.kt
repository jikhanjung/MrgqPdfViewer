package com.mrgq.pdfviewer.recording

import java.io.File
import java.io.RandomAccessFile

/** 16비트 모노 PCM WAV 파일 쓰기 — 머리는 [close] 때 길이를 알고 채운다. 연습 녹음 · 마이크 추적 기록이 같이 쓴다 */
class WavWriter(file: File, private val sampleRate: Int) {
    private val out: RandomAccessFile
    private var bytes = ByteArray(8192)

    /** 쓴 소리 샘플 수 */
    var samples = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(ByteArray(HEADER_BYTES))
    }

    fun write(buffer: ShortArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
        }
        out.write(bytes, 0, count * 2)
        samples += count
    }

    fun close() {
        val data = samples * 2
        fun le32(v: Long) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
        out.seek(0)
        out.write("RIFF".toByteArray()); out.write(le32(36 + data)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
        out.write(le32(sampleRate.toLong())); out.write(le32(sampleRate * 2L)); out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(data))
        out.close()
    }

    companion object {
        const val HEADER_BYTES = 44
    }
}
