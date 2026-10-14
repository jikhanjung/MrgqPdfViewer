# 140 — 두 대로 펼쳐 보기 다음: 악보 받기 · 자르기 맞추기 · 짝 기기에서 연주 시작 · 휴대폰 세로 (P22, v0.9.0-beta.3)

2026-10-14 · 계획 [`P22`](20261014_P22_paired_spread_plan.md) · 앞 [#139](20261014_139_paired_spread_play_ensemble.md)
사용자: (첫 판에서 뺀 것 넷을) "이거 차례대로 진행해줘. 휴대폰을 가로로 두는 건 짝 맺은 상태에서는 일단 배제하자."
— 실기기 확인 전. 짝 판은 3 그대로(모두 선택 필드 · 새 동작, 옛 판은 무시).

## ① 짝 기기에 없는 악보 — 주 기기에서 받기

- 주 기기가 짝을 기다리는 동안 `FileServerManager`(합주 파일 서버와 같은 클래스) 를 **포트 9095** 로 띄우고, 펼침을 보낼 때마다
  **지금 악보 하나만** 내놓는다(`PairSession.offerFile` — 원본 PDF. 파트 PDF 는 기기마다 만든다, P07 §0)
- 짝 기기: `spread.sha256` 의 악보를 `EnsembleFiles` 로 못 찾으면 합주 연주자의 받기 대화상자를 그대로 — 주소만 `http://<주 기기>:9095`
  (`PairSession.fileServerUrl`, 짝 기기가 붙은 주소 — 다시 찾기로 바뀌면 따라감). 악보 화면 · 곡 목록 모두. 넘길 때마다 다시 묻지 않게 한 곡(sha256)에 한 번

## ② 위 · 아래 자르기 맞추기

- `spread` 에 `clip_top` · `clip_bottom`(%) — 짝 기기는 **저장하지 않고** 그 값으로 그린다. 처음 바꿀 때 제 값을 기억했다가 짝이 끝나면 되돌리고,
  곡이 바뀌면 그 곡의 제 값은 DB 에서 다시 읽는다
- 주 기기가 자르기 대화상자에서 움직이거나(미리 보기) · 적용 · 취소하면 같은 펼침이라도 다시 보낸다(`pairResendSpread`)
- 짝 기기의 자르기 메뉴는 안내("짝을 기다린 기기에서")

## ③ 짝 기기에서 연주 · 듣기 시작

- 짝 기기의 ▶ = 짝 기기 연주 메뉴(`ViewerMenus.pairPartnerPlay`) — 주 기기 상태(`focus.playing`: metronome · mic · selecting)에 따라
  "▶ 시작 · 🎤 듣기 — 이 펼침 첫 마디부터" 또는 정지. 짝 기기의 🎤(`toggleMicFollow`)도 listen · stop 을 보낸다
- 주 기기는 `input` start · listen · stop 을 **음성 명령과 같은 길**(`viewerCommands.start()` · `listen(HERE)` · `stop()`)로 — 시작 마디는 합친 펼침의 앞 쪽 첫 마디
  (#139 의 `firstShownMeasure`), 주 기기에서 마디를 고르는 중이면 고른 마디. 못 하면 이유를 주 기기에 띄우고 `notice` 로 짝 기기에도
- 마디를 골라 시작하는 것은 주 기기에서만 — 짝 기기 화면의 마디 탭은 그대로 쓰지 않는다

## ④ 휴대폰은 짝 동안 세로만

`phoneOrientationRequest` 가 짝이 있으면(`PairSession.isActive`) 세로 — 짝을 맺는 순간 · 화면에 돌아올 때 · 짝이 끝나면 제 회전 모드로. 회전 메뉴는 안내.

## 시험

`PairTest` 메시지 왕복에 clip · start/listen/stop · playing · notice, `ViewerMenusTest` 에 짝 기기 연주 메뉴. 단위 테스트 527개.
