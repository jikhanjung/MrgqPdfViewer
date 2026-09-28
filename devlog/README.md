# devlog 인덱스

MrgqPdfViewer 개발 로그 모음. 구현 기록은 `YYYYMMDD_NNN_` 형식(NNN 은 작성 순
연속 번호, 날짜와 무관), 계획 문서는 `YYYYMMDD_PNN_` 별도 체계(P01 부터).

**기준 버전**: v0.3.3 (2026-09-28) · 최종 갱신: 2026-09-28

> 전체 개발 연대기(커밋 히스토리 기반, devlog 이전 구간 포함)는
> [`20260619_039_project_timeline.md`](20260619_039_project_timeline.md) 참고.

---

## 📁 v0.1.8 — 권한 제거 · 리팩토링 시도 (2025-07-15)
- [`001`](20250715_001_Daily_Summary.md) — Daily Summary
- [`002`](20250715_002_Page_Settings_Preset_Fix.md) — 페이지 설정 프리셋 버그 수정
- [`003`](20250715_003_PdfViewerActivity_Refactoring.md) — PdfViewerActivity 리팩토링 (매니저 분리 시도)
- [`004`](20250715_004_WSS_Security_Implementation.md) — WSS 보안 구현 (이후 롤백)
- [`005`](20250715_005_Storage_Policy_Refinement.md) — 스토리지 정책 정비 (외부 권한 제거)

## 🎨 v0.1.9 — UI/UX · 애니메이션 (2025-07-18~19)
- [`006`](20250718_006_animation_scaling_fix.md) — 애니메이션 스케일링 수정
- [`007`](20250718_007_comprehensive_ui_improvements.md) — 종합 UI 개선
- [`008`](20250719_008_feature_requests.md) — 사용자 기능 요청 정리
- [`009`](20250719_009_feature_implementation.md) — 기능 구현

## 🎼 v0.1.9+ — 합주 동기화 · 두 페이지 리팩토링 (2025-07-20)
- [`010`](20250720_010_Daily_Overview.md) — Daily Overview
- [`011`](20250720_011_collaboration_file_sync_fix_plan.md) — 합주 파일 동기화 수정 계획
- [`012`](20250720_012_collaboration_sync_deep_analysis.md) — 동기화 심층 분석
- [`013`](20250720_013_collaboration_sync_fix_plan_final.md) — 동기화 수정 최종 계획
- [`014`](20250720_014_collaboration_sync_fix_implementation.md) — 동기화 수정 구현
- [`015`](20250720_015_animation_sync_race_condition_fix_plan.md) — 애니메이션 동기화 race condition 수정 계획
- [`016`](20250720_016_animation_sync_analysis_correction.md) — 애니메이션 동기화 분석 정정
- [`017`](20250720_017_animation_sync_fix_implementation.md) — 애니메이션 동기화 수정 구현
- [`018`](20250720_018_performer_animation_sync_implementation.md) — 연주자 애니메이션 동기화 구현
- [`019`](20250720_019_two_page_rendering_refactoring.md) — 두 페이지 렌더링 리팩토링
- [`020`](20250720_020_two_page_padding_aspect_ratio_fix.md) — 두 페이지 여백/종횡비 수정

## 🚀 v0.1.10 — 동시성 · 웹서버 (2025-07-26~28)
- [`021`](20250726_021_ensemble_mode_improvements.md) — 합주 모드 개선
- [`022`](20250727_022_Daily_Summary.md) — Daily Summary
- [`023`](20250727_023_delay_minimization_guide.md) — 지연 최소화 가이드
- [`024`](20250727_024_ensemble_mode_performance_optimization.md) — 합주 모드 성능 최적화
- [`025`](20250727_025_pdf_renderer_concurrency_issue.md) — PDF 렌더러 동시성 문제 분석
- [`026`](20250727_026_pdf_renderer_concurrency_fix_implementation.md) — 동시성 문제 수정 구현
- [`027`](20250727_027_v0.2.x_roadmap.md) — v0.2.x 로드맵
- [`028`](20250727_028_webserver_ui_improvements.md) — 웹서버 UI 개선
- [`029`](20250728_029_file_list_sorting_fix.md) — 파일 목록 정렬 수정
- [`030`](20250728_030_main_screen_file_index_mismatch.md) — 메인 화면 파일 인덱스 불일치
- [`031`](20250728_031_main_screen_file_open_race_condition_fix.md) — 파일 열기 race condition 수정 계획
- [`032`](20250728_032_race_condition_fix_implementation.md) — race condition 수정 구현

## 📐 (2025-07-29~08-02) 레이아웃 · 설정 개선
- [`033`](20250729_033_two_page_layout_improvements.md) — 두 페이지 레이아웃 개선
- [`034`](20250802_034_ui_and_settings_improvements.md) — UI 및 설정 개선

## 🎼 v0.1.11 — 오선 두께 균일화 (P01, 2026-05-28)
- [`P01`](20260528_P01_staff_line_rendering_fix_plan.md) — 오선 렌더링 개선 **계획**
- [`035`](20260528_035_staff_line_rendering_fix_implementation.md) — 오선 렌더링 개선 **구현** (단일 Matrix 렌더, dead code 정리)

## 🎼 v0.1.12 — 오선 dropout 해결 (P2, 2026-05-30)
- [`036`](20260530_036_P2_oversample_bump.md) — P2 oversample bump (P2-A 실패→P2-B 채택→P2-C revert 전 과정)

## 🎵 v0.1.13 — 합주 자동화 · 악보 분석 (2026-06)
- [`037`](20260613_037_score_analysis_work.md) — 벡터 PDF 악보 분석 작업 (data/, 앱 미통합)
- [`P02`](20260613_P02_score_sync_autoturn_plan.md) — 재생·자동 넘김·메트로놈·스코어 팔로잉 단계별 **계획**
- [`P03`](20260613_P03_clock_sync_design.md) — 클럭 동기화 **설계**
- [`038`](20260614_038_phase0_sync_page_turn_implementation.md) — 합주 Phase 0 동기 페이지 넘김 **구현** (⚠️ 실기기 미검증)

## 🖥️ v0.1.13 — 4K 조사 · 잉크 감마 (2026-07~08)
- [`040`](20260815_040_4k_display_investigation.md) — 4K 계단현상 조사 → **기기 한계로 종결** (`ro.surface_flinger.max_graphics_*` = 1920×1080 하드코딩, SurfaceView 경로도 불가)
- [`041`](20260815_041_ink_gamma_and_renderer_comparison.md) — PDFium vs MuPDF 비교(**교체 무익, P5 제외**) → 다운스케일 잉크 희석 발견 → **감마 보정 도입**
- [`P04`](20260815_P04_rpi5_appliance_review.md) — 리눅스 악보 전용기 **기술 검토**. #042 로 4K 동인은 소멸 → **Pi 4 로 "전용기기 구성이 즉각 반응하는가" 검증**으로 전환 (동인: P02 Python 정합성)
- [`042`](20260815_042_oversample_removal.md) — 배율 스윕 → **oversample 자체가 원인**. 4×→1× 로 되돌려 오선 darkness 1.000 달성 (P01/P2 전제 정정)

## 🧪 CI · 테스트 (2026-09)
- [`043`](20260906_043_instrumentation_ci_repair.md) — 계측 CI 복구. 도입 이후 3주간 한 번도 초록불이 아니었던 워크플로를 고쳐 **API 21/30/34 전 매트릭스 통과**. 실패 네 겹 중 셋이 CI 스크립트 자체 문제였다
- [`044`](20260913_044_room_migration_test.md) — Room 마이그레이션 테스트(v1~v3→v4). 과거 스키마 JSON 이 없어 git 히스토리로 복원. **`MIGRATION_3_4` 가 설정을 지워 왔음** 발견 → 현재 동작으로 고정

## 📄 PDF 문서 정보 (2026-09)
- [`045`](20260913_045_pdf_document_info.md) — PdfBox-Android 로 제목·작성자를 읽어 DB(v5)에 캐시하고 목록 카드에 표시. **insert(REPLACE) 갱신이 표시 설정을 지우는 함정**을 테스트로 고정, APK 8.8MB → 4.6MB(BouncyCastle PQC 제외)

## 🎼 악보 구조 분석 (2026-09)
- [`046`](20260913_046_score_layout_in_app.md) — `segment_score.py` 를 Kotlin(PdfBox)으로 옮겨 **파이썬 골든(26 시스템/81 마디)과 일치**, DB v6 캐시, 뷰어 **마디 박스 오버레이**(두 페이지 모드 화면 정합 확인). 변이 검사로 기둥 제외 규칙은 골든이 못 잡음을 발견. MusicXML·메트로놈 다음 단계 메모

## 🥁 메트로놈 (2026-09)
- [`047`](20260913_047_metronome.md) — AudioTrack 스트림에 클릭을 샘플 단위로 이어 써 흔들림 없는 박, 파일별 템포·박자(v7). 에뮬레이터에서 박 간격이 100 BPM 샘플 수와 정확히 일치. **CI 문서 정보 스모크가 세 매트릭스 모두 건너뛰고 있음**을 발견
- [`050`](20260913_050_time_signature_and_score_follow.md) — **박자표 읽기**(Moldau 6/8, 텍스트 위치로) + **메트로놈 악보 연동**: 악보에서 커서로 시작 마디 선택 → 한 마디 예비박 → 현재 마디 표시 → 마지막 마디 2박 전 자동 넘김 (P02 Phase 1). 에뮬레이터 흐름 확인
- [`051`](20260913_051_metronome_time_signature.md) — **박자 선택**: x/4 고정 → 분자/분모 (흔한 박자 버튼 + 직접 고르기), 겹박자 1박 강 · 4박 중간(새 클릭음), 고른 적 없는 파일은 악보 박자표로 채움(DB v10). 흔한 박자 통계는 코퍼스 수치를 못 찾아 이론 자료 순서로
- [`052`](20260913_052_metronome_dotted_beat.md) — **점음표 박**: 겹박자를 `8분음표 6박` / `점4분음표 2박` 중 파일마다 골라 센다(바꿔도 빠르기 유지, BPM 3배 환산). 빠른 6/8 이 240 BPM 상한에 막히던 문제 해결. 악보 연동도 2박 마디로(DB v11)
- [`053`](20260913_053_metronome_pause_menu.md) — **↑ 메트로놈 메뉴 + 악보 연동 일시정지**: 연주 중 ↑ 나 OK 길게(옵션 메뉴)면 멈추고, 이어서(멈춘 마디부터 예비박) · 마디 골라 다시 시작 · 정지를 고른다. 메뉴를 모두 닫은 순간은 창 포커스로 판단. 리모컨 키만으로 에뮬레이터 흐름 확인

## 📱 지원 범위 (2026-09)
- [`048`](20260913_048_min_sdk_30.md) — **minSdk 21 → 30**. API 21 에뮬레이터의 악보 분석 OOM(직전엔 통과하던 불안정 게이트)을 계기로, CI 매트릭스를 API 30/34 로 축소. (근거로 든 "Z18TV Pro = Android 11" 은 출고 사양이었고 실기기는 Android 14 — #049 에서 정정, minSdk 30 유지)
- [`049`](20260913_049_score_analysis_memory.md) — 악보 분석이 PdfBox 엔진에서 **905MB 할당**(토큰화 633MB)하던 것을 경량 콘텐츠 해석기로 교체 → **19MB, 7.4초 → 0.19초**. 전 페이지 박스 대조 테스트. **Z18TV Pro 실기기 8쪽 556ms**, 실기기에서 끝세로줄 가짜 마디·BOM 없는 UTF-8 작성자 깨짐 발견해 수정(DB v8)

## 🔄 업데이트 (2026-09)
- [`054`](20260926_054_in_app_update.md) — **앱 안에서 업데이트**: 설정 → 앱 정보 → 업데이트 확인. GitHub `releases/latest` 조회 → `-release.apk` 다운로드(cacheDir) → 에셋 SHA-256 digest 검증 → "출처를 알 수 없는 앱" 허용 → `FileProvider` + `ACTION_VIEW` 로 시스템 설치 화면

## 🎻 합주 메트로놈 · 악보 분석 확장 (2026-09)
- [`055`](20260926_055_ensemble_metronome_sync.md) — **합주 메트로놈 · 악보 연동 동기화**: 핑퐁으로 지휘자와의 시계 차이를 재고(RTT 최소 표본), 지휘자가 "박 0 = 지휘자 시계 T0" 시간표를 방송 → 연주자는 같은 시간표를 자기 시계로 읽어 박 · 현재 마디 · 넘김을 계산. 일시정지 · 마디 고르기 커서도 동기화, 연주자는 소리만 고름. **Z18TV Pro 2대 실측: 마디 전환 차이 중앙값 2.5 ms, 최대 한 프레임**
- [`056`](20260926_056_musescore_score_analysis.md) — **MuseScore PDF 악보 분석**: Clair de Lune(기타 2대) 0마디 → 72마디 · 9/8. 끊어 그린 오선 합치기, 연결선으로 시스템 나누기, 이어 그린 마디선, 보표 2개 시스템, SMuFL 음표 머리 · 박자 숫자. 몰다우 골든 불일치 0. DB v12 로 이전 분석 캐시 비움. 실기기 확인

## 🥁 메트로놈 다듬기 · 합주 (2026-09-27)
- [`057`](20260927_057_tempo_sections.md) — **메트로놈 대화상자 단순화 · 구간별 빠르기**: 첫 화면은 박자 · 속도 · 소리 크기, 나머지는 "상세…". 악보 박자가 바뀌는 곳마다 구간(이어받기 · 세는 단위), 악보 연동 · 합주까지. DB v13
- [`060`](20260927_060_count_in_two_bars.md) — **예비박 두 마디(기본) · 한 마디 선택**, 화면 가운데 남은 마디 수(2 → 1), 합주 연주자도 같게
- [`061`](20260927_061_ensemble_version_check.md) — **합주 연결 때 버전 확인** — 다르면 양쪽에 업데이트할 기기를 가리키는 안내

## ☁️ ScoreMate 클라이언트 (2026-09-26 ~ 28)
- [`P05`](20260926_P05_scoremate_client_plan.md) — **계획**: TV 기기 연결 · 악보 동기화 · 합주 파일 맞추기 · 세트리스트 · 분석 공유
- [`P06`](20260927_P06_scoremate_server_requests.md) — **서버에 요청할 것 · 서버 알림**(서버 세션이 함께 고친다): 이름 변경 경로, 기기별 동기화 범위(§7 · §9), 편곡자(§10), MusicXML(§11), 보표 · 마디 분석 파일(§12)
- [`058`](20260927_058_scoremate_device_link.md) — **C1 기기 연결**: QR · 코드(RFC 8628) → 토큰(백업 제외), 갱신은 한 번에 하나, heartbeat, 해제
- [`059`](20260927_059_scoremate_score_sync.md) — **C2 악보 동기화**: `PDFs/ScoreMate/<앙상블 | 내 악보>/`, 302 서명 URL · SHA-256 검증, 새 판 교체, 이름 규칙, 옮길 때 레코드 먼저. DB v14
- [`062`](20260927_062_file_source_tabs.md) — 파일 목록 탭(#063 에서 걷어냄) · 합주로 받은 파일 저장 위치 수정 · 동기화 버튼
- [`063`](20260927_063_one_library_and_ensemble_cache.md) — **한 번에 한 서재**(연결 = ScoreMate 악보만, 웹서버 끔), 합주 파일을 **내용 SHA-256** 으로 찾고 없으면 캐시, 경로 공격 수정
- [`064`](20260928_064_setlists_on_tv.md) — **세트리스트 보기**: 곡 순서 · 번호 · 메모, 이전/다음도 곡 순서. §4 연결된 TV 는 세트리스트로만(v0.2.9)

## 🎼 파트보 · 반주 연습 · 서버 분석 (2026-09-28, v0.2.9 ~ v0.3.3)
- [`P07`](20260928_P07_part_view_plan.md) — **계획 · 단계별 결과**: 파트보 보기(분석 · 파트 PDF · 소속에 따라 넓혀 자르기 · 여러 파트 · 마디 연동 · 합주), MusicXML 반주 연습, 열린 질문(빈 보표 숨긴 총보 · 셈여림 · 세로 태블릿)
- [`065`](20260928_065_part_view_accompaniment_server_analysis.md) — **요약**: v0.2.9 ~ v0.3.3 다섯 릴리스 — 세트리스트로만 · 10분마다 업데이트 확인 · 파트보 · MusicXML 동기화 · 반주 · 서버 분석 받아 쓰기 · 목록 곡 정보(DB v15 ~ v18)

## 📱 세로 태블릿 (2026-09-28)
- [`066`](20260928_066_tablet_support.md) — **1 · 2단계**: 같은 APK 로 태블릿 설치(leanback 필수 아님 · LAUNCHER), TV 가 아니면 세로 · 세로는 늘 한 쪽, 넘김 페달 키, 터치(탭 · 밀기 · 두 번 탭 · 길게 · 마디 탭). 함께: 합주 버전 경고 오판 · 두 쪽 짝 맞춤 수정, 파트보에 파트 이름. 샤오신패드 12.7 실기기

## 🎤 다음 구상 (2026-09-28)
- [`P08`](20260928_P08_mic_score_following_plan.md) — **마이크로 연주를 듣고 악보 위치 따라가기**(태블릿): 0 음량 · 온셋 → 1 박 · 템포 → 2 리듬 맞추기 → 3 음높이(단선율) → 4 **크로마 + 온라인 DTW**(화음, 기타 주력 후보) → 5 확률 추적 · 되돌아가기 → 6 다성 전사(basic-pitch). A단계: 녹음 도구(§6) · 합성 총보 실험 — 오프라인 100%, 온라인 OLTW + 재위치 ±1박 83 ~ 90%, 남은 오류는 반복 진입부 1 ~ 2마디 멈춤(§7). **다음: 실제 기타 녹음**(§8)

## 📚 메타
- [`039`](20260619_039_project_timeline.md) — 커밋 히스토리 기반 전체 개발 연대기

---

## 번호 체계 메모
- **NNN (구현/기록)**: 001부터 작성 순 연속. 날짜와 무관하게 증가.
- **PNN (계획/설계)**: P01부터 별도 연속. 계획→구현 매핑은 본문 cross-reference 참고.
- 파일명 형식은 `20260528_035_...` 부터 `_NNN_`(3자리)로 통일됨 (그 이전 `_NN_` → 일괄 변경, 커밋 `827d51a`).
