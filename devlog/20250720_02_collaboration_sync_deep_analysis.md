# 합주 모드 파일 동기화 버그 심층 분석

**날짜**: 2025-07-20  
**작성자**: Claude (AI Assistant)  
**상태**: ✅ 분석 완료

## 1. 문제 상황 재확인

합주 모드에서 지휘자가 첫 번째 파일을 열면 모든 연주자 기기에서 정상적으로 파일이 열리고 페이지 동기화도 잘 작동한다. 이후 지휘자가 파일 목록으로 돌아온 후 **두 번째 파일을 열면 연주자 기기들은 반응하지 않고 파일 목록 화면에 그대로 머무르는 버그**가 발생한다.

## 2. 코드 분석을 통한 실제 동작 흐름

### 2.1. 각 시점별 메시지 브로드캐스트 현황

#### 📍 시점 1: 지휘자가 파일 목록에서 파일 선택 (MainActivity)

```kotlin
// MainActivity.kt:356-362
private fun openPdfFile(pdfFile: PdfFile, position: Int) {
    // ... 
    // 지휘자 모드에서 파일 변경 브로드캐스트
    val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    if (globalCollaborationManager.getCurrentMode() == CollaborationMode.CONDUCTOR) {
        Log.d("MainActivity", "🎵 지휘자 모드: 파일 선택 브로드캐스트 - ${pdfFile.name}")
        globalCollaborationManager.addFileToServer(pdfFile.name, pdfFile.path)
        globalCollaborationManager.broadcastFileChange(pdfFile.name, 1) // ✅ 브로드캐스트 발생
    }
    // ...
}
```

**결과**: MainActivity에서 파일을 선택할 때마다 `file_change` 메시지가 브로드캐스트됨

#### 📍 시점 2: PdfViewerActivity 초기화

```kotlin
// PdfViewerActivity.kt - initializeCollaboration()
private fun initializeCollaboration() {
    collaborationMode = globalCollaborationManager.getCurrentMode()
    
    when (collaborationMode) {
        CollaborationMode.CONDUCTOR -> {
            setupConductorCallbacks()  // ❌ 여기서는 브로드캐스트 없음
            updateCollaborationStatus()
        }
        CollaborationMode.PERFORMER -> {
            setupPerformerCallbacks()
            updateCollaborationStatus()
        }
        // ...
    }
}
```

**결과**: PdfViewerActivity가 시작될 때는 브로드캐스트가 발생하지 않음

#### 📍 시점 3: 새 연주자가 연결될 때만 브로드캐스트

```kotlin
// PdfViewerActivity.kt - setupConductorCallbacks()
private fun setupConductorCallbacks() {
    globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
        runOnUiThread {
            // ... 
            // 새로 연결된 연주자에게만 현재 파일 정보 전송
            globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
            val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
            globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber) // ✅ 새 연주자에게만
        }
    }
    // ...
}
```

**결과**: 이미 연결된 연주자들에게는 브로드캐스트되지 않음

### 2.2. 연주자 측 메시지 처리 구조

#### 📍 MainActivity의 file_change 핸들러

```kotlin
// MainActivity.kt - setupCollaborationCallbacks()
globalCollaborationManager.setOnFileChangeReceived { fileName, page ->
    runOnUiThread {
        handleRemoteFileChange(fileName, page)  // ✅ 파일 찾아서 열기
    }
}
```

#### 📍 PdfViewerActivity의 file_change 핸들러

```kotlin
// PdfViewerActivity.kt - setupPerformerCallbacks()
globalCollaborationManager.setOnFileChangeReceived { file, page ->
    runOnUiThread {
        handleRemoteFileChange(file, page)  // ✅ 현재 파일과 다르면 전환
    }
}
```

### 2.3. 실제 문제 시나리오 분석

1. **첫 번째 파일 열기 (정상 동작)**
   - 지휘자: MainActivity → `broadcastFileChange("file1.pdf", 1)` 
   - 연주자: MainActivity에서 수신 → `handleRemoteFileChange` → PdfViewerActivity 열기 ✅

2. **파일 목록으로 돌아가기**
   - 지휘자: PdfViewerActivity 종료 → MainActivity
   - 연주자: 동일하게 PdfViewerActivity 종료 → MainActivity ✅

3. **두 번째 파일 열기 (문제 발생 가능성)**
   - 지휘자: MainActivity → `broadcastFileChange("file2.pdf", 1)`
   - 연주자: ?

## 3. 문제의 실제 원인 분석

### 3.1. 기존 문서(20250720_01)의 분석 평가

기존 문서는 "PdfViewerActivity 시작 시 브로드캐스트가 없다"고 분석했지만, **실제로는 MainActivity에서 이미 브로드캐스트를 하고 있음**.

### 3.2. 실제 문제 원인 후보

#### 🔍 원인 1: 타이밍 이슈
- MainActivity에서 브로드캐스트를 보내지만, 연주자 측에서 제대로 수신/처리하지 못할 가능성
- 네트워크 지연이나 메시지 유실 가능성

#### 🔍 원인 2: 콜백 등록 문제
- 연주자가 MainActivity로 돌아왔을 때 콜백이 제대로 재등록되지 않을 가능성
- GlobalCollaborationManager의 콜백이 덮어씌워지거나 해제될 가능성

#### 🔍 원인 3: 복잡한 파일 전환 로직
```kotlin
// PdfViewerActivity.kt - handleRemoteFileChange
private fun handleRemoteFileChange(file: String, targetPage: Int) {
    val fileIndex = fileNameList.indexOf(file)
    
    if (fileIndex >= 0 && fileIndex < filePathList.size) {
        // 복잡한 파일 전환 로직
        currentFileIndex = fileIndex
        pdfFilePath = filePathList[fileIndex]
        pdfFileName = fileNameList[fileIndex]
        
        // 협업 모드를 임시로 NONE으로 변경 (문제 가능성)
        val originalMode = collaborationMode
        collaborationMode = CollaborationMode.NONE
        
        loadFileWithTargetPage(pdfFilePath, pdfFileName, targetPage, originalMode)
    }
    // ...
}
```

이 로직의 문제점:
- PdfViewerActivity 내에서 파일을 재로드하는 복잡한 구조
- 협업 모드를 임시로 변경하는 것이 예상치 못한 부작용 발생 가능

## 4. 해결 방안 비교

### 4.1. 기존 문서의 제안 (추가 브로드캐스트)

```kotlin
// PdfViewerActivity.kt의 initializeCollaboration() 수정
private fun initializeCollaboration() {
    // ...
    when (collaborationMode) {
        CollaborationMode.CONDUCTOR -> {
            // 액티비티 시작 시마다 현재 파일 정보 브로드캐스트
            globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
            val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
            globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber)
            
            setupConductorCallbacks()
            updateCollaborationStatus()
        }
        // ...
    }
}
```

**장점**:
- 간단하고 직관적
- 확실한 동기화 보장
- 기존 로직을 크게 변경하지 않음

**단점**:
- 중복 브로드캐스트 발생 (MainActivity와 PdfViewerActivity에서 각각)
- 근본 원인을 해결하지 않고 우회하는 방식

### 4.2. 대안 1: 간단한 Activity 전환 방식

```kotlin
// PdfViewerActivity.kt의 handleRemoteFileChange 수정
private fun handleRemoteFileChange(file: String, targetPage: Int) {
    if (file != pdfFileName) {
        // 다른 파일로 변경 시 MainActivity로 돌아가서 처리
        Log.d("PdfViewerActivity", "🎼 다른 파일 요청받음. MainActivity로 돌아가서 처리")
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("requested_file", file)
            putExtra("requested_page", targetPage)
        }
        startActivity(intent)
        finish()
    }
    // 같은 파일이면 페이지만 변경
}
```

**장점**:
- 깔끔하고 단순한 구조
- Activity 생명주기를 활용한 자연스러운 전환
- 복잡한 파일 재로드 로직 제거

**단점**:
- Activity 전환으로 인한 화면 깜빡임 가능
- 상태 전환이 사용자에게 노출됨

### 4.3. 대안 2: 콜백 재등록 보장

```kotlin
// MainActivity.kt - onResume() 수정
override fun onResume() {
    super.onResume()
    
    // 협업 콜백 재등록 보장
    if (globalCollaborationManager.getCurrentMode() != CollaborationMode.NONE) {
        setupCollaborationCallbacks()  // 콜백 재등록
    }
    
    updateCollaborationStatus()
    loadPdfFiles()
}
```

**장점**:
- 콜백 유실 문제 해결
- 최소한의 코드 변경

**단점**:
- 문제의 근본 원인이 콜백 유실이 아닐 경우 효과 없음

## 5. 권장 해결 방안

### 5.1. 단기 해결책 (즉시 적용 가능)

**기존 문서의 제안을 적용**하는 것이 가장 안전하고 빠른 해결책입니다:
- PdfViewerActivity 시작 시 추가 브로드캐스트
- 중복 메시지는 연주자 측에서 무시 가능
- 즉시 테스트 가능

### 5.2. 장기 개선 방향

1. **메시지 전달 보장 시스템**
   - ACK 기반 메시지 전달 확인
   - 재전송 메커니즘

2. **상태 동기화 개선**
   - 정기적인 상태 동기화 체크
   - 연결 복구 시 자동 상태 맞춤

3. **아키텍처 단순화**
   - PdfViewerActivity 내 파일 전환 로직 제거
   - MainActivity 중심의 단순한 파일 관리

## 6. 테스트 시나리오

문제 해결 후 다음 시나리오를 철저히 테스트해야 합니다:

1. **기본 시나리오**
   - 파일 A 열기 → 목록 → 파일 B 열기
   - 모든 연주자 동기화 확인

2. **네트워크 지연 시나리오**
   - 인위적 네트워크 지연 상황에서 테스트
   - 메시지 유실 시뮬레이션

3. **복잡한 전환 시나리오**
   - 빠른 파일 전환 (A → B → C)
   - 동시 다발적 액션 처리

4. **엣지 케이스**
   - 연주자가 수동으로 다른 파일 열기
   - 연결 끊김/재연결 상황

## 7. 결론

현재 버그는 복잡한 상호작용으로 인해 발생하는 것으로 보이며, 기존 문서의 해결책이 실용적이고 효과적입니다. 단, 장기적으로는 더 근본적인 아키텍처 개선이 필요할 수 있습니다.

중복 브로드캐스트의 부작용보다는 동기화 실패의 문제가 더 크므로, 안전한 방향으로 해결하는 것이 좋습니다.