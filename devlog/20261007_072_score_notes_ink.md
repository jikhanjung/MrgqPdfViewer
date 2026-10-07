# 072 — 악보 메모 0 · 1단계: 좌표 공식 · 펜으로 선 긋기 · 붙는 보표 (P11)

작성일: 2026-10-07
관련: [`P11`](20261007_P11_score_annotations_plan.md), ScoreMateServer devlog 076(메모 동기화 계획)
상태: 🟡 구현 · 단위 테스트 통과(349개 중 새것 24) · debug APK 빌드 — **실기기 확인 전**

---

## 1. 한 일

### 0단계 — 쪽 pt ↔ 표시 비트맵 한 공식
- `ScoreOverlayGeometry.placements()` → `PagePlacement`(쪽 · fitScale · 위 클리핑 pt · 오프셋 · 표시 크기). `toBitmapX/Y` · `toPageX/Y` · `contains`
- 마디 박스(`boxes`)도 이것을 쓰게 바꿨다 — 렌더러 · 마디 박스 · 메모가 같은 공식(#046 원칙). 기존 마디 박스 테스트 그대로 통과
- 오른쪽 쪽을 따로 받는다 — 차례 넘김(3 | 2, P10) 화면에서도 맞다
- 쪽 크기: 렌더할 때 기록(`PageCache.pageSizeOf`, 뷰어 직접 렌더는 렌더러 인스턴스에 묶은 표 — 렌더러가 바뀌면 자동으로 비움). 마디가 없는 쪽도 된다

### 1단계 — 선 긋기 (`notes/` 새 패키지)
| 파일 | 내용 |
|---|---|
| `ScoreNotes` | 메모 모델(`ScoreNote.Ink` — id 문자열 · 쪽 · 색 · 굵기 pt · 점 pt · 붙는 보표) + 더하기 · 지우기 · 되돌리기/다시(지우개 한 번 = 되돌리기 한 번) |
| `NoteGeometry` | Douglas–Peucker 줄이기(0.25pt), 지우개 맞히기(굵기 절반만큼 넓혀), 위 · 아래 끝 |
| `NoteStaff` | **붙는 보표** — 오선 안이면 분명, 사이면 가까운 쪽(거리 차 30% 미만이면 애매), 보표 높이 3배보다 멀면 전체 악보 |
| `ScoreNotesFile` | 곁 파일 `<이름>.notes.json` 읽기 · 쓰기(다른 이름 → 바꿔 끼우기), 모르는 type 보존, 한 줄 스레드로 열기 · 저장, 새 판 감지(pdf_sha256) |
| `ScoreNotesView` | pdfView 위 메모 층 — 쪽 pt → 비트맵 px → imageMatrix 로 그리기, 쪽마다 잘라 그림, 긋는 획 · 지우개 동그라미 · 붙을 보표 띠 + 이름 |
| `NotePen` | 터치 입력 — 펜은 바로, 손가락은 메모 모드에서만(가장자리 8% 탭 = 넘김), 펜 지우개 끝 · 펜 단추 = 지우개, 손바닥 무시 500ms, 두 손가락이면 그 획 버림, 화면이 바뀌기 직전 긋던 획 마저 저장(`flush`) |

뷰어(`PdfViewerActivity`):
- `dispatchTouchEvent` 앞단에서 `NotePen` 이 먼저 본다. 도구 줄에서 시작한 터치는 단추에만
- 메모 층은 마디 박스와 같은 때 다시 그린다(`refreshScoreOverlay` → `refreshNotes`), 넘김 애니메이션 중엔 숨김
- 메뉴: ↑ 메뉴 · 연주자 메뉴 · PDF 표시 옵션에 **✏️ 메모 쓰기 / 메모 끝**(TV 아님), PDF 표시 옵션에 **메모 보이기: 켜짐/꺼짐**(전역)
- 도구 줄(위 가운데): ✏️ · 🧽 · 색 3(검정 · 빨강 · 파랑) · 굵기 3(0.8 · 1.5 · 3pt) · ↶ · ↷ · ✔. 색 · 굵기는 기억
- 뒤로 = 메모 끝. 파트 보기에서는 막고 안내, 반 쪽 넘김 화면이면 온전한 쪽으로 돌린 뒤 메모 모드

### 곁 파일이 악보를 따라다니게
- `ScoreMateSync`: `sidecarsOf` = 서버 것(`.musicxml` · `.layout.json`) + 메모. PDF 를 옮기면 함께. 서버 것은 PDF 가 없어지면 지우고(`serverSidecarsOf`), **메모는 보관함 `PDFs/.ScoreMateNotes/<score_id>.notes.json`** 에 두었다가 다시 받으면 돌려 놓는다(곡목에서 빠짐 · TV 에서 지움). 새 판 + 새 이름이면 메모가 새 이름으로. 연결을 끊으며 파일까지 지우면 함께 지움
- 로컬 파일을 지우면(목록 · 웹 · 설정 전체 삭제) 메모도

## 2. 계획에서 바뀐 것
- **저장: Room v19 → 곁 파일** — 사용자 결정 "메모는 악보 파일에 같이 따라다니게, musicxml 처럼". DB 마이그레이션 없음
- **붙는 보표(§3.6)** 추가 — 사용자 요청(파트보에 쓰려면 어느 파트 것인지). 긋는 동안 그 보표 띠를 칠하고 이름, 애매하면 주황 · "?"
- **id: 정수 → 무작위 16자 문자열** — ScoreMateServer 세션 지적(여러 기기가 서버에서 합칠 때 겹침), format 1 배포 전에
- 펜은 메모 모드 없이 바로(사용자 결정)

## 3. 서버 동기화 논의 (ScoreMateServer · 반장 세션과, 같은 날)
P11 §6 에 정리. 요점: personal · conductor 두 겹, score id 에 묶음, 문서 통째 PUT + base_revision(409 → 앱이 합침), 합치기 = 서버 ∪ 내 추가 − 내 삭제, **메모는 불변**(고치면 새 id), 목록에서 빠짐 ≠ 삭제, 겹마다 곁 파일 하나, 지휘자 쓰기 권한은 `sync/notes` 의 `conductor_writable`. 앱 구현은 1단계 실기기 확인 뒤.

## 4. 빌드 · 테스트
- 이 기기(리눅스)에는 Windows Gradle 이 없어 `~/.local/share/mrgq-build/` 에 JDK 17 · Android SDK(platform 34 · build-tools 34)를 받아 `./gradlew testDebugUnitTest assembleDebug` 로 확인
- 새 테스트: `ScoreNotesTest`(16 — 되돌리기 · id · 줄이기 · 지우개 · 붙는 보표 · 곁 파일), `ScoreOverlayGeometryTest` +4(왕복 · 두 쪽 · 차례 넘김 · 크기 모름), `ScoreMateSyncTest` +4(옮기기 · 보관 · 돌려 놓기 · 로컬로)

## 5. 남은 것 · 확인할 것
- **실기기**: 샤오신패드(손가락 · 펜이 있으면 펜), 휴대폰(조각 화면에서 긋기 · 조각 이동), TV 두 쪽에서 보이기. 손바닥, 쪽 넘긴 뒤 같은 자리, 클리핑 · 여백 바꿔도 같은 자리, 앱 종료 후 남음
- 휴대폰 조각을 따라가는 화면 이동(`followChunk`) · 마이크 자동 넘김이 긋는 중에 일어나면 `flush` 로 그 화면 기준 저장 — 실기기 확인
- 회전(/Rotate) PDF 의 좌표(P11 §7-7)
- 2단계 글자, 서버 동기화
