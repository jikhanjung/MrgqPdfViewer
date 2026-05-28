# 두 페이지 렌더링 함수 통합 및 여백 계산 개선

**날짜**: 2025-07-20  
**작성자**: Claude (AI Assistant)  
**상태**: ✅ 완료

## 1. 개요

두 페이지 모드에서 중복된 렌더링 함수들과 여백 계산 버그를 발견하여 전면적인 리팩토링을 수행했습니다. 4개의 중복 함수를 2개의 통합 함수로 단순화하고, 여백 계산 로직의 일관성을 확보했습니다.

## 2. 발견된 문제점

### 2.1 중복 함수 문제
현재 코드베이스에 **같은 작업을 하는 함수가 2벌씩** 존재했습니다:

**두 페이지 처리:**
- `renderTwoPages()` - 새로운 방식 (원본 해상도 렌더링 후 결합)
- `combineTwoPages()` - 기존 방식 (이미 렌더링된 비트맵 결합)

**마지막 홀수 페이지 처리:**
- `renderSinglePageOnLeft()` - 새로운 방식
- `combinePageWithEmpty()` - 기존 방식

### 2.2 여백 계산 버그
`combineTwoPages()` 함수에서 0% 여백 설정에도 불구하고 실제로는 중앙에 큰 여백이 발생하는 문제가 있었습니다.

**버그 있던 로직:**
```kotlin
// 전체 화면 너비에서 여백을 빼고 각 페이지 영역을 계산 (❌)
val paddingPixels = (scaledWidth * currentCenterPadding).toInt()
val availableWidth = totalWidth - paddingPixels
val singlePageWidth = availableWidth / 2

val leftX = ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
val rightX = singlePageWidth + paddingPixels + ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
```

**결과:** 0% 설정에도 페이지들이 화면 좌우 끝에 배치되면서 중앙에 큰 여백 발생

## 3. 해결 방안

### 3.1 통합된 함수 설계

**새로운 통합 함수:**
1. `combineTwoPagesUnified(leftBitmap, rightBitmap?)` - 모든 비트맵 결합 처리
2. `renderTwoPagesUnified(leftPageIndex, isLastOddPage)` - 모든 두 페이지 렌더링 처리

### 3.2 정확한 여백 계산

**수정된 로직:**
```kotlin
// 두 페이지와 설정된 여백만을 계산해서 화면 중앙에 배치 (✅)
val paddingPixels = (leftBitmap.width * currentCenterPadding).toInt()
val rightWidth = rightBitmap?.width ?: leftBitmap.width
val combinedWidth = leftBitmap.width + rightWidth + paddingPixels

// 화면 중앙에 배치
val combinedCanvas = Canvas(combinedBitmap)
combinedCanvas.drawBitmap(leftBitmap, 0f, 0f, null)

if (rightBitmap != null) {
    val rightPageX = leftBitmap.width.toFloat() + paddingPixels
    combinedCanvas.drawBitmap(rightBitmap, rightPageX, 0f, null)
}
```

**결과:** 
- 0% 설정 시 → 페이지들이 완전히 붙어서 표시
- 5% 설정 시 → 페이지 너비의 5%만큼만 정확히 사이 여백 생성

## 4. 구현 내용

### 4.1 통합 함수 구현

#### `combineTwoPagesUnified()` 함수
```kotlin
/**
 * 통합된 두 페이지 결합 함수 - 모든 두 페이지 모드 렌더링을 처리
 * @param leftBitmap 왼쪽 페이지 비트맵 (원본 해상도)
 * @param rightBitmap 오른쪽 페이지 비트맵 (null이면 빈 공간으로 처리)
 * @return 결합된 고해상도 비트맵
 */
private fun combineTwoPagesUnified(leftBitmap: Bitmap, rightBitmap: Bitmap? = null): Bitmap
```

**핵심 특징:**
- `rightBitmap`이 `null`이면 마지막 홀수 페이지로 처리
- 원본 해상도에서 결합 후 고해상도 스케일링
- 정확한 여백 계산으로 중앙 배치
- `applyDisplaySettings()` 호출로 클리핑 적용

#### `renderTwoPagesUnified()` 함수
```kotlin
/**
 * 통합된 두 페이지 렌더링 함수 - 처음부터 렌더링하는 모든 두 페이지 모드를 처리
 * @param leftPageIndex 왼쪽 페이지 인덱스
 * @param isLastOddPage 마지막 홀수 페이지 모드 (오른쪽 빈 공간)
 * @return 결합된 고해상도 비트맵
 */
private suspend fun renderTwoPagesUnified(leftPageIndex: Int, isLastOddPage: Boolean = false): Bitmap
```

**핵심 특징:**
- `isLastOddPage = true`이면 오른쪽 페이지 없이 처리
- PDF 페이지 열기부터 비트맵 결합까지 전체 과정 처리
- 예외 처리 및 리소스 정리 완벽 구현
- 통합된 `combineTwoPagesUnified()` 호출

### 4.2 기존 함수 호출 변경

**모든 호출 지점을 새로운 통합 함수로 변경:**

```kotlin
// 캐시에서 즉시 표시 (두 페이지)
combineTwoPagesUnified(page1, page2)

// 캐시에서 즉시 표시 (마지막 홀수 페이지)
combineTwoPagesUnified(page1, null)

// 즉시 렌더링 (두 페이지)
combineTwoPagesUnified(page1, page2)

// 즉시 렌더링 (마지막 홀수 페이지)
combineTwoPagesUnified(page1, null)

// showPage()에서 직접 렌더링 (두 페이지)
renderTwoPagesUnified(index)

// showPage()에서 직접 렌더링 (마지막 홀수 페이지)
renderTwoPagesUnified(index, true)
```

### 4.3 중복 함수 제거

**완전히 제거된 함수들:**
1. `combineTwoPages()` - 130라인 제거
2. `combinePageWithEmpty()` - 40라인 제거  
3. `renderTwoPages()` - 130라인 제거
4. `renderSinglePageOnLeft()` - 90라인 제거

**총 제거된 코드:** ~390라인

## 5. 자동 두 페이지 모드 개선

사용자 요청에 따라 가로 화면에서 세로 PDF를 열 때 다이얼로그 없이 자동으로 두 페이지 모드가 적용되도록 수정했습니다.

**변경 전:**
```kotlin
} else if (screenAspectRatio > 1.0f && pdfAspectRatio < 1.0f) {
    // Screen is landscape and PDF is portrait - ask user
    showTwoPageModeDialog(onComplete)
```

**변경 후:**
```kotlin
} else if (screenAspectRatio > 1.0f && pdfAspectRatio < 1.0f) {
    // Screen is landscape and PDF is portrait - automatically use two page mode
    isTwoPageMode = true
    saveDisplayModePreference(DisplayMode.DOUBLE)
    Log.d("PdfViewerActivity", "✅ Auto-enabled two page mode and saved preference")
    onComplete()
```

## 6. 최종 결과

### 6.1 코드 품질 개선
- **함수 수:** 4개 → 2개로 50% 감소
- **코드 라인:** ~390라인 제거
- **중복 제거:** 완전한 DRY 원칙 적용
- **유지보수성:** 단일 책임 원칙으로 크게 향상

### 6.2 기능 개선
- **여백 계산:** 0% 설정 시 진짜 0% 여백 적용
- **자동 모드:** 가로 화면 + 세로 PDF → 자동 두 페이지 모드
- **일관성:** 모든 두 페이지 렌더링이 동일한 로직 사용
- **성능:** 중복 계산 제거로 렌더링 효율성 향상

### 6.3 사용자 경험 개선
- **정확한 여백:** 설정한 퍼센티지대로 정확히 적용
- **자동 전환:** 세로 악보를 가로 화면에서 열면 바로 두 페이지 표시
- **일관된 품질:** 모든 상황에서 동일한 고해상도 렌더링
- **안정성:** 예외 처리 강화로 앱 크래시 방지

## 7. 향후 확장성

통합된 함수 구조로 인해 향후 기능 추가가 용이해졌습니다:

- **세 페이지 모드:** `combineTwoPagesUnified()` 확장으로 쉽게 구현 가능
- **동적 여백:** 제스처로 실시간 여백 조정 기능 추가 용이
- **다양한 레이아웃:** 상하 배치, 대각선 배치 등 다양한 모드 확장 가능
- **성능 최적화:** 단일 함수이므로 프로파일링 및 최적화 집중 가능

이번 리팩토링으로 두 페이지 모드의 안정성과 사용성이 크게 향상되었으며, 향후 기능 확장을 위한 견고한 기반을 구축했습니다.