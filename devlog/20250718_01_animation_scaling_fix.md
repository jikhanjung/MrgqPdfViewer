# 페이지 전환 애니메이션 스케일링 이슈 해결

**날짜**: 2025-01-18  
**버전**: v0.1.8+  
**작업자**: Claude Code  
**상태**: ✅ 완료

## 🎯 문제 상황

### 발견된 이슈
두 페이지 모드에서 페이지 1-2에서 페이지 3-4로 전환할 때 애니메이션 중에 다음과 같은 문제가 발생:

1. **처음 발견**: 페이지 3의 왼쪽 상단이 크게 확대된 상태로 애니메이션 진행
2. **첫 번째 수정 후**: 확대는 해결되었지만 페이지 3의 왼쪽 상단 모서리만 보이고 나머지는 공백

### 사용자 피드백
- "파일의 첫 (두) 페이지를 보여주다가 다음 페이지로 넘어가면 이상하게 다음 페이지의 왼쪽 상단이 크게 확대가 된 채로 애니메이션이 진행돼"
- "다음 페이지의 캐시가 잘 안 만들어져 있는 것 같은데?"
- "now it's not enlarged, but only the upper left corner of page is shown during animation and the rest is blank"

## 🔍 원인 분석

### 1. 매트릭스 스케일 계산 오류
초기 문제는 `animatePageTransition` 메서드에서 현재 페이지와 타겟 페이지의 모드 차이를 제대로 감지하지 못했음:

```kotlin
// 문제가 있던 코드
val useScale = if (isTwoPageMode) optimalScale else currentScaleX
```

### 2. 비트맵 결합 로직 불일치
더 근본적인 문제는 `combineTwoPages`와 `combinePageWithEmpty` 메서드의 로직이 잘못되었음:

- **기존 방식**: 비트맵을 단순 연결하여 화면 너비를 초과하는 큰 비트맵 생성
- **결과**: ImageView 매트릭스가 oversized 비트맵을 화면에 맞추려 하면서 왼쪽 상단만 표시

### 3. 렌더링 방식 불일치
`PdfPageManager`의 `combineTwoPages`와 `PdfViewerActivity`의 `combineTwoPages`가 서로 다른 방식으로 동작:

- **PdfPageManager**: 개별 페이지를 렌더링 후 적절한 스케일과 위치로 결합
- **PdfViewerActivity**: 단순 비트맵 연결

## 🛠️ 해결 과정

### 1단계: 애니메이션 매트릭스 로직 개선
```kotlin
// 현재 페이지와 타겟 페이지의 실제 모드 확인
val currentIsTwoPage = isCurrentPageTwoPageMode()
val targetIsTwoPage = isTargetPageTwoPageMode(targetIndex)

// 모드 변경 시에만 최적 스케일 사용
val useScale = if (currentIsTwoPage != targetIsTwoPage) {
    optimalScale
} else {
    currentScaleX
}
```

### 2단계: 헬퍼 메서드 추가
```kotlin
private fun isCurrentPageTwoPageMode(): Boolean {
    return isTwoPageMode && pageIndex % 2 == 0
}

private fun isTargetPageTwoPageMode(targetIndex: Int): Boolean {
    return isTwoPageMode && targetIndex % 2 == 0
}
```

### 3단계: 비트맵 결합 로직 완전 재작성
`PdfPageManager`의 로직을 기반으로 `combineTwoPages` 메서드를 재구현:

```kotlin
private fun combineTwoPages(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap {
    // 화면 크기에 맞는 스케일 계산 (two-page mode)
    val screenWidth = binding.pdfView.width
    val screenHeight = binding.pdfView.height
    
    val scaleX = (screenWidth / 2).toFloat() / leftBitmap.width
    val scaleY = screenHeight.toFloat() / leftBitmap.height
    val scale = kotlin.math.min(scaleX, scaleY)
    
    // 항상 화면 크기와 동일한 최종 비트맵 생성
    val totalWidth = screenWidth
    val totalHeight = kotlin.math.min(screenHeight, scaledHeight)
    
    // 각 페이지를 적절한 크기로 스케일링 후 배치
    val leftScaled = Bitmap.createScaledBitmap(leftBitmap, scaledWidth, scaledHeight, true)
    val rightScaled = Bitmap.createScaledBitmap(rightBitmap, scaledWidth, scaledHeight, true)
    
    // 중앙 여백을 고려한 정확한 위치 계산
    val leftX = ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
    val rightX = singlePageWidth + paddingPixels + ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
    
    canvas.drawBitmap(leftScaled, leftX.toFloat(), y.toFloat(), null)
    canvas.drawBitmap(rightScaled, rightX.toFloat(), y.toFloat(), null)
}
```

### 4단계: combinePageWithEmpty 메서드도 동일 로직 적용
마지막 홀수 페이지를 왼쪽에 배치하는 로직도 동일한 방식으로 수정.

## 📊 수정 결과

### Before (문제 상황)
- ❌ 페이지 전환 시 확대된 화면 표시
- ❌ 애니메이션 중 일부 영역만 보임
- ❌ 비트맵 크기 불일치로 인한 매트릭스 계산 오류

### After (해결 완료)
- ✅ 자연스러운 페이지 전환 애니메이션
- ✅ 전체 페이지 내용이 올바르게 표시
- ✅ 화면 크기에 맞는 일관된 비트맵 생성
- ✅ 두 페이지 모드와 단일 페이지 모드 간 전환 완벽 지원

## 🔧 주요 수정 파일

### `/app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt`

#### 1. 애니메이션 매트릭스 로직 개선
- 라인 2813-2846: 현재/타겟 페이지 모드 확인 로직 추가
- 라인 2840-2846: 모드 변경 시에만 최적 스케일 사용

#### 2. 헬퍼 메서드 추가
- 라인 2802-2804: `isCurrentPageTwoPageMode()`
- 라인 2809-2811: `isTargetPageTwoPageMode()`

#### 3. 비트맵 결합 로직 재작성
- 라인 631-679: `combineTwoPages()` 완전 재구현
- 라인 681-719: `combinePageWithEmpty()` 완전 재구현

#### 4. 개별 페이지 렌더링 로직 정리
- 라인 2774-2778: `renderPageDirectly()` 스케일 계산 단순화

## 🎯 기술적 개선점

### 1. 일관된 렌더링 파이프라인
- `PdfPageManager`와 `PdfViewerActivity`의 비트맵 처리 로직 통일
- 캐시된 페이지와 즉시 렌더링된 페이지 간 동일한 결과 보장

### 2. 정확한 스케일 계산
- 두 페이지 모드에서 각 페이지는 화면 폭의 절반 기준으로 스케일링
- 중앙 여백 설정 반영한 정확한 위치 계산

### 3. 메모리 효율성
- 임시 스케일링된 비트맵 즉시 recycle
- 화면 크기와 정확히 일치하는 최종 비트맵 생성

### 4. 디버깅 개선
- 상세한 로깅으로 비트맵 크기와 위치 추적
- 애니메이션 과정에서의 매트릭스 변환 추적

## 🚀 사용자 경험 개선

### 애니메이션 품질
- 실제 악보 페이지를 넘기는 것과 같은 자연스러운 전환
- 확대/축소 없는 일관된 스케일 유지
- 부드러운 350ms 페이지 슬라이드 애니메이션

### 시각적 일관성
- 정적 페이지 표시와 애니메이션 중 페이지 표시 완전 일치
- 두 페이지 모드에서 중앙 여백 설정 정확히 반영
- 클리핑 설정 애니메이션에서도 올바르게 적용

## 📝 학습 포인트

### 1. 비트맵 조작 시 주의사항
- ImageView 매트릭스는 화면 크기를 기준으로 계산됨
- 화면 크기를 초과하는 비트맵은 예상치 못한 결과 초래
- 스케일링과 위치 계산은 최종 출력 크기를 기준으로 해야 함

### 2. 애니메이션 최적화
- 애니메이션용 비트맵은 표시용 비트맵과 동일한 방식으로 생성
- 캐시 로직과 즉시 렌더링 로직 간 일관성 유지 필요
- 디버깅 로그는 문제 해결에 필수적

### 3. 아키텍처 일관성
- 동일한 기능의 중복 구현 시 로직 통일 필요
- 매니저 클래스 분리 후에도 핵심 로직 동기화 유지
- 리팩토링 시 기존 동작과의 완벽한 호환성 보장

## 🔄 후속 작업 제안

### 1. 성능 최적화
- [ ] 애니메이션용 비트맵 사전 생성 및 캐싱
- [ ] 메모리 사용량 모니터링 및 최적화
- [ ] 대용량 PDF 파일에서의 애니메이션 성능 테스트

### 2. 사용자 경험 개선
- [ ] 애니메이션 속도 사용자 설정 추가
- [ ] 다양한 애니메이션 효과 옵션 제공
- [ ] 저사양 기기에서의 애니메이션 품질 조정

### 3. 코드 품질
- [ ] 비트맵 결합 로직 유틸리티 클래스로 분리
- [ ] 단위 테스트 추가 (특히 스케일 계산)
- [ ] 메모리 누수 방지 검증

---

**결론**: 이번 수정을 통해 PDF 뷰어의 페이지 전환 애니메이션이 완벽하게 동작하게 되었습니다. 사용자가 실제 악보 페이지를 넘기는 것과 같은 자연스러운 경험을 제공할 수 있게 되었습니다. 🎵✨