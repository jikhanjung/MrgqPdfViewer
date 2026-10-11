# P18 — 언어 설정 · 영어(다국어) 화면

2026-10-11 · 사용자: "따로 계획을 하나 세워줘. 언어 설정을 넣어서 영어(혹은 다국어)도 가능하게. 전체를 손대야 할테니 좀 큰 계획이 될 것 같은데."
상태: 📝 **계획 — 정함**(2026-10-11, §9 모두 추천대로 — 사용자: "P18 추천대로 정하고 commit/push 해줘"). 다음: 0단계(틀)
관련: [`P17`](20261011_P17_touch_ui_shell_plan.md)(터치 껍질 — 메뉴 내용 `ViewerMenus` 가 글을 한 곳에 모았다), [`P12`](20261009_P12_voice_commands_plan.md)(음성 명령 — 한국어 문법),
[`P14`](20261010_P14_ios_app_plan.md)(iOS — 공용 문자열을 어디에 둘지와 이어진다), [`P15`](20261010_P15_private_repo_releases_on_scoremate_plan.md)(업데이트 notes 가 서버에서 온다)

---

## 0. 한 줄

설정에 **언어**(시스템 따름 · 한국어 · English)를 넣고, 화면의 글을 모두 리소스(`strings.xml`)로 옮겨 **영어 번역**을 단다. 한국어 화면은 한 글자도 바뀌지 않게, **화면 단위로 작게 나눠** beta 로 낸다.
음성 명령의 영어는 문법이 따로라 **별도 단계 · 좁힌 범위**로.

## 1. 왜

- 외국 연주자 · 합주 손님이 쓰기 어렵다 — 지금 앱의 글은 거의 전부 Kotlin 코드 안 한국어다
- 한국어 기기가 아니어도 같은 APK 를 쓴다(사이드로드 · ScoreMate 다운로드 페이지)
- iOS(P14) 로 가기 전에 "글 = 리소스" 를 정리해 두면 공용 문자열 표를 그대로 옮길 수 있다

## 2. 지금 규모 (2026-10-11 측정)

| 대상 | 수 | 메모 |
|---|---|---|
| Kotlin 안 한글 문자열 리터럴 | **1,403개 · 56파일** | 주석 뺌. 이 중 **로그 197** · **음성(`voice/`) 336** · **나머지(화면 글) 약 870** |
| 화면 글이 많은 파일 | `PdfViewerActivity` 391 · `SettingsActivity` 282 · `MainActivity` 100 · `menu/ViewerMenus` 40 · `update/UpdateController` 37 · `scoremate/ScoreMateSync` 23 · `ScoreMateClient` 20 · `server/WebServerManager` 19(+ HTML) · `PageCache` 17 · `scoremate/DeviceLinkDialog` 15 | 위 10개가 화면 글의 약 85% |
| `voice/` | `VoiceLexicon` 239 · `KoreanNumbers` 27 · `VoiceListener` 24 · `VoiceCommand` 19 · `VoiceCommandRunner` 16 · `PartNames` 5 | 대부분 **인식 어휘**(번역 대상 아님 — 문법) |
| `res/values/strings.xml` | **41개**(한글 39) | `values-en` 등 다른 언어 폴더 없음 |
| 레이아웃 XML 하드코딩 글(`text` · `hint` · `contentDescription`) | **52개 · 8파일 / 13** | `activity_settings.xml` 19 · `activity_main.xml` 10 · `activity_settings_new.xml` 9 · `activity_pdf_viewer.xml` 7 |
| `Toast` · `setTitle("…")` · `setMessage("…")` 호출 | 181곳 | 대화상자 · 토스트가 화면 글의 큰 몫 |
| 한글을 단언하는 단위 테스트 | **33파일 / 37** | 단언 줄이 많은 곳: `VoiceRecordingsTest` 53 · `ScoreMateSyncTest` 36 · `PartNamesTest` 36 · `VoiceCommandTest` 22 · `VoiceCommandRunnerTest` 14 · `ViewerMenusTest` 13 |
| 날짜 · 크기 형식 | 11곳 | `Locale.KOREA` 고정 1곳(녹음 기록), `Locale.US` 숫자 형식 몇 곳 |
| 웹 업로드 서버 HTML | `WebServerManager` 한 파일, 한글 56줄 | 브라우저 쪽 언어(`Accept-Language`)를 따를지 따로 |

lint: `HardcodedText` · `MissingTranslation` 등은 지금 **켜져는 있지만 걸리지 않는다**(코드 안 글은 lint 가 못 본다, 레이아웃 52개는 경고 수준). `disable` 은 `ExpiredTargetSdkVersion` 하나.

## 3. 원칙

- **한국어 화면은 그대로** — 옮기는 단계마다 한국어 글이 바이트 단위로 같아야 한다(테스트가 지킨다, §6)
- **로그는 옮기지 않는다** — `Log.*` 197개는 한국어 그대로(개발용, 기기 밖으로 안 나감)
- **사람이 읽는 글만 리소스로** — 합주 메시지 · 프로토콜 필드 · 파일 이름 · prefs 키는 건드리지 않는다. 합주 메시지에는 사람이 읽는 글이 없다(확인: `addProperty` 에 한글 값 없음) → 합주 버전(`EnsembleVersion.CURRENT`) 그대로
- **기본(`values/`) = 한국어**, 영어는 `values-en/` — 기본이 한국어여야 번역 빠진 줄이 한국어로 보인다(빈칸 · 키 이름 대신)
- 이모지(🎤 🎙 ✏️ ▶ 👁 ☁️)는 글 밖에 둔다 — 두 언어가 같은 아이콘
- 화면 단위로 나눠 **기능 변화 없는 beta** 로 — 한 번에 1,000줄을 옮기지 않는다

## 4. 방식

### 4.1 언어 고르기
- Android 13 의 **앱별 언어**(per-app language)를 AppCompat 1.6 백포트로 쓴다: `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))`, 빈 목록 = 시스템 따름
- minSdk 30(TV 11 · 태블릿 13 · TV 14 섞임) → 매니페스트 `AppLocalesMetadataHolderService` 에 **`autoStoreLocales=true`** — Android 12 이하에서도 AppCompat 이 고른 언어를 저장 · 복원한다. Android 13+ 는 시스템 설정 → 앱 → 언어에도 보인다(`res/xml/locales_config.xml`: `ko` · `en`)
- AppCompat 은 이미 1.6.1, 액티비티 4개 모두 `AppCompatActivity` — 바꿀 것 없음
- 바꾸면 **떠 있는 액티비티가 다시 만들어진다**(재시작 없음). 설정 화면에서만 바꾸므로 악보 화면(`configChanges` 가 큼, #044)은 그때 떠 있지 않다 — 그래도 `locale|layoutDirection` 을 `configChanges` 에 **넣지 않는다**(넣으면 다시 그리지 않아 글이 안 바뀐다)
- 설정 → 앱 정보(또는 "표시") 에 **언어** 줄: 시스템 따름(기본) · 한국어 · English

### 4.2 글 옮기기
- 코드의 `"…"` → `getString(R.string.…)` / `getQuantityString`(개수 — "곡 3개" · "3 songs"), 끼워 넣기는 `%1$s` · `%1$d`
- **조사**: 한국어 글은 지금처럼 문장째 리소스로(조사를 코드에서 붙이지 않는다). 받침에 따라 바뀌는 조사(`을/를` 등)를 코드에서 고르는 곳이 있으면 그 문장만 따로 키
- 이름 붙이기: `화면_무엇` (`settings_update_check_title`, `viewer_menu_play_start`) — 화면별 파일 `strings_settings.xml` · `strings_viewer.xml` … 로 나눠 충돌 · 리뷰를 줄인다
- **JVM 순수 코드**(`menu/ViewerMenus` · `update/` 의 메시지 등)는 Android `Context` 가 없다 → 글 대신 **키(enum · `@StringRes Int`) + 인자**를 돌려주고 화면이 `getString` 한다. `ViewerMenus` 는 P17 0단계에서 내용 · 그리기를 나눴으니 이 일이 쉽다
- 날짜 · 크기: `Locale.getDefault()` 대신 **앱 언어**(`AppCompatDelegate.getApplicationLocales()` → 없으면 기기)로. `Locale.KOREA` 고정 1곳 정리. `%.1f MB` 같은 숫자는 `Locale.US` 그대로(소수점 쉼표 막기)

### 4.3 서버 · 밖에서 오는 글
| 글 | 지금 | 이 계획에서 |
|---|---|---|
| ScoreMate 곡 제목 · 파트 · 작곡가 | 서버 그대로 | **번역 안 함**(사용자 데이터) |
| 업데이트 변경 내용(`notes` = CHANGELOG 섹션, 한국어) | 서버 그대로 | 앱은 그대로 보인다. 영어 notes 가 필요하면 **서버 요청**(§8) |
| 서버 오류 메시지 | 앱이 자기 글로 바꿔 보임 | 앱 글이므로 옮긴다 |
| 웹 업로드 페이지(HTML) | 한국어 고정 | 브라우저 `Accept-Language` 로 고르기 — 마지막 단계(웹서버는 연결 안 한 TV 만 쓴다) |
| 음성 인식 안내 · 오류 | 한국어 | 옮긴다(음성 명령 자체가 한국어인 동안은 "한국어로 말하세요" 안내 포함) |

## 5. 단계 — 판 번호는 미리 잡지 않는다(P15 교훈), 단계마다 그때의 다음 beta

| 단계 | 내용 | 대략 분량 | 확인 |
|---|---|---|---|
| 0 | 틀: `locales_config.xml` · `autoStoreLocales` · 설정 "언어" 줄(시스템 따름 · 한국어 · English), `values-en/strings.xml` 에 지금 41개 번역, 앱 언어를 읽는 도움 함수(날짜 형식용), lint 에 `MissingTranslation` 을 오류로 | 작음 | 한국어 화면 그대로, English 를 고르면 그 41개만 영어 · 나머지 한국어 |
| 1 | **설정 화면**(`SettingsActivity` 282 · 레이아웃 28) | 큼 — 하루 | 두 칸 · 한 칸 모두 English, 스위치 · 대화상자 |
| 2 | **곡 목록 · 첫 화면**(`MainActivity` 100 · `PdfFileAdapter` · `activity_main` · 스플래시) + 날짜 · 크기 형식 | 중간 | 세트리스트 · 빈 화면 안내 · 동기화 토스트 |
| 3 | **악보 화면 메뉴**(`ViewerMenus` 40 → 키 + 인자, 테스트를 키 단언으로) · 도구 막대 · 하단 시트 | 중간 | TV 목록 · 터치 시트 모두 |
| 4 | **악보 화면 나머지**(`PdfViewerActivity` 391 — 메트로놈 대화상자 · 반주 · 파트 보기 · 메모 · 🎤 · 합주 안내) — 4a 메트로놈 · 반주 / 4b 파트 · 메모 / 4c 합주 · 🎤 로 세 번 나눔 | 가장 큼 — 이틀 | 화면마다 English |
| 5 | **ScoreMate · 업데이트 · 그 밖**(`scoremate/` 68 · `update/` 52 · `DeviceLinkDialog` · `PageCache` · `ensemble` 안내 · 알림) | 중간 | 연결 화면 · 업데이트 대화상자("받는 곳") |
| 6 | **lint 게이트**: `HardcodedText` 를 오류로 + 코드 안 한글 화면 글을 찾는 작은 검사(CI 스크립트 — `Log` · `voice/` 어휘 · 테스트는 빼고) | 작음 | 새 글이 리소스 밖에 생기면 CI 실패 |
| 7 | **웹 업로드 페이지**(`Accept-Language`) | 작음 | 브라우저 영어 |
| 8 | **음성 명령 영어**(따로 — §7) | 큼 | 영어 문장 표 |
| 9 | **docs · 정식** — `docs/User_Manual.md` 영어판(`User_Manual.en.md`), 정식 판 | 중간 | — |

0 ~ 6 이 "화면 다국어" 의 끝(정식 판 하나), 7 · 8 · 9 는 그 뒤 따로 정한다. 1 ~ 5 는 순서를 바꿔도 되지만 **1(설정)을 먼저** — 언어를 고르는 곳이 영어로 보여야 시험하기 쉽다.

## 6. 테스트

- **한국어 그대로**: 옮기기 전 · 후 같은 글인지 — 화면 글을 단언하는 지금의 단위 테스트(33파일)는 **한국어 기대값 그대로 두고**, Robolectric 없이 돌도록 순수 코드는 "키 + 인자" 를 단언(`ViewerMenusTest` → `R.string.…` 키 · 인자). 한국어 글 자체는 리소스 파일에 있으니 리소스 대조 테스트 하나(키마다 `values/` 값이 옮기기 전 글과 같은지 — 옮기는 단계에서만 쓰는 한 번짜리 스크립트로 충분)
- **번역 빠짐**: `MissingTranslation` 오류(0단계부터), 끼워 넣기 자리(`%1$s`) 수가 두 언어에서 같은지 lint `StringFormatMatches`
- **계측**(에뮬레이터 API 30 · 34): 로케일을 `en` 으로 바꾸고 설정 · 목록이 뜨는지 스모크 하나(글 길이로 잘림은 사람 눈으로)
- **음성 테스트**(`VoiceRecordingsTest` 등)는 한국어 문법 그대로 — 영어 문법은 8단계에서 따로 표

## 7. 음성 명령 영어 — 별도 · 좁게

- 지금 문법은 **한국어에 깊이 묶여 있다**: 정규화(조사 · 띄어쓰기 · 받아 적기 흔들림), 한자어 · 고유어 숫자(`KoreanNumbers`), 사람 이름 보표(`PartNames` — 성 뺀 이름 · 모음 하나 차이), "부터 · 시작 · 꺼 줘" 같은 끝말, 대화 막기(#117)
- 그래서 영어는 **같은 명령 층(`ViewerCommands`)에 새 파서 하나**: `EnglishCommandParser` — 인식 언어 `en-US`, 숫자는 인식기가 숫자로 준다
- **범위를 좁힌다(추천)**: 마디(`measure 57` · `from bar 57`) · 시작 · 정지 · 템포(`tempo 72`) · 쪽(`next page` · `page 3`) · 파트(악기 이름 · `full score`) · 듣기(`listen`). 사람 이름 보표 · 대화 막기 · 레터(`letter B` 는 쉬우니 넣어도 됨)는 나중
- 앱 언어와 음성 언어는 **따로 둔다**(한국어 화면 + 영어로 말하기도 있음) — 설정 "음성 명령 언어: 앱 언어 따름(기본) · 한국어 · English"
- `docs/Voice_Commands.md` 영어 표는 이 단계에서

## 8. 용어집 (번역 기준 — 0단계에 `docs/Glossary_ko_en.md` 로)

| 한국어 | English | 메모 |
|---|---|---|
| 마디 | measure | 화면은 measure, 음성은 bar 도 받는다 |
| 보표 | staff | 여럿 staves |
| 총보 | full score | |
| 파트보 · 파트 보기 | part · part view | |
| 예비박 | count-in | "예비박 두 마디" = 2-bar count-in |
| 박자 · 박자표 | time signature | 박 = beat |
| 빠르기 · 템포 | tempo | 구간별 빠르기 = tempo sections |
| 셈여림 · 악상기호 | dynamics · expression marks | |
| 반주 연습 | accompaniment | |
| 합주 · 지휘자 · 연주자 | ensemble · conductor · performer | |
| 세트리스트 · 곡목 | setlist | |
| 연주 듣고 넘기기 | listen & turn pages | |
| 메모 | annotations | 지휘자 메모 = conductor annotations |
| 넘김 · 쪽 | page turn · page | |
| 사전 릴리스 | pre-release (beta) | |

번역은 내가 초안 → 사용자가 영어 화면을 훑어 고친다(합주 손님 중 영어권이 있으면 그분께도).

## 9. 정할 것 — **2026-10-11 모두 추천대로**

| # | 질문 | 추천 |
|---|---|---|
| 1 | 언어 목록 | **시스템 따름 · 한국어 · English** 셋. 다른 언어는 틀만(번역 파일을 더하면 됨) |
| 2 | 기본값 | **시스템 따름** — 한국어 기기는 지금 그대로, 영어 기기는 English |
| 3 | 리소스 기본(`values/`) 언어 | **한국어** — 번역이 빠져도 한국어가 보인다(사용자 대부분 한국어) |
| 4 | 음성 명령 영어 | **화면 다국어 끝난 뒤 따로, 좁힌 범위**(§7) |
| 5 | 웹 업로드 페이지 | **7단계로 미룸**(연결한 TV 는 웹서버를 쓰지 않는다) |
| 6 | docs 영어판 | **User_Manual 만**(9단계). devlog · HANDOFF · CLAUDE.md 는 한국어 그대로 |
| 7 | CHANGELOG · 업데이트 notes | **한국어 그대로** — 영어 기기에도 한국어 notes. 영어 notes 가 꼭 필요해지면 서버에 `notes_en`(또는 `?lang=en`) 요청 + CI 가 CHANGELOG 영어 섹션을 따로 — 지금은 안 함 |
| 8 | 화면 글 lint 게이트 | **6단계에서 켠다**(그 전엔 옮기는 중이라 실패만 쌓인다) |
| 9 | 판 묶기 | 0 ~ 6 을 beta 여럿 → **정식 하나**("영어 화면") |

## 10. 위험

- **한국어 글이 미묘하게 바뀜** — 옮기며 띄어쓰기 · 가운뎃점(` · `) 하나 틀리기 쉽다 → 옮기는 단계마다 리소스 대조(§6), 커밋을 화면 단위로 작게
- **글 길이** — 영어가 길어 TV 두 칸 오른쪽 · 하단 시트 · 도구 막대에서 잘림. 단계마다 TV · 태블릿 · 휴대폰 English 로 한 번씩 본다
- **조사 · 복수** — 문장째 옮기면 대부분 해결, 개수 글은 `plurals`
- **순수 코드에 Context 끌어들이기** — `ViewerMenus` · `update/` · `voice/` 는 JVM 테스트 대상. 글 대신 키를 돌려주는 방식을 지킨다
- **액티비티 재생성** — 언어를 바꾸면 설정 화면이 다시 만들어진다(두 칸의 지금 카테고리 · 웹서버 상태를 `savedInstanceState` 로 지킬 것)
- **iOS(P14)** — 공용 문자열을 KMP 로 옮길 때 다시 손댈 수 있다. 키 이름을 화면_무엇으로 두면 그대로 옮겨진다
- 분량: 화면 글 약 870 + 레이아웃 52 + strings 41 ≈ **960개**. 번역 초안은 빠르지만 사용자 확인(화면을 English 로 훑기)이 단계마다 필요
