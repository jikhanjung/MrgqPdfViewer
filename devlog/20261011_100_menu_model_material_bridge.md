# 100 — 메뉴 내용 모델 · Material 다리 테마 (P17 0단계)

2026-10-11 · [P17](20261011_P17_touch_ui_shell_plan.md) 0단계 · 사용자: "0단계 진행해줘."

화면은 바뀌지 않는다 — 터치 껍질(1단계 하단 시트 · 2단계 도구 막대)을 얹을 바닥만 깐다.

## 바꾼 것
- **메뉴 내용 = `menu/ViewerMenus`** (순수 Kotlin)
  - 연주(`play`) · 합주 연주자(`performer`) · 보기(`view`) 메뉴의 줄 · 순서 · 제목 · 보일 조건을 악보 화면에서 뗐다
  - 줄은 `MenuItem(action, label, value, checked)`: TV 는 "이름: 값"(`text`)으로 지금 글 그대로, `checked` 가 있는 줄(마디 박스 · 메모 보이기 · 연주자 소리)은 터치에서 스위치가 된다
  - 누르면 무엇을 할지는 `MenuAction` 으로만 — 실제 동작은 악보 화면의 `when`(예전 람다와 같은 함수)
  - 악보 화면은 지금 상태를 `PlayState` · `PerformerState` · `ViewState` 로 넘기고, `showViewerMenu` 가 TV 목록 대화상자로 그린다(제목 · 줄 · "닫기" · 닫힘 처리 — 일시정지 중 고르지 않고 닫으면 정지, #053 그대로)
  - 반주 줄을 만들던 `accompanimentMenuLabel` 은 모델로 옮겨 지움
- **테마 부모 `Theme.AppCompat.NoActionBar` → `Theme.MaterialComponents.NoActionBar.Bridge`**
  - 다리 테마는 Material 속성(색 역할 · 모양 · 글자 모양 · 컴포넌트 스타일)만 더한다. 위젯을 Material 위젯으로 바꾸는 inflater(`viewInflaterClass`)는 넣지 않으므로 지금 단추 · 대화상자 모습은 그대로 — material 1.11.0 리소스에서 확인
  - 이것이 있어야 1단계의 `BottomSheetDialog` · `MaterialSwitch` · 칩이 제대로 그려진다
- `docs/Viewer_Menus.md` 에 "내용은 `ViewerMenus` 한 곳" 한 줄

## 테스트
- `ViewerMenusTest` 9개: TV 목록 글 · 순서를 `docs/Viewer_Menus.md` 대로 고정(멈춤 · 마디 고르는 중 · 일시정지 + 반주 · 도는 중 · 듣는 중, 연주자 셋, 보기 TV 가로 · 두 쪽 · 지휘자 · 태블릿 · 휴대폰), 연주 메뉴와 보기 메뉴에 같은 동작이 없음(P13 "한 항목은 한 메뉴에만")

## 확인
- [ ] CI 빌드 · 단위 테스트 · 계측
- 실기기 — 화면이 **그대로**인지만:
  - [ ] TV: ↑ 연주 메뉴(멈춤 · 일시정지 → 고르지 않고 닫으면 정지), OK 길게 보기 메뉴(닫기 단추), 대화상자 · 설정 화면 색 · 모양
  - [ ] 태블릿: 두 번 탭 연주 메뉴(🎤 줄), 마디 고르는 중 두 번 탭, 길게 보기 메뉴(✏️ 메모)
  - [ ] 합주 연주자: 연주 메뉴 · 소리 켬 ↔ 끔
