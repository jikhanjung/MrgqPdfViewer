# 045 — PDF 문서 정보(제목·작성자) 읽기: PdfBox-Android + 스키마 v5

작성일: 2026-09-13
선행: [`044`](20260913_044_room_migration_test.md)(마이그레이션 테스트 — v4→v5 검증에 바로 썼다),
[`037`](20260613_037_score_analysis_work.md)(파이썬 악보 분석 — 이번은 그 "1단계"의 문서 정보만)
관련 문서: [`docs/4_Build_CI_Release.md`](../docs/4_Build_CI_Release.md)

---

## 0. 요약

PDF 의 문서 정보(Info 딕셔너리)에서 **제목과 작성자**를 읽어 DB 에 저장하고 파일 목록 카드에
표시한다. 파이썬으로 했던 악보 구조 분석(시스템·마디 bbox)은 "2단계"로 남겨 두었다.

| 결정 | 내용 | 이유 |
|---|---|---|
| 라이브러리 | **PdfBox-Android 2.0.27.0** | `PdfRenderer` 는 렌더링만 한다. 자체 파서보다 견고(암호화 포함)하고, 2단계의 콘텐츠 스트림 파싱에도 쓸 수 있다 |
| 작성자 | `author` 컬럼 신설 (composer 에 넣지 않음) | PDF 의 Author 는 **문서 작성자**다. 샘플 총보(몰다우)의 Author 도 작곡가(스메타나)가 아닌 다른 사람 이름이었다 |
| 스키마 | v4 → **v5** (`author`, `docInfoReadAt`) | 044 에서 만든 마이그레이션 테스트로 바로 검증 |
| 목록 | 페이지 수·문서 정보를 **DB 캐시**로 | 목록 로드마다 모든 파일을 PdfBox 로 열 수 없다 |

결과 화면 (release APK, API 29 TV 에뮬레이터):

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Moldau0607.pdf                                  391.6 KB • 13페이지 • …        │
│ Die Moldau (Vltava) - Full Score · (작성자)                                   │
├──────────────────────────────────────────────────────────────────────────────┤
│ Moldau_1_Jinho.pdf                              369.2 KB • 3페이지 • …         │
└──────────────────────────────────────────────────────────────────────────────┘
```

파트보는 Title 이 파일명과 같아(아래 §5) 부제목 줄을 숨긴다.

---

## 1. 구조

```
MainActivity.getCurrentPdfFiles ─┐
PdfViewerActivity.ensurePdfFile ─┴→ MusicRepository.syncPdfFile → PdfFileSync.sync (Mutex)
                                                                   │ 처음 보거나 바뀐 파일만
                                                                   ▼
                                                   PdfAnalyzer.analyzePdfFile
                                                   ├ PdfRenderer: 페이지 수·크기·방향
                                                   └ PdfMetadataReader (PdfBox): Title·Author
```

| 파일 | 역할 |
|---|---|
| `utils/PdfMetadataReader.kt` | PdfBox 로 Info 읽기. 손상·사용자 암호 → null |
| `utils/PdfDocumentInfo.kt` | **순수 함수**: 값 정리(공백·NUL), 카드 부제목 규칙, 재분석 판정 — JVM 단위 테스트 |
| `repository/PdfFileSync.kt` | 파일 ↔ 레코드 동기화. 캐시 판정, update 강제, 직렬화 |
| `PdfViewerApplication` | `PDFBoxResourceLoader.init` |

### 재분석 판정

`docInfoReadAt == null`(v4 이전 레코드) 이거나 **분석 당시 mtime ≠ 지금 mtime** 이면 다시 읽는다.
분석 당시 mtime 은 원래부터 `PdfAnalyzer` 가 `createdAt` 에 넣던 값을 그대로 쓴다.
"늘었는지"가 아니라 "같은지"로 본 것은, adb push 처럼 원본 mtime 을 보존하는 복사가 있어서다.

재기동 시 분석 로그가 0줄인 것까지 에뮬레이터에서 확인했다.

---

## 2. 함정 1 — `insertPdfFile` 로 갱신하면 설정이 지워진다

`PdfFileDao.insertPdfFile` 은 `OnConflictStrategy.REPLACE` 다. SQLite 의 REPLACE 는 기존 행을
**DELETE 후 INSERT** 하므로, `user_preferences` 의 `ON DELETE CASCADE` 가 발동해 파일별 표시
설정이 사라진다. 그래서 기존 레코드 갱신은 `updatePdfFile` 로만 한다.

추측으로 두지 않고 테스트로 고정했다:

- `REPLACE_삽입은_표시설정을_지운다` — 함정이 실제로 있다는 증거 (통과 = 설정이 지워짐)
- `파일이_바뀌면_다시_읽고_표시설정은_보존된다` — `PdfFileSync` 가 그 함정을 피한다는 증거

지금까지는 `ensurePdfFileInDatabase` 가 "없을 때만 insert" 라 드러나지 않았다. 레코드를 갱신하는
경로가 처음 생기면서 표면에 올라온 것이다.

## 3. 함정 2 — 같은 파일을 두 번 동시에 분석한다

release APK 첫 기동 로그에서 같은 파일의 분석 로그가 **스레드만 다르게 두 줄** 찍혔다.
`MainActivity` 가 `onCreate` 와 `onResume` 에서 목록 로드를 겹쳐 띄우기 때문이다(기존 동작 —
전에는 PdfRenderer 로 페이지 수를 두 번 셌을 뿐이라 티가 안 났다). 둘 다 "레코드 없음"을 보고
insert(REPLACE) 를 겹쳐 실행할 수 있으므로, `PdfFileSync` 를 `Mutex` 로 직렬화했다.
두 번째 호출은 첫 번째가 저장한 레코드를 보고 바로 돌아간다.

목록 로드 중복 자체는 그대로 두었다 (이번 범위 밖).

---

## 4. APK 크기 — 2.6MB → 8.8MB → 4.6MB

PdfBox 를 넣자 release APK 가 **8.8MB** 가 됐다 (v0.1.13 은 2.6MB). 내용물을 나눠 보니:

| 항목 | 비압축 | 압축 | 처리 |
|---|---|---|---|
| `org/bouncycastle/pqc` (SIKE·Picnic 데이터) | 7.79MB | 3.96MB | **제외** |
| `assets/com/tom_roush` (폰트·CMap) | 4.42MB | 1.54MB | 유지 |
| `classes.dex` | 2.45MB | 1.16MB | — |

BouncyCastle 의 양자내성암호 데이터 파일은 dex 가 아니라 **Java 리소스**라 R8 이 걷어내지 못한다.
PDF 암호화(RC4·AES)와 무관하므로 `packaging.resources.excludes` 로 뺐다 → **4.6MB (+2.0MB)**.

PdfBox 의 폰트·CMap assets(압축 1.5MB)는 문서 정보 읽기에는 필요 없지만, 2단계(콘텐츠 스트림
파싱)에서 쓸 수 있어 남겼다. 더 줄여야 하면 다음 후보다.

R8 은 선택 의존성인 JPEG2000 디코더(`com.gemalto.jp2.JP2Decoder`) 누락으로 빌드를 막았다.
이미지를 디코딩하지 않으므로 `-dontwarn com.gemalto.jp2.**` 로 해결했다.

---

## 5. 정정 — 파트보 PDF 에도 Title 이 있다

작업 전에 "`data/parts/` 의 파트보 PDF 에는 Info 가 아예 없다"고 판단했는데 틀렸다. 그때
`pdfinfo … | grep` 이 아무것도 안 찍은 것을 그렇게 읽었지만, 실제로는
`Title: "Moldau_1_Jinho "`(파일명 + 공백, Producer: ImageMagick)가 있다. PdfBox 로그로 드러났다.

결과적으로 카드 부제목 규칙("제목이 파일명과 같으면 숨김")이 바로 이 사례를 처리한다.
단위 테스트에 실제 값 그대로 넣었다.

---

## 6. release 스모크에 문서 정보 검사 추가

`.github/scripts/release_smoke.sh` 가 샘플 악보(`data/Moldau0607.pdf`)를 앱 폴더에 넣고 기동한 뒤,
minify 된 APK 가 로그에 `title=Die Moldau` 를 남기는지 본다. PdfBox 는 암호화 핸들러를
리플렉션으로 만들어 R8 에 취약한 표면이다.

겪은 것 두 가지:

1. **Android 11+ 에서는 넣기가 실패한다.** 로컬 API 30 에서 셸이 다른 앱의 `Android/data` 에
   `Permission denied`. 실패는 경고로 두고 검사를 건너뛴다. R8 결과물은 API 레벨과 무관하게
   같으므로, 넣을 수 있는 매트릭스(CI 의 API 21)에서 한 번 보면 된다. 로컬에서는 API 29 로 확인했다.
2. **`logcat | grep -q` 가 찾았는데도 실패했다.** API 29 첫 실행에서 로그에 문자열이 있는데
   실패 판정. `pipefail` + `grep -q` 조기 종료 → logcat SIGPIPE 가 가장 유력하다. 변수에 담아
   here-string 으로 grep 하도록 바꾼 뒤 통과했다. 다만 **단독 재현은 되지 않아 원인은 추정**이다.

---

## 7. 동작 변화

- 파일 목록의 페이지 수가 매 로드 PdfRenderer 계산 → **DB 캐시**로 바뀌었다 (파일이 바뀌면 다시 계산)
- `pdf_files` 레코드가 파일을 **열 때가 아니라 목록에 보일 때** 생긴다
- 업데이트 후 첫 목록 로드는 모든 파일을 한 번씩 분석한다 (v4 레코드 포함). 이후는 캐시

---

## 8. 검증

| 항목 | 결과 |
|---|---|
| 단위 테스트 | **66/66** (신규 `PdfDocumentInfoTest` 9) |
| 계측 테스트 | **25/25** — API 30 TV(Mutex 적용 전), API 29 TV(최종). 신규 `PdfMetadataReaderTest` 7, `PdfFileSyncTest` 6, 마이그레이션 v4→v5 1 |
| `lintDebug` · `assembleRelease`(R8) | 성공 |
| release 스모크 (API 29) | `문서 정보 읽기 ✓` + 정상 기동 |
| 화면 | 총보 카드에 "제목 · 작성자", 파트보는 숨김 (스크린샷 확인) |
| 스키마 | `5.json` 생성·커밋 대상 |

`PdfMetadataReaderTest` 는 PdfBox 로 만든 PDF 외에 **바이트를 직접 조립한 PDF**(UTF-16BE 16진 제목,
이스케이프된 괄호)도 쓴다. PdfBox 가 저장하는 방식에 기대지 않고 다른 작성기의 표현을 재현하려는 것이다.

---

## 9. 남은 것

- **CI API 21 에서 스모크 문서 정보 검사가 실제로 도는지** 확인 (로컬은 API 29 까지만)
- XMP 메타데이터(PDF 2.0 에서 Info 대신 쓰는 dc:title)는 읽지 않는다
- `composer`·`key`·`tempo` 등은 여전히 채우는 곳이 없다. `searchPdfFiles` 는 title 은 보지만 author 는 안 본다
- 웹 파일 관리 페이지에는 표시하지 않는다
- `MainActivity` 목록 로드 중복(onCreate + onResume)
