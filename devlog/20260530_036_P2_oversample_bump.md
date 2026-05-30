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
