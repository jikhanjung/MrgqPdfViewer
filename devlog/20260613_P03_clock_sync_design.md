# Phase 0 동기 페이지 넘김 설계 (절대 timestamp 예약 방식)

작성일: 2026-06-13 (rev. 단순화 — 접근법 A 채택)
대상 버전: v0.2.x
상태: ✅ 접근법 A 구현 완료 (온디바이스 빌드/테스트 필요 — Windows Android Studio)
설정: `협업 모드 > 동기 페이지 넘김`(토글, 기본 OFF) + `예약 시간`(ms). prefs 키 `sync_page_turn_enabled` / `sync_turn_lead_ms`
관련: [P02 계획](20260613_P02_score_sync_autoturn_plan.md) · [037 분석 기록](20260613_037_score_analysis_work.md)

---

## 0. 목표

지휘자가 페이지 넘김 신호를 보낼 때 **"실제로 넘길 절대 시각 `turn_at` (예: 지금+2초)"** 를 함께 전달한다. 모든 기기(지휘자 포함)는 신호를 미리 받아 두고 **그 시각에 동시에** 넘긴다. 신호 전달·렌더 딜레이 편차를 lead time 여유로 흡수.

현재(수신 즉시 넘김)의 기기 간 시점 어긋남을 제거하는 것이 목적.

---

## 1. 방식: 절대 벽시계 timestamp 예약 (접근법 A)

- 지휘자: `turn_at = System.currentTimeMillis() + LEAD_MS`.
- 모든 기기: 자기 벽시계가 `turn_at` 에 도달하면 넘김.
- **전제**: 기기들이 **네트워크 자동 시간(NTP)** 으로 충분히 동기돼 있음 (Android "자동 날짜/시간" ON). 같은 Wi-Fi 의 안드로이드 기기는 보통 수십~수백 ms 내. 2초 lead + 페이지 넘김 특성상 체감 동시성에 충분.
- **시계 선택**: 반드시 **`System.currentTimeMillis()`**(벽시계, 기기 간 공유 절대시각). `elapsedRealtime()` 은 기기별 부팅 기준이라 절대 비교 불가 → 여기선 사용 안 함.

> 기기 시계가 많이 어긋나는 경우를 대비한 **오프셋 직접 측정(NTP ping/pong)** 은 부록 B 참고. **실측 편차가 크면 그때 도입**. 우선 A로 단순 구현.

---

## 2. 메시지 프로토콜 (최소 변경)

기존 `page_change` 에 `turn_at` 필드 **하나만** 추가.

지휘자 → 연주자:
```json
{
  "action": "page_change",
  "page": 42,
  "file": "score.pdf",
  "timestamp": 1629724305000,   // 기존(전송 시각, 로깅용)
  "turn_at": 1629724307000      // 신규: 실제 넘길 절대 시각 (전송시각 + LEAD_MS)
}
```
- `turn_at` 누락(구버전 지휘자) → 연주자는 **즉시 넘김**(현재 동작). **하위호환 유지.**

`file_change` 도 동일하게 `turn_at` 추가 가능(선택, 동일 패턴).

---

## 3. 동작 흐름

**지휘자** (페이지 넘김을 누르면):
```
turn_at = now() + LEAD_MS
broadcast page_change { page, file, turn_at }
schedule(turn_at) { showPageLocal(index) }   // 자기 화면도 같은 시각에 (재브로드캐스트 X)
```

**연주자** (page_change 수신):
```
delay = turn_at - now()
if (turn_at == null || delay <= 0)  showPage(index)              // 폴백: 즉시
else                                postDelayed(delay) { showPage(index) }
```

핵심: **지휘자도 즉시 넘기지 않고 turn_at 에 예약** → 지휘자·연주자 전원이 같은 절대시각에 넘어감.

---

## 4. 통합 지점 (실제 코드)

### 4-1. `CollaborationServerManager.broadcastPageChange()` (≈173–183행)
- 메시지에 `addProperty("turn_at", turnAt)` 추가. `turnAt` 은 인자로 받음.

### 4-2. `GlobalCollaborationManager`
- `broadcastPageChange(page, file, turnAt)` 로 시그니처 확장(또는 내부에서 turn_at 계산).
- 연주자 콜백 `setOnPageChangeReceived` 시그니처를 `(page, file, turnAt)` 로 확장.

### 4-3. `CollaborationClientManager` (page_change 파싱, ≈158–161행)
- `turn_at` 파싱(`json.get("turn_at")?.asLong`), 콜백에 함께 전달.

### 4-4. `PdfViewerActivity`
- **지휘자**: 현재 `showPage()` 가 즉시 넘기고 그 안에서 `broadcastCollaborationPageChange()` 호출(≈587행). 합주-지휘자 모드에서는 **넘김을 예약 경로로** 전환:
  - 네비게이션 입력 → `scheduleConductorTurn(index)`:
    ```kotlin
    val turnAt = System.currentTimeMillis() + leadMs()
    gcm.broadcastPageChange(index + 1, pdfFileName, turnAt)   // 브로드캐스트만
    uiHandler.postDelayed({
        isHandlingRemotePageChange = true     // showPage 내부 재브로드캐스트 억제(기존 플래그 재사용)
        showPageScheduled(index)
        isHandlingRemotePageChange = false
    }, leadMs())
    ```
  - 단독 모드/비협업: 기존처럼 즉시 `showPage`.
- **연주자**: `setOnPageChangeReceived { page, file, turnAt -> ... }` (≈1484행) →
  ```kotlin
  runOnUiThread {
    if (file != pdfFileName) return@runOnUiThread
    val delay = (turnAt ?: 0L) - System.currentTimeMillis()
    if (turnAt == null || delay <= 0) handleRemotePageChange(page)         // 즉시(폴백)
    else uiHandler.postDelayed({ handleRemotePageChange(page) }, delay)
  }
  ```
  - 입력 차단(`updateSyncTime`, 500ms)은 **예약 실행 시점**에 갱신.

---

## 5. lead time 정책

- 기본 `LEAD_MS = 2000ms` (설정 가능). 조건: 모든 연주자에게 신호가 `turn_at` 전에 도달.
- 수동 넘김: 지휘자가 누르면 2초 뒤 전원 동시 넘김 (의도적 지연이 동기 보장).
- Phase 1 템포 기반: 페이지 끝 시점을 미리 알아 자연스럽게 예약(체감 지연 없음).
- 너무 길면 답답, 너무 짧으면 폴백 빈발 → 실측으로 1.0~2.0s 튜닝.

---

## 6. 엣지 / 폴백

| 상황 | 처리 |
|---|---|
| `turn_at` 누락(구버전) | 즉시 넘김 (하위호환) |
| `delay ≤ 0` (신호 늦게 도착 / 기기 시계 뒤처짐) | 즉시 넘김 + 경고 로그 |
| 기기 자동시간 OFF / 시계 크게 어긋남 | 동시성 저하 → **부록 B(오프셋 측정) 도입 신호** |
| 같은 페이지 재신호 | 기존 중복 무시 로직 유지 |
| 예약 대기 중 또 다른 넘김 신호 | 직전 예약 취소 후 최신 것으로 갱신(`removeCallbacks`) |

---

## 7. 구현 순서

1. `page_change` 에 `turn_at` 추가 (서버 송신 + 클라 파싱) — 메시지 레벨.
2. 콜백 시그니처 확장 (`turnAt` 전파): ServerManager → GlobalManager → ClientManager → Activity.
3. 연주자 예약 실행 + 폴백.
4. 지휘자 예약 실행(자기 화면도 turn_at 에) + 재브로드캐스트 억제.
5. `LEAD_MS` 설정값화, 예약 중복/취소 처리.
6. 단독/비협업 모드는 기존 즉시 경로 유지 확인.
7. 2기기 실측 편차 로그 → lead 튜닝.

---

## 8. 검증

- **2기기 로그 대조**: 지휘자·연주자가 `turn_at` 과 실제 `showPage` 실행 시각(`System.currentTimeMillis()`)을 로그 → 편차 측정. 목표 체감 동시(≤ ~100ms).
- **카메라 동시촬영**: 두 화면을 한 프레임에 담아 동시성 확인 (기존 즉시-넘김 대비 개선 확인).
- 기기 자동시간 ON/OFF 비교로 A 의 전제 민감도 파악.

---

## 부록 A. 핵심 코드 스케치

```kotlin
// 공통
private fun leadMs(): Long = preferences.getLong("sync_turn_lead_ms", 2000L)
private val uiHandler = Handler(Looper.getMainLooper())

// 지휘자: 예약 넘김
private fun scheduleConductorTurn(index: Int) {
    val turnAt = System.currentTimeMillis() + leadMs()
    globalCollaborationManager.broadcastPageChange(index + 1, pdfFileName, turnAt)
    pendingTurn?.let { uiHandler.removeCallbacks(it) }
    pendingTurn = Runnable {
        isHandlingRemotePageChange = true
        showPage(index)               // 내부 재브로드캐스트는 플래그로 억제
        isHandlingRemotePageChange = false
    }
    uiHandler.postDelayed(pendingTurn!!, leadMs())
}

// 연주자: 예약 실행
globalCollaborationManager.setOnPageChangeReceived { page, file, turnAt ->
    runOnUiThread {
        if (file != pdfFileName) return@runOnUiThread
        val delay = (turnAt ?: 0L) - System.currentTimeMillis()
        pendingTurn?.let { uiHandler.removeCallbacks(it) }
        if (turnAt == null || delay <= 0) {
            handleRemotePageChange(page)
        } else {
            pendingTurn = Runnable { handleRemotePageChange(page) }
            uiHandler.postDelayed(pendingTurn!!, delay)
        }
    }
}
```

## 부록 B. (보류) 오프셋 직접 측정 — 실측 편차가 크면 도입

기기 자동시간이 신뢰 불가일 때만. NTP 4-타임스탬프로 지휘자-연주자 시계차(offset)를 측정해 `turn_at − offset` 으로 환산. 연결 시 ping/pong 버스트(8회/200ms) → RTT 최소 표본 중앙값, 하트비트로 EMA 유지. `elapsedRealtime()` 기준. (상세는 이 문서 이전 리비전 / 필요 시 별도 작성.)

```
RTT    = (t3−t0) − (t2−t1)
offset = ((t1−t0) + (t2−t3)) / 2     // 지휘자 − 연주자
delay  = (turn_at − offset) − now()
```
