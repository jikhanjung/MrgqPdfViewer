# 145 — 곡 목록의 별: ★ 악보 연동 · ★★ MusicXML 까지 (v0.9.1)

2026-10-15. 사용자: "앱의 곡 목록에서 한쪽에 별을 보여주자. 벡터 PDF 라서 마디 인식, 메트로놈 연동 자동 페이지 넘기기가 가능하면 별 하나.
MusicXML 이 있어서 파트보, 반주, 듣고 넘기기 등이 가능하면 별 두 개." → "끝나면 바로 0.9.1 로 릴리스 해줘."

## 기준 (`score/ScoreLevel`)

- ★ = 마디를 찾았고 박자표를 읽은 마디가 있다 — `ScoreFollower.startableMeasures` 가 비지 않음. 악보 화면이 메트로놈 악보 연동을
  켤 수 있는 조건과 똑같다(마디만 있고 박자표를 못 읽으면 일반 메트로놈이라 별 없음).
- ★★ = 거기에 PDF 옆 `.musicxml`(`ScoreMateSync.musicXmlFileOf`). 파트 보기 · 반주 · 🎤 듣고 넘기기는 모두 PDF 의 마디 자리가 있어야
  돌므로, MusicXML 만 있고 마디를 못 찾은 악보(스캔 · 서버 OMR)는 별이 없다.
- 반주가 실제로 되는지(마디 수 · 박자가 맞는지, `MusicXmlMatch`)는 목록에서 보지 않는다 — MusicXML 을 읽어 맞춰 봐야 해서 무겁다.

## 목록에서

- 분석이 끝난 파일: `score_measures` 질의 한 번(`ScoreMeasureDao.followableFileIds` — 박자표가 있는 마디의 파일 id)으로 별을 바로.
- 아직 분석하지 않은 파일(`ScoreLayoutStore.needsAnalysis` — `scoreAnalyzedAt` 없음 · 서버 분석 파일이 더 새로움): 목록을 그린 뒤
  **보이는 차례로 하나씩** `getOrAnalyze`(`MainActivity.analyzeForStars`) — 별이 바뀌면 목록을 다시. 분석 결과는 원래 캐시라 악보
  화면도 그대로 쓴다(TV 는 마디 박스 · 메트로놈을 켤 때 분석하던 것을 미리 하는 셈). 악보를 열면(onPause) 멈추고, 목록을 다시 읽으면 처음부터.
  같은 뮤텍스라 악보를 열 때 분석 중이던 파일 하나만큼은 기다릴 수 있다.
- 카드 오른쪽 끝, 크기 · 날짜 뒤에 금색 별. 칸 최소 폭(34dp)을 정해 둬 줄마다 크기 · 날짜가 맞는다. 화면 읽기 글은 리소스(한 · 영).

## 테스트

`ScoreLevelTest` 3개(★ · ★★ · 마디나 박자표가 없으면 MusicXML 이 있어도 0). 단위 테스트 546. 설명서 4.2.
