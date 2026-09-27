# P05 — ScoreMate 클라이언트: TV 기기 연결 · 악보 동기화

작성일: 2026-09-26 · 개정 2026-09-27 (서버 구현 완료 후 — 서버 규약에 맞춤)
상태: 📋 계획
서버: https://scoremate.noematica.kr (0.6.1 운영 중). 서버 쪽 계획 · 규약:
ScoreMateServer `devlog/20260926_054_악보공유_및_TV클라이언트_계획.md` · **`059_S3_TV_기기_연결.md`** · **`060_S4_TV_동기화_API.md`** ·
**`061_S6_세트리스트_분석공유_Google로그인.md`** — 요청 · 응답의 정본은 서버 devlog 다. 이 문서는 TV 쪽 설계.

---

## 0. 목표

앙상블 리더가 ScoreMate 서버(웹)에 올린 악보가 **멤버의 TV 에 저절로 들어와 있게** 한다. 수정판(판 2, 3 …)을 올리면 TV 는 다음 동기화에서 새 판으로 바뀐다.
연주회 곡목(세트리스트)과 악보 분석(마디)도 서버에서 받는다.

바뀌지 않는 것:
- **합주 실시간 동기화는 LAN 그대로** (#055: 마디 전환 차이 2.5 ms). 서버는 배포만 한다
- **오프라인 우선** — 받은 악보는 앱 폴더에 있고, 서버 · 인터넷이 없어도 모든 기능이 돈다
- 웹 업로드(로컬 웹 서버) · 지휘자에게서 받기 등 지금의 넣는 방법도 그대로

## 1. 사용자 흐름

1. 설정 → **ScoreMate** → "연결" → 화면에 코드 `BCDF-GHJK` 와 QR
2. 휴대폰으로 QR → 로그인(초대 전용 — 초대 링크로 가입한 계정) → 코드 확인 → 기기 이름 → "연결"
3. TV 가 토큰을 받고 바로 동기화 → 파일 목록에 ScoreMate 악보가 ☁️ 표시와 함께 나타난다
4. 이후 앱을 켤 때(업데이트 확인과 같은 시점) · 파일 목록에서 수동으로 동기화
5. 설정에서 "연결 해제" — 서버에 해제를 알리고, 받은 악보를 남길지 지울지 묻는다
6. 웹 "TV" 화면에서 해제되면(다른 기기에서) 다음 요청이 401 → "다시 연결" 안내

## 2. 서버 규약 요약 (정본: 서버 devlog 059 · 060 · 061)

| 무엇 | 요청 | 응답 · TV 가 할 일 |
|---|---|---|
| 코드 | `POST /api/v1/device/code` `{name, model, app_version}` | `{device_code, user_code, verification_uri, verification_uri_complete, expires_in: 600, interval: 5}` — QR = `verification_uri_complete` |
| 토큰 폴링 | `POST /api/v1/device/token` `{device_code}` `interval` 초마다 | 400 `authorization_pending` 계속 · `slow_down` 간격 +5초 · `expired_token` 새 코드 · `access_denied` 처음부터 · `invalid_grant` 처음부터 / 200 `{access_token, refresh_token, token_type, expires_in: 3600, device_id}` |
| 갱신 | `POST /api/v1/auth/token/refresh/` `{refresh}` | `{access, refresh}` — **필드 이름이 폴링 응답과 다르다**. refresh 도 새 것 → **바로 저장**(access 를 쓰기 전에) |
| 401 | 아무 요청 | 기기 해제됨(또는 refresh 만료 180일) → 토큰 지우고 다시 연결 |
| 동기화 | `GET /api/v1/sync/scores/?cursor=` | `{cursor, has_more, scores[], ids[]}` — §4 |
| 받기 | `GET {download_url}` (Bearer) | 302 → 서명 URL(5분, 인증 불필요) → PDF |
| 세트리스트 | `GET /api/v1/sync/setlists/` | `{setlists: [{id, title, description, ensemble, updated_at, items: [{score_id, position, notes}]}]}` — 통째로 바꿔 끼운다 |
| 분석 | `GET/PUT /api/v1/scores/{id}/analysis/` | §5 |
| 알림 | `POST /api/v1/devices/me/heartbeat/` `{app_version, model}` | 앱을 켤 때 — 웹 TV 목록에 앱 버전 |
| 해제 | `DELETE /api/v1/devices/{device_id}/` | 204 — 설정의 "연결 해제" |

- 서버 주소 기본값 `https://scoremate.noematica.kr` (설정에서 바꿀 수 있게 — 개발 서버)
- 요청 제한: 동기화 · 받기 · 분석 · heartbeat 는 **기기마다** 시간당 3000 (같은 계정의 다른 TV 와 나누지 않는다). 429 면 다음 동기화로 미룬다
- 한 계정에 TV 여러 대: 각자 `device_id` · 토큰 · 커서를 가진다. 한 대를 해제해도 다른 TV 는 그대로

## 3. 구조

| 부품 | 내용 |
|---|---|
| `scoremate/ScoreMateClient` | HTTP (업데이트 기능의 `HttpURLConnection` 방식) — code · token 폴링 · refresh(401 이면 한 번 갱신 후 재시도) · sync · download · analysis · heartbeat · 해제 |
| `scoremate/DeviceLinkDialog` | 코드 · QR 표시, 폴링(서버 `interval`, `slow_down` 반영), 만료 시 새 코드 |
| QR 생성 | `com.google.zxing:core` (순수 Java, 작다) → Bitmap |
| 토큰 저장 | 앱 전용 SharedPreferences (앱 샌드박스): access · refresh · device_id · 서버 주소 |
| `scoremate/ScoreSync` | §4 — 커서로 변경분 → 새 판 다운로드(`.part` → **SHA-256 검증** → 교체, `UpdateClient` 재사용) → `ids` 에 없는 악보 정리 |
| DB (v13) | `server_scores`(serverId PK, pdfFileId, versionNumber, sha256, ensembleId, ensembleName, partName, title, hidden, syncedAt) · `scoremate_state`(cursor, lastSyncAt) · 2차: `server_setlists` · `server_setlist_items` |
| 저장 위치 | §6 |

## 4. 동기화 (서버 devlog 060 §1)

```
loop:
  GET /sync/scores/?cursor=<저장값>          (처음엔 cursor 없이)
  for s in scores:
      로컬 판(versionNumber, sha256)과 다르면 → 받기 대상
      s.version.sha256 == null  → 아직 서버 처리 중, 이번엔 건너뜀(다음 동기화에 다시 온다)
      제목 · 파트 · 앙상블이 바뀌었으면 → 이름 바꾸기(§6)
  cursor 저장 (받기가 끝난 뒤 — 중간에 끊기면 다시 받는다)
  has_more 면 곧바로 다시
ids 에 없는 server_scores → 지운다(또는 사용자에게 묻기) — 삭제 · 앙상블 나가기 · 내보내기 · 앙상블 삭제 · 개인 악보로 옮김이 모두 이것
```

- **지운 목록(deleted)은 없다.** 서버는 매번 "지금 볼 수 있는 id 전부"(`ids`)를 준다. 로컬에만 있는 것이 지워진 것
- **개인 악보도 온다**(`ensemble: null`) — 내가 웹에 올린 개인 악보
- 새로 들어간 앙상블의 옛 악보도 다음 동기화에 `scores` 로 온다(서버가 "들어온 시각"을 기준으로 삼는다)
- 서버는 방금(5초 안) 바뀐 것을 다음 번으로 미룬다 — 올린 직후 바로 동기화하면 안 보일 수 있다
- 400 `{"cursor": …}` → 커서를 버리고 처음부터
- **보는 중인 파일에 새 판** → 그 파일을 닫은 뒤 교체
- **TV 에서 동기화 악보를 지우면**: `hidden` — 목록에서 숨기고 다시 받지 않음. 설정에서 "숨긴 악보 다시 받기"
- 인터넷이 없으면 조용히 건너뛴다(업데이트 확인과 같은 정책). **합주 모드 중에는 동기화하지 않는다**(다운로드가 LAN 대역을 쓰지 않게)

## 5. 악보 분석 공유 (서버 devlog 061 §2)

같은 판을 앙상블 TV 들이 **같은 분석(같은 마디 번호)**으로 쓰게 한다 — 합주 중 마디 동기화(#055)의 전제.

```
판을 받은 뒤:
  s.version.analyses 에 {analyzer: "mrgq-measures", analyzer_version ≥ 내 ANALYZER_VERSION} 가 있으면
      GET /scores/{id}/analysis/?analyzer=mrgq-measures → score_measures 로 넣는다 (직접 분석하지 않는다)
  없으면
      직접 분석(ScoreLayoutStore) → PUT /scores/{id}/analysis/
        {analyzer: "mrgq-measures", analyzer_version: ANALYZER_VERSION, sha256: <받은 파일>, data: {...}}
        201/200 → 됨 · 409 not_newer → 이미 있음(다음 동기화에 받는다) · 409 mismatch → 파일이 그 사이 바뀜 · 409 processing → 나중에
```

- **`ANALYZER_VERSION` 상수가 필요하다.** 지금은 분석기가 바뀌면 DB 마이그레이션으로 `scoreAnalyzedAt` 을 비운다(v9 · v12). 서버에서 나누려면
  "어느 분석기로 만든 결과인가"를 숫자로 가져야 한다 — 마이그레이션에서 캐시를 비울 때마다 함께 올린다
- 서버는 `data` 를 해석하지 않는다. 제안(`score_measures` 행 그대로):
  `{"schema": 1, "measures": [{"n": measureNumber, "p": pageIndex, "s": systemIndex, "box": [left, top, right, bottom], "page": [widthPt, heightPt], "ts": [num, den] | null}]}`
- 멤버 TV 는 같은 판에 이미 분석이 있으면 **더 새 analyzer_version** 일 때만 바꿀 수 있다(서버 규칙). 소유자 · 리더 계정의 TV 는 언제나
- 분석이 1MB 를 넘으면 413 — 큰 총보(수백 마디)도 위 형식이면 수십 KB

## 6. 저장 위치 · 파일 이름

- `PDFs/ScoreMate/<앙상블 이름 | 내 악보>/<제목>[ (파트)].pdf` — 기존 파일과 섞이지 않게
- **⚠️ 지금 파일 목록은 PDF 폴더를 `listFiles()` 한 단계로만 읽는다**(MainActivity · SettingsActivity · WebServerManager). 하위 폴더를 쓰려면
  목록 · 웹 서버 파일 관리 · 합주 파일 전달을 함께 바꿔야 한다 — C2 의 선행 작업. (대안: 하위 폴더 없이 `ScoreMate - <앙상블> - <제목>.pdf` — 간단하지만 목록이 길어진다. C2 착수 때 결정)
- 이름 규칙: 파일 시스템에 못 쓰는 글자(`/ \ : * ? " < > |`)는 `_`, 앞뒤 공백 · 점 제거, 길이 제한. **같은 이름이 둘이면 뒤에 ` [#<serverId>]`** — 모든 TV 가 같은 규칙을 써야 한다
- 제목 · 파트 · 앙상블 이름이 바뀌면(동기화로 옴) 파일 이름을 바꾼다 — `pdf_files` 레코드를 **update**(REPLACE 금지 규칙)해 파일별 표시 설정을 유지
- **합주 모드 파일 맞추기**: 지금 `file_change` 는 파일 이름(`file`)으로 가리킨다. ScoreMate 악보는 TV 마다 이름이 같도록 위 규칙을 지키되,
  더 확실하게 **`file_change` 에 `score_id`(서버 악보 id)를 선택 필드로 더하고**, 연주자는 `score_id` 가 있으면 `server_scores` 로 찾는다(없으면 지금처럼 이름).
  같은 앙상블 악보가 이미 동기화돼 있으면 LAN 다운로드가 필요 없다

## 7. 새 판으로 바뀔 때

파일 내용이 바뀐다 → `PdfFileSync` 가 변경을 감지해 페이지 수 · 문서 정보를 다시 읽는다. 마디는 §5 순서(서버 분석 먼저, 없으면 직접).
파일별 표시 설정(두 페이지 · 클리핑 · 메트로놈 템포/박자)은 **유지** — 같은 `pdf_files` 레코드를 update.

## 8. 단계 (서버는 모두 완료)

| 단계 | 클라이언트 | 서버 |
|---|---|---|
| C1 | ScoreMate 설정 화면 · 서버 주소 · 연결(코드 + QR + 폴링) · 토큰 저장 · 갱신 · 401 처리 · 해제 · heartbeat | S3 ✅ |
| C2 | 하위 폴더 지원(또는 이름 규칙 결정) → 동기화: 목록 · 다운로드 · SHA-256 · 새 판 교체 · `ids` 정리 · 숨김 · DB v13 | S4 ✅ |
| C3 | 파일 목록 ☁️ 표시 · 앙상블 · 파트 · 판 번호, 시작 시 자동 동기화 | S4 ✅ |
| C4 | 분석 공유(`ANALYZER_VERSION` · GET/PUT) · `file_change` 의 `score_id` · 세트리스트(연주회 곡목) 화면 | S6 ✅ |

테스트: 순수 로직(폴링 상태 기계 · 커서 · 판 비교 · `ids` 정리 · 파일 이름 규칙 · 분석 JSON 변환)은 JVM 단위, 다운로드 교체 · DB 는 계측,
실기기는 Google TV Streamer · Z18TV Pro. 서버 쪽 E2E 는 운영 서버에 임시 계정으로(서버 devlog 059 · 060 방식).

## 9. 개정 이력
- 2026-09-27: 서버 구현(0.1.0~0.6.1)에 맞춤 — 지운 목록 대신 `ids`, 개인 악보, 분석 공유 흐름 · `ANALYZER_VERSION` · data 형식,
  세트리스트 API, 해제 · heartbeat · 401 · 토큰 필드 이름 차이, 기기별 요청 제한, 하위 폴더 미지원 발견, `file_change` 의 `score_id` 제안
