# 사용자 요청 기능 구현 완료

**날짜**: 2025-01-19  
**버전**: v0.1.9+  
**작업자**: Claude Code  
**상태**: ✅ 구현 완료

## 📋 구현 요약

devlog/20250719_008_feature_requests.md에 기록된 사용자 요청사항 4가지를 모두 구현 완료했습니다.

## 🚀 구현된 기능 상세

### 1. 합주 모드 페이지 동기화 개선 (우선순위: 높음)

#### 문제 상황
- v0.1.9에서 페이지 전환 애니메이션 추가 후 합주 모드 동기화 실패
- 지휘자가 페이지를 넘겨도 연주자 기기의 페이지가 넘어가지 않음
- 애니메이션 완료 리스너에 협업 브로드캐스트 코드가 누락됨

#### 구현 내용

**PdfViewerActivity.kt 수정사항:**

1. **통합 브로드캐스트 메서드 추가**
```kotlin
/**
 * 협업 모드에서 페이지 변경을 브로드캐스트합니다.
 * 중복 코드를 제거하고 일관된 로직을 제공합니다.
 */
private fun broadcastCollaborationPageChange(pageIndex: Int) {
    if (collaborationMode == CollaborationMode.CONDUCTOR && !isHandlingRemotePageChange) {
        val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
        Log.d("PdfViewerActivity", "🎵 지휘자 모드: 페이지 $actualPageNumber 브로드캐스트 중...")
        globalCollaborationManager.broadcastPageChange(actualPageNumber, pdfFileName)
    }
}

/**
 * 원격 페이지 변경을 처리하고 있는지 여부를 추적하는 플래그
 */
private var isHandlingRemotePageChange = false
```

2. **animatePageTransition() 메서드 수정**
```kotlin
private fun animatePageTransition(targetBitmap: Bitmap, direction: Int, targetIndex: Int) {
    if (isAnimating) return
    
    isAnimating = true
    
    // 페이지 넘기기 사운드 재생
    playPageTurnSound()
    
    // 협업 모드 브로드캐스트 (애니메이션 시작 전 즉시)
    broadcastCollaborationPageChange(targetIndex)
    
    // 다음 페이지 ImageView 설정
    binding.pdfViewNext.setImageBitmap(targetBitmap)
    // ... 애니메이션 코드
}
```

3. **handleRemotePageChange() 재귀 방지 개선**
```kotlin
private fun handleRemotePageChange(page: Int) {
    // Convert to 0-based index
    val targetIndex = page - 1
    
    if (targetIndex >= 0 && targetIndex < pageCount) {
        // 재귀 방지를 위해 플래그 설정
        isHandlingRemotePageChange = true
        
        Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 중...")
        showPage(targetIndex)
        
        // 플래그 해제
        isHandlingRemotePageChange = false
    }
}
```

#### 결과
- 애니메이션과 브로드캐스트 타이밍 완전 분리
- 350ms 애니메이션 지연 없이 실시간 동기화
- 재귀 호출 방지로 안정성 향상

---

### 2. 합주 모드 다중 연주자 연결 불안정 문제 해결 (우선순위: 높음)

#### 문제 상황
- 연주자 1명일 때는 정상 작동
- 연주자 2명 이상 연결 시 모든 연주자의 연결이 불안정
- 같은 모델의 TV 사용 시 중복 장치명으로 서로의 연결을 끊어버림

#### 구현 내용

**SimpleWebSocketServer.kt 수정사항:**

1. **IP 주소 기반 클라이언트 ID 생성**
```kotlin
private fun acceptConnections() {
    while (isRunning && serverSocket?.isClosed == false) {
        try {
            val clientSocket = serverSocket?.accept()
            if (clientSocket != null && isRunning) {
                // IP 주소 기반으로 클라이언트 ID 생성
                val clientAddress = clientSocket.remoteSocketAddress
                val clientIp = if (clientAddress is java.net.InetSocketAddress) {
                    clientAddress.address.hostAddress
                } else {
                    "unknown_${clientCounter.incrementAndGet()}"
                }
                val clientId = clientIp ?: "client_${clientCounter.incrementAndGet()}"
                
                Log.d(TAG, "New client connection: $clientId (IP: $clientIp)")
                
                val connection = SimpleWebSocketConnection(
                    clientSocket,
                    clientId,
                    this,
                    serverManager
                )
                // ...
            }
        }
    }
}
```

2. **브로드캐스트 안정성 개선**
```kotlin
fun broadcastMessage(message: String) {
    Log.d(TAG, "Broadcasting message to ${connections.size} clients: $message")
    
    Thread {
        val disconnectedClients = mutableListOf<String>()
        
        // 연결 목록의 복사본을 사용하여 동시성 문제 방지
        val connectionsCopy = connections.toMap()
        
        connectionsCopy.forEach { (clientId, connection) ->
            try {
                if (!connection.sendMessage(message)) {
                    disconnectedClients.add(clientId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting to client $clientId", e)
                disconnectedClients.add(clientId)
            }
        }
        
        // Remove disconnected clients
        if (disconnectedClients.isNotEmpty()) {
            disconnectedClients.forEach { clientId ->
                removeClient(clientId)
            }
        }
    }.start()
}
```

**CollaborationServerManager.kt 수정사항:**

1. **개선된 클라이언트 추가 로직**
```kotlin
@Synchronized
internal fun addClient(clientId: String, webSocket: WebSocket, deviceName: String) {
    // IP 기반 ID로 지나친 중복 처리를 방지합니다.
    // 같은 IP에서 연결이 끊기고 다시 연결될 때만 기존 연결을 제거합니다.
    val existingClient = connectedClients[clientId]
    if (existingClient != null) {
        try {
            Log.d(TAG, "Replacing existing connection for $clientId")
            existingClient.close(1000, "Replaced by new connection")
            connectedClients.remove(clientId)
            clientDeviceNames.remove(clientId)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing existing connection $clientId", e)
        }
    }
    
    // 장치 이름에 고유 식별자 추가 (선택사항)
    val uniqueDeviceName = if (deviceName == "Android TV Device" || deviceName == "Unknown Device") {
        "$deviceName ($clientId)"
    } else {
        deviceName
    }
    
    connectedClients[clientId] = webSocket
    clientDeviceNames[clientId] = uniqueDeviceName
    // ...
}
```

2. **동기화 추가**
```kotlin
@Synchronized
internal fun removeClient(clientId: String) {
    connectedClients.remove(clientId)
    val deviceName = clientDeviceNames.remove(clientId)
    
    Log.d(TAG, "Client disconnected: $clientId ($deviceName)")
    onClientDisconnected?.invoke(clientId)
}
```

#### 결과
- IP 주소 기반 식별로 중복 문제 완전 해결
- 동시성 문제 해결로 안정적인 다중 연결 지원
- 같은 모델 TV 여러 대 사용 시에도 안정적 동작

---

### 3. 페이지 정보 표시 완전 숨기기 옵션 추가 (우선순위: 중간)

#### 구현 내용

**strings.xml 추가:**
```xml
<!-- Settings -->
<string name="settings_show_page_info">페이지 정보 표시</string>
<string name="settings_show_page_info_desc">파일명과 페이지 번호를 화면 하단에 표시합니다</string>
```

**SettingsActivity.kt 수정사항:**

1. **설정 메뉴 아이템 추가**
```kotlin
private fun showAnimationSoundPanel() {
    val showPageInfo = preferences.getBoolean("show_page_info", true)
    
    val items = listOf(
        // ... 기존 아이템들
        SettingsItem(
            id = "page_info_toggle",
            icon = "📄",
            title = getString(R.string.settings_show_page_info),
            subtitle = if (showPageInfo) "표시함" else "숨김",
            type = SettingsType.TOGGLE
        )
    )
    
    showDetailPanel("애니메이션 & 사운드", items)
}
```

2. **토글 처리 메서드 추가**
```kotlin
private fun togglePageInfo() {
    val currentEnabled = preferences.getBoolean("show_page_info", true)
    val newEnabled = !currentEnabled
    
    preferences.edit().putBoolean("show_page_info", newEnabled).apply()
    
    val message = if (newEnabled) "페이지 정보가 표시됩니다" else "페이지 정보가 숨겨집니다"
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    
    hideDetailPanel()
    setupMainMenu()
}
```

**PdfViewerActivity.kt 수정사항:**

1. **setupUI() 메서드에서 설정 확인**
```kotlin
private fun setupUI() {
    binding.pdfView.isFocusable = true
    binding.pdfView.isFocusableInTouchMode = true
    binding.pdfView.requestFocus()
    
    // 페이지 정보 표시 설정 확인
    val showPageInfo = preferences.getBoolean("show_page_info", true)
    if (!showPageInfo) {
        binding.pageInfo.visibility = View.GONE
    } else {
        // Hide page info after a few seconds
        binding.pageInfo.postDelayed({
            binding.pageInfo.animate().alpha(0.3f).duration = 500
        }, 3000)
    }
}
```

2. **페이지 정보 표시 부분 수정**
```kotlin
// Show page info briefly if enabled
if (preferences.getBoolean("show_page_info", true)) {
    binding.pageInfo.animate().alpha(1f).duration = 200
    binding.pageInfo.postDelayed({
        binding.pageInfo.animate().alpha(0.3f).duration = 500
    }, 2000)
}
```

#### 결과
- 설정에서 페이지 정보 표시/숨김 선택 가능
- 설정 즉시 적용 (앱 재시작 불필요)
- 악보 연주 시 방해 요소 제거 가능

---

### 4. 페이지 넘기기 애니메이션 속도 조절 기능 추가 (우선순위: 중간)

#### 구현 내용

**strings.xml 추가:**
```xml
<string name="settings_animation_speed">애니메이션 속도</string>
<string name="settings_animation_speed_desc">페이지 넘기기 애니메이션 속도를 조절합니다</string>
```

**SettingsActivity.kt 수정사항:**

1. **설정 메뉴 아이템 추가**
```kotlin
SettingsItem(
    id = "animation_speed",
    icon = "⏱️",
    title = getString(R.string.settings_animation_speed),
    subtitle = getAnimationSpeedText(),
    type = SettingsType.INPUT
)
```

2. **애니메이션 속도 관련 메서드 추가**
```kotlin
private fun getAnimationSpeedText(): String {
    val duration = preferences.getLong("page_animation_duration", 350L)
    return when (duration) {
        0L -> "즉시"
        200L -> "빠르게"
        350L -> "보통"
        500L -> "느리게"
        800L -> "매우 느리게"
        else -> "${duration}ms"
    }
}

private fun showAnimationSpeedDialog() {
    val currentDuration = preferences.getLong("page_animation_duration", 350L)
    val speeds = arrayOf("즉시 (0ms)", "빠르게 (200ms)", "보통 (350ms)", "느리게 (500ms)", "매우 느리게 (800ms)")
    val durations = longArrayOf(0L, 200L, 350L, 500L, 800L)
    
    var selectedIndex = durations.indexOf(currentDuration)
    if (selectedIndex == -1) selectedIndex = 2 // 기본값 "보통"
    
    AlertDialog.Builder(this)
        .setTitle("페이지 넘기기 애니메이션 속도")
        .setSingleChoiceItems(speeds, selectedIndex) { dialog, which ->
            val newDuration = durations[which]
            preferences.edit().putLong("page_animation_duration", newDuration).apply()
            
            val message = "애니메이션 속도가 변경되었습니다: ${speeds[which]}"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            
            dialog.dismiss()
            hideDetailPanel()
            setupMainMenu()
        }
        .setNegativeButton("취소", null)
        .show()
}
```

**PdfViewerActivity.kt 수정사항:**

```kotlin
// 애니메이션 설정 (사용자 설정 적용)
val animationDuration = preferences.getLong("page_animation_duration", 350L)

if (animationDuration == 0L) {
    // 애니메이션 없이 즉시 전환
    binding.pdfView.setImageBitmap(targetBitmap)
    setImageViewMatrix(targetBitmap, binding.pdfView)
    binding.pdfView.translationX = 0f
    binding.pdfViewNext.visibility = View.GONE
    
    // 페이지 인덱스 업데이트
    pageIndex = targetIndex
    updatePageInfo()
    binding.loadingProgress.visibility = View.GONE
    saveLastPageNumber(targetIndex + 1)
    
    // 페이지 정보 표시
    if (preferences.getBoolean("show_page_info", true)) {
        binding.pageInfo.animate().alpha(1f).duration = 200
        Handler(Looper.getMainLooper()).postDelayed({
            binding.pageInfo.animate().alpha(0f).duration = 1000
        }, 1500)
    }
    
    isAnimating = false
    return
}

currentPageAnimator.duration = animationDuration
nextPageAnimator.duration = animationDuration
```

#### 결과
- 5가지 프리셋 속도 제공
- "즉시" 선택 시 애니메이션 완전 비활성화
- TV 하드웨어 성능에 맞춰 조절 가능

---

## 🔧 기술적 개선사항

### SharedPreferences 키 정리
- `show_page_info`: 페이지 정보 표시 여부 (Boolean, 기본값: true)
- `page_animation_duration`: 애니메이션 지속 시간 (Long, 기본값: 350L)

### 동시성 문제 해결
- `@Synchronized` 어노테이션 사용
- 연결 목록 복사본 사용으로 ConcurrentModificationException 방지
- 플래그를 이용한 재귀 호출 방지

### TV 최적화 고려사항
- 모든 설정은 리모컨으로 조작 가능
- 즉각적인 피드백 (Toast 메시지)
- 설정 변경 시 즉시 적용

---

## 📊 테스트 시나리오

### 합주 모드 테스트
1. 지휘자 모드 활성화
2. 연주자 2명 이상 연결
3. 페이지 전환 시 모든 연주자에게 즉시 동기화 확인
4. 연결 안정성 확인 (연결/해제 반복)

### 설정 기능 테스트
1. 페이지 정보 숨김 설정 후 PDF 열기
2. 애니메이션 "즉시" 설정 후 페이지 전환
3. 각 애니메이션 속도별 전환 확인

---

## 🎯 향후 개선 가능성

1. **애니메이션 속도 커스텀 설정**: 슬라이더를 이용한 세밀한 조정
2. **애니메이션 타입 선택**: 슬라이드 외 페이드, 플립 등 다양한 전환 효과
3. **페이지 정보 위치 선택**: 상단/하단/좌측/우측 선택 가능
4. **합주 모드 연결 상태 표시**: 연결된 연주자 목록 실시간 표시

---

## 📝 참고사항

- 모든 수정사항은 기존 코드와의 호환성을 유지
- TV 리모컨 조작에 최적화
- 사용자 경험을 해치지 않는 선에서 기능 추가
- 성능에 미치는 영향 최소화