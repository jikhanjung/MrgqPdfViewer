# P2 — OVERSAMPLE_FACTOR 2.5 → 4.0

날짜: 2026-05-30
브랜치: `main`
이전 작업: P01 (`devlog/20260528_035_staff_line_rendering_fix_implementation.md`)
대상 버전: v0.1.12 (예정)

---

## 1. 동기

P01 (v0.1.11) 적용 후 실기기 (Z18TV Pro / FHD) 두 페이지 모드 매크로 사진 (`assets/IMG_2609.jpg`) 에서 **오선 5줄 중 맨 아래 한 줄만 회색에 가깝게 흐려지는** 증상 확인. 다른 줄은 진한 검정. PC PDF reader 동일 PDF 동일 FHD 환경 사진 (`assets/IMG_2610.jpg`) 에서는 5줄 모두 균일. P2-B 적용 후 결과는 `assets/IMG_2611.jpg` — 5줄 모두 검정 (두께 미세 차이 잔존).

P01 의 본 목표였던 **줄 두께 불균일** (2단계 fractional scaling 잡음) 은 해소된 것으로 보이나, **특정 줄 dropout** 은 별개 증상이며 P01 의 단일 단계 + 2.5× oversample 로는 부족.

## 2. 원인 (P01 implementation log §재정리)

PDF vector 의 오선 5줄 y 좌표 × `finalScale` 의 fractional part 가 줄마다 다름:

| fractional part | dark coverage (oversample N=2.5) | 시각 결과 |
|---|---|---|
| 0 또는 1 근처 | ~1.0 (한 행에 몰빵) | 진한 검정 줄 |
| 0.5 근처 | (0.5 + 1 + 1 + 0.5) / 3 ≈ **0.83** | 회색에 가까운 흐린 줄 |

5줄 중 0.5 근처에 걸린 줄이 "사라진 것처럼" 보임. **어떤 줄이 걸릴지는 vector y 좌표 × scale 의 결과에 따라 결정** → 페이지/파일/줌 비율마다 다른 줄이 흐려짐 = "불규칙한 증상".

## 3. 수학

oversample factor N 에서 줄이 fractional 0.5 일 때 dark coverage:

```
coverage = (N × 1) / (N + 1)   (양 끝 0.5 + 가운데 1×(N-1) 행 평균)
```

- N=2.5 → 2.5/3.5 = **0.71** … wait, 다시 계산

정확히 다시:
- oversample 공간에서 줄 두께는 device 1px 에 해당 → N 픽셀
- 줄 위치 fractional 0 → 정확히 N 픽셀 행에 몰빵 (각 행 alpha 1)
- 줄 위치 fractional 0.5 → N+1 개 행에 분배 (양 끝 alpha 0.5, 가운데 N-1 개 행 alpha 1)
- downsample factor N 으로 평균:
  - 0 위치: 1 device 행에 N×alpha=N, coverage = N/N = **1.0**
  - 0.5 위치: 2 device 행, 각 행은 N/2 oversample 행 평균. 최악 분배는 device 행 하나에 (0.5 + 0.5×(N/2 -1)) ≈ N/4 + 0.25, 다른 행에 동일. **device 행당 평균 alpha ≈ (N/2) / (N/2) = ...**

이 계산은 분배 방식 (Box / Bilinear / Lanczos) 마다 다르므로 정확값보다는 경향만:

| N | mid-fractional 줄의 device pixel darkness (대략) |
|---|---|
| 2.5 | 0.80~0.85 |
| 3.0 | 0.85~0.90 |
| 4.0 | 0.92~0.96 |
| 6.0 | 0.97~0.99 |

육안 인지 한계는 ~0.05 정도 → **4.0 이면 다른 줄과 구분 어려운 수준**, 2.5 는 회색 인지됨.

## 4. 변경 사항

### 코드
- `app/src/main/java/com/mrgq/pdfviewer/PageCache.kt` line 27:
  ```kotlin
  // const val OVERSAMPLE_FACTOR = 2.5f
  const val OVERSAMPLE_FACTOR = 4.0f
  ```
- 호출부 (PdfViewerActivity 의 `renderPageAtSinglePageTarget`, `renderPageAtTwoPageTarget`, `combineTwoPagesUnified`, `calculateOptimalScale`) 는 모두 `PageCache.OVERSAMPLE_FACTOR` 상수 참조이므로 자동 반영.

### 문서
- `CLAUDE.md`: "2.5× oversample" → "4× oversample (v0.1.12+)", P01 단계 2.5 였음을 부기.
- `HANDOFF.md`: §0 (P2) 신설, §1 (P01) 은 그대로.
- (v0.1.12 빌드 시 `build.gradle.kts` `versionCode` / `versionName` 갱신 별도 필요.)

## 5. 트레이드오프

### 메모리
- oversample 공간 픽셀 수: `(4/2.5)² = 2.56×` 증가
- PageCache 최대 6장 단일 페이지: 예) 1920×1080 FHD 기준
  - P01 (2.5×): 한 장 ≈ 4800×2700×4 byte = **49 MB**
  - P2 (4.0×): 한 장 ≈ 7680×4320×4 byte = **127 MB**
  - 6장 캐시 풀: 295 MB → **762 MB** (이론치)
- 실제로는 두 페이지 모드에서 합친 비트맵 하나만 캐시되고, 단일 페이지 모드도 LruCache 가 진짜 6장 다 들고 있지는 않음. 그래도 OOM 위험은 분명히 올라감.
- **검증 필수**: `adb logcat -s art:V` 에서 GC 빈도 / heap 사이즈 모니터링. OOM 발생 시 `maxCacheSize` 6 → 4 또는 oversample 3.0 으로 후퇴.

### 렌더 시간
- PdfRenderer.render() 는 픽셀 수에 비례 → **2.56× 증가**.
- 단일 페이지 prefetch 가 백그라운드라 사용자 체감은 첫 페이지 진입만 영향. 예) 200ms → 500ms 정도 추정 (실측 필요).
- 두 페이지 모드는 `combineTwoPagesUnified` 가 두 페이지 모두 처리 → 첫 페이지 진입 추가 지연 더 큼 (~1s 가능).

### 4K TV 시나리오
- 향후 Google TV Streamer + 4K 모니터 사용 시 화면 픽셀이 (3840×2160) 4× 되면 oversample 공간은 (15360×8640) → 한 장 비트맵 **530 MB**. 이건 어떤 시스템도 못 버팀.
- 4K 출력 시점에는 oversample factor 를 device pixel density 에 따라 동적으로 줄여야 함 (예: 4K 면 1.5, FHD 면 4.0). 이번 P2 에서는 미구현, 별도 task.

## 6. 검증 계획

### 6-1. 시각 (P2 주 목적)
- 같은 PDF / 같은 페이지를 두 페이지 모드로 띄우고 IMG_2609 와 동일 카메라 거리/각도로 매크로 사진.
- 모든 오선 5줄이 균일한 진한 검정으로 보이는지 비교.
- 여러 페이지에서 (vector y 좌표가 페이지마다 미세하게 다르므로) 검증.

### 6-2. 메모리
- `adb shell dumpsys meminfo com.mrgq.pdfviewer` — Bitmap 항목 모니터링.
- 50+ 페이지 PDF 를 끝까지 넘겨보며 `adb logcat -s art:V` 의 GC 로그가 폭주하는지.
- OOM 발생 여부 (앱 강제 종료).

### 6-3. 성능
- 첫 페이지 진입 시간 (조작 → 화면 렌더 완료). 체감 1s 초과면 문제.
- 페이지 넘김 (캐시 히트) 의 즉시성 유지 확인 (P01 에서 보장됨).

### 6-4. 회귀
- 단일 페이지 모드도 같은 상수 쓰므로 동일하게 확인.
- 크롭 / 중앙 여백 슬라이더 동작.
- 합주 모드 페이지 동기화.
- 페이지 전환 애니메이션 350ms 부드러움.

## 7. 분기

| 시각 결과 | 메모리 | 다음 |
|---|---|---|
| 모든 줄 균일 | 안정 | 커밋, v0.1.12 푸시. P4 (사용자 환경 가이드) 만 남음. |
| 모든 줄 균일 | OOM/GC 폭주 | `maxCacheSize` 6 → 4 로 후퇴, 다시 검증. |
| 여전히 일부 줄 흐림 | - | oversample 4× 도 부족 → **P5 (PDFium/MuPDF)** 본격 검토. |
| 첫 페이지 진입 1s+ | - | prefetch 우선순위 조정 or oversample 3.0 후퇴. |

## 8. 미해결 / 후속

- **4K 출력 시 동적 oversample**: P2 채택 후 별도 task.
- **PageCache.renderScale, settingsChanged dead field**: P01 implementation log §3-2 에 적은 cleanup. P2 는 손 안 댐.
- **`calculateOptimalScale` 시그니처만 남은 함수**: 동일.
- **P5 (PDFium/MuPDF)**: 4.0 도 부족하면. line snap-to-pixel hinting 이 있어 근본 해결책.

---

작성 시점에는 코드 변경만 완료, **실기기 검증 전**. 위 검증 통과 후 build.gradle 의 versionName/versionCode 를 0.1.12 로 올리고 커밋.

---

## 9. P2-A 실패 (2026-05-30)

`OVERSAMPLE_FACTOR = 4.0f` 만 변경한 1차 시도. 두 페이지 모드에서 첫 페이지 진입 시 즉시 크래시:

```
FATAL EXCEPTION: main
java.lang.RuntimeException: Canvas: trying to draw too large(132710400bytes) bitmap.
    at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:266)
    at android.graphics.BaseRecordingCanvas.drawBitmap(BaseRecordingCanvas.java:94)
    at android.graphics.drawable.BitmapDrawable.draw(BitmapDrawable.java:549)
    at android.widget.ImageView.onDraw(ImageView.java:1446)
```

### 원인
- 132,710,400 bytes = **7680×4320×4** (combined two-page bitmap @ screen × oversample 4×)
- Android `RecordingCanvas.MAX_BITMAP_SIZE` = ~100MB. View hierarchy 가 RecordingCanvas 로 draw 명령 기록 → 한계 초과 시 throw.
- OOM 아님 (heap 여유 충분). GPU/CPU 텍스처 한계 이슈.
- P01 (2.5×) 에서는 4800×2700×4 = 51MB 라 한계 아래였음 — P01 의 "oversample bitmap 을 그대로 ImageView 에 넘기고 matrix 로 0.25× downscale" 아키텍처가 4× 에서 깨짐.

### 왜 사전에 못 봤나
- §6 검증 계획에 "OOM" 만 적었고 RecordingCanvas 한계는 누락. 메모리 = 비트맵 size 라는 단순 가정.
- 안드로이드 Canvas 한계는 메모리와 별개의 하드 리미트. 다음에 oversample 류 작업할 때는 두 한계 모두 고려.

---

## 10. P2-B (현재 적용)

### 아키텍처
oversample render 직후 즉시 화면 크기로 다운스케일. 캐시/ImageView 는 화면 크기 비트맵만 본다.

```
PdfRenderer.render(matrix, oversample_bitmap)   ← 132MB transient
  ↓ Bitmap.createScaledBitmap(filter=true)      ← bilinear 4:1
display_bitmap (e.g. 1920×1080)                 ← 8MB cached & displayed
  ↓ oversample_bitmap.recycle()
```

### 변경 파일
- `app/src/main/java/com/mrgq/pdfviewer/PageCache.kt`
  - `renderPageToTargetBitmap`: render → createScaledBitmap → recycle → display bitmap 반환
  - `OVERSAMPLE_FACTOR` 주석에 P2-A 실패와 P2-B 전환 기록
- `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt`
  - `renderPageAtSinglePageTarget`: 동일 패턴
  - `renderPageAtTwoPageTarget`: 동일 패턴
  - `combineTwoPagesUnified`: 입력은 display 크기 half bitmap, 결과는 `screenWidth × pageHeight` (oversample 좌표계 제거)
- `setImageViewMatrix` 양쪽 overload: 코드 변경 없음 (bitmap 크기 기반 동적 scale 계산이 자동으로 ≈1.0 적용)

### 메모리 비교
| 항목 | P01 (2.5×) | P2-A (4×, 실패) | P2-B (4× downscale) |
|---|---|---|---|
| 캐시 비트맵 1장 (single, FHD) | 4800×2700×4 = 51MB | 7680×4320×4 = 132MB | 1920×1080×4 = **8MB** |
| 캐시 풀 (6장) | ~300MB | ~760MB (이론) | **~50MB** |
| Transient 렌더 비트맵 | 51MB (= 캐시) | 132MB (= 캐시, ImageView 로 감) | 132MB (즉시 recycle) |
| ImageView 가 보는 비트맵 | 51MB | 132MB → **CRASH** | 8MB |

### 시각 효과 — bilinear vs box filter
P2-A 이론치 (box filter 가정) 의 dark coverage ~0.94 는 모든 줄이 가능한 모든 fractional 위치에서 균일 darkness 를 보장한다는 의미였음.

`Bitmap.createScaledBitmap(filter=true)` 는 bilinear (skia native). 4:1 downscale 시:
- 각 output 픽셀은 input 의 2×2 (4 sample) 만 가중 평균. box filter (4×4 = 16 sample) 보다 정보 손실.
- 그러나 oversample 4× 에서 staff line 은 ~4 oversample 픽셀 두께로 렌더링되므로, bilinear sample window 안에 line pixel 이 들어옴 → dark coverage 유의미하게 보존.
- 정확한 효과는 vector y fractional × scale 결과에 따라 달라짐. **육안 검증 필수**.

이론적 box filter 대비 약간 떨어질 수 있으나 P01 (2.5×) 대비는 확실히 개선될 것으로 기대.

### 검증 (재정의)
P2-A 의 §6 와 동일하되 추가로:
- **크래시 회귀 없음** (가장 기본). 두 페이지 모드 진입, 페이지 넘김, 합주 페이지 동기화 모두 정상.
- **첫 페이지 진입 속도**: `createScaledBitmap` 추가 비용. logcat 의 `렌더링 완료` ~ `캐시에서 즉시 표시` 사이 timestamp 차이로 측정 가능. 200ms 넘으면 검토.
- **시각**: IMG_2609 와 같은 매크로 사진. 모든 오선 5줄 균일 진한 검정 보이는지.

### 분기
- bilinear 4× 로 시각 충분 → 채택, v0.1.12.
- 시각 부족 → 다음 옵션:
  - (a) Canvas + Paint(filter=true, dither=true) 로 multi-step 2:1 downscale 두 번 (메모리 ↑, 정확도 ↑)
  - (b) RenderScript Toolkit (androidx.renderscript-toolkit) 의 resize → Lanczos 가능
  - (c) PdfRenderer 대신 PDFium / MuPDF
- 렌더 속도 너무 느림 → oversample 3.0 후퇴 (cache 는 여전히 screen-size 라 메모리 안전)

---

## 11. P2-C — Multi-step bilinear downscale (2026-05-30, **시도 후 revert**)

### 동기
P2-B 실기기 검증 (IMG_2611) 에서 staff line dropout 은 해결됐지만 **줄 두께가 위치에 따라 미세하게 다른** 잔존 현상 확인. 2/4번 줄이 1/3/5번 줄보다 살짝 두껍게 보임. PC PDF reader (IMG_2610) 의 균일성과는 여전히 차이.

### 원인 — 단일 bilinear 의 sample window 한계
`Bitmap.createScaledBitmap(filter=true)` = bilinear interpolation. **ratio 와 무관하게 항상 2×2 sample window**. 4:1 다운스케일이면:
- output 한 픽셀이 source 의 4×4 영역에 해당하지만
- bilinear 는 가운데 2×2 만 샘플링, **나머지 12 픽셀 무시**
- source 영역 가장자리 (row 0, 3) 의 오선이 sampling 에 안 잡힘 → 줄 위치에 따라 출력 dark coverage 불균일

### Multi-step 의 원리
**2:1 ratio 에서는 bilinear 가 정확한 box 평균과 동등** (sample window 2×2 = source 영역 전체). 4:1 을 2:1 두 번으로 분해:

```
Step 1 (8×8 → 4×4): 각 intermediate = avg(source 2×2)   ← 정확한 box
Step 2 (4×4 → 2×2): 각 output = avg(intermediate 2×2)
                            = avg(avg(source 2×2), avg(source 2×2), ...)
                            = avg(source 4×4 전체)       ← 진짜 4:1 box filter
```

source 4×4 의 모든 픽셀이 동등하게 출력에 기여 → 가장자리 오선도 손실 없이 반영. Nyquist 관점: N:1 다운샘플은 너비 N kernel low-pass 가 필요한데, 단일 bilinear (kernel 폭 2) 는 4:1 에 부족 → 2:1 두 번 = 효과적 kernel 폭 4.

### 변경
- `PageCache.companion.downscaleMultiStep(src, targetW, targetH)` 신설 — 2:1 단계를 반복하다가 마지막에 잔여 ratio 처리. 임의 ratio 안전.
- `PageCache.renderPageToTargetBitmap`, `PdfViewerActivity.renderPageAtSinglePageTarget` / `renderPageAtTwoPageTarget` — 단일 `createScaledBitmap` → `downscaleMultiStep` 호출로 교체.
- Docstring / 주석 갱신.

### 트레이드오프
- **중간 비트맵**: 두 페이지 모드 half-page 기준 oversample 3056×4320 → mid 1528×2160 (~13MB) → final 764×1080. peak transient = oversample + mid 동시 보유 ~64MB. 안전.
- **CPU 시간**: bilinear 2번. native skia 라 둘이 합쳐 ~150~200ms 예상 (단일 ~100ms 대비). 첫 페이지 진입 시간 약간 증가.
- **시각**: 이론적으로 4:1 box filter 와 동등 → 줄 위치 무관 균일 darkness 기대.

### 검증 (예정)
- 두 페이지 모드 매크로 사진 → IMG_2611 (P2-B) 과 비교. 줄 두께 균일 여부.
- 50+ 페이지 PDF 페이지 전환 안정성. 메모리 OOM 없는지.
- 첫 페이지 진입 시간 측정 (logcat).

### 채택 기준
- 시각 차이가 IMG_2610 (PC) 수준에 가까워지면 → 채택, 버전 그대로 0.1.12 (마이너 빌드) 또는 0.1.13.
- 차이 미미하면 → multi-step rollback (단일 bilinear 가 더 빠르므로). devlog 에 기록.

### 결과 → Revert

실기기 검증 결과 **단일 bilinear (P2-B) 와 시각 차이 거의 없음**. 이론적으로는 multi-step 이 4:1 box filter 와 동등 → 잔존 두께 불균일 해소 기대였으나 실제 결과는 IMG_2611 과 동등 수준.

**이유 추정** — 오선이 oversample 공간에서 ~4 픽셀 두께로 AA 렌더되면 line 중심부 (input row 1, 2) 가 진하고 가장자리 (row 0, 3) 는 soft. 단일 bilinear 의 2×2 sample window 가 정확히 그 진한 중심부를 잡음. "버려진" 12 픽셀 (= AA 가장자리) 의 dark coverage 기여도가 실제로 미미. → bilinear 의 정보 손실이 이론만큼 크지 않음.

**잔존 두께 불균일의 진짜 원인**: bilinear/box 가 아니라 **PdfRenderer 의 vector → raster AA 자체**. line snap-to-pixel hinting 이 없어 vector y fractional 위치에 따라 source bitmap 자체의 line darkness 가 줄마다 다름. Downscale 단계로 회피 불가.

**Revert** — 코드 복잡도/렌더 시간 ↑ vs 시각 효과 ≈ 0. 단일 bilinear 가 같은 결과에 더 빠르고 단순. 헬퍼 제거, 3 render path 의 호출도 `Bitmap.createScaledBitmap` 으로 환원.

**이번 시도의 가치** (devlog 에 남기는 이유): 이 길은 가도 효과 없음을 확인했으므로 미래에 같은 시도 안 함. 두께 미세 차이를 더 줄이려면 P5 (PDFium/MuPDF) 등 line hinting 있는 렌더러로 가야 함.
