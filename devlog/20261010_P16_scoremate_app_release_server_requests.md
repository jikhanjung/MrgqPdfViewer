# P16 — ScoreMate 서버에 요청: 앱 릴리스를 서버로 (사전 릴리스 · 변경 내용 · 최신 버전 API)

작성일: 2026-10-10
대상: ScoreMateServer `backend/` — 근거 코드는 `f99a43a` 기준(`files/app_releases.py` · `files/views.py` `AppReleaseUploadView` · `web/views.py` `download` · `apk_redirect`)
앱 쪽 계획: [`P15`](20261010_P15_private_repo_releases_on_scoremate_plan.md)

## 배경
MrgqPdfViewer 저장소를 **private 으로** 바꾼다(사용자 결정, 2026-10-10).
지금 앱 안 업데이트는 GitHub 공개 주소(`releases/latest` · `releases.atom` · 릴리스 파일 · raw CHANGELOG)를 로그인 없이 읽는다. private 이면 모두 404 가 된다.
그래서 **정식 · 사전 릴리스(beta) 모두 이 서버가 배포 정본**이 되고, 앱은 이 서버만 보고 업데이트한다.
ScoreMate 에 연결하지 않은 기기(합주 연주자 TV 등)도 업데이트해야 하므로 **읽기는 로그인 없이**.

| # | 요청 | 우선 | 앱 영향 |
|---|---|---|---|
| 1 | 올리기: beta 버전 받기 · `notes` 저장 · 정식 / beta 최신 따로 | 🔴 | 없으면 CI 가 beta 를 올리지 못한다(400) |
| 2 | 읽기: `GET /api/v1/app-releases/android/latest/?channel=stable\|beta` (로그인 없이) | 🔴 | 앱 업데이트 확인이 이것만 본다 |
| 3 | 판마다 고정 내려받기 주소 → 서명 URL 302 | 🔴 | 알림 뒤 늦게 받아도 만료되지 않게 |
| 4 | 다운로드 페이지: 변경 내용 본문 표시, GitHub 링크 · 폴백 빼기 | 🟡 | private 뒤 GitHub 링크가 404 |
| 5 | beta 보관 개수 | 🟡 | 저장소 |
| 6 | (선택) 판 목록 | ⚪ | 앱은 쓰지 않음 |

**순서**: 서버를 먼저 배포한 뒤 앱 CI 가 beta · `notes` 를 올린다(P15 §3). 그 전까지 지금 CI(정식만, `notes_url`)는 그대로 동작해야 한다 — `notes` 없이 와도 받기.

---

## 1. 🔴 올리기 — `POST /api/v1/app-releases/android/` 넓히기

### 지금
- `version` 은 `^v\d+\.\d+\.\d+$` 만 받는다(`VERSION_RE`). beta 태그는 400 "Version must look like v1.2.3."
- `latest.json` 하나 — 같거나 새 버전이면 바꾼다(`version_key` 튜플 비교)
- `notes_url` 만 받고, 없으면 GitHub 릴리스 페이지로 채운다

### 제안
- **버전**
  - `vX.Y.Z` 와 `vX.Y.Z-(alpha|beta|rc).N` 을 받는다(앱 `AppVersion` 과 같은 모양)
  - 비교는 SemVer 로 한다
    - 사전 릴리스 < 같은 `X.Y.Z` 정식
    - 꼬리는 종류 순(alpha < beta < rc), 그 안에서 숫자 비교: `beta.10` > `beta.9`
    - 예: `v0.7.4-beta.8` < `v0.7.4` < `v0.7.5-beta.1` < `v0.7.5`
  - `-test` 꼬리(릴리스 워크플로 시험 태그)는 받지 않는다
- **새 필드 `notes`**(선택, Markdown 텍스트)
  - 그 판의 CHANGELOG 섹션 본문으로, 앱이 업데이트 알림에 그대로 보인다
  - 상한은 예컨대 32 KB. 없으면 빈 문자열
  - 지금 앱 CHANGELOG 섹션은 2 ~ 4 KB 다
- **`notes_url`** 은 받되 쓰지 않아도 된다. private 뒤에는 GitHub 링크가 404 이므로 서버 페이지가 정본이다
- **최신 둘**
  - **정식 최신**(stable): 정식 버전 가운데 가장 새것
  - **beta 포함 최신**(beta): 정식 · 사전 릴리스 통틀어 가장 새것
  - 올릴 때마다 두 최신을 다시 정한다(지금처럼 옛 판을 다시 올려도 뒤로 가지 않게)
- **판마다 기록**: 버전 · 파일 이름 · sha256 · size · published_at · notes · prerelease
  - 판 목록과 보관(§5)이 필요하므로 `latest.json` 한 파일보다 DB 표(예: `AppRelease`)가 낫다
  - 파일은 지금처럼 `app/android/` 에 둔다
- **응답**(201): 지금 필드 + `prerelease`, `latest_stable`, `latest_beta`
  - 예: `{"version":"v0.7.5-beta.1","prerelease":true,"apk":"…","sha256":"…","size":6600000,"latest_stable":"v0.7.4","latest_beta":"v0.7.5-beta.1"}`
  - 지금 CI 가 보는 것은 HTTP 201 뿐이다. `latest` 필드는 남겨도 되고(= stable 최신이 됐는가) 빼도 된다

## 2. 🔴 읽기 — `GET /api/v1/app-releases/android/latest/?channel=stable|beta` (로그인 없이)

```json
{
  "version": "v0.7.5",
  "prerelease": false,
  "apk": "MrgqPdfViewer-v0.7.5-release.apk",
  "size": 6596870,
  "sha256": "b1b4e8b5…",
  "published_at": "2026-10-12T10:20:30+09:00",
  "notes": "> **…** …\n\n### 🎙 음성 명령\n- …",
  "download_url": "https://scoremate.noematica.kr/api/v1/app-releases/android/v0.7.5/apk/"
}
```
- `channel` 이 없거나 `stable` 이면 정식 최신, `beta` 이면 beta 포함 최신(§1)
- 판이 하나도 없으면 **404**. 앱은 "확인 실패"로 조용히 넘긴다
- 인증이 없다. throttle 은 느슨하게(기기마다 10분에 한 번, 기기 수는 많아야 수십 대)
- 가볍게: 매번 파일을 읽지 않는다(DB 한 줄 또는 캐시). `Cache-Control: public, max-age=60` 이면 충분하다
- `download_url` 은 §3 의 고정 주소다. **서명 URL 을 여기 바로 넣지 않는다**: 앱은 확인 → 알림 → (몇 시간 뒤) "받기" 순이라 15분 서명이 만료된다
- **앱이 의존하는 필드**: `version` · `sha256` · `size` · `download_url` · `notes` · `prerelease`. 바꿀 때 미리 알려 주세요

## 3. 🔴 판마다 고정 내려받기 주소

`GET /api/v1/app-releases/android/<version>/apk/` → **302** 서명 URL(지금 `apk_download_url` 그대로, 15분).
- 로그인 없이
- 없는 판이면 404(보관에서 지운 beta 포함)
- 받은 파일 이름은 APK 이름 그대로(지금처럼 `filename=`)
- 짧은 주소 `/apk/` 는 지금처럼 **정식 최신**으로 둔다. TV Downloader 앱 안내용이다

## 4. 🟡 다운로드 페이지 (`web/download.html`)
- "변경 내용"을 GitHub 링크(`notes_url`) 대신 저장한 `notes` 로 보인다(Markdown → HTML, 정식 최신)
- **GitHub 을 가리키는 곳을 뺀다**: private 뒤에는 404 다
  - `RELEASES_URL` · `LATEST_URL`
  - `latest.json` 이 없을 때의 GitHub 폴백(`download` · `apk_redirect`) → "아직 올린 판이 없습니다"
- (선택) 아래에 작게 "사전 릴리스: v0.7.5-beta.1 — 시험 중인 판" 링크(§3 주소)

## 5. 🟡 보관
- 정식은 모두 남긴다
- 사전 릴리스는 **최근 10개**만 남기고 파일 · 기록을 지운다(올릴 때 정리)
  - 하루에 beta 가 여러 개 나온다(2026-10-10 에만 7개, 하나에 약 6.6 MB)
  - 지금 정식 최신보다 옛 beta 는 바로 지워도 된다
- 지운 판의 §3 주소는 404 다. 앱은 늘 §2 의 최신만 받으므로 상관없다

## 6. ⚪ (선택) 판 목록
`GET /api/v1/app-releases/android/?channel=stable|beta` → 최근 순 `[{version, prerelease, published_at, size}]`. 웹에서 지난 판을 보일 때. 앱은 쓰지 않는다.

---

## 앱 · CI 쪽 (참고 — P15)
- CI `release.yml`
  - beta 도 올린다
  - `notes` = 그 판 CHANGELOG 섹션(지금 GitHub Release 본문과 같은 것)
  - `notes_url` 은 보내지 않는다
  - 실패하면(201 이 아니면) 릴리스가 실패로 끝난다
- 앱 `update/`
  - `GET {설정의 서버 주소}/api/v1/app-releases/android/latest/?channel=…` → `download_url` 로 받고 `sha256` 로 검증한다
  - 서버 주소 기본은 `https://scoremate.noematica.kr`
  - 전환 동안은 서버가 실패하면 GitHub 으로 폴백한다

## 테스트 (제안 — `tests/test_download_page.py` 를 이어 씀)
- `v0.7.5-beta.1` 업로드 201, `latest_beta` 는 그것이고 `latest_stable` 은 그대로
- SemVer
  - `v0.7.5-beta.10` > `v0.7.5-beta.9`
  - `v0.7.5` > `v0.7.5-rc.1`
  - `v0.7.4` < `v0.7.5-beta.1`
  - 옛 정식을 다시 올려도 최신은 그대로
- `-test` 꼬리 · 이상한 버전은 400
- `notes` 저장 · 응답 · 페이지 표시, `notes` 없이 와도 201(지금 CI)
- `latest/?channel=stable` 은 beta 를 무시하고, `?channel=beta` 는 새 beta 를 돌려준다. 판이 없으면 404, 로그인 없이 200
- `<version>/apk/` 는 302 서명 URL, 없는 판은 404
- 보관: beta 11개째를 올리면 가장 옛 beta 파일 · 기록이 지워진다

## 완료 기준
```bash
curl -s https://scoremate.noematica.kr/api/v1/app-releases/android/latest/?channel=beta     # 200, beta 포함 최신
curl -s https://scoremate.noematica.kr/api/v1/app-releases/android/latest/                  # 200, 정식 최신
curl -sI https://scoremate.noematica.kr/api/v1/app-releases/android/v0.7.4/apk/ | grep -i location   # 서명 URL
```
다운로드 페이지에 github.com 링크가 없다.

## 변경 이력
- 2026-10-10: 처음 작성 (P15 — 저장소 private 전환)
