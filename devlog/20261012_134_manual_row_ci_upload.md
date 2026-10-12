# 134 — 📖 설명서 줄 · 릴리스 CI 가 설명서를 서버에 (P19 4단계, v0.8.1-beta.2)

2026-10-12 · 계획 [`P19`](20261012_P19_user_manual_rewrite_plan.md) 4단계 · 서버 요청 [`P20`](20261012_P20_scoremate_manual_hosting_server_requests.md)(ScoreMateServer 0.23.1 운영 배포, 서버 devlog P05)

## 한 것
- **앱**: 설정 → 📊 앱 정보 → 묶음 "도움말" → **📖 설명서**
  - 주소 `{설정의 서버}/manual/?lang={en|ko}&v=v{versionName}` — `scoremate/ManualLink`(단위 테스트 `ManualLinkTest`). 언어는 지금 화면 글 언어(`AppLanguage.locale`), 영어가 아니면 한국어
  - 태블릿 · 휴대폰: `ACTION_VIEW` 로 브라우저. 브라우저가 없으면 아래 QR 로
  - TV: 브라우저가 없는 경우가 많아 곧바로 QR(220dp, `DeviceLinkDialog.qrBitmap`) + 주소 글 대화상자 — 휴대폰으로 찍어 본다
  - 서버에 닿지 못해도 줄은 보인다(페이지를 열 때 브라우저가 알린다). 이 판이 서버에 없으면 서버가 정식 최신 · 한국어로
- **CI** `release.yml`: APK 올리기 다음 "Upload manual to ScoreMate" — `POST /api/v1/app-manuals/`, `app=mrgqpdfviewer` · `version=<태그>` · `doc` · `lang` · `filename` · `markdown=@파일`
  - `manual ko docs/User_Manual.md` · `manual en docs/User_Manual.en.md` · `voice-commands ko docs/Voice_Commands.md`
  - 조건은 APK 와 같다(토큰 · 릴리스 키 서명 · `-test` 제외). 201 · 200 아니면 릴리스 실패
  - 설명서만 고쳤으면 Release 를 그 태그로 workflow_dispatch(APK 는 같은 sha256 → 200, 설명서는 200 으로 내용 바뀜)
- **설명서**: 머리말에 "설정 → 앱 정보 → 설명서 로 엽니다", 10장 표의 앱 정보 줄에 설명서(한 · 영)

## 남은 것
- 정식 설명서는 다음 정식 판(v0.8.1)부터 서버에 있다 — 그 전엔 beta 판 주소만 열린다. 정식 v0.8.0 기기에는 이 줄이 없어 문제 없음
- 실기기: 태블릿 브라우저 · TV QR (CHANGELOG 확인 목록)
