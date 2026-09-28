# 연습 녹음 (P08 A단계)

태블릿 앱의 **연습 녹음**(뷰어 오른쪽 위 빨간 동그라미, P08 §6)으로 만든 녹음. 마이크 악보 추적 실험(`data/score_follow_dtw.py`)의 자료다.

> **이 목록(README.md)만 저장소에 있다.** WAV · JSON · 악보 사본은 `data/.gitignore` 로 빠진다(크기 · 저작권 — P07 §0).
> 원본은 태블릿 `/sdcard/Android/data/com.mrgq.pdfviewer/files/recordings/`, 받기: `adb pull <그 경로> data/recordings/`

## 파일

- `<악보>_<yyyyMMdd_HHmmss>.wav` — 44.1kHz 16비트 모노. `source` 가 `UNPROCESSED` 면 가공 없는 마이크(소음 제거 · 자동 음량 없음)
- 같은 이름 `.json` — 머리(`source` · `clock` · `info`: 곡 · 파트 보기 · 템포 · 박자 · 예비박 · 기기 · 앱 버전)와 사건 `events`
  - `t_ms`: 녹음 시작 기준. `clock: "timestamp"` 이면 0 = WAV 첫 샘플이 마이크에 들어온 순간, 박 · 마디는 **스피커에서 난 시각**
  - `page` · `metronome`(start/stop) · `beat`(마디 · 마디 안 박 · bpm, 예비박이면 `count_in`) · `measure`
  - ⚠️ `info.time_signature` · `bpm` 은 녹음 시작 때의 **메트로놈 설정**이라 악보 박자와 다를 수 있다 — 박 단위는 `beat` 사건에서 읽는다(스크립트가 그렇게 한다)
- `scores/` — 실험에 쓴 MusicXML 사본(ScoreMate 동기화 파일, 태블릿 `PDFs/ScoreMate/내 악보/`)

## 목록

곡은 모두 **몰다우 총보**(`Die Moldau (Vltava) (Full Score)`, 6/8, 박 = 8분음표, 120 bpm → 박 0.5초). 기기는 샤오신패드 12.7(TB371FC), 앱 v0.3.5 + 녹음 기능(커밋 전후 빌드).

| 녹음 (시각) | 길이 | 내용 | 정답(박 · 마디) | 시각 기준 | 쓰임 · 주의 |
|---|---|---|---|---|---|
| `…_192907` | 105초 | **Z18TV** 에서 메트로놈 + 반주, 태블릿은 녹음만(20cm 이내). 쪽 1 → 5 | 없음 (TV 가 돌림) | 옛(첫 버퍼) | TV 소리가 거의 안 들어옴(평균 −57 dBFS). 버튼 탭 소리만 큼. **실험엔 안 씀** — 녹음 도구 첫 확인 |
| `…_194236` | 56초 | 손뼉 몇 번(2 ~ 3.5초) 뒤 **Z18TV** 반주 | 없음 | 옛 | 마이크 이득 확인용 — 손뼉 −2 dBFS(정상), TV 반주 −50 ~ −60 dBFS(TV 음량이 작음) |
| `…_194647` | 33초 | **태블릿**에서 메트로놈 + 반주, 1 ~ 11마디 | 있음 | 옛(첫 버퍼) | ⚠️ 사건이 소리보다 **약 190ms 늦게** 적힘 + 박 기록이 화면 틱(±21ms). 시각 맞춤 수정의 계기 — **정답으로 쓰지 말 것** |
| `…_195355` | 51초 | 태블릿 메트로놈 + 반주(총보 전체), 1 ~ 14마디, 예비박 두 마디 | 있음 (박 94 · 마디 14) | **timestamp** | 시각 맞춤 확인: 예비박 클릭이 기록보다 **약 60ms** 늦음(출력 지연), 박 기록 흔들림 0.4ms. 첫 DTW 실험 |
| `…_200858` | 177초 | 태블릿 **반주만**(클릭 끔, 총보 전체), 1 ~ 57마디, 쪽 1 → 10 | 있음 (박 349 · 마디 57) | **timestamp** | **주 실험 자료**(P08 §7). 똑같은 반복 40 ~ 47 = 48 ~ 55 포함. 클릭이 없어 지연은 재지 않음(60ms 가정) |

모두 **합성음**(같은 MusicXML 을 앱 합성기로 낸 반주)이다 — 가장 쉬운 경우. 실제 기타 녹음은 P08 §8.

## 다시 돌리기

```bash
R=data/recordings; X="$R/scores/Die Moldau (Vltava) (Full Score).musicxml"
python data/recording_align.py "$R/Die Moldau (Vltava) (Full Score)_20260928_195355"          # 출력 지연 (예비박 클릭)
python data/score_follow_dtw.py "$R/Die Moldau (Vltava) (Full Score)_20260928_200858" "$X"          # 오프라인
python data/score_follow_dtw.py "$R/Die Moldau (Vltava) (Full Score)_20260928_200858" "$X" --online --stretch 0.85
```

(venv 에 `librosa` 필요)

## 새 녹음을 넣을 때

`adb pull` 로 받고 위 표에 한 줄 — 무엇을 어떻게 연주했는지(기기 · 파트 보기 · 반주 · 실제 악기 · 연주 상태), 정답 유무, 시각 기준, 쓰임.
