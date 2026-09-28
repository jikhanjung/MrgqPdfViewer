# HANDOFF — 현재 작업 인계 노트

마지막 갱신: 2026-09-28
브랜치: `main` (origin/main 과 동기화, 미푸시 커밋 0개)
버전: **v0.3.3** (versionCode 29) — 릴리스 완료, 실기기(Z18TV Pro)는 앱 안 업데이트로 받는다
관련 문서: [CLAUDE.md](CLAUDE.md) (가이드 · 알아 둘 결정) · [TODOs.md](TODOs.md) · [CHANGELOG.md](CHANGELOG.md) · [devlog 인덱스](devlog/README.md)

---

## 0. 지금 상태 한눈에

2026-09-26 ~ 28 사흘 동안 v0.2.4 → v0.3.3. 큰 줄기 셋:

1. **ScoreMate 클라이언트** (P05 · P06, devlog #058 ~ #064): TV 연결(QR · 토큰), 악보 동기화, 한 번에 한 서재, 합주 파일을 내용 해시로, 세트리스트
2. **파트보 보기 · 반주 연습** (P07, devlog #065): 총보에서 고른 보표만 잘라 이은 벡터 PDF, 파트 화면 악보 연동 · 합주, MusicXML 로 다른 파트 반주
3. **서버와 나눈 일** (P06 §10 ~ §12): 서버가 PDF → MusicXML 인식, 앱 `score/` 를 파이썬으로 옮겨 서버에서 분석 → TV 는 곁 파일(`.musicxml` · `.layout.json`)로 받아 쓴다

오늘 작업 요약: [`devlog/20260928_065_...`](devlog/20260928_065_part_view_accompaniment_server_analysis.md). 단계별 결정 · 측정: [`P07`](devlog/20260928_P07_part_view_plan.md).

## 1. 다음 할 일 (우선순위)

1. **합주 중 파트 보기 두 대 실측** — 태블릿(한 쪽) → TV(두 쪽) 넘김은 확인했고 뒤로 넘김의 짝 맞춤을 고쳤다(재확인 전). 두 쪽 지휘자 → 한 쪽 연주자는 쓰지 않는다(P07 4단계 표). 남은 확인:
   - 지휘자 전체 악보 · 연주자 파트보: 넘김(`page_change`) · 곡 바꾸기(`file_change` 목표 쪽) · 합주 메트로놈(시작 마디 · 현재 마디 · 일시정지 커서)
   - 반대(지휘자 파트보): 지휘자가 보내는 쪽 번호가 원본 쪽으로 바뀌는지(`outgoingPage`)
   - 쪽 번호 옮기기는 `PartLayout.sourcePageFor` · `dstPageForSource`, 뷰어 `outgoingPage` · `incomingPageIndex`
2. **반주 다듬기** — 셈여림 · 아티큘레이션, 반복 기호. 반주 크기 따로는 끝남. 악기 소리(SF2)는 나중에(사용자 결정)
3. **빈 보표 숨긴 총보** — 시스템마다 보표 수가 다르면 지금은 파트 보기를 막는다(`ScoreParts.Result.VaryingStaves`). 지금 쓰는 기타 앙상블 악보에는 없었다
4. **곡 단위 합주** (`work_id + 마디`, 서버 P01) — 각자 다른 PDF 로 같은 곡 합주. TV 요청 문서(P08)부터
5. 합주 Phase 0 동기 넘김 — 기본 OFF · 보류(CLAUDE.md "합주")

## 2. 일하는 방법 (이 환경)

- **빌드 · 테스트는 WSL 에서 `powershell.exe` 로 Windows Gradle** — `--no-daemon` 필수, 로그 UTF-16. 명령은 CLAUDE.md "빌드 명령어"
- **실기기**: Z18TV Pro `adb 192.168.55.75:5555`(Android 14). **샤오신패드 12.7**(태블릿, #066) — 무선 디버깅: "페어링 코드로 기기 페어링" 창의 포트로 `adb pair <ip>:<포트> <코드>` 후 화면의 IP:포트로 `adb connect`(포트는 켤 때마다 바뀐다). 와이파이가 느려 `adb install` 이 가끔 실패 → `adb push` + `pm install -r`. release 빌드는 덮어써도 데이터가 남는다
  - 절전에서 깬 직후엔 adb 가 "device not found" — `adb disconnect` 후 다시 `connect`
  - 분석 · 파트 · 반주 로그: `adb logcat -d | grep -aE "ScoreLayout|ServerLayouts|PartPdfBuilder|파트 보기|반주|ScoreMate 동기화"`
  - 악보 분석은 **필요할 때만**(파트 보기 · 마디 박스 표시 · 악보 연동) — 파일을 열기만 해서는 돌지 않는다
- **계측 테스트는 CI** 에서(main 푸시 · 수동, 회당 5 ~ 6분). TV 에 debug 를 올리면 release 와 서명이 달라 지워야 하므로 하지 않는다
- **서버 세션이 따로 있다**: ScoreMateServer 작업을 하는 다른 Claude 세션이 이 저장소의 `devlog/*P06*` 을 고쳐 main 에 푸시한다
  - 푸시 · 릴리스 전에 **`git fetch` 후 rebase**. 태그는 main 푸시가 성공한 뒤에만(파이프로 푸시 실패를 가리지 말 것)
  - 서버 계약은 P06 에서 읽고, TV 반영을 같은 절에 "앱 반영"으로 적는다
- **릴리스**: versionCode/Name → CHANGELOG 섹션 → 커밋 · 푸시 → **CI 초록 확인** → `v*` 태그 → Release 워크플로 → `releases/latest` 확인(앱 안 업데이트가 이것을 본다)

## 3. 자주 부딪힌 것

- **동기화 커서**: 새 필드(MusicXML · 편곡자 등)를 앱이 알게 되면 `ScoreMateSync.SYNC_FORMAT` 을 올린다 — 옛 앱이 이미 넘긴 변경을 한 번 처음부터 다시 받는다(지금 2)
- **곡 정보만 바뀐 동기화**는 `SyncReport.updated` 로 세야 목록이 다시 그려진다
- **DB 스키마**: 릴리스 전이라도 기기에 올라간 버전의 스키마를 바꾸면 Room 이 기기에서 깨진다 — 새 버전 + 마이그레이션으로
- **파트보 캐시**(`cacheDir/parts`): 이름에 원본 크기 · 수정 시각 · 서버 분석 파일 시각 · 보표 · 형식 번호. 배치 규칙을 바꾸면 `PartPdfBuilder.FORMAT` 을 올린다(지금 8)
- 파트 이름 오인식(예: "예원" → "예완")은 서버 웹에서 고친다 — MusicXML 이 바뀌어 TV 가 새로 받는다
