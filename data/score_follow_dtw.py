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


def stft_chroma(y, sr=SR, hop=None, n_fft=2048, fmin=60.0, fmax=4000.0, weight="power"):
    """앱(P10 `follow/Chroma`)과 같은 계산의 STFT 크로마 — Hann 창 [n_fft], 걸음 [hop], [fmin, fmax] 빈을 12음으로.
    빈의 음높이 p = 69 + 12·log2(f/440), 가장 가까운 반음 n 에 무게 max(0, 1 − 2|p − n|) × (세기² | 세기 | log(1+100·세기))"""
    hop = hop or HOP
    win = np.hanning(n_fft + 1)[:-1]
    n = 1 + max(0, (len(y) - n_fft) // hop)
    frames = np.lib.stride_tricks.as_strided(y, shape=(n, n_fft), strides=(y.strides[0] * hop, y.strides[0]))
    mag = np.abs(np.fft.rfft(frames * win, axis=1))  # (n, n_fft/2+1)
    f = np.fft.rfftfreq(n_fft, 1 / sr)
    sel = (f >= fmin) & (f <= fmax)
    p = 69 + 12 * np.log2(f[sel] / 440.0)
    near = np.round(p)
    w = np.maximum(0, 1 - 2 * np.abs(p - near))
    pc = (near.astype(int) % 12)
    m = mag[:, sel]
    v = m * m if weight == "power" else (m if weight == "mag" else np.log1p(100 * m))
    out = np.zeros((12, n))
    for k in range(12):
        idx = pc == k
        out[k] = (v[:, idx] * w[idx]).sum(1)
    return out


def normalize(c):
    return c / (np.linalg.norm(c, axis=0, keepdims=True) + 1e-9)


def online_dtw(C, start_frame=0, start_col=0, window_s=4.0, every=4, ahead_s=8.0, quiet=None, prior=0.02):
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
            if quiet is not None and quiet[i]:
                est += every  # 소리가 거의 없으면(쉼 · 아주 여린 곳) 기준 빠르기로 그냥 간다
            else:
                last = D[-1] / (i + 1 - a)
                # 빠르기 사전 — 기준 빠르기로 간 곳(est + every)에서 멀수록 조금씩 비싸게
                cand = np.arange(e0, e1)
                last = last[e0:e1] + prior * np.abs(cand - (est - lo + every))
                est = lo + e0 + int(np.argmin(last))
        path[i] = est
    return path


def oltw(C, start_frame=0, start_col=0, band_s=3.0, prior=0.0, tempo_s=4.0, reloc=None, log=None, args_tail_s=5.0):
    """온라인 DTW (Dixon MATCH 식) — 녹음 프레임마다 누적 비용 한 줄을 지금 위치 ±[band_s] 초 띠 안에서만 갱신.
    D(i,j) = min(D(i-1,j) + d, D(i,j-1) + d, D(i-1,j-1) + 2d) — 대각선이 두 배라 경로 기울기(빠르기)에 치우치지 않는다.
    지금 위치 = 띠 안에서 D(i,j) / (i + j) 가 가장 작은 j. 되돌아가지 않는다(앞으로만).
    [prior] > 0 이면 **최근 [tempo_s] 초 경로 기울기로 잰 빠르기**로 예측한 곳에서 멀수록 조금 비싸게 — 똑같이 반복되는
    악절 · 여린 곳에서 멈춰 서지 않게 (기준 빠르기가 아니라 지금 연주 빠르기)

    [reloc] = (every_s, window_s, range_s, gain): **주기적 재위치** — every_s 초마다 최근 window_s 초 녹음을 지금 위치
    ±range_s 초 악보에 부분 DTW 로 다시 맞춰, 그 경로의 평균 거리가 지금 경로(같은 녹음 구간의 path)보다 gain 배 이상 좋고
    끝이 1초 넘게 다르면 거기로 뛰고 OLTW 누적을 그 점에서 다시 시작한다.
    후보가 여럿(똑같은 반복)이면 **시작부터의 평균 빠르기로 예상한 위치**에 가까운 쪽 — 악보 구조로 반복의 몇 번째인지 가른다"""
    n, m = C.shape
    band = int(band_s * SR / HOP)
    path = np.zeros(n, dtype=int)
    prev = np.full(m, np.inf)
    prev[start_col] = 2 * C[start_frame, start_col]
    est = start_col
    path[start_frame] = est
    restart = start_frame  # OLTW 누적을 다시 시작한 녹음 프레임 (정규화 걸음 수의 기준)
    restart_col = start_col
    if reloc:
        r_every, r_win, r_range, r_gain, r_struct = (int(x * SR / HOP) if k < 3 else x for k, x in enumerate(reloc))
    for i in range(start_frame + 1, n):
        if reloc and i - start_frame >= r_win and (i - start_frame) % r_every == 0:
            a = i - r_win
            r_tail = min(r_win, int(args_tail_s * SR / HOP))
            lo_r, hi_r = max(0, path[i - 1] - r_range), min(m, path[i - 1] + r_range)
            D, steps = librosa.sequence.dtw(C=C[a:i, lo_r:hi_r], subseq=True, backtrack=False, return_steps=True)
            last = D[-1]
            # 후보 끝: 가장 좋은 끝의 1.1배 안에 드는 봉우리(국소 최소)들 — 똑같이 반복되는 악절이면 여러 개
            cands = [k for k in range(1, len(last) - 1)
                     if last[k] <= last[k - 1] and last[k] <= last[k + 1] and last[k] <= last.min() * 1.1]
            # 악보 구조: 시작부터 지금까지의 평균 빠르기로 "지금쯤 여기" — 반복 두 번째인지 첫 번째인지를 가른다
            tempo = (path[i - 1] - start_col) / max(1, i - 1 - start_frame) if i - start_frame > 5 * r_every else 1.0
            expected = start_col + (i - 1 - start_frame) * min(2.0, max(0.5, tempo))

            def score(col, cost):
                return cost * (1 + r_struct * abs(col - expected) / r_range)

            best = None
            for k in cands:
                wp = librosa.sequence.dtw_backtracking(steps, subseq=True, start=k)
                tail = wp[:, 0] >= r_win - r_tail  # 비교는 끝 몇 초만 — 긴 창은 정렬(반복 구별)에, 판정은 최근에
                cost = np.mean(C[a + wp[tail, 0], lo_r + wp[tail, 1]])
                sc = score(lo_r + k, cost)
                if best is None or sc < best[0]:
                    best = (sc, lo_r + k, cost)
            cur_cost = np.mean(C[np.arange(i - r_tail, i), path[i - r_tail:i]])
            if best and best[0] * r_gain < score(path[i - 1], cur_cost) and abs(best[1] - path[i - 1]) > SR / HOP:
                if log is not None:
                    log.append((i, path[i - 1], best[1], cur_cost, best[2]))
                est = best[1]
                prev = np.full(m, np.inf)
                prev[est] = 2 * C[i - 1, est]
                restart, restart_col = i - 1, est
        lo, hi = max(0, est - band), min(m, est + band)
        d = C[i, lo:hi]
        cur = np.full(m, np.inf)
        a = prev[lo:hi] + d  # 녹음만 진행
        diag = np.full(hi - lo, np.inf)
        diag[1:] = prev[lo:hi - 1] + 2 * d[1:]
        if lo > 0:
            diag[0] = prev[lo - 1] + 2 * d[0]
        row = np.minimum(a, diag)
        for k in range(1, hi - lo):  # 악보만 진행 (같은 녹음 프레임 안)
            v = row[k - 1] + d[k]
            if v < row[k]:
                row[k] = v
        cur[lo:hi] = row
        steps = (i - restart) + (np.arange(lo, hi) - restart_col) + 1
        norm = row / np.maximum(steps, 1)
        if prior > 0:
            back = int(tempo_s * SR / HOP)
            k0 = max(restart, i - back)
            slope = (path[i - 1] - path[k0]) / max(1, i - 1 - k0) if i - 1 - k0 >= back // 2 else 1.0
            slope = min(2.0, max(0.5, slope))
            predicted = path[i - 1] + slope
            norm = norm + prior * np.abs(np.arange(lo, hi) - predicted) / band
        est = max(est, lo + int(np.argmin(norm))) if i - 1 != restart else lo + int(np.argmin(norm))
        path[i] = est
        prev = cur
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("musicxml")
    ap.add_argument("--offset-ms", type=float, default=60.0, help="기록 → 실제 소리 지연 (recording_align.py)")
    ap.add_argument("--online", action="store_true")
    ap.add_argument("--stretch", type=float, default=1.0, help="녹음을 이 배율로 빠르게(>1) · 느리게(<1) 바꿔 시험 — 빠르기를 따라가는지")
    ap.add_argument("--method", choices=["oltw", "window"], default="oltw", help="온라인 방식: oltw(누적 · MATCH 식) | window(최근 4초 창)")
    ap.add_argument("--reloc-every", type=float, default=1.0, help="oltw 재위치 주기 초 (0 = 끔)")
    ap.add_argument("--reloc-window", type=float, default=30.0, help="재위치에 쓰는 최근 녹음 초 — 똑같이 반복되는 악절(몰다우 8마디 ≈ 24초)보다 길어야 한다")
    ap.add_argument("--reloc-range", type=float, default=40.0, help="재위치로 찾는 악보 범위 ± 초")
    ap.add_argument("--reloc-gain", type=float, default=1.15, help="지금 경로보다 이 배 이상 좋아야 뛴다")
    ap.add_argument("--reloc-tail", type=float, default=5.0, help="재위치 판정: 창 끝 몇 초의 평균 거리를 비교")
    ap.add_argument("--struct", type=float, default=0.5, help="재위치: 평균 빠르기로 예상한 위치에서 멀수록 비싸게 (0 = 끔)")
    ap.add_argument("--prior", type=float, default=0.0, help="온라인: 빠르기 사전 무게 (0 = 없음)")
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
    if args.stretch != 1.0:
        y = librosa.effects.time_stretch(y, rate=args.stretch)
        for e in meta["events"]:
            e["t_ms"] = (e["t_ms"] + args.offset_ms) / args.stretch - args.offset_ms
    X = normalize(librosa.feature.chroma_cqt(y=y, sr=SR, hop_length=HOP))
    C = 1 - X.T @ Y  # 코사인 거리 (녹음 프레임 × 악보 프레임)
    rms = librosa.feature.rms(y=y, hop_length=HOP)[0]
    silent = rms < 10 ** (-60 / 20)
    quiet = np.convolve(rms, np.ones(20) / 20, mode="same") < 10 ** (-50 / 20)  # 약 0.5초 평균

    if args.online:
        first = next(e for e in meta["events"] if e["type"] == "beat" and "measure" in e)
        start_frame = int((first["t_ms"] + args.offset_ms) / 1000 * SR / HOP)
        start_col = int(mstarts[first["measure"] - 1] * sec_per_q * SR / HOP)
        reloc = (args.reloc_every, args.reloc_window, args.reloc_range, args.reloc_gain, args.struct) if args.reloc_every > 0 else None
        jumps = []
        path = oltw(C, start_frame, start_col, prior=args.prior, reloc=reloc, log=jumps, args_tail_s=args.reloc_tail) if args.method == "oltw" else online_dtw(C, start_frame, start_col, quiet=quiet, prior=args.prior)
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
    mode = f"온라인 {args.method}" if args.online else "오프라인 부분 DTW"
    near = int(np.sum(np.abs(errs) <= 0.5))
    print(f"[{mode}] 박 {len(errs)}개: 마디 정답 {hits}/{len(errs)} ({100 * hits / max(1, len(errs)):.0f}%), ±0.5박 안 {near}/{len(errs)}, ±1박 안 {int(np.sum(np.abs(errs) <= 1.0))}/{len(errs)}, "
          f"박 오차 중앙값 {np.median(np.abs(errs)):.2f}박, 90% {np.percentile(np.abs(errs), 90):.2f}박, 최대 {np.abs(errs).max():.2f}박")
    if args.online and args.method == "oltw" and jumps:
        to_q = lambda col: col * HOP / SR / sec_per_q
        mnum = lambda col: int(np.searchsorted(mstarts, to_q(col), side="right"))
        print(f"  재위치 {len(jumps)}번: " + ", ".join(f"{i * HOP / SR:.0f}s {mnum(a)}→{mnum(b)}마디" for i, a, b, _, _ in jumps[:12]))
    bad = {}
    for t, m, b, em, err in rows:
        if abs(err) > 0.5:
            bad.setdefault(m, []).append(err)
    if bad:
        print("  ±0.5박 밖 마디: " + ", ".join(f"{m}({len(v)}박, {np.median(v):+.1f})" for m, v in sorted(bad.items())))
    for t, m, b, em, err in rows[:: max(1, len(rows) // 12)]:
        print(f"  {t / 1000:6.2f}s  정답 {m}마디 {b + 1}박  → 추정 {em}마디  ({err:+.2f}박)")


if __name__ == "__main__":
    sys.exit(main())
