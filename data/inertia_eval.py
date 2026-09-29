"""P10 — '관성 항법' 위치 거르개 실험 (사용자 제안 2026-09-29).

정렬기(OnlineAligner = Python oltw)의 칸별 추정 z 를 그대로 쓰지 않고:
  - 예측 x̂ = x + v (v = 빠르기, 악보 칸 / 녹음 칸)
  - |z − x̂| ≤ 문(gate) 이면 받아들여 x = x̂ + α(z − x̂), v += β(z − x̂)   (α-β 거르개)
  - 문 밖이면 관성으로 x = x̂ (z 무시), 그런데 z 가 [switch] 초 넘게 문 밖에서 **스스로 고른 빠르기로** 이어 가면 그쪽으로 옮김
  - 조용하면(선택) 멈춤 — 이번 자료엔 음량이 없어 생략

비교: 날것(z) vs 거른 것(x) — 기준 경로(arp_ref.npz) 대비 ±0.5 · ±1마디, 쪽 넘김 표(쪽 끝 + 1초 머무름, 반 쪽 넘김).
자료: recordings/fixtures (score_follow_dtw 로 만든 아르페지오네 경로 = 앱 경로와 칸 단위로 같음).
"""
import argparse
import json

import numpy as np

import score_follow_dtw as s

F = "recordings/fixtures/"


def smooth(z, fps, gate_s=1.5, alpha=0.1, beta=0.002, switch_s=3.0, vmin=0.5, vmax=2.0):
    """z: 칸별 날것 추정(악보 칸). 반환: 거른 위치(실수), 옮김 기록"""
    n = len(z)
    gate = gate_s * fps  # 악보 칸 (기준 빠르기에서 초 × fps)
    sw = int(switch_s * fps)
    x = float(z[0])
    v = 1.0
    out = np.zeros(n)
    out[0] = x
    outside = 0
    switches = []
    for i in range(1, n):
        pred = x + v
        r = z[i] - pred
        if abs(r) <= gate:
            x = pred + alpha * r
            v = min(vmax, max(vmin, v + beta * r))
            outside = 0
        else:
            x = pred
            outside += 1
            if outside >= sw:
                # z 가 문 밖에서 sw 칸 동안 스스로 고른 빠르기로 갔나 — 기울기가 빠르기 범위 안, 흩어짐이 문 안
                seg = z[i - sw + 1:i + 1].astype(float)
                k = np.arange(sw)
                slope, icpt = np.polyfit(k, seg, 1)
                resid = np.abs(seg - (slope * k + icpt)).max()
                if vmin <= slope <= vmax and resid <= gate:
                    x = float(z[i])
                    v = float(slope)
                    switches.append(i)
                    outside = 0
        out[i] = x
    return out, switches


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gate", type=float, default=1.5)
    ap.add_argument("--alpha", type=float, default=0.1)
    ap.add_argument("--beta", type=float, default=0.002)
    ap.add_argument("--switch", type=float, default=3.0)
    ap.add_argument("--hold", type=float, default=1.0)
    args = ap.parse_args()

    meta = json.load(open(F + "arp_meta.json"))
    frame_sec = meta["frameSec"]
    fps = 1 / frame_sec
    z = np.fromfile(F + "arp_path.i32", dtype=np.int32)
    ref = np.load(F + "arp_ref.npz")
    notes, ms = s.read_musicxml("recordings/scores/Sonate für Pianoforte und Arpeggione (Full score).musicxml")
    spq = 60 / 77
    L = json.load(open("recordings/scores/Sonate für Pianoforte und Arpeggione (Full score).layout.json"))
    page_of = {m["measureNumber"] - 1: m["pageIndex"] for m in L["measures"]}
    lower = {m["measureNumber"] - 1: m["topPt"] >= m["pageHeightPt"] / 2 for m in L["measures"]}
    first_frame = int(round(ref["t"][0] / frame_sec))  # 경로 0칸 = 원래 녹음의 이 칸 (fixture 만들 때와 같이)
    t = (first_frame + np.arange(len(z))) * frame_sec

    def mpos(cols):
        q = np.asarray(cols, float) * frame_sec / spq
        i = np.clip(np.searchsorted(ms, q, side="right") - 1, 0, len(ms) - 2)
        return i + np.clip((q - ms[i]) / (ms[i + 1] - ms[i]), 0, 1)

    refm = np.interp(t, ref["t"], ref["m"], left=np.nan, right=np.nan)
    ok = ~np.isnan(refm)

    def turns(pos):
        """PageTurnDecider 와 같은 규칙 → [(종류, 쪽, 시각)]"""
        hold = args.hold
        page = page_of[int(pos[0])]
        half_shown = False
        pending, since, hsince = -1, 0.0, -1.0
        last_page = max(page_of.values())
        half_point = lambda p: next((i for i in sorted(page_of) if page_of[i] == p and lower[i]), -1)
        hp = half_point(page)
        ev = []
        for k in range(len(pos)):
            now = t[k]
            idx = int(min(max(pos[k], 0), len(page_of) - 1))
            target = page_of[idx]
            if target != page:
                hsince = -1
                if target != pending:
                    pending, since = target, now
                if now - since >= hold:
                    ev.append(("쪽", target, now))
                    page, half_shown, hp, pending = target, False, half_point(target), -1
                continue
            pending = -1
            if not half_shown and hp >= 0 and page < last_page and idx >= hp:
                if hsince < 0:
                    hsince = now
                if now - hsince >= hold:
                    half_shown = True
                    ev.append(("반", page, now))
            elif not half_shown:
                hsince = -1
        return ev

    first_of, half_of = {}, {}
    for i in sorted(page_of):
        first_of.setdefault(page_of[i], i)
        if lower[i]:
            half_of.setdefault(page_of[i], i)
    reft = lambda m: np.interp(m, ref["m"], ref["t"])

    def report(name, pos):
        d = pos[ok] - refm[ok]
        ev = turns(pos)
        rows = []
        for kind, p, when in ev:
            point = first_of[p] if kind == "쪽" else half_of.get(p)
            if point is None:
                continue
            here = np.interp(when, t, refm)
            rows.append((kind, p, when - reft(point), here - point))
        full = np.array([r[2] for r in rows if r[0] == "쪽"])
        half = np.array([r[2] for r in rows if r[0] == "반"])
        early = [(k, p + 1, round(e, 1), round(h, 2)) for k, p, e, h in rows if h < -0.25]
        print(f"[{name}] ±0.5마디 {np.mean(abs(d) <= .5) * 100:.0f}%, ±1마디 {np.mean(abs(d) <= 1) * 100:.0f}%, 최대 {d.min():+.1f}/{d.max():+.1f} | "
              f"쪽 넘김 {len(full)}번 ±2초 {np.mean(abs(full) <= 2) * 100:.0f}% 최악 {full.min():+.1f}/{full.max():+.1f}초 | "
              f"반 쪽 {len(half)}번 최악 이름 {half.min():+.1f}초 | 넘김 점보다 일찍(>0.25마디): {early}")

    report("날것", mpos(z))
    x, sw = smooth(z, fps, args.gate, args.alpha, args.beta, args.switch)
    report(f"관성 gate {args.gate}s α{args.alpha} β{args.beta} 옮김 {args.switch}s (옮김 {len(sw)}번)", mpos(x))


if __name__ == "__main__":
    main()
