package com.mrgq.pdfviewer.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
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
 *
 * **시간표 모드** ([startScheduled], 합주 #055): 박을 샘플 수로 세는 대신 [BeatSchedule] 이 정한 **시각**에 놓는다.
 * `AudioTrack.getTimestamp()` 로 프레임 ↔ 단조 시계를 대응시켜 그 시각이 몇 번째 프레임인지 계산한다. 화면의 박은
 * 재생 위치가 아니라 시간표로 고른다 — 소리를 끈 연주자도 같은 박을 본다. 설정 필드(bpm 등)는 쓰지 않는다.
 */
class MetronomeEngine(private val sampleRate: Int = 44100) {

    @Volatile var bpm = MetronomeClock.DEFAULT_BPM
    @Volatile var timeSignature = TimeSignature.DEFAULT
    /** 겹박자를 점음표 박으로 센다 (6/8 = 2박). [bpm] 도 점음표 기준. */
    @Volatile var dottedBeat = false
    @Volatile var volume = 0.6f
    @Volatile var soundEnabled = true

    /** 악보 연동 중이면 박 번호 → 마디 안 위치와 박자 (강박을 악보 박자표대로). null 이면 [timeSignature] 로 센다. */
    @Volatile var barPosition: ((Long) -> BarPosition)? = null

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
    @Volatile private var schedule: BeatSchedule? = null

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

    /**
     * 시간표 모드로 시작한다 (#055). [withSound] 가 false 거나 소리 장치를 못 열면 스레드 없이 시간표로 박만 센다.
     * 이미 지난 박은 건너뛰고 다음 박부터 울린다 (늦은 합류).
     * @return 소리와 함께 시작했으면 true
     */
    fun startScheduled(schedule: BeatSchedule, withSound: Boolean): Boolean {
        if (isRunning) stop()
        synchronized(lock) { recentBeats.clear() }
        this.schedule = schedule
        val audio = if (withSound) createTrack() else null
        isRunning = true
        isSilentFallback = audio == null
        startNanos = System.nanoTime()
        if (audio != null) {
            track = audio
            audio.play()
            thread = Thread({ scheduledAudioLoop(audio, schedule) }, "Metronome").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } else if (withSound) {
            Log.w(TAG, "AudioTrack 을 열지 못함 — 시간표로 박만 센다")
        }
        return audio != null
    }

    /** 시간표 모드인가 */
    val isScheduled: Boolean get() = isRunning && schedule != null

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
        schedule = null
        synchronized(lock) { recentBeats.clear() }
    }

    /** 지금 들리고 있는 박 (가장 최근에 재생 위치를 지난 박). 시작 직후 첫 박 전이면 null. */
    fun currentBeat(): Beat? {
        if (!isRunning) return null
        schedule?.let { s ->
            val beat = s.beatAtLocal(System.nanoTime())
            return if (beat < 0) null else s.beatInfo(beat)
        }
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
        val accentClick = ClickSynth.render(sampleRate, Accent.STRONG)
        val mediumClick = ClickSynth.render(sampleRate, Accent.MEDIUM)
        val normalClick = ClickSynth.render(sampleRate, Accent.WEAK)
        val buffer = ShortArray(CHUNK_FRAMES)
        val clock = MetronomeClock(sampleRate, startFrame = (sampleRate * LEAD_IN_SEC).toLong())

        var next = record(clock.next(bpm, timeSignature, dottedBeat, barPosition))
        var written = 0L
        var click: ShortArray? = null
        var clickPos = 0

        while (isRunning) {
            val gain = if (soundEnabled) volume.coerceIn(0f, 1f) else 0f
            for (i in 0 until CHUNK_FRAMES) {
                if (written + i >= next.frame) {
                    click = when (next.accent) {
                        Accent.STRONG -> accentClick
                        Accent.MEDIUM -> mediumClick
                        Accent.WEAK -> normalClick
                    }
                    clickPos = 0
                    next = record(clock.next(bpm, timeSignature, dottedBeat, barPosition))
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

    /**
     * 시간표 모드의 오디오 루프. 쓰는 프레임과 시각의 대응은 `getTimestamp()` 로 얻는다 — 재생 전(타임스탬프 없음)에는
     * 무음만 쓴다. 다음 박의 프레임은 청크마다 다시 계산한다 (시계 동기가 offset 을 고치거나 템포가 바뀌어도 따라간다).
     */
    private fun scheduledAudioLoop(audio: AudioTrack, schedule: BeatSchedule) {
        val clicks = mapOf(
            Accent.STRONG to ClickSynth.render(sampleRate, Accent.STRONG),
            Accent.MEDIUM to ClickSynth.render(sampleRate, Accent.MEDIUM),
            Accent.WEAK to ClickSynth.render(sampleRate, Accent.WEAK),
        )
        val buffer = ShortArray(CHUNK_FRAMES)
        val timestamp = AudioTimestamp()
        var haveTimestamp = false
        var lastTimestampRead = 0L
        val loopStart = System.nanoTime()
        var written = 0L
        var nextBeat = -1L
        var click: ShortArray? = null
        var clickPos = 0

        fun frameOf(localNs: Long): Long =
            timestamp.framePosition + Math.round((localNs - timestamp.nanoTime) * sampleRate / 1e9)

        while (isRunning) {
            val now = System.nanoTime()
            if (!haveTimestamp || now - lastTimestampRead > TIMESTAMP_REFRESH_NS) {
                lastTimestampRead = now
                if (audio.getTimestamp(timestamp)) {
                    haveTimestamp = true
                } else if (now - loopStart > TIMESTAMP_FALLBACK_NS) {
                    // 타임스탬프를 주지 않는 장치 — 재생 위치를 지금 들리는 프레임으로 본다 (출력 지연만큼 늦게 들린다)
                    timestamp.framePosition = audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    timestamp.nanoTime = now
                    haveTimestamp = true
                }
            }

            var targetFrame = Long.MAX_VALUE
            if (haveTimestamp) {
                if (nextBeat < 0) {
                    // 지금 쓰는 프레임이 울릴 시각 다음의 첫 박부터 (박 0 이전은 없다)
                    val writingAtNs = timestamp.nanoTime + (written - timestamp.framePosition) * 1_000_000_000L / sampleRate
                    nextBeat = maxOf(0L, schedule.beatAtLocal(writingAtNs) + 1)
                }
                targetFrame = frameOf(schedule.localTimeOf(nextBeat))
                // 이미 한참 지난 박(시계 보정으로 당겨짐 등)은 치지 않고 건너뛴다. 조금 늦은 건 바로 친다
                if (targetFrame < written - LATE_TOLERANCE_FRAMES) {
                    nextBeat = -1L
                    targetFrame = Long.MAX_VALUE
                }
            }

            val gain = if (soundEnabled) volume.coerceIn(0f, 1f) else 0f
            for (i in 0 until CHUNK_FRAMES) {
                if (written + i >= targetFrame) {
                    val beat = schedule.beatInfo(nextBeat)
                    click = clicks.getValue(beat.accent)
                    clickPos = 0
                    nextBeat++
                    targetFrame = frameOf(schedule.localTimeOf(nextBeat))
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
            val beat = record(clock.next(bpm, timeSignature, dottedBeat, barPosition))
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
        /** 시간표 모드: 프레임 ↔ 시각 대응을 다시 읽는 간격 — 오디오 장치 시계와 시스템 시계의 속도 차를 따라간다 */
        const val TIMESTAMP_REFRESH_NS = 250_000_000L
        /** 이만큼 지나도 타임스탬프가 없으면 재생 위치로 대신한다 */
        const val TIMESTAMP_FALLBACK_NS = 1_000_000_000L
        /** 이만큼(약 20ms) 늦은 박까지는 바로 친다. 더 늦으면 건너뛴다 */
        const val LATE_TOLERANCE_FRAMES = 882L
    }
}
