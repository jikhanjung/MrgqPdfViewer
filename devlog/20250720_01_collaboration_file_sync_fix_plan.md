# 합주 모드 파일 동기화 버그 수정 계획

**날짜**: 2025-07-19  
**작성자**: Gemini (AI Assistant)  
**상태**: 🟡 계획 수립

## 1. 문제 상황

합주 모드에서 지휘자가 첫 번째 파일을 열면 모든 연주자 기기에서 정상적으로 파일이 열리고 페이지 동기화도 잘 작동한다. 이후 지휘자가 파일 목록으로 돌아오면 연주자들도 정상적으로 목록으로 돌아온다.

하지만, 지휘자가 목록에서 **두 번째 파일을 열면 연주자 기기들은 반응하지 않고 파일 목록 화면에 그대로 머무르는 버그**가 발생한다.

## 2. 근본 원인 분석 (코드 기반)

문제의 직접적인 원인은 `PdfViewerActivity.kt`의 `setupConductorCallbacks()` 메서드 내의 로직 때문이다.

```kotlin
// PdfViewerActivity.kt -> setupConductorCallbacks()

globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
    runOnUiThread {
        // ... Toast, 상태 업데이트 등 ...
        
        // 현재 파일 정보를 브로드캐스트하는 로직
        globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
        val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
        globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber)
    }
}
```

위 코드에서 `broadcastFileChange` (파일 열기 알림) 메시지는 **오직 새로운 연주자가 지휘자에게 접속했을 때(`setOnServerClientConnected`)만 호출**된다.

따라서 지휘자가 이미 연결된 연주자들과 세션을 유지한 채로 두 번째 파일을 열 경우, 새로운 접속이 아니므로 이 콜백은 실행되지 않는다. 결과적으로 두 번째 파일에 대한 정보가 연주자들에게 전달되지 않아 동기화가 실패하는 것이다.

## 3. 수정 계획

지휘자가 `PdfViewerActivity`를 시작할 때마다(즉, 새 파일을 열 때마다) 역할 상태나 연주자 접속 상태와 관계없이 **항상 현재 파일 정보를 브로드캐스트**하도록 로직을 수정하여 일관성을 확보한다.

### 3.1. 대상 파일 및 메서드

*   **대상 파일**: `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt`
*   **대상 메서드**: `initializeCollaboration()`

### 3.2. 상세 구현 방안

`initializeCollaboration()` 메서드는 `PdfViewerActivity`가 생성될 때(`onCreate`) 항상 호출되므로, 이 메서드에 브로드캐스트 로직을 추가하는 것이 가장 적합하다.

`when (collaborationMode)` 분기문 내의 `CollaborationMode.CONDUCTOR` 케이스에 아래와 같이 코드를 추가한다.

```kotlin
// PdfViewerActivity.kt의 initializeCollaboration() 메서드 수정안

private fun initializeCollaboration() {
    collaborationMode = globalCollaborationManager.getCurrentMode()
    Log.d("PdfViewerActivity", "Collaboration mode: $collaborationMode")
    
    when (collaborationMode) {
        CollaborationMode.CONDUCTOR -> {
            // ====================[ 핵심 수정 사항 ]====================
            // 액티비티가 시작될 때마다 현재 파일 정보를 브로드캐스트합니다.
            // 이를 통해 두 번째, 세 번째 파일을 열 때도 동기화가 보장됩니다.
            Log.d("PdfViewerActivity", "🎵 지휘자 모드: 현재 파일($pdfFileName, page ${pageIndex + 1}) 정보 브로드캐스트")
            
            // 1. 연주자가 파일을 다운로드할 수 있도록 파일 서버에 파일 추가
            globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
            
            // 2. 파일 변경 메시지 브로드캐스트
            val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
            globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber)
            // ==========================================================

            // 기존 콜백 설정은 그대로 유지하여, 중간에 새로 참여하는 연주자를 처리합니다.
            setupConductorCallbacks() 
            updateCollaborationStatus()
        }
        CollaborationMode.PERFORMER -> {
            setupPerformerCallbacks()
            updateCollaborationStatus()
        }
        CollaborationMode.NONE -> {
            binding.collaborationStatus.visibility = View.GONE
        }
    }
}
```

### 3.3. 기대 효과

*   지휘자가 파일을 열 때마다 `initializeCollaboration()`이 호출되어 `broadcastFileChange` 메시지가 안정적으로 전송된다.
*   첫 번째 파일뿐만 아니라 두 번째 이후의 모든 파일이 연주자 기기에서 정상적으로 열리게 된다.
*   기존의 `setOnServerClientConnected` 콜백 로직은 그대로 유지되므로, 세션 중간에 합류하는 새로운 연주자에 대한 동기화 기능도 문제없이 작동한다.

## 4. 테스트 및 검증 계획

1.  지휘자 1명, 연주자 2명으로 합주 모드를 시작한다.
2.  **[Test 1]** 지휘자가 **파일 A**를 연다. -> 연주자 2명 모두 파일 A가 열리는지 확인한다.
3.  **[Test 2]** 지휘자가 페이지를 넘긴다. -> 연주자 2명 모두 페이지가 동기화되는지 확인한다.
4.  **[Test 3]** 지휘자가 뒤로가기로 **파일 목록**으로 돌아온다. -> 연주자 2명 모두 파일 목록으로 돌아오는지 확인한다.
5.  **[Test 4 - 핵심]** 지휘자가 **파일 B**를 연다. -> 연주자 2명 모두 파일 B가 정상적으로 열리는지 확인한다.
6.  **[Test 5]** 지휘자가 파일 B에서 페이지를 넘긴다. -> 연주자 2명 모두 페이지가 동기화되는지 확인한다.
7.  **[Test 6]** 지휘자가 파일 B를 열어둔 상태에서, 새로운 **연주자 3**이 접속한다. -> 연주자 3에게 파일 B가 자동으로 열리는지 확인한다.

위 테스트 시나리오를 모두 통과하면 버그가 완전히 해결된 것으로 간주한다.
