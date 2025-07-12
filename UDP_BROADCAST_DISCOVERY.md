# UDP 브로드캐스트 자동 발견 메커니즘

## 개요

MrgqPdfViewer의 협업 모드에서 연주자가 지휘자를 자동으로 발견하는 UDP 브로드캐스트 시스템에 대한 기술 문서입니다.

## 아키텍처

### 📡 통신 구조
```
지휘자 기기 (192.168.x.100)          연주자 기기 (192.168.x.101)
─────────────────────                ─────────────────────
🎼 지휘자 모드                         🎵 연주자 모드
│                                   │
├── UDP 송신 (임의 포트)                ├── UDP 수신 (포트 9091)
│   목적지: 브로드캐스트:9091           │   대기: 브로드캐스트 메시지
│   간격: 2초마다                      │   타임아웃: 1초
│                                   │
└── WebSocket 서버 (포트 9090)         └── WebSocket 클라이언트
    협업 세션 관리                        자동 연결 시도
```

### 🔄 발견 프로세스

1. **지휘자 브로드캐스트 시작**
   ```json
   {
     "type": "conductor_announcement",
     "conductor_name": "Android TV (지휘자)",
     "websocket_port": 9090,
     "timestamp": 1625097600000,
     "ip_address": "192.168.1.100"
   }
   ```

2. **연주자 수신 대기**
   - 포트 9091에 UDP 소켓 바인딩
   - 1초 타임아웃으로 반복 수신 시도
   - 최대 12초간 발견 시도

3. **자동 연결**
   - 브로드캐스트 수신 즉시 지휘자 IP 파싱
   - WebSocket 포트 9090으로 연결 시도
   - 성공 시 협업 세션 시작

## 핵심 설정값

### ⏱️ 타이밍 최적화 (2024-07-12 개선)

```kotlin
private const val DISCOVERY_PORT = 9091           // UDP 브로드캐스트 포트
private const val BROADCAST_INTERVAL = 2000L      // 2초마다 브로드캐스트
private const val LISTEN_TIMEOUT = 1000          // 1초 수신 타임아웃
private const val MAX_DISCOVERY_TIME = 12000L    // 최대 12초 발견 시도
```

### 📊 성능 특성

- **최대 대기 시간**: 2초 (브로드캐스트 간격)
- **평균 대기 시간**: 1초
- **발견 성공률**: 12초 동안 6회 시도
- **네트워크 부하**: 낮음 (2초 간격)

## 브로드캐스트 주소 감지

### 🌐 다중 브로드캐스트 전략

```kotlin
private fun getBroadcastAddresses(): List<String> {
    val addresses = mutableListOf<String>()
    
    // 1. 네트워크 인터페이스에서 감지
    NetworkInterface.getNetworkInterfaces().forEach { interface ->
        interface.interfaceAddresses.forEach { interfaceAddress ->
            val broadcast = interfaceAddress.broadcast?.hostAddress
            if (broadcast != null) addresses.add(broadcast)
        }
    }
    
    // 2. 현재 IP 기반 계산 (192.168.x.255)
    val localIP = NetworkUtils.getLocalIpAddress()
    if (localIP.contains(".")) {
        val parts = localIP.split(".")
        val calculated = "${parts[0]}.${parts[1]}.${parts[2]}.255"
        addresses.add(calculated)
    }
    
    // 3. 기본 브로드캐스트 주소
    addresses.add("255.255.255.255")
    
    return addresses
}
```

### 🔄 폴백 메커니즘

브로드캐스트 실패 시 주요 네트워크 주소로 유니캐스트 시도:
- 게이트웨이 주소 (x.x.x.1)
- 브로드캐스트 주소 (x.x.x.255)

## 네트워크 환경별 동작

### ✅ 지원되는 환경

- **가정용 Wi-Fi**: 완전 지원
- **기업/학교 네트워크**: 브로드캐스트 허용 시 지원
- **동일 VLAN**: 완전 지원

### ❌ 제한사항

- **다른 네트워크 세그먼트**: 브로드캐스트 불가
- **방화벽 차단**: UDP 9091 포트 차단 시 불가
- **가상화 환경**: NAT 네트워크에서 제한적

### 🧪 에뮬레이터 환경

안드로이드 에뮬레이터는 NAT 네트워크 사용으로 브로드캐스트 제한:
```
지휘자: 192.168.55.x (물리 네트워크)
연주자: 10.0.2.x (가상 네트워크)
→ 브로드캐스트 패킷 전달 불가
```

**해결방법**: 수동 IP 연결 사용
```kotlin
connectToConductor("192.168.55.100", 9090, "Android Emulator")
```

## 디버깅 및 로그

### 📊 지휘자 로그
```
ConductorDiscovery: Starting conductor broadcast service: Android TV (지휘자)
ConductorDiscovery: Broadcasting to 3 addresses (broadcast #1)
ConductorDiscovery: ✓ Sent conductor broadcast to 192.168.1.255:9091
ConductorDiscovery: ✓ Sent conductor broadcast to 255.255.255.255:9091
```

### 📱 연주자 로그
```
ConductorDiscovery: Starting conductor discovery...
ConductorDiscovery: Successfully bound to port 9091 for discovery
ConductorDiscovery: Waiting for discovery packet on port 9091...
ConductorDiscovery: Received discovery message from 192.168.1.100: {...}
ConductorDiscovery: Found new conductor: Android TV (지휘자) at 192.168.1.100:9090
```

### 🔧 네트워크 테스트 함수

```kotlin
fun testNetworkConnectivity(): String {
    // 로컬 IP, 브로드캐스트 주소, 소켓 바인딩 등 테스트
    // 디버깅 시 네트워크 상태 확인용
}
```

## 보안 고려사항

### 🔒 브로드캐스트 보안

- **정보 노출**: 네트워크의 모든 기기가 메시지 수신 가능
- **메시지 스푸핑**: 악의적 브로드캐스트 가능성
- **DoS 공격**: 과도한 브로드캐스트로 네트워크 부하

### 🛡️ 완화 방안

- **메시지 검증**: JSON 형식 및 필수 필드 확인
- **연결 검증**: WebSocket 연결 시 추가 인증
- **타임스탬프**: 오래된 메시지 무시
- **중복 방지**: 동일 지휘자 중복 처리 방지

## 향후 개선 방향

### 📈 성능 개선

1. **적응적 브로드캐스트**: 네트워크 상태에 따른 간격 조정
2. **멀티캐스트 전환**: 브로드캐스트 대신 멀티캐스트 사용
3. **mDNS 통합**: Bonjour/Zeroconf 프로토콜 지원

### 🔧 기능 확장

1. **지휘자 우선순위**: 여러 지휘자 발견 시 선택 옵션
2. **연결 히스토리**: 이전 연결 지휘자 기억
3. **QR 코드 연결**: 브로드캐스트 대안 연결 방법

---

**작성일**: 2024-07-12  
**버전**: v0.1.5+  
**관련 파일**: `ConductorDiscovery.kt`, `GlobalCollaborationManager.kt`