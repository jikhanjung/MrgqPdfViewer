# 104 — 곡 목록 당겨서 동기화 (P17 4단계)

2026-10-11 · [P17](20261011_P17_touch_ui_shell_plan.md) 4단계 · 사용자: "계속 진행해줘"

## 바꾼 것
- `activity_main.xml`: 곡 목록(`recyclerView`)과 빈 화면 안내(`emptyView`)를 `SwipeRefreshLayout`(`listRefresh`) 안의 `FrameLayout` 으로 — 둘이 같은 자리(controlStrip 아래 ~ 바닥)에 겹쳐 있던 것을 그대로 옮겼다. 의존성 `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`
- **당기면 `runScoreMateSync(quiet = false)`** — 🔄 버튼과 같은 길(이미 동기화 중이면 그렇다고 알림, 합주 중이면 안 함). 끝나면 돌던 표시를 거둔다
- 켜는 조건: ScoreMate 연결 · TV 가 아님(`showLibrary` 에서 매번). 연결 안 된 서재는 동기화할 것이 없다
- 당겨지는 때: 목록이 맨 위일 때만 — 자식이 FrameLayout 이라 `setOnChildScrollUpCallback` 으로 RecyclerView 의 위 스크롤 여부를 본다. 빈 화면(목록이 GONE)이면 늘 당겨진다 — "이 세트리스트의 악보를 아직 받지 않았습니다"에서 바로 당기면 된다
- 색: 돌기 `tv_primary`, 바탕 `tv_surface`
- **세트리스트 탭은 칩으로 바꾸지 않았다** — 이미 탭으로 고른다(`setOnClickListener`, 포커스로 바꾸는 것은 리모컨 키 뒤 500ms 안의 이동만). 모양만 바꾸는 일이라 P17 §7 에서 뺐다
- 머리줄 단추를 아이콘으로 바꾸는 것도 이번엔 안 함

## 확인
- [ ] CI
- 실기기(연결된 태블릿 · 휴대폰):
  - [ ] 목록 맨 위에서 아래로 당기기 → 돌기 → "ScoreMate: …" 알림 → 돌기 사라짐
  - [ ] 목록을 내린 상태에서 위로 올리기는 스크롤만(동기화 아님)
  - [ ] 빈 세트리스트 화면에서 당기기
  - [ ] TV: 달라진 것 없음, 리모컨으로 목록 · 🔄 그대로
