# 합주 모드 애니메이션 동기화 레이스 컨디션 해결 계획

**날짜**: 2025-07-20  
**작성자**: Gemini (AI Assistant)  
**상태**: ✅ 계획 수립 완료

## 1. 문제 정의

합주 모드에서 페이지를 넘길 때, **페이지 전환 애니메이션이 활성화된 경우에만 동기화가 실패**하는 버그가 발생한다. 애니메이션을 비활성화하면 정상적으로 동기화된다. 이는 애니메이션 처리 로직과 메시지 브로드캐스트(방송) 타이밍 간의 경쟁 상태(Race Condition)가 존재함을 시사한다.

## 2. 근본 원인: 동시성(UX)과 안정성(데이터)의 딜레마

현재 코드는 두 가지 상충하는 목표 사이에서 문제가 발생한다.

1.  **동시성 (UX 우선):** 지휘자와 연주자가 거의 동시에 페이지를 넘기는 것처럼 보이게 하려면, 방송을 먼저 보내야 한다. (현재 방식)
    *   **로직:** `방송` -> `애니메이션 시작` -> `애니메이션 종료 후 상태 업데이트`
    *   **위험:** 방송 후 지휘자 기기의 상태 업데이트(`pageIndex` 변경)가 애니메이션 실패 등의 이유로 누락되면, 지휘자의 내부 상태와 방송 내용이 달라져 다음 동기화부터 완전히 깨진다. **이것이 현재 버그의 직접적인 원인이다.**

2.  **안정성 (데이터 우선):** 데이터 정합성을 완벽하게 보장하려면, 지휘자 기기의 상태 업데이트가 완료된 후 방송해야 한다.
    *   **로직:** `애니메이션 시작` -> `애니메이션 종료 후 상태 업데이트` -> `방송`
    *   **위험:** 연주자는 항상 지휘자의 애니메이션 시간(350ms)만큼 늦게 반응하여 사용자 경험을 해친다.

## 3. 최종 해결 방안: "상태 선(先) 업데이트, 후(後) 애니메이션"

위 딜레마를 해결하고 동시성과 안정성을 모두 확보하기 위해, **내부 상태 업데이트와 방송을 즉시 처리하고, 눈에 보이는 시각 효과(애니메이션)만 나중에 재생**하는 방식으로 아키텍처를 변경한다.

### 새로운 동작 시나리오

1.  사용자가 페이지 넘김을 입력한다.
2.  `animatePageTransition` 메서드가 호출된다.
3.  **(핵심 1) `pageIndex` 변수 값을 목표 페이지로 즉시 변경한다. (상태 선-업데이트)**
4.  **(핵심 2) 변경된 `pageIndex` 값을 기준으로 `broadcastCollaborationPageChange`를 즉시 호출한다. (정확한 상태 방송)**
5.  이제 지휘자의 내부 상태와 방송 내용은 완벽히 일치하며, 연주자는 지연 없이 신호를 받는다.
6.  그 후에 지휘자 기기에서 350ms짜리 애니메이션을 재생하여 시각 효과를 보여준다.

이 방식은 애니메이션 재생이 실패하더라도 가장 중요한 `pageIndex` 상태는 이미 올바르게 업데이트되었기 때문에, 다음 동기화가 깨지지 않는 안정성을 보장한다.

## 4. 상세 구현 계획

*   **대상 파일**: `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt`
*   **대상 메서드**: `animatePageTransition()`

### 수정 전 (Before)
```kotlin
// animatePageTransition() 내부
private fun animatePageTransition(targetBitmap: Bitmap, direction: Int, targetIndex: Int) {
    if (isAnimating) return
    isAnimating = true
    playPageTurnSound()
    
    broadcastCollaborationPageChange(targetIndex) // 방송 먼저
    
    // ... 애니메이션 설정 ...
    animatorSet.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            // ... UI 정리 ...
            pageIndex = targetIndex // 상태 업데이트는 나중에
            updatePageInfo()
            isAnimating = false
        }
    })
    animatorSet.start()
}
```

### 수정 후 (After)
```kotlin
// animatePageTransition() 내부
private fun animatePageTransition(targetBitmap: Bitmap, direction: Int, targetIndex: Int) {
    if (isAnimating) return

    // ====================[ 핵심 수정 사항 ]====================
    // 1. 내부 상태를 먼저, 그리고 즉시 업데이트합니다.
    pageIndex = targetIndex
    
    // 2. 업데이트된 상태를 즉시 방송합니다.
    broadcastCollaborationPageChange(targetIndex)
    // =========================================================

    // 3. 이제 눈에 보이는 애니메이션 효과만 처리합니다.
    isAnimating = true
    playPageTurnSound()
    
    binding.pdfViewNext.setImageBitmap(targetBitmap)
    // ... (애니메이션 설정 및 실행 코드) ...

    animatorSet.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            // 여기서는 UI 정리 작업만 수행합니다.
            // pageIndex 업데이트는 이미 위에서 처리했습니다.
            updatePageInfo() // pageIndex가 이미 바뀌었으므로 올바른 정보 표시
            binding.pdfView.setImageBitmap(targetBitmap)
            setImageViewMatrix(targetBitmap)
            binding.pdfView.translationX = 0f
            binding.pdfViewNext.visibility = View.GONE
            isAnimating = false
        }
    })
    animatorSet.start()
}
```

## 5. 검증 계획

1.  지휘자와 연주자 기기에서 모두 **애니메이션 설정을 활성화**한다.
2.  합주 모드를 시작하고 파일을 연다.
3.  **[Test 1]** 지휘자가 페이지를 1->2, 2->3, 3->2 순서로 여러 번 넘긴다. -> 매번 연주자의 페이지가 지연 없이 거의 동시에 넘어가는지 확인한다.
4.  **[Test 2]** 두 페이지 모드에서도 동일하게 테스트를 반복하여 정상 동작을 확인한다.
5.  **[Test 3]** 지휘자가 마지막 페이지에서 다음 파일로 넘어갈 때도 동기화가 잘 되는지 확인한다.

## 6. 결론

이 계획은 사용자 경험(동시성)을 해치지 않으면서 데이터 정합성(안정성)을 보장하는 가장 이상적인 해결책이다. 상태 업데이트와 시각 효과를 분리함으로써, 애니메이션 로직의 성공 여부와 관계없이 핵심적인 동기화 기능이 안정적으로 동작하도록 보장한다.
