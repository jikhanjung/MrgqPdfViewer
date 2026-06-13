# Phase 0 합주 동기 페이지 넘김 구현 기록 (예약 timestamp 방식)

작성일: 2026-06-14
대상 버전: v0.2.x
상태: ✅ 구현 완료 (온디바이스 빌드/테스트 미수행 — Windows Android Studio 필요)
설계: [20260613_P03_clock_sync_design.md](20260613_P03_clock_sync_design.md) (접근법 A) · 계획: [P02](20260613_P02_score_sync_autoturn_plan.md)

---

## 0. 개요

합주 모드 페이지 넘김의 기기 간 시점 어긋남을 줄이기 위해, 지휘자가 **"실제로 넘길 절대 시각 `turn_at`(=지금+lead)"** 를 함께 보내고 모든 기기(지휘자 포함)가 그 벽시계 시각에 동시에 넘기도록 구현했다. **접근법 A**(기기 자동시간 동기 전제, 오프셋 직접 측정 없이 `System.currentTimeMillis()` 절대값 공유).

**설정으로 ON/OFF 선택** 가능하게 했고, **기본값은 OFF(기존처럼 즉시 넘김)** 라 켜기 전엔 동작 변화가 없다.

---

## 1. 변경 파일 (6개)

### 1-1. 메시지 레이어 — `turn_at` 전파
- **`CollaborationServerManager.kt`** — `broadcastPageChange(pageNumber, fileName, turnAt: Long? = null)` 로 확장. `turnAt` 지정 시 JSON 에 `turn_at` 추가.
- **`CollaborationClientManager.kt`** — `page_change` 수신 시 `turn_at` 파싱(`isJsonNull` 가드). 생성자 콜백 시그니처 `(Int, String) → (Int, String, Long?)`.
- **`GlobalCollaborationManager.kt`** — `onPageChangeReceived` 필드 타입·performer 초기화 람다·`broadcastPageChange` overload·`setOnPageChangeReceived` 시그니처를 `Long?` 포함으로 확장.
- **`ViewerCollaborationManager.kt`** — 콜백 람다 `{ page, file, _ -> }` 로 시그니처 맞춤(미통합 dead-code 후보지만 컴파일 유지).

### 1-2. 동작 — `PdfViewerActivity.kt`
- 필드 추가: `syncTurnHandler`, `pendingSyncTurn`, `suppressBroadcastUntil`, 헬퍼 `isSyncTurnEnabled()`/`syncTurnLeadMs()`.
- **지휘자**: DPAD 좌/우 넘김에서 `collaborationMode==CONDUCTOR && isSyncTurnEnabled()` 이면 `conductorScheduledTurn(target, dir)`:
  1. `turn_at = now + lead` 계산 → 연주자에게 즉시 브로드캐스트.
  2. 자기 화면도 `postDelayed(lead)` 로 예약. 실행 시 `suppressBroadcastUntil` 창(2초)을 세워 `showPageWithAnimation` 내부 재브로드캐스트를 억제.
- **연주자**: `setOnPageChangeReceived { page, file, turnAt -> }` 에서 `delay = turn_at − now`. `delay>0` 면 예약, 아니면(또는 turn_at 없으면) 즉시 `handleRemotePageChange`(폴백).
- `broadcastCollaborationPageChange()` 앞에 `suppressBroadcastUntil` 가드 추가.
- `onDestroy` 에서 `pendingSyncTurn` 정리.

### 1-3. 설정 — `SettingsActivity.kt` (협업 모드 패널)
- 🎯 **동기 페이지 넘김** 토글 (`sync_page_turn_enabled`, 기본 `false`).
- ⏲️ **예약 시간** 다이얼로그 (`sync_turn_lead_ms`, 기본 2000, 범위 500–5000ms). sync OFF 면 비활성 표시.
- 토글/변경 시 `showCollaborationPanel()` 재호출로 패널 새로고침.

---

## 2. 핵심 설계 결정 (접근법 A)

- **공유 절대시각 = `System.currentTimeMillis()`** (벽시계). 기기들이 네트워크 자동 시간으로 동기돼 있다는 전제. 단조 시계 `elapsedRealtime` 은 기기별 부팅 기준이라 절대 비교 불가 → 여기선 미사용.
- **하위호환**: `turn_at` 누락 메시지(구버전/동기 OFF)는 연주자가 즉시 넘김 → 기존 동작 유지.
- **지휘자도 지연**: "모두 동시에" 요건을 위해 지휘자 자신도 `turn_at` 에 예약(즉시 넘기지 않음).
- **재브로드캐스트 억제**: 예약 실행이 `showPage/animatePageTransition` 내부 브로드캐스트(3개 호출부)를 다시 타지 않도록 시간창(`suppressBroadcastUntil`)으로 차단. 동기/비동기(캐시 미스) 타이밍 모두 견고.

---

## 3. SharedPreferences 키 (단일 파일 `pdf_viewer_prefs`)

| 키 | 타입 | 기본 | 의미 |
|---|---|---|---|
| `sync_page_turn_enabled` | Boolean | `false` | 동기 예약 넘김 사용(지휘자 기준) |
| `sync_turn_lead_ms` | Long | `2000` | 누른 뒤 넘기까지 lead(ms) |

설정 화면과 뷰어가 같은 prefs 파일을 쓰므로 즉시 연동.

---

## 4. 검증 상태 / 미확인

⚠️ **WSL 환경이라 빌드/실행 미수행.** 다음은 Windows Android Studio + 실기기에서 확인 필요:

1. **컴파일** — `setOnPageChangeReceived` 시그니처 변경이 4개 호출부(Global/Pdf/Viewer + Client 생성자)에 일관 반영됐는지(grep 검증은 완료).
2. **2기기 동시성** — 동기 ON 후 지휘자 넘김 시 두 화면 편차(목표 ≤ ~100ms). 카메라 동시촬영 권장. lead 튜닝.
3. **기기 자동시간 의존성** — 자동시간 OFF/시계 큰 편차 시 동시성 저하(→ 접근법 B 오프셋 측정 도입 신호, P03 부록 B).
4. **기존 경로 무변화** — 동기 OFF/단독 모드에서 기존 즉시 넘김 동일 동작.

### 알려진 v1 한계
- **예약 대기(lead) 중 빠른 연속 넘김**: `pageIndex` 가 실행 시점에 갱신되므로, lead 창 안에서 두 번 누르면 같은 페이지를 타깃할 수 있음. 합주 페이지 넘김 특성상 드묾. 개선안: "대기 중 목표 페이지" 별도 추적.
- **`suppressBroadcastUntil` 창 2초**: 캐시 미스로 렌더가 2초 이상 걸리는 극단 케이스에선 억제 창 만료 후 재브로드캐스트 가능. 실측 시 확인, 필요 시 창 확대.

---

## 5. 후속

- 빌드/실측 후 lead 튜닝 및 (필요 시) 접근법 B 도입.
- Phase 1(템포 자동 넘김 + 메트로놈/마디 하이라이트)에서 이 예약 메커니즘을 합주 모드 넘김 경로로 재사용 (P02 참조).
