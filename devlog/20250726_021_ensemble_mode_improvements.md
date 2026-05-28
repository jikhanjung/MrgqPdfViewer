# 합주 모드 개선 사항 (2025-07-26)

## 1. 연주자 모드 화면 꺼짐 방지 기능

### 문제점
- 연주자가 PdfViewerActivity에서 별도의 액션(페이지 넘기기 등) 없이 악보를 읽기만 할 경우
- Android TV의 화면보호기나 절전모드가 활성화되어 화면이 꺼질 수 있음
- 연주 중 화면이 꺼지면 다시 켜는 동안 연주에 방해가 됨

### 해결방안
```kotlin
// PdfViewerActivity.kt의 onCreate()에 추가
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

### 구현 세부사항
- 연주자 모드일 때만 적용 (지휘자 모드는 선택적)
- Activity가 활성화된 동안만 유효
- onPause()에서 자동으로 해제되므로 별도 해제 코드 불필요

### 적용 위치
- `PdfViewerActivity.kt`의 `onCreate()` 메소드
- 합주 모드 활성화 조건과 함께 적용

## 2. 지휘자-연주자 페이지 동기화 입력 보호

### 문제점
- 지휘자가 페이지를 넘긴 직후 연주자가 페달/리모컨을 누르는 경우
- 네트워크 지연으로 인한 타이밍 차이로 연주자가 한 페이지 더 넘어가는 문제
- 예: 지휘자 3→4 페이지, 연주자도 3→4로 동기화 후 바로 4→5로 넘어감

### 해결방안
1. **입력 무효화 시간 설정**
   - 지휘자 동기화 메시지 수신 후 일정 시간(예: 500ms) 동안 연주자 입력 무시
   - 타이머 기반 입력 차단 메커니즘

2. **구현 방법**
   ```kotlin
   // ViewerCollaborationManager.kt에 추가
   private var lastSyncTime = 0L
   private val INPUT_BLOCK_DURATION = 500L // 0.5초
   
   fun isInputBlocked(): Boolean {
       return System.currentTimeMillis() - lastSyncTime < INPUT_BLOCK_DURATION
   }
   ```

3. **적용 위치**
   - `ViewerInputHandler.kt`의 키 입력 처리 부분
   - 페이지 넘기기 전 `isInputBlocked()` 체크

### 추가 고려사항
- 블록 시간을 설정에서 조정 가능하도록 구현
- 시각적 피드백 (예: 토스트 메시지 "동기화 중...")

## 3. 실시간 마우스 포인터 및 하이라이트 공유

### 기능 설명
- 지휘자가 마우스로 특정 부분을 가리키거나 하이라이트하면 연주자 화면에도 표시
- 리허설이나 연습 시 유용한 기능

### 기술적 구현 방안

#### 3.1 마우스 포인터 공유
```kotlin
// 새로운 메시지 타입 추가
data class MousePointerMessage(
    val x: Float,  // 0.0 ~ 1.0 (상대 좌표)
    val y: Float,  // 0.0 ~ 1.0 (상대 좌표)
    val visible: Boolean
)
```

#### 3.2 하이라이트/주석 기능
```kotlin
data class AnnotationMessage(
    val type: String, // "highlight", "circle", "arrow"
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val color: String,
    val strokeWidth: Float
)
```

#### 3.3 구현 단계
1. **1단계: 마우스 포인터 전송**
   - 지휘자의 마우스 움직임 캡처
   - WebSocket으로 좌표 전송 (throttling 적용)
   - 연주자 화면에 포인터 오버레이 표시

2. **2단계: 임시 주석 기능**
   - 터치/마우스 드래그로 하이라이트
   - 실시간 전송 및 표시
   - 페이지 전환 시 자동 삭제

3. **3단계: 영구 주석 저장 (선택적)**
   - Room DB에 주석 데이터 저장
   - 파일별, 페이지별 주석 관리

### UI/UX 고려사항
- 연주자 화면에서 주석 표시 ON/OFF 설정
- 포인터 크기 및 색상 설정
- 네트워크 부하 최소화 (좌표 전송 빈도 조절)

### 필요한 신규 컴포넌트
- `AnnotationOverlayView.kt`: PDF 위에 주석을 그리는 커스텀 뷰
- `MousePointerView.kt`: 마우스 포인터 표시 뷰
- `AnnotationWebSocketHandler.kt`: 주석 관련 WebSocket 메시지 처리

## 4. 합주 모드 메시지 큐 시스템

### 필요성
- 네트워크 지연이나 순간적인 부하로 메시지 순서가 뒤바뀔 수 있음
- 동시에 여러 메시지가 도착할 경우 처리 순서 보장 필요
- 메시지 유실 방지 및 재시도 메커니즘 필요

### 메시지 큐 설계

#### 4.1 기본 구조
```kotlin
// MessageQueue.kt
class CollaborationMessageQueue {
    private val messageQueue = LinkedBlockingQueue<CollaborationMessage>()
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class CollaborationMessage(
        val id: String = UUID.randomUUID().toString(),
        val type: MessageType,
        val payload: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val priority: Priority = Priority.NORMAL
    )
    
    enum class MessageType {
        PAGE_CHANGE,
        FILE_CHANGE,
        MOUSE_POINTER,
        ANNOTATION,
        CONNECTION_STATUS
    }
    
    enum class Priority {
        HIGH,    // 페이지 변경 등 즉시 처리 필요
        NORMAL,  // 일반 메시지
        LOW      // 마우스 움직임 등 덜 중요한 메시지
    }
}
```

#### 4.2 메시지 처리 로직
```kotlin
// 메시지 처리기
fun startProcessing() {
    processingScope.launch {
        while (isActive) {
            try {
                val message = messageQueue.poll(100, TimeUnit.MILLISECONDS)
                message?.let { processMessage(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Message processing error", e)
            }
        }
    }
}

// 우선순위 기반 큐 삽입
fun enqueueMessage(message: CollaborationMessage) {
    when (message.priority) {
        Priority.HIGH -> {
            // 높은 우선순위는 큐 앞쪽에 삽입
            val tempList = mutableListOf<CollaborationMessage>()
            messageQueue.drainTo(tempList)
            messageQueue.offer(message)
            messageQueue.addAll(tempList)
        }
        else -> messageQueue.offer(message)
    }
}
```

#### 4.3 메시지 중복 제거 및 순서 보장
```kotlin
// 중복 메시지 필터링
private val processedMessageIds = LruCache<String, Long>(100)

fun isDuplicate(messageId: String): Boolean {
    val existing = processedMessageIds.get(messageId)
    if (existing != null) return true
    processedMessageIds.put(messageId, System.currentTimeMillis())
    return false
}

// 타임스탬프 기반 순서 검증
private var lastProcessedTimestamp = 0L

fun isOutOfOrder(timestamp: Long): Boolean {
    return timestamp < lastProcessedTimestamp
}
```

### 통합 아키텍처

#### 4.4 ViewerCollaborationManager 개선
```kotlin
class ViewerCollaborationManager {
    private val messageQueue = CollaborationMessageQueue()
    
    // WebSocket 메시지 수신 시
    fun onWebSocketMessage(rawMessage: String) {
        val message = parseMessage(rawMessage)
        
        // 큐에 메시지 추가
        messageQueue.enqueueMessage(
            CollaborationMessage(
                type = determineMessageType(message),
                payload = rawMessage,
                priority = determinePriority(message)
            )
        )
    }
    
    // 입력 차단 상태 확인 (큐 기반)
    fun isInputBlocked(): Boolean {
        // 최근 PAGE_CHANGE 메시지 처리 시간 확인
        return messageQueue.getLastProcessedTime(MessageType.PAGE_CHANGE)
            ?.let { System.currentTimeMillis() - it < INPUT_BLOCK_DURATION }
            ?: false
    }
}
```

### 장점
1. **순서 보장**: 메시지가 도착한 순서대로 처리
2. **우선순위 처리**: 중요한 메시지(페이지 변경)를 먼저 처리
3. **재시도 메커니즘**: 실패한 메시지 재처리 가능
4. **성능 최적화**: 마우스 움직임 같은 저우선순위 메시지 throttling
5. **디버깅 용이**: 모든 메시지 추적 및 로깅 가능

### 구현 시 고려사항
- 메모리 사용량 모니터링 (큐 크기 제한)
- 오래된 메시지 자동 제거 (TTL 설정)
- 메시지 처리 실패 시 재시도 횟수 제한
- 큐 상태 모니터링 UI (디버그 모드)

## 구현 우선순위

1. **즉시 구현 (High Priority)**
   - 화면 꺼짐 방지 (가장 간단하고 즉각적인 효과)
   - 메시지 큐 시스템 (안정적인 합주 모드의 기반)
   - 입력 무효화 기능 (큐 시스템과 연동)

2. **추후 구현 (Medium Priority)**
   - 마우스 포인터 공유 (유용하지만 복잡도 높음)
   - 하이라이트 기능 (추가 UI 작업 필요)

## 테스트 시나리오

### 1. 화면 꺼짐 방지 테스트
- 연주자 모드에서 10분 이상 대기
- 화면이 꺼지지 않는지 확인
- 다른 앱으로 전환 시 정상적으로 절전모드 동작 확인

### 2. 입력 무효화 테스트
- 지휘자가 페이지를 넘기고 즉시(0.1초 이내) 연주자가 입력
- 연주자 입력이 무시되는지 확인
- 0.5초 후 연주자 입력이 정상 동작하는지 확인

### 3. 마우스 공유 테스트
- 지휘자 마우스 움직임이 연주자에게 실시간 표시
- 네트워크 지연 시간 측정 (목표: 100ms 이내)
- 다중 연주자 환경에서 성능 테스트

### 4. 메시지 큐 시스템 테스트
- 동시 다발적 메시지 전송 시 순서 보장 확인
- 우선순위별 처리 순서 검증 (페이지 변경 > 일반 > 마우스 움직임)
- 네트워크 끊김 상황에서 메시지 재시도 동작 확인
- 큐 오버플로우 상황 처리 (1000개 이상 메시지)
- 중복 메시지 필터링 동작 확인