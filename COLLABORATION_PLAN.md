# 🤝 협업 기능 구현 계획서

## 📋 프로젝트 개요

**목표**: Android TV 기기 간 실시간 PDF 페이지 동기화 시스템  
**사용 사례**: 앙상블/합주에서 지휘자가 모든 연주자의 악보 페이지를 동기화  
**기술 스택**: WebSocket + JSON 메시지 프로토콜  
**용어**: Conductor-Performer 패턴 (포용적 언어 사용)

---

## 🏗️ 시스템 아키텍처

### 네트워크 구조
```
지휘자 기기 (Conductor)
    ↓ WebSocket 서버 (포트 9090)
    ↓ 브로드캐스트: {"action":"page_change","page":5}
┌─────────────────────────────────────────┐
│  연주자1      연주자2      연주자3        │
│ (바이올린)     (비올라)      (첼로)       │
│ WebSocket     WebSocket    WebSocket    │
│ 클라이언트     클라이언트     클라이언트    │
└─────────────────────────────────────────┘
```

### 역할 분담
- **지휘자 기기**: WebSocket 서버 + PDF 뷰어 + 제어 권한
- **연주자 기기**: WebSocket 클라이언트 + PDF 뷰어 + 수신 전용

---

## 🔧 구현 단계별 계획

### Phase 1: WebSocket 기반 구조 (1-2일)

#### 1.1 WebSocket 서버 구현
```kotlin
// CollaborationServerManager.kt
class CollaborationServerManager {
    private var webSocketServer: CollaborationWebSocketServer? = null
    private val connectedClients = mutableListOf<WebSocket>()
    
    fun startServer(port: Int = 9090): Boolean {
        return try {
            webSocketServer = CollaborationWebSocketServer(port, this)
            webSocketServer?.start()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun broadcastPageChange(pageNumber: Int, fileName: String) {
        val message = JsonObject().apply {
            addProperty("action", "page_change")
            addProperty("page", pageNumber)
            addProperty("file", fileName)
            addProperty("timestamp", System.currentTimeMillis())
        }
        broadcastToClients(message.toString())
    }
}
```

#### 1.2 WebSocket 클라이언트 구현
```kotlin
// CollaborationClientManager.kt
class CollaborationClientManager(
    private val onPageChangeReceived: (Int, String) -> Unit
) {
    private var webSocketClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    
    fun connectToMaster(masterIpAddress: String, port: Int = 9090): Boolean {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("ws://$masterIpAddress:$port")
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }
        })
        
        return true
    }
    
    private fun handleIncomingMessage(message: String) {
        try {
            val json = JsonParser.parseString(message).asJsonObject
            when (json.get("action").asString) {
                "page_change" -> {
                    val page = json.get("page").asInt
                    val file = json.get("file").asString
                    onPageChangeReceived(page, file)
                }
            }
        } catch (e: Exception) {
            Log.e("CollaborationClient", "Failed to parse message: $message", e)
        }
    }
}
```

### Phase 2: 설정 UI 및 연결 관리 (1일)

#### 2.1 설정 화면 확장
```xml
<!-- activity_settings.xml에 추가 -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:layout_marginTop="32dp">
    
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="협업 모드"
        android:textColor="@color/tv_text_primary"
        android:textSize="@dimen/subtitle_text_size"
        android:textStyle="bold" />
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="16dp">
        
        <Button
            android:id="@+id/masterModeBtn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="마스터 모드 (지휘자)"
            android:backgroundTint="@color/tv_primary" />
        
        <Button
            android:id="@+id/slaveModeBtn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="슬레이브 모드 (연주자)"
            android:backgroundTint="@color/tv_surface"
            android:layout_marginStart="16dp" />
    </LinearLayout>
    
    <TextView
        android:id="@+id/collaborationStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="협업 모드: 비활성"
        android:textColor="@color/tv_text_secondary"
        android:layout_marginTop="16dp" />
</LinearLayout>
```

#### 2.2 TV 친화적 연결 시스템

**방법 1: IP 주소 직접 입력**
```kotlin
// 마스터 IP 주소 표시 및 입력
class NetworkConnectionManager {
    fun getLocalIpAddress(): String {
        // 현재 기기의 IP 주소 반환
        return getCurrentWifiIpAddress()
    }
    
    fun displayConnectionInfo(): String {
        val ip = getLocalIpAddress()
        val port = 9090
        return "연결 주소: $ip:$port"
    }
}
```

**방법 2: 네트워크 자동 검색**
```kotlin
// 같은 네트워크에서 마스터 기기 자동 발견
class MasterDiscoveryManager {
    fun scanForMasters(callback: (List<MasterDevice>) -> Unit) {
        // UDP 브로드캐스트로 마스터 기기 검색
        val subnet = getNetworkSubnet()
        for (ip in subnet) {
            checkIfMaster(ip) { isMaster, deviceName ->
                if (isMaster) {
                    callback(MasterDevice(ip, deviceName))
                }
            }
        }
    }
}
```

**방법 3: 웹 인터페이스 연결**
```kotlin
// 기존 웹서버(8080)를 통한 연결 설정
class WebBasedConnection {
    fun addCollaborationEndpoint() {
        // GET /collaboration/master_info
        // 마스터 기기 정보 반환
        
        // POST /collaboration/join
        // 슬레이브 기기 연결 요청
    }
}
```

### Phase 3: PdfViewerActivity 통합 (0.5일)

#### 3.1 협업 모드 감지 및 초기화
```kotlin
// PdfViewerActivity.kt 수정
class PdfViewerActivity : AppCompatActivity() {
    private var collaborationMode: String = "none" // "master", "slave", "none"
    private var collaborationServerManager: CollaborationServerManager? = null
    private var collaborationClientManager: CollaborationClientManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 기존 코드...
        
        initializeCollaborationMode()
    }
    
    private fun initializeCollaborationMode() {
        val prefs = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        collaborationMode = prefs.getString("collaboration_mode", "none") ?: "none"
        
        when (collaborationMode) {
            "master" -> {
                collaborationServerManager = CollaborationServerManager()
                val serverStarted = collaborationServerManager?.startServer()
                if (serverStarted == true) {
                    Log.d("Collaboration", "Master mode: Server started")
                }
            }
            "slave" -> {
                val masterIp = prefs.getString("master_ip", "") ?: ""
                if (masterIp.isNotEmpty()) {
                    collaborationClientManager = CollaborationClientManager { page, file ->
                        runOnUiThread {
                            handleRemotePageChange(page, file)
                        }
                    }
                    collaborationClientManager?.connectToMaster(masterIp)
                }
            }
        }
    }
}
```

#### 3.2 페이지 변경 이벤트 처리
```kotlin
// 기존 showPage 함수 수정
private fun showPage(index: Int) {
    if (index < 0 || index >= pageCount) return
    
    // 기존 페이지 렌더링 코드...
    
    // 협업 모드에서 페이지 변경 브로드캐스트
    if (collaborationMode == "master") {
        collaborationServerManager?.broadcastPageChange(index + 1, pdfFileName)
    }
}

// 원격 페이지 변경 처리
private fun handleRemotePageChange(page: Int, fileName: String) {
    // 현재 파일과 동일한지 확인
    if (fileName == pdfFileName) {
        // 슬레이브 모드에서는 브로드캐스트하지 않도록 플래그 설정
        val originalMode = collaborationMode
        collaborationMode = "none" // 임시로 비활성화
        
        showPage(page - 1) // 0-based index로 변환
        
        collaborationMode = originalMode // 복원
    }
}
```

### Phase 4: 연결 관리 및 UI 피드백 (0.5일)

#### 4.1 연결 상태 표시
```kotlin
// 연결된 클라이언트 수 표시 (마스터 모드)
private fun updateCollaborationStatus() {
    val connectedCount = collaborationServerManager?.getConnectedClientCount() ?: 0
    
    runOnUiThread {
        when (collaborationMode) {
            "master" -> {
                binding.collaborationStatus.text = "마스터 모드: ${connectedCount}명 연결됨"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            "slave" -> {
                val isConnected = collaborationClientManager?.isConnected() ?: false
                binding.collaborationStatus.text = if (isConnected) "슬레이브 모드: 연결됨" else "슬레이브 모드: 연결 끊김"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            else -> {
                binding.collaborationStatus.visibility = View.GONE
            }
        }
    }
}
```

#### 4.2 네트워크 끊김 재연결
```kotlin
class CollaborationClientManager {
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    private fun attemptReconnection() {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            Handler(Looper.getMainLooper()).postDelayed({
                connectToMaster(masterIpAddress)
            }, 2000 * reconnectAttempts) // 지수 백오프
        }
    }
}
```

---

## 📱 사용자 시나리오

### 마스터 기기 설정 (지휘자)
1. **설정 진입**: 메인 화면 → 설정 → 협업 모드
2. **마스터 모드 활성화**: "마스터 모드 (지휘자)" 버튼 클릭
3. **IP 주소 표시**: "연결 주소: 192.168.1.100:9090" 화면에 표시
4. **연결 대기**: "0명 연결됨" 상태 표시
5. **PDF 열기**: 평소와 같이 PDF 파일 선택
6. **페이지 이동**: 리모컨으로 페이지 이동 시 자동 동기화

### 슬레이브 기기 설정 (연주자)

**방법 A: IP 직접 입력**
1. **설정 진입**: 메인 화면 → 설정 → 협업 모드
2. **슬레이브 모드 활성화**: "슬레이브 모드 (연주자)" 버튼 클릭
3. **IP 입력**: 리모컨으로 마스터 IP 주소 입력 (예: 192.168.1.100)
4. **연결 시도**: 자동으로 WebSocket 연결 시도
5. **PDF 열기**: 마스터와 동일한 파일 열기 (수동)
6. **자동 동기화**: 마스터의 페이지 변경에 따라 자동 이동

**방법 B: 자동 검색**
1. **설정 진입**: 메인 화면 → 설정 → 협업 모드
2. **슬레이브 모드 활성화**: "슬레이브 모드 (연주자)" 버튼 클릭
3. **마스터 검색**: "마스터 검색" 버튼으로 네트워크 스캔
4. **마스터 선택**: 검색된 마스터 기기 목록에서 선택
5. **자동 연결**: 선택한 마스터에 자동 연결
6. **동기화 시작**: 마스터의 페이지와 자동 동기화

**방법 C: 웹 인터페이스 (모바일 보조)**
1. **마스터 웹서버 접속**: 모바일에서 `http://마스터IP:8080/collaboration`
2. **연결 코드 생성**: 웹에서 6자리 연결 코드 생성
3. **코드 입력**: 슬레이브 TV에서 연결 코드 입력
4. **자동 연결**: 코드 검증 후 자동 연결

---

## 🔧 메시지 프로토콜

### 기본 메시지 구조
```json
{
  "action": "page_change",
  "page": 5,
  "file": "symphony_no5.pdf",
  "timestamp": 1699123456789,
  "master_id": "device_12345"
}
```

### 메시지 타입별 상세

#### 1. 페이지 변경 (page_change)
```json
{
  "action": "page_change",
  "page": 5,
  "file": "symphony_no5.pdf",
  "two_page_mode": true,
  "timestamp": 1699123456789
}
```

#### 2. 클라이언트 연결 (client_connect)
```json
{
  "action": "client_connect",
  "device_id": "device_67890",
  "device_name": "바이올린_악보대",
  "app_version": "v0.1.5"
}
```

#### 3. 연결 응답 (connect_response)
```json
{
  "action": "connect_response",
  "status": "success",
  "master_id": "device_12345",
  "current_file": "symphony_no5.pdf",
  "current_page": 3
}
```

#### 4. 파일 변경 (file_change)
```json
{
  "action": "file_change",
  "file": "concerto_no1.pdf",
  "page": 1,
  "timestamp": 1699123456789
}
```

#### 5. 하트비트 (heartbeat)
```json
{
  "action": "heartbeat",
  "timestamp": 1699123456789
}
```

---

## 🛠️ 구현 상세사항

### 의존성 추가 (app/build.gradle.kts)
```kotlin
dependencies {
    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON 처리
    implementation("com.google.code.gson:gson:2.10.1")
    
    // 네트워크 상태 체크
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // 네트워크 검색
    implementation("androidx.core:core-ktx:1.12.0")
}
```

### 권한 추가 (AndroidManifest.xml)
```xml
<!-- 카메라 권한 제거 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

### 설정 저장 구조
```kotlin
// SharedPreferences 키
const val PREF_COLLABORATION_MODE = "collaboration_mode" // "master", "slave", "none"
const val PREF_MASTER_IP = "master_ip"
const val PREF_COLLABORATION_PORT = "collaboration_port" // 기본 9090
const val PREF_DEVICE_NAME = "device_name" // "바이올린_악보대"
```

---

## 🔍 테스트 계획

### 단위 테스트
1. **메시지 직렬화/역직렬화**
2. **WebSocket 연결/해제**
3. **페이지 동기화 로직**

### 통합 테스트
1. **마스터-슬레이브 연결**
2. **페이지 변경 브로드캐스트**
3. **네트워크 끊김 재연결**
4. **다중 클라이언트 동기화**

### 실제 환경 테스트
1. **2-3대 Android TV 기기**
2. **같은 Wi-Fi 네트워크**
3. **다양한 PDF 파일 크기**
4. **네트워크 지연 시뮬레이션**

---

## ⚠️ 주의사항 및 제약

### 네트워크 요구사항
- 모든 기기가 같은 Wi-Fi 네트워크에 연결
- 방화벽에서 9090 포트 허용
- 네트워크 지연 시간 < 100ms 권장

### 보안 고려사항
- 암호화되지 않은 WebSocket (로컬 네트워크만)
- 기기 인증 없음 (QR 코드 기반 신뢰)
- 마스터 권한 검증 없음

### 성능 제약
- 최대 동시 연결: 10대 기기
- 메시지 크기: 1KB 미만
- 브로드캐스트 지연: < 50ms

---

## 🎯 예상 개발 일정

| 단계 | 소요 시간 | 주요 작업 |
|------|-----------|----------|
| Phase 1 | 2일 | WebSocket 서버/클라이언트 구현 |
| Phase 2 | 1일 | 설정 UI 및 QR 코드 시스템 |
| Phase 3 | 0.5일 | PdfViewerActivity 통합 |
| Phase 4 | 0.5일 | 연결 관리 및 UI 피드백 |
| **총합** | **4일** | **완전한 협업 시스템** |

---

## 🚀 향후 확장 계획

### v0.2.0 고급 협업 기능
- 파트별 악보 지원 (바이올린1, 바이올린2, 비올라...)
- 메타데이터 동기화 (템포, 키 시그니처)
- 음성 채팅 기능

### v0.3.0 클라우드 협업
- 인터넷 기반 원격 협업
- 암호화된 연결
- 사용자 계정 시스템

이 계획서를 바탕으로 협업 기능 구현을 시작하시겠습니까?