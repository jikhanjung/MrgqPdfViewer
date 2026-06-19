# devlog 인덱스

MrgqPdfViewer 개발 로그 모음. 구현 기록은 `YYYYMMDD_NNN_` 형식(NNN 은 작성 순
연속 번호, 날짜와 무관), 계획 문서는 `YYYYMMDD_PNN_` 별도 체계(P01 부터).

**기준 버전**: v0.1.12 (2026-05-30) · 최종 갱신: 2026-06-19

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

## 🎵 Unreleased — 합주 자동화 · 악보 분석 (2026-06)
- [`037`](20260613_037_score_analysis_work.md) — 벡터 PDF 악보 분석 작업 (data/, 앱 미통합)
- [`P02`](20260613_P02_score_sync_autoturn_plan.md) — 재생·자동 넘김·메트로놈·스코어 팔로잉 단계별 **계획**
- [`P03`](20260613_P03_clock_sync_design.md) — 클럭 동기화 **설계**
- [`038`](20260614_038_phase0_sync_page_turn_implementation.md) — 합주 Phase 0 동기 페이지 넘김 **구현** (⚠️ 실기기 미검증)

## 📚 메타
- [`039`](20260619_039_project_timeline.md) — 커밋 히스토리 기반 전체 개발 연대기

---

## 번호 체계 메모
- **NNN (구현/기록)**: 001부터 작성 순 연속. 날짜와 무관하게 증가.
- **PNN (계획/설계)**: P01부터 별도 연속. 계획→구현 매핑은 본문 cross-reference 참고.
- 파일명 형식은 `20260528_035_...` 부터 `_NNN_`(3자리)로 통일됨 (그 이전 `_NN_` → 일괄 변경, 커밋 `827d51a`).
