# 083 — 🎙 음성 명령: 기기 음성 인식 (SpeechRecognizer) (v0.6.4-beta.3)

2026-10-09 · P12 2단계 · 사용자: "진행해줘" → "로컬 모델 안 쓰고 온라인으로 처리하면 어때?" → "google 깔렸어"

## 결정 — 기기 안 모델 대신 기기의 음성 인식
처음엔 sherpa-onnx + Moonshine tiny-ko(기기 안, P12 §4.2)를 붙이려 했다. 조사 결과(2026-10-09):
- sherpa-onnx v1.13.8 은 Maven Central 에 없고 JitPack(.aar 그대로) — onnxruntime 포함 arm64 약 27MB(압축 안 함), APK 6.5 → 약 33MB
- 모델 `sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27`(Moonshine v2: encoder 13MB + merged decoder 58MB + tokens) — **Moonshine AI Community License**(비상업 무료, 상업은 연 매출 100만 달러 미만 + 등록)
- v1.13.8 은 9.2초 넘는 녹음에 빈 결과(고침은 다음 판), Moonshine 은 핫워드 불가(부르면 프로세스가 죽는다)
- CI 에뮬레이터가 x86 · x86_64 라 arm64 만 넣은 APK 는 계측 테스트용으로 따로 빌드해야 한다

사용자가 온라인 처리를 물었고, 태블릿에 Google 이 있어 **Android `SpeechRecognizer`** 로 정했다 — APK · 라이선스 부담 없음, 한국어 품질, Android 13+ 낱말 힌트. 대가: 대개 인터넷 필요, 녹음 파일을 남길 수 없음. 기기 안 모델 조사는 P12 §4.2 에 남겨 둔다.

## 무엇
- **설정 → 앱 정보 → 🎙 음성 명령 (시험)**(TV 아닌 기기, 음성 인식 서비스가 없으면 안내만) → 악보 화면 **오른쪽 아래 🎙**
- **누르는 동안 듣는다** (`voice/VoiceListener`): 누르면 메트로놈 정지(§4.2 — 합주 연주자면 이 기기만 빠짐) → `startListening`(ko-KR, 후보 5, 중간 결과) → 떼면 `stopListening`. 서비스가 먼저 말 끝을 알면 떼기 전에 결과가 온다
  - 낱말 힌트(Android 13+ `EXTRA_BIASING_STRINGS`): 명령 낱말 + 악기 이름 + 이 곡 MusicXML 파트 이름
  - 듣는 동안 단추가 빨갛고, 위에 "🎙 듣는 중…" → 중간 결과 → "🎙 “57마디부터 템포 72” ✓ 57마디 · ♩=72"(5초)
  - 🎤 연주 추적 중에는 흐리고, 누르면 "연주 추적을 멈춘 뒤에 말하세요"(§4.1). 마이크 권한은 처음 누를 때 묻는다
- **후보 고르기** `CommandParser.parseBest`: 첫 후보가 명령이 아니면 다음 후보 — 단, 첫 후보가 **일부러 한 말로 막힌 것**(말고 · 상대 위치 · 둘)이면 그대로(“57마디 말고”를 둘째 후보 “57마디”로 실행하지 않게). 못 알아들음 · 남는 수일 때만 다음 후보
- **기록** `files/voice/commands.jsonl`(1MB 넘으면 뒤 절반) — 후보들 · 고른 말 · 결과. 기기 밖으로 보내지 않는다. 로그 `VoiceCommand`
- 🎙 단추에서 시작한 터치는 넘김 몸짓으로 보내지 않는다(`dispatchTouchEvent`, 메모 도구 줄과 같게) — 오른쪽 아래가 넘김 자리라
- Android 11+ 패키지 가시성: 매니페스트 `<queries>` 에 `android.speech.RecognitionService`

## 확인
- 단위 테스트 +2 (후보 고르기)
- 실기기 — 아직: 태블릿에서 "57마디부터 템포 72", "다음 쪽", "첼로 파트", 소리가 나는 방에서
