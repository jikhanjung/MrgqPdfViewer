# 101 — 악보 화면 메뉴를 하단 시트로 (P17 1단계)

2026-10-11 · [P17](20261011_P17_touch_ui_shell_plan.md) 1단계 · 사용자: "계속 진행해줘"

## 바꾼 것
- **태블릿 · 휴대폰(`DeviceForm` 이 TV 가 아님)은 연주 · 합주 연주자 · 보기 메뉴를 `BottomSheetDialog` 로** — `showViewerMenu` 가 기기로 고른다(`sheetMenus()`), TV 는 0단계 그대로 목록 대화상자
- 내용은 0단계의 `ViewerMenus` 그대로. 그리기만 `showMenuSheet`:
  - `sheet_viewer_menu.xml`(손잡이 · 제목 · 스크롤되는 줄 목록) + 줄마다 `item_menu_sheet_row.xml`(이름 · 값 · 스위치, 최소 높이 56dp)
  - **`checked` 가 있는 줄 = 스위치**(`SwitchMaterial`): 누르면 동작한 뒤 시트를 연 채 내용을 다시 만든다 — 메뉴를 함수(`build`)로 넘기도록 바꾼 까닭. 스위치가 상태를 보이므로 그 줄의 토스트는 끈다(`toggleScoreOverlay` · `toggleNotesVisible` · `setPerformerSound` 에 `announce`)
  - **값이 있는 줄**은 오른쪽에 값(파트 이름 · 두 쪽 · 회전 · 반주)
  - 다른 줄은 목록 대화상자와 같은 순서 — 고른 동작을 먼저 부르고 닫는다(다음 대화상자가 뜬 뒤 시트가 닫힌다). 그래서 연주 메뉴의 "고르지 않고 닫으면 정지"(`chosen`)와 메뉴를 모두 닫은 순간 판단(`onWindowFocusChanged`, #053)이 그대로다
  - 처음부터 다 펼침(`STATE_EXPANDED` · `skipCollapsed`), 태블릿은 폭 600dp(`MENU_SHEET_MAX_WIDTH_DP`)로 가운데, 휴대폰 가로는 시트 안에서 스크롤(`NestedScrollView`)
  - "닫기" 단추는 없다 — 아래로 밀기 · 바깥 탭 · 뒤로
- 테마 `ThemeOverlay.MrgqPdfViewer.BottomSheet`(부모 `Theme.MaterialComponents.BottomSheetDialog` — 어두운 면 `tv_surface`, 스위치 · 강조는 `tv_primary`, 위 모서리 16dp). 0단계의 다리 테마 위라 이 위젯들이 제대로 그려진다
- `docs/Viewer_Menus.md` "여는 법" 아래에 모양 설명

## 확인
- 내용 · 순서는 0단계 `ViewerMenusTest` 가 고정 — 새 단위 테스트 없음(그리기)
- [ ] CI 빌드 · lint · 계측
- 실기기:
  - [ ] 태블릿 · 휴대폰: 두 번 탭 → 연주 시트, 길게 → 보기 시트, 스위치(마디 박스가 뒤에서 바로 바뀌는지)
  - [ ] 악보 연동 중 두 번 탭 → 일시정지 시트 → 바깥 탭 = 정지, "이어서" = 이어서
  - [ ] 휴대폰 가로에서 긴 보기 시트 스크롤
  - [ ] TV: 전처럼 목록 대화상자
