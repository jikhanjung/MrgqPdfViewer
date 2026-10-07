# 077 — 지휘자 메모: 두 겹 · 쓴 사람 · 권한 (P11 §6, v0.6.1-beta.1)

작성일: 2026-10-08
관련: [`P11`](20261007_P11_score_annotations_plan.md) §6, [`076`](20261007_076_score_notes_server_sync.md)(개인 메모 동기화), ScoreMateServer 0.19.0(conductor author, 서버 devlog 076 §9)
상태: 🟡 구현 · 단위 테스트(가짜 서버) 통과 — 실기기 · 두 계정 확인 전

---

## 1. 결정
- owner · leader 가 여럿이어도 **앙상블 악보마다 지휘자 문서 하나**, 항목마다 **서버가 쓴 사람(author)** 을 찍는다(사용자 2026-10-07: "대개 owner/leader = 지휘자, 복잡한 경우는 나중에"). 앱이 보낸 author 는 서버가 버린다
- 고치기 · 지우기는 owner · leader 모두 열어 둔다. 옮기거나 고치면 새 id 라 쓴 사람도 그 사람으로
- v0.6.0 정식은 이것 없이 먼저 냈다(사용자 "메모 작동하니 0.6.0") — 이 작업은 `feature/conductor-notes` 에서 이어 와 0.6.1-beta.1

## 2. 한 일
- 곁 파일 `<이름>.conductor.notes.json` (`ScoreNotesFile.conductorFileOf`). `sync.writable` = 이 사용자가 쓸 수 있나(서버 `conductor_writable`, 동기화마다 갱신). 쓸 수 있는데 파일이 없으면 빈 것을 만들어 뷰어가 쓰기 겹을 보이게
- 모델 `ScoreNote.author`(이름) — `{"author": {"id", "name"}}` 의 name 을 읽고, 쓸 때 그대로 둔다. `movedBy` · 글자 고치기는 author 를 비운다(새 메모)
- `ScoreNotesSync`: 악보마다 personal + (앙상블이면) conductor. conductor 는 쓸 수 있을 때만 올리고, 아니면 서버 것 그대로 받기
- **올리지 못한 지휘자 메모 보호**(반장 검토):
  - 곡목에서 빠져 PDF 가 없어질 때: conductor 는 서버 것이라 지우지만, **올리지 못한 변경이 있으면**(`hasUnsent` — 쓸 수 있고 id ≠ base, 또는 맞춘 적 없이 메모가 있음) 보관함 `.ScoreMateNotes/<id>.conductor.notes.json` 으로 → 다시 받으면 돌려 놓고 다음 동기화가 올린다
  - **권한을 잃음**(목록에서 빠짐 또는 PUT 403): 올리지 못한 판을 `.ScoreMateNotes/<id>.conductor-unsent-<시각>.notes.json` 에 복사해 두고, 곁 파일은 서버 것으로 되돌리고(같은 것을 다시 보관하지 않게) `writable` 을 거둔다. 알림 한 번. 다시 시도하지 않는다
  - 보관한 unsent 판을 되살리는 화면은 아직 없다 — 파일로만 남는다
- 뷰어: `NoteLayer` 둘(개인 · 지휘자) — 겹마다 메모 · 되돌리기 · 곁 파일. 편집은 고른 겹에만(`activeLayer`). 그리는 중 묶음은 겹을 바꾸면 앞 겹에 확정. 도구 줄 **👤 내 메모 / 🎼 지휘자** 는 쓸 수 있는 악보에서만. 지휘자 메모는 개인 메모 아래에 **연보라 빛 테두리**(같은 모양을 굵은 반투명 보라로 먼저 깔기). 잡은 지휘자 메모는 "○○ 님 메모" — 이름은 텍스트로만(사용자 입력값, 서버 당부)
- 테스트: `ScoreMateSyncTest` +5(멤버 받기만, 리더 올리기 · author, 권한 잃음 → 보관 · 서버 것으로 · 다시 보관 안 함, 403, 곡목에서 빠질 때 unsent 보관 · 돌려 놓기 · 올리기), `ScoreNotesTest` +2(author · writable 왕복, 옮기면 author 비움)

## 3. 확인할 것
CHANGELOG [0.6.1-beta.1] 의 실기기 목록 — 리더 → 멤버, 멤버 편집 막힘, 두 겹 구분, 오프라인 리더 메모
