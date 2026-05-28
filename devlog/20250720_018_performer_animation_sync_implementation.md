# 연주자 모드 애니메이션 동기화 구현

**날짜**: 2025-07-20  
**작성자**: Claude (AI Assistant)  
**상태**: ✅ 구현 완료

## 1. 구현 배경

기존에는 합주 모드에서 지휘자가 페이지를 넘길 때, 연주자 기기에서는 애니메이션 설정과 관계없이 항상 즉시 페이지 전환이 발생했습니다. 이로 인해 다음과 같은 사용자 경험 문제가 있었습니다:

1. **일관성 부족**: 지휘자는 애니메이션을 보지만 연주자는 즉시 전환
2. **개인 설정 무시**: 연주자의 애니메이션 설정이 합주 모드에서 반영되지 않음
3. **시각적 불일치**: 지휘자와 연주자 간의 다른 시각적 경험

## 2. 문제 분석

### 2.1. 기존 동작 흐름

```
지휘자 페이지 넘김
↓
지휘자: animatePageTransition() → 애니메이션 실행
↓
브로드캐스트: page_change 메시지 전송
↓
연주자: handleRemotePageChange() → showPage() 호출
↓
연주자: 즉시 페이지 전환 (애니메이션 없음) ❌
```

### 2.2. 기존 코드 분석

```kotlin
// 기존 handleRemotePageChange()
private fun handleRemotePageChange(page: Int) {
    val targetIndex = page - 1
    
    if (targetIndex >= 0 && targetIndex < pageCount) {
        isHandlingRemotePageChange = true
        
        // 항상 즉시 전환만 수행 ❌
        showPage(targetIndex)
        
        isHandlingRemotePageChange = false
    }
}
```

## 3. 구현 방안

### 3.1. 설계 원칙

1. **개인 설정 존중**: 각 연주자의 애니메이션 설정을 따름
2. **일관된 경험**: 지휘자와 연주자가 유사한 시각적 경험
3. **방향성 유지**: 페이지 이동 방향에 맞는 애니메이션
4. **성능 고려**: 애니메이션 비활성화 시 즉시 전환으로 빠른 반응

### 3.2. 구현 전략

기존의 `isPageTurnAnimationEnabled()` 메서드와 `showPageWithAnimation()` 메서드를 활용하여 연주자도 조건부 애니메이션을 지원하도록 수정했습니다.

## 4. 구현 세부사항

### 4.1. 수정된 코드

**파일**: `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt`  
**메서드**: `handleRemotePageChange()`

```kotlin
private fun handleRemotePageChange(page: Int) {
    // Convert to 0-based index
    val targetIndex = page - 1
    
    Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 변경 신호 수신됨 (current file: $pdfFileName, pageCount: $pageCount)")
    
    if (targetIndex >= 0 && targetIndex < pageCount) {
        // 재귀 방지를 위해 플래그 설정
        isHandlingRemotePageChange = true
        
        Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 중...")
        
        // ====================[ 핵심 수정 사항 ]====================
        // 연주자도 애니메이션 설정에 따라 애니메이션을 보여줌
        if (isPageTurnAnimationEnabled()) {
            val direction = if (targetIndex > pageIndex) 1 else -1
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 애니메이션과 함께 페이지 전환 (방향: $direction)")
            showPageWithAnimation(targetIndex, direction)
        } else {
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 즉시 페이지 전환 (애니메이션 비활성화)")
            showPage(targetIndex)
        }
        // ==========================================================
        
        // 플래그 해제
        isHandlingRemotePageChange = false
        
        Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 완료")
    } else {
        Log.w("PdfViewerActivity", "🎼 연주자 모드: 잘못된 페이지 번호 $page (총 $pageCount 페이지)")
    }
}
```

### 4.2. 핵심 개선 요소

1. **조건부 애니메이션**:
   ```kotlin
   if (isPageTurnAnimationEnabled()) {
       showPageWithAnimation(targetIndex, direction)
   } else {
       showPage(targetIndex)
   }
   ```

2. **스마트 방향 계산**:
   ```kotlin
   val direction = if (targetIndex > pageIndex) 1 else -1
   // 1: 오른쪽으로 슬라이드 (앞 페이지)
   // -1: 왼쪽으로 슬라이드 (뒤 페이지)
   ```

3. **상세한 로깅**:
   ```kotlin
   Log.d("PdfViewerActivity", "🎼 연주자 모드: 애니메이션과 함께 페이지 전환 (방향: $direction)")
   Log.d("PdfViewerActivity", "🎼 연주자 모드: 즉시 페이지 전환 (애니메이션 비활성화)")
   ```

## 5. 동작 시나리오

### 5.1. 애니메이션 활성화 시나리오

```
지휘자: 페이지 2 → 3 넘김
↓
지휘자 기기: 오른쪽 슬라이드 애니메이션 (350ms)
↓
브로드캐스트: page_change(3) 메시지
↓
연주자 기기: handleRemotePageChange(3) 수신
↓
연주자 설정 확인: isPageTurnAnimationEnabled() = true
↓
연주자 기기: 오른쪽 슬라이드 애니메이션 (350ms) ✅
↓
결과: 지휘자와 연주자가 거의 동시에 동일한 애니메이션 경험
```

### 5.2. 애니메이션 비활성화 시나리오

```
지휘자: 페이지 3 → 2 넘김
↓
지휘자 기기: 왼쪽 슬라이드 애니메이션 (또는 즉시 전환)
↓
브로드캐스트: page_change(2) 메시지
↓
연주자 기기: handleRemotePageChange(2) 수신
↓
연주자 설정 확인: isPageTurnAnimationEnabled() = false
↓
연주자 기기: 즉시 페이지 전환 ✅
↓
결과: 빠른 반응으로 지연 없는 동기화
```

## 6. 기술적 세부사항

### 6.1. 활용된 기존 메서드

1. **`isPageTurnAnimationEnabled()`**:
   ```kotlin
   private fun isPageTurnAnimationEnabled(): Boolean {
       return preferences.getBoolean("page_turn_animation_enabled", true)
   }
   ```

2. **`showPageWithAnimation()`**:
   - 기존에 구현된 애니메이션 메서드
   - 방향(direction) 매개변수를 받아 슬라이드 방향 결정
   - 내부적으로 `animatePageTransition()` 호출

### 6.2. 재귀 방지 메커니즘

```kotlin
isHandlingRemotePageChange = true
// ... 페이지 전환 로직 ...
isHandlingRemotePageChange = false
```

- 연주자가 원격 페이지 변경을 처리하는 동안 추가 브로드캐스트를 방지
- 무한 루프 방지

## 7. 사용자 경험 개선 효과

### 7.1. Before vs After 비교

| 항목 | 기존 (Before) | 개선 후 (After) |
|------|---------------|----------------|
| **연주자 애니메이션** | 항상 즉시 전환 | 설정에 따라 애니메이션 |
| **지휘자-연주자 일관성** | 다른 경험 | 동일한 시각적 경험 |
| **개인 설정 반영** | 무시됨 | 완전 반영 |
| **방향성** | 없음 | 스마트 방향 계산 |

### 7.2. 사용자 혜택

1. **시각적 일관성**: 모든 참가자가 유사한 페이지 전환 경험
2. **개인화**: 각자의 애니메이션 선호도 존중
3. **직관적 방향성**: 앞/뒤 페이지 이동에 맞는 자연스러운 애니메이션
4. **선택적 성능**: 애니메이션 비활성화 시 빠른 반응

## 8. 테스트 시나리오

### 8.1. 기본 기능 테스트

1. **애니메이션 활성화 상태 테스트**:
   - 지휘자가 앞 페이지로 이동 → 연주자도 오른쪽 슬라이드 애니메이션
   - 지휘자가 뒤 페이지로 이동 → 연주자도 왼쪽 슬라이드 애니메이션

2. **애니메이션 비활성화 상태 테스트**:
   - 지휘자가 페이지 이동 → 연주자는 즉시 페이지 전환

3. **혼합 설정 테스트**:
   - 지휘자: 애니메이션 활성화, 연주자: 비활성화
   - 지휘자: 애니메이션 비활성화, 연주자: 활성화

### 8.2. 엣지 케이스 테스트

1. **빠른 연속 페이지 넘김**: 애니메이션 중 추가 신호 수신
2. **첫/마지막 페이지**: 경계 페이지에서의 동작
3. **두 페이지 모드**: 두 페이지 모드에서의 애니메이션 동기화

## 9. 성능 고려사항

### 9.1. 메모리 사용량

- 기존 애니메이션 시스템 활용으로 추가 메모리 사용량 최소화
- 조건부 실행으로 불필요한 리소스 사용 방지

### 9.2. 네트워크 영향

- 브로드캐스트 메시지 구조 변경 없음
- 추가 네트워크 트래픽 없음

### 9.3. CPU 사용량

- 애니메이션 비활성화 시 CPU 사용량 절약
- 활성화 시에도 기존 애니메이션 시스템과 동일한 수준

## 10. 향후 확장 가능성

### 10.1. 추가 개선 아이디어

1. **애니메이션 속도 동기화**: 지휘자와 연주자의 애니메이션 속도 일치
2. **맞춤형 애니메이션**: 합주 모드 전용 애니메이션 효과
3. **지연 보상**: 네트워크 지연을 고려한 타이밍 조정

### 10.2. 설정 확장

```kotlin
// 향후 추가 가능한 설정들
private fun getCollaborationAnimationDuration(): Long {
    return preferences.getLong("collaboration_animation_duration", 350L)
}

private fun isCollaborationAnimationSyncEnabled(): Boolean {
    return preferences.getBoolean("collaboration_animation_sync", true)
}
```

## 11. 결론

이번 구현을 통해 합주 모드에서의 사용자 경험이 크게 개선되었습니다. 특히:

1. **완전한 개인화**: 각 참가자의 애니메이션 설정 완전 반영
2. **일관된 경험**: 지휘자와 연주자 간의 시각적 일관성 확보
3. **스마트한 동작**: 페이지 이동 방향에 맞는 자연스러운 애니메이션
4. **성능 최적화**: 설정에 따른 선택적 애니메이션 실행

이제 연주자들도 각자의 선호도에 맞춰 지휘자와 거의 동시에 부드러운 페이지 전환을 경험할 수 있습니다. 합주 모드의 몰입감과 사용성이 한층 향상될 것으로 기대됩니다.