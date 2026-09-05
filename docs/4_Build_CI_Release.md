# 빌드 · CI · 릴리스 — 표준 점검 결과

작성일: 2026-08-15 (최종 갱신 2026-09-06)
기준: `devdocs/guides/desktop/` (README · ci.md · packaging-release.md · code-quality.md)

> 기준 가이드는 **Python + Qt + PyInstaller 데스크탑 앱**을 전제로 쓰였다. 이 프로젝트는
> **Android + Kotlin + Gradle** 이므로, 원칙은 가져오되 대응물이 없는 항목은 그대로 옮기지 않는다.
> 가이드 README 의 지침대로 **"의도적으로 안 한 것"과 "아직 못 한 것"을 구분해서 기록**한다.
>
> 가이드는 이 저장소에 커밋하지 않는다(형제 repo 는 public, devdocs 는 private).
> 형제 프로젝트로 참조하려면: `ln -s ../devdocs/guides .guides` + `.gitignore`.

---

## 0. 요약

| 영역 | 상태 |
|---|---|
| 앱 빌드 | 🟢 CI 빌드·버전 단일 출처 확보. ⚠️ **릴리스 서명이 debug 키 폴백** |
| CI | 🟡 단위 테스트 + Lint 게이트(08-15). 에뮬레이터 계측·release 스모크 API 21/30/34 초록불(09-06). **여전히 순수 로직 커버리지는 렌더 튜닝·정렬·프로토콜뿐** |
| 릴리스 | 🟢 2026-08-15 자동화 도입 (태그 → 검증 → 빌드 → 릴리스, 노트는 CHANGELOG) |

### 게이트를 켠 첫날 나온 것 (2026-08-15)

테스트가 0개였던 상태에서 게이트를 켜자마자 **실제 결함 7건**이 나왔다.
"테스트가 없다"는 것이 "버그가 없다"가 아니었음을 보여주는 기록이라 남긴다.

| 발견 | 경로 | 성격 |
|---|---|---|
| `effectiveOversampleFactor` 의 비정상 크기 가드가 음수를 통과 | 단위 테스트 첫 실행 | 잠재 (호출부가 `coerceAtLeast(1)`) |
| **`java.util.Base64`(API 26+) 를 WebSocket 핸드셰이크에서 사용** | Lint `NewApi` | 🔴 **Android 8 미만에서 합주 기능이 죽는다** |
| `Context.getColor`(API 23+) ×4 | Lint `NewApi` | 🔴 API 21~22 크래시 |
| 23자 초과 로그 태그 ×32 | Lint `LongLogTag` | API<24 예외 |
| Leanback 런처인데 `android:banner` 없음 | Lint `MissingTvBanner` | TV 홈 화면에 배너 미표시 |

`minSdk 21` 을 선언만 하고 한 번도 검증한 적이 없다는 점이 그대로 드러난 셈이다.
**Base64 건은 특히 뼈아프다** — 합주 기능은 이 프로젝트가 가장 많은 시간을 쓴 영역인데
(devlog #011~#018, #021~#032), 선언한 최소 지원 구간의 절반에서 애초에 동작할 수 없었다.

---

## 1. 앱 빌드 과정

### 채택됨
- **CI 에서 빌드한다** (`packaging-release.md` §1) — 개발자 노트북 빌드는 그 머신의 특성이 섞인다.
  main 푸시마다 debug+release APK 를 만든다. WSL 에서 Android SDK 없이 개발하는 구조라
  **컴파일 검증을 CI 에 의존**하고 있어 특히 중요하다.
- **버전 단일 출처** (§5) — `app/build.gradle.kts` 의 `versionName` / `versionCode` 가 유일한 원본이고,
  APK 파일명(`MrgqPdfViewer-v${versionName}-release.apk`)이 여기서 파생된다.
  가이드가 요구하는 "artifact name derives from the version single-source" 를 이미 만족한다.
- **버전 일관성 검증** (§5, `ci.md` §4) — 2026-08-15 도입. 릴리스 시 **태그 == versionName** 을
  강제하고, 불일치면 빌드 전에 실패한다.
- **산출물 존재 확인** (`ci.md` §9) — 업로드 **전에** glob 이 실제 APK 에 닿는지 확인한다.
  가이드가 인용한 실제 사고("인스톨러가 빌드·체크섬·릴리스 노트까지 갔는데 첨부만 안 됨,
  한 릴리스 내내 아무도 몰랐음")를 막기 위한 것.
- **체크섬** (§2) — `SHA256SUMS.txt` 를 릴리스에 첨부.

### ⚠️ 실질적 문제 — 릴리스 서명
`RELEASE_KEYSTORE_*` secrets 가 등록돼 있지 않으면 release 빌드가 **debug keystore 로 서명**된다.
빌드는 성공하지만 그 APK 는:
- Play 스토어에 올릴 수 없고,
- **정식 서명된 기존 설치본 위에 덮어쓸 수 없다** (서명 불일치로 설치 거부).

개인용 사이드로딩이라 당장 문제는 없다. 다만 가이드 §2 의 원칙("컴파일된 것과 검증된 것을
혼동하지 말고 릴리스 노트에 명시하라")에 따라 **릴리스 본문에 서명 상태를 자동으로 표기**하도록 했다.
정식 서명을 원하면 `RELEASE_KEYSTORE_BASE64` / `_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`
4개를 repo secrets 에 등록하면 워크플로우 수정 없이 전환된다.

### 미도입 (해도 되는 것)
- **빌드 메타데이터 embed** (§5) — 커밋 SHA·빌드 날짜를 앱에 심어 About 화면에 표시.
  현재는 APK 만 보고 어느 커밋인지 알 수 없다. `BuildConfig` 필드로 넣으면 20줄 이내.

### 해당 없음
PyInstaller / conda DLL / setuptools 자동탐지 / Inno Setup·AppImage·DMG 레시피 —
Android 는 Gradle 이 패키징을 전담하므로 대응물이 없다. 코드 서명은 위 APK 서명이 대응물.

---

## 2. CI 과정 — 🟡 (2026-08-15 이전에는 🔴 였다)

### 채택됨
- **빌드 정의 단일화** (`ci.md` §6) — 2026-08-15. `build.yml` 을 재사용 워크플로우로 두고
  개발 빌드(`android-build.yml`)와 릴리스(`release.yml`)가 **같은 정의를 호출**한다.
  정의를 두 벌 두면 드리프트한다.
- **dev-build 와 tag-release 분리** (§6) — 태그는 `release.yml` 만 받는다. 예전에는 하나의
  워크플로우가 둘 다 받아 태그 푸시 때 같은 빌드가 두 번 돌 수 있었다.
- **"태그 전에 빌드"** (§9) — main 푸시마다 빌드가 돌므로 릴리스가 빌드의 첫 실행이 되는
  상황은 방지된다. 가이드가 경고한 "릴리스가 빌드의 최초 실행"은 이미 피하고 있다.
- **액션 버전 핀** (§6) — Node 24 베이스라인으로 통일 (2026-07-26).

### 🟡 갭 1: 테스트 커버리지 (2026-08-15 착수 → 09-06 현재 단위 57개)

2026-08-15 에 단위 테스트 14개와 Lint 게이트를 넣었다. 첫 대상은 **이 프로젝트에서 가장
자주 틀렸던 영역**인 렌더 배율·감마 계산으로 잡았다 (P01 → P2 → #041 → #042. 세 번 다
실기기에서 눈으로 보고서야 알았고, 세 번 다 상수 하나의 방향 문제였다).

- `RenderTuningTest` — `InkGamma.defaultFor`, `PageCache.effectiveOversampleFactor`.
  측정으로 결정한 값(1080p 앵커 2.0, 4K 중립점 1.0, 34MP 상한)을 **코드로 고정**한다.
- **테스트가 실제로 몇 개 돌았는지 세는 게이트** — 테스트 소스가 통째로 사라져도 gradle 은
  성공하므로, XML 결과에서 개수를 세어 0 이면 실패시킨다 (`ci.md` §8).
- Android Lint 를 게이트로. `abortOnError = true`, 예외는 사유를 적은 것 하나뿐.

**"실제로 고장났던 영역" 커버리지 — 2026-09-06 현재:**

1. [x] ~~**렌더 좌표 계산**~~ — 삼중 구현을 `PageGeometry` 순수 함수로 추출하고 테스트 16개를
   붙였다 (`648c313`). 가이드 §4 의 "커버되지 않은 코드를 리팩터링하기 **전에** 골든 테스트를
   쓴다" 순서와는 반대로 갔다는 점은 남겨 둔다 — 추출과 테스트를 한 커밋에서 했다.
2. [x] ~~**파일 목록 정렬·인덱스**~~ — 자연 정렬 + 전순서로 바꾸고 테스트 14개 (`54cc2c4`).
   #030~#032 의 인덱스 불일치는 정렬이 전순서가 아니었던 것이 구조적 원인이었다.
3. [x] ~~**합주 메시지 프로토콜**~~ — `CollaborationProtocol` 로 와이어 포맷을 단일화하고
   테스트 13개 (`0311a9e`). `turn_at` 누락(구버전) 하위호환도 테스트로 고정했다.
4. 🟡 **Room 마이그레이션** — 스키마 export 와 드리프트 게이트는 붙였고(`0087ca6`, `713af4e`),
   저장 왕복은 계측 테스트로 본다. 다만 **v2→v3 픽셀→퍼센티지 변환 자체를 검증하는
   마이그레이션 테스트는 아직 없다.** `MigrationTestHelper` 의 전제(과거 버전 스키마 JSON)는
   갖춰졌으므로 지금은 쓸 수 있다.
5. ~~**에뮬레이터 스모크 (API 21 / 30 / 34)**~~ — 2026-08-15 도입, **2026-09-06 실제 통과**.
   가이드 §1 OS 매트릭스의 대응물이다. 위 Base64/getColor 건이 정확히 이 매트릭스가
   잡았을 버그다. 다만 도입 후 3주간 세 매트릭스 전부 빨간불이었고 아무도 몰랐다 —
   실패 네 겹 중 셋이 CI 스크립트 자체 문제였다 (devlog [`043`](../devlog/20260906_043_instrumentation_ci_repair.md)).
   **게이트를 켠 것과 게이트가 도는 것은 다르다.**

### 🟡 갭 2: 의존성 재현성
Gradle dependency locking 도 버전 카탈로그도 없다. 가이드 §3 의 해시 락파일에 대응하는 것은
`dependencyLocking { lockAllConfigurations() }` + `gradle/verification-metadata.xml`.
의존성 수가 적어(Room, NanoHTTPD, coroutines) 우선순위는 낮다.

### 미도입 — 규모상 의도적 (가이드 effort 표의 "solo / internal tool" 열)
- **Dependabot** — 커밋-투-메인 방식에 노이즈가 크다
- **커버리지 게이트** — 테스트가 생긴 뒤에 논할 것
- **CodeQL** — Kotlin 을 지원하므로 나중에 추가 가치가 있으나, 지금은 테스트가 먼저

---

## 3. 릴리스 과정 — 2026-08-15 자동화 도입

### 흐름
```
v* 태그 푸시
  → verify   : SemVer 형식 · 태그 == versionName · CHANGELOG 섹션 존재
  → build    : build.yml 재사용 (개발 빌드와 동일 정의, 해당 태그의 코드로)
  → publish  : APK + SHA256SUMS.txt 첨부, 본문 = CHANGELOG 섹션 그대로
```

### 채택됨
- **CHANGELOG 가 릴리스 노트의 단일 출처** (`packaging-release.md` §7) — `CHANGELOG.md` 는
  이미 Keep-a-Changelog 형식으로 있었다. 이제 릴리스 본문이 여기서 **자동 추출**된다.
  별도 `RELEASE_NOTES.md` 는 두지 않는다 — 가이드의 실제 사례처럼 반드시 낡는다.
- **빈 릴리스 노트 방지** (§6) — CHANGELOG 에 해당 버전 섹션이 없으면 **빌드 전에** 실패한다.
- **SemVer + 사전 릴리스 자동 표시** (§5) — `-alpha` / `-beta` / `-rc` 는 pre-release 로 표시.
  pre-1.0 관례(MAJOR 는 0 유지, breaking change 는 MINOR 를 올림)를 따른다.
- **annotated 태그** (§6) — `git tag -a`. 태그는 삭제·재사용·amend 하지 않는다.
  이미 푸시된 태그로 릴리스를 다시 만들어야 하면 `workflow_dispatch` 로 태그를 지정한다
  (태그를 지웠다 다시 만들지 않기 위해 이 입력이 존재한다).
- **버전 불일치 차단** (§6) — `verify-version` 이 태그와 소스 버전이 다르면 릴리스를 막는다.

### 미도입 (다음 후보)
- **원커맨드 릴리스 스크립트** (§6) — `make release BUMP=patch` 에 해당. 더티 트리 거부,
  `versionCode`/`versionName` 갱신, CHANGELOG `[Unreleased]` 롤, 커밋, 태그, 푸시.
  지금은 수동이라 **`versionCode` 를 안 올리는 실수가 가능**하다 (워크플로우가 검증하지 않는 유일한 항목).
- **테스트 태그로 릴리스 워크플로우 검증** (§6) — 도입 직후 `v0.0.0-rc.1-test` 로 전 구간을
  돌려보고 삭제하는 절차. 최초 도입 시 1회 수행.
- **롤백 계획** (§6) — 문제 발견 시: 릴리스를 pre-release/draft 로 내리고, README 에 경고,
  hotfix 브랜치, 재릴리스.

### 해당 없음
3-OS 빌드 매트릭스 / 공증(notarization) / Gatekeeper 우회 안내 — 단일 플랫폼이라 무의미.

---

## 4. 다음에 할 것 (우선순위)

1. [x] ~~순수 로직 단위 테스트 + `./gradlew lint` 를 CI 게이트로~~ (2026-08-15)
2. [x] ~~렌더 좌표 계산을 순수 함수로 추출 + 테스트~~ (2026-08-15, `PageGeometry`)
3. [x] ~~에뮬레이터 스모크 (API 21 / 30 / 34)~~ (2026-09-06 초록불) — minSdk 21 이 처음으로
   런타임 검증됐고, minify 된 release APK 도 처음으로 실행됐다
4. **Room 마이그레이션 테스트** — 남은 "실제로 고장났던 영역" 중 유일하게 미착수. 전제(스키마
   export)는 이미 갖춰져 있다
5. **계측 테스트를 release 빌드로도** — 지금은 `connectedDebugAndroidTest` 라, R8 난독화가
   Room 의 enum 저장을 깨는 회귀는 "앱이 뜬다"까지만 확인된다
6. **빌드 메타데이터(커밋 SHA) 를 앱에 심고 About 에 표시** — APK 만 보고 어느 커밋인지 알 수 있게
7. **릴리스 스크립트** — `versionCode` 누락 방지
8. (선택) 정식 릴리스 키 등록 — 배포 계획이 생기면

---

*기준 문서는 `devdocs/guides/desktop/` 의 living document 다. 이 점검 결과도 재점검 시 갱신한다.*
