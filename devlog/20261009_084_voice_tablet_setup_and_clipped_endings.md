# 084 — 🎙 태블릿에서 음성 명령이 켜지지 않던 까닭 · 끝 음절 잘림 · "N마디" = 고르기 · 예비박 (v0.7.0)

2026-10-09 · P12 2단계 후속 · 사용자: "음성명령이 활성화 안 되는 것부터 해결해야 돼" → (태블릿 설정 후) "아 이제 잘 돼" →
"마지막 음절을 잘 못듣는 경우가 많아 … 마디라고 말하면 해당 마디를 선택하게" → "'시작'은 예비박 주고 바로 연주 시작" → "버전을 0.7.0 으로"
→ "'예비박 한마디' '예비박 두마디' 이것도 넣어줘" → "명령어 전체 목록 docs/ 에 문서 추가"

v0.6.4-beta.3(#083)을 샤오신패드 12.7(TB371FC, **중국판 ZUI 15 롬** `TB371FC_CN_OPEN_USER_…_ZUI_15.0.299`, Android 13)에서 처음 시험했다.
그날 고친 것은 넷이다.
1. 음성 명령이 켜지지 않음 — 앱이 아니라 기기 설정 문제였다
2. 켜진 뒤 끝 음절이 잘림 — 앱에서 고침
3. 마디 명령의 뜻 — 앱에서 고침
4. 예비박 명령 추가, 명령 전체 목록 문서 [`docs/Voice_Commands.md`](../docs/Voice_Commands.md)

## 1. 음성 명령이 켜지지 않던 까닭 — 기기 설정

### 증상
설정 → 앱 정보 → 🎙 음성 명령에 "이 기기에는 음성 인식 서비스가 없습니다"가 떴고, 눌러도 켜지지 않았다.
`VoiceListener.isAvailable` = `SpeechRecognizer.isRecognitionAvailable()` 가 false 였다. 이 함수는 `android.speech.RecognitionService` 를
처리하는 **켜진** 서비스를 `queryIntentServices` 로 찾는다.

### 조사 (adb, 2026-10-09)
태블릿에는 Google 앱(`com.google.android.googlequicksearchbox` 17.64.15)이 그날 10:34 Play 에서 깔려 있었다(사용자: "google 깔렸어").

| 확인 | 결과 |
|---|---|
| `cmd package query-services -a android.speech.RecognitionService` | **No services found** |
| 같은 질의 + `--query-flags 512`(꺼진 구성 요소 포함) | `googlequicksearchbox/…voicesearch.serviceapi.GoogleRecognitionService` 하나 |
| `RECOGNIZE_SPEECH` 활동(음성 입력 창) | 마찬가지로 꺼진 것만 있음(`…voicesearch.intentapi.IntentApiActivity`) |
| `dumpsys package` 의 `disabledComponents` | 이 서비스는 없음 → 실행 중에 끈 것이 아니라 **매니페스트 기본값이 꺼짐** |
| `pm enable …/GoogleRecognitionService` | `SecurityException: Shell cannot change component state` — 루팅 없이 켤 수 없다 |
| `settings get secure voice_recognition_service` | `null` — 시스템 기본 음성 인식도 비어 있었다 |
| `com.google.android.tts` (Google 음성 인식 및 합성) | 깔려 있지 않음 |

→ **Google 앱만으로는 부족했다.** 인식 서비스가 들어 있지만 꺼진 채로 들어 있고, Google 앱 스스로도 켜지 않았다.
요즘 Google 기기에서는 음성 인식이 "음성 인식 및 합성" 앱 몫이라, 그 앱이 없는 중국판 롬에서는 비어 있는 것으로 보인다.
이 부분은 추정이다. Google 앱을 열어 음성 검색을 한 번 쓰면 서비스가 켜지는지는 시험하지 않았다.

### 해결 1 — "음성 인식 및 합성" 설치 (11:27)
Play 스토어에서 **Speech Recognition & Synthesis from Google**(`com.google.android.tts`, `googletts.google-speech-apk_20260817.01`)을 깔았다
(adb `am start -a VIEW -d market://details?id=com.google.android.tts` 로 페이지를 띄움).
- 켜진 인식 서비스가 생겼다: `com.google.android.tts/…googletts.service.GoogleTTSRecognitionService`
- 이 앱이 스스로 **시스템 기본 음성 인식**(`voice_recognition_service`)이 됐다
- 앱 설정의 🎙 토글이 켜지고, 악보 화면에 🎙 단추가 생겼다

### 해결 2 — 그 앱에 마이크 권한 (11:29)
켜졌지만 말하면 "이 기기의 음성 인식이 한국어를 지원하지 않아요"가 떴다. 사용자는 그 전에 "마이크 권한이 필요하대"라고도 알렸는데,
어느 화면의 문구였는지는 확인하지 못했다. 로그(11:29:04 · :06 · :18, 세 번 모두 같음):

```
ALT.AudioSessionsRegy: … TNG_TRANSCRIPTION): microphone permission denied
NetworkSpeechRecognizer: htr: Error type: MICROPHONE_UNAVAILABLE Error code: 102     ← 온라인 인식이 마이크를 못 염
RecognitionServiceImpl: RecognitionService#onFallback                                 ← 기기 안 인식(SODA)으로
SodaLPDirGenerator: Returning no LP, as MDD does not support locale: ko-KR.           ← 한국어 기기 안 팩 없음
RecognitionServiceImpl: Speech recognition error type LANGUAGE_PACK_ERROR with error code 12
VoiceListener: 인식 오류 12                                                            ← 앱: "한국어를 지원하지 않아요"
```

- 우리 앱의 마이크 권한은 이미 있었다(`RECORD_AUDIO: granted=true, USER_SET`)
- **인식 서비스 앱 자신**(`com.google.android.tts`)에 마이크 권한이 없었다. 설치만 하고 한 번도 열지 않아 물어볼 기회가 없었다
- 그래서 앱이 띄운 "한국어를 지원하지 않아요"는 마지막 단계의 오류였을 뿐이다. 진짜 원인은 마이크 권한이었다
- 고침: `adb shell pm grant com.google.android.tts android.permission.RECORD_AUDIO`. 사용자가 직접 한다면 설정 → 앱 → 음성 인식 및 합성 → 권한 → 마이크

그 뒤로는 바로 됐다(11:30:05 · 11:30:11 "다음 페이지" ✓ 다음 쪽). 이때도 SODA 의 `error 12` 는 로그에 같이 찍힌다.
온라인 인식(`NetworkSpeechRecognizer`)의 결과가 쓰인 것이다 → **이 태블릿의 한국어 음성 명령은 인터넷이 있어야 한다.**

### 휴대폰은 마이크 권한만 (사용자 보고)
사용자의 휴대폰에서는 "마이크 권한을 주라"는 안내가 떴고, 그것만 주니까 잘 됐다. 일반 Google 휴대폰에는 인식 서비스와 기본 설정이 이미 있다.
그러니 위 1 · 2단계는 **중국판 롬 태블릿만의 문제**다.

### 정리 — 이런 기기에서 켜는 법
1. Play 스토어에서 **음성 인식 및 합성**(Speech Recognition & Synthesis, `com.google.android.tts`) 설치
2. 설정 → 앱 → 음성 인식 및 합성 → 권한 → **마이크 허용**
3. MRGQ 설정 → 앱 정보 → 🎙 음성 명령 켜기, 처음 누를 때 MRGQ 마이크 권한 허용

앱이 이 경우를 더 잘 안내할 수 있다. 아직 손대지 않았다(TODOs):
- 꺼진 인식 서비스만 있을 때: 지금은 "서비스가 없습니다"만 나온다. "음성 인식 및 합성 앱을 설치하세요"로 안내하기
- 오류 12: 인식 앱의 마이크 권한도 함께 짚기
- `voice_recognition_service` 가 비어 있는 기기에서는 `SpeechRecognizer` 가 `ERROR_CLIENT` 를 낸다. 앱은 이 오류를 조용히 넘기므로 🎙 가 아무 반응도 없게 된다

## 2. 끝 음절 잘림 — 손을 뗀 뒤 0.6초 더 듣기 + "N 마" = N마디

### 증상
켜진 뒤 "115 마디"를 말하면 처음에는 후보가 `["115 마", "115마", "115m"]` 로만 왔다 → "명령을 찾지 못했어요"(11:30:16).
다시 말하니 `["115 마디", "115마디", "115 마"]` → ✓(11:30:21).
사용자: "'50 마디'라고 약간 빠르게 말하고 바로 손가락을 떼면 '50 마'까지만 알아들어."
떼는 순간 `stopListening()` 을 부르기 때문에, 마이크 버퍼에 아직 들어오는 중이던 마지막 음절이 잘린다.

### 고침
- **떼고 0.6초 더 듣는다**(`VOICE_RELEASE_TAIL_MS` 600ms): 떼면 단추는 바로 꺼지고 "🎙 알아듣는 중…"이 뜬다. `stopListening` 은 0.6초 뒤에 부른다
  - 그사이 다시 누르면 예약을 지우고 새로 듣는다. 화면을 벗어나면(`onPause`) 예약도 지운다
  - 서비스가 먼저 말 끝을 알아채면 기다리지 않고 결과가 온다
  - 대가: 결과가 0.6초 늦다. 놓치는 것보다 낫다고 봤다. 값은 실사용을 보며 조절한다
- **잘린 단위 되살리기**(`VoiceLexicon.CLIPPED_UNITS`, `CommandNormalizer.restoreClippedUnit`): **수 바로 뒤, 말의 맨 끝**에 있는
  "마"는 마디로, "페이"는 쪽으로 읽는다("50 마" = 50마디). 가운데의 "마"("50 마 템포 72")나 수 없는 "마"는 그대로 두어 실행하지 않는다
  (사용자: "'마'를 마디로 인식하는 건 괜찮은 것 같아")

## 3. "N마디" = 고르기, "부터 · 시작" = 바로 시작

### 증상
#082 에서는 마디 명령을 모두 "마디 고르기 없이 바로 시작"으로 정했다. 그래서 "57마디"라고만 해도 예비박 두 마디 뒤 연주가 시작됐다.
사용자: "마디라고 말하면 해당 마디를 선택하게 하는 게 좋을 것 같아", "'시작'은 예비박 주고 바로 연주 시작 —
마디 선택이 안 되었을 경우 페이지 첫 마디로".

### 이제
| 말 | 동작 |
|---|---|
| **57마디** · 57번 마디 · 쉰일곱 마디 · 수만("오십칠") | 57마디를 시작 마디로 **고르기만**(커서). 이미 고르는 중이면 커서를 옮긴다 |
| **57마디부터** · 57부터 · 57마디에서부터 | 예비박 뒤 57마디부터 바로 |
| **57마디 시작** · 57마디에서 시작 · 57마디 다시 | 고르기에 "시작 · 다시"가 붙으면 = 57마디부터 |
| **시작** | 고른 마디가 있으면 거기서, 없으면 **지금 화면의 첫 마디**에서 예비박 뒤 바로. 마디를 못 읽은 악보면 악보 연동 없이 메트로놈만 |
| 처음부터 · 다시 · 이어서 | 그대로(바로 시작) |

"다음 쪽 시작"(넘김 + 시작)은 넘김이 끝나기 전에 시작이 오면 앞 쪽 첫 마디에서 시작할 수 있다. `pageIndex` 는 캐시된 쪽을 애니메이션으로 넘길 때만 바로 바뀌기 때문이다. 확인 전이다.

- 고른 뒤에는 "시작"이라고 말하거나 그 마디를 한 번 더 탭하면 시작한다(탭은 원래 동작)
- **🎙 를 눌러도 고르던 마디는 그대로 둔다**(`stopForCommand`) — "57마디" → "시작"이 이어지도록. 울리는 메트로놈은 전처럼 멈춘다
- 고르는 중에 시작하는 명령이 오면 멈추지 않고 그대로 시작한다(`startFollowingAt` — OK · 마디 탭과 같다). 합주 지휘자면 고르던 진행이 이어진다
- 고를 때 이 파일의 메트로놈 설정을 엔진에 넣는다(`loadSavedMetronomeSettings`). 그래서 마디를 탭해 시작해도 방금 말한 템포가 쓰인다.
  ↑ 메뉴 "시작"도 같은 함수를 쓴다
- 표시: "57마디 선택 · ♩=72" / "57마디부터 · ♩=72"
- 지금 화면의 첫 마디(`firstShownMeasure`)는 마디 고르기에 들어갈 때 커서를 놓던 규칙과 같다
  (화면에 보이는 첫 마디 → 이 쪽 뒤 첫 마디 → 맨 앞)

### 코드
- `voice/VoiceCommand.SelectMeasure`(위치 명령, 재생 시작 아님) · `GotoMeasure` 표시 "N마디부터"
- `CommandParser`: 수 + 마디 + 부터 → `GotoMeasure`, 수 + 마디 → `SelectMeasure`, 고르기 + 시작 · 다시 → `GotoMeasure`.
  `VoiceCommandRunner`: 수만이면 `SelectMeasure`(템포 대화상자가 열려 있으면 템포 — 그대로)
- `ViewerCommands.selectMeasure` 추가, `start()` 의 뜻 바뀜(고르기 창을 여는 것 → 바로 시작)
- `PdfViewerActivity`: `followableMeasures` · `startableMeasuresNow` · `startFollowingAt` · `firstShownMeasure` · `loadSavedMetronomeSettings` 로 나눔.
  `enterMeasureSelection(at)` 은 커서 자리를 받는다(이때 안내 토스트는 띄우지 않는다)

## 4. 예비박 명령 · 명령 목록 문서

- **"예비박 한 마디" · "예비박 두 마디"**(`SetCountIn`): 박자 상세의 예비박 버튼과 같은 전역 설정(`metronome_count_in_bars`, #060)을 바꾼다.
  다음 시작부터 적용되고, 합주 연주자는 못 한다(지휘자의 `count_in_bars` 를 따른다)
  - 말: "예비박 한 마디" · "예비박 두마디" · "예비박 2마디" · "예비박 둘" · "예비 박 이 마디" · "두 마디 예비박" · "카운트인 …"
  - 예비박 뒤의 한 글자 수는 수로 읽는다(템포 뒤와 같다). "이"도 2 로 읽는다 — "이 마디"(= 지금 마디)와 헷갈릴 일이 없다
  - "N 마디 예비박"은 예비박이지만, "57마디 예비박 두 마디"의 57 은 마디다. 예비박 뒤에 수가 오면 그 수가 예비박 몫이다
  - 설정 명령이라 템포처럼 맨 먼저 실행한다("예비박 한 마디 57마디부터" = 예비박 바꾸고 57마디부터). 1 · 2 밖이면 "예비박은 한 마디나 두 마디만 돼요"
  - "예비박을 한 마디로"는 안 된다. 조사에 붙은 한 글자 수("을한")는 일부러 수로 읽지 않기 때문이다(#081 규칙)
- [`docs/Voice_Commands.md`](../docs/Voice_Commands.md) — 사용자용 명령 전체 목록: 켜기 · 쓰는 법, 마디 · 시작 · 설정 · 쪽 · 파트,
  한 번에 여럿, 실행하지 않는 말, 합주 중, 수 읽기, 기록, 음성 인식이 켜지지 않을 때. 명령을 고치면 함께 고친다

## 확인
- 단위 테스트: 전체 424 통과. 이번에 더한 것:
  - 마디만이면 고르기 · 마디부터 시작 · 고르기 + 시작 · 잘린 "마" · "페이"
  - 가운데 "마"는 실행 안 함
  - 예비박 여러 말 · 마디와 함께 · 둘이면 실행 안 함
  - 실행기: 고르기 / 시작 / "50 마" / 예비박 범위
- 실기기(태블릿, 이 판): 아직 — "50 마디"를 빨리 말하고 바로 떼기, "57마디" → 커서만 → "시작", 아무것도 고르지 않고 "시작"(지금 쪽 첫 마디)
