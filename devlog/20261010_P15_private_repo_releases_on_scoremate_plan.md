# P15 — 저장소를 private 으로: 릴리스 · 업데이트를 ScoreMate 서버로 (계획)

2026-10-10 · 사용자: "현재는 github 와 ScoreMate 서버에 흩어져 있는 느낌이라.. MrgqPdfViewer repo 를 private 으로 변경하려고 하거든.
그러려면 릴리스 관련된 건 다 ScoreMate 쪽에 가야 하니까."

서버에 요청할 것: [`P16`](20261010_P16_scoremate_app_release_server_requests.md).

## 0. 목표 · 원칙
- **배포 정본 = ScoreMate 서버**
  - 정식과 사전 릴리스(beta) 모두 서버에 올리고, 앱은 서버만 보고 업데이트한다
  - GitHub 은 코드 · CI 만 맡는다(private)
- **연결하지 않은 기기도 업데이트한다**: 업데이트 확인은 로그인 없이 읽는 API
  - ScoreMate 에 연결하지 않은 TV · 합주 연주자 기기도 있다
- **옛 기기를 끊지 않는다**
  - 업데이트 확인 오류는 조용히 넘긴다(#054). 그래서 private 으로 바꾸는 순간 옛 판 기기는 **아무 표시 없이** 업데이트를 못 받게 된다
  - 서버를 보는 판을 **모든 기기에 깐 뒤에** 바꾼다(§3)

## 1. 지금 GitHub 공개에 기대는 것

| 쓰는 곳 | GitHub 주소 | private 이면 |
|---|---|---|
| 정식 확인(`UpdateClient.fetchLatestStable`) | `github.com/…/releases/latest` 302 의 태그 | 404 — 확인 실패(조용히) |
| beta 확인(`newestFromFeed`, #073) | `github.com/…/releases.atom` | 404 |
| APK · 검증(`downloadApk`, `fetchChecksum`) | `…/releases/download/<tag>/MrgqPdfViewer-<tag>-release.apk` · `SHA256SUMS.txt` | 404 |
| 변경 내용(`fetchReleaseNotes`) | `raw.githubusercontent.com/…/<tag>/CHANGELOG.md` 의 그 판 섹션 | 404 |
| 서버 다운로드 페이지 | 변경 내용 링크 `notes_url` = GitHub 릴리스 페이지, `latest.json` 이 없으면 GitHub 으로 | 404 |
| 문서 | README · CLAUDE.md · User_Manual 의 GitHub 릴리스 · 설치 안내 | 못 봄 |

서버(ScoreMateServer `files/app_releases.py`)도 지금은 이렇다:
- **정식만 받는다**(`VERSION_RE = ^v\d+\.\d+\.\d+$`)
- 최신은 `latest.json` 하나뿐이다
- 변경 내용은 GitHub 링크로만 남는다
- CI 도 정식 · 릴리스 키 서명일 때만 올린다(`release.yml` "Upload APK to ScoreMate")

## 2. 바꿀 것

### 2.1 서버 — [P16](20261010_P16_scoremate_app_release_server_requests.md)
- beta 받기: SemVer 비교를 하고, 정식 최신과 beta 포함 최신을 따로 둔다
- 변경 내용 본문을 저장한다
- 로그인 없이 읽는 `GET …/app-releases/android/latest/?channel=stable|beta`
- 판마다 안정된 내려받기 주소: 서명 URL 로 302
- 다운로드 페이지에서 GitHub 을 뺀다
- beta 보관 개수를 정한다

### 2.2 앱 `update/`
- **확인**
  - `GET {서버}/api/v1/app-releases/android/latest/?channel=stable`(사전 릴리스 받기를 켰으면 `beta`)
  - `{서버}` = 설정 → ☁️ ScoreMate 의 서버 주소(`ScoreMateStore.server`, 기본 `ScoreMateProtocol.DEFAULT_SERVER`). 연결 여부와 무관하다
- **응답 → `ReleaseInfo`**
  - tag = version
  - body = `notes`: 변경 내용을 같이 받으므로 CHANGELOG raw 를 따로 받지 않는다
  - apk = (`download_url`, `size`, `sha256`)
- **검증**: 응답의 `sha256` 으로 한다. `SHA256SUMS.txt` 를 따로 받지 않는다. 다 받은 뒤 해시가 다르면 지우는 지금 규칙은 그대로다
- **받기**: `download_url` 은 판마다 고정 주소이고, 받을 때마다 서명 URL 로 302 된다
  - 알림을 띄우고 몇 시간 뒤 "받기"를 눌러도 만료되지 않는다
  - `HttpURLConnection` 이 https → https 리다이렉트를 따라간다
- **전환 동안 GitHub 폴백**: 서버가 실패하면(404 · 네트워크) 지금 GitHub 경로로 확인한다. private 뒤에는 폴백도 실패하므로 §3 5단계에서 지운다
- 비교 · "나중에" · 10분 주기 · 합주 중 제외 같은 나머지 규칙은 그대로다(`UpdateController`)
- **테스트**
  - 응답 파싱(`ReleaseInfo.fromScoreMate`)
  - beta 채널이 정식보다 새 beta 를 고르는지
  - `download_url` 이 없거나 이상할 때
  - 해시가 다를 때

### 2.3 CI
- **`release.yml` "Upload APK to ScoreMate"**
  - **사전 릴리스도 올린다**: `if: prerelease != 'true'` 를 뺀다
  - `notes` 를 보낸다: "Extract release notes from CHANGELOG" 단계가 이미 뽑은 그 판 섹션을 그대로 보낸다
  - `notes_url` 은 뺀다(private 뒤 404)
  - 서버가 P16 을 배포하기 전에 이 변경을 넣으면 beta 업로드가 400 이 된다 → **서버 먼저**
- **GitHub Release 는 계속 만든다**(private 이라 개발자만 보는 보관용). 지우는 쪽보다 비용이 없다 — 정할 것 2
- **Actions 분**
  - private 저장소는 무료가 아니다: Free 월 2,000분, Pro 3,000분
  - 2026-10-10 실측(작업 분)

    | 무엇 | 분 | 언제 |
    |---|---|---|
    | Android Build | 약 6 | main 푸시마다 |
    | Instrumentation | 약 14 (API 30 · 34 두 에뮬레이터) | 앱 코드가 바뀐 푸시마다 |
    | Release | 약 8 | 판마다 |

  - 이날 하루(빌드 8 · 계측 6 · 릴리스 5)에 **약 170분**이 들었다. 이 속도면 Free 한도가 2주 안에 찬다
  - **Android Build 에 `paths-ignore`**(`devlog/**` · `docs/**` · `*.md`): 문서만 바꾼 푸시를 빌드하지 않는다. 서버 세션의 P06 푸시도 여기에 든다
  - **Instrumentation 횟수 줄이기** — 정할 것 1
    - (가) 지금처럼 앱 코드 푸시마다: 분이 넉넉한 요금제일 때
    - (나) 릴리스 커밋(버전을 올린 `app/build.gradle.kts`) · 수동에서만: 릴리스 전 게이트는 그대로 지킨다
    - (다) 자체 러너(이 PC): 분은 0 이지만 Windows 에뮬레이터 설정이 필요하고, PC 가 꺼져 있으면 멈춘다
- 릴리스 스크립트(대기 → 태그 → 확인)는 그대로다. 단, 확인에 `releases.atom` 대신 서버 `latest/` 응답을 본다

### 2.4 문서
- CLAUDE.md
  - "`.guides` … 이 저장소는 public 이므로 가이드를 **커밋하지 않는다**" — 전환 뒤 다시 정한다(private 이어도 devdocs 와 따로 둘지)
  - 릴리스 절차
  - 앱 안 업데이트 설명(#054 · #073)
- `docs/User_Manual.md` · README: 설치 · 업데이트 안내를 `scoremate.noematica.kr/download/` · `/apk/` 로
- `docs/4_Build_CI_Release.md`: 배포 정본이 서버라는 것, Actions 분

## 3. 순서

| 단계 | 할 일 | 끝난 기준 |
|---|---|---|
| 0 | 서버 P16 배포(서버 세션) | `curl …/latest/?channel=beta` 가 응답하고, beta 업로드가 201 |
| 1 | 앱(서버 먼저 + GitHub 폴백) · CI(beta · notes 올리기, paths-ignore) → **v0.7.5-beta.1** | 서버 다운로드 기록에 beta.1 |
| 2 | **v0.7.5-beta.2** 로 beta 기기(태블릿 · 휴대폰)가 **서버 경로로** 업데이트되는지 | 기기 로그에 서버 주소로 받은 기록, 설정 → 업데이트 확인 |
| 3 | 정식 **v0.7.5**: beta 를 안 받는 TV 들이 **GitHub 경로로** 받아 서버를 보는 판이 된다 | Z18TV × 2 · Google TV Streamer · 태블릿 · 휴대폰 모두 ≥ v0.7.5 (설정 → 앱 정보) |
| 4 | **사용자가 GitHub 설정에서 private 으로** | 각 기기 "업데이트 확인"이 오류 없이 "최신", 다운로드 페이지 정상 |
| 5 | GitHub 폴백 코드 · 문서 정리 → v0.7.6 | 앱에 `github.com` 주소 없음(`grep`) |

- 3 → 4 사이 **기기 확인을 건너뛰지 않는다**. 못 받은 기기는 adb 로 깔아야 한다(TV 는 `pm install -r`, CLAUDE.md)
- 4 를 되돌리기는 쉽다(public 으로 다시). 그래도 그 사이 옛 기기는 업데이트를 못 받는다

## 4. 정할 것 — **2026-10-10 모두 추천대로**(사용자: "결정해야 하는 사항들 모두 추천한 내용대로 진행하는 게 좋겠어.")

| # | 질문 | 추천 → 결정 |
|---|---|---|
| 1 | Instrumentation 횟수 (§2.3) | 요금제에 따라. Free 면 (나) 릴리스 커밋 · 수동만, Pro 이상이면 (가) + paths-ignore 로 시작해 실측 → **public 인 동안은 지금(가) 그대로(무료), private 으로 바꾸는 4단계에서 (나)로**. 요금제가 넉넉하다고 확인되면 (가)로 되돌린다 |
| 2 | GitHub Release 를 계속 만들까 | 만든다 — private 보관용, 비용 없음 |
| 3 | 업데이트 확인도 설정의 서버 주소를 따를까 | 따른다 — 시험 서버로 바꾸면 업데이트도 그쪽(로컬 시험에 편함) |
| 4 | 옛 beta(v0.7.4-beta.*)를 서버에 올릴까 | 안 올린다 — v0.7.5-beta.1 부터 |

## 5. 위험
- **옛 기기가 끊긴다**: §3 3단계 확인으로 막는다. 끊기면 adb 로 깐다
- **서버가 멈추면 업데이트도 멈춘다**: private 뒤에는 GitHub 이 대신하지 못한다. 기기는 조용히 다음 확인(10분)을 기다리므로 견딜 만하다
- **서명 URL 만료**: 판마다 고정 주소 + 302(P16 §3)로 피한다
- **서버 저장소**: APK 하나에 약 6.6 MB이고, beta 는 하루에도 여러 개다 → beta 는 최근 N 개만 남긴다(P16 §5)
- **10분마다 확인 × 기기 수**: 작은 JSON 이다. 서버는 파일을 읽지 않고 캐시한 최신만 돌려주면 된다(P16 §2)
