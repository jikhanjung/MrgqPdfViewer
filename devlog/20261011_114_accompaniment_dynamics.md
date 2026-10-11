# 114 — 반주 셈여림 (MusicXML 셈여림표)

2026-10-11 · TODOs "반주 다듬기 — 반주 셈여림 · 아티큘레이션" · 사용자: "계속 진행해줘"

반주(P07 5 · 6단계)는 모든 음을 같은 크기로 냈다. MusicXML 의 셈여림을 읽어 음마다 크기를 달리한다.

## 바꾼 것
- **읽기**(`MusicXmlReader`, 파트마다 문서 순서로):
  - `<direction><direction-type><dynamics><p/>` 등 — pppp ~ ffff → 지금 셈여림(다음 표까지)
  - **한 음만 세게**: sf · sfz · sffz · fz · rf · rfz · fp · sfp · sfpp — 그 표 뒤 첫 음(화음이면 같은 시각의 음 모두)만, 그다음은 앞 셈여림. fp · sfp 는 뒤를 p 로(sfpp 는 pp)
  - 편집기가 적은 재생 크기: `<sound dynamics="X">`(방향 안 · 마디 안) = 포르테의 X%, 음에 적힌 `<note dynamics="X">` 가 가장 먼저
  - **셈여림표가 하나도 없는 파트는 모두 1** — 예전과 같은 크기(그래서 지금까지의 반주가 작아지지 않는다). 표가 있는 파트에서 첫 표 앞의 음은 mf
- 크기(`Dynamics.LEVELS`): ppp 0.28 · pp 0.38 · p 0.5 · mp 0.62 · mf 0.74 · f 0.86 · ff 0.94 · fff 1 — 악기의 실제 폭보다 좁게(여린 곳이 안 들리지 않게, 전체 크기는 반주 크기로)
- **소리**: `XmlNote.velocity` → `Accompaniment.velocities` → `AccompanimentVoices.noteOn(…, velocity)` 가 그 음의 진폭에 곱한다(오디오 스레드는 배열을 읽기만)
- 안 한 것: 크레셴도 · 디미누엔도(쐐기) — 다음 표까지 서서히 바꾸는 것, 피아노 보표마다 다른 셈여림(파트 하나로 본다), 아티큘레이션(스타카토 · 슬러 → 길이)

## 확인
- 단위: `MusicXmlAccompanimentTest.셈여림`(p · f · 화음 sf · sound · note dynamics · 표 없는 파트 · 첫 표 앞 · fp), `셈여림만큼_작게_울린다`(진폭 비례)
- [ ] 실기기: 셈여림 있는 MusicXML 곡(K488 등)에서 반주를 켜고 여린 곳 · 센 곳이 들리는지
