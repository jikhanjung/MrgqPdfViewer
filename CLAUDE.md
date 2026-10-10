# MrgqPdfViewer 프로젝트 가이드

## 공통 가이드 (`.guides`)
Android CI·릴리스·서명·사이드로드 배포 규약은 `.guides/mobile/README.md`(이 저장소의 경험에서 일반화), 공통 원칙은 `.guides/desktop/ci.md`·`packaging-release.md`, 브랜드는 `.guides/branding.md`. `.guides`는 `../devdocs/guides`를 가리키는 로컬 상대 심볼릭 링크다(`.gitignore` 처리).
없거나 끊어져 있으면 형제 devdocs 체크아웃이 없는 것 — devdocs는 private이고 이 저장소는 public이므로 가이드를 여기에 **커밋하지 않는다.**

## 프로젝트 개요
Android TV OS용 PDF 악보 리더 앱으로, 무선 파일 업로드와 리모컨을 이용한 탐색 기능을 제공합니다.

**타깃 기기**: Google TV Streamer + UPerfect 23.8" 4K 모니터 (2026-07 전환) · **Z18TV Pro**(1080p, 2대 — 합주 · 일상 실기기 확인은 주로 이것, adb `192.168.55.75:5555`) · **세로 태블릿** 샤오신패드 12.7(TB371FC, Android 13, 1840×2944 — adb 무선 디버깅, 포트는 켤 때마다 바뀜, #066)  
> ⚠️ **이 기기의 앱 UI 는 물리적으로 1080p 를 넘을 수 없다.** `ro.surface_flinger.max_graphics_width/height`
> 가 1920×1080 으로 빌드에 고정돼 있어(read-only, 루팅 없이 변경 불가) 앱은 1080p 로 그리고
> SurfaceFlinger 가 4K 로 업스케일한다. 4K 관련 작업을 시작하기 전에
> [`devlog/20260815_040_4k_display_investigation.md`](devlog/20260815_040_4k_display_investigation.md) 를 먼저 읽을 것.

**현재 버전**: v0.7.4-beta.7 (2026-10-10)  
**빌드 상태**: 🟢 빌드 가능 (GitHub Actions CI 로 커밋마다 검증)  
**CI 게이트**: 🟢 단위 테스트 444개 + Android Lint + 에뮬레이터 계측 60개·release APK 스모크(API 30/34 — ⚠️ 문서 정보 검사는 모든 매트릭스에서 건너뜀, #047 §5). 계측은 main 푸시와 수동 실행에서만 (회당 5~6분)  
**테스트 상태**: 🟢 v0.3.x 기능을 Z18TV Pro 에서 확인(2026-09-28): 파트보(한 · 여러 파트, 넓혀 자르기, 번호) · 파트 화면 악보 연동 · MusicXML 반주(K488) · MusicXML · 서버 분석 동기화 · 목록 곡 정보 · 앱 안 업데이트(v0.2.9 → v0.3.3). 🟡 **두 대가 필요한 것만 남음** — 합주 중 파트 보기, 합주 Phase 0 동기 넘김. 렌더 품질 · 키 매핑 등 전반 스모크는 2026-08-15(Google TV Streamer + 4K 모니터)
**최근 업데이트** (전체는 [`CHANGELOG.md`](CHANGELOG.md), 오늘 작업 요약은 devlog [`065`](devlog/20260928_065_part_view_accompaniment_server_analysis.md)):
- **v0.7.4-beta.7** — 태블릿 첫 화면 버전의 `-beta.n` 꼬리를 처음부터 다음 줄로
- **v0.7.4-beta.6** — **악보 화면 메뉴 정리**(P13, #095 ~ #097): ↑ = **연주**(🎤 · 시작/정지 · 메트로놈 설정 · 반주), OK 길게 = **보기**(파트 · 마디 박스 · 메모 · 두 쪽 · 자르기 · 여백 · 휴대폰 회전), 한 항목은 한 곳에만, 예비박은 메트로놈 첫 화면, 가운데 여백 다시 조절, ⌨️ 글자로 명령 뺌. 목록 `docs/Viewer_Menus.md`
- **v0.7.4-beta.5** — 🎙 **사람 이름 보표를 이름으로**: "지한이 파트" · "김지한 57마디부터"(악기 아닌 한글 보표 이름 — 온이름 · 성 뺀 이름, 어휘보다 먼저, 인식 힌트에도, `voice/PartNames`, #094)
- **v0.7.4-beta.4** — 🎙 설정 제목 "음성 명령"(시험 뗌), **👂 계속 듣기 잠시 꺼 둠**(`VOICE_ALWAYS_AVAILABLE = false` — 설정 줄 흐리게, 켜 둔 기기도 듣지 않음, 저장값 · 코드는 남김, #093)
- **v0.7.4-beta.3** — 👂 호출어 설정(설정 → 앱 정보 → 🗣, 쉼표로 여럿), 호출 뒤 말 놓침 줄임(말 끝 1.5초 · 호출어만이면 곧바로 다시, #091), 👂 가 듣는 동안 알림 소리를 꺼 딸깍 소리 없앰(#092)
- **v0.7.4-beta.2** — 휴대폰 **회전 모드**(설정 → 표시 모드: 기기 회전 · 가로 · 세로, 기본 기기 회전, 돌리면 새 폭으로 다시 그림, #090), TV · 태블릿 첫 화면 "× ScoreMate ☁️" 도 연한 파랑
- **v0.7.4-beta.1** — 🎤 듣기를 켜고 시작을 기다리는 마디만 연하게(#089)
- **v0.7.3** — 🎤 고른 마디부터 듣기 · 길게 눌러 다시 맞춤 · 음성 "듣기", 두 쪽 연주자 듣기 시작 펼침 (beta.1 · beta.2 묶음, #087 · #088)
- **v0.7.3-beta.2** — 지휘자가 듣기를 시작하면 두 쪽 연주자가 바로 시작 펼침(`page_change.roll_start`, 오른쪽 자리에서 시작 → 왼쪽을 다음 쪽으로, #088)
- **v0.7.3-beta.1** — 🎤 연주 듣고 넘기기를 고른 마디부터(고르는 중 "🎤 N마디부터 듣기" 단추 · 두 번 탭 ↑ 메뉴), 듣는 중 마디 길게 누르기 = 그 마디부터 다시 맞춤, 음성 "듣기" · "57마디부터 듣기"(#087)
- **v0.7.2** — 합주 버전(`ensemble_version`, 지금 0.4.0)이 다를 때만 합주 연결 안내(#086), 🎙 를 끄면 👂 도 끔, 0.7.1-beta.1 의 👂 계속 듣기(시험) 포함
- **v0.7.1-beta.1** — 👂 **계속 듣기(시험)**: 단추 없이 호출어 "메이트 / Mate" 뒤의 말만 실행, 연주 중에도, 새 명령 "멈춰 · 정지" · "N쪽 시작"(#085)
- **v0.7.0** — 🎙 **음성 명령(시험)** 판(beta.1 ~ beta.3 + #084): "57마디" = 고르기 · "부터 · 시작" = 바로, "시작" = 고른 마디(없으면 지금 화면 첫 마디), "예비박 한 마디 · 두 마디", 떼고 0.6초 더 듣기 · 잘린 "마" = 마디. 중국판 롬 태블릿은 "음성 인식 및 합성" 설치 + 그 앱 마이크 권한(#084). 명령 목록 `docs/Voice_Commands.md`
- **v0.6.4-beta.3** — 🎙 음성 명령(시험): 설정 → 앱 정보에서 켜면 악보 화면 오른쪽 아래 🎙, 누른 채로 말하기 — 기기 음성 인식(Google, 대개 인터넷), 후보 여럿 중 명령(#083)
- **v0.6.4-beta.2** — 음성 명령 명령 층 + 설정 → 앱 정보 ⌨️ 글자로 명령 시험(↑ 메뉴, 마디 명령은 바로 시작, #082)
- **v0.6.4-beta.1** — 음성 명령 1단계: 정규화 · 규칙 처리기(`voice/`, 화면 변화 없음, #081)
- **v0.6.3** — 목록 메모 동기화 확인 1분 → 10분 ± 30초(#080)
- **v0.6.2** — **지휘자 메모**(앙상블 두 겹, owner · leader 가 쓰고 모두 봄, #077) · 메모 모드 두 손가락 확대 · 이동(휴대폰은 쪽 단위, #078) · 곡 목록 ✏️ · 동기화 필요 표시(#079) · 목록 1분 확인 + 악보에서 돌아올 때 자동 동기화(#080)
- **v0.6.0** — **악보 메모**(태블릿 · 휴대폰, P11 · #072 ~ #076): 펜 바로 · 손가락은 ✏️ 메모 모드, 획 묶음(확인) · 글자 · 옮기기 · 지우개 · 되돌리기, 붙는 보표 표시, 곁 파일 `.notes.json`(악보를 따라다님), 개인 메모 ScoreMate 동기화(합치기)
- **v0.5.7** — 설정 🧪 사전 릴리스(beta) 받기(releases.atom, SemVer 비교, #073)
- **v0.5.6** — 스플래시 문구 "TV · 태블릿 · 휴대폰을 위한 스마트 악보 리더"
- **v0.5.5** — 휴대폰 ScoreMate 표시 0.6배 · 좁은 공백(구름 줄바꿈 고침)
- **v0.5.4** — 휴대폰 첫 화면 "MRGQ  × ScoreMate ☁️"(작게 · 연한 파랑) / "PDF Viewer v…" — 따로 줄 없음(`showPhoneTitle`)
- **v0.5.2** — 휴대폰 첫 화면 이름 "MRGQ" / "PDF Viewer v…" 두 줄, 아이콘은 이름 두 줄 가운데에(휴대폰 · 태블릿, `alignIconToTitle`)
- **v0.5.1** — 휴대폰 첫 화면 버전을 앱 이름 끝에 작게(스팬)
- **v0.5.0** — **휴대폰 지원** 판(v0.4.2 ~ v0.4.6 묶음) + ScoreMate 연결 화면은 휴대폰에서 QR 위 · 코드 아래(코드 한 줄, `DeviceLinkDialog`)
- **v0.4.6** — 휴대폰 첫 화면 앱 이름 두 줄까지(잘림 고침)
- **v0.4.5** — 휴대폰 악보 세로로 끌기 · 손 떼면 가까운 시스템에 맞춤, 화면 = 아무 시스템부터 들어가는 만큼, 쪽 끝 넘어 끌면 넘김
- **v0.4.4** — 휴대폰에서 악보를 열자마자 "Document already closed" 로 닫히던 것: 뷰어는 돌 때 다시 만들지 않는다(`configChanges` + 크기 · 세로 여부를 기기 종류로)
- **v0.4.3** — 휴대폰 악보 화면: 가로 고정, 분석된 악보는 시스템 1 ~ 2개씩(화면 높이에 맞춰 묶음), 없으면 위 · 아래 절반. 지금 마디를 따라 화면 이동, 반 쪽 넘김 · 넘김 애니메이션 끔
- **v0.4.2** — 휴대폰 첫 화면: 머리줄 넘침 고침(합주 상태는 이름 아래, 버튼 최소 폭 해제), 목록 카드 정보는 이름 아래 줄 (`DeviceForm.isPhone`, 짧은 변 600dp 미만)
- **v0.4.1** — 설정 → 🎤 녹음 기록(태블릿): 목록 · 요약 · 재생 · 공유 · 지우기
- **v0.4.0** — **태블릿이 연주를 듣고 쪽을 넘긴다**(P10, #067 ~ #071): 🎤 연주 듣고 넘기기, 반 쪽 넘김, 관성 · 시작 찾기, 합주 연주자 차례 넘김 · 시스템 표시, 지휘자가 끝내면 연주자도 끝. 연습 녹음 버튼 뺌
- **v0.3.5** — 태블릿 목록에서 곡을 한 번 탭으로 열기
- **v0.3.4** — 세로 태블릿(설치 · 세로 한 쪽 · 터치 · 페달, #066), 합주 버전 경고 오판 · 두 쪽 짝 맞춤 수정, 지휘자는 총보만, 파트보에 파트 이름, 쪽 캐시 지금 + 다음 화면
- **v0.3.3** — 목록 카드를 곡 정보로(서버 제목 + 파트, "작곡 · 편곡", 편곡자 DB v18), 쪽 정보도 제목, 첫 줄 "× ScoreMate ☁️" · 🔄 동기화, 세트리스트 곡 수 괄호
- **v0.3.2** — 서버 분석 파일(layout, P06 §12) 받아 쓰기, 파트 보기 이름을 MusicXML 에서
- **v0.3.1** — MusicXML 반주 연습(다른 파트를 박 시간표에 맞춰 단순 합성, 크기 따로), 합주 중 파트 보기, 메트로놈 메뉴에 파트 보기 · 반주
- **v0.3.0** — 파트보 보기(P07: 벡터 파트 PDF · 여러 파트 · 원본 번호 · 넓혀 자르기 · 악보 연동, DB v15~v17), 박 표시 작게, C · ¢ 박자표, MusicXML 동기화
- v0.2.9 ~ v0.2.10 — 연결한 TV 는 세트리스트로만, 10분마다 업데이트 확인(웹 releases/latest). v0.2.x 는 ScoreMate 연결 · 동기화 · 세트리스트, 합주 메트로놈, 구간별 빠르기, 앱 안 업데이트, 악보 분석 · 메트로놈 악보 연동

## 주요 기능
- **전문적인 스플래시 스크린**: 브랜딩 강화된 2.5초 애니메이션 시퀀스로 앱 시작
- **카드 기반 파일 목록**: 페이지 수, 파일 크기, 수정 날짜가 포함된 현대적 파일 표시. **한 번에 한 서재**(#063): ScoreMate 에 연결된 TV 는 `PDFs/ScoreMate/*/` 만(첫 줄 앱 이름 옆 "× ScoreMate ☁️", 🔄 동기화 버튼은 세트리스트 줄 오른쪽 끝, 정렬 · 파일관리 줄은 통째로 숨김, **웹서버 끔**), 아니면 `PDFs/` 바로 아래만. 반대쪽은 숨길 뿐. 합주 연주자: `file_change.sha256`(내용 해시 — 서버 · 계정 무관, 판까지 맞음. score_id 는 서버마다 번호라 안 씀)과 같은 내용이 내 ScoreMate 에 있으면 그것, 없으면 캐시(`cacheDir/ensemble/<해시16>/<이름>`, 200MB, 목록 밖, 설정 저장) — `ensemble/EnsembleFiles`. 지휘자가 보낸 이름은 경로로 쓰지 않음(`safeName`). **목록 카드**(ScoreMate 악보): 첫 줄 서버 제목 + 파트(작게), 둘째 줄 "작곡 ○○ · 편곡 ○○"(서버 곡 정보, `arranger` 는 DB v18), 오른쪽은 크기 · 쪽 수 · 날짜만(☁️ · 앙상블 · 판 뺌). 곡 정보만 바뀌어도 동기화가 목록을 다시 그린다(`SyncReport.updated`). **세트리스트**(#064): 동기화 때 `sync/setlists/` 를 받아 prefs 에 저장, 파일 목록 위 [곡목 (곡 수) …] 줄(포커스로 바뀜). **연결된 TV 는 세트리스트로만 본다** — "모든 악보" 탭 없음, 고른 적 없으면 첫 곡목, 곡목에 없는 악보는 안 보임, 세트리스트가 없으면 빈 화면 안내 — 곡 순서 · 번호 · 📝 메모, 이전/다음 파일도 곡 순서
- **상세 파일 정보**: PDF 페이지 수 + 문서 정보(제목·작성자, PdfBox) 표시. DB 에 캐시하고 바뀐 파일만 다시 분석 (`PdfFileSync`, devlog #045)
- **악보 구조 분석 (마디 박스)**: 벡터 악보 PDF 에서 시스템·마디를 찾아 DB 에 캐시하고 뷰어에 번호 붙은 박스로 표시 (보기 메뉴 "마디 박스"로 켬, 두 페이지 모드·클리핑 추종). 지원: Sibelius→PDF 형식. `score/` 패키지, devlog #046. 콘텐츠 스트림은 PdfBox 엔진이 아닌 경량 해석기(`PathContentInterpreter`)로 읽는다 — 할당 905MB → 19MB, Z18TV Pro 실기기 8쪽 556ms (#049). 박자표(예: 6/8)도 텍스트 위치로 읽는다 (#050). **MuseScore PDF**(끊어 그린 오선 · 보표 사이를 잇는 마디선 · 보표 2개 시스템 · SMuFL 음표 머리/박자 숫자)도 읽는다 (#056, DB v12 가 분석 캐시를 비움). **보표 띠 · 이름**도 저장(`score_staves`, v15 — 파트보 보기 1단계, P07 · v0.3.0 부터, `ScoreParts`). **파트보 보기**(P07 2단계): 고른 보표만 잘라 세로 A4 로 쌓은 벡터 PDF 를 캐시에 만들어(`PartLayout` · `PartPdfBuilder`) 뷰어가 연다 — 보기 메뉴 "파트 보기"(여러 파트 — 이웃한 보표는 한 조각, 보표 앞 `Pt. n`). 가운데선을 걸친 슬러 · 빔 · 덧줄 음은 소속에 따라 넓혀 자른다(`PartClip`), 원본 마디 · 쪽 번호 표시. 파트 화면에서도 마디 박스 · 악보 연동(`PartLayout.mapMeasures` 로 마디 좌표를 옮김, 3단계). **MusicXML 반주 연습**(P07 5 · 6단계): 악보 연동 메트로놈을 켜면 PDF 옆 `.musicxml` 의 다른 파트(파트 보기면 내 파트 빼고)를 단순 합성으로 들려준다 — 박 위치로 놓아 클릭과 같은 샘플 시계(`Accompaniment` · `AccompanimentVoices` · `MusicXmlReader` · `MusicXmlMatch`), ↑ 연주 메뉴 "반주"(소리 크기는 클릭과 따로). **합주 중 파트 보기**(4단계): 주고받는 쪽 번호는 원본 기준으로 옮긴다(`sourcePageFor` · `dstPageForSource`), 마디 신호는 그대로 — 두 대 실측 전
- **메트로놈**: ↑ 연주 메뉴 "메트로놈 설정…"에서 템포·박자·클릭음 설정 후 시작. AudioTrack 에 샘플 단위로 클릭을 이어 써 박이 흔들리지 않고, 화면 왼쪽 위 박 표시는 실제 재생 위치를 따른다. 템포·박자는 파일별 저장(v7, 분모 v10). 합주 동기화는 아래 "합주 메트로놈 동기화", 반주는 "반주 연습". `metronome/` 패키지, devlog #047
  - **대화상자** (#057): 첫 화면은 박자(2/4 · 3/4 · 4/4 · 6/8, `TimeSignature.PRIMARY`) · 속도 · 소리 크기 세 줄, 나머지는 줄마다 "상세…"(제목 줄 오른쪽 — 슬라이더가 ←→ 를 쓰므로)
  - **구간별 빠르기** (#057): 악보 박자가 바뀌는 마디마다 구간(`TempoSections.spans`, 몇~몇 마디 표시). 둘째 구간부터 이어받기(음표 길이 그대로 = 기본 · 박 길이 그대로 · 직접 입력) · 세는 단위를 정한다(`metronomeSections` JSON, v13). 첫 구간 = 기존 파일 설정. 악보 연동은 `ScoreFollower(sections)` 의 `beatTimes` · `BarPosition.bpm` 으로, 합주는 `metronome_run.tempo_sections` 로 연주자가 같은 박 시각을 만든다. 구간이 하나뿐인 곡은 v0.2.4 와 똑같이 동작. 연주 중 세는 단위 변경은 다음 시작부터. 2단계(템포만 바뀌는 곳에 구간 추가)는 미정
  - **박자 선택** (#051): `TimeSignature`(분자/분모) — 흔한 박자 버튼 + 분자 슬라이더·분모 버튼. 박·BPM = 분모 음표. 겹박자(분자 6·9·12)는 1박 강 · 4박 중간(`Accent.MEDIUM`). 고른 적 없는 파일은 악보 박자표로 채움(`metronomeBeatUnit` null = 미선택)
  - **점음표 박** (#052): 겹박자에서 `8분음표 6박` / `점4분음표 2박` 토글(파일별, `metronomeDottedBeat` v11). 바꿀 때 BPM 3배 환산으로 빠르기 유지. `TimeSignature.beatsPerBar(dotted)`, 악보 연동(`ScoreFollower(dottedBeat)`)도 같은 단위. 자동 전환 안 함(곡마다 경계가 달라 사용자 결정). `MIN_BPM` 20
  - **악보 연동** (#050): 박자표를 읽은 파일이면 시작 시 악보에서 커서(←→ 마디, ↑↓ 줄, OK)로 시작 마디를 고르고, 예비박(**기본 두 마디 · 한 마디 선택**, 박자 상세, 전역 — #060. 화면 가운데에 남은 마디 수 2 → 1, 합주는 `count_in_bars` 로 연주자도 같게) 뒤 현재 마디를 노랗게 표시하며 마지막 마디 끝나기 2박 전(`TURN_LEAD_BEATS`)에 페이지를 넘긴다. 박 = 박자표 분모 음표. 연주 중 뒤로는 메트로놈만 정지. 박자표를 못 읽으면 일반 메트로놈
  - **↑ 연주 메뉴 · 일시정지** (#053, 이름은 P13): 뷰어에서 ↑ = 연주 메뉴. 연주 중 ↑ 나 OK 길게(보기 메뉴)면 `FollowState.PAUSED` → 이어서(멈춘 마디부터 예비박) / 마디 골라 다시 / 정지. 메뉴를 모두 닫은 순간은 `onWindowFocusChanged` + 300ms 재확인으로 판단, 고르지 않고 닫으면 정지
- **합주 메트로놈 동기화** (#055): 연주자가 `clock_ping/pong` 으로 지휘자와의 시계 차이(`ClockSync`, RTT 최소 표본)를 재고, 지휘자는 `metronome_run`(상태 전체: 시간표 `BeatTimeline` · 박자 · 시작 마디 · `focus_measure`)을 상태 변화 · 연결 시 · 5초마다 방송. 연주자는 `EnsembleSchedule` 로 자기 시계에 옮겨 `MetronomeEngine.startScheduled`(AudioTimestamp 로 박을 프레임에 맞춤) — 박 표시 · 현재 마디는 시간표를 따른다. 따라가는 동안 지휘자 `page_change` 무시(마디로 스스로 넘김). 연주자는 소리만 고름(`ensemble_metronome_sound`, 기본 끔). `ensemble/` 패키지. 실측 마디 전환 차이 중앙값 2.5ms. **연결 때 버전 확인**(#061 → #086): `client_connect` · `connect_response` 의 **`ensemble_version`(합주 버전 = 합주 메시지가 바뀐 판의 앱 버전, 지금 0.4.0 — 합주 메시지를 바꾸면 `EnsembleVersion.CURRENT` 를 그 판으로)** 이 다를 때만 양쪽 화면에 업데이트할 기기를 가리키는 안내(`EnsembleVersion`, `VersionNotice`). 필드 없는 옛 기기는 `app_version` 0.4.0 이상이면 같음(v0.2.5 이하는 "v0.1.5" 고정 → "v0.2.5 이하")
- **파트보 보기** (P07, v0.3.0~): 보기 메뉴 "파트 보기" — 고른 보표(여러 개)만 잘라 세로 A4 로 이은 **벡터 PDF** 를 캐시에 만들어 뷰어가 연다(`PartLayout` · `PartPdfBuilder`). 이웃한 보표는 한 조각, 가운데선을 걸친 슬러 · 빔 · 덧줄 음은 소속에 따라 넓혀 자른다(`PartClip`). 줄마다 원본 마디 · 쪽 번호, 여러 파트면 보표 앞 `Pt. n`. 파트 화면에서도 마디 박스 · 악보 연동(`mapMeasures`), 합주 중에도(쪽 번호는 원본 기준). 파일별 선택 = 보표 비트 마스크(`partStaff`, v17). 이름은 PDF → 못 읽으면 MusicXML 파트 이름
- **반주 연습** (P07 5 · 6단계, v0.3.1~): 악보 연동 메트로놈을 켜면 PDF 옆 `.musicxml` 의 다른 파트(파트 보기면 내 파트 빼고)를 단순 합성으로 — 음을 박 위치로 놓아 클릭과 같은 샘플 시계(`MusicXmlReader` · `MusicXmlMatch` · `Accompaniment` · `AccompanimentVoices`). 마디 수 · 박자가 악보와 같을 때만. 반주 크기는 클릭과 따로, 합주 중에는 끔
- **마이크로 듣고 쪽 넘기기** (P10, #067 ~ #071, v0.4.0): 태블릿(지휘자) ↑ 메뉴 **🎤 연주 듣고 넘기기** — `follow/`: STFT 크로마(46ms) → `OnlineAligner`(OLTW + 주기적 재위치, Python 실험과 칸 단위로 같음) → `InertialTracker`(관성: 짧은 헤맴은 고른 빠르기로) → `PageTurnDecider`(반 쪽 넘김: 아래 절반을 칠 때 위만 다음 쪽 · 쪽 끝 + 1초 머무름). 시작은 첫 소리가 아니라 악보 첫머리와 맞는 소리(`StartDetector`). 지금 마디 · 시스템 표시, 손 넘김이면 다시 맞춤, 기록 `recordings/*_follow`. 합주: `page_change.roll` → 두 쪽 연주자는 5초 뒤 다 친 쪽 자리만 +2(`RollingTurns`), `follow_position` 으로 연주자도 지금 시스템 표시, `ensemble_end` 로 지휘자가 끝내면 연주자도 끝. 아르페지오네 실기기: ±1마디 93%, 쪽 넘김 ±2초 91%. 기준 빠르기 = 메트로놈 템포
- **세로 태블릿** (#066): 같은 APK — TV 가 아니면 세로(`DeviceForm`), 세로는 늘 한 쪽, 넘김 페달 키(PageUp/Down · 미디어 · Home/End), 터치(좌우 탭 · 밀기 = 넘김, 가운데 두 번 탭 = 연주 메뉴, 길게 = 보기 메뉴, 마디 탭 = 시작 마디). 세로 목록은 제목 · "× ScoreMate ☁️" · 세트리스트 세 줄
- **휴대폰** (v0.4.2 ~ v0.4.3, `DeviceForm.isPhone` = TV 아님 · 짧은 변 600dp 미만): 첫 화면은 합주 상태를 이름 아래로 · 카드 정보를 이름 아래 줄로. 악보 화면은 **회전 모드**(설정 → 표시 모드: 기기 회전(기본) · 가로 · 세로, #090 — 돌리면 새 폭으로 쪽 캐시를 다시 만든다), 쪽을 화면 폭에 맞춰 한 장으로 렌더(렌더 높이 = 폭 × 3)하고 보이는 **조각**을 행렬로 고른다 — 칸 = 시스템(이웃과의 빈칸 가운데까지), 분석 없으면 반 쪽. 화면 = 칸 `chunkIndex` 부터 들어가는 만큼(가로 보통 1 ~ 2개, 세로는 보통 한 쪽 전체), 화면보다 높을 때만 줄임. → ← 는 화면 단위, **세로로 끌기 → 손 떼면 가까운 시스템에 맞춤**(쪽 끝 넘어 1/4 → 넘김, v0.4.5), 악보 연동 · 마이크 · 시작 마디 커서는 그 마디가 화면 밖일 때만 그 시스템을 맨 위로(`followChunk`). 마디 분석은 휴대폰에서 쪽을 열 때 조용히 불러온다. 마이크 반 쪽 넘김 · 넘김 애니메이션은 휴대폰에서 끔
- **ScoreMate 서버 연결** (P05 C1, #058): 설정 → ☁️ ScoreMate → "이 TV 연결" — QR · 코드(RFC 8628) → 휴대폰에서 연결 → 토큰(앱 전용 prefs `scoremate`, **백업 제외** `backup_rules.xml`). 갱신은 `Mutex` 로 한 번에 하나(refresh 회전), 401 → 갱신 후 한 번 재시도, 갱신 401 → 해제로 보고 토큰 삭제. 앱 시작 시 heartbeat. `scoremate/` 패키지. **악보 동기화**(C2, #059): 앱 시작 · 설정 "지금 동기화" → `PDFs/ScoreMate/<앙상블 | 내 악보>/<제목>[ (파트)].pdf`(파일 목록 ☁️), 302 직접 · SHA-256 검증 · 옮길 때 `pdf_files` 경로 먼저 update · `ids` 밖은 묻지 않고 정리(서버 0.7.0: TV 는 웹에서 고른 세트리스트의 곡만 받음 — 곡목을 바꾸면 빠지는 게 정상, 파일별 설정은 남김) · TV 에서 지우면 숨김 · 모두 성공해야 커서 저장. `server_scores`(v14). **MusicXML**(서버 0.9.6, P06 §11): 응답의 `musicxml` 을 PDF 옆 `.musicxml` 로(sha 다를 때만, null 이면 지움, PDF 따라 옮김/지움), 앱 동기화 형식(`sync_format`)이 오르면 커서를 한 번 처음부터. **서버 분석**(P06 §12): `layout` 을 PDF 옆 `.layout.json` 으로 받고, 그 판의 것이면 앱이 분석하지 않고 쓴다(`ServerLayouts`, 파일이 캐시보다 새로우면 다시 읽음). 서버 요청: devlog P06
- **앱 안 업데이트** (#054): 설정 → 앱 정보 → 업데이트 확인. 웹 `github.com/…/releases/latest` 의 302 Location 에서 태그(API 안 씀 — 비인증 IP 당 시간 60회 한도 회피) → 태그로 만든 `-release.apk` 주소로 다운로드(`cacheDir/updates`) → `SHA256SUMS.txt` 로 검증, 변경 내용은 알릴 때만 그 태그의 `CHANGELOG.md` 섹션(raw) → 설치 허용 확인 → `FileProvider` + `ACTION_VIEW`. `update/` 패키지. 자동 확인: 파일 목록이 떠 있는 동안 **10분마다**(성공 · 실패 무관, MainActivity 1분 틱 → `UpdateController.checkIfDue`, onPause 에서 멈춤), 오류는 조용히 무시(로그만), 새 버전이 있을 때만 알림 · "나중에" 누른 버전은 그 프로세스에서 다시 안 물음 · 합주 중 · 대화상자 위 제외 · 설정에서 끔
- **페이지 전환 애니메이션**: 350ms 슬라이드 애니메이션으로 실제 악보 페이지 넘기기 구현
- **효과음 시스템**: SoundPool 기반 페이지 넘기기 사운드 및 슬라이더 볼륨 조절
- **TV 스타일 설정**: 이모지 아이콘 카테고리 메뉴, 리모컨 최적화 탐색, 직관적 UI
- **웹 서버 업로드**: 설정 가능한 포트의 HTTP 서버를 통한 브라우저 기반 무선 파일 업로드  
- **업로드 진행률**: 실시간 업로드 진행률 표시 및 상태 메시지
- **웹 파일 관리**: 브라우저에서 파일 목록 조회, 개별/전체 삭제 기능
- **실시간 웹서버 모니터링**: 모든 HTTP 요청, 파일 업로드, 삭제 작업을 실시간 로그로 투명하게 추적
- **PDF 뷰어**: Android PdfRenderer를 이용한 고해상도 PDF 렌더링
- **두 페이지 모드**: 가로 화면에서 세로 PDF를 자동으로 두 페이지로 표시하는 스마트 모드
- **악보 화면 메뉴 두 개** (P13, [`docs/Viewer_Menus.md`](docs/Viewer_Menus.md)): **↑ 연주**(🎤 · 시작/정지 · 메트로놈 설정 · 반주) · **OK 길게 보기**(파트 보기 · 마디 박스 · 메모 · 두 쪽 · 위/아래 자르기 0-15% + 두 쪽이면 가운데 여백 0-15% · 휴대폰 회전). 한 항목은 한 곳에만. 예비박은 메트로놈 설정 첫 화면
- **Room 데이터베이스**: PDF 메타데이터 및 사용자 설정을 SQLite로 관리
- **DisplayMode 시스템**: AUTO/SINGLE/DOUBLE 표시 모드를 파일별로 개별 저장
- **혁신적인 파일 탐색**: 좌우 분할 카드 UI로 직관적인 파일 간 이동
- **리모컨 탐색**: DPAD 및 ENTER 키 완벽 지원
- **TV 최적화**: Android TV UI 가이드라인 준수, 다크 테마 기반 디자인
- **보안 향상**: 앱 전용 디렉토리 사용으로 외부 저장소 권한 불필요

## 기술 스택
- **플랫폼**: Android TV OS (minSdk 30, targetSdk 30). 2026-09-13 에 21 → 30 (devlog #048). 실사용 기기 **Google TV Streamer · Z18TV Pro 둘 다 Android 14** (Z18TV Pro 는 출고 Android 11 → 업데이트, adb `192.168.55.75` 로 확인, #049)
- **언어**: Kotlin
- **아키텍처**: PdfViewerActivity + PageCache 중심 (v0.1.8 의 Manager 분리 시도는 미통합 상태로 남아 있다가 v0.1.11 에서 제거됨)
- **PDF 렌더링**: PdfRenderer (Android 5.0+ 내장)
- **PdfBox-Android** 2.0.27.0: 문서 정보(Title/Author), 악보 분석의 스트림 · 글꼴, 파트 PDF 만들기(폼 가져오기). BouncyCastle PQC 리소스는 패키징 제외)
- **데이터베이스**: Room 2.6.1 (SQLite 기반, 현재 v18 스키마 — `score_measures`(박자표 포함), `score_staves`(보표 띠 · 이름, 파트보 P07), `user_preferences.partStaff`(파트보 선택 — 보표 비트 마스크, 여러 파트, v17), `server_scores`(ScoreMate 받은 악보), 파일별 메트로놈(템포·박자 분자/분모·점음표 박·구간별 빠르기) 포함. ⚠️ `pdf_files` 갱신에 `insertPdfFile`(REPLACE) 금지 — CASCADE 로 표시 설정 삭제)
- **웹 서버**: NanoHTTPD 2.3.1
- **합주 통신**: WebSocket(WS) — WSS 는 v0.1.8 에 시도했다가 롤백, cleartext 허용
- **입력 처리**: 리모컨용 KeyEvent 처리
- **비동기**: Kotlin Coroutines

## 디렉토리 구조
```
/storage/emulated/0/Android/data/com.mrgq.pdfviewer/files/PDFs/
├── 악보1.pdf
├── 악보2.pdf
└── 악보3.pdf
```

## 필요한 권한
- `INTERNET`, `ACCESS_NETWORK_STATE` (웹 서버 기능용)
- `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE` (네트워크 상태 관리용)

## 핵심 컴포넌트

### 1. PDF 파일 목록
- 앱 전용 디렉토리의 PDF 파일만 필터링하여 표시
- 파일명 정렬 (A-Z)
- 썸네일 없는 단순 리스트 뷰
- 리모컨 탐색 (DPAD_UP/DOWN, ENTER)

### 2. 웹 서버
- 기본 포트: 8080
- HTML 업로드 폼 (단일/다중 파일 지원)
- 앱 전용 디렉토리에 자동 파일 저장
- ON/OFF 토글 기능

### 3. PDF 뷰어
- 한 번에 한 페이지 표시
- 고정 배율 또는 화면 맞춤 비율
- 리모컨으로 페이지 이동 (LEFT/RIGHT)
- 선택적 페이지 번호 표시

## 리모컨 키 매핑
- **DPAD_UP/DOWN**: 파일 목록 탐색
- **DPAD_LEFT/RIGHT**: PDF 뷰어에서 이전/다음 페이지
- **ENTER**: 파일 선택 또는 동작 확인
- **ENTER 길게 누르기 (800ms)**: 보기 메뉴 (악보 연동 중이면 일시정지)
- **DPAD_UP (PDF 뷰어)**: 연주 메뉴 — 악보 연동 중이면 일시정지 후 이어서 / 마디 골라 다시 / 정지 (#053). 시작 마디 고르는 중에는 이전 줄로 커서 이동

## 개발 가이드라인
- Android SDK 네이티브 컴포넌트 사용
- 파일 작업용 적절한 에러 처리 구현
- TV 하드웨어에서 원활한 성능 보장
- Android TV UI 가이드라인 준수
- 리모컨 입력으로 철저한 테스트
- 앱 전용 디렉토리 사용으로 보안 및 권한 정책 준수

## 개발 환경

### 개발 워크플로우
- **코딩**: WSL2의 Claude Code에서 소스 코드 편집
- **빌드/테스트**: WSL 에서 `powershell.exe` 로 Windows Gradle 을 부른다 (WSL 에는 Android SDK 가 없다) — 아래 명령. Android Studio 도 그대로 쓸 수 있다
- **실기기 설치**: TV 와이파이가 느려 `adb install` 스트리밍이 가끔 실패한다 — `adb push` 로 `/data/local/tmp` 에 올린 뒤 `adb shell pm install -r`. release 빌드는 덮어써도 데이터가 남는다(debug 는 서명이 달라 지워야 한다)
- **CI 빌드**: GitHub Actions. 빌드 정의는 `build.yml`(재사용 워크플로우) 한 곳에 있고 `android-build.yml`(main 푸시/PR/수동)과 `release.yml`(`v*` 태그)이 이를 호출한다. 컴파일 검증을 WSL에서 커밋만으로 수행 가능. release 는 기본 debug keystore 서명 (정식 서명은 `RELEASE_KEYSTORE_*` secrets 등록 시 자동 전환)
- **릴리스**: `v*` 태그 푸시 → 검증(태그==versionName, CHANGELOG 섹션 존재) → 빌드 → GitHub Release 생성(APK + `SHA256SUMS.txt`, **본문은 `CHANGELOG.md` 의 해당 섹션 그대로**). `-alpha`/`-beta`/`-rc` 는 pre-release 자동 표시. 이미 푸시된 태그로 재생성하려면 Actions → Release → workflow_dispatch 에 태그 입력 (태그는 삭제·재사용하지 않는다)
- **빌드/CI/릴리스 표준 점검**: `docs/4_Build_CI_Release.md` — 무엇을 채택했고 무엇을 의도적으로 안 했는지, 남은 갭이 무엇인지. CI 에는 단위 테스트 · lint · 계측 테스트(API 30/34, main 푸시 · 수동)가 들어갔다(#043~)
- **디버깅**: Windows 11에서 로그 확인 및 에뮬레이터/실기기 테스트

### 빌드 명령어
```bash
# WSL 에서 (반드시 --no-daemon — 아니면 데몬이 파이프를 잡아 명령이 끝나지 않는다. 로그는 UTF-16)
powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; Set-Location D:\projects\MrgqPdfViewer; .\gradlew.bat assembleRelease testDebugUnitTest --no-daemon --console=plain *> D:\projects\MrgqPdfViewer\app\build\build.log; "exit=$LASTEXITCODE"'
iconv -f UTF-16LE -t UTF-8 app/build/build.log | grep -E "^e:|BUILD|FAILED"

# 실기기 설치 (Z18TV Pro)
adb connect 192.168.55.75:5555
adb -s 192.168.55.75:5555 push app/build/outputs/apk/release/MrgqPdfViewer-v<버전>-release.apk /data/local/tmp/mrgq.apk
adb -s 192.168.55.75:5555 shell pm install -r /data/local/tmp/mrgq.apk
```

### 테스트 항목
기능 구현 시 다음 항목들을 테스트하세요:
- 파일 목록 기능 (PDF 파일 인식 및 표시)
- PDF 렌더링 성능 (다양한 크기 파일)
- 웹 서버 업로드/다운로드 (브라우저 테스트)
- 리모컨 반응성 (모든 키 입력)
- 저장소 권한 처리 (Android 11+ 포함)

## 프로젝트 파일 구조

### 핵심 파일
- `MainActivity.kt`: 메인 화면 및 파일 목록, 합주 파일 변경 처리
- `PdfViewerActivity.kt`: PDF 뷰어 화면 (고해상도 렌더링, 두 페이지 모드, 클리핑/여백 설정)
- `SettingsActivity.kt`: 기존 설정 화면 (포트 설정, 파일 관리, 설정 초기화)
- `SettingsActivityNew.kt`: 새로운 TV 스타일 설정 화면
- `WebServerManager.kt`: HTTP 서버 관리 (업로드 진행률, 파일 관리 API)
- `PdfFileAdapter.kt`: 파일 목록 어댑터
- `SimpleWebSocketServer.kt`: WebSocket 서버 (소켓 타임아웃 개선)
- `GlobalCollaborationManager.kt`: 전역 합주 상태 관리
- `ConductorDiscovery.kt`: UDP 브로드캐스트 자동 발견 시스템 (타이밍 최적화)
- `PageCache.kt`: 페이지 렌더 캐시 · 프리렌더 (1× · 정수 좌표)

### 패키지
- `score/`: 악보 분석(`PathContentInterpreter` · `StaffSystemDetector` · `TimeSignatureDetector` · `StaffLabelDetector` → `ScoreLayout`, 캐시 `ScoreLayoutStore`, 서버 분석 `ServerLayouts`), 파트보(`ScoreParts` · `PartLayout` · `PartClip` · `PartPdfBuilder` · `PartStaves`), MusicXML(`MusicXmlScore` · `MusicXmlReader`), 마디 오버레이
- `metronome/`: 엔진(AudioTrack 샘플 단위) · 박 시계 · 박자 · 구간별 빠르기 · 악보 연동(`ScoreFollower`) · 반주(`Accompaniment` · `AccompanimentVoices` · `MusicXmlMatch`)
- `ensemble/`: 합주 메트로놈 시간표 · 시계 동기 · 합주 파일 맞추기(`EnsembleFiles`) · 버전 확인
- `scoremate/`: 서버 연결(`ScoreMateClient` · 토큰) · 동기화(`ScoreMateSync` — PDF + 곁 파일 `.musicxml` · `.layout.json`) · 세트리스트
- `update/`: 앱 안 업데이트(웹 `releases/latest` → APK · SHA256SUMS)
- `database/`: Room 엔티티 · DAO · 마이그레이션 (`repository/` 가 감싼다)

### 리소스 파일
- `activity_settings.xml`: 기존 설정 화면 레이아웃 (웹서버, PDF 관리, 파일별 설정)
- `activity_settings_new.xml`: 새로운 TV 스타일 설정 화면 레이아웃
- `item_settings.xml`: 설정 아이템 레이아웃 (아이콘, 제목, 부제목)
- `circle_background.xml`: 설정 아이콘 배경 drawable
- `colors.xml`: TV 최적화 색상 팔레트
- `themes.xml`: TV 전용 테마
- `dimens.xml`: TV 화면 크기 고려 치수
- `strings.xml`: 다국어 지원 텍스트

### 모델 및 어댑터
- `SettingsItem.kt`: 설정 메뉴 아이템 데이터 모델
- `SettingsAdapter.kt`: TV 스타일 설정 RecyclerView 어댑터

## 현재 상태 · 알아 둘 결정

버전별 변경은 [`CHANGELOG.md`](CHANGELOG.md), 작업 기록은 [`devlog/README.md`](devlog/README.md)(인덱스), 지금 할 일은 [`HANDOFF.md`](HANDOFF.md) · [`TODOs.md`](TODOs.md).
v0.1.x 시절의 상세 이력(합주 모드 재구조화 · 스플래시 · 설정 화면 등)은 CHANGELOG 와 devlog #001~#039 에 있다.

### 🟡 확인이 남은 것
- ~~합주 중 파트 보기~~ — 두 대 확인 완료(2026-09-28, 태블릿 ↔ Z18TV, 파트보 연주자 포함). **두 쪽 지휘자 → 한 쪽 연주자는 쓰지 않는다**(P07 4단계 표). **지휘자는 늘 총보**(파트 보기는 연주자 · 혼자 연습)
- **합주 Phase 0 동기 넘김** (기본 OFF, 접근법 보류 — 아래)

### 렌더링 (바꾸기 전에 읽을 것)
- **네이티브 1× 렌더 · 정수 좌표**(#042): oversample 은 얇은 선을 오히려 흐리게 했다(PDFium 이 1px 미만 선을 픽셀에 스냅). 소수점 blit/scale 은 스냅을 무효화하므로
  `combineTwoPagesUnified` 배치와 `setImageViewMatrix` translate 는 정수, 배율 1.0 근처는 정확히 1.0. 잉크 감마(#041)는 1× 에서 자동 OFF
- **4K 불가**(#040): 기기 UI 가 1080p 고정(`ro.surface_flinger.max_graphics_*`)
- **쪽 캐시 = 지금 화면 + 다음 화면**(#066): 한 쪽 모드 2장 · 두 쪽 모드 4장, 뒤로는 그 자리에서(태블릿 40~55ms). 비트맵은 네이티브 메모리라 Java 힙 한도와 무관
- **렌더러 교체 안 함**(#041): PdfRenderer = PDFium, MuPDF 가 더 흐렸다(P5 제외)
- **파트보는 벡터 PDF**(P07): 보표를 잘라 이은 PDF 를 캐시에 만들어 같은 렌더 경로로 연다

### 합주
- **Phase 0 동기 넘김**(#038, 기본 OFF): 예약 방식은 늘 `lead` 만큼 기다려 돌발 대처가 안 된다 — 보류. 재설계한다면 먼저 기기 간 편차의 실체(렌더 지연 가능성)를 잰다
- **합주 메트로놈**(#055)은 시계 차이 + 시간표 방송으로 실측 마디 전환 차이 중앙값 2.5ms — 넘김도 마디로 스스로 한다
- **합주 버전**(#086): 합주 메시지 · 명령을 바꾸는 판에서 `EnsembleVersion.CURRENT` 를 **그 판의 앱 버전**으로 올린다(지금 0.4.0). 앱 버전만 다르면 연결 안내가 뜨지 않는다. 옛 기기가 무시해도 어긋나지 않는 선택 필드 추가(예: `roll_start`)는 올리지 않는다
- **WSS 는 롤백**(v0.1.8): 인증서 호환 문제로 일반 WebSocket(WS), cleartext 허용
- 파트 PDF · MusicXML · 서버 분석 파일은 **기기 밖으로 보내지 않는다**(저작권 원칙, P07 §0 · 서버 P01 §2). 지휘자가 나눠 주는 것은 원본 PDF 뿐

### DB (Room v18)
- 마이그레이션은 `MusicDatabase.ALL_MIGRATIONS` 한 곳, 계측 테스트 `MusicDatabaseMigrationTest` 가 v1 부터 검사
- `pdf_files` 갱신에 `insertPdfFile`(REPLACE) 금지 — CASCADE 로 파일별 설정 삭제
- 분석 캐시 무효화는 이제 서버 분석 파일(`.layout.json`)이 새로우면 다시 읽는 것으로(`ServerLayouts`) — 연결하지 않은 TV 는 여전히 마이그레이션(v9 · v12 · v15 처럼)으로 비워야 한다
