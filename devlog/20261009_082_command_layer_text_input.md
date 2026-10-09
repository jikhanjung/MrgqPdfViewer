# 082 — 음성 명령 0단계 명령 층 + 글자로 명령 시험 창 (v0.6.4-beta.2)

2026-10-09 · P12 0단계 · 1단계 나머지 · 사용자: "진행하자"

## 무엇
- **명령 층** `voice/ViewerCommands` — 명령 하나 = 함수 하나(`gotoMeasure` · `gotoStart` · `restart` · `resume` · `gotoRehearsalMark` · `nextPage` · `previousPage` · `gotoPage` · `setTempo` · `selectParts` · `showFullScore` · `start`), 결과는 `Done` / `Failed(이유)`. 악보 화면(`PdfViewerActivity.viewerCommands`)이 구현한다
- **실행기** `voice/VoiceCommandRunner` — 읽은 결과 → 명령 층. 값 범위(템포 20 ~ 240, 마디 · 쪽 ≥ 1)는 **실행 전에** 보고 하나라도 벗어나면 아무것도 하지 않는다. 순서대로 실행하다 실패하면 멈추고 "♩=72 했지만 — 57마디가 없어요 (1 ~ 120)". 수만 들리면 마디(메트로놈 대화상자가 열려 있으면 템포 — 음성 단계에서)
- **파트 이름 맞추기** `voice/PartMatcher` — 보표 이름("Violin I", "Vln. 2", "Vn. II", "1st Violin", "Violoncello", "Vc.", "Double Bass", "바이올린 1")에서 마지막 악기 낱말 + 번호(1 · I · 1st). 번호 없이 "바이올린"인데 I · II 가 있으면 고르지 않고 묻는다. 이름 없는 아래 보표(피아노 왼손)는 함께
- **⌨️ 글자로 명령 (시험)**: 설정 → 앱 정보 → ⌨️ 글자로 명령 시험을 켜면 ↑ 메트로놈 메뉴에 줄이 생긴다(연주자 메뉴에도 — 쪽 · 파트만). 글자 → `CommandParser` → 실행기 → 명령 층, 결과는 토스트 "✓ 57마디 · ♩=72" / "✗ …", 로그 `VoiceCommand`
  - 🎙 와 같은 규칙: 🎤 연주 추적 중에는 열지 않는다(P12 §4.1), 여는 순간 메트로놈 정지(§4.2 — 합주 연주자면 이 기기만 빠진다)

## 명령 층이 부르는 기존 경로
| 명령 | 경로 |
|---|---|
| 마디 · 처음 · 다시 · 이어서 | `followFrom` — 시작 가능 마디(`ScoreFollower.startableMeasures`)에서 고르고, 이 파일의 설정을 엔진에 넣고(↑ 메뉴 "시작"과 같다) `cursorIndex` → `startFollowing()`. **마디 고르기 없이 바로** — 유일한 새 동작 |
| 다음 · 이전 쪽 | ← → 키 본문을 `turnForward()` · `turnBack()` · `turnTo()` 로 떼어 키 · 페달 · 터치 · 명령이 같이 쓴다(키 동작 그대로). 명령은 끝 쪽이면 실패로 알린다(안내 · 다음 파일로 넘어가지 않음) |
| N쪽 | 원본 쪽 번호 — 파트 보기면 `dstPageForSource`, 두 쪽이면 짝의 첫 쪽, 지휘자 동기 넘김 · 마이크 맞춤도 `turnTo` 로 |
| 템포 | 엔진 + 파일별 저장(대화상자 닫을 때와 같은 `setMetronomeForFile`) — 저장을 기다리므로 이어지는 "57마디부터"가 새 템포로. 연주 중이면 `refreshFollowTempo` · `retimeConductorRun` |
| 파트 · 총보 | 파트 보기 대화상자와 같다: 저장 → `loadFile`. 다음 명령이 새 화면에서 돌도록 **첫 쪽을 보일 때까지 기다린다**(`fileShownSignal`, 15초 한도) |
| 시작 | `startMetronomeWithSavedSettings()` — 지금처럼 마디 고르기로 |
| 레터 | 아직 실패("마디 번호로 말하세요") — P12 4단계 |

## 판단
- **"다시" = 이 곡에서 마지막으로 시작한 마디**(`lastFollowStart`, `startFollowing` 이 남김). 계획(P12 §4.2)은 🎙 정지 전에 `followStartMeasure` 를 보관하는 것이었지만, 시작할 때 남기면 정지 경로와 상관없다
- 합주 연주자: 마디 · 템포 · 시작은 "지휘자가 정해요", 쪽 · 파트만. 지휘자: 파트는 "총보만", 총보 명령은 그대로 성공
- 명령 층은 아직 음성 · 글자 창만 쓴다 — 키 처리는 쪽 넘김만 같은 함수로 옮겼다(나머지 키 · 메뉴는 그대로)

## 확인
- 단위 테스트 +11 (`VoiceCommandRunnerTest` — 실행 순서 · 범위 · 실패 보고 · 파트 이름)
- 실기기: 태블릿에서 글자로 "57마디부터 템포 72", "첼로 파트", "다음 쪽", "다시", "이어서" — 아직
