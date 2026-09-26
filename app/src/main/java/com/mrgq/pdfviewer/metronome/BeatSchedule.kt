package com.mrgq.pdfviewer.metronome

/**
 * 박이 **언제** 울리는지를 밖에서 정해 주는 시간표 — 합주에서 지휘자와 연주자가 같은 시간표로 돈다 (#055).
 * 시각은 이 기기의 `System.nanoTime()` (단조 시계, `AudioTimestamp.nanoTime` 과 같은 시간축).
 * 오디오 스레드와 UI 스레드가 동시에 읽는다 — 구현은 스레드 안전해야 한다.
 */
interface BeatSchedule {
    /** 박 [beat] 이 울리는 이 기기 시각 */
    fun localTimeOf(beat: Long): Long

    /** [localNs] 에 들리고 있는 박 — 시각이 [localNs] 이하인 마지막 박. 첫 박(0) 전이면 음수 */
    fun beatAtLocal(localNs: Long): Long

    /** 박 [beat] 의 마디 안 위치 · 박자 · 템포 ([Beat.frame] 은 쓰지 않는다) */
    fun beatInfo(beat: Long): Beat
}
