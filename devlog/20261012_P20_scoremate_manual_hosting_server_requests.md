# P20 — ScoreMate 서버에 요청: 사용자 설명서를 서버에서 보이기

작성일: 2026-10-12
대상: ScoreMateServer `backend/` — 앱 릴리스 보관(P16, 서버 0.21.0 ~)과 같은 자리 · 같은 토큰
앱 쪽 계획: [`P19`](20261012_P19_user_manual_rewrite_plan.md) 4단계 (1 ~ 3단계는 [#133](20261012_133_user_manual_rewrite.md))

## 배경
MrgqPdfViewer 의 사용자 설명서(`docs/User_Manual.md` 한국어 · `docs/User_Manual.en.md` 영어)를 v0.8 에 맞게 다시 썼다.
저장소를 **private 으로** 바꾸면(P15 4단계) GitHub 의 `docs/` 를 사용자가 열 수 없다. TV 에는 대개 브라우저도 없다.
그래서 **설명서도 앱처럼 서버가 보관 · 공개** 하고, 앱은 설정 → 앱 정보 → **설명서** 줄에서 그 페이지를 연다(TV 는 주소 + QR 을 띄워 휴대폰으로 본다).
설명서의 정본은 이 저장소의 Markdown 이고(코드와 같은 커밋에서 고친다), **릴리스 CI 가 판마다 올린다** — APK 와 `notes` 를 올리는 것과 같은 단계.

| # | 요청 | 우선 | 앱 영향 |
|---|---|---|---|
| 1 | 올리기: `POST /api/v1/app-manuals/android/` — 판 · 문서 · 언어마다 Markdown | 🔴 | 없으면 CI 가 올리지 못한다 |
| 2 | 웹 페이지: `GET /manual/` — Markdown → HTML, 로그인 없이, 휴대폰에서 읽기 좋게 | 🔴 | 앱 "설명서" 줄 · TV QR 이 여는 주소 |
| 3 | 문서 사이 링크 바꾸기 | 🟡 | 설명서 → 음성 명령 목록 링크 |
| 4 | 보관 | 🟡 | 저장소(작다) |
| 5 | (나중) 그림 | ⚪ | P19 5단계 — 지금은 글 · 표만 |

**순서**: 서버를 먼저 배포한 뒤 앱 CI 에 올리기 단계를 더한다. 그 다음 앱에 "설명서" 줄(beta). **P15 4단계(private 전환) 전에** 끝낸다 — 사용자는 아직 업데이트 확인이 안 된 기기들이 있어 전환을 미뤄 두었다(2026-10-12).

---

## 1. 🔴 올리기 — `POST /api/v1/app-manuals/android/`

인증은 앱 릴리스와 같은 `Authorization: Bearer APP_RELEASE_TOKEN`. multipart.

| 필드 | |
|---|---|
| `version` | 앱 판 태그 — 앱 릴리스와 같은 규칙(`vX.Y.Z` · `vX.Y.Z-(alpha\|beta\|rc)[.N]`, `-test` 거절) |
| `doc` | 문서 이름: `manual` · `voice-commands` (아래 표) |
| `lang` | `ko` · `en` |
| `filename` | 저장소의 파일 이름(예: `User_Manual.en.md`) — §3 링크 바꾸기에 쓴다 |
| `markdown` | 본문(UTF-8 Markdown), 256 KB 까지. 지금 한국어 설명서 약 25 KB |

CI 가 한 판에 올리는 것:

| `doc` | `lang` | `filename` | 비고 |
|---|---|---|---|
| `manual` | `ko` | `User_Manual.md` | |
| `manual` | `en` | `User_Manual.en.md` | |
| `voice-commands` | `ko` | `Voice_Commands.md` | 한 파일에 한국어 표 + 영어 표. 영어판은 따로 없다 — `en` 으로 물으면 `ko` 를 보인다(§2) |

- 응답: **201** 새로 저장 · **200** 같은 (`version`, `doc`, `lang`)을 다시 올림 — **내용을 바꾼다**(앱 판 사이에 설명서만 고쳐 다시 올릴 수 있게, CI 수동 실행) · **400** 형식 오류
  - 응답 예: `{"version":"v0.8.1","doc":"manual","lang":"ko","size":25311,"url":"https://scoremate.noematica.kr/manual/?lang=ko&v=v0.8.1"}`
- 그 판의 APK 가 서버에 아직 없어도 받는다(CI 가 APK 다음에 올리지만 순서에 기대지 않게)
- 400 모양은 앱 릴리스와 같게: `{"error":{"details":{"<필드>":"…"}}}`

## 2. 🔴 웹 페이지 — `GET /manual/` (로그인 없이)

| 매개변수 | |
|---|---|
| `lang` | `ko` · `en`. 없으면 `Accept-Language` 로, 그것도 모르면 `ko` |
| `v` | 앱 판(예: `v0.8.1-beta.1`). **그 판이 있으면 그 판, 없으면 정식 최신** — 아래 |
| (경로) `/manual/voice-commands/` | 다른 문서 |

**어느 판을 보이나**
- `v` 가 없으면 **정식 최신**(앱 릴리스의 `latest_stable` 과 같은 판, 그 판의 설명서가 없으면 설명서가 있는 가장 새 정식)
- `v` 가 있고 그 판의 설명서가 있으면 그 판 — beta 를 쓰는 기기는 자기 판의 설명서를 본다
- `v` 가 있는데 없으면(지운 beta · 아직 안 올림) 정식 최신. 404 를 내지 않는다
- 페이지 위에 작게 "v0.8 기준 · 다른 언어: English" 같은 줄 — 언어 바꾸기 링크는 같은 판 · 다른 `lang`

**그리기**
- GitHub 식 Markdown: **표** · 인용(`>`) · 굵게 · 인라인 코드 · 목록(들여쓴 목록 포함) · 가로줄(`---`) · 링크
- 제목에 **id**(GitHub 과 같은 slug — 소문자, 공백 → `-`, 문장부호 · 이모지 빼고 한글은 그대로. 예: `## 5.3 보기 메뉴` → `53-보기-메뉴`). 개발 문서가 이 앵커로 링크한다
- 맨 위에 `##` 장 목차(접을 수 있으면 좋다). 넓은 화면이면 옆에
- **휴대폰에서 읽기**가 주 용도(TV 의 QR 을 찍어 연다): 글 폭 맞춤, 표는 가로 스크롤, 글자 16px 안팎. 어두운 화면이면 좋다(앱이 어둡다)
- 서버에서 HTML 로 만들어 보낸다(JS 없이도 읽히게). 받은 Markdown 은 CI 가 올린 것이지만 **HTML 은 정리**(raw HTML · `javascript:` 링크 빼기)
- `Cache-Control: public, max-age=300` 정도

**짧은 주소**: `/manual/` 자체가 짧아 QR 에 그대로 쓴다. 앱은 `https://scoremate.noematica.kr/manual/?lang=en&v=v0.8.1` 처럼 연다(서버 주소는 앱 설정의 ScoreMate 서버 주소).

## 3. 🟡 문서 사이 링크 바꾸기

설명서 안의 상대 링크는 저장소 기준이다:
- `[음성 명령 목록](Voice_Commands.md)` · `[English](User_Manual.en.md)` · `[한국어](User_Manual.md)`
- `Voice_Commands.md` 안: `[사례집](Voice_Command_Examples.md)` · `[devlog/P12](../devlog/…)`

그릴 때:
- **같은 판에 올라온 `filename`** 이면 그 페이지로(`#앵커` 는 그대로 붙임) — `User_Manual.en.md` → `/manual/?lang=en&v=…`, `Voice_Commands.md` → `/manual/voice-commands/?v=…`
- **올라오지 않은 파일**(`../devlog/…` · `Voice_Command_Examples.md` 등)은 **링크를 빼고 글자만** 남긴다 — private 뒤 GitHub 링크는 404 다
- `http(s)://` 바깥 링크는 그대로

## 4. 🟡 보관
- 앱 릴리스와 같게: **정식 판은 모두**, 사전 릴리스는 **최근 10개**(정식 최신보다 옛 beta 의 설명서는 지워도 된다). 판마다 수십 KB 라 부담은 작다
- 지운 판을 `v` 로 물으면 §2 대로 정식 최신

## 5. ⚪ (나중) 그림
P19 5단계에서 몇 장(ScoreMate 연결 QR 화면 · 터치 도구 막대 · 설정 두 칸 · 메트로놈 대화상자)을 넣을 수 있다. 저장소에서는 `docs/manual/img/<lang>/<이름>.png` 를 상대 경로로 링크한다.
그때 올리기에 그림 파일을 더하는 방법(같은 엔드포인트에 `images[]` 등)을 따로 요청하겠다. 지금은 필요 없다.

---

## 앱 · CI 쪽 (참고)
- CI `release.yml`: APK 올리기 다음 단계 "Upload manual to ScoreMate" — 위 표의 셋을 같은 토큰으로. `-test` 태그 · 토큰 없음은 건너뜀(APK 와 같은 조건). **실패하면 릴리스가 실패로**(APK 는 이미 올라감 — 다시 실행하면 APK 는 200, 설명서는 200 으로 바뀜)
  - 설명서만 고쳤을 때: Release 를 workflow_dispatch 로 그 태그에 다시 돌리면 같은 판의 설명서가 바뀐다. 아니면 설명서만 올리는 작은 워크플로를 따로 둘 수도 있다(정하면 알림)
- 앱(P19 4단계, 서버 배포 뒤 beta): 설정 → 📊 앱 정보 → **설명서** 줄
  - 태블릿 · 휴대폰: 브라우저로 `{서버}/manual/?lang={앱 언어}&v={앱 판}`
  - TV: 같은 주소 + QR 대화상자(ScoreMate 연결 화면과 같은 모양) — 휴대폰으로 찍어 본다
  - 서버에 닿지 못해도 줄은 보인다(페이지를 열 때 브라우저가 알린다)

## 테스트 (제안)
- 올리기: `manual`/`ko` 201, 같은 것 다시 200 · 내용 바뀜, 이상한 `version` · `doc` · `lang` 400, `-test` 400, 256 KB 넘음 400, 토큰 없음 401
- 고르기: `v` 없음 = 정식 최신, 있는 beta `v` = 그 판, 없는 `v` = 정식 최신(200), 언어 없음 = `Accept-Language`, `voice-commands?lang=en` = `ko`
- 그리기: 표 · 인용 · 들여쓴 목록이 HTML 로, 제목 id(`53-보기-메뉴`), raw HTML · `javascript:` 링크가 빠짐
- 링크: `Voice_Commands.md` → `/manual/voice-commands/?v=…`, `User_Manual.en.md#2-installing--updates--language` → 앵커 유지, `../devlog/x.md` → 글자만
- 보관: beta 11번째 설명서를 올리면 가장 옛 beta 설명서가 지워짐

## 완료 기준
```bash
curl -s -o /dev/null -w '%{http_code}\n' https://scoremate.noematica.kr/manual/                       # 200 (정식 최신, 한국어)
curl -s 'https://scoremate.noematica.kr/manual/?lang=en' | grep -c '<table'                           # 표가 HTML 로
curl -s 'https://scoremate.noematica.kr/manual/?lang=ko&v=v9.9.9' | grep -o 'v0\.[0-9.]* 기준' | head -1   # 없는 판 → 정식 최신
curl -s 'https://scoremate.noematica.kr/manual/voice-commands/' | grep -c 'github.com'                # 0
```

## 서버 검토 — 정한 것 (2026-10-12, 서버 세션)

서버 구현은 **ScoreMate 앱 작업이 끝난 뒤**(사용자 결정). 방향은 그대로, 아래로 바뀜:

| | 정한 것 |
|---|---|
| 링크 바꾸기 | **볼 때** 한다 — 서버는 Markdown 만 저장, 그릴 때 그 판에 올라온 `filename` 목록으로 바꾼다. 올리는 순서에 기대지 않음 |
| 엔드포인트 | 정식 `POST /api/v1/app-manuals/` + **`app`**(기본 `mrgqpdfviewer`) — 서버가 앱 릴리스에도 `app` 칸을 더하는 중(새 ScoreMate 앱). `/api/v1/app-manuals/android/` 는 별칭. `/manual/` = MrgqPdfViewer 설명서. **CI 는 정식 경로에 `app=mrgqpdfviewer` 를 명시** |
| `doc` | 두 값으로 묶지 않고 형식 `[a-z0-9-]{1,40}` 만 — 주소 `/manual/<doc>/` |
| 언어 대체 | 일반 규칙: 그 `lang` 이 없으면 `ko` |
| 다시 올리기 | 200 으로 바꾸고 바뀐 기록(언제 · 크기)을 남김 |
| 보관 | 앱 릴리스와 같은 규칙을 **판 순서만으로**(APK 없이도 받으므로) |
| 그리기 | markdown-it-py(raw HTML 끔 · `javascript:` 막음, `table` 켬) |
| 앵커 | GitHub 이 렌더한 HTML(`Accept: application/vnd.github.html` 의 `id="user-content-…"`)에서 뽑은 "제목 → 앵커" 짝을 서버 테스트에 고정 — 예: `2. 설치 · 업데이트 · 언어` → `2-설치--업데이트--언어`, `6.5 🎤 Listen & turn pages (tablet · phone)` → `65--listen--turn-pages-tablet--phone`, `🎙 음성 명령 목록` → `-음성-명령-목록`, `👂 계속 듣기 (시험, v0.7.1-beta.1)` → `-계속-듣기-시험-v071-beta1`. 규칙: 소문자, `·` `&` `.` `()` `,` `'` · 이모지 지움, 공백 하나하나 `-`, 앞뒤 `-` 안 다듬음, 중복은 `-1` |

## 변경 이력
- 2026-10-12: 처음 작성 (P19 4단계 — 사용자 "서버 요청 문서 써줘")
- 2026-10-12: 서버 검토 반영 — 볼 때 링크 바꾸기 · `app` 칸(`/api/v1/app-manuals/`) · `doc` 형식만 · lang 대체 일반 규칙 · 앵커 짝(GitHub 렌더 기준)
