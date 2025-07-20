# 두 페이지 모드 여백 설정 시 종횡비 버그 수정

**날짜**: 2025-07-20  
**작성자**: Claude (AI Assistant)  
**상태**: ✅ 완료

## 1. 문제 발견

사용자가 두 페이지 모드에서 가운데 여백(center padding) 설정을 조정할 때, 오른쪽 페이지가 가로로 늘어나서 페이지의 가로세로 비율이 변하는 문제를 발견했습니다.

**증상:**
- 왼쪽 페이지: 정상적으로 여백만 증가
- 오른쪽 페이지: 가로로 늘어나면서 종횡비 변형

## 2. 원인 분석

### 2.1 코드 흐름 추적
1. `combineTwoPagesUnified()`: 여백이 정확히 적용된 결합 비트맵 생성
2. `applyDisplaySettings()`: 클리핑 및 추가 여백 처리

### 2.2 근본 원인
`applyDisplaySettings()` 함수의 두 페이지 모드 처리 로직에서 **중복 여백 처리**가 발생했습니다:

**문제 있던 로직:**
```kotlin
if (isTwoPageMode) {
    // 여백을 고려한 최종 폭 계산 (여백이 이미 적용된 상태)
    val actualPaddingWidth = if (currentCenterPadding > 0) {
        (originalWidth * currentCenterPadding).toInt()
    } else {
        0
    }
    val leftPageWidth = (originalWidth - actualPaddingWidth) / 2
    val rightPageStartX = leftPageWidth + actualPaddingWidth
    
    // 왼쪽 페이지 (클리핑된 부분)
    val leftSrcRect = android.graphics.Rect(0, topClipPixels, leftPageWidth, originalHeight - bottomClipPixels)
    val leftDstRect = android.graphics.Rect(0, 0, leftPageWidth, clippedHeight)
    canvas.drawBitmap(originalBitmap, leftSrcRect, leftDstRect, null)
    
    // 오른쪽 페이지 (클리핑된 부분)
    val rightSrcRect = android.graphics.Rect(rightPageStartX, topClipPixels, originalWidth, originalHeight - bottomClipPixels)
    val rightDstRect = android.graphics.Rect(rightPageStartX, 0, finalWidth, clippedHeight)
    canvas.drawBitmap(originalBitmap, rightSrcRect, rightDstRect, null)
}
```

**문제점:**
1. `combineTwoPagesUnified()`에서 이미 여백이 정확히 적용됨
2. `applyDisplaySettings()`에서 다시 여백 계산을 시도
3. 오른쪽 페이지의 소스/대상 좌표 불일치로 늘어남 현상 발생

## 3. 해결 방안

### 3.1 설계 원칙 재정립
- `combineTwoPagesUnified()`: 여백 적용 및 페이지 결합 담당
- `applyDisplaySettings()`: 클리핑만 담당 (여백은 이미 처리됨)

### 3.2 수정된 로직
```kotlin
if (isTwoPageMode) {
    // 두 페이지 모드에서는 여백이 이미 적용된 상태
    // 여백이 있는 경우 원본에서 각 페이지의 위치를 정확히 계산
    
    Log.d("PdfViewerActivity", "두 페이지 클리핑: 원본 폭=${originalWidth}, 여백 폭=${paddingWidth}")
    
    // 클리핑만 적용 (여백은 이미 적용되어 있음)
    val srcRect = android.graphics.Rect(0, topClipPixels, originalWidth, originalHeight - bottomClipPixels)
    val dstRect = android.graphics.Rect(0, 0, finalWidth, clippedHeight)
    canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
}
```

**핵심 변경:**
1. 복잡한 페이지별 좌표 계산 제거
2. 전체 비트맵에 대해 단순 클리핑만 적용
3. 여백 처리는 이전 단계에서 이미 완료된 것으로 인식

## 4. 구현 결과

### 4.1 수정 전후 비교

**수정 전:**
- 0% 여백: 정상
- 5% 여백: 오른쪽 페이지 가로 늘어남
- 10% 여백: 오른쪽 페이지 심각한 변형

**수정 후:**
- 0% 여백: 페이지들이 완전히 붙어서 표시
- 5% 여백: 정확히 5% 간격으로 분리, 종횡비 유지
- 15% 여백: 정확히 15% 간격으로 분리, 종횡비 유지

### 4.2 기능 검증
- ✅ 왼쪽 페이지 종횡비 유지
- ✅ 오른쪽 페이지 종횡비 유지
- ✅ 여백 설정 정확히 적용
- ✅ 클리핑 기능 정상 작동
- ✅ 마지막 홀수 페이지 정상 처리

## 5. 기술적 세부사항

### 5.1 비트맵 처리 흐름
```
PDF 페이지 렌더링 
    ↓
combineTwoPagesUnified() - 여백 적용하여 결합
    ↓
applyDisplaySettings() - 클리핑만 적용
    ↓
최종 표시
```

### 5.2 좌표 계산 단순화
- **이전**: 복잡한 페이지별 소스/대상 좌표 계산
- **현재**: 단순한 전체 비트맵 클리핑

### 5.3 메모리 효율성
- 중간 비트맵 생성 최소화
- 불필요한 좌표 변환 제거
- 더 간단한 Canvas 작업

## 6. 향후 확장성

이번 수정으로 두 페이지 모드의 여백 처리가 더욱 견고해졌습니다:

### 6.1 추가 기능 구현 용이성
- **동적 여백 조정**: 실시간 여백 변경 기능 추가 용이
- **다양한 여백 모드**: 고정 픽셀, 퍼센티지, 자동 조정 등
- **페이지별 개별 여백**: 왼쪽/오른쪽 페이지 개별 여백 설정

### 6.2 성능 최적화 가능성
- **GPU 가속**: 단순한 클리핑 로직으로 GPU 활용 용이
- **캐싱 효율성**: 일관된 처리 로직으로 캐시 적중률 향상
- **메모리 사용량**: 불필요한 중간 계산 제거로 메모리 절약

## 7. 사용자 경험 개선

### 7.1 즉시 개선되는 부분
- **정확한 여백**: 설정한 퍼센티지가 정확히 반영
- **일관된 품질**: 모든 여백 설정에서 동일한 페이지 품질
- **직관적 동작**: 여백 증가 시 예상대로 간격만 벌어짐

### 7.2 장기적 사용성 향상
- **신뢰성**: 어떤 여백 설정에서도 안정적인 표시
- **예측 가능성**: 사용자가 예상하는 대로 동작
- **전문성**: 실제 악보 뷰어처럼 정확한 페이지 비율 유지

이번 수정으로 두 페이지 모드의 여백 기능이 완전히 안정화되어, 사용자가 악보를 더욱 편리하고 정확하게 볼 수 있게 되었습니다.