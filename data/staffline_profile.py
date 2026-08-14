#!/usr/bin/env python3
"""
오선 프로파일 정밀 분석 — 대비(darkness) vs 두께 균일성(uniformity) 트레이드오프

renderer_compare.py 에서 PDFium 1× 가 4×+다운스케일보다 훨씬 진한 오선을 내는 것이
관측됐다. 가설: PDFium 은 1픽셀 미만 stroke 를 최소 1px 로 스냅한다(잉크 증가).
- 1× : 스냅 덕에 진하지만, 서브픽셀 위치에 따라 1px/2px 로 갈려 두께가 불균일 (= P01 증상)
- 4×→다운 : 물리적으로 정확해 균일하지만 잉크가 희석돼 회색 (= P2 증상)

여기서는 오선별 프로파일을 직접 재서 두 지표를 분리 측정하고, 다운스케일 후
톤 커브(감마) 보정이 균일성을 지키면서 대비를 회복시키는지 확인한다.
"""

import numpy as np
import pymupdf
import pypdfium2 as pdfium
from PIL import Image

SCREEN_W, SCREEN_H = 1920, 1080
PDF = "Moldau0607.pdf"
PAGE = 0


def render_pdfium(scale):
    doc = pdfium.PdfDocument(PDF)
    img = doc[PAGE].render(scale=scale).to_pil().convert("L")
    doc.close()
    return img


def render_mupdf(scale):
    doc = pymupdf.open(PDF)
    pix = doc[PAGE].get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csGRAY)
    img = Image.frombytes("L", (pix.width, pix.height), pix.samples)
    doc.close()
    return img


def gamma_darken(img, gamma):
    """다운스케일로 희석된 잉크를 되살리는 톤 커브. gamma>1 이면 중간톤이 어두워진다."""
    lut = [round(255 * ((i / 255) ** gamma)) for i in range(256)]
    return img.point(lut)


def staff_profiles(arr, x0, x1):
    """오선 구간의 x 범위에서 열평균 세로 프로파일을 만들고, 각 오선의 골(darkest)을 찾는다."""
    prof = arr[:, x0:x1].mean(axis=1)
    # 국소 최소값 = 오선 중심. 충분히 어두운 것만.
    cands = []
    for y in range(2, len(prof) - 2):
        if prof[y] <= prof[y - 1] and prof[y] < prof[y + 1] and prof[y] < 200:
            cands.append(y)
    # 인접 후보 병합
    merged, cur = [], [cands[0]] if cands else []
    for y in cands[1:]:
        if y - cur[-1] <= 2:
            cur.append(y)
        else:
            merged.append(int(np.mean(cur)))
            cur = [y]
    if cur:
        merged.append(int(np.mean(cur)))
    return prof, merged


def analyze(name, img, x0, x1, ref_lines=None):
    arr = np.asarray(img, dtype=np.float32)
    prof, lines = staff_profiles(arr, x0, x1)
    use = ref_lines if ref_lines is not None else lines
    darkness, thickness = [], []
    for y in use:
        lo, hi = max(0, y - 3), min(len(prof), y + 4)
        seg = prof[lo:hi]
        darkness.append(1.0 - seg.min() / 255.0)
        # 반치폭 근사: 배경(흰색) 대비 50% 이하인 픽셀 수
        base = prof[lo:hi].max()
        half = (base + seg.min()) / 2
        thickness.append(int((seg <= half).sum()))
    d = np.array(darkness)
    t = np.array(thickness)
    print(f"{name:26} darkness  평균 {d.mean():.3f}  최소 {d.min():.3f}  "
          f"표준편차 {d.std():.3f}   두께 평균 {t.mean():.2f}  표준편차 {t.std():.2f}")
    return use, d, t


def main():
    doc = pymupdf.open(PDF)
    r = doc[PAGE].rect
    doc.close()
    s_fit = min((SCREEN_W / 2) / r.width, SCREEN_H / r.height)
    disp = (round(r.width * s_fit), round(r.height * s_fit))
    print(f"fitScale {s_fit:.4f} → {disp[0]}x{disp[1]}, oversample 4× → "
          f"{round(r.width*s_fit*4)}x{round(r.height*s_fit*4)}\n")

    a1 = render_pdfium(s_fit).resize(disp, Image.BILINEAR)
    m1 = render_mupdf(s_fit).resize(disp, Image.BILINEAR)
    b4 = render_pdfium(s_fit * 4).resize(disp, Image.BILINEAR)
    d4 = render_mupdf(s_fit * 4).resize(disp, Image.BILINEAR)

    # 오선이 지나가는 x 구간 (좌우 여백 제외)
    x0, x1 = int(disp[0] * 0.15), int(disp[0] * 0.85)

    # 기준 오선 위치는 가장 대비가 좋은 A 에서 잡고, 모든 변형에 동일 적용
    ref_lines, _, _ = analyze("A  PDFium 1×", a1, x0, x1)
    print(f"   (검출된 오선 {len(ref_lines)}개, 이 위치를 모든 변형에 공통 적용)\n")

    analyze("A  PDFium 1×", a1, x0, x1, ref_lines)
    analyze("C  MuPDF 1×", m1, x0, x1, ref_lines)
    analyze("B  PDFium 4×→down (현재 앱)", b4, x0, x1, ref_lines)
    analyze("D  MuPDF 4×→down", d4, x0, x1, ref_lines)
    print()
    for g in (1.5, 2.0, 2.5, 3.0):
        analyze(f"B + 감마 {g}", gamma_darken(b4, g), x0, x1, ref_lines)

    # 시각 비교
    y0 = ref_lines[len(ref_lines) // 2] - 26
    box = (x0, max(0, y0), x0 + 130, min(disp[1], y0 + 52))
    from PIL import ImageDraw
    variants = [
        ("A PDFium 1x", a1),
        ("B PDFium 4x->down (app)", b4),
        ("D MuPDF 4x->down", d4),
        ("B + gamma 2.0", gamma_darken(b4, 2.0)),
        ("B + gamma 2.5", gamma_darken(b4, 2.5)),
    ]
    f = 7
    cw, ch = (box[2] - box[0]) * f, (box[3] - box[1]) * f
    canvas = Image.new("L", (cw, (ch + 20) * len(variants)), 255)
    dr = ImageDraw.Draw(canvas)
    for i, (lab, im) in enumerate(variants):
        y = i * (ch + 20)
        dr.text((4, y + 4), lab, fill=0)
        canvas.paste(im.crop(box).resize((cw, ch), Image.NEAREST), (0, y + 20))
    canvas.save("staffline_profile_zoom.png")
    print("\n확대 비교: staffline_profile_zoom.png")


if __name__ == "__main__":
    main()
