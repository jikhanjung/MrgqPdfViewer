package com.mrgq.pdfviewer.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * 메트로놈 재생기.
 *
 * **AudioTrack 스트림에 클릭 파형을 직접 이어 쓴다.** 박 위치는 [MetronomeClock] 이 샘플 단위로 정하므로
 * 스레드 스케줄링에 흔들리지 않는다 (Handler 로 SoundPool 을 울리면 수~수십 ms 씩 흔들린다).
 * 화면의 박 표시는 AudioTrack 의 **실제 재생 위치**(playback head)로 고른다 — 소리와 표시가 같은 시계를 본다.
 *
 * 소리 장치를 열지 못하면(에뮬레이터 등) 벽시계로 박만 세는 **무음 모드**로 돈다.
 *
 * 설정 필드는 재생 중에 바꿔도 된다 — 오디오 스레드가 다음 박을 놓을 때 읽는다.
 */
class MetronomeEngine(private val sampleRate: Int = 44100) {

    @Volatile var bpm = MetronomeClock.DEFAULT_BPM
    @Volatile var beatsPerBar = MetronomeClock.DEFAULT_BEATS
    @Volatile var volume = 0.6f
    @Volatile var soundEnabled = true

    @Volatile var isRunning = false
        private set

    /** 소리 장치를 열지 못해 박 표시만 하는 중인가. */
    @Volatile var isSilentFallback = false
        private set

    private val lock = Any()
    private val recentBeats = ArrayDeque<Beat>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var startNanos = 0L

    /** @return 소리와 함께 시작했으면 true, 무음 모드면 false */
    fun start(): Boolean {
        if (isRunning) return !isSilentFallback
        synchronized(lock) { recentBeats.clear() }

        val audio = createTrack()
        isRunning = true
        isSilentFallback = audio == null
        startNanos = System.nanoTime()

        thread = if (audio != null) {
            track = audio
            audio.play()
            Thread({ audioLoop(audio) }, "Metronome").apply { priority = Thread.MAX_PRIORITY }
        } else {
            Log.w(TAG, "AudioTrack 을 열지 못함 — 무음 모드")
            Thread({ silentLoop() }, "Metronome")
        }.also { it.start() }
        return audio != null
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        val audio = track
        try {
            // 쓰기에서 막혀 있는 오디오 스레드를 풀어 준다
            audio?.pause()
            audio?.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack 정지 중 오류", e)
        }
        thread?.interrupt()
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        audio?.release()
        track = null
        synchronized(lock) { recentBeats.clear() }
    }

    /** 지금 들리고 있는 박 (가장 최근에 재생 위치를 지난 박). 시작 직후 첫 박 전이면 null. */
    fun currentBeat(): Beat? {
        if (!isRunning) return null
        val played = if (isSilentFallback) {
            (System.nanoTime() - startNanos) * sampleRate / 1_000_000_000L
        } else {
            try {
                // playbackHeadPosition 은 부호 없는 32bit 로 취급해야 한다
                (track ?: return null).playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (e: IllegalStateException) {
                return null
            }
        }
        synchronized(lock) {
            return recentBeats.lastOrNull { it.frame <= played }
        }
    }

    private fun record(beat: Beat): Beat {
        synchronized(lock) {
            recentBeats.addLast(beat)
            while (recentBeats.size > RECENT_BEATS) recentBeats.removeFirst()
        }
        return beat
    }

    private fun audioLoop(audio: AudioTrack) {
        val accentClick = ClickSynth.render(sampleRate, accent = true)
        val normalClick = ClickSynth.render(sampleRate, accent = false)
        val buffer = ShortArray(CHUNK_FRAMES)
        val clock = MetronomeClock(sampleRate, startFrame = (sampleRate * LEAD_IN_SEC).toLong())

        var next = record(clock.next(bpm, beatsPerBar))
        var written = 0L
        var click: ShortArray? = null
        var clickPos = 0

        while (isRunning) {
            val gain = if (soundEnabled) volume.coerceIn(0f, 1f) else 0f
            for (i in 0 until CHUNK_FRAMES) {
                if (written + i >= next.frame) {
                    click = if (next.isAccent) accentClick else normalClick
                    clickPos = 0
                    next = record(clock.next(bpm, beatsPerBar))
                }
                var sample = 0
                val current = click
                if (current != null) {
                    sample = (current[clickPos] * gain).toInt()
                    clickPos++
                    if (clickPos >= current.size) click = null
                }
                buffer[i] = sample.toShort()
            }
            val result = try {
                audio.write(buffer, 0, CHUNK_FRAMES)
            } catch (e: IllegalStateException) {
                -1
            }
            if (result < 0) {
                if (isRunning) Log.w(TAG, "AudioTrack 쓰기 실패: $result")
                break
            }
            written += CHUNK_FRAMES
        }
    }

    private fun silentLoop() {
        val clock = MetronomeClock(sampleRate, startFrame = (sampleRate * LEAD_IN_SEC).toLong())
        while (isRunning) {
            val beat = record(clock.next(bpm, beatsPerBar))
            val dueNanos = startNanos + beat.frame * 1_000_000_000L / sampleRate
            val waitMs = (dueNanos - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun createTrack(): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            null
        } else {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val created = AudioTrack(attributes, format, minBuffer * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (created.state == AudioTrack.STATE_INITIALIZED) {
                created
            } else {
                created.release()
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "AudioTrack 생성 실패", e)
        null
    }

    private companion object {
        const val TAG = "MetronomeEngine"
        /** 한 번에 쓰는 프레임 수 (44.1kHz 에서 약 12ms) — 템포 변경이 반영되는 최대 지연이기도 하다. */
        const val CHUNK_FRAMES = 512
        /** 첫 박 앞 여유 — 재생 시작 직후 클릭 앞머리가 잘리지 않게. */
        const val LEAD_IN_SEC = 0.15
        const val RECENT_BEATS = 16
        const val JOIN_TIMEOUT_MS = 300L
    }
}
