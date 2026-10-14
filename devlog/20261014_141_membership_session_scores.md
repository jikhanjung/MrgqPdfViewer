# 141 — 멤버십: 합주 동안만 두는 악보(`retention: session`) · 설정 멤버십 줄

2026-10-14 · ScoreMate 서버 0.25.0(멤버십 B1, 서버 devlog 084 · 계획 P06) 운영 반영에 맞춘 앱 쪽. 서버 세션 요청
(사용자 요청 "앱 쪽 작업 진행해줘"), ScoreMate 앱(KMP)도 같은 규칙.

## 서버가 주는 것

- 등급 Lite · Pro · Maestro. 기존 계정은 Pro 무기한, 새 가입은 Pro 30일 체험 → 끝나면 Lite.
- 사용자 결정: "리더가 Maestro 일 때 Lite 멤버도 합주 악보 받되 합주 끝나면 저장은 안 하게."
- `GET /sync/scores/` 의 scores[i] `retention: "keep" | "session"`(없으면 keep). 범위 · retention 이 바뀌면 서버가 그 앙상블 악보의
  updated_at 을 올려 증분 동기화로 다시 온다.
- `GET /api/v1/membership/`(기기 토큰): tier · name · source · until · limits · omr(이번 달 사용). 쿠폰 · 체험은 웹 `/account/membership/` 에서만.

## 앱

- **DB v20** `server_scores.session`(0) — `SyncedScore.session`. 행(곡 정보 + session)은 늘, 파일은 합주 모드 동안만.
- **일반 동기화**(`ScoreMateSync.sync`, 합주 모드가 아닐 때): session 악보는 받지 않고 행만, 파일이 있으면 지운다 — 개인 메모 ·
  올리지 못한 지휘자 메모는 보관함(`.ScoreMateNotes`), `pdf_files` 레코드는 남김(곡목에서 빠질 때와 같게). 지운 수 `SyncReport.evicted`
  → 목록 다시. 동기화 형식 3 — 기존 기기가 한 번 처음부터 목록을 받아 retention 을 채운다(sha 같은 PDF 는 다시 받지 않음).
- **합주 모드 시작**(지휘자 · 연주자, 앱을 켤 때 저장된 합주 모드를 되살릴 때도) → `fetchSessionScores`: 커서는 그대로, 처음부터 목록을
  한 번 받아 session 악보만 + 곁 파일(MusicXML · 분석). 보관한 메모가 돌아온다.
- **합주 모드 끝**(직접 끄기 · 지휘자가 끝냄 — 둘 다 `deactivateCollaborationMode`) → `evictSessionScores`. 앱을 켤 때 합주 모드가
  아니면 남은 것을 지운다(합주 중에 앱이 죽었을 때).
- 언제 부를지는 `scoremate/SessionScores`(`GlobalCollaborationManager` 에서), 순서는 동기화 잠금이 지킨다. 받기 · 지우기는 잠금 안에서
  합주 모드를 다시 본다 — 차례를 기다리는 동안 합주가 끝나거나 다시 시작돼도 엇갈리지 않게. 바뀌면 목록 화면이 다시 읽는다.
- **설정 → ☁️ ScoreMate → 멤버십** 줄(연결됐고 서버가 알 때만): 등급(체험이면 "Pro 체험") · 끝나는 날. 누르면 이번 달 악보 인식,
  Lite 면 합주 악보 안내, "관리는 웹에서" — 터치는 "웹에서 관리"(브라우저), TV 는 QR.

## 남긴 것 · 확인할 것

- 합주 연주자가 지휘자에게서 받은 악보(HTTP 8090 → `cacheDir/ensemble`, 200MB)와 짝 기기가 주 기기에서 받은 악보(9095)는 서버 악보가
  아니라 이번 규칙 밖 — 합주가 끝나도 캐시에 남는다. 맞출지는 서버 · 사용자와 정할 일.
- 세트리스트에 든 session 곡은 합주 밖에서 파일이 없어 "N곡 중 M곡" 의 빠진 곡으로 센다.
- 실기기: Lite 계정 + Maestro 리더 앙상블로 합주 켜기 · 끄기, 합주 중 앱 강제 종료 뒤 다시 켜기.
