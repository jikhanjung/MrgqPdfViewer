# 🔌 포트 바인딩 해제 문제 분석 및 해결 방안

## 📋 문제 현상
- **주요 증상**: 앱 강제 종료 후 지휘자 모드 재활성화 불가
- **포트**: 9090 (WebSocket 협업 서버)
- **에러**: "Address already in use" 또는 포트 바인딩 실패
- **임시 해결책**: 앱 완전 삭제 후 재설치 (매우 불편함)
- **문제 발생 시점**: 2025-07-06 협업 모드 구현 후 발견
- **해결 상태**: ⚠️ **미확인** - 소켓 타임아웃 구현 완료, 실제 효과 검증 필요

---

## 🎯 예상 원인 분석 (우선순위별)

### 🔴 **1순위: Accept 스레드 블로킹 상태**
**원인:**
- `ServerSocket.accept()`가 무한 대기 상태에서 블로킹됨
- 앱 강제 종료 시 Accept 스레드가 정상적으로 종료되지 않음
- 스레드가 살아있는 동안 포트가 계속 점유됨

**증거:**
```kotlin
// 기존 문제 코드 (SimpleWebSocketServer.kt)
while (isRunning && serverSocket?.isClosed == false) {
    val clientSocket = serverSocket?.accept() // ← 여기서 무한 대기
    // 앱 강제 종료 시 이 라인에서 영원히 블로킹됨
}
```

**기술적 배경:**
- TCP 소켓의 `accept()` 메서드는 기본적으로 블로킹 I/O
- 클라이언트 연결이 없으면 무한정 대기
- Android에서 앱 강제 종료 시 스레드 즉시 종료 보장 안됨

### 🟠 **2순위: TCP 소켓 TIME_WAIT 상태**
**원인:**
- TCP 연결 종료 시 소켓이 TIME_WAIT 상태로 전환
- RFC 793에 따라 일반적으로 2분 정도 포트가 점유 상태 유지
- Android에서는 더 길어질 수 있음

**시스템 레벨 동작:**
```
연결 종료 → FIN_WAIT → TIME_WAIT → 포트 해제
                        ↑
                   이 단계에서 2분간 대기
```

### 🟡 **3순위: 리소스 정리 불완전**
**원인:**
- `ServerSocket.close()` 호출 후에도 내부적으로 정리가 완료되지 않음
- `SO_REUSEADDR` 설정 부재로 즉시 재사용 불가
- JVM 가비지 컬렉션 지연으로 네이티브 리소스 해제 지연

**현재 구현 상태:**
```kotlin
// CollaborationServerManager.kt:261-273
private fun isPortAvailable(port: Int): Boolean {
    return try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true  // ← 이미 구현됨
            socket.bind(InetSocketAddress(port))
            true
        }
    } catch (e: Exception) {
        false
    }
}
```

### 🟢 **4순위: Android 시스템 레벨 이슈**
**원인:**
- Android TV OS의 포트 관리 정책
- 앱 프로세스 강제 종료와 소켓 정리 타이밍 불일치
- 시스템 레벨의 네트워크 스택 이슈
- SELinux 정책이나 네트워크 네임스페이스 관련 이슈

---

## 🛠️ 해결 방안 (우선순위별)

### ✅ **1순위: 소켓 타임아웃 구현 (구현 완료, 검증 필요)**

**구현 내용:**
```kotlin
// SimpleWebSocketServer.kt:25-29
serverSocket = ServerSocket(port).apply {
    // Set socket timeout to 1 second for better shutdown responsiveness
    soTimeout = 1000
    Log.d(TAG, "Server socket timeout set to 1000ms for better shutdown")
}

// SimpleWebSocketServer.kt:69-72
} catch (e: java.net.SocketTimeoutException) {
    // Socket timeout - this is normal, just check isRunning and continue
    // Log.v(TAG, "Accept timeout - checking shutdown signal...") // Verbose logging
    continue
```

**작동 원리:**
1. `ServerSocket.setSoTimeout(1000)`으로 1초 타임아웃 설정
2. `accept()` 호출 시 1초 후 `SocketTimeoutException` 발생
3. 예외 처리에서 `isRunning` 플래그 확인 후 루프 계속 또는 종료
4. 블로킹 I/O → 반응형 I/O 전환

**장점:**
- ✅ 간단하고 안정적인 해결책
- ✅ Accept 스레드가 1초마다 종료 신호 확인
- ✅ 표준 Java Socket API 활용
- ✅ 기존 코드 구조 유지

**단점:**
- 1초마다 타임아웃 발생 (CPU 사용량 약간 증가)
- 여전히 1초 지연 가능성

**검증 방법:**
1. 지휘자 모드 활성화
2. 앱 강제 종료 (Recent Apps에서 스와이프)
3. 즉시 앱 재시작 후 지휘자 모드 재활성화 시도
4. 성공 시 → 해결됨, 실패 시 → 추가 해결책 필요

---

### 🔄 **2순위: NIO 기반 Non-blocking I/O (미구현)**

**구현 방안:**
```kotlin
import java.nio.channels.ServerSocketChannel
import java.nio.channels.Selector
import java.nio.channels.SelectionKey

private fun acceptConnectionsNIO() {
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.bind(InetSocketAddress(port))
    
    val selector = Selector.open()
    serverChannel.register(selector, SelectionKey.OP_ACCEPT)
    
    while (isRunning) {
        if (selector.select(1000) > 0) { // 1초 타임아웃
            val keys = selector.selectedKeys()
            for (key in keys) {
                if (key.isAcceptable) {
                    val clientChannel = serverChannel.accept()
                    // 클라이언트 처리
                }
            }
            keys.clear()
        }
        // isRunning 체크 - 즉시 반응
    }
}
```

**장점:**
- 완전한 논블로킹 I/O
- 더 정밀한 제어 가능
- 성능상 이점 (다중 연결 처리)
- CPU 사용량 최적화

**단점:**
- 구현 복잡도 높음
- 기존 코드 대폭 수정 필요
- Android에서 NIO 성능 검증 필요
- WebSocket 프로토콜 처리 복잡화

**우선순위 이유:**
- 소켓 타임아웃으로 해결되지 않을 경우의 차순책
- 근본적인 해결책이지만 리스크 높음

---

### 🔄 **3순위: 동적 포트 할당 (미구현)**

**구현 방안:**
```kotlin
fun findAvailablePort(startPort: Int = 9090, range: Int = 10): Int {
    for (port in startPort..startPort+range) {
        if (isPortAvailable(port)) {
            Log.d(TAG, "Found available port: $port")
            return port
        }
    }
    throw IOException("No available ports in range $startPort-${startPort+range}")
}

// 사용 시
val actualPort = findAvailablePort()
startServer(actualPort)
broadcastPort(actualPort) // UDP 브로드캐스트로 실제 포트 알림
```

**장점:**
- 포트 충돌 완전 방지
- 여러 인스턴스 동시 실행 가능
- 시스템 레벨 이슈 우회

**단점:**
- 연주자가 포트 번호 알기 어려움
- 자동 발견 시스템 필수 (이미 구현됨)
- 네트워크 설정 복잡화
- 포트 번호 일관성 없음

**관련 기능:**
- `ConductorDiscovery.kt`에서 UDP 브로드캐스트로 실제 포트 전달 가능
- 연주자는 자동 발견으로 동적 포트 찾기 가능

---

### 🔄 **4순위: 프로세스 강제 종료 감지 (부분 구현)**

**현재 구현:**
```kotlin
// PdfViewerApplication.kt (이미 구현됨)
class PdfViewerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalCollaborationManager.getInstance().initialize(this)
    }
    
    override fun onTerminate() {
        // Note: 이 메서드는 실제로는 거의 호출되지 않음
        GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
        super.onTerminate()
    }
}

// MainActivity.kt:290-299 (이미 구현됨)
override fun onDestroy() {
    super.onDestroy()
    
    // Stop web server
    webServerManager.stopServer()
    
    // Force stop all collaboration modes to prevent port conflicts
    Log.d("MainActivity", "Forcing collaboration mode cleanup on app destruction")
    GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
}
```

**추가 개선 방안:**
```kotlin
// 런타임 종료 훅 추가
Runtime.getRuntime().addShutdownHook(Thread {
    Log.d(TAG, "JVM shutdown hook - cleaning up collaboration resources")
    GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
})

// 프로세스 종료 신호 감지
class ProcessMonitor {
    fun startMonitoring() {
        Thread {
            while (true) {
                if (isProcessTerminating()) {
                    emergencyCleanup()
                    break
                }
                Thread.sleep(100)
            }
        }.start()
    }
}
```

**장점:**
- 앱 종료 시점에 확실한 정리
- 여러 종료 시나리오 대응

**단점:**
- `onTerminate()`는 실제로 호출되지 않을 수 있음
- 강제 종료 시 실행 보장 없음
- Android 프로세스 관리 정책에 의존적

---

## 📊 현재 구현 상태 및 테스트 계획

### ✅ **구현된 해결책**
1. **소켓 타임아웃** (1초) - Accept 스레드 반응형 종료
2. **강화된 shutdown 로직** - 다단계 정리 프로세스
3. **포트 가용성 검증** - SO_REUSEADDR 활용
4. **Application 클래스** - 앱 종료 시 강제 정리

### 🧪 **테스트 시나리오**

#### **테스트 1: 정상 종료 시나리오**
1. 지휘자 모드 활성화
2. BACK 키로 정상 종료
3. 즉시 재시작 후 지휘자 모드 재활성화
4. **예상 결과**: 즉시 성공

#### **테스트 2: 강제 종료 시나리오 (핵심)**
1. 지휘자 모드 활성화
2. Recent Apps에서 앱 스와이프하여 강제 종료
3. 즉시 재시작 후 지휘자 모드 재활성화
4. **예상 결과**: 1초 내 성공 (소켓 타임아웃 효과)

#### **테스트 3: 시스템 강제 종료 시나리오**
1. 지휘자 모드 활성화
2. `adb shell am force-stop com.mrgq.pdfviewer`
3. 즉시 재시작 후 지휘자 모드 재활성화
4. **예상 결과**: 1초 내 성공

#### **테스트 4: 반복 테스트**
1. 위 시나리오들을 10회 반복
2. 성공률 측정
3. **목표**: 90% 이상 성공률

### 📈 **성공 지표**
- **포트 해제 시간**: 무제한 → **1초 이내**
- **재활성화**: 재설치 필요 → **즉시 가능**
- **사용자 경험**: 매우 불편 → **매끄러운 전환**
- **성공률**: 0% → **목표 90%+**

---

## 🔮 고급 해결 방안 (필요시)

### **5순위: SO_REUSEADDR + SO_REUSEPORT 조합**

**배경:**
- `SO_REUSEADDR`만으로도 대부분의 포트 점유 문제 해결 가능
- Android/Linux 커널에서는 `SO_REUSEPORT`도 함께 설정 시 즉시 재바인딩 더 확실
- `ServerSocket`에서는 `SO_REUSEPORT` 직접 지원하지 않음

**NIO 기반 구현:**
```kotlin
val serverChannel = ServerSocketChannel.open()
val socket = serverChannel.socket()
socket.reuseAddress = true

// Android에서 지원하는 경우에만 사용
try {
    serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
    Log.d(TAG, "SO_REUSEPORT enabled")
} catch (e: UnsupportedOperationException) {
    Log.w(TAG, "SO_REUSEPORT not supported on this platform")
}
```

### **6순위: WebSocket 서버 프로세스 분리**

**개념:**
- 앱 메인 프로세스와 별도 Service로 WebSocket 서버 격리
- Foreground Service 등록으로 강제 종료 가능성 감소
- UI 프로세스 크래시와 무관하게 협업 서버 유지

**구현 방향:**
```kotlin
// AndroidManifest.xml
<service 
    android:name=".CollaborationService"
    android:process=":collaboration"
    android:exported="false" />

// CollaborationService.kt
class CollaborationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startWebSocketServer()
        return START_STICKY // 시스템이 서비스 재시작
    }
}
```

**장점:**
- 앱 크래시와 협업 서버 분리
- 안드로이드 시스템 레벨 서비스 보호
- 더 안정적인 협업 연결 유지

**단점:**
- 프로세스 간 통신(IPC) 필요
- 메모리 사용량 증가
- 구현 복잡도 상승

### **7순위: SO_LINGER 옵션으로 TIME_WAIT 회피**

**원리:**
- `SO_LINGER(0)` 설정으로 연결 종료 시 TIME_WAIT 상태 진입 방지
- TCP 우아한 종료 대신 강제 종료 (RST 패킷)

**구현:**
```kotlin
// 클라이언트 연결 accept 시
val clientSocket = serverSocket.accept()
clientSocket.setSoLinger(true, 0) // 즉시 소켓 종료

// 또는 서버 소켓 자체에
serverSocket.setSoLinger(true, 0)
```

**주의사항:**
⚠️ **신중한 적용 필요**
- 네트워크 환경에 따라 예기치 않은 종료 문제 발생 가능
- 데이터 손실 위험성
- RFC 권장사항 위반

### **8순위: 포트 점유 프로세스 진단 시스템**

**개발용 디버깅 모듈:**
```kotlin
class PortDiagnostics {
    fun getPortOwner(port: Int): String? {
        return try {
            // 방법 1: /proc/net/tcp 파싱
            val tcpInfo = File("/proc/net/tcp").readText()
            parsePortOwner(tcpInfo, port)
            
            // 방법 2: lsof 명령어 (루팅 필요할 수 있음)
            val process = Runtime.getRuntime().exec("lsof -i :$port")
            val result = process.inputStream.bufferedReader().readText()
            parseLsofOutput(result)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot diagnose port $port: ${e.message}")
            null
        }
    }
    
    private fun parsePortOwner(tcpInfo: String, targetPort: Int): String? {
        // /proc/net/tcp 형식:
        // sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
        tcpInfo.lines().forEach { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 10) {
                val localAddr = parts[1]
                val portHex = localAddr.split(":")[1]
                val port = Integer.parseInt(portHex, 16)
                if (port == targetPort) {
                    val uid = parts[7]
                    val inode = parts[9]
                    return "UID: $uid, inode: $inode"
                }
            }
        }
        return null
    }
}
```

**사용 사례:**
```kotlin
// 설정 화면에서 포트 진단 정보 표시
val diagnostics = PortDiagnostics()
val owner = diagnostics.getPortOwner(9090)
binding.portDiagnostics.text = "포트 9090 점유: ${owner ?: "없음"}"
```

### **9순위: 리모트 디버깅 로그 시스템**

**목적:**
- 포트 점유 상태 발생 시 자동으로 진단 정보 수집
- 현장 문제 재현 없이 디버깅 가능

**구현 방향:**
```kotlin
class RemoteDebugger {
    fun uploadPortIssueReport(port: Int, error: Exception) {
        val report = JsonObject().apply {
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("port", port)
            addProperty("error", error.message)
            addProperty("device_model", Build.MODEL)
            addProperty("android_version", Build.VERSION.RELEASE)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            
            // 포트 상태 정보
            addProperty("port_available", isPortAvailable(port))
            addProperty("port_owner", PortDiagnostics().getPortOwner(port))
            
            // 시스템 상태
            addProperty("free_memory", Runtime.getRuntime().freeMemory())
            addProperty("gc_count", getGCCount())
            addProperty("app_pid", Process.myPid())
            addProperty("app_uid", Process.myUid())
            
            // 최근 바인딩 시도 기록
            add("recent_binding_attempts", getRecentBindingAttempts())
        }
        
        // 익명화된 진단 서버로 전송 (선택적)
        uploadToDebugServer(report)
    }
    
    private fun getRecentBindingAttempts(): JsonArray {
        // 최근 포트 바인딩 시도 시간들 기록
        return JsonArray().apply {
            recentAttempts.forEach { add(it) }
        }
    }
}
```

### **10순위: 시스템 레벨 포트 강제 해제 (개발용)**

**루팅 기기 한정:**
```kotlin
class SystemPortManager {
    fun forceKillPortProcess(port: Int): Boolean {
        if (!isDeviceRooted()) {
            Log.w(TAG, "Device not rooted, cannot force kill port process")
            return false
        }
        
        return try {
            // 포트를 점유한 프로세스 찾기
            val pid = getProcessIdUsingPort(port)
            if (pid != null) {
                // 프로세스 강제 종료
                Runtime.getRuntime().exec("su -c 'kill -9 $pid'")
                Thread.sleep(100)
                
                // 확인
                !isPortInUse(port)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force kill port $port process", e)
            false
        }
    }
    
    private fun isDeviceRooted(): Boolean {
        return try {
            Runtime.getRuntime().exec("su -c 'echo test'")
            true
        } catch (e: Exception) {
            false
        }
    }
}

// 개발자 옵션에서만 활성화
class DeveloperOptions {
    fun showAdvancedPortDebugging() {
        if (BuildConfig.DEBUG) {
            // 개발 빌드에서만 표시
            binding.developerSection.visibility = View.VISIBLE
            binding.forceKillPortBtn.setOnClickListener {
                SystemPortManager().forceKillPortProcess(9090)
            }
        }
    }
}
```

## 🎯 고급 해결 방안 우선순위 평가

### **즉시 적용 권장 (High Priority)**
1. **SO_REUSEPORT 조합** - NIO 전환 시 함께 적용
2. **SO_LINGER(0) 테스트** - 신중하게 A/B 테스트

### **중장기 적용 검토 (Medium Priority)**  
3. **WebSocket 서버 프로세스 분리** - 안정성 크게 향상, 하지만 복잡도 증가
4. **포트 진단 시스템** - 개발/디버깅 단계에서 매우 유용

### **개발/진단 용도 (Low Priority)**
5. **리모트 디버깅 시스템** - 현장 이슈 분석용
6. **시스템 레벨 강제 해제** - 개발자 도구로만 활용

## 📋 권장 적용 순서

### **1단계: 현재 소켓 타임아웃 테스트**
- 기존 구현된 1초 타임아웃 방식 효과 검증
- 성공률 90% 이상 달성 시 추가 작업 불필요

### **2단계: SO_REUSEPORT 강화 (필요시)**
```kotlin
// NIO 전환과 함께 적용
val serverChannel = ServerSocketChannel.open()
serverChannel.socket().reuseAddress = true
try {
    serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
} catch (e: UnsupportedOperationException) {
    // 지원하지 않는 플랫폼에서는 무시
}
```

### **3단계: SO_LINGER 실험 (신중히)**
```kotlin
// A/B 테스트로 안정성 확인 후 적용
serverSocket.setSoLinger(true, 0) // 실험적 적용
```

### **4단계: 진단 시스템 추가 (개발 편의성)**
- 포트 점유 프로세스 식별
- 리모트 디버깅 로그 수집
- 개발자 도구 메뉴 추가

## 🔮 추가 개선 방안 (필요시)

### **실시간 포트 모니터링**
```kotlin
// 디버깅 및 모니터링용
fun checkPortStatus(port: Int): String {
    return when {
        isPortAvailable(port) -> "✅ 사용 가능"
        isPortInTimeWait(port) -> "⏳ TIME_WAIT 상태"
        else -> "❌ 다른 프로세스에서 사용 중"
    }
}

// 설정 화면에서 포트 상태 실시간 표시
binding.portStatus.text = checkPortStatus(9090)
```

### **페일세이프 메커니즘**
```kotlin
// 최후 수단: 포트 강제 해제 시도
fun forceReleasePort(port: Int): Boolean {
    return try {
        // 1. SO_REUSEADDR로 강제 바인딩 시도
        val testSocket = ServerSocket()
        testSocket.reuseAddress = true
        testSocket.bind(InetSocketAddress(port))
        testSocket.close()
        
        // 2. 가비지 컬렉션 강제 실행
        System.gc()
        Thread.sleep(100)
        
        // 3. 재확인
        isPortAvailable(port)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot force release port $port: ${e.message}")
        false
    }
}
```

### **사용자 피드백 개선**
```kotlin
// 포트 바인딩 실패 시 자세한 안내
fun showPortBindingError(port: Int) {
    AlertDialog.Builder(this)
        .setTitle("지휘자 모드 활성화 실패")
        .setMessage("""
            포트 $port 이 사용 중입니다.
            
            해결 방법:
            1. 잠시 후 다시 시도 (권장)
            2. 앱 완전 종료 후 재시작
            3. 기기 재부팅
            
            현재 포트 상태: ${checkPortStatus(port)}
        """.trimIndent())
        .setPositiveButton("다시 시도") { _, _ -> retryActivation() }
        .setNegativeButton("취소", null)
        .show()
}
```

---

## 📝 결론 및 다음 단계

### **현재 상황**
- ✅ 소켓 타임아웃 해결책 구현 완료 (1순위)
- ⚠️ 실제 효과 검증 필요
- 📋 체계적인 테스트 계획 수립
- 🎯 **고급 해결 방안 10가지 추가 분석 완료**

### **예상 결과**
현재 구현한 **소켓 타임아웃 방식**이 80-90%의 실질 문제를 해결할 것으로 예상됩니다:

- **즉시 효과**: 포트 바인딩 문제 1초 내 해결 예상
- **사용자 경험**: 재설치 없이 즉시 지휘자 모드 재활성화 가능
- **기술적 안정성**: 검증된 표준 TCP 소켓 기법 활용

### **단계별 접근 전략**

#### **1단계: 기본 해결책 검증 (즉시)**
1. 현재 소켓 타임아웃 방식 테스트
2. 성공률 90% 이상 달성 시 → **완료**
3. 성공률 부족 시 → 2단계 진행

#### **2단계: 고급 기법 적용 (필요시)**
**우선순위별 적용:**
1. **SO_REUSEPORT 조합** (NIO 전환과 함께)
2. **SO_LINGER(0) 실험적 적용** (A/B 테스트)
3. **포트 진단 시스템** (개발 편의성)

#### **3단계: 구조적 개선 (장기)**
**안정성 극대화:**
1. **WebSocket 서버 프로세스 분리** 
2. **리모트 디버깅 시스템**
3. **동적 포트 할당 시스템**

### **권장 즉시 적용 사항**
기존 소켓 타임아웃과 함께 **간단히 추가할 수 있는 강화책**:

```kotlin
// SimpleWebSocketServer.kt에 추가
serverSocket = ServerSocket(port).apply {
    soTimeout = 1000
    reuseAddress = true
    
    // SO_LINGER 실험적 적용 (선택적)
    if (BuildConfig.DEBUG) {
        setSoLinger(true, 0)
        Log.d(TAG, "SO_LINGER(0) enabled for debug build")
    }
}
```

### **추가 고려사항**
- 다양한 Android 버전에서의 동작 확인
- TV 기기별 네트워크 스택 차이 검증  
- 네트워크 환경 변화 시 안정성 테스트
- **고급 기법들의 Android TV 환경 호환성 검증**

### **성공 시나리오 예측**
- **90% 케이스**: 소켓 타임아웃만으로 해결
- **95% 케이스**: SO_REUSEPORT + SO_LINGER 추가로 해결
- **99% 케이스**: WebSocket 서버 프로세스 분리로 해결

현재 단계에서는 **1단계 검증에 집중**하되, 문제 발생 시 즉시 적용 가능한 **고급 해결 방안들이 준비**되어 있어 안정적인 해결이 가능합니다.

---

**문서 작성일**: 2025-07-06  
**최종 업데이트**: 2025-07-06  
**상태**: 🔄 해결책 구현 완료, 검증 대기 중