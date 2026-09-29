"""P10 — 연주 시작 찾기: 첫 소리가 아니라 '악보 첫머리와 맞는 소리'에서 추적을 시작한다 (사용자 보고 2026-09-29: 바로 연주하지 않으면 헤맨다).

1초마다 최근 [win] 초 녹음을 (가) 시작 마디부터 [head] 초의 악보, (나) 곡 곳곳의 같은 길이 창들에 부분 DTW 로 맞춰
비율 = (가) / (나 중앙값). 연주 전 소리는 ≈ 1.0, 첫머리를 치면 0.5 ~ 0.65 (아르페지오네 태블릿 기록).
비율이 [ratio] 아래로 [need] 번 이어지면 시작 — 정렬은 (가) 경로 끝 칸에서.
"""
import numpy as np
import librosa

import score_follow_dtw as s


def detect(R, Y, start_col, fps, win=4.0, head=12.0, ratio=0.75, need=2, samples=10, from_frame=0):
    W, H = int(win * fps), int(head * fps)
    step = int(fps)
    lo = start_col
    # 곡 곳곳 — 시작 부분(첫머리 + 1분)은 빼고 고르게
    far = max(lo + H + int(60 * fps), 0)
    starts = np.linspace(far, Y.shape[1] - H - 1, samples).astype(int) if Y.shape[1] - H - 1 > far else np.array([0])
    hits = 0
    history = []
    for i in range(max(from_frame + W, W), R.shape[1], step):
        q = R[:, i - W:i]
        D = librosa.sequence.dtw(C=1 - q.T @ Y[:, lo:lo + H], subseq=True, backtrack=False, step_sizes_sigma=s.SLOPE)[-1] / W
        c0, j = D.min(), int(np.argmin(D))
        others = [librosa.sequence.dtw(C=1 - q.T @ Y[:, st:st + H], subseq=True, backtrack=False, step_sizes_sigma=s.SLOPE)[-1].min() / W
                  for st in starts]
        r = c0 / np.median(others)
        history.append((i, r))
        hits = hits + 1 if r < ratio else 0
        if hits >= need:
            return i, lo + j, history
    return None, None, history
