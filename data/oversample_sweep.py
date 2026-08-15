#!/usr/bin/env python3
"""
oversample 배율 스윕 — 1× 가 정말 4×→다운스케일보다 나쁜가?

배경: 어도비 리더는 1080p 에서도 오선을 또렷한 검정으로 그리는데 우리 앱은 그렇지 못하다.
staffline_profile.py 에서 PDFium **1× 가 0.957**, 4×→다운이 0.652 로 나온 것과 일치한다.
1× 가 또렷한 이유는 PDFium 이 1px 미만 stroke 를 device pixel 에 스냅하기 때문 —
데스크톱 뷰어가 하는 것과 같은 동작이다.

그런데 우리는 왜 oversample 을 쓰게 됐나?
- P01(v0.1.11): 두 페이지 모드의 **2단계 fractional scaling** 때문에 오선 두께가 들쭉날쭉.
  → 단일 Matrix 렌더로 교체하면서 oversample 2.5× 도입.
- P2(v0.1.12): 특정 오선만 흐려지는 dropout → 2.5× → 4× 로 상향.

핵심: **1× vs 4× 비교는 한 번도 안 해봤다.** P01 이 2단계 스케일링을 없앤 뒤에도 oversample 이
정말 필요한지 확인되지 않았다. 이 스크립트가 그 배율 스윕을 돌려 대비/균일성 트레이드오프를 잰다.
"""

import numpy as np
import pypdfium2 as pdfium
from PIL import Image, ImageDraw

SCREEN_W, SCREEN_H = 1920, 1080
PDF = "Moldau0607.pdf"
PAGE = 0
FACTORS = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0]


def render(scale):
    doc = pdfium.PdfDocument(PDF)
    img = doc[PAGE].render(scale=scale).to_pil().convert("L")
    doc.close()
    return img


def variant(s_fit, factor, disp):
    """앱 파이프라인: fitScale×factor 로 렌더 → 표시 크기로 다운스케일."""
    img = render(s_fit * factor)
    if img.size != disp:
        img = img.resize(disp, Image.BILINEAR)
    return img


def detect_staff_lines(arr, x0, x1):
    """오선만 골라낸다: x 구간 전체에 걸쳐 어두운 가로줄."""
    dark = (arr[:, x0:x1] < 160).mean(axis=1)
    rows = np.where(dark > 0.80)[0]          # 구간의 80% 이상이 어두운 행
    if len(rows) == 0:
        return []
    groups, cur = [], [rows[0]]
    for r in rows[1:]:
        if r - cur[-1] <= 2:
            cur.append(r)
        else:
            groups.append(cur)
            cur = [r]
    groups.append(cur)
    return [int(np.mean(g)) for g in groups]


def measure(arr, lines, x0, x1):
    """오선별: darkness(1=검정), 두께(반치폭 픽셀 수)."""
    prof = arr[:, x0:x1].mean(axis=1)
    dark, thick = [], []
    for y in lines:
        lo, hi = max(0, y - 3), min(len(prof), y + 4)
        seg = prof[lo:hi]
        base, mn = seg.max(), seg.min()
        dark.append(1.0 - mn / 255.0)
        thick.append(int((seg <= (base + mn) / 2).sum()))
    return np.array(dark), np.array(thick)


def main():
    doc = pdfium.PdfDocument(PDF)
    w_pt, h_pt = doc[PAGE].get_size()
    doc.close()
    s_fit = min((SCREEN_W / 2) / w_pt, SCREEN_H / h_pt)
    disp = (round(w_pt * s_fit), round(h_pt * s_fit))
    x0, x1 = int(disp[0] * 0.18), int(disp[0] * 0.82)
    print(f"fitScale {s_fit:.4f} → 표시 {disp[0]}x{disp[1]}, 오선 검출 x 구간 {x0}~{x1}\n")

    imgs = {f: variant(s_fit, f, disp) for f in FACTORS}

    # 오선 위치는 가장 또렷한 1× 에서 잡아 모든 변형에 공통 적용
    ref = np.asarray(imgs[1.0], dtype=np.float32)
    lines = detect_staff_lines(ref, x0, x1)
    print(f"검출된 오선 {len(lines)}개\n")

    print(f"{'oversample':>10} | {'darkness 평균':>13} {'최소':>7} {'표준편차':>8} | {'두께 평균':>9} {'표준편차':>8}")
    print("-" * 72)
    for f in FACTORS:
        d, t = measure(np.asarray(imgs[f], dtype=np.float32), lines, x0, x1)
        tag = "  ← 현재 앱" if f == 4.0 else ("  ← 어도비류(네이티브)" if f == 1.0 else "")
        print(f"{f:>9.1f}× | {d.mean():13.3f} {d.min():7.3f} {d.std():8.3f} | "
              f"{t.mean():9.2f} {t.std():8.3f}{tag}")

    # 감마 2.0 을 얹은 현재 앱과 1× 를 직접 비교
    lut = [round(255 * ((i / 255) ** 2.0)) for i in range(256)]
    g4 = imgs[4.0].point(lut)
    d, t = measure(np.asarray(g4, dtype=np.float32), lines, x0, x1)
    print(f"{'4.0×+γ2.0':>10} | {d.mean():13.3f} {d.min():7.3f} {d.std():8.3f} | "
          f"{t.mean():9.2f} {t.std():8.3f}  ← 현재 앱(감마 적용 후)")

    # 시각 비교
    cy = lines[len(lines) // 2]
    box = (x0, max(0, cy - 26), x0 + 130, min(disp[1], cy + 26))
    show = [("1.0x (native, Adobe-like)", imgs[1.0]),
            ("2.0x -> down", imgs[2.0]),
            ("4.0x -> down (app, no gamma)", imgs[4.0]),
            ("4.0x -> down + gamma 2.0 (app now)", g4)]
    fz = 7
    cw, ch = (box[2] - box[0]) * fz, (box[3] - box[1]) * fz
    canvas = Image.new("L", (cw, (ch + 20) * len(show)), 255)
    dr = ImageDraw.Draw(canvas)
    for i, (lab, im) in enumerate(show):
        y = i * (ch + 20)
        dr.text((4, y + 4), lab, fill=0)
        canvas.paste(im.crop(box).resize((cw, ch), Image.NEAREST), (0, y + 20))
    canvas.save("oversample_sweep_zoom.png")
    print("\n확대 비교: oversample_sweep_zoom.png")


if __name__ == "__main__":
    main()
