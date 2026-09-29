"""P08 — 정답(박 기록)이 없는 실제 연주 녹음으로 온라인 추적 시험.

20초 조각 맞춤을 이은 경로를 **기준**으로 두고 온라인(OLTW + 재위치)이 얼마나 따라가는지 잰다.
그림(--plot)에 교차 유사도 · 오프라인 경로 · 온라인 경로를 겹쳐 그린다.

  python data/score_follow_compare.py <녹음.wav> <곡.musicxml> --bpm 73 [--start-s 0] [--plot out.png]

지표: 오프라인 대비 마디 차이(1초마다) — ±0.5 · ±1마디 안 비율, 중앙값, 최대 뒤처짐 · 앞섬,
놓침(|차이| > 1마디) 구간들의 시작 · 길이(= 회복까지 걸린 시간), 처음 놓친 곳.
"""
import argparse
import json

import librosa
import numpy as np

import score_follow_dtw as s


def measure_of(cols, mstarts, sec_per_q):
    q = np.asarray(cols) * s.HOP / s.SR / sec_per_q
    idx = np.searchsorted(mstarts, q, side="right")  # 1부터
    frac = (q - mstarts[np.clip(idx - 1, 0, len(mstarts) - 1)]) / np.maximum(1e-9, np.diff(mstarts)[np.clip(idx - 1, 0, len(mstarts) - 2)])
    return idx + np.clip(frac, 0, 1) - 1  # 연속 마디 번호(1.0 = 1마디 처음)


def page_turns(args, layout, mstarts, sec_per_q, on, off, valid, first_i, n):
    """쪽 넘김 시점 오차 — 쪽마다 마지막 마디가 끝나기 [lead] 박 전(앱 악보 연동 규칙 TURN_LEAD_BEATS = 2)을 넘김 점으로,
    온라인이 처음 그 점을 넘는 시각 vs 기준 경로가 넘는 시각. 일찍 넘기면(−) 그 순간 기준 위치로 **아직 안 친 마디 수**(가려지는 마디)도.
    --hold 초: 온라인 추정이 넘김 점 뒤에 그만큼 머물러야 넘긴다(일찍 넘김 줄이기 실험)"""
    to_q = lambda cols: np.asarray(cols) * s.HOP / s.SR / sec_per_q
    q_on, q_off = to_q(on), to_q(off)
    t = np.arange(n) * s.HOP / s.SR
    page_of = {m["measureNumber"]: m["pageIndex"] for m in layout["measures"]}
    beat_q = 4.0 / 4  # 4분음표 박 (아르페지오네 4/4). 박자가 바뀌는 곡은 마디 박자로 바꿀 것
    rows = []
    hold = int(args.hold * s.SR / s.HOP)
    for page in range(layout["page_count"] - 1):
        last = max(mn for mn, pg in page_of.items() if pg == page)
        end_q = mstarts[last]  # 마지막 마디 끝 (= 다음 마디 시작)
        turn_q = end_q - args.lead * beat_q
        idx = np.arange(first_i, n)
        ref_hit = idx[(q_off[idx] >= turn_q) & valid[idx]]
        if len(ref_hit) == 0 or ref_hit[0] <= first_i + 1:
            continue
        past = q_on[idx] >= turn_q
        if hold > 0:  # hold 칸 연속으로 넘은 첫 끝
            run = np.convolve(past.astype(int), np.ones(hold, dtype=int), mode="full")[: len(past)]
            hit = idx[run >= hold]
        else:
            hit = idx[past]
        if len(hit) == 0:
            continue
        ti, tr = hit[0], ref_hit[0]
        err = t[ti] - t[tr]
        # 넘긴 순간 기준 위치에서 이 쪽에 남은 박(넘김 점까지가 아니라 쪽 끝까지) → 마디 수로
        left_q = end_q - q_off[ti]
        rows.append((page + 1, last, t[tr], err, left_q / 4.0))
    if not rows:
        print("쪽 넘김: 비교할 넘김 없음")
        return
    # 반 쪽 넘김(아래 절반은 이 쪽 그대로, 위 절반에 다음 쪽 윗부분): 넘긴 순간 남은 마디가 아래 절반 시스템 마디 수 안이면 안 가려진다
    lower = {}
    for m in layout["measures"]:
        if m["topPt"] >= m["pageHeightPt"] / 2 - 1:
            lower[m["pageIndex"]] = lower.get(m["pageIndex"], 0) + 1
    half_hidden = sum(1 for pg, last, _, _, lm in rows if lm > lower.get(pg - 1, 0))
    e = np.array([r[3] for r in rows])
    left = np.array([r[4] for r in rows])
    early = e < -1.0
    hidden = left > 1.0  # 넘긴 순간 이 쪽에 한 마디 넘게 남음 = 치고 있는 마디가 가려짐
    print(f"\n쪽 넘김 {len(rows)}번 (마지막 마디 끝 {args.lead:g}박 전, 머무름 {args.hold:g}초): 오차(온라인 − 기준) 중앙값 {np.median(e):+.1f}초, "
          f"|오차| ≤ 2초 {np.mean(np.abs(e) <= 2) * 100:.0f}%, ≤ 5초 {np.mean(np.abs(e) <= 5) * 100:.0f}%, 최악 이름 {e.min():+.1f}초 · 늦음 {e.max():+.1f}초")
    print(f"  1초 넘게 일찍 {early.sum()}번, 넘긴 순간 이 쪽에 1마디 넘게 남음(가려짐) {hidden.sum()}번, 반 쪽 넘김이면 가려짐 {half_hidden}번"
          f", 5초 넘게 늦음 {int(np.sum(e > 5))}번")
    if args.quiet:
        return
    for pg, last, tref, err, lm in rows:
        flag = " ← 가려짐" if lm > 1.0 else (" ← 늦음" if err > 5 else "")
        print(f"  쪽 {pg:2d}→{pg + 1:2d} (마지막 {last:3d}마디, 기준 {tref:5.0f}초): {err:+6.1f}초, 넘긴 순간 남은 {lm:+5.2f}마디{flag}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("musicxml")
    ap.add_argument("--bpm", type=float, required=True, help="기준 빠르기(4분음표)")
    ap.add_argument("--start-s", type=float, default=None, help="연주 시작 시각 — 없으면 오프라인 경로에서")
    ap.add_argument("--plot")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--layout", help="앱 · 서버 분석 .layout.json — 쪽 넘김 시점 오차")
    ap.add_argument("--lead", type=float, default=2.0, help="넘김 점: 쪽 마지막 마디 끝 몇 박 전 (앱 TURN_LEAD_BEATS)")
    ap.add_argument("--quiet", action="store_true", help="쪽 넘김 표를 줄마다 찍지 않는다")
    ap.add_argument("--hold", type=float, default=0.0, help="온라인 추정이 넘김 점 뒤에 이 초만큼 머물러야 넘긴다")
    ap.add_argument("--dpi", type=int, default=70)
    ap.add_argument("--reloc-every", type=float, default=1.0)
    ap.add_argument("--reloc-window", type=float, default=30.0)
    ap.add_argument("--reloc-range", type=float, default=40.0)
    ap.add_argument("--reloc-tail", type=float, default=5.0)
    ap.add_argument("--struct", type=float, default=0.5)
    args = ap.parse_args()

    sec_per_q = 60.0 / args.bpm
    notes, mstarts = s.read_musicxml(args.musicxml)
    Y = s.normalize(s.score_chroma(notes, mstarts[-1], sec_per_q))
    y, _ = librosa.load(args.wav, sr=s.SR, mono=True)
    X = s.normalize(librosa.feature.chroma_cqt(y=y, sr=s.SR, hop_length=s.HOP))
    C = 1 - X.T @ Y
    n = C.shape[0]
    print(f"녹음 {n * s.HOP / s.SR:.0f}s, 악보 {len(mstarts) - 1}마디 ({mstarts[-1] * sec_per_q:.0f}s @ {args.bpm:g})")

    # 기준 경로: 전체 DTW 는 실제 녹음에서 기준 빠르기에 끌려갔다(빠르기를 바꾸면 경로가 바뀜) — 대신
    # 20초 조각을 5초마다 곡 전체에서 찾고(부분 DTW, 0.25초 칸), 후보(최선의 +0.01 안 국소 최소 = 풀어 쓴 반복의 쌍둥이 포함) 중
    # 앞으로만 가며 빠르기가 고른 열을 동적 계획법으로 고른다 → 사이는 선형 보간
    k = 11
    hs = k * s.HOP / s.SR
    def pool(A):
        m = A.shape[1] // k
        return s.normalize(A[:, : m * k].reshape(12, m, k).mean(2))
    A, B = pool(X), pool(Y)
    snip, every = int(20 / hs), int(5 / hs)
    anchors = []  # (녹음 끝 칸, [(악보 칸, 비용)])
    for a in range(0, A.shape[1] - snip, every):
        Cq = 1 - A[:, a:a + snip].T @ B
        last = librosa.sequence.dtw(C=Cq, subseq=True, backtrack=False, step_sizes_sigma=s.SLOPE)[-1] / snip
        best = last.min()
        cands = [(jj, last[jj]) for jj in range(1, len(last) - 1)
                 if last[jj] <= last[jj - 1] and last[jj] <= last[jj + 1] and last[jj] <= best + 0.01]
        anchors.append((a + snip - 1, cands))
    # DP (비터비): 상태 = (조각, 후보). 이전 1 ~ 4 조각의 후보에서 이어질 수 있다(맞는 후보가 없는 조각은 건너뜀, 건너뛸 때마다 벌점).
    # 이어짐 = 사이 진행이 0.3 ~ 2.5배 속도, 비용 = 조각 비용 + 빠르기 변화 벌점. 새로 시작은 처음 두 조각에서만
    G, SKIP = 4, 0.05
    best = [np.array([c for _, c in an[1]]) for an in anchors]  # 누적 비용 (덮어씀)
    ptr = [[None] * len(an[1]) for an in anchors]
    for t in range(1, len(anchors)):
        for q, (jj, c) in enumerate(anchors[t][1]):
            cand = (np.inf, None)
            for g in range(1, min(G, t) + 1):
                pos = np.array([p_ for p_, _ in anchors[t - g][1]])
                steps = (jj - pos) / (every * g)
                ok = (steps >= 0.3) & (steps <= 2.5)
                v = np.where(ok, best[t - g] + 0.02 * np.abs(steps - 1.0) + SKIP * (g - 1), np.inf)
                b = int(np.argmin(v))
                if v[b] < cand[0]:
                    cand = (v[b], (t - g, b))
            if cand[1] is None:
                best[t][q] = (c if t < 2 else 10.0 + c)
            else:
                best[t][q] = cand[0] + c
                ptr[t][q] = cand[1]
    # 끝: 마지막 G 조각 중 가장 좋은 곳에서 거슬러
    t, q = max(((tt, int(np.argmin(best[tt]))) for tt in range(len(anchors) - G, len(anchors))),
               key=lambda x: -x[0] + 0 * best[x[0]][x[1]])
    picked = {}
    while t is not None:
        picked[t] = anchors[t][1][q][0]
        nxt = ptr[t][q]
        t, q = nxt if nxt is not None else (None, None)
    keep = sorted(picked)
    anchors = [anchors[t] for t in keep]
    chosen = [picked[t] for t in keep]
    rec_cells = np.array([an[0] for an in anchors])
    ref_cells = np.array(chosen, dtype=float)
    if args.debug:
        print('기준 조각:', ' '.join(f'{rc * hs:.0f}s→m{int(measure_of([c * k], mstarts, sec_per_q)[0])}' for rc, c in zip(rec_cells[::6], ref_cells[::6])))
    offf = np.interp(np.arange(n) / k, rec_cells, ref_cells, left=np.nan, right=np.nan) * k
    valid = ~np.isnan(offf)
    off = np.minimum(np.where(valid, np.round(offf), 0).astype(int), C.shape[1] - 1)
    # 시작: 기준 경로가 정의된 첫 칸 (첫 조각 끝 = 20초). 앱은 시작 마디를 알므로 거기서 넘겨준다
    first_i = int(args.start_s * s.SR / s.HOP) if args.start_s is not None else int(np.argmax(valid))
    off_m = measure_of(np.maximum(off, 0), mstarts, sec_per_q)
    print(f"오프라인: 시작 {first_i * s.HOP / s.SR:.1f}s = {off_m[first_i]:.1f}마디 → 끝 {off_m[-1]:.1f}마디")

    jumps = []
    reloc = (args.reloc_every, args.reloc_window, args.reloc_range, 1.15, args.struct)
    on = s.oltw(C, first_i, int(max(off[first_i], 0)), reloc=reloc, log=jumps, args_tail_s=args.reloc_tail)
    on_m = measure_of(on, mstarts, sec_per_q)

    step = int(s.SR / s.HOP)  # 1초마다
    idx = np.arange(first_i, n, step)
    idx = idx[valid[idx]]
    d = on_m[idx] - off_m[idx]
    t = idx * s.HOP / s.SR
    print(f"온라인 − 오프라인 (마디, 1초마다 {len(d)}점): 중앙값 {np.median(d):+.2f}, |차이| ≤ 0.5마디 {np.mean(np.abs(d) <= 0.5) * 100:.0f}%, "
          f"≤ 1마디 {np.mean(np.abs(d) <= 1) * 100:.0f}%, 최대 뒤처짐 {d.min():+.1f}, 최대 앞섬 {d.max():+.1f}")
    lost = np.abs(d) > 1
    spans, k = [], 0
    while k < len(lost):
        if lost[k]:
            e = k
            while e < len(lost) and lost[e]:
                e += 1
            spans.append((t[k], t[e - 1] - t[k] + 1, off_m[idx[k]], d[k:e].min(), d[k:e].max()))
            k = e
        else:
            k += 1
    print(f"놓침(>1마디) {len(spans)}번, 합 {sum(sp[1] for sp in spans):.0f}s")
    for st, du, m, lo, hi in spans[:15]:
        print(f"  {st:6.0f}s ({m:5.1f}마디 부근) {du:4.0f}s 동안, 차이 {lo:+.1f} ~ {hi:+.1f}마디")
    print(f"재위치 {len(jumps)}번")

    if args.layout:
        page_turns(args, json.load(open(args.layout, encoding="utf-8")), mstarts, sec_per_q, on, off, valid, first_i, n)

    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        k = 11
        def pool(A):
            m = A.shape[1] // k
            return s.normalize(A[:, : m * k].reshape(12, m, k).mean(2))
        sim = pool(X).T @ pool(Y)
        sim = sim - np.median(sim, axis=1, keepdims=True)
        enh = librosa.segment.path_enhance(sim, n=41, window="hann", n_filters=7, max_ratio=1.6, min_ratio=0.6)
        fig, ax = plt.subplots(1, 2, figsize=(15, 10), gridspec_kw={"width_ratios": [4, 1]})
        tsec = np.arange(n) * s.HOP / s.SR
        ax[0].imshow(np.clip(enh, 0, None), aspect="auto", origin="lower", cmap="magma",
                     extent=[1, len(mstarts), 0, n * s.HOP / s.SR])
        # x 를 마디로 — imshow 는 악보 시간이 선형이라 마디가 고르지 않다. 마디 길이가 같은 곡이면 거의 맞다
        scale = (len(mstarts) - 1) / (mstarts[-1] * sec_per_q)
        ax[0].plot(np.where(valid, off, np.nan) * s.HOP / s.SR * scale + 1, tsec, "c-", lw=0.8, label="offline")
        ax[0].plot(on * s.HOP / s.SR * scale + 1, tsec, "lime", lw=0.8, ls="--", label="online")
        ax[0].set_xlabel("measure"); ax[0].set_ylabel("recording (s)"); ax[0].legend(loc="upper left")
        ax[1].plot(d, t, lw=0.8); ax[1].axvline(0, color="k", lw=0.5)
        ax[1].set_xlim(-10, 10); ax[1].set_ylim(0, n * s.HOP / s.SR); ax[1].set_xlabel("online - offline (measures)")
        plt.tight_layout(); plt.savefig(args.plot, dpi=args.dpi)


if __name__ == "__main__":
    main()
