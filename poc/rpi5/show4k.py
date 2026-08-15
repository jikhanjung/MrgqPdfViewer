#!/usr/bin/env python3
"""
Phase A PoC — Pi OS Lite(데스크톱 없음)에서 PDF 를 네이티브 해상도로 표시.

목적은 앱을 만드는 게 아니라 **전제 하나를 검증**하는 것이다:
"4K 네이티브가 현재 Android 기기의 1080p 대비 실제로 눈에 띄게 나은가?"

동시에 다음을 측정한다 — 실제로 잡힌 디스플레이 모드, 페이지 렌더 시간, 1:1 blit 여부.

## 설계 원칙: 스케일링 금지

devlog #042 에서 확인했듯 얇은 선의 선명도는 **비트맵이 정수 좌표에 1:1 로 놓일 때만**
유지된다. 그래서 이 PoC 는 절대 화면 스케일링을 하지 않는다:
  - PDF 를 화면 높이에 맞는 배율로 **직접 렌더** (렌더 후 확대/축소 없음)
  - 정수 좌표에 blit
  - 컴포지터 없음 (SDL KMSDRM → DRM/KMS 직행)

## 실행

    sudo apt install -y python3-venv libsdl2-2.0-0
    python3 -m venv ~/mrgq-venv
    ~/mrgq-venv/bin/pip install pymupdf pygame-ce
    SDL_VIDEODRIVER=kmsdrm ~/mrgq-venv/bin/python show4k.py 악보.pdf

데스크톱이 떠 있는 환경에서 시험할 때는 `SDL_VIDEODRIVER` 를 빼면 창으로 뜬다.

## 조작
    → / ↓ / Space : 다음 페이지        ← / ↑ : 이전 페이지
    d             : 두 페이지 모드 토글  g : 그리드(1px 검정선) 오버레이 토글
    i             : 측정값 출력          Esc / q : 종료
"""

import argparse
import os
import statistics
import sys
import time

try:
    import pymupdf
except ImportError:
    sys.exit("pymupdf 가 없습니다:  pip install pymupdf")

try:
    import pygame
except ImportError:
    sys.exit("pygame 이 없습니다:  pip install pygame-ce")


class Viewer:
    def __init__(self, path, two_page=False):
        self.doc = pymupdf.open(path)
        self.path = path
        self.index = 0
        self.two_page = two_page
        self.render_times = []
        self.grid = False

        pygame.init()
        pygame.mouse.set_visible(False)
        # (0, 0) + FULLSCREEN → 현재 디스플레이의 네이티브 모드를 그대로 잡는다.
        self.screen = pygame.display.set_mode((0, 0), pygame.FULLSCREEN)
        self.w, self.h = self.screen.get_size()

        print(f"드라이버      : {pygame.display.get_driver()}")
        print(f"화면 모드     : {self.w}x{self.h}")
        if self.w < 3000:
            print("  ⚠️  4K 가 아닙니다. HDMI 모드/케이블 또는 config.txt 를 확인하세요.")
        print(f"PDF           : {path}  ({self.doc.page_count}페이지)")
        r = self.doc[0].rect
        print(f"페이지 크기   : {r.width:.1f} x {r.height:.1f} pt")
        print()

    # ---- 렌더 ----

    def render_page(self, i):
        """화면 높이에 맞춘 배율로 **직접** 렌더. 렌더 후 스케일링은 하지 않는다."""
        page = self.doc[i]
        r = page.rect
        avail_w = self.w / 2 if self.two_page else self.w
        scale = min(avail_w / r.width, self.h / r.height)

        t0 = time.perf_counter()
        pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), alpha=False)
        elapsed = (time.perf_counter() - t0) * 1000
        self.render_times.append(elapsed)

        surf = pygame.image.frombuffer(pix.samples, (pix.width, pix.height), "RGB")
        return surf, scale, elapsed

    def draw(self):
        self.screen.fill((255, 255, 255))
        pages = [self.index]
        if self.two_page and self.index + 1 < self.doc.page_count:
            pages.append(self.index + 1)

        info = []
        for n, pno in enumerate(pages):
            surf, scale, ms = self.render_page(pno)
            if self.two_page:
                area_x = n * (self.w // 2)
                x = area_x + (self.w // 2 - surf.get_width()) // 2
            else:
                x = (self.w - surf.get_width()) // 2
            y = (self.h - surf.get_height()) // 2
            # 정수 좌표 blit — 소수점이면 재샘플링돼 선이 뭉개진다 (devlog #042)
            self.screen.blit(surf, (int(x), int(y)))
            info.append(f"p{pno + 1} {surf.get_width()}x{surf.get_height()} "
                        f"scale {scale:.3f} {ms:.1f}ms")

        if self.grid:
            self.draw_grid()

        pygame.display.flip()
        print("  ".join(info))

    def draw_grid(self):
        """1픽셀 검정 가로선 5개 — 오선과 나란히 놓고 렌더 품질을 비교하는 기준선."""
        base = self.h // 2 - 40
        for k in range(5):
            y = base + k * 20
            pygame.draw.line(self.screen, (0, 0, 0), (self.w // 4, y), (self.w * 3 // 4, y), 1)

    # ---- 입력 ----

    def step(self, delta):
        stride = 2 if self.two_page else 1
        new = self.index + delta * stride
        if 0 <= new < self.doc.page_count:
            self.index = new
            self.draw()

    def report(self):
        if not self.render_times:
            return
        t = sorted(self.render_times)
        print(f"\n--- 렌더 {len(t)}회 ---")
        print(f"  중앙값 {statistics.median(t):.1f}ms   최소 {t[0]:.1f}   "
              f"최대 {t[-1]:.1f}   평균 {statistics.mean(t):.1f}")
        print(f"  화면 {self.w}x{self.h}, 두 페이지 모드 {self.two_page}\n")

    def run(self):
        self.draw()
        clock = pygame.time.Clock()
        while True:
            for e in pygame.event.get():
                if e.type == pygame.QUIT:
                    return
                if e.type != pygame.KEYDOWN:
                    continue
                k = e.key
                if k in (pygame.K_ESCAPE, pygame.K_q):
                    return
                elif k in (pygame.K_RIGHT, pygame.K_DOWN, pygame.K_SPACE, pygame.K_PAGEDOWN):
                    self.step(1)
                elif k in (pygame.K_LEFT, pygame.K_UP, pygame.K_PAGEUP):
                    self.step(-1)
                elif k == pygame.K_d:
                    self.two_page = not self.two_page
                    self.index -= self.index % 2
                    self.draw()
                elif k == pygame.K_g:
                    self.grid = not self.grid
                    self.draw()
                elif k == pygame.K_i:
                    self.report()
            clock.tick(60)


def main():
    ap = argparse.ArgumentParser(description="Pi OS Lite 4K PDF 표시 PoC")
    ap.add_argument("pdf")
    ap.add_argument("--two-page", action="store_true", help="두 페이지 모드로 시작")
    args = ap.parse_args()

    if not os.path.exists(args.pdf):
        sys.exit(f"파일 없음: {args.pdf}")

    v = Viewer(args.pdf, two_page=args.two_page)
    try:
        v.run()
    finally:
        v.report()
        pygame.quit()


if __name__ == "__main__":
    main()
