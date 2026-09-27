# 058 — ScoreMate C1: TV 기기 연결 (코드 · QR · 토큰 · heartbeat · 해제)

작성일: 2026-09-27
계획: [`P05`](20260926_P05_scoremate_client_plan.md) C1 · 서버: ScoreMateServer devlog 059 (S3 TV 기기 연결, RFC 8628)
상태: 🟡 구현 · 단위 테스트(227개 전체 통과) · release 빌드 · lint 통과. **실기기 · 운영 서버 연결은 아직** (TV 꺼짐)

---

## 0. 요약

설정 → ☁️ **ScoreMate** → "이 TV 연결" → 화면에 QR 과 코드(`BCDF-GHJK`) → 휴대폰으로 QR → 로그인 · 코드 확인 · "연결" →
TV 가 토큰을 받아 저장하고 heartbeat 로 앱 버전 · 모델을 알린다. 악보 동기화(C2)는 아직 — 설정에 "준비 중"으로 보인다.

## 1. 구조 (`scoremate/`)

| 파일 | 내용 |
|---|---|
| `ScoreMateProtocol` | 서버 규약 해석만 — 코드 · 폴링(pending · slow_down · denied · expired · invalid · 429) · 갱신(`{access, refresh}`) · 기기 정보 · 서버 주소 다듬기. JVM 테스트 |
| `ScoreMateClient` | 통신. 전송은 `HttpTransport`(기본 `HttpURLConnection`)로 바꿔 끼울 수 있어 JVM 테스트에서 서버를 흉내 낸다 |
| `ScoreMateStore` | 앱 전용 SharedPreferences `scoremate` — 서버 주소 · access · refresh · device_id · 기기 이름. 토큰은 `commit()` |
| `DeviceLinkDialog` | 코드 · QR(zxing core) · 남은 시간 · 상태. 서버 `interval` 로 폴링 |

## 2. P05 §3.1 반영

- **갱신은 한 번에 하나** — `Mutex`. 401 을 받은 요청은 자기가 쓴 access 가 아직 저장돼 있을 때만 갱신하고, 아니면(다른 요청이 이미 갱신) 저장된 새 access 로 다시.
  테스트: 세 요청이 동시에 401 → 갱신 1번(`동시에_401_이어도_갱신은_한_번만`)
- **401 구분** — 인증 요청 401 → 갱신 후 한 번만 다시. 갱신이 400/401 이거나 다시 해도 401 → 해제된 것: 토큰을 지우고 `ScoreMateUnlinkedException`.
  네트워크 오류는 토큰을 지우지 않는다
- **백업 제외** — `res/xml/backup_rules.xml`(`fullBackupContent`)이 `scoremate.xml` 을 뺀다. targetSdk 30 이라 Android 12+ 도 이 규칙
- API 요청은 리다이렉트를 따라가지 않는다 (`instanceFollowRedirects = false`). 받기(302 → 서명 URL)는 C2 에서 따로 처리

## 3. 흐름 세부

- 폴링: `slow_down` · 429 → 간격 +5초. `expired_token` · `invalid_grant` · 10분 지남 → 저절로 새 코드. `access_denied` → 멈추고 "새 코드" 를 기다림.
  폴링 중 네트워크 오류는 표시만 하고 계속
- 기기 이름 기본값: TV 설정의 기기 이름(`Settings.Global.DEVICE_NAME`), 없으면 모델명 — 휴대폰에서 연결할 때 바꿀 수 있고, 연결 뒤 heartbeat 응답의 이름을 저장
- **heartbeat**: 앱을 켤 때(프로세스당 한 번, MainActivity.onResume) · 합주 중 제외 · 네트워크 오류 무시 · 해제됐으면 토스트 한 번
- 설정: 연결 안 됨 → "이 TV 연결" · "서버 주소"(개발 서버용, 비우면 기본). 연결됨 → "연결 확인"(`/devices/me/`) · "연결 해제"
- **해제**: `DELETE /api/v1/devices/{device_id}/` 후 토큰 삭제. 서버에 닿지 못해도 이 TV 에서는 해제한다(웹 "TV" 화면에서도 해제 안내)

## 4. 테스트 (`ScoreMateClientTest`, 12개)

규약 해석(코드 · 폴링 7가지 · 간격 · 갱신 필드 이름 · 서버 주소), 만료 access → 갱신 → 회전된 refresh 저장, 동시 401 → 갱신 1번,
갱신 401 → 토큰 삭제, 네트워크 오류 → 토큰 유지, 폴링 연결 → 저장, 해제(서버 204 · 오프라인), 요청 URL · Bearer.
로컬 실행에서 `normalizeServer("https://")` 가 `https://https:` 를 내던 것을 잡아 고쳤다.

## 5. 남은 것

- 실기기에서 운영 서버(`scoremate.noematica.kr`)로 연결 → 웹 "TV" 목록에 앱 버전 · 모델이 보이는지, 해제가 양쪽에서 되는지
- C2 — 저장 위치(하위 폴더 / 이름 규칙) 결정이 먼저
