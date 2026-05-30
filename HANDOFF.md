# HANDOFF — 현재 작업 인계 노트

마지막 갱신: 2026-05-30
브랜치: `main` (origin/main 보다 4 커밋 앞섬 + P2 변경 미커밋)
대상 버전: **v0.1.11** (현재 main 의 build.gradle.kts 값, P2 빌드 전 v0.1.12 로 올릴 것)

---

## 0. 방금 끝낸 작업 — P2 (2026-05-30)

### 배경
- P01 (v0.1.11) 실기기 검증: **두 페이지 모드에서 "맨 아래 오선만 흐려지는" 현상 잔존** (IMG_2609 매크로). P01 의 두께 균일성 개선과 별개 증상.
- 원인: PDF vector y × scale 의 fractional part 가 0.5 근처인 줄이 두 픽셀 행에 50/50 분배 → 회색. oversample 2.5× 로는 dark coverage 0.83 까지만 회복.

### P2-A (실패, 1차 시도)
- 단순히 `OVERSAMPLE_FACTOR 2.5 → 4.0` 만 변경.
- **결과: 즉시 크래시.** `Canvas: trying to draw too large(132710400bytes) bitmap` — RecordingCanvas 의 ~100MB 한계 초과 (7680×4320×4 = 132MB 비트맵을 ImageView 에 직접 draw 시도).
- OOM 이 아니라 GPU/Canvas 텍스처 한계 이슈.

### P2-B (현재 적용)
**아키텍처 변경**: oversample 렌더 직후 화면 크기로 createScaledBitmap 다운스케일. 캐시/ImageView 는 화면 크기 비트맵만 본다.

- `PageCache.renderPageToTargetBitmap`: render → createScaledBitmap(display 크기) → oversample bitmap recycle → display bitmap 반환.
- `PdfViewerActivity.renderPageAtSinglePageTarget`, `renderPageAtTwoPageTarget`: 동일 패턴 적용.
- `combineTwoPagesUnified`: 입력은 화면 절반 영역에 fit 된 display 크기 비트맵. oversample 좌표계 제거. 결과 `screenWidth × pageHeight` (≤ FHD).
- `setImageViewMatrix`: 코드 변경 불필요 (bitmap 크기 기반 동적 scale 계산이라 자동으로 ≈1.0 적용).
- `OVERSAMPLE_FACTOR = 4.0f` 유지 (transient 렌더 단계에서만 사용).

### 트레이드오프
- **캐시 메모리**: P01 (oversample 2.5× × 6장) ≈ 300MB → P2-B (display × 6장) ≈ **50MB**. 오히려 줄어듦.
- **Transient 메모리**: 페이지 렌더 순간 oversample bitmap (~132MB) 할당 → 즉시 recycle. Native heap 에 할당되며 ImageView 에는 절대 안 넘어감 → Canvas 한계 우회.
- **렌더 CPU 시간**: `createScaledBitmap` (bilinear, native skia) 추가 → 4× downscale 시 100~200ms 예상 (실측 필요). 캐시 히트 시 추가 비용 0.
- **시각 효과**: bilinear 4:1 downscale 이라 dark coverage 는 box filter 대비 약간 떨어질 수 있음. P2-A 이론치 0.94 보단 낮지만 P01 (2.5×) 보단 확실히 개선.

### 검증 (다음 작업자 1순위)
1. Windows Android Studio 에서 `assembleDebug` → 설치.
2. 두 페이지 모드로 같은 PDF 띄우고 IMG_2609 와 동일 각도/거리 매크로 사진. **모든 오선 5줄이 균일하게 진한 검정** 인지 확인.
3. 50+ 페이지 PDF 끝까지 넘겨보기 → 크래시/OOM/메모리 폭주 없는지. `adb logcat -s art PageCache PdfViewerActivity | grep -iE "gc|memory|outofmemory|canvas"`.
4. 첫 페이지 진입 체감 속도 (createScaledBitmap 추가로 +100~200ms 예상; 너무 느리면 검토).
5. 단일 페이지 모드, 크롭/여백 슬라이더, 페이지 전환 애니메이션, 합주 모드 동기화 회귀 없는지.

### 분기 판단
- **모두 정상** → 커밋, 버전 v0.1.12 로 올리고 푸시.
- **여전히 오선 흐림** → bilinear 가 box filter 만큼 dark 보존 못 함. (a) Canvas+Paint 로 multi-step downscale, (b) RenderScript Toolkit 의 resize, (c) P5 (PDFium) 중 선택.
- **첫 페이지 진입 1s+** → bilinear downscale 이 병목. (a) oversample 3.0 후퇴, (b) downscale 을 백그라운드 prefetch 와 같은 코루틴에서 미리 수행 등.
- **다시 크래시** → 다른 종류 한계. logcat 보고 분석.

### 미커밋 변경
- `app/src/main/java/com/mrgq/pdfviewer/PageCache.kt` (P2-B 적용)
- `app/src/main/java/com/mrgq/pdfviewer/PdfViewerActivity.kt` (P2-B 적용)
- `CLAUDE.md` (4× oversample 언급)
- `HANDOFF.md` (이 문서)
- `devlog/20260530_036_P2_oversample_bump.md` (P2-A 1차 시도 기록, P2-B 후속 섹션 추가됨)

---

## 1. 그 전 작업

### P01 — 두 페이지 모드 오선 렌더링 개선
- 커밋 `0f0c567` — `fix: 두 페이지 모드 오선 두께 불균일 문제 해결 (P01)`
- 커밋 `5929bde` — `chore: dead code 정리 (PdfPageManager) 및 devlog/CLAUDE.md 갱신`

### 핵심 변경
- `PdfViewerActivity.combineTwoPagesUnified`: Canvas `postScale` 제거. 두 페이지 모드의 2단계 fractional scaling 안티패턴 → 단일 단계 oversample.
- `PdfPageManager.renderPageToTargetBitmap` (신설): 단일 단계 Matrix 렌더로 통합. 크롭을 `Matrix.postTranslate` 로 vector 변환에 흡수.
- `PdfViewerActivity` 에 `renderPageAtSinglePageTarget` / `renderPageAtTwoPageTarget` 헬퍼 신설. PageCache 와 같은 공식 사용 → 캐시 히트/미스 결과 좌표계 일치.
- `applyDisplaySettings` 함수 (Activity, PageCache 양쪽) 완전 제거.
- `OVERSAMPLE_FACTOR = 2.5f` 상수로 단일화 (PageCache.companion).
- `PdfPageManager.kt` 삭제 (v0.1.8 작성 후 통합 안 된 dead code).
- 코드 순 −400 라인.

자세한 분석/근거는 `devlog/20260528_035_staff_line_rendering_fix_implementation.md`.

---

## 2. 다음 작업자가 가장 먼저 해야 할 일

**실기기 검증.** WSL2 에서 gradle 빌드 못 하므로 Windows 의 Android Studio 에서:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 검증 체크리스트

| 항목 | 확인 포인트 |
|---|---|
| 두 페이지 모드 오선 균일성 | **이번 작업의 본 목표.** 가는/두꺼운/진한/흐린 줄이 섞여 보이던 현상이 사라져야 함. FHD TV 또는 1080p 출력 환경에서 가장 잘 보임. |
| 단일 페이지 모드 회귀 없음 | 단일 페이지는 기존에도 정상이었음. 같은 헬퍼를 거치게 되었으므로 시각적 동일성 확인. |
| 크롭 슬라이더 동작 | 위 0/5/10/15%, 아래 동일. 슬라이더 변경 시 즉시 반영 (PageCache 자동 invalidation), 크롭된 영역이 화면 전체로 확대 (Option A). |
| 중앙 여백 슬라이더 | 0~15%. 두 페이지 사이 간격이 정확히 반영되고 페이지가 각 절반 영역 가운데 배치. |
| 마지막 홀수 페이지 | 왼쪽에 표시. |
| 페이지 전환 애니메이션 | 350ms 슬라이드 (renderPageDirectly 경로) 정상. |
| 합주 모드 | 페이지 변경 동기화 정상 (렌더와 무관하므로 영향 없을 것이지만 한 번 확인). |

문제 발견 시 `adb logcat -s PdfViewerActivity PageCache` 로 로그 확인.

---

## 3. 알려진 미해결 / 한계

### 3-1. 외부 환경 한계 (앱 코드로 해결 불가)
- Google TV Streamer 1080p 출력 → QHD 모니터 1.333× 업스케일. EDID 협상이 4K 출력 막음.
- 해결책은 사용자 측 (모니터 sharpness/HDR/Noise Reduction OFF, 또는 1:1 픽셀 모드, 또는 UHD 모니터로 교체).
- README/설정 화면 도움말에 가이드 추가 필요 (P4, 미착수).

### 3-2. 코드 cleanup 남은 항목
- `calculateOptimalScale` (PdfViewerActivity 945줄 부근) — 더 이상 실제 렌더에 사용 안 되지만 호출부 호환을 위해 시그니처만 유지. PageCache.updateSettings 가 받는 scale 값도 PageCache 내부에서 무시됨. 여러 호출부 (line 238, 252, 1083, 1096, 1197, 1211, 1773, 1782) 같이 정리 가능.
- `PageCache.renderScale`, `PageCache.settingsChanged` — 더 이상 읽히지 않는 필드. 컴파일에는 문제 없음.

### 3-3. v0.2.x 로드맵 미착수
- 마우스 포인터 공유, 하이라이트/주석, 음성 채팅 등. `devlog/20250727_027_v0.2.x_roadmap.md` 참고.

---

## 4. P01 후속 (실기기 검증 후 결정)

오선 품질이 충분히 개선됐으면:
- P2 (oversampling 강화) 불필요
- P4 (사용자 환경 가이드) 만 추가

부족하면:
- **P2**: OVERSAMPLE_FACTOR 를 2.5 → 3.0 또는 동적. 메모리 영향 실측 필요 (Google TV Streamer 메모리에서 4K 캐시 6장 = ~200MB 감당 가능한지).
- **P5 (장기)**: PDFium / MuPDF 도입. PdfRenderer 의 stroke alignment 제어 불가 한계 회피. 빌드 크기 / 통합 비용 큼.

하드웨어 시나리오 결정 대기 중 (QHD vs UHD 모니터).

---

## 5. 환경 메모

- 코딩: WSL2 + Claude Code
- 빌드/테스트: Windows 11 Android Studio
- 타깃: Z18TV Pro (Android TV, FHD), 향후 Google TV Streamer + 외부 모니터
- 미푸시 커밋 2개. 푸시 전에 빌드 검증 권장.
