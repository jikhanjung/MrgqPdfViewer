# 046 — 악보 구조 분석을 앱으로: 마디 검출(PdfBox) + DB 캐시 + 마디 박스 오버레이

작성일: 2026-09-13
선행: [`037`](20260613_037_score_analysis_work.md)(파이썬 분석 — 이번 작업의 원본),
[`P02`](20260613_P02_score_sync_autoturn_plan.md)(자동 넘김 로드맵 — "공통 토대" 1·2번),
[`045`](20260913_045_pdf_document_info.md)(PdfBox-Android 도입)

---

## 0. 요약

`data/segment_score.py` 의 시스템·마디 검출을 **앱 안(Kotlin + PdfBox)** 으로 옮기고, 결과를 DB 에
캐시하고, 뷰어에 **마디 박스 오버레이**를 붙여 눈으로 검증했다.

| 결정 (사용자 선택) | 내용 |
|---|---|
| 범위 | 분석 + 저장 + 디버그 오버레이 (Phase 1 자동 넘김은 다음) |
| 대상 | Moldau 형식(Sibelius → Microsoft Print to PDF) 먼저. 다른 형식은 "마디 없음" 으로 조용히 넘어간다 |

결과:

- **골든 일치**: 파이썬 결과(26 시스템 / 81 마디)와 시스템 수·마디 수·마디선·보표 밴드가 **0.6pt 이내로 전부 일치**. 첫 실행에서 맞았다
- **화면 정합**: 두 페이지 모드에서 박스가 마디선에 정확히 붙고, 박스 번호가 인쇄된 마디 번호와 같다 (에뮬레이터 스크린샷)
- **P02 의 "최대 난관" 좌표 변환**을 렌더러와 같은 공식으로 풀었다 — Phase 1 마디 하이라이트가 그대로 쓸 수 있다

---

## 1. 구조

```
PdfViewerActivity ── (오버레이 켜짐) ──→ MusicRepository.getOrAnalyzeScoreMeasures
                                            └→ ScoreLayoutStore (Mutex, DB 캐시)
                                                 └→ ScoreLayoutAnalyzer (처음 한 번)
                                                      ├ PdfPathCollector   ← PdfBox PDFGraphicsStreamEngine
                                                      └ StaffSystemDetector ← segment_score.py 포트 (순수 Kotlin)
refreshScoreOverlay ─→ ScoreOverlayGeometry (pt → 비트맵 px, 순수) ─→ ScoreOverlayView (imageMatrix 적용)
```

| 파일 | 역할 | 테스트 |
|---|---|---|
| `score/PdfPathCollector.kt` | 콘텐츠 스트림 실행, 칠한 경로마다 bbox | 골든(계측) |
| `score/StaffSystemDetector.kt` | 오선·보표·시스템·마디선 판정 | JVM 11 |
| `score/ScoreLayout.kt` | 페이지·시스템 → 문서 전체 마디 번호 | JVM(위와 같이) |
| `score/ScoreLayoutAnalyzer.kt` | 문서 단위 분석, 회전 페이지 제외 | 골든 7 |
| `database/entity/ScoreMeasure.kt` + DAO | 마디 테이블 (v6) | 마이그레이션 1 |
| `score/ScoreLayoutStore.kt` | 처음 한 번 분석·캐시, 무효화, 경로 가드 | 계측 7 |
| `score/ScoreOverlayGeometry.kt` | 마디 pt → 표시 비트맵 px | JVM 8 |
| `score/ScoreOverlayView.kt` | ImageView 위 투명 뷰 | 스크린샷 |

### 파이썬 → Kotlin 에서 달라진 것

- **CTM 추적이 사라졌다.** PdfBox 가 q/Q/cm 을 적용한 좌표로 `moveTo/lineTo/curveTo/appendRectangle` 을 부른다
- **파이썬 파서가 보지 않던 것을 본다**: `re` 사각형, Form XObject 안의 경로, 한 줄에 여러 연산자. 이 파일에서는 결과에 영향이 없었다 (골든 일치)
- 페이지 콘텐츠 오브젝트 번호 하드코딩(`PAGE_CONTENTS`)과 `mutool clean` 전처리가 필요 없다
- 판정 임계값(pt)은 **그대로** 옮겼다 — 이 형식 전용이라는 한계도 그대로다

---

## 2. 골든 테스트와 변이 검사

`ScoreLayoutAnalyzerTest` 는 `data/` 에서 복사한 `Moldau0607.pdf` 와 파이썬 결과 JSON 을 androidTest
assets 로 두고 대조한다(PdfBox-Android 는 Android 런타임이 필요해 JVM 테스트로는 못 돌린다).

첫 실행에 통과해서 오히려 **테스트가 실제로 무엇을 잡는지** 규칙을 하나씩 꺼 봤다:

| 변이 | 단위 테스트 | 골든 테스트 |
|---|---|---|
| 음표 머리 붙은 세로선(기둥) 제외 규칙 끔 | ❌ 실패 (잡음) | ✅ **통과 — 못 잡음** |
| 겹세로줄 병합 끔 (`MIN_MEASURE_WIDTH` = 0) | ❌ 실패 | ❌ 4개 실패 — p9 시스템 2 의 `:\|\|` 가 마디 하나로 더 잡혀 82마디 |

**기둥 제외 규칙은 이 샘플에서 결과를 바꾸지 않는다.** #037 에서는 이 규칙이 p6 과검출(12마디)을
고쳤다고 기록했지만, 그때 같이 넣은 "양 끝 ±1.5pt 정렬"과 "거의 모든 보표에 같은 x" 두 규칙만으로
이미 걸러지는 것으로 보인다. 즉 이 규칙은 **골든이 아니라 단위 테스트만 지킨다.** (PdfBox 경로에서
음표 머리가 실제로 검출되는지는 따로 확인하지 않았다.)

---

## 3. 저장 (스키마 v6)

- `score_measures(pdfFileId, measureNumber)` PK, `pdf_files(id)` ON DELETE CASCADE.
  마디 박스(시스템 세로 범위 × 마디 가로 범위)와 **페이지 크기**를 함께 둔다 — 오버레이 좌표 변환에 필요
- `pdf_files.scoreAnalyzedAt` — "분석했음" 표시. **마디 0개(악보 아님·열 수 없음)도 표시**해서 다시 분석하지 않는다
- 무효화: 파일이 바뀌면 `PdfFileSync` 가 레코드를 새로 쓰면서 `scoreAnalyzedAt` 이 비워지고(일부러 보존 안 함),
  다음 조회에서 이전 마디를 지우고 다시 분석한다. 이전 마디가 남지 않는 것을 테스트로 고정

### 경로 가드

뷰어가 다음 파일로 넘어가는 동안 `currentPdfFileId` 는 잠시 **이전 파일**을 가리키고 `pdfFilePath` 는
**새 파일**이다. 그대로 분석하면 새 파일의 마디를 이전 파일 레코드에 저장한다. 그래서
`ScoreLayoutStore` 는 레코드의 경로와 요청 파일이 다르면 분석하지 않고 null 을 돌려준다.

---

## 4. 오버레이 좌표 변환

```
PDF pt(위→아래) ─(fitScale, 위 클리핑)→ 페이지 비트맵 px ─(두 페이지면 TwoPageOffsets)→ 표시 비트맵 px ─(imageMatrix)→ 화면
```

- 앞의 두 단계는 **렌더러와 같은 함수**(`PageGeometry.compute`, `TwoPageOffsets.compute`)로 계산한다.
  클리핑·중앙 여백·두 페이지 배치가 바뀌어도 박스가 악보와 같이 움직인다
- 마지막 단계는 계산하지 않고 **ImageView 에 실제로 걸린 imageMatrix 를 그대로 쓴다.** 행렬을 만드는
  `setImageViewMatrix` 가 두 벌이고(1.0 스냅·정수 반올림 유무가 다르다) 다시 계산하면 드리프트한다
- 페이지 크기는 PdfBox 의 실수 크기를 **절삭**해 `PdfRenderer.Page.getWidth/Height` 와 맞춘다.
  추정으로 두지 않고 계측 테스트가 실제 PdfRenderer 값(595×841)과 대조한다
- 박스는 비트맵에 그리지 않고 별도 뷰로 올린다 — 캐시된 페이지 비트맵을 건드리지 않는다
- 넘김 애니메이션 동안 숨기고, 끝나서 `pdfView` 에 행렬이 잡히면 다시 그린다

켜는 곳: OK 길게 누르기 → PDF 표시 옵션 → **"마디 박스 표시 (악보 분석)"** (전역 설정). 켜면 현재 파일을
처음 한 번 분석하고 "마디 N개" 토스트를 띄운다. 시스템마다 파랑/빨강을 번갈아 칠한다.

---

## 5. 실행 확인 (에뮬레이터 API 29 TV, 1920×1080)

- 분석 로그: `Moldau0607.pdf: 13쪽, 시스템 26개, 마디 81개 (8897ms)`
- 가로 화면 + 세로 PDF → **자동 두 페이지 모드**로 열림 — 좌표 변환에서 가장 까다로운 경우가 바로 검증됐다
- 1–2쪽(마디 1–13), 3–4쪽(14–25) 스크린샷에서 박스가 마디선에 붙고 번호가 인쇄 번호와 같음

⚠️ **분석 8.9초**. x86 에뮬레이터 값이라 실기기와 다르지만 가볍지 않다. 지금은 오버레이를 켰을 때만,
파일마다 처음 한 번, IO 스레드에서 돌아 화면을 막지는 않는다. Phase 1 에서 모든 악보를 미리 분석하려면
실기기에서 먼저 재 봐야 한다.

확인하지 **못한** 것:
- 옵션 메뉴의 토글 항목을 리모컨으로 눌러 보지 않았다 (설정 파일을 직접 넣어 켰다)
- 클리핑·단일 페이지 모드는 단위 테스트로만 확인했다 (화면으로는 안 봤다)
- 실기기(Google TV Streamer)

---

## 6. 같이 한 것 — CI release 스모크 수정 (045 후속)

045 푸시 후 Instrumentation 이 **API 34 에서 실패**했다. 매트릭스별 로그:

| API | 샘플 넣기 | 결과 |
|---|---|---|
| 21 | "1 file pushed" 인데 **실패 코드** → 검사 건너뜀 | 045 에서 "API 21 이 검사를 돈다"고 한 설명이 틀렸다 |
| 30 | Permission denied | 건너뜀 |
| 34 | 성공 | 제목 로그 없음 → 실패. 진단 필터에 우리 태그가 없어 원인 불명 |

adb 종료 코드를 믿지 않도록 판정을 바꿨다: **앱 로그로 샘플을 봤는지(`LOADED N PDF FILES`) 먼저 가르고**,
봤는데 권한 오류도 없이 제목이 없을 때만 실패. 못 봤으면 경고. 진단 필터에 `PdfFileSync` 등 태그 추가.
로컬 API 29 통과. **CI 에서는 아직 안 돌렸다.**

---

## 7. 다음 — MusicXML 과 메트로놈 (사용자 메모, 2026-09-13)

### MusicXML (Sibelius 에서 PDF 와 같이 내보낼 예정)

P02 의 하이브리드: PDF = "어디에", MusicXML = "무엇을". 이번 설계에서 미리 맞춰 둔 것과 남은 것:

- `ScoreMeasure.measureNumber` 는 **PDF 에서 찾은 순서**(1부터 연속)로 의미를 못박았다.
  MusicXML 의 `<measure number>` 는 못갖춘마디 `"0"`, `implicit="yes"`, 반복 때문에 다를 수 있다
- 둘 사이 **정렬은 별도 테이블/단계**로 둔다. 확인해야 할 실제 사례가 이미 있다: 마지막 페이지의
  `:||` + 빈 마디 2개(#037), p9 의 겹세로줄
- 짝짓기는 **같은 이름**(`악보.pdf` + `악보.musicxml`/`.mxl`)을 전제로 한다 — 웹 업로드가 둘을 같이 받게 된다
- MusicXML 이 있으면 박자표·템포를 거기서 가져와 메트로놈·자동 넘김의 입력이 된다

### 메트로놈

분석과 **독립**이다. BPM·박자만 있으면 된다 — 클릭음(기존 SoundPool 효과음) + 화면 박자 표시.
나중에 마디 박스가 있으면 현재 마디 하이라이트로 이어진다(Phase 1).

---

## 8. 남은 것

- 분석 속도(에뮬레이터 8.9초) — 실기기 측정
- 임계값이 pt 고정 — 다른 크기·작성기 악보는 못 찾는다
- 회전 페이지는 분석하지 않는다
- 기둥 제외 규칙은 골든이 지키지 않는다 (§2)
- 옵션 토글의 리모컨 조작, 클리핑·단일 페이지 화면 확인, 실기기
