# 렌더러 비교(PDFium vs MuPDF) 와 잉크 감마 보정 도입

작성일: 2026-08-15
대상 버전: v0.2.x
상태: ✅ 구현 완료 (⚠️ 실기기 튜닝 필요 — 적정 감마값 미확정)
선행: [`040`](20260815_040_4k_display_investigation.md) (4K 기기 한계 종결) · [`036`](20260530_036_P2_oversample_bump.md) (P2) · [`035`](20260528_035_staff_line_rendering_fix_implementation.md) (P01)
스크립트: `data/renderer_compare.py`, `data/staffline_profile.py`

---

## 0. 요약

#040 에서 4K 가 기기 한계로 막히면서 "1080p 안에서 화질을 올리는" 카드만 남았고, 그 후보였던
**P5(PDF 렌더러 교체)를 데스크톱에서 먼저 판정**했다. 결론 두 가지:

1. **MuPDF 로 교체하면 오히려 더 흐려진다.** P5 는 이 문제의 해법이 아니다 → 로드맵에서 내림.
2. **현재 앱의 4×→다운스케일 파이프라인 자체가 오선을 회색으로 만들고 있었다.**
   원인은 PDFium 의 최소 1px stroke 스냅. 다운스케일 후 **톤 커브(감마) 보정**으로
   균일성을 지킨 채 대비를 1× 수준 가까이 회복할 수 있다 → 구현함.

---

## 1. 왜 데스크톱에서 판정했나

Android `android.graphics.pdf.PdfRenderer` 는 내부적으로 **PDFium**이다. 따라서
AndroidPdfViewer 같은 PDFium 래퍼로 갈아타는 것은 **같은 엔진을 다른 껍데기로 부르는 것**이고,
실질적 대안은 MuPDF 뿐이다. 그런데 MuPDF 는 네이티브 라이브러리 추가 + **AGPL v3**
(배포 시 소스 공개 또는 Artifex 상용 라이선스) 라는 큰 비용을 동반한다.

앱에 붙이기 **전에** 답을 내기 위해, Python 에서 동일 엔진 쌍으로 비교했다:
- `pypdfium2` — PDFium (= Android PdfRenderer 와 같은 엔진)
- `pymupdf` — MuPDF

앱 파이프라인(`PageCache.renderPageToTargetBitmap`)을 그대로 재현:
fitScale 로 화면 맞춤 → ×4 oversample 렌더 → 표시 크기로 다운스케일.
테스트: `Moldau0607.pdf` 1페이지, A4 세로, fitScale 1.283 → **764×1080**, oversample 3055×4320.

## 2. 측정 결과

오선 54개의 세로 프로파일에서 darkness(1.0 = 완전 검정), 그 편차, 두께 편차를 분리 측정.

| 변형 | darkness 평균 | darkness 편차 | 두께 편차 |
|---|---|---|---|
| A · PDFium 1× | **0.957** | 0.160 | 0.91 |
| C · MuPDF 1× | 0.648 | 0.166 | 0.85 |
| **B · PDFium 4×→다운 (현재 앱)** | 0.652 | 0.135 | 0.85 |
| D · MuPDF 4×→다운 (교체 시) | **0.562** | 0.111 | 0.85 |

- **MuPDF 는 더 흐리다** (0.652 → 0.562). 구조 차이도 미미: B vs D 픽셀 RMS 7.9/255(≈3%),
  차이 8 초과 픽셀 7.7%. 확대 비교 이미지에서도 D 가 눈에 띄게 연하다.
- **1× 가 4×→다운보다 훨씬 진하다** (0.957 vs 0.652). 이게 예상 밖이었고 핵심 발견이다.

## 3. 원인 — PDFium 의 최소 1px stroke 스냅

PDFium 은 1픽셀보다 얇은 stroke 를 최소 1픽셀 실선으로 스냅한다(잉크 증가).

- **1×**: 스냅 덕에 오선이 새까맣다. 대신 서브픽셀 위치에 따라 1px/2px 로 갈려 두께가
  불균일해진다 → **P01 이 잡았던 그 증상**.
- **4×→다운**: 4× 해상도에서 스냅된 1px 은 표시 기준 0.25px 이므로, 다운스케일 시 잉크가
  희석되어 회색이 된다 → **P2 가 남긴 증상**.

즉 **oversample 배율을 올릴수록 오선은 균일해지지만 연해진다.** P2 가 편차를
0.160 → 0.135 로 줄인 것은 사실이고, 다만 전체를 "균일하게 연한" 쪽으로 맞춘 셈이다.
실기기에서 "그럭저럭 볼만한" 수준에 머물렀던 것과 일치한다.

## 4. 해법 — 다운스케일 후 감마 보정

| 변형 | darkness 평균 | darkness 편차 | 두께 편차 |
|---|---|---|---|
| B (현재) | 0.652 | 0.135 | 0.85 |
| B + 감마 1.5 | 0.772 | 0.145 | 0.91 |
| B + 감마 2.0 | 0.844 | 0.149 | 0.88 |
| B + 감마 2.5 | **0.885** | 0.150 | 0.91 |

흰 배경(255)과 완전한 검정(0)은 그대로 두고 중간톤만 어둡게 하므로, **균일성을 거의 해치지 않고
대비만 1×(0.957) 수준 가까이 회복**한다. 비용은 256엔트리 LUT 한 번 + 픽셀 루프이며,
엔진 교체와 비교 불가하게 싸다.

### ⚠️ 실기기에서 값을 튜닝해야 한다

측정에 쓴 다운스케일은 PIL(면적 평균에 가까움)이고, Android `createScaledBitmap` 은 mipmap
없는 **2×2 bilinear** 라 언더샘플링이 있다. 그 언더샘플링을 흉내낸 변형(E)은 darkness 0.719 로
B 보다 진했다. **따라서 실기기의 적정 감마는 위 표의 2.0 보다 낮을 수 있다.**

## 5. 구현

### 5-1. `InkGamma.kt` (신규)

- `gamma` (@Volatile, 1.0~3.0, 기본 **1.5**) + 256엔트리 LUT 캐시(값이 바뀔 때만 재생성).
- `apply(bitmap)`: 표시 크기 비트맵에 LUT 적용. `gamma <= 1.0` 이면 원본 그대로 반환(무비용).
- `createScaledBitmap` 이 immutable 을 돌려주는 경로를 방어 — mutable 복사 후 원본 recycle.
- 근거 측정치를 KDoc 에 그대로 박아둠(왜 이 값인지 나중에 다시 추적하지 않도록).

### 5-2. 렌더 경로 3곳에 적용 (다운스케일 직후)

- `PageCache.renderPageToTargetBitmap` (프리렌더/캐시 경로)
- `PdfViewerActivity.renderPageAtSinglePageTarget` (캐시 미스 단일 페이지)
- `PdfViewerActivity.renderPageAtTwoPageTarget` (캐시 미스 두 페이지)

두 페이지 결합(`combineTwoPagesUnified`)은 이미 보정된 비트맵을 배치만 하므로 **중복 적용 없음**.

### 5-3. 설정 UI — PDF 표시 옵션 → "오선 진하기 (감마)"

- OK 길게 누르기 → PDF 표시 옵션에 항목 추가. 슬라이더 1.00~3.00 (0.05 단위),
  빠른 버튼 "보정 없음 / 1.5 / 2.0", **실시간 미리보기**(250ms 디바운스).
- 클리핑과 달리 **파일별이 아닌 전역 설정** — 디스플레이 특성에 따르는 값이라
  SharedPreferences(`ink_gamma`)에 저장. `onCreate` 에서 로드.
- 미리보기·적용·취소 모두 `pageCache?.clear()` 를 동반한다 — **캐시된 비트맵에는 이전 감마가
  구워져 있기 때문**. 이걸 빠뜨리면 이미 캐시된 페이지만 옛 감마로 남는다.

## 6. 다음

- [ ] **실기기에서 감마 튜닝** — 슬라이더로 1.0 / 1.3 / 1.5 / 2.0 비교, 오선이 진하면서
      음표 머리·굵은 마디선이 뭉개지지 않는 값 선택. 결정되면 `InkGamma.DEFAULT` 갱신.
- [ ] 감마 적용 비용 측정 — 764×1080 ≈ 825k 픽셀 루프. 페이지 전환 체감 지연이 생기는지 확인
      (백그라운드 렌더 경로라 문제없을 것으로 예상하나 느린 AP 기기에서 확인 필요).
- [x] **P5(렌더러 교체) 로드맵에서 제외** — MuPDF 는 더 흐리고, PDFium 래퍼는 같은 엔진.

## 7. 재현

```bash
# venv: /home/jikhanjung/venv/MrgqPdfViewer
pip install pymupdf pypdfium2 pillow numpy
cd data
python renderer_compare.py      # 5개 변형 + 지표 + renderer_compare_zoom.png
python staffline_profile.py     # 오선 프로파일 정밀 측정 + 감마 스윕
```
