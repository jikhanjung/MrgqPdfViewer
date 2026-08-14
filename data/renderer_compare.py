#!/usr/bin/env python3
"""
PDF 렌더러 화질 비교 — PDFium(= Android PdfRenderer 엔진) vs MuPDF

배경: devlog/20260815_040 에서 4K 계단현상이 기기 한계로 종결되면서, 남은 화질 카드가
"1080p 안에서 렌더러 교체(P5)" 뿐인지 판정할 필요가 생겼다. Android 의
android.graphics.pdf.PdfRenderer 는 내부적으로 PDFium 이므로, PDFium 래퍼로 갈아타는 건
같은 엔진이다. 진짜 대안은 MuPDF 뿐이고, 이 스크립트가 그 차이를 앱과 동일한
파이프라인 위에서 측정한다.

앱 파이프라인 (PageCache.renderPageToTargetBitmap):
    fitScale 로 화면에 맞춤 → ×4 oversample 로 렌더 → 화면 크기로 다운스케일

변형:
    A: PDFium 1×            (oversample 없이 바로 표시 크기)
    B: PDFium 4× → 다운     ← 현재 앱
    C: MuPDF  1×
    D: MuPDF  4× → 다운     ← MuPDF 로 교체 시
    E: PDFium 4× → 2×2 bilinear 근사 다운  (Android createScaledBitmap 의 언더샘플링 재현)

사용: python renderer_compare.py [PDF] [--page N]
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import pymupdf
import pypdfium2 as pdfium
from PIL import Image

SCREEN_W, SCREEN_H = 1920, 1080
OVERSAMPLE = 4.0


def fit_scale(pw, ph, tw, th):
    """앱의 fitScale 과 동일 — 타깃 영역에 페이지를 맞추는 배율."""
    return min(tw / pw, th / ph)


def render_pdfium(path, page_no, scale):
    doc = pdfium.PdfDocument(str(path))
    page = doc[page_no]
    bitmap = page.render(scale=scale)
    img = bitmap.to_pil().convert("L")
    doc.close()
    return img


def render_mupdf(path, page_no, scale):
    doc = pymupdf.open(str(path))
    page = doc[page_no]
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csGRAY)
    img = Image.frombytes("L", (pix.width, pix.height), pix.samples)
    doc.close()
    return img


def downscale_quality(img, size):
    """PIL 의 필터는 축소 비율에 맞춰 support 가 확장되므로 area-평균에 가깝다."""
    return img.resize(size, Image.BILINEAR)


def downscale_naive_bilinear(img, size):
    """Android createScaledBitmap(filter=true) 근사.

    Skia 는 mipmap 없이 2x2 이웃만 보는 bilinear 라, 4× 축소에서는 원본 픽셀 대부분을
    건너뛴다(언더샘플링). NEAREST 로 4점 격자만 뽑은 뒤 2x2 평균으로 근사한다.
    """
    w, h = size
    a = np.asarray(img, dtype=np.float32)
    ys = np.clip((np.arange(h) * img.height / h).astype(int), 0, img.height - 2)
    xs = np.clip((np.arange(w) * img.width / w).astype(int), 0, img.width - 2)
    q = (a[np.ix_(ys, xs)] + a[np.ix_(ys + 1, xs)]
         + a[np.ix_(ys, xs + 1)] + a[np.ix_(ys + 1, xs + 1)]) / 4.0
    return Image.fromarray(q.astype(np.uint8), "L")


def find_staff_rows(arr, thresh=128, min_frac=0.35):
    """가로로 길게 이어지는 어두운 행 = 오선. 연속 행을 묶어 그룹의 중심을 반환."""
    dark_frac = (arr < thresh).mean(axis=1)
    rows = np.where(dark_frac > min_frac)[0]
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
    return [(int(np.mean(g)), len(g)) for g in groups]


def staff_metrics(arr, staff_rows):
    """오선별 darkness = 1 - (행 평균 최소값)/255. 1.0 이면 완전 검정."""
    out = []
    for center, _ in staff_rows:
        lo, hi = max(0, center - 2), min(arr.shape[0], center + 3)
        band = arr[lo:hi]
        row_means = band.mean(axis=1)
        out.append(1.0 - row_means.min() / 255.0)
    return np.array(out)


def sharpness(arr):
    """평균 |Laplacian| — 클수록 경계가 또렷하다."""
    a = arr.astype(np.float32)
    lap = (4 * a[1:-1, 1:-1] - a[:-2, 1:-1] - a[2:, 1:-1] - a[1:-1, :-2] - a[1:-1, 2:])
    return float(np.abs(lap).mean())


def midtone_fraction(arr):
    """회색(중간톤) 픽셀 비율 — 오선 dropout 이 심하면 올라간다."""
    return float(((arr > 60) & (arr < 200)).mean())


def zoom_crop(img, box, factor=6):
    return img.crop(box).resize(
        ((box[2] - box[0]) * factor, (box[3] - box[1]) * factor), Image.NEAREST
    )


def montage(images, labels, out_path, pad=8):
    from PIL import ImageDraw

    w = max(i.width for i in images)
    h = images[0].height
    label_h = 22
    canvas = Image.new("L", (w, (h + label_h + pad) * len(images)), 255)
    draw = ImageDraw.Draw(canvas)
    for n, (im, lab) in enumerate(zip(images, labels)):
        y = n * (h + label_h + pad)
        draw.text((4, y + 4), lab, fill=0)
        canvas.paste(im, (0, y + label_h))
    canvas.save(out_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf", nargs="?", default="Moldau0607.pdf")
    ap.add_argument("--page", type=int, default=0)
    ap.add_argument("--out", default="renderer_compare")
    args = ap.parse_args()

    pdf = Path(args.pdf)
    if not pdf.exists():
        sys.exit(f"PDF not found: {pdf}")

    doc = pymupdf.open(str(pdf))
    rect = doc[args.page].rect
    doc.close()
    pw, ph = rect.width, rect.height

    # 앱의 두 페이지 모드: 화면 절반 폭에 페이지 하나. 단일 모드도 세로 A4 면 높이 제한이라
    # 실효 배율이 같다. 여기서는 화면 높이에 맞추는 경우로 잡는다.
    s_fit = fit_scale(pw, ph, SCREEN_W / 2, SCREEN_H)
    disp = (round(pw * s_fit), round(ph * s_fit))
    s_over = s_fit * OVERSAMPLE

    print(f"PDF        : {pdf.name}  page {args.page}  ({pw:.1f} x {ph:.1f} pt)")
    print(f"fitScale   : {s_fit:.4f}  → 표시 크기 {disp[0]} x {disp[1]}")
    print(f"oversample : {OVERSAMPLE}× → 렌더 크기 {round(pw*s_over)} x {round(ph*s_over)}")
    print()

    variants = {}
    variants["A_pdfium_1x"] = render_pdfium(pdf, args.page, s_fit).resize(disp, Image.BILINEAR)
    variants["C_mupdf_1x"] = render_mupdf(pdf, args.page, s_fit).resize(disp, Image.BILINEAR)

    big_pdfium = render_pdfium(pdf, args.page, s_over)
    big_mupdf = render_mupdf(pdf, args.page, s_over)
    variants["B_pdfium_4x_down"] = downscale_quality(big_pdfium, disp)
    variants["D_mupdf_4x_down"] = downscale_quality(big_mupdf, disp)
    variants["E_pdfium_4x_naive"] = downscale_naive_bilinear(big_pdfium, disp)

    ref = np.asarray(variants["B_pdfium_4x_down"])
    staff = find_staff_rows(ref)
    print(f"검출된 오선 후보 행: {len(staff)}개\n")

    print(f"{'variant':22} {'staff dark':>11} {'min':>7} {'sharp':>8} {'midtone%':>9}")
    print("-" * 62)
    results = {}
    for name, img in variants.items():
        arr = np.asarray(img)
        d = staff_metrics(arr, staff)
        results[name] = dict(
            dark=float(d.mean()) if len(d) else float("nan"),
            dmin=float(d.min()) if len(d) else float("nan"),
            sharp=sharpness(arr),
            mid=midtone_fraction(arr) * 100,
        )
        r = results[name]
        print(f"{name:22} {r['dark']:11.4f} {r['dmin']:7.4f} {r['sharp']:8.2f} {r['mid']:9.3f}")

    print()
    b = np.asarray(variants["B_pdfium_4x_down"], dtype=np.float32)
    d = np.asarray(variants["D_mupdf_4x_down"], dtype=np.float32)
    diff = np.abs(b - d)
    print(f"B(현재 앱) vs D(MuPDF 교체) 픽셀 차이:")
    print(f"  RMS   {np.sqrt((diff**2).mean()):.3f} / 255")
    print(f"  평균  {diff.mean():.3f}  최대 {diff.max():.0f}")
    print(f"  |차이|>8 픽셀 비율  {(diff > 8).mean()*100:.3f}%")

    # 시각 비교 — 오선이 가장 조밀한 구간을 잡는다
    if staff:
        centers = [c for c, _ in staff]
        best_i = max(
            range(len(centers)),
            key=lambda i: sum(1 for c in centers if abs(c - centers[i]) < 40),
        )
        cy = centers[best_i]
        box = (disp[0] // 5, max(0, cy - 24), disp[0] // 5 + 150, min(disp[1], cy + 26))
        crops = [zoom_crop(variants[k], box) for k in variants]
        montage(crops, list(variants.keys()), f"{args.out}_zoom.png")
        print(f"\n확대 비교 이미지: {args.out}_zoom.png  (crop {box}, 6× 확대)")

    for name, img in variants.items():
        img.save(f"{args.out}_{name}.png")
    print(f"전체 페이지 렌더: {args.out}_*.png")


if __name__ == "__main__":
    main()
