# 044 — Room 마이그레이션 테스트: 없던 스키마 JSON 과 조용히 지워지던 설정

작성일: 2026-09-13
선행: `0087ca6`(스키마 export), `713af4e`(드리프트 게이트), [`043`](20260906_043_instrumentation_ci_repair.md)(계측 CI 복구)
관련 문서: [`docs/4_Build_CI_Release.md`](../docs/4_Build_CI_Release.md) §2 갭 1-4, §4-4

---

## 0. 요약

`docs/4_Build_CI_Release.md` 에 "실제로 고장났던 영역 중 유일하게 미착수"로 남아 있던 Room
마이그레이션 테스트를 붙였다. 계측 테스트 5개(`MusicDatabaseMigrationTest`).

작업 중에 두 가지가 드러났다.

| # | 발견 | 처리 |
|---|---|---|
| 1 | 문서의 "과거 버전 스키마 JSON 이 갖춰졌다"는 **틀렸다** — `app/schemas` 에는 `4.json` 뿐 | 옛 DB 를 git 히스토리로 복원한 SQL 로 직접 생성. 문서 정정 |
| 2 | `MIGRATION_3_4` 가 `user_preferences` 를 **복사 없이 DROP 후 재생성** — v1~v3 의 파일별 표시 설정이 전부 초기화됐다 | 배포된 동작이라 유지. 테스트로 고정 + 코드 경고 주석 |

---

## 1. 마이그레이션이 뭐였나

DB 에는 파일 메타데이터(`pdf_files`)와 OK 롱프레스로 조정하는 파일별 표시 설정
(`user_preferences`: 표시 모드, 위/아래 클리핑, 중앙 여백, 마지막 페이지)이 저장된다.

| 단계 | 커밋 | 내용 |
|---|---|---|
| v1 | `96c23d9` (2025-07-13) | 최초. 설정에 클리핑·여백 없음 |
| 1→2 | (커밋 없음) | 클리핑 2개(REAL) + `centerPadding`(INTEGER, 픽셀) `ALTER TABLE ADD COLUMN` |
| 2→3 | `dc2c09f` (2025-07-15) | 중앙 여백 픽셀 → 비율. 새 테이블 생성 후 `centerPadding / 600.0` 으로 복사 |
| 3→4 | `8057448` (v0.1.8) | "외래키 문제 수정" — 테이블 DROP 후 재생성 |

v0.1.8 의 "두 페이지 모드 설정이 저장 안 됨" 버그 원인이 이 마이그레이션 오류였다.

---

## 2. 발견 1 — 스키마 JSON 이 4.json 뿐이다

`MigrationTestHelper.createDatabase(name, 1)` 은 `1.json` 을 읽어 그 시점 DB 를 만든다. 그런데
스키마 export 를 켠 것이 v4 시점(2026-08-15)이라 과거 JSON 은 한 번도 생성된 적이 없다.
문서는 "export 를 켰으니 전제가 갖춰졌다"고 적었지만, export 는 **현재 버전만** 쓴다.

### 대응: 옛 DB 를 SQL 로 직접 만든다

- git 히스토리의 엔티티로 당시 Room 이 생성했을 `CREATE TABLE` 문을 복원했다.
  - v1: `96c23d9` 엔티티
  - v3: `dc2c09f` 엔티티 (= 현재 v4 와 동일한 테이블)
  - **v2 는 커밋된 적이 없다** (`96c23d9` v1 → `dc2c09f` v3 로 바로 감). `MIGRATION_1_2` 가 추가한
    컬럼과 `MIGRATION_2_3` 주석("INTEGER → REAL")에서 역산했다.
- `SupportSQLiteOpenHelper` 로 그 버전의 DB 파일을 만들고 데이터를 넣은 뒤,
  `helper.runMigrationsAndValidate(name, 4, …)` 로 최신까지 올리면서 `4.json` 과 대조한다.
  대조에는 목표 버전 JSON 만 필요하다.
- v2→v3 변환은 3.json 이 없어 헬퍼로 v3 검증을 할 수 없으므로, 마이그레이션 객체의
  `migrate()` 를 직접 호출하고 값을 조회한다.

앞으로 v5 를 만들 때는 `4.json` 이 있으므로 `helper.createDatabase(name, 4)` 를 쓸 수 있다.
직접 SQL 이 필요한 것은 v1~v3 뿐이다.

---

## 3. 발견 2 — 3→4 가 설정을 지운다

```kotlin
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS user_preferences")
        database.execSQL("CREATE TABLE user_preferences ( … )")   // 복사 없음
    }
}
```

v1·v2·v3 어디서 출발하든 v4 에 도착하면 `user_preferences` 는 비어 있다. 즉 2→3 의 정교한
픽셀→비율 변환 결과도 바로 다음 단계에서 버려진다.

게다가 v3 의 테이블 정의가 v4 와 **완전히 같다** (`dc2c09f` 의 2→3 이 이미 외래키를 포함해
만들었다). 커밋 메시지는 "외래키 제약조건 누락 수정"이지만, 테이블 구조로 보면 3→4 가 실제로
바꾼 것은 데이터 삭제뿐이다. 당시 개발 기기의 DB 가 어떤 상태였는지는 알 수 없으므로 원인
판단은 여기서 멈춘다.

### 고치지 않은 이유

- 이미 배포된 마이그레이션이다. 사용 기기는 전부 v4 에 도달해 있어 지금 고쳐도 되살릴 데이터가 없다.
- 고치면 v3 에 머문 기기가 있을 때만 이득인데, 그런 기기는 없다.

그래서 **현재 동작을 테스트로 고정**했다. v1~v3 → v4 테스트는 "설정 행 0개"를 단언하고, 이
단언이 깨지면 "보존하도록 바뀐 것 — 의도했는지 확인할 것"이라는 메시지를 낸다.

---

## 4. 테스트 구성

`app/src/androidTest/java/com/mrgq/pdfviewer/MusicDatabaseMigrationTest.kt`

| 테스트 | 검증 |
|---|---|
| `v1_에서_최신까지_스키마가_일치한다` | 4.json 과 테이블·외래키 일치, `pdf_files` 보존, 설정 초기화(현재 동작) |
| `v2_에서_최신까지_스키마가_일치한다` | 〃 |
| `v3_에서_최신까지_스키마가_일치한다` | 〃 |
| `v2_to_v3_중앙여백_픽셀이_비율로_변환된다` | 60px → 0.1, 0 → 0, 클리핑·표시 모드·페이지 보존 |
| `v1_DB_를_앱의_마이그레이션으로_열어_읽고_쓸_수_있다` | 앱과 같은 목록으로 Room 을 열고 DAO 로 enum 읽기·쓰기 |

### 등록 누락도 잡는다

헬퍼에 마이그레이션을 직접 넘기는 테스트는, **앱의 `Room.databaseBuilder` 에서 `addMigrations`
를 빠뜨린 경우**를 못 잡는다. 그래서 `MusicDatabase.ALL_MIGRATIONS` 를 만들어 앱
(`getDatabase`)과 테스트가 같은 배열을 쓰게 했다.

`getDatabase()` 자체를 테스트에서 부르지 않은 것은 의도다. DB 이름이 `music_database` 로
고정이라, 실기기에서 `connectedAndroidTest` 를 돌리면 **사용자의 실제 설정 DB 를 덮어쓴다.**
테스트는 `migration-test` 라는 별도 파일을 쓰고 전후로 지운다.

### 빌드 설정

- `androidTest` assets 에 `app/schemas` 추가 — 헬퍼는 테스트 APK 의 assets 에서 JSON 을 읽는다
- `androidTestImplementation`: `room-testing:2.6.1`, `sqlite-framework:2.4.0`

---

## 5. 검증

로컬(Windows Gradle + `Television_1080p_2` AVD, API 30 google-tv x86):

| 단계 | 결과 |
|---|---|
| `assembleDebug assembleDebugAndroidTest` | 성공 (`internal` 가시성 androidTest 에서 문제없음) |
| `connectedDebugAndroidTest` | **11/11 통과** (신규 5 + 기존 `MusicDatabaseTest` 6) |
| 변이 검사: `MIGRATION_3_4` 에서 `ON DELETE CASCADE` 제거 | **4개 실패** (스키마 3개 + Room 열기 1개, `Migration didn't properly handle: user_preferences`) |
| 변이 복원 후 재실행 | 11/11 통과 |
| `lintDebug testDebugUnitTest` | 성공 |
| `git status app/schemas` | 드리프트 없음 |

변이 검사를 한 이유는 043 의 교훈이다 — **게이트를 켠 것과 게이트가 도는 것은 다르다.**
헬퍼의 스키마 대조가 실제로 외래키 차이를 잡는지 눈으로 확인했다.

API 21 / 34 는 로컬에서 돌리지 않았다. main 푸시 시 Instrumentation 워크플로가 검증한다.

---

## 6. 남은 것

- **API 21/34 결과는 CI 확인 필요.** 특히 API 21 에서 `room-testing` 이 문제없이 도는지.
- **2→3 변환에 상한이 없다.** 150px 이상은 0.25 처럼 슬라이더 범위(0~15%)를 넘는다. 3→4 가
  결과를 버리므로 실제 영향은 없어 테스트하지 않았다.
- v5 를 만들 때: 테스트의 `LATEST_VERSION` 을 올리고 4→5 는 `helper.createDatabase(name, 4)` 로.
  3→4 처럼 테이블을 재생성할 때는 **데이터 복사를 빠뜨리지 말 것** — 이번 테스트 패턴(시드 →
  마이그레이션 → 행 조회)이 그걸 잡는다.
