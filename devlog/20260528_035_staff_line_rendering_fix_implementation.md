# 오선 렌더링 개선 구현 — P1 + 크롭 + 0.9× 마진 (실제로는 dead code 정리)

작성일: 2026-05-28
대상 버전: v0.1.10 → 다음 빌드
상태: ✅ 구현 완료, 실기기 검증 대기
관련 계획: [20260528_P01_staff_line_rendering_fix_plan.md](20260528_P01_staff_line_rendering_fix_plan.md)
커밋: `0f0c567`

---

## 1. 계획 대비 실제 발견 사항

계획 문서가 분석 대상으로 지목한 `PdfPageManager.kt` 는 **완전한 dead code** 였음. `grep -rn "PdfPageManager(" app/src` 결과가 클래스 정의 본인 한 줄뿐.

v0.1.8 매니저 패턴 리팩토링이 클래스 작성까지만 되고 `PdfViewerActivity` 에 통합되지 않은 채 남아있었음. 실제 라이브 렌더 파이프라인은 전부 `PdfViewerActivity` + `PageCache` 에 있었다.

### 1-1. 영향

- 계획의 §3-2 (a) 가 지목한 2단계 스케일링 안티패턴은 **다른 파일에 같은 형태로 실재** 했음. 결론은 그대로 유효.
- §3-2 (b) 의 0.9× 마진은 dead code 에만 있었음. 라이브 코드는 단일 페이지 모드에서 `(scale × 2.0).coerceIn(2.0, 4.0)` 으로 2~4× oversampling 을 정상 수행 중이었다. **CLAUDE.md 의 "2-4x 스케일링" 기록은 단일 페이지 모드에 한해 사실.**
- 그러나 두 페이지 모드는 oversampling 자체는 있되 (`combineTwoPagesUnified` 의 `finalScale = 2.5f`), 그 안에서 fractional scaling 이 두 번 발생하는 구조였음. P1 의 정성적 결론(두 페이지 모드만 안티패턴, 단일 페이지는 양호)이 정확했다.

### 1-2. 작업 범위 조정

| 원래 계획 | 실제 작업 |
|---|---|
| `PdfPageManager.kt` 의 두 페이지 함수 손대기 | dead code 이므로 손대지 않음. 별도 cleanup 으로 분리 예정. |
| 0.9× 마진 제거 (P3) | 라이브 코드에 없으므로 불필요. |
| `setImageViewMatrix` 1:1 변경 | 단일 페이지 모드의 oversample → ImageView 다운스케일이 anti-alias 효과로 기능 중. 변경 불필요. |
| **`PdfViewerActivity` 의 `renderTwoPagesUnified` + `combineTwoPagesUnified` 단일 단계화** | 실제 작업의 핵심 |
| **`PageCache.createBitmapForPage` 의 두 페이지 분기 수정** | 두 번째 핵심 |
| 크롭 (위/아래 클리핑) 을 vector 변환에 베이크 | 함께 수행 |

---

## 2. 라이브 코드의 안티패턴 (수정 전)

### 2-1. 두 페이지 모드 — 캐시 미스 경로 (`renderTwoPagesUnified`)

```kotlin
// PDF 네이티브 크기 비트맵 생성
leftBitmap = Bitmap.createBitmap(leftPage.width, leftPage.height, ARGB_8888)
// Matrix=null → PdfRenderer 가 비트맵 크기에 맞춰 자동 fit (= 네이티브 → 네이티브, 1:1)
leftPage.render(leftBitmap, null, null, RENDER_MODE_FOR_DISPLAY)
// 같은 방식으로 rightBitmap

// 그 다음 combineTwoPagesUnified 로 전달
```

### 2-2. 두 페이지 모드 — 캐시 히트 경로 (`PageCache.createBitmapForPage`)

```kotlin
if (isTwoPageMode) {
    // 두 페이지 모드: 원본 PDF 비율 유지하면서 스케일 적용
    targetWidth = (pdfWidth * renderScale).toInt()
    targetHeight = (pdfHeight * renderScale).toInt()
}
// page.render(bitmap, null, null, mode) → 비트맵 크기에 맞춰 PdfRenderer 자동 스케일
```

여기까지는 한 번의 PdfRenderer 단계 스케일. 문제는 그 다음.

### 2-3. `combineTwoPagesUnified` (양쪽 경로 모두 거치는 합성)

```kotlin
val finalScale = 2.5f
val finalBitmap = createBitmap(screenW × 2.5, screenH × 2.5)  // oversample 목표

val availableHalfWidth = (halfScreenW - paddingPx/2) × finalScale
val pageScale = min(availableHalfWidth / leftPageWidth, ...)  // fractional 비율 (~1.25)

// Canvas Matrix scale 로 입력 비트맵을 oversample 목표에 다시 그림
val leftMatrix = Matrix().apply { postScale(pageScale, pageScale); postTranslate(...) }
finalCanvas.drawBitmap(leftBitmap, leftMatrix, null)   // ← 2단계 fractional scaling
```

흐름 정리:
1. PDF (612×792) → 비트맵 (PDF 네이티브 또는 pdfW × renderScale) — 1단계
2. Canvas Matrix postScale (~1.25 같은 fractional 비율) 로 oversample finalBitmap 에 그림 — **2단계 fractional scaling, 오선 두께 깨짐 발생 위치**
3. ImageView Matrix 가 finalBitmap (screen × 2.5) 을 screen 크기로 다운스케일 — 3단계, oversampling 다운스케일이라 양호

오선이 망가지는 곳은 정확히 2단계.

### 2-4. 크롭 처리 — 분리된 후처리

`applyDisplaySettings` (Activity, PageCache 양쪽) 가 결과 비트맵을 `srcRect/dstRect` 로 위/아래 잘라낸다. 스케일링 없는 crop 이라 품질 손실은 없으나, 결과 비트맵 높이가 짧아져서 ImageView 가 다시 fit (fractional scaling 추가).

---

## 3. 수정 후 구조

### 3-1. 단일 렌더 헬퍼 (PdfViewerActivity)

```kotlin
private fun renderPageAtSinglePageTarget(page: PdfRenderer.Page): Bitmap {
    val topClip = currentTopClipping.coerceIn(0f, 0.45f)
    val bottomClip = currentBottomClipping.coerceIn(0f, 0.45f)
    val visibleFraction = (1f - topClip - bottomClip).coerceAtLeast(0.1f)
    val visiblePdfH = page.height * visibleFraction

    val fitScale = minOf(screenWidth / page.width.toFloat(), screenHeight / visiblePdfH)
    val finalScale = fitScale * PageCache.OVERSAMPLE_FACTOR   // 2.5

    val bitmap = Bitmap.createBitmap(
        (page.width * finalScale).toInt(),
        (visiblePdfH * finalScale).toInt(),
        ARGB_8888
    )
    val matrix = Matrix().apply {
        setScale(finalScale, finalScale)
        // PDF y=topClip*pageH 가 비트맵 y=0 에 오도록 → 크롭을 vector 변환에 흡수
        postTranslate(0f, -page.height * topClip * finalScale)
    }
    page.render(bitmap, null, matrix, RENDER_MODE_FOR_DISPLAY)
    return bitmap
}
```

`renderPageAtTwoPageTarget` 도 동일 패턴, `fitScale` 만 `(halfScreenW - halfPadPx)` 기준으로 계산.

### 3-2. PageCache — 단일 단계 통합

기존 `createBitmapForPage` + `applyDisplaySettings` (2단계) → `renderPageToTargetBitmap` 한 함수. 위 헬퍼와 정확히 같은 공식 사용. 캐시 히트/미스 결과가 동일한 좌표계 보장.

```kotlin
companion object {
    // PdfViewerActivity.combineTwoPagesUnified 의 finalScale 과 반드시 일치
    const val OVERSAMPLE_FACTOR = 2.5f
}
```

### 3-3. `combineTwoPagesUnified` — 합성만

```kotlin
private fun combineTwoPagesUnified(leftBitmap: Bitmap, rightBitmap: Bitmap? = null): Bitmap {
    val oversample = PageCache.OVERSAMPLE_FACTOR
    val finalWidth = (screenWidth * oversample).toInt()
    val pageHeight = maxOf(leftBitmap.height, rightBitmap?.height ?: 0)
    val finalHeight = pageHeight   // 크롭 반영된 높이

    val finalBitmap = createBitmap(finalWidth, finalHeight, ARGB_8888)
    val finalCanvas = Canvas(finalBitmap).apply { drawColor(WHITE) }

    val centerPadOversamplePx = (screenWidth * currentCenterPadding * oversample).toInt()
    val halfWidth = finalWidth / 2
    val halfPadPx = centerPadOversamplePx / 2
    val leftAreaWidth = halfWidth - halfPadPx

    // 왼쪽 영역의 가운데에 배치 — 스케일링 없음, drawBitmap 만
    val leftX = (leftAreaWidth - leftBitmap.width) / 2f
    val leftY = (finalHeight - leftBitmap.height) / 2f
    finalCanvas.drawBitmap(leftBitmap, leftX, leftY, null)
    // rightBitmap 도 동일하게 우측 영역에 배치
    return finalBitmap
}
```

Canvas Matrix scale 완전 제거. 입력 비트맵이 이미 정확한 목표 크기로 와있으므로 가능.

### 3-4. 차원 일관성 검증

`renderPageAtTwoPageTarget` 의 최대 `targetW`:
```
availW = (screenW/2) - (screenW × pad / 2) = screenW × (1-pad) / 2
fitScale_max = availW / pdfW
targetW_max = pdfW × fitScale_max × 2.5 = availW × 2.5 = screenW × (1-pad) × 1.25
```

`combineTwoPagesUnified` 의 `leftAreaWidth`:
```
finalWidth = screenW × 2.5
halfWidth = screenW × 1.25
halfPadPx = (screenW × pad × 2.5) / 2 = screenW × pad × 1.25
leftAreaWidth = halfWidth - halfPadPx = screenW × (1-pad) × 1.25
```

→ `targetW_max == leftAreaWidth`. 너비 일치 ✓ (fitScale 이 높이로 바인드되면 targetW 가 더 작아져서 letterbox).

---

## 4. 변경 파일 / 라인 수

| 파일 | 변경 |
|---|---|
| `app/src/main/java/com/mrgq/pdfviewer/PageCache.kt` | +50 −135 (−85 net) |
| `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt` | +173 −284 (−111 net) |

**총 −196 라인.** 안티패턴 정리로 코드가 단순해짐.

주요 함수 변화:

PageCache:
- `createBitmapForPage` 제거
- `applyDisplaySettings` 제거
- `renderPageToTargetBitmap` 신설 (renderPageSync/Async 가 호출)
- `OVERSAMPLE_FACTOR = 2.5f` 상수 추가

PdfViewerActivity:
- `renderPageAtSinglePageTarget` 신설
- `renderPageAtTwoPageTarget` 신설
- `renderSinglePage` / `renderSinglePageInternal` → 새 헬퍼로 단순화
- `renderPageDirectly` → 새 헬퍼로 단순화 (isTwoPageMode 분기)
- `renderTwoPagesUnified` → 좌/우 페이지를 `renderPageAtTwoPageTarget` 로 렌더
- `combineTwoPagesUnified` → Canvas postScale 제거, 단순 배치
- `applyDisplaySettings` 완전 제거 (호출부 모두 정리)
- `calculateOptimalScale` → visibleFraction 반영, OVERSAMPLE_FACTOR 사용 (호출부 호환 위해 시그니처 유지하되 실제 렌더에는 직접 사용 안 됨)

---

## 5. 효과 (예상)

### 5-1. 직접 효과
- 두 페이지 모드: fractional scaling 2회 → 1회 (마지막 ImageView 다운스케일만 남고 이는 oversampling 효과로 작용)
- 크롭 사용 시: ImageView 추가 fit 단계 제거. 크롭된 영역이 화면 전체에 1:1 비율로 렌더링 (Option A "남은 영역을 화면에 다시 fit" 의미와 일치)

### 5-2. 부수 효과
- 코드 −196 라인. 두 페이지 모드 / 단일 페이지 모드 / 캐시 히트 / 캐시 미스 4가지 경로가 모두 동일한 `renderPageAtXxxTarget` 헬퍼 또는 `renderPageToTargetBitmap` 을 통과 → 동작 일관성 향상.
- `OVERSAMPLE_FACTOR` 가 한 곳에서 정의됨 (이전엔 PageCache 의 renderScale 과 combineTwoPagesUnified 의 2.5f 가 따로 놀았음).
- 크롭 후 추가 비트맵 복사가 사라짐 (vector → cropped raster 직접) → 메모리 할당/GC 부담 미세 감소.

### 5-3. 외부 환경 한계
- §2-2 의 1080p HDMI → QHD 모니터 1.333× 외부 업스케일은 앱이 해결 못 함. P4 가이드 (모니터 sharpness OFF, 4K 출력 가능 시 사용) 별도 진행.

---

## 6. 검증 항목 (실기기 테스트)

WSL2 환경이라 빌드는 Windows 의 Android Studio 에서:
```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

확인:

1. **두 페이지 모드 PDF — 오선 두께 균일성** (핵심 목표). 이전엔 가는 줄 / 두꺼운 줄 / 진한 줄 / 흐린 줄이 섞여 보였음. 수정 후 균일해져야 함.
2. **단일 페이지 모드 — 회귀 없음**. 단일 페이지는 기존에도 정상이었으므로 그대로 유지되어야 함.
3. **크롭 슬라이더** (위 0/5/10/15%, 아래 동일) 변경 시:
   - 즉시 반영 (PageCache 자동 invalidation)
   - 크롭된 영역이 화면 전체 높이로 확대되어 표시 (Option A)
4. **중앙 여백 슬라이더** (0~15%):
   - 두 페이지 사이 간격 정확히 반영
   - 페이지가 각 절반 영역의 가운데에 배치
5. **마지막 홀수 페이지** — 왼쪽에 표시 (CLAUDE.md 명시 동작)
6. **페이지 전환 애니메이션** (350ms 슬라이드) — `renderPageDirectly` 경로 정상 동작
7. **캐시 히트 vs 미스** — 같은 페이지가 캐시에서 나올 때와 직접 렌더할 때 시각적 차이 없어야 함 (같은 헬퍼 사용으로 보장됨, 시각적 회귀 테스트)
8. **합주 모드** — 지휘자 페이지 변경 시 연주자 화면 정상 (렌더 자체와 무관하므로 영향 없을 것)

문제 발견 시 항목별로 로그 (`adb logcat -s PdfViewerActivity PageCache`) 첨부.

---

## 7. 후속 작업

### 7-1. 즉시 가능
- **`PdfPageManager.kt` 삭제** — dead code 확정. 별도 cleanup 커밋.
- **CLAUDE.md 수정** — "2-4x 스케일링" 문구를 "OVERSAMPLE_FACTOR=2.5 단일 단계 Matrix 렌더" 로 갱신. dead code 삭제 시 같이.

### 7-2. 실기기 검증 후 결정
- 오선 품질이 충분히 개선됐는지 확인. 부족하면:
  - **P2 oversampling 강화** — 2.5 → 3.0 또는 동적 (PDF 크기 따라). 메모리 영향 실측 필요.
  - **P4 사용자 환경 가이드** — README 또는 설정 화면 도움말에 모니터 sharpness/HDR/Noise Reduction OFF 안내.
- 두 페이지 모드 외에 단일 페이지 모드도 같은 헬퍼 거치므로 단일 페이지에서 회귀 없는지 특히 확인.

### 7-3. 하드웨어 결정 후
- 시나리오 B (UHD 모니터) 채택 시: P2/P4/P6 불필요. P1 의 효과만으로 충분할 가능성.
- 시나리오 A 유지 시: P4 가이드 필수, P6 (1:1 픽셀 모드) 검토.

---

## 8. 참고

계획: `20260528_P01_staff_line_rendering_fix_plan.md`
커밋: `0f0c567` — `fix: 두 페이지 모드 오선 두께 불균일 문제 해결 (P01)`
