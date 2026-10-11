# 125 — 언어 설정 틀 (P18 0단계)

2026-10-11 · [P18](20261011_P18_i18n_language_setting_plan.md) 0단계 · 사용자: "P18 전체 다 진행해서 0.8.0 베타로 가자."

## 바꾼 것
- **설정 → 앱 정보 → 언어 (Language)**(맨 위 묶음): 시스템 따름(기본) · 한국어 · English. 제목에 두 언어 — 못 읽는 언어로 바뀌어도 찾게. 시스템 따름이면 설명에 지금 언어("시스템 따름 — 한국어")
- AppCompat 앱별 언어(`AppCompatDelegate.setApplicationLocales`) — `utils/AppLanguage`. 고르면 액티비티가 다시 만들어진다(재시작 없음). 두 칸 설정은 보던 칸으로 돌아온다(`onSaveInstanceState`)
- 매니페스트: `AppLocalesMetadataHolderService` + `autoStoreLocales`(Android 12 이하 TV 도 고른 언어를 기억), `localeConfig` = `res/xml/locales_config.xml`(ko · en — Android 13+ 시스템 설정 → 앱 → 언어에도)
- `values-en/strings.xml`: 지금 리소스 39줄 + 언어 줄 번역. `app_name` · `page_info` 는 `translatable="false"`
- lint `MissingTranslation` = **오류** — 한국어 줄이 영어에 없으면 CI 실패
- 용어집 `docs/Glossary_ko_en.md`(번역 기준)

## 이 판에서 보이는 것
- English 를 골라도 **화면 대부분은 아직 한국어** — 리소스로 옮긴 줄(언어 줄 · 몇몇 안내)만 영어. 1 ~ 5단계에서 화면별로 옮긴다

## 확인
- [ ] 태블릿 · TV: 언어 English → 설정이 다시 그려지고 앱 정보 칸에 머문다, 앱을 껐다 켜도 English(TV Android 11 · 14, 태블릿 13)
- [ ] 시스템 따름으로 되돌리면 한국어
