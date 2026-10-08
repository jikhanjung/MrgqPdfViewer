# ScoreMate 음성 명령 · AI 연동 기술 논의
작성일: 2026-10-07 (원본 `ScoreMateServer/docs/ScoreMate_technical_discussion_2026-10-07.md` 에서 음성 명령 · AI 연동 부분만 남김)
추가: 2026-10-09 — §6 EmbeddingGemma 2 활용 검토

## 0. 전제: PageFlow
PageFlow 는 악보상의 현재 음악적 위치(current_score_position)를 공통 상태로 유지하고, 여러 입력원(페달, 지휘자, 터치, 메트로놈, 연주 추적, AI/에이전트 명령)과 여러 표시 장치를 잇는 실시간 제어 계층이다. 아래의 음성 명령 · 에이전트는 모두 이 계층의 입력원 중 하나로 들어온다.

---

## 1. AI/에이전트 인터페이스

ScoreMate가 범용 에이전트에 대체되는 것을 방어하는 것보다, ScoreMate가 적극적으로 에이전트를 받아들이는 방향이 유리하다.

핵심 역할 분담:

- AI/Agent: 자연어 이해, 의도 해석
- ScoreMate/PageFlow: 실제 실행, 상태 검증, 동기화, 실시간 제어

### 1.1 PageFlow API 후보
- get_position()
- set_position(measure)
- next_position()
- previous_position()
- goto_rehearsal_mark(mark)
- set_tempo(bpm)
- start_metronome()
- stop_metronome()
- select_part(part)
- show_full_score()
- sync_session()
- add_annotation()
- get_session_state()

AI가 화면을 직접 조작하는 것이 아니라 도메인 명령을 호출하게 한다.

예:
"57마디부터 다시, 템포 72"
→ set_position(57)
→ set_tempo(72)

---

## 2. 로컬 음성 명령

공연 중에는 클라우드 의존성을 최소화하는 것이 유리하다.

가장 적합한 UX는 push-to-talk 방식이다.

### 2.1 권장 흐름
1. 평소에는 페달 / 메트로놈 / 연주 추적 등이 PageFlow를 제어
2. 지휘자가 태블릿의 작은 마이크 버튼을 누름
3. 누르는 동안 STT 활성화
4. 버튼을 떼면 utterance 확정
5. intent/parser 실행
6. validator가 현재 악보/상태와 비교
7. PageFlow API 실행
8. 기존 모드로 복귀

외부 BLE 스위치를 선택 옵션으로 둘 수 있다.
지휘봉 부착형은 일부 지휘자에게 유용할 수 있으나, 지휘봉을 사용하지 않는 지휘자도 많기 때문에 기본 UX는 화면 마이크 버튼이 적합하다.

---

## 3. STT 및 명령 처리 구조

범용 LLM을 매번 호출하는 대신 다음의 fast path를 사용할 수 있다.

Speech / Text
→ Normalization
→ Intent Classifier
→ Slot Parser
→ Context Resolver
→ Command Validator
→ PageFlow API

### 예
"57마디부터"
- intent: GOTO_MEASURE
- measure: 57

"템포 84"
- intent: SET_TEMPO
- bpm: 84

"첼로 파트"
- intent: SELECT_PART
- part: cello

### 3.1 가능한 intent
NAVIGATION
- NEXT_PAGE
- PREVIOUS_PAGE
- GOTO_PAGE
- GOTO_MEASURE
- GOTO_REHEARSAL_MARK

PRACTICE
- SET_TEMPO
- START_METRONOME
- STOP_METRONOME
- START_REPEAT

SCORE
- SELECT_PART
- SHOW_FULL_SCORE
- SHOW_PART_SCORE

ENSEMBLE
- START_SESSION
- JOIN_SESSION
- SYNC_POSITION
- FOLLOW_CONDUCTOR

ANNOTATION
- ADD_TEXT
- ADD_MARK
- DELETE_ANNOTATION

---

## 4. 소형 로컬 STT 후보

sherpa-onnx의 Moonshine tiny-ko quantized 모델은 ScoreMate 용도로 유망하다.

장점:
- 한국어 전용
- tiny 모델
- quantized
- on-device
- Android에서 실시간 사용 가능
- 약 69 MB 수준의 모델 크기

권장 구조:
Push-to-talk
→ VAD
→ Moonshine tiny-ko
→ vocabulary normalization
→ intent/parser
→ PageFlow

### 평가 지표
- intent 정확도
- measure number 정확도
- BPM 숫자 정확도
- 악기명 정확도
- 명령 종료 후 latency
- 조용한 환경 vs 실제 리허설 환경

특히 실제 음악이 배경에 있는 상황에서의 성능이 핵심이다.

---

## 5. 정리: 장기적 역할 분담

공연 현장에서는 low latency · low jitter · deterministic behavior · recovery · offline capability · domain validation 을 보장하는 도메인 실행 계층이 필요하다.

Agent = intent layer
PageFlow = real-time musical control layer

---

## 6. EmbeddingGemma 2 활용 검토 (2026-10-09 추가)

### 6.1 모델 요약
2026-10-06 Google DeepMind 공개. EmbeddingGemma 1(2025-09, 텍스트 전용 308M, Gemma 3 기반)의 후속.

| 항목 | EmbeddingGemma 1 | EmbeddingGemma 2 |
|---|---|---|
| 기반 | Gemma 3 | Gemma 4 |
| 입력 | 텍스트 | 텍스트(코드) · 이미지 · 비디오 · **오디오** — 하나의 768차원 공간 |
| 크기 | 308M | 740M = 백본 130M + 임베더 140M + 비전 170M + **오디오 300M** |
| 모듈 로딩 | — | 텍스트 270M / 텍스트+이미지 440M / **텍스트+오디오 570M** / 전체 740M (같은 체크포인트) |
| 문맥 | 2K 토큰 | 8K 토큰(전 모달 공유) |
| MTEB 다국어 v2 | 61.15 | 61.36 |
| MTEB 코드 v1 | 68.76 | 78.68 |
| MRL | 768/512/256/128 | 같음 — 256d 까지 거의 무손실, 128d 는 멀티모달 품질 크게 하락 |
| 라이선스 | Gemma Terms of Use | **Apache 2.0** |

- 오디오: **16 kHz 모노**, 접두어 없이 넣는다. 초당 25토큰 → 최대 약 327초. 학습 데이터는 "여러 언어의 음성, 환경음, 음향 이벤트" — **음악은 언급 없음**. MSEB(음성 검색) MRR@10 69.54, MAEB 49.39
- 텍스트: v1 과 같은 작업 접두어(`task: classification | query: …` 등)
- **float16 금지**: 활성값이 float16 범위를 넘어 오류 없이 NaN 이나 품질 저하 임베딩을 낸다. bfloat16 또는 float32
- 기기: 보도 기준 Pixel 11 Pro 에서 양자화 시 텍스트 전용 약 191MB, 전체 약 567MB RAM. LiteRT · MediaPipe 첫날 지원, LiteRT Community(HF)에 최적화판. ⚠️ 공식 모델 카드에는 RAM · 양자화 · LiteRT 언급이 없다 — 텍스트+오디오(570M) 구성의 RAM 은 공개 수치 없음

### 6.2 쓸 수 있는 자리 세 가지

**A. 의도 분류 보조 (§3 Intent Classifier) — 텍스트 270M**
- STT 결과를 의도 예문(intent 마다 10 ~ 30개, 한국어 · 영어)과 비교해 가장 가까운 의도를 고른다(`task: classification` 접두어, 예문 임베딩은 앱에 미리 계산해 넣음)
- 단, **기본 경로는 규칙 기반 처리기**가 맞다. 명령의 핵심은 슬롯(마디 57 · BPM 84)이고, 임베딩은 "57"과 "75"를 구별하지 못한다. 어휘가 좁아 규칙이 더 정확하고 결정적이다
- 임베딩의 자리는 **규칙이 못 잡은 말의 대체 경로**: 유사도가 기준 이상이면 "○○ 하시겠어요?"로 되묻고, 아니면 무시. 바로 실행하지 않는다(§2 validator 원칙)

**B. 슬롯 값 맞추기 (§3 Context Resolver) — 텍스트 270M — 가장 잘 맞는 자리**
- "첼로 파트", "비올라 둘째", "아르페지오네" 처럼 **후보가 악보마다 정해진 슬롯**: 파트 이름(MusicXML `part-name` · PDF 보표 이름 — `Violoncello` / `Vc.` / `Cello`), 세트리스트 곡명, 리허설 마크
- 후보 임베딩은 동기화 · 분석 때 한 번 계산해 저장하고, 말할 때는 STT 결과 하나만 임베딩해 비교 → 지연이 작다
- 다국어 공간이라 한국어 발화 ↔ 영어 · 이탈리아어 · 독일어 표기 맞추기를 사전 없이 처리한다. 약어(`Vc.`, `Vla. II`)는 실측 필요 — 부족하면 작은 별칭 사전을 앞에 둔다

**C. 오디오 직접 임베딩 (STT 생략) — 텍스트+오디오 570M — 권하지 않음**
- 발화 오디오를 바로 임베딩해 의도 예문(텍스트)과 비교하는 방식. 매력적이지만:
  - **슬롯을 못 뽑는다.** 숫자가 든 명령(마디 · 템포 · 쪽)이 대부분이라 어차피 STT 가 필요하다
  - **무겁다.** 오디오 인코더만 300M — Moonshine tiny-ko(약 69MB)보다 크다
  - **음악 배경 내성 미지.** 학습 데이터에 음악이 없고, 한국어 짧은 명령 발화 성능 공개 수치도 없다. §4 가 말한 핵심 조건(리허설 중 배경 음악)을 보장하지 못한다
- 슬롯 없는 명령("멈춰", "다음 쪽")만 맡기는 것도 STT + 규칙이 이미 처리하므로 얻는 것이 없다

### 6.3 쓰지 않을 곳
- **연주 추적(현재 위치 찾기)**: 초당 25토큰(40ms) 오디오 임베딩은 검색용이지 정렬용이 아니다. 지금의 크로마 + 온라인 DTW(`follow/OnlineAligner`, 아르페지오네 실기기 ±1마디 93%)를 대체할 근거가 없다
- **말소리 / 음악 구분(VAD)**: 환경음 학습이 있어 "말인가 음악인가" 판별에 쓸 여지는 있으나 MAEB 49.39 로 강하지 않다. 전용 VAD(sherpa-onnx Silero 등)가 가볍고 검증돼 있다

### 6.4 권장 구조

```
Push-to-talk
→ VAD
→ Moonshine tiny-ko (STT)
→ vocabulary normalization (한국어 숫자: 오십칠 · 쉰일곱 → 57)
→ 규칙 기반 intent + slot parser ──(실패)──→ EmbeddingGemma 2 의도 대체 경로 → 되묻기
→ Context Resolver: 파트 · 곡명 · 리허설 마크 = EmbeddingGemma 2 후보 유사도 (B)
→ Command Validator
→ PageFlow API
```

EmbeddingGemma 2 는 **텍스트 전용(270M)** 으로만 싣는다. 오디오 인코더는 싣지 않는다.

### 6.5 Android 적용 시 주의
- **GPU 델리게이트의 fp16**: LiteRT GPU 델리게이트는 흔히 fp16 정밀도로 계산한다 → §6.1 float16 금지 조건에 걸려 **조용히 틀린 임베딩**이 나올 수 있다. CPU(XNNPACK) 또는 GPU fp32 강제로 돌리고, 데스크톱 float32 결과와 코사인 유사도로 대조하는 계측 테스트를 둔다
- **모델 크기**: 양자화 텍스트판도 수백 MB 단위로 APK 에 넣기엔 크다 → 음성 명령을 켤 때 받아 두는 방식(앱 안 업데이트 `cacheDir/updates` 와 같은 SHA-256 검증)
- **메모리 동시 사용**: 지휘자 태블릿은 연주 추적(마이크) · 쪽 캐시 비트맵 · PdfBox 를 이미 쓴다. 푸시투토크 동안만 STT · 임베딩을 올리고, 마이크는 연주 추적과 AudioRecord 를 나눌지 · 잠시 멈출지 정해야 한다
- **대상 기기**: 음성 명령은 지휘자 태블릿(TB371FC, Android 13)이 1차. TV(Z18TV Pro · Google TV Streamer)는 마이크 · 메모리 여건상 대상 아님

### 6.6 검증 항목 (§4 평가 지표에 추가)
- 의도 대체 경로: 규칙이 못 잡은 발화 중 올바른 되묻기 비율, 엉뚱한 되묻기 비율(기준 유사도 조정)
- 파트 이름 맞추기: 한국어 발화 → MusicXML 파트 이름(약어 포함) 정확도, 별칭 사전 필요 여부
- 태블릿에서 텍스트 1건 임베딩 지연(CPU · GPU fp32), 상주 RAM
- 데스크톱 float32 와 기기 결과의 코사인 유사도(정밀도 문제 확인)
- 공개 수치 미확인 항목 직접 확인: LiteRT 텍스트 전용판 크기 · 양자화 방식 · 한국어 품질

### 6.7 결론
EmbeddingGemma 2 의 새 기능(오디오)은 음성 명령의 STT 를 대신하지 못한다 — 슬롯과 음악 배경이 걸림돌. 대신 **텍스트 전용 270M 을 Context Resolver(파트 · 곡명 · 리허설 마크 맞추기)와 의도 분류 대체 경로**에 쓰는 것이 이득이 분명하다. Apache 2.0 으로 바뀌어 상업 배포 부담도 줄었다. 1차 구현은 STT + 규칙만으로 하고, 파트 이름 맞추기가 규칙으로 부족해지는 시점에 붙인다.

### 6.8 출처
- [EmbeddingGemma 개요 (Google AI)](https://ai.google.dev/gemma/docs/embeddinggemma)
- [EmbeddingGemma 2 모델 카드](https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2)
- [EmbeddingGemma 2: The Developer Guide (Google Developers Blog)](https://developers.googleblog.com/embeddinggemma-2-the-developer-guide/)
- [Unite.AI: DeepMind Debuts EmbeddingGemma 2](https://www.unite.ai/deepmind-debuts-embeddinggemma-2-mapping-five-modalities-into-one-space/) — 출시일 · Pixel 11 Pro RAM · LiteRT
- [CellCog: EmbeddingGemma 2 Benchmarks, Specs](https://cellcog.ai/blog/embeddinggemma-2/) — RAM 수치 교차 확인
