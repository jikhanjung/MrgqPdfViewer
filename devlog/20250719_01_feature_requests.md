# 사용자 요청사항 및 개선 제안

**날짜**: 2025-07-19  
**버전**: v0.1.9+  
**작업자**: Claude Code  
**상태**: 🟡 요청 접수

## 📋 요청사항 목록

### 1. 페이지 정보 표시 완전 숨기기 옵션

**요청 내용**: 악보 보여줄 때 아래쪽에 현재 파일 이름과 페이지 정보가 희미하게 표시되는데, 이를 아예 안 보이게 할 수 있는 옵션 추가

**현재 상태**: 
- PDF 뷰어에서 `binding.pageInfo` 텍스트뷰가 파일명과 페이지 번호를 표시
- 기본적으로 0.3f 투명도로 희미하게 보임
- 페이지 전환 시 잠시 1.0f로 밝아진 후 다시 희미해짐

**제안 구현 방안**:
1. **설정 메뉴에 토글 옵션 추가**
   - "페이지 정보 표시" ON/OFF 스위치
   - SettingsActivityNew의 "시스템" 카테고리에 추가

2. **SharedPreferences로 설정 저장**
   - 키: `show_page_info`
   - 기본값: `true` (현재 동작 유지)

3. **PdfViewerActivity에서 설정 반영**
   - 설정이 OFF인 경우 `binding.pageInfo.visibility = View.GONE`
   - 페이지 전환 애니메이션도 해당 설정 반영

**우선순위**: 중간 (사용자 편의성 개선)

**예상 작업 시간**: 30분 내외

---

### 2. 합주 모드 페이지 동기화 개선

**요청 내용**: 애니메이션 여부와 상관없이 합주 모드에서 지휘자 기기는 페이지 넘기기 전에 페이지 동기화 신호를 먼저 broadcast

**문제 상황**:
- v0.1.9에서 페이지 전환 애니메이션 추가 후 합주 모드 동기화 실패
- 지휘자가 페이지를 넘겨도 연주자 기기의 페이지가 넘어가지 않음
- 애니메이션 완료 리스너에 협업 브로드캐스트 코드가 누락됨

**분석된 원인**:
1. `showPage()` 메서드에 중복된 브로드캐스트 로직 존재 (542-547라인, 615-620라인)
2. `animatePageTransition()` 함수에서 브로드캐스트 누락
3. 무한 루프 방지를 위한 모드 차단 로직이 다른 동기화 신호를 누락시킬 수 있음

**해결 방안 (테스트 완료)**:
1. **브로드캐스트 로직 통합**
   - 새로운 `broadcastCollaborationPageChange()` 메서드로 통일
   - 중복 코드 제거

2. **애니메이션과 브로드캐스트 타이밍 분리**
   - 애니메이션 시작 전에 즉시 브로드캐스트
   - 350ms 애니메이션 지연 없이 실시간 동기화

3. **재귀 방지 개선**
   - `collaborationMode` 임시 차단 대신 `isHandlingRemotePageChange` 플래그 사용
   - 다른 동기화 신호 누락 방지

**구현 코드 스니펫**:
```kotlin
// 애니메이션 시작 전 즉시 브로드캐스트
private fun animatePageTransition(...) {
    // 페이지 넘기기 사운드 재생
    playPageTurnSound()
    
    // 협업 모드 브로드캐스트 (애니메이션 시작 전 즉시)
    broadcastCollaborationPageChange(targetIndex)
    
    // 애니메이션 진행...
}
```

**우선순위**: 높음 (핵심 기능 버그)

**예상 작업 시간**: 1시간 (이미 해결 방안 테스트 완료)

**참고사항**: 
- 소스코드는 롤백 예정이나 해결 방안은 검증됨
- 나중에 재작업 시 이 문서 참고하여 동일하게 적용 가능

---

### 3. 합주 모드 다중 연주자 연결 불안정 문제

**요청 내용**: 합주 모드에서 연주자가 2명 이상 연결 시 계속해서 연결이 끊어졌다가 재연결되는 문제 해결

**문제 상황**:
- 연주자 1명일 때는 정상 작동
- 연주자 2명 이상 연결 시 모든 연주자의 연결이 불안정
- 연결됨/연결 끊김이 반복적으로 발생

**분석된 원인**:

1. **중복 장치 이름 처리 문제** (주요 원인)
   - `CollaborationServerManager.kt`의 `addClient()`에서 같은 장치 이름 발견 시 기존 연결 강제 종료
   - 같은 모델의 TV/기기 사용 시 장치 이름이 동일하여 서로를 끊어버림
   ```kotlin
   // 현재 코드: 중복 장치명 발견 시 기존 연결 강제 종료
   val duplicateClientIds = clientDeviceNames.filterValues { it == deviceName }.keys
   duplicateClientIds.forEach { oldClientId ->
       connectedClients[oldClientId]?.close(1000, "Duplicate connection")
   }
   ```

2. **브로드캐스트 동시성 문제**
   - `SimpleWebSocketServer.kt`에서 메시지 전송 중 연결 추가/제거 시 문제 발생
   - 한 클라이언트 전송 실패가 다른 클라이언트에 영향

3. **WebSocket 버퍼 크기 제한**
   - 고정 크기 버퍼(1024바이트)로 큰 메시지 처리 시 문제 가능성

4. **연결 상태 관리 경쟁 조건**
   - 여러 스레드에서 동시에 클라이언트 추가/제거 시 동기화 문제

5. **하트비트 메커니즘 충돌**
   - 여러 클라이언트가 동시에 30초마다 하트비트 전송

**해결 방안**:

1. **클라이언트 ID를 IP 주소로 변경** (권장)
   ```kotlin
   // SimpleWebSocketServer.kt에서
   val clientAddress = socket.remoteSocketAddress
   val clientIp = (clientAddress as InetSocketAddress).address.hostAddress
   val clientId = clientIp // 기존 UUID 대신 IP 주소 사용
   ```
   - 장점: IP 주소는 네트워크상에서 고유하므로 중복 문제 자동 해결
   - 장치 이름은 표시용으로만 사용하고, 실제 식별은 IP로 처리

2. **장치 이름 고유화**
   ```kotlin
   // 장치 이름에 타임스탬프나 UUID 추가
   val uniqueDeviceName = "$deviceName_${UUID.randomUUID().toString().take(8)}"
   ```

3. **중복 연결 허용**
   ```kotlin
   // 중복 장치명 허용하도록 로직 수정
   // addClient()에서 중복 체크 로직 제거
   ```

3. **브로드캐스트 안정성 개선**
   ```kotlin
   // 연결 목록 복사본으로 순회
   val clientsCopy = connections.toMap()
   clientsCopy.forEach { ... }
   ```

4. **동적 버퍼 사용**
   ```kotlin
   // ByteArrayOutputStream으로 가변 크기 메시지 처리
   private val messageBuffer = ByteArrayOutputStream()
   ```

5. **연결 관리 동기화**
   ```kotlin
   @Synchronized
   fun addClient(...) { ... }
   
   @Synchronized
   fun removeClient(...) { ... }
   ```

**우선순위**: 높음 (핵심 기능 치명적 버그)

**예상 작업 시간**: 2-3시간

**테스트 시나리오**:
- 같은 모델의 TV 2대 이상으로 연결 테스트
- 다른 모델의 기기 혼합 연결 테스트
- 연결/해제 반복 스트레스 테스트

---

### 4. 페이지 넘기기 애니메이션 속도 조절 기능

**요청 내용**: 페이지 넘기기 애니메이션의 속도를 사용자가 조절할 수 있는 기능 추가

**현재 구현 상태**:
- **애니메이션 방식**: 슬라이드 트랜지션 (현재 페이지 밀려나가고 새 페이지 슬라이드인)
- **고정 속도**: 350ms
- **보간기**: DecelerateInterpolator(1.8f)
- **코드 위치**: `PdfViewerActivity.kt`의 `animatePageTransition()` 메서드

```kotlin
// 현재 코드
val animationDuration = 350L  // 고정값
currentPageAnimator.duration = animationDuration
nextPageAnimator.duration = animationDuration
```

**제안 구현 방안**:

1. **설정 화면에 슬라이더 추가**
   - 범위: 100ms ~ 1000ms
   - 기본값: 350ms
   - 단계: 50ms 단위
   - 위치: "시스템" 카테고리 또는 새로운 "디스플레이" 카테고리

2. **프리셋 옵션 제공**
   ```kotlin
   enum class AnimationSpeed(val duration: Long, val displayName: String) {
       INSTANT(0L, "즉시"),
       FAST(200L, "빠르게"),
       NORMAL(350L, "보통"),
       SLOW(500L, "느리게"),
       VERY_SLOW(800L, "매우 느리게")
   }
   ```

3. **SharedPreferences로 저장**
   - 키: `page_animation_duration`
   - 타입: Long (밀리초)

4. **실시간 적용**
   ```kotlin
   private fun getAnimationDuration(): Long {
       return sharedPreferences.getLong("page_animation_duration", 350L)
   }
   
   // animatePageTransition()에서
   val animationDuration = getAnimationDuration()
   ```

5. **애니메이션 끄기 옵션**
   - 0ms로 설정 시 애니메이션 없이 즉시 전환
   - 접근성 향상 및 빠른 페이지 넘기기 선호 사용자를 위함

**추가 고려사항**:
- 보간기(Interpolator)도 함께 조절 가능하도록 확장 가능
- 효과음 재생 타이밍도 애니메이션 속도에 맞춰 조정
- 두 페이지 모드에서도 동일하게 적용

**우선순위**: 중간 (사용자 편의성 개선)

**예상 작업 시간**: 1시간

**참고사항 - TV vs 타블렛 애니메이션 성능 차이**:

TV에서 애니메이션이 끊기는 현상이 보고됨. 주요 원인:

1. **하드웨어 성능 차이**
   - Android TV: 저전력 SoC, 1-2GB RAM
   - 타블렛: 더 강력한 프로세서, 4-8GB+ RAM

2. **해상도 부담**
   - TV: 4K (3840x2160) 고해상도로 큰 비트맵 처리
   - 타블렛: FHD~2K 수준으로 상대적으로 가벼움

3. **메모리 대역폭 제한**
   - TV SoC는 비용 절감으로 메모리 대역폭이 제한적
   - 큰 PDF 페이지 이동 시 병목 현상 발생

4. **TV 최적화 고려사항**
   ```kotlin
   // TV용 저해상도 렌더링
   private fun getTvOptimizedScale(): Float {
       return if (isRunningOnTv()) 0.7f else 1.0f
   }
   
   // 하드웨어 가속 레이어 강제
   binding.pdfView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
   
   // TV용 더 짧은 기본 애니메이션
   val defaultDuration = if (isRunningOnTv()) 250L else 350L
   ```

이러한 하드웨어 차이를 고려하여 애니메이션 속도 조절 기능이 특히 TV 사용자에게 중요함.

---

## 🔄 진행 상황

- [ ] 요청사항 분석 완료
- [ ] 구현 방안 검토
- [ ] 실제 구현 진행
- [ ] 테스트 및 검증
- [ ] 사용자 피드백 수집

---

## 📝 참고사항

- 현재 페이지 정보는 악보 연주 시 방해가 될 수 있음
- 특히 TV 화면에서 큰 글씨로 표시되어 시각적 방해 요소가 될 수 있음
- 합주 모드에서는 페이지 동기화 확인 차 보이는 것이 유용할 수 있으므로 완전 숨김과 부분 표시 옵션 고려 필요