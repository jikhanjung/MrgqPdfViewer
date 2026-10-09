# HANDOFF — 현재 작업 인계 노트

마지막 갱신: 2026-10-09
브랜치: `main` (origin/main 과 동기화)
버전: **v0.7.3** (versionCode 63). 실기기는 앱 안 업데이트로 받는다(태블릿 · TV 모두 사전 릴리스 받기 켜 둠)
관련 문서: [CLAUDE.md](CLAUDE.md) (가이드 · 알아 둘 결정) · [TODOs.md](TODOs.md) (할 일 · **실기기 확인 목록**) · [CHANGELOG.md](CHANGELOG.md) · [devlog 인덱스](devlog/README.md) · [음성 명령 목록](docs/Voice_Commands.md)

---

## 0. 지금 상태 한눈에

v0.5.6(2026-10-06) 뒤로 사흘 동안 v0.7.3 까지. 큰 줄기 다섯:

1. **악보 메모** (P11, devlog #072 ~ #080, v0.6.0 ~ v0.6.3): 태블릿 · 휴대폰에서 펜 · 손가락으로 긋기 · 글자 · 옮기기 · 지우개 · 되돌리기
   - 곁 파일 `.notes.json`(악보를 따라다님), 개인 메모 ScoreMate 동기화(합치기)
   - 지휘자 메모(앙상블 두 겹), 메모 모드 확대 · 이동, 곡 목록 ✏️ 표시, 자동 동기화(10분 ± 30초)
2. **사전 릴리스 받기** (#073, v0.5.7): 설정 🧪 — `releases.atom` 에서 beta 까지 받는다. 기능마다 beta 로 기기에 올려 시험한다
3. **🎙 음성 명령** (P12, #081 ~ #085, v0.6.4-beta.1 ~ v0.7.1-beta.1)
   - 🎙 를 누른 채로 말하면 기기 음성 인식(`SpeechRecognizer`, 대개 Google 온라인)이 받아 적는다 → 정규화 · 규칙 → 명령 층(`ViewerCommands`)
   - 명령: 마디 고르기 · 마디부터 · 시작 · 멈춰 · 템포 · 예비박 · 쪽 · 파트 · 총보 · 듣기
   - **👂 계속 듣기**(시험): 단추 없이 호출어 "메이트 / Mate" 뒤의 말만 실행한다
   - 명령 전체: [`docs/Voice_Commands.md`](docs/Voice_Commands.md)
4. **합주 버전** (#086, v0.7.2): 연결 안내는 `ensemble_version`(합주 메시지가 바뀐 판의 앱 버전, 지금 **0.4.0**)이 다를 때만 뜬다
5. **🎤 연주 듣고 넘기기 다듬기** (P10 §10, #087 · #088, v0.7.3)
   - 고른 마디부터 듣기, 듣는 중 마디 길게 눌러 다시 맞춤, 음성 "듣기 · 57마디부터 듣기"
   - 지휘자가 듣기를 시작하면 두 쪽 연주자가 바로 시작 펼침으로 간다(`page_change.roll_start`)

사용자 평(2026-10-09): "생각보다 모든 기능들이 너무 잘 동작하고 있어." 실기기 확인이 남은 것은 TODOs 맨 위 목록에 모았다.

## 1. 다음 할 일 (우선순위)

1. **실기기 확인** — TODOs "🔍 실기기 확인"
   - 먼저 v0.7.3 의 **TV 두 쪽 시작 펼침**(태블릿 지휘자 + TV)
   - 그다음 **👂 계속 듣기**(아직 한 번도 안 써 봄 — 메트로놈 · 반주 소리가 줄거나 끊기는지, 호출어 없는 말이 실행되지 않는지)
2. **음성 명령 다듬기** (P12 3단계)
   - `files/voice/commands.jsonl` 로 오인식을 모아 정규화 · 규칙을 늘린다
   - 음성 인식 서비스가 없거나 꺼진 기기의 안내 문구를 고친다(#084 끝)
   - "처음부터 듣기" · "이어서 듣기"
3. **연주 듣고 넘기기 여러 곡 시험** (P10 §12) — 몰다우(반복) · K488 · Clair de Lune(루바토) · 실제 기타 앙상블. 그 뒤 태블릿 "악장" + TV 지휘자(P10 §10)
4. 반주 · 파트보 다듬기, 합주 동기 넘김 재설계, 곡 단위 합주(P09) — TODOs 2 ~ 4순위 그대로
5. **릴리스 keystore 백업** — 아직이다(TODOs 기술 부채). 잃으면 모든 기기가 앱 안 업데이트를 못 받는다

## 2. 일하는 방법 (이 환경)

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
- **devlog**: 단계마다 번호 devlog + `devlog/README.md` 인덱스. 사용자 말(결정)을 인용해 남긴다. 다음 번호 **089**

## 3. 자주 부딪힌 것

- **동기화 커서**: 새 필드(MusicXML · 편곡자 등)를 앱이 알게 되면 `ScoreMateSync.SYNC_FORMAT` 을 올린다 — 옛 앱이 이미 넘긴 변경을 한 번 처음부터 다시 받는다(지금 2)
- **곡 정보만 바뀐 동기화**는 `SyncReport.updated` 로 세야 목록이 다시 그려진다
- **DB 스키마**: 릴리스 전이라도 기기에 올라간 버전의 스키마를 바꾸면 Room 이 기기에서 깨진다 — 새 버전 + 마이그레이션으로(지금 v18)
- **파트보 캐시**(`cacheDir/parts`): 이름에 원본 크기 · 수정 시각 · 서버 분석 파일 시각 · 보표 · 형식 번호. 배치 규칙을 바꾸면 `PartPdfBuilder.FORMAT` 을 올린다(지금 8)
- 파트 이름 오인식(예: "예원" → "예완")은 서버 웹에서 고친다 — MusicXML 이 바뀌어 TV 가 새로 받는다
- **합주 메시지를 바꾸면** `EnsembleVersion.CURRENT` 를 그 판의 앱 버전으로 올린다(#086)
  - 옛 기기가 무시해도 박 · 쪽이 어긋나지 않는 **선택 필드 추가**(예: `roll_start` #088)는 올리지 않는다
  - 비교는 기기마다 따로 하므로 양쪽에 새 판이 깔려야 안내가 사라진다
- **음성 명령을 더하면** `docs/Voice_Commands.md` 도 고친다. 규칙의 정본은 `voice/` 와 `VoiceCommandTest`
  - 원칙은 **"모르면 실행하지 않는다"** — 부정 · 상대 위치 · 둘 · 남는 수는 실행하지 않는다
- **마이크는 하나**: 🎤 연주 듣기 · 🎙 단추 · 👂 계속 듣기는 동시에 쓰지 않는다
  - 듣기가 시작되면 👂 는 준비 중부터 쉬고(`micFollowStarting`) 🎙 는 흐려진다. 듣는 동안 멈추기는 ↑ 메뉴
- **시작 마디 고르기**(`FollowState.SELECTING`)는 메트로놈 · 🎤 듣기 · 음성이 함께 쓴다. 고르는 중의 터치는 이렇게 동작한다:
  - 마디 탭은 한 번 탭이 확인된 뒤(약 0.3초) 처리한다
  - 두 번 탭 = ↑ 메뉴, 아래 가운데 "🎤 N마디부터 듣기" 단추
  - TV 리모컨은 그대로 ←→ 마디 · ↑↓ 줄
