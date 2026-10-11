# 118 — 앱 업데이트를 ScoreMate 서버에서 (P15 1단계)

2026-10-11 · [P15](20261010_P15_private_repo_releases_on_scoremate_plan.md) 1단계 · 서버 [P16](20261010_P16_scoremate_app_release_server_requests.md) 배포(ScoreMateServer 0.21.0) 뒤 · 사용자: "모두 진행해줘"

## 확인한 서버 (2026-10-11)
- `GET /api/v1/app-releases/android/latest/` · `?channel=beta` → v0.7.5(아직 사전 릴리스 없음), `Cache-Control: public, max-age=60`, sha256 = GitHub `SHA256SUMS.txt` 와 같음
- `…/v0.7.4/apk/` → 302 서명 URL, 없는 판 → 404. 지금 v0.7.5 의 `notes` 는 빈 값 · `version_code` null(CI 가 아직 보내지 않던 때 올라감)
- 서명 인증서 고정 값 `dece2981…c65734` = 릴리스 키(#97023ab 백업과 같음)

## 앱 (`update/`)
- `UpdateClient(server = { ScoreMateStore(activity).server })` — **서버 먼저**: `latest/?channel=stable`(사전 릴리스 받기면 `beta` = 정식 · 사전 통틀어 최신)
  - 서버가 안 되면(404 · 네트워크 · 깨진 응답) 지금까지의 GitHub 경로로 — **P15 4단계(private)까지의 폴백**, 5단계에서 지운다
  - `lastSource`("ScoreMate" · "GitHub")를 자동 확인 로그에 남긴다 — 2단계 확인용(`adb logcat | grep UpdateController`)
- `ReleaseInfo.fromScoreMate(json, server)`(Gson): version → tag · `AppVersion`, `notes` → 본문, `apk` · `size` · `sha256` → 받을 파일, `download_url` → 받기 주소
  - 받기 주소는 같은 서버의 https 만(로컬 시험 서버면 http), 없거나 다른 곳이면 `{서버}/api/v1/app-releases/android/<버전>/apk/` 로 만든다 — 판마다 고정이라 알림 뒤 늦게 받아도 만료되지 않는다(받을 때 302)
  - sha256 이 형식에 안 맞으면 없음 → 검증할 수 없어 설치하지 않는다(전과 같은 규칙). `SHA256SUMS.txt` 는 받지 않는다
- 변경 내용: 서버 `notes` 가 있으면 그것, 비었으면(지금 v0.7.5 처럼) GitHub CHANGELOG 섹션
- 확인 대화상자 문구에서 "GitHub 에서" 를 뺐다
- 테스트: `ReleaseInfoTest` — 서버 응답 읽기 · 빠진 값 · 이상한 받기 주소 · 로컬 http · 깨진 해시 · 버전 없음 · 깨진 응답

## CI
- `release.yml` "Upload APK to ScoreMate": **사전 릴리스도** 올린다(조건을 뺐다), 시험 태그(`-test`)는 건너뜀(서버가 400), `notes` = CHANGELOG 섹션(`changelog-section.md`, 빌드 정보 빼고), `version_code` = 그 태그의 `build.gradle.kts`, `notes_url` 은 뺐다(private 뒤 404), **200(같은 판 재실행)도 성공**
- `android-build.yml`: `devlog/**` · `docs/**` · `**/*.md` 만 바꾼 푸시 · PR 은 빌드하지 않는다(Actions 분, P15 §2.3). 릴리스 커밋은 `build.gradle.kts` 를 바꾸므로 빌드된다
- 판 번호: 서버는 정식 최신보다 옛 사전 릴리스를 올라오는 즉시 지운다 → v0.7.5 뒤라 **v0.7.6-beta.1**

## 확인
- [ ] CI: Release 의 ScoreMate 업로드가 201, `curl …/latest/?channel=beta` → v0.7.6-beta.1 · `notes` 있음 · `version_code` 90
- [ ] 2단계(다음 beta): 태블릿 · 휴대폰이 **서버 경로로** 업데이트 — `adb logcat | grep "자동 업데이트 확인(ScoreMate)"`
