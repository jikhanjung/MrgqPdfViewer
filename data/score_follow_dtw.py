"""P08 4단계 오프라인 실험 — 크로마 + DTW 로 녹음이 악보 어디인지 찾기.

녹음(WAV + 사건 JSON, 앱 연습 녹음)과 같은 곡의 MusicXML 을 받아:
  1. MusicXML 음표 → 악보 크로마(12음 성분, 박 위치를 기준 빠르기로 초로) — 합성 없이 기호에서 바로
  2. 녹음 → 크로마(CQT)
  3. 부분 DTW(subsequence) — 녹음이 곡 전체의 어느 구간인지 모른 채로 맞춘다
  4. 녹음의 박 기록(정답)마다 DTW 가 가리킨 악보 위치와 비교 — 박 오차 · 마디 정답률

  python data/score_follow_dtw.py <녹음 base> <곡.musicxml> [--offset-ms 60] [--online]

--online: 앞만 보는 추정 — 0.1초마다 최근 4초를 지금 위치 근처 악보에 맞춘다. 앱에서 실시간으로 돌릴 방식의 근사.
"""
import argparse
import json
import sys
import wave
import xml.etree.ElementTree as ET

import librosa
import numpy as np

SR = 22050
HOP = 512  # 23.2ms
# DTW 한 걸음: 녹음 1 · 악보 1 / 녹음 1 · 악보 2 / 녹음 2 · 악보 1 — 빠르기 0.5 ~ 2배, 멈춰 서기만 이어지는 경로를 막는다
SLOPE = np.array([[1, 1], [1, 2], [2, 1]])
STEP = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def read_musicxml(path):
    """(onset_q, dur_q, pitch_class, part) 목록과 마디 시작(4분음표 단위) — 첫 파트 기준 마디 길이"""
    root = ET.parse(path).getroot()
    notes, measure_starts = [], None
    for pi, part in enumerate(root.findall("part")):
        divisions, pos, starts = 1, 0.0, []
        for measure in part.findall("measure"):
            starts.append(pos)
            cur, maxcur, last_onset = 0.0, 0.0, 0.0
            for el in measure:
                if el.tag == "attributes":
                    d = el.find("divisions")
                    if d is not None:
                        divisions = float(d.text)
                elif el.tag == "backup":
                    cur -= float(el.find("duration").text) / divisions
                elif el.tag == "forward":
                    cur += float(el.find("duration").text) / divisions
                elif el.tag == "note":
                    if el.find("grace") is not None:
                        continue
                    dur_el = el.find("duration")
                    dur = float(dur_el.text) / divisions if dur_el is not None else 0.0
                    chord = el.find("chord") is not None
                    onset = last_onset if chord else cur
                    p = el.find("pitch")
                    if p is not None:
                        alter = p.find("alter")
                        pc = (STEP[p.find("step").text] + (int(float(alter.text)) if alter is not None else 0)) % 12
                        notes.append((pos + onset, dur, pc, pi))
                    if not chord:
                        last_onset = cur
                        cur += dur
                maxcur = max(maxcur, cur)
            pos += maxcur
        starts.append(pos)
        if measure_starts is None:
            measure_starts = np.array(starts)
    return notes, measure_starts


def score_chroma(notes, total_q, sec_per_q):
    n = int(total_q * sec_per_q * SR / HOP) + 1
    c = np.zeros((12, n))
    for onset, dur, pc, _ in notes:
        a = int(onset * sec_per_q * SR / HOP)
        b = max(a + 1, int((onset + dur) * sec_per_q * SR / HOP))
        # 기타 · 합성 소리처럼 친 뒤 줄어든다 — 앞을 무겁게
        w = np.exp(-np.arange(b - a) * HOP / SR / 0.6)
        c[pc, a:b] += w[: max(0, min(b, n) - a)]
    return c


def normalize(c):
    return c / (np.linalg.norm(c, axis=0, keepdims=True) + 1e-9)


def online_dtw(C, start_frame=0, start_col=0, window_s=4.0, every=4, ahead_s=8.0):
    """앞만 보는(인과적) 추정 — [every] 프레임마다 **최근 [window_s] 초 녹음**을 지금 추정 위치 근처 악보 구간
    ([-2s, +ahead_s])에 부분 DTW 로 맞춰 경로 끝을 지금 위치로. 처음에는 악보 앞 [start_s] 초 안에서 시작한다
    (끝은 지난 위치에서 0 ~ 2배 속도만 — 반복 음형으로 뛰지 않게. 앱은 시작 마디와 연주 시작 시각을 안다 — [start_frame] 녹음 프레임에 악보 [start_col]). 사이 프레임은 직전 추정"""
    n, m = C.shape
    win = int(window_s * SR / HOP)
    ahead = int(ahead_s * SR / HOP)
    back = int(2.0 * SR / HOP)
    path = np.zeros(n, dtype=int)
    est = start_col
    for i in range(start_frame, n):
        if (i - start_frame) % every == 0 and i - start_frame >= 8:
            a = max(start_frame, i - win)
            lo = max(start_col, est - back - (i - a))
            hi = min(m, est + ahead)
            D = librosa.sequence.dtw(C=C[a:i + 1, lo:hi], subseq=True, backtrack=False, step_sizes_sigma=SLOPE)
            # 끝 위치는 빠르기 제약 안에서만 — 지난 갱신 뒤로 0 ~ 2배 진행 (반복 음형으로 멀리 뛰지 않게)
            e0, e1 = est - lo, min(hi - lo, est - lo + 2 * every + 1)
            last = D[-1] / (i + 1 - a)
            est = lo + e0 + int(np.argmin(last[e0:e1]))
        path[i] = est
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("musicxml")
    ap.add_argument("--offset-ms", type=float, default=60.0, help="기록 → 실제 소리 지연 (recording_align.py)")
    ap.add_argument("--online", action="store_true")
    args = ap.parse_args()
    base = args.base.removesuffix(".json").removesuffix(".wav")
    meta = json.load(open(base + ".json", encoding="utf-8"))
    notes, mstarts = read_musicxml(args.musicxml)
    # 박 단위는 정답 기록에서 — 마디 길이(4분음표) ÷ 그 마디의 박 수 (info 의 박자는 메트로놈 설정이라 악보와 다를 수 있다)
    beats = [e for e in meta["events"] if e["type"] == "beat" and "measure" in e]
    first = beats[0]["measure"]
    per_bar = 1 + max(e["beat_in_measure"] for e in beats if e["measure"] == first)
    q_per_beat = (mstarts[first] - mstarts[first - 1]) / per_bar
    bpm = float(beats[0]["bpm"])
    sec_per_q = 60.0 / bpm / q_per_beat
    print(f"박 = {q_per_beat:g} 4분음표, {bpm:.0f} bpm, 마디당 {per_bar}박")
    total_q = mstarts[-1]
    print(f"MusicXML: 음 {len(notes)}개, 마디 {len(mstarts) - 1}개, {total_q:.0f} 4분음표 (기준 {bpm:.0f} bpm → {total_q * sec_per_q:.0f}s)")
    Y = normalize(score_chroma(notes, total_q, sec_per_q))

    y, _ = librosa.load(base + ".wav", sr=SR, mono=True)
    X = normalize(librosa.feature.chroma_cqt(y=y, sr=SR, hop_length=HOP))
    C = 1 - X.T @ Y  # 코사인 거리 (녹음 프레임 × 악보 프레임)
    silent = np.sqrt((librosa.feature.rms(y=y, hop_length=HOP)[0]) ** 2) < 10 ** (-50 / 20)

    if args.online:
        first = next(e for e in meta["events"] if e["type"] == "beat" and "measure" in e)
        start_frame = int((first["t_ms"] + args.offset_ms) / 1000 * SR / HOP)
        start_col = int(mstarts[first["measure"] - 1] * sec_per_q * SR / HOP)
        path = online_dtw(C, start_frame, start_col)
    else:
        _, wp = librosa.sequence.dtw(C=C, subseq=True)
        wp = wp[::-1]
        path = np.zeros(C.shape[0], dtype=int)
        for i, j in wp:
            path[i] = j  # 같은 i 면 마지막(가장 뒤) j
    score_q = path * HOP / SR / sec_per_q

    # 정답: 마디 안 박 기록 → 악보 위치(4분음표)
    errs, hits, rows = [], 0, []
    for e in meta["events"]:
        if e["type"] != "beat" or "measure" not in e:
            continue
        m = int(e["measure"])
        truth_q = mstarts[m - 1] + e["beat_in_measure"] * q_per_beat
        frame = int((e["t_ms"] + args.offset_ms) / 1000 * SR / HOP)
        if frame >= len(path) or silent[min(frame, len(silent) - 1)]:
            continue
        est_q = score_q[frame]
        est_m = int(np.searchsorted(mstarts, est_q, side="right"))
        errs.append((est_q - truth_q) / q_per_beat)
        hits += est_m == m
        rows.append((e["t_ms"], m, e["beat_in_measure"], est_m, (est_q - truth_q) / q_per_beat))
    errs = np.array(errs)
    mode = "온라인(최근 4초 창)" if args.online else "오프라인 부분 DTW"
    near = int(np.sum(np.abs(errs) <= 0.5))
    print(f"[{mode}] 박 {len(errs)}개: 마디 정답 {hits}/{len(errs)} ({100 * hits / max(1, len(errs)):.0f}%), ±0.5박 안 {near}/{len(errs)}, "
          f"박 오차 중앙값 {np.median(np.abs(errs)):.2f}박, 90% {np.percentile(np.abs(errs), 90):.2f}박, 최대 {np.abs(errs).max():.2f}박")
    for t, m, b, em, err in rows[:: max(1, len(rows) // 12)]:
        print(f"  {t / 1000:6.2f}s  정답 {m}마디 {b + 1}박  → 추정 {em}마디  ({err:+.2f}박)")


if __name__ == "__main__":
    sys.exit(main())
