# HANDOFF — 현재 작업 인계 노트

마지막 갱신: 2026-06-12
브랜치: `main` (origin/main 과 동기화됨, 미푸시 커밋 0개)
대상 버전: **v0.1.12** (build.gradle.kts versionCode 12 / versionName 0.1.12, 커밋·푸시 완료)
미커밋 변경: `app/release/output-metadata.json` (릴리스 빌드 자동 산출물, 의미 없음)

> 오선(staff line) 렌더링 개선 작업(P01 → P2-A/B/C)이 일단락됨. P2-B 채택, P2-C 는 시도 후 revert.
> 모두 커밋·푸시 완료. 실기기(FHD)에서 dropout 해결 확인됨. QHD 모니터에서도 "그럭저럭 볼만한" 수준으로 사용 가능.

---

## 0. 방금 끝낸 작업 — P2 (커밋 완료)

> 아래 P2-A/B 의 "미커밋"·"검증 예정" 서술은 작성 당시 기준. **현재 P2-B 는 커밋 `47507d7` 로 적용·푸시 완료, P2-C 는 `2fce7ca` 에 revert 기록 완료.** 자세한 최종 결과는 §0.5 참조.

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

### 관련 커밋 (모두 푸시 완료)
- `47507d7` — `fix: 두 페이지 모드 오선 dropout 개선 (P2-B, v0.1.12)` (PageCache.kt, PdfViewerActivity.kt, CLAUDE.md, 버전)
- `2fce7ca` — `docs: P2-C multi-step downscale 시도 결과 + 외부 환경 한계 정리`
- 상세 기록: `devlog/20260530_036_P2_oversample_bump.md` (P2-A 실패 → P2-B 채택 → P2-C revert 까지 전 과정)

---

## 0.5. P2 최종 결과 (실기기 검증 후)

- **P2-B 채택 (`47507d7`)**: oversample 4× 렌더 → 화면 크기로 `createScaledBitmap` 다운스케일. 두 페이지 모드의 **오선 dropout(특정 줄 회색화) 해결 확인**. 캐시 메모리는 오히려 300MB→50MB 로 감소. 크래시/OOM 없음.
- **P2-C 시도 후 revert (`2fce7ca`)**: 잔존하는 미세 두께 불균일을 줄이려 multi-step bilinear 다운스케일을 시도했으나, 단일 bilinear 와 **시각 차이 거의 없음** → 복잡도/렌더 시간만 늘어 revert. 잔존 두께 차이의 진짜 원인은 다운스케일이 아니라 **PdfRenderer 의 vector→raster AA (line snap-to-pixel hinting 부재)** 로 결론. 더 줄이려면 P5(PDFium/MuPDF) 필요.
- **QHD 모니터**: 1.333× 업스케일 경유에도 실사용상 "그럭저럭 볼만한" 수준으로 확인됨. UHD 교체는 더 이상 시급하지 않음(§3-1 참조).

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

## 2. 다음 작업자가 할 수 있는 일

오선 렌더링 작업(P01~P2)은 일단락됨. P2-B 실기기 검증 통과·커밋 완료. 남은 선택지:

- **코드 cleanup** (§3-2): `calculateOptimalScale` 시그니처/dead field 정리. 저위험.
- **P4 — 사용자 환경 가이드** (§3-1): README/설정 도움말에 모니터 권장 사항 추가. 미착수.
- **P5 — PDFium/MuPDF** (§4): 잔존 두께 미세차까지 잡으려면. 장기·고비용.
- **v0.2.x 로드맵** (§3-3): 신규 기능.

회귀 재검증이 필요하면 WSL2 에서 gradle 빌드 불가하므로 Windows 의 Android Studio 에서:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 회귀 체크리스트 (코드 수정 시)

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
- **HDMI→USB-C 컨버터 시도 (2026-05-30)**: 컨버터 끼워서 4K EDID 가 협상되길 기대했으나 여전히 FHD 출력. 컨버터가 단순 FHD pass-through 이거나 끝단 모니터의 EDID 가 그대로 보임. 효과 없음.
- **현재 판단 (2026-06-12)**: QHD 모니터 + 1.333× 업스케일 환경에서도 P2-B 적용 후 **"그럭저럭 볼만한"** 수준으로 실사용 가능 확인. 1:1 매핑 환경(FHD TV 또는 UHD 모니터)에서 가장 깔끔하지만, **UHD 모니터 교체는 더 이상 시급하지 않음**.
- 더 완벽한 1:1 매핑을 원하면 UHD 모니터 교체가 최선. EDID emulator(4K 강제 주입)는 1.5× 다운스케일 단계가 또 들어가서 깔끔하지 않음.
- README/설정 화면 도움말에 가이드 추가하면 좋음 (P4, 미착수, 우선순위 낮음): "UHD 모니터 또는 FHD TV 권장. QHD 모니터 사용 시 sharpness/noise reduction OFF, 1:1 픽셀 모드 활성화."

### 3-2. 코드 cleanup 남은 항목
- `calculateOptimalScale` (PdfViewerActivity 945줄 부근) — 더 이상 실제 렌더에 사용 안 되지만 호출부 호환을 위해 시그니처만 유지. PageCache.updateSettings 가 받는 scale 값도 PageCache 내부에서 무시됨. 여러 호출부 (line 238, 252, 1083, 1096, 1197, 1211, 1773, 1782) 같이 정리 가능.
- `PageCache.renderScale`, `PageCache.settingsChanged` — 더 이상 읽히지 않는 필드. 컴파일에는 문제 없음.

### 3-3. v0.2.x 로드맵 미착수
- 마우스 포인터 공유, 하이라이트/주석, 음성 채팅 등. `devlog/20250727_027_v0.2.x_roadmap.md` 참고.

---

## 4. 장기 후속 — P5 (PDFium/MuPDF)

P2-B 로 dropout 은 해결됐고 QHD 에서도 실사용 가능. 다만 **줄 두께 미세 불균일**은 PdfRenderer 의 vector→raster AA 한계(line snap-to-pixel hinting 부재)라 다운스케일·oversample 로는 더 못 줄임(P2-C 가 이를 확인).

- **P5**: PDFium / MuPDF 도입. PdfRenderer 의 stroke alignment 제어 불가 한계 회피. line hinting 으로 근본 해결 가능하나 빌드 크기 / 통합 비용 큼. 현재 품질로 충분하다면 굳이 착수할 필요 없음.

---

## 5. 환경 메모

- 코딩: WSL2 + Claude Code
- 빌드/테스트: Windows 11 Android Studio (WSL2 에서 gradle 빌드 불가)
- 타깃: Z18TV Pro (Android TV, FHD), Google TV Streamer + QHD 외부 모니터
- main 은 origin/main 과 동기화 완료. 미푸시 커밋 없음.
