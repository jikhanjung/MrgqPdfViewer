# 136 — 반주 파트를 파트 보기와 따로 (v0.8.4-beta.1, P21 1단계)

2026-10-13 · 계획 [`P21`](20261013_P21_accompaniment_parts_part_listen_plan.md) · 사용자: "파트보를 보여주면서 그 파트를 제외한 반주를 해주는 게 default 인 것은 좋은데,
반주는 그와 별개로 전체를 다 들려줄 수도 있고 안 들려줄 수도 있고, 혹은 내것과 다른 파트의 일부만 반주를 해 줄 수도 있고"

## 전
반주(P07 6단계)는 파트 보기로 정해졌다 — 보고 있는 보표에 걸친 MusicXML 파트를 빼고 모두. 대화상자는 켜기(전역) · 악상기호 · 크기뿐.

## 한 것
- 반주 대화상자에 **반주 파트**: ◉ 자동 — 내 파트 빼고(처음 값 = 지금까지) · ○ 전체 — 내 파트도 · ○ 직접 고르기(파트마다 체크, 파트 보기에서 내 파트에는 "(내 파트)")
  - 직접 고르기를 누르면 지금 들리는 파트에서 시작, 체크를 바꿀 때마다 저장. 모두 빼면 "들려줄 파트를 고르지 않았습니다" · 그 곡은 반주 없음
  - 연주 중이면 바로 다시 만든다(`rebuild` — 켜기 · 악상기호와 같은 길). 파트가 많은 총보를 위해 대화상자 내용은 ScrollView
- **곡마다 저장** — `user_preferences.accompanimentParts`(DB v19): null 자동 · `-1` 전체(파트가 늘어도 전체) · 그 밖 MusicXML 파트 순번 마스크.
  동기화로 파트가 줄어 마스크에 없는 파트가 있으면 자동으로 본다(값은 그대로 두고 다음에 고를 때 덮어씀)
- **끄기는 "반주 켜기"(전역) 그대로** — 계획 초안은 파트 선택지에 넣으려 했지만, 반주를 안 쓰는 사람이 곡마다 끌 필요가 없고 저장값을 옮길 일도 없다
- 파일을 열 때 `loadMusicXml` 이 MusicXML 과 함께 이 값을 읽어 둔다(`accompanimentChoice`)

## 코드
- `metronome/AccompanimentParts`(순수: `mode` · `resolve` · `toMask`) — 테스트 `AccompanimentPartsTest` 5개(단위 테스트 516개)
- `PdfViewerActivity`: `accompanimentParts(score)` 를 `accompanimentFor` · 대화상자가 함께 쓴다, `showAccompanimentDialog` 에 반주 파트 줄
- DB: `MIGRATION_18_19`(`ALTER TABLE … ADD COLUMN accompanimentParts INTEGER`), DAO `updateAccompanimentParts`, `MusicRepository.setAccompanimentPartsForFile`,
  계측 테스트 `v18_에서_v19_는_반주_파트_칸을_자동으로_더하고_파트_선택을_보존한다`
- `app/schemas/…/19.json` 은 18.json 에 칸을 더해 손으로 — Room(kapt)은 스키마가 같으면 파일을 다시 쓰지 않아 CI 드리프트 검사를 통과한다
  (identityHash 는 18 의 값 그대로 남는다. 앱은 생성된 `_Impl` 의 해시를 쓰고 마이그레이션 테스트는 표 구조만 보므로 영향 없음)
- 설명서 6.4 · 연주 메뉴 표(한 · 영), `docs/Viewer_Menus.md`

## 확인
- [ ] 태블릿: 파트 보기에서 자동 · 전체 · 직접 고르기, 전체 악보에서 직접 고르기, 연주 중 바꾸기, 곡을 다시 열어도 기억
