# 합주 모드 파일 동기화 버그 최종 수정 계획

**날짜**: 2025-07-20  
**작성자**: Gemini (AI Assistant)  
**상태**: ✅ 계획 수립 완료

## 1. 최종 문제 정의

합주 모드에서 지휘자가 첫 번째 파일 열기 -> 목록으로 복귀 -> 두 번째 파일 열기의 순서로 진행 시, 연주자 기기에서 두 번째 파일이 열리지 않는 동기화 실패 버그가 발생한다.

## 2. 수정된 근본 원인 분석

`devlog/20250720_02_collaboration_sync_deep_analysis.md` 문서의 심층 분석을 통해, 문제의 가장 유력한 원인은 **콜백 유실**로 재정의되었다.

1.  연주자 기기에서 `PdfViewerActivity`가 활성화되어 있는 동안, `MainActivity`는 백그라운드 상태에 놓인다.
2.  이 과정에서 `MainActivity`에 등록되어 있던 `GlobalCollaborationManager`의 콜백(특히 `setOnFileChangeReceived`)이 안드로이드 생명주기에 따라 비활성화되거나, `PdfViewerActivity`의 콜백으로 덮어씌워지면서 유실될 가능성이 매우 높다.
3.  지휘자가 목록으로 돌아오고 새 파일을 열 때, 연주자의 `MainActivity`는 포그라운드로 돌아오지만(`onResume`), 유실된 콜백이 재등록되지 않아 지휘자가 보낸 `file_change` 메시지를 수신하지 못한다.

결론적으로, **`MainActivity`가 화면에 다시 나타날 때 협업 관련 콜백을 명시적으로 재등록하지 않는 것**이 버그의 핵심 원인이다.

## 3. 최종 수정 계획

이전 계획이었던 '중복 브로드캐스트' 방식은 근본 원인을 해결하지 못하는 땜질식 처방이므로 폐기한다. 대신, `MainActivity`의 생명주기를 활용하여 콜백의 활성 상태를 보장하는 방식으로 수정한다.

### 3.1. 1단계: MainActivity 생명주기를 이용한 콜백 재등록 (권장 해결책)

`MainActivity`가 사용자에게 다시 보여지는 `onResume()` 시점에 협업 콜백을 다시 설정하여, 메시지 수신 준비 상태를 확실하게 보장한다.

*   **대상 파일**: `app/src/main/java/com/mrgq/pdfviewer/MainActivity.kt`
*   **대상 메서드**: `onResume()`
*   **수정 내용**: `onResume()` 메서드에 `setupCollaborationCallbacks()` 호출 로직을 추가한다.

```kotlin
// MainActivity.kt 수정 제안

override fun onResume() {
    super.onResume()

    // ====================[ 핵심 수정 사항 ]====================
    // 액티비티가 다시 활성화될 때마다 협업 콜백을 재등록합니다.
    // 이를 통해 PdfViewerActivity에서 돌아왔을 때 콜백 유실을 방지하고,
    // 지휘자의 파일 변경 메시지를 안정적으로 수신할 수 있습니다.
    val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    if (globalCollaborationManager.getCurrentMode() != CollaborationMode.NONE) {
        Log.d("MainActivity", "onResume: Re-registering collaboration callbacks.")
        setupCollaborationCallbacks()
    }
    // ==========================================================

    // 기존 로직은 그대로 유지
    updateCollaborationStatus()
    
    val refreshNeeded = preferences.getBoolean("refresh_file_list", false)
    if (refreshNeeded) {
        preferences.edit().putBoolean("refresh_file_list", false).apply()
        loadPdfFiles()
    }
}
```

**기대 효과:**
*   `PdfViewerActivity`에서 `MainActivity`로 돌아올 때마다 `onResume`이 호출되어 콜백이 항상 활성화된다.
*   지휘자가 두 번째 파일을 열 때 보내는 `broadcastFileChange` 메시지를 연주자의 `MainActivity`가 놓치지 않고 수신하여 처리할 수 있게 된다.
*   불필요한 중복 메시지 없이 근본 원인을 해결하는 가장 깔끔한 방법이다.

### 3.2. 2단계: 안전장치 계획 (1단계로 해결되지 않을 경우)

만약 1단계 수정만으로 해결되지 않는 복잡한 타이밍 문제가 존재할 경우, 최후의 수단으로 `PdfViewerActivity`에서 추가 브로드캐스트를 하는 이전 계획을 안전장치로 도입할 수 있다. 하지만 이는 1단계가 실패했을 때만 고려할 부차적인 옵션이다.

## 4. 테스트 및 검증 계획

수정 후, 아래 테스트 시나리오를 통해 해결 여부를 검증한다.

1.  지휘자 1명, 연주자 2명으로 합주 모드를 시작한다.
2.  **[Test 1]** 지휘자가 **파일 A**를 연다. -> 연주자 2명 모두 파일 A가 열리는지 확인한다.
3.  **[Test 2]** 지휘자가 **파일 목록**으로 돌아온다. -> 연주자 2명 모두 파일 목록으로 돌아오는지 확인한다.
4.  **[Test 3 - 핵심]** 지휘자가 **파일 B**를 연다. -> **연주자 2명 모두 파일 B가 정상적으로 열리는지 확인한다.**
5.  **[Test 4]** 지휘자가 파일 B에서 페이지를 넘긴다. -> 연주자 2명 모두 페이지가 동기화되는지 확인한다.
6.  **[Test 5]** 지휘자가 파일 B를 열어둔 상태에서, 새로운 **연주자 3**이 접속한다. -> 연주자 3에게 파일 B가 자동으로 열리는지 확인한다.

## 5. 결론

새로운 계획은 증상만 완화하는 것이 아니라, **콜백 유실이라는 근본 원인을 해결**하는 데 초점을 맞춘다. 이는 더 안정적이고 예측 가능한 동작을 보장하며, 향후 발생할 수 있는 유사한 생명주기 관련 버그를 예방하는 효과도 있다. 따라서 이 계획에 따라 수정을 진행하는 것이 가장 바람직하다.
