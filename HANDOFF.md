# HANDOFF — 현재 작업 인계 노트

마지막 갱신: 2026-10-20
브랜치: `main` (origin/main 과 동기화)
버전: **v0.9.1** (versionCode 137, 2026-10-15 정식 — 곡 목록의 별 ★ 악보 연동 · ★★ MusicXML 까지, #145). 그 앞 v0.9.0(2026-10-14) = 📖 두 대로 펼쳐 보기(P22) · ☁️ ScoreMate 멤버십. private 전환(P15 4단계)은 사용자가 미룸. 실기기는 앱 안 업데이트로 받는다(태블릿 · TV 모두 사전 릴리스 받기 켜 둠), 급하면 adb 로 릴리스 APK 를 직접
관련 문서: [CLAUDE.md](CLAUDE.md) (가이드 · 알아 둘 결정) · [TODOs.md](TODOs.md) (할 일 · **실기기 확인 목록**) · [CHANGELOG.md](CHANGELOG.md) · [devlog 인덱스](devlog/README.md) · [프로토콜](docs/protocols/README.md) · [사용자 설명서](docs/User_Manual.md) · [음성 명령 목록](docs/Voice_Commands.md) · [사례집](docs/Voice_Command_Examples.md)

---

## 0. 지금 상태 한눈에

v0.7.4(2026-10-10) 뒤로 닷새 동안 v0.9.1 까지. 큰 줄기:

1. **📖 두 대로 펼쳐 보기** (P22, #138 ~ #144, v0.8.5-beta.1 ~ v0.9.0): 태블릿 · 휴대폰 두 대 = TV 두 쪽
   - 첫 화면 단추(합주 옆) → 주 기기가 두 자리 번호, 보조 기기는 세 개 중 고르기(#142). 짝은 prefs 에 남아 앱 재시작 · 주소 바뀜에도 다시 붙는다
   - 주 기기가 펼침을 정하고 보조 기기는 제 칸만 그린다(`pair/` — TCP 9093 · UDP 9094 찾기 · HTTP 9095 악보 받기, **짝 판 3** `PairProtocol.VERSION`, [`Pair_Protocol.md`](docs/protocols/Pair_Protocol.md))
   - 연주는 TV 두 쪽과 같은 차례 넘김(메트로놈 · 🎤), 보조 기기는 노란 마디, 시작 마디는 두 기기 어디서든(#144), 🎙 는 오른쪽 기기에만(#143)
   - 합주에서는 짝 = "TV 두 쪽 기기 한 대"(보조 기기는 합주 신호 무시)
   - 사용자: 짝 맺기 · 따라 열기 "잘 되는데", 음성 · 닫기 "잘 되네"(2026-10-14, 태블릿 + S22)
2. **☁️ ScoreMate 멤버십** (#141, v0.9.0-beta.4, 서버 0.25.0): 설정 멤버십 줄, Lite 의 합주용 악보(`retention: session`)는 합주 동안만 목록에(숨은 캐시 `.ScoreMateSession`), 지휘자 · 짝에게서 받은 곡은 캐시(30일 · 200MB). DB v20, 동기화 형식 3
3. **⭐ 곡 목록의 별** (#145, v0.9.1): ★ = 박자표를 읽은 마디(메트로놈 악보 연동) · ★★ = + `.musicxml`, 분석 안 한 악보는 목록 뒤에서 하나씩(`ScoreLevel` · `analyzeForStars`)
4. **🎹 반주 파트 · 파트 보기 듣기** (P21, #136 · #137, v0.8.4): 반주 파트를 파트 보기와 따로(자동 · 전체 · 직접, DB v19), 파트 보기에서도 🎤 — 내 파트만, 조용함 = 쉼
5. **🎼 합주 다듬기** (v0.8.3, #135): 합주 단추 색(흰 · 초록 + 연주자 수 · 빨강 · 연결 중 깜박임), 끈 연주자 몰래 다시 연결 · 연결 되풀이 고침, 메트로놈도 두 쪽 차례 넘김
6. **🌐 영어 화면 · 📖 설명서** (P18 · P19 · P20, v0.8.0 · v0.8.1): 앱 전체 English · 영어 음성 명령, 화면 글 CI 게이트(`tools/i18n_gate.py`), 설정은 제목만, 사용자 설명서 한 · 영(설정 → 앱 정보 → 설명서, 서버 `/manual/`, 구조 검사 `tools/manual_check.py`)
7. **☁️ 업데이트는 ScoreMate 서버 먼저** (P15, v0.7.6 · v0.7.7 · v0.8.2): GitHub 폴백, 기기 정보 기록, 주소 `…/app-releases/mrgqpdfviewer/android/`(서버 다중 앱)
8. **📱 터치 껍질 · 설정 두 칸** (P17, v0.7.5): 가운데 탭 = 도구 막대(두 번 탭 없앰), 하단 시트 메뉴, 당겨서 동기화, TV · 넓은 태블릿 설정 두 칸

그 앞(v0.5.7 ~ v0.7.4): 악보 메모(P11) · 사전 릴리스 받기 · 🎙 음성 명령(P12, 👂 계속 듣기는 꺼 둠 #093) · 합주 버전(#086) · 🎤 듣기 다듬기 · 메뉴 정리(P13) — CHANGELOG · devlog #072 ~ #099.

CI 게이트: 단위 테스트 546 · 계측 60 · lint · 화면 글 · 설명서 구조. 실기기 확인이 남은 것은 TODOs 맨 위 목록에 모았다.

## 1. 다음 할 일 (우선순위)

1. **실기기 확인** — TODOs "🔍 실기기 확인"(새 판부터)
   - v0.9.1 별(TV · 태블릿 · 휴대폰), v0.9.0 연주 중 도구 막대 · 시작 마디 두 기기 어디서든
   - 두 대로 펼쳐 보기의 **연주 · 합주**(짝 + TV) — 짝 맺기 · 음성은 사용자 확인됨
   - 멤버십: Lite + Maestro 리더 앙상블의 합주용 악보(보였다 사라짐 · 비행기 모드 · 강제 종료), 연결 안 된 연주자의 받은 곡 캐시
2. **N대 펼쳐 보기** (P22 뒤, TODOs "📖 두 대로 펼쳐 보기 다음") — 두 대 짝이 실기기에서 안정된 뒤. `RollingTurns` · `PairSpread` 를 N칸으로, 짝 판 올림
3. **private 전환** (P15 4 · 5단계) — 사용자가 미룸. 할 때 Instrumentation 을 릴리스 커밋 · 수동만으로, 그 뒤 GitHub 폴백 정리
4. 음성 명령 다듬기(`commands.jsonl` 로 오인식 모으기), 연주 듣고 넘기기 여러 곡 시험(P10 §12), 반주 · 파트보 다듬기, 합주 동기 넘김 재설계, 곡 단위 합주(P09) — TODOs 아래 절 그대로

## 2. 일하는 방법 (이 환경)

- **지금 PC 는 네이티브 Linux**(2026-10 ~): JDK · Android SDK 가 없어 **빌드 · 테스트는 CI 로만** 확인한다(푸시 → Android Build 통과 → 태그). 아래 WSL · powershell 줄은 예전 PC 의 것
  - **adb 는 `~/Android/platform-tools/adb`**(Google platform-tools, 2026-10-11 설치). 태블릿은 이 PC 와 **페어링 완료** — 무선 디버깅을 켜면 연결 포트를 mDNS 로 찾아 저절로 잡힌다(`adb devices` → `adb-HA1V46NV-…._adb-tls-connect._tcp`). 안 잡히면 `adb mdns services`
  - **휴대폰 Galaxy S22**(SM-S901N, Android 16, 1080×2340)도 2026-10-14 부터 페어링 — mDNS `adb-R5CT343WEAL-…`, `192.168.55.76`. 태블릿과 함께 두 대로 펼쳐 보기 시험용(로그 `PairSession` · `PairLink`)
  - 음성 명령 기록: `adb pull /sdcard/Android/data/com.mrgq.pdfviewer/files/voice/commands.jsonl` (#109)
- **빌드 · 테스트는 WSL 에서 `powershell.exe` 로 Windows Gradle** — `--no-daemon` 필수, 로그 UTF-16. 명령은 CLAUDE.md "빌드 명령어"
- **adb 도 Windows 쪽**(`powershell.exe -Command "adb …"`). `adb shell … | grep` 은 WSL 쪽에서 파이프한다(PowerShell 따옴표 안에서는 깨진다)
- **실기기**
  - Z18TV Pro `adb 192.168.55.75:5555`(Android 14). 두 대 — 합주 연주자
  - **샤오신패드 12.7**(TB371FC, **중국판 ZUI 15 롬**, Android 13, 2026-10-09 에 `192.168.55.186`)
    - 무선 디버깅만 된다. 연결 포트는 켤 때마다 바뀌므로 사용자에게 묻는다. mDNS 로는 안 잡혔다
    - TCP 는 열리는데 `failed to connect` 면 이 PC 의 키가 페어링에서 빠진 것이다. 사용자에게 **페어링 포트 + 6자리 코드**를 받아 `adb pair` → `adb connect` 를 한 번에 한다(창이 금방 닫힌다)
    - 페어링하면 mDNS 이름 기기도 하나 더 잡히므로 늘 `-s <ip>:<포트>`
    - 절전에서 깬 직후엔 "device not found" — `adb disconnect` 후 다시 `connect`
  - 와이파이가 느려 `adb install` 이 가끔 실패한다 → `adb push` + `pm install -r`. release 빌드는 덮어써도 데이터가 남는다
  - 로그: 분석 · 파트 · 반주 `adb logcat -d | grep -aE "ScoreLayout|ServerLayouts|PartPdfBuilder|파트 보기|반주|ScoreMate 동기화"`, 음성 `VoiceCommand|VoiceListener|NetworkSpeechRecognizer|SodaSpeechRecognizer`
- **태블릿 음성 인식**: 중국판 롬은 Google 앱의 인식 서비스가 꺼진 채라 Google 앱만으로는 안 된다
  - **"음성 인식 및 합성"**(`com.google.android.tts`)을 깔고 **그 앱에 마이크 권한**을 준다(#084). 2026-10-09 에 해 두었다
  - 한국어 기기 안 팩은 없어 **온라인 인식**이다(인터넷 필요)
  - 일반 Google 휴대폰은 마이크 권한만 주면 된다
- **계측 테스트는 CI** 에서(main 푸시 · 수동, 회당 5 ~ 7분). TV 에 debug 를 올리면 release 와 서명이 달라 지워야 하므로 하지 않는다
- **서버 세션이 따로 있다**: ScoreMateServer 작업을 하는 다른 Claude 세션이 이 저장소의 `devlog/*P06*` 을 고쳐 main 에 푸시한다
  - 푸시 · 릴리스 전에 **`git fetch` 후 rebase**. 태그는 main 푸시가 성공한 뒤에만(파이프로 푸시 실패를 가리지 말 것)
  - 서버 계약은 P06 에서 읽고, TV 반영을 같은 절에 "앱 반영"으로 적는다
- **릴리스**(정식 · 사전 같음)
  1. versionCode/Name 을 올리고 CHANGELOG 에 그 판 섹션을 쓴다 → 커밋 · 푸시
  2. **CI 두 개(Android Build · Instrumentation) 초록 확인** → 그 커밋에 **annotated 태그** `git tag -a vX.Y.Z -m "vX.Y.Z" <커밋>` → 태그 푸시 → Release 워크플로
  3. 정식이면 `releases/latest`, 사전 릴리스면 `releases.atom` 맨 위로 확인
  - `-beta.N` 은 pre-release 로 자동 표시된다
  - Release verify 는 태그 == versionName · **versionCode > 직전 태그** · CHANGELOG `## [X.Y.Z]` 섹션을 검사한다
  - 태그만 푸시하고 기기에 안 뜬다면 릴리스가 안 만들어진 것이다(v0.7.1-beta.1 때 태그를 늦게 달아 TV 가 못 봤다)
  - 기능 하나 = 사전 릴리스 하나로 기기에서 시험하고, 확인분을 모아 정식으로 낸다(v0.6.0 · v0.7.0 · v0.7.2 처럼)
- **devlog**: 단계마다 번호 devlog + `devlog/README.md` 인덱스. 사용자 말(결정)을 인용해 남긴다. 다음 번호 **146** (계획은 P23)

## 3. 자주 부딪힌 것

- **동기화 커서**: 새 필드(MusicXML · 편곡자 등)를 앱이 알게 되면 `ScoreMateSync.SYNC_FORMAT` 을 올린다 — 옛 앱이 이미 넘긴 변경을 한 번 처음부터 다시 받는다(지금 3)
- **곡 정보만 바뀐 동기화**는 `SyncReport.updated` 로 세야 목록이 다시 그려진다
- **DB 스키마**: 릴리스 전이라도 기기에 올라간 버전의 스키마를 바꾸면 Room 이 기기에서 깨진다 — 새 버전 + 마이그레이션으로(지금 v20)
- **파트보 캐시**(`cacheDir/parts`): 이름에 원본 크기 · 수정 시각 · 서버 분석 파일 시각 · 보표 · 형식 번호. 배치 규칙을 바꾸면 `PartPdfBuilder.FORMAT` 을 올린다(지금 9)
- 파트 이름 오인식(예: "예원" → "예완")은 서버 웹에서 고친다 — MusicXML 이 바뀌어 TV 가 새로 받는다
- **합주 메시지를 바꾸면** `EnsembleVersion.CURRENT` 를 그 판의 앱 버전으로 올린다(#086)
  - 옛 기기가 무시해도 박 · 쪽이 어긋나지 않는 **선택 필드 추가**(예: `roll_start` #088)는 올리지 않는다
  - 비교는 기기마다 따로 하므로 양쪽에 새 판이 깔려야 안내가 사라진다
- **음성 명령을 더하면** `docs/Voice_Commands.md` 와 사례집 `docs/Voice_Command_Examples.md` 도 고친다. 규칙의 정본은 `voice/` 와 `VoiceCommandTest`
  - 원칙은 **"모르면 실행하지 않는다"** — 부정 · 상대 위치 · 둘 · 남는 수는 실행하지 않는다
- **마이크는 하나**: 🎤 연주 듣기 · 🎙 단추 · 👂 계속 듣기는 동시에 쓰지 않는다
  - 듣기가 시작되면 👂 는 준비 중부터 쉬고(`micFollowStarting`) 🎙 는 흐려진다. 듣는 동안 멈추기는 ↑ 메뉴
- **짝 메시지를 바꾸면** `pair/PairProtocol.VERSION` 을 올린다(지금 3 — 다른 판과는 짝을 맺지 않는다)와 `docs/protocols/Pair_Protocol.md`. 합주 메시지도 `Ensemble_Mode_Protocol.md` 를 같이 고친다
- **화면 글 · 설명서**: 한글 리터럴은 CI 가 막는다(`res/values*/strings_<영역>.xml` 한 · 영 같은 키). 화면 · 조작 이름을 바꾸면 `docs/User_Manual.md` · `.en.md` 도
- **시작 마디 고르기**(`FollowState.SELECTING`)는 메트로놈 · 🎤 듣기 · 음성이 함께 쓴다. 고르는 중의 터치는 이렇게 동작한다(#102 · #144):
  - 두 번 탭은 없다 — 마디 탭이 기다림 없이 커서 옮김, 같은 마디를 다시 탭 = 시작
  - 아래 막대가 저절로: ▶ N마디부터 시작 · 🎤 N마디부터 듣기 · 취소 · ⋯(연주 메뉴)
  - 짝이면 보조 기기에서도 고른다(마디 탭 · 아래 막대)
  - TV 리모컨은 그대로 ←→ 마디 · ↑↓ 줄
