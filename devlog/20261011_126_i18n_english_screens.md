# 126 — 영어 화면 (P18 1 ~ 9단계)

2026-10-11 · [P18](20261011_P18_i18n_language_setting_plan.md) · 0단계 [#125](20261011_125_i18n_step0_language_setting.md) 뒤 · 사용자: "P18 전체 다 진행해서 0.8.0 베타로 가자."

## 어떻게 나눴나
0단계(틀)를 먼저 v0.8.0-beta.1 로 낸 뒤, 1 ~ 9단계를 **맡은 파일이 겹치지 않게** 나눠 동시에 옮겼다(각자 워크트리 · 자기 리소스 파일 `strings_<영역>.xml`). 합친 뒤 CI 로 컴파일 · 테스트 · lint 를 보고, 영역 경계의 호출부(예외 · 요약 · 음성 결과)를 메인에서 이었다.

| 단계 | 영역 · 리소스 | 한국어 키 | 메모 |
|---|---|---|---|
| 1 | 설정 `strings_settings` | 239 | 칸 비교를 화면 글(`detailTitle == "웹서버"`)에서 카테고리 id(`detailCategory`)로. 옛 `activity_settings.xml`(안 쓰임) 지움 |
| 2 | 곡 목록 · 첫 화면 `strings_main` | 86 | `DeviceForm.noun` = TV · tablet · phone, 날짜는 앱 언어(`AppLanguage.locale`), 쪽 수 plurals |
| 3 · 4 | 악보 화면 · 메뉴 `strings_viewer` · `strings_viewer_menu` | 274 | `ViewerMenus` 는 글 대신 `Text`(키 + 인자) — TV 줄 "이름: 값"은 화면이 조립. `TimeSignature` 음표 이름 · `MusicXmlMatch` 이유 · `NoteStaff` 이름도 키 |
| 5 | ScoreMate · 업데이트 · 합주 안내 `strings_connect` · `strings_update` | 115 | `UiText`(update/) — `ScoreMateException` · `UpdateException` · 동기화 오류 · 합주 버전 안내가 키 + 인자. 기기 폴더 "내 악보" · 파일 이름은 그대로(바꾸면 파일이 옮겨짐) |
| 6 | CI 게이트 `tools/i18n_gate.py` | — | 코드 · 레이아웃의 한글 리터럴 + values-en 빠짐 → CI 실패. 바꾸면 안 되는 글은 `// i18n-ignore: 이유` |
| 7 | 웹 업로드 페이지 `server/WebText` · `UploadPage` | 17(로그) + 사전 32 | **브라우저 `Accept-Language`** 로 한국어 / 영어(앱 언어와 따로). 한국어 페이지는 같은 글(+ `lang="ko"`, "전체 삭제" 확인 창의 글자 그대로 보이던 `\n` 고침) |
| 8 | 음성 명령 영어 `voice/` · `strings_voice` | 76 | `EnglishCommandParser` — 영어 문장을 한국어 어순 조각으로 바꿔 **같은 규칙**(`CommandParser`)에 넣는다: measure · from bar · start · stop · tempo · next/previous/last page · 악기 파트 · full score · listen · letter · count-in, 영어 수 낱말. 설정 → 🎙 → **음성 명령 언어**(앱 언어 따름 · 한국어 · English), 인식 언어 `en-US`. 사람 이름 보표는 한국어만 |
| 9 | `docs/User_Manual.en.md` | — | 한국어판과 같은 구성. 한국어판 자체가 오래돼(TV 전용 시절) 어긋난 곳 목록은 아래 |

합계 한국어 키 약 **840개**(0단계 포함), 단위 테스트 502개(영어 음성 문장 표 · 웹 언어 고르기 · 메뉴 키 단언).

## 지킨 것
- 한국어 화면 글은 그대로(각 영역이 옮기기 전 리터럴과 리소스를 대조). 로그는 한국어 그대로
- 순수 JVM 코드(`ViewerMenus` · `update/` · `scoremate/` · `voice/` · `ensemble/`)는 Context 없이 키를 돌려주고 화면이 resolve
- 합주 메시지로 나가는 글("이름 (연주자)" 등)은 그대로 — 합주 버전 안 바뀜

## 남은 것 · 알려진 한계
- `MusicXmlScore` 의 이름 없는 파트 "파트 N", `ScoreParts` 의 "보표 N", 세트리스트 기본 이름 "세트리스트" 는 데이터 기본값이라 English 에서도 한국어로 보일 수 있다(드묾)
- 웹서버를 켠 채 언어를 바꾸면 웹서버 활동 로그는 다시 켤 때까지 옛 언어
- 휴대폰(한 칸) 설정은 언어를 바꾸면 목록으로 돌아간다(두 칸은 보던 칸으로)
- User_Manual(한국어 · 영어 모두)이 TV 전용 시절 내용 — 태블릿 · ScoreMate · 메트로놈 · 음성 등이 빠져 있다. 따로 고쳐 쓸 일
- 글 길이: TV 두 칸 · 하단 시트 · 도구 막대에서 영어가 잘리는지는 기기로 봐야 한다

## 확인
- [ ] TV · 태블릿 · 휴대폰 English: 설정 · 곡 목록 · 악보 화면 메뉴 · 메트로놈 · 연결 화면 · 업데이트 대화상자, 잘림
- [ ] 한국어로 되돌려 지금과 같은지
- [ ] 영어 음성 명령(태블릿): "measure 57" · "start from bar 12" · "tempo 80" · "next page" · "cello part" · "full score"
- [ ] 웹 업로드 페이지를 영어 브라우저로
