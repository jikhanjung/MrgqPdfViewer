"""P08 A단계 — 앱 연습 녹음(WAV + 사건 JSON)의 시각 맞춤 확인.

녹음 JSON 의 박 기록(t_ms)과 WAV 속 메트로놈 클릭을 비교해 **출력 지연**(기록 → 실제 소리)을 잰다.
예비박(count_in) 박은 반주 없이 클릭만 나므로 그것으로 잰다. 결과 오프셋을 모든 사건에 더하면 오디오와 맞는다.

  adb pull /sdcard/Android/data/com.mrgq.pdfviewer/files/recordings/ rec/
  python data/recording_align.py "rec/Die Moldau (Vltava) (Full Score)_20260928_195355"

실측(2026-09-28, 샤오신패드 TB371FC, 스피커 → 자기 마이크): 클릭이 기록보다 약 60ms 늦다(대부분 58~66ms).
"""
import json
import sys
import wave

import numpy as np


def load(base):
    events = json.load(open(base + ".json", encoding="utf-8"))
    w = wave.open(base + ".wav")
    sr = w.getframerate()
    audio = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float)
    return events, audio, sr


def highpass_env(audio, sr, cutoff=3000):
    """클릭은 고역이 강하다 — 반주(저 · 중역)를 걸러 낸 절댓값"""
    spec = np.fft.rfft(audio)
    spec[np.fft.rfftfreq(len(audio), 1 / sr) < cutoff] = 0
    return np.abs(np.fft.irfft(spec, len(audio)))


def click_offsets(events, env, sr, window_ms=150):
    """예비박 박마다 기록 시각 뒤 [window_ms] 안에서 클릭 봉우리까지 (ms). 1ms 칸 평균 중 가장 큰 곳"""
    out = []
    for e in events["events"]:
        if e["type"] != "beat" or "count_in" not in e:
            continue
        start = int(e["t_ms"] * sr / 1000)
        seg = env[start:start + int(window_ms * sr / 1000)]
        step = sr // 1000
        bins = seg[: len(seg) // step * step].reshape(-1, step).mean(1)
        if len(bins):
            out.append(float(np.argmax(bins)))
    return np.array(out)


def main(base):
    events, audio, sr = load(base)
    print(f"{events['wav']}  {events['duration_ms'] / 1000:.1f}s  source={events.get('source')}  clock={events.get('clock')}")
    counts = {}
    for e in events["events"]:
        counts[e["type"]] = counts.get(e["type"], 0) + 1
    print("사건:", counts)
    offsets = click_offsets(events, highpass_env(audio, sr), sr)
    if len(offsets) == 0:
        print("예비박 박이 없다 — 악보 연동 메트로놈을 녹음 중에 시작해야 잰다")
        return
    med = np.median(offsets)
    print(f"예비박 클릭 {len(offsets)}개: 기록 → 소리 중앙값 {med:.0f}ms (범위 {offsets.min():.0f}~{offsets.max():.0f})")
    beats = np.array([e["t_ms"] for e in events["events"] if e["type"] == "beat"])
    if len(beats) > 1:
        print(f"박 기록 간격 흔들림(표준편차) {np.diff(beats).std():.2f}ms")


if __name__ == "__main__":
    main(sys.argv[1].removesuffix(".json").removesuffix(".wav"))
