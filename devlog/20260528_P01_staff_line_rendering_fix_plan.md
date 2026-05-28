# FHD TV에서 악보 오선 불균일 렌더링 문제 분석 및 개선 계획

작성일: 2026-05-28
대상 버전: v0.1.10 → v0.2.x
상태: 📋 계획 수립 (구현 미착수)

---

## 1. 증상

FHD(1080p) TV 환경에서 PDF 악보를 표시할 때 오선(staff line)이 다음과 같이 보이는 문제가 보고됨.

- 오선 일부는 두껍고 일부는 가늘게 보임
- 줄 두께가 균일하지 않음 (어떤 줄은 진하고 어떤 줄은 흐림)
- 페이지 전환 / 모드 전환 시 두께가 달라지는 경우 있음
- PC 모니터에서는 덜 두드러지지만 TV / Google TV 출력 시 두드러짐

사용자 실사용은 **두 페이지 모드** 에 집중되어 있음 (단일 페이지 모드는 거의 사용 안 함).

---

## 2. 환경 / 하드웨어 제약

### 2-1. 현재 → 전환 후 (검토 중인 시나리오)

| 항목 | 현재 (Z18TV Pro) | 시나리오 A | 시나리오 B (대안) |
|---|---|---|---|
| Android TV 소스 | Z18TV 내장 Google TV | Google TV Streamer | Google TV Streamer |
| 디스플레이 | Z18TV 내장 패널 | UPerfect 24" QHD | UPerfect 23.8" UHD |
| 디스플레이 네이티브 | (FHD) | 2560×1440 | **3840×2160** |
| 스트리머 출력 (실제) | (FHD) | **1080p 고정** | **4K 네이티브** |
| 모니터 측 스케일링 | (없음 추정) | 1.333× 업스케일 강제 | **없음 (1:1)** |
| PPI | 낮음 | ≈ 122 | **≈ 185** |

### 2-2. 시나리오 A 의 핵심 제약: 해상도 미스매치

- Google TV Streamer 는 UHD(3840×2160) 또는 FHD(1920×1080) 만 출력 가능.
- UPerfect QHD 패널은 2560×1440 네이티브 → 두 해상도 모두 미스매치.

| 스트리머 출력 | 모니터 측 스케일 | 얇은 선 영향 |
|---|---|---|
| 1080p | 1.333× 업스케일 | 불균일 두꺼움 (최악) |
| 4K | 0.667× 다운스케일 | 좋은 스케일러면 보존 (상대적 양호) |

**2026-05-28 확인 사실**: QHD 모니터 연결 시 Google TV Streamer 가 4K 출력으로 전환 불가 (EDID 협상 결과로 추정 — 패널이 1440p 만 보고, 스트리머가 1080p 로 fallback). 결과적으로 **1080p 출력에 갇힌 상태 = 원본 분석 문서의 최악 시나리오와 정확히 일치**.

→ 시나리오 A 에서는 모니터 단 fractional scaling 이 **앱 코드로 해결 불가**.
→ 앱이 할 수 있는 일은 HDMI 로 나가는 1080p 프레임을 최대한 깨끗하게 만드는 것까지.

### 2-3. 시나리오 B 의 의미: 문제의 대부분이 하드웨어로 해결됨

UHD 모니터로 갈 경우 Streamer 4K 출력이 패널 네이티브와 일치 → 모니터 측 스케일링 없음 → §3-1 의 1·2 항목 (fractional scaling, pixel alignment) 이 디스플레이 단에서 자동 해소.

추가로 23.8" 4K ≈ **185 PPI** 라 정상 시청 거리에서 픽셀 자체가 거의 안 보임 → 잔존 artifact 도 가려짐.

트레이드오프:
- **장점**: P2 (oversampling), P4 (TV 보정 가이드), P6 (1:1 모드) 가 거의 불필요해짐. 개발 작업이 P1 한 가지로 축소.
- **단점**:
  - UHD 모니터 비용 증가
  - 앱이 4K 로 렌더 → 비트맵 메모리 1080p 대비 4배 (1080p × ARGB8888 × 6 페이지 × 4배 ≈ 50MB → 4K 환경에서 200MB대). Google TV Streamer 메모리에서 감당 가능한지 실측 필요.
  - 4배 픽셀 → PdfRenderer 렌더 시간 증가, 페이지 전환 체감 영향 가능. PageCache 프리렌더링이 더 중요해짐.

**의사결정 분기**: 하드웨어 확정 전에는 P2/P4/P6 의 작업 가치가 불확정. 시나리오 B 채택 시 그쪽 작업 모두 보류 가능.

---

## 3. 원인 분석

### 3-1. 일반론: 얇은 vector line 이 TV 파이프라인에서 깨지는 매커니즘

1. **Fractional scaling**: 원본 1px 선이 1.3px / 1.7px 로 스케일되면 어떤 줄은 1px, 어떤 줄은 2px 로 떨어져 두께가 불균일.
2. **Pixel alignment**: 선이 0.5px / 1.3px 같은 위치에 걸리면 anti-aliasing 이 비대칭으로 들어가 줄마다 진하기가 달라짐.
3. **TV 영상 보정**: Sharpness, Edge Enhancement, Dynamic Contrast, Noise Reduction 등이 얇은 흑백선을 망가뜨림.
4. **저품질 PDF rasterization**: 저해상도 비트맵 → 확대 / GPU texture scaling 의존 방식은 line jitter 와 uneven thickness 를 유발.

§2-2 의 모니터 강제 업스케일은 위 1·2 항목에 직접 해당.

### 3-2. 우리 앱 코드에서 확인된 구체적 원인

코드 리뷰 결과 두 가지 안티패턴이 실제로 존재.

#### (a) 두 페이지 모드: 2단계 스케일링 (가장 큰 의심 포인트)

`PdfPageManager.kt` 의 `renderTwoPages()` (라인 235-264), `combineTwoPages()` (라인 269-298), `renderSinglePageOnLeft()` (라인 195-230) 가 모두 다음 패턴을 사용.

```kotlin
// 1단계: PDF 네이티브 해상도(예: 612×792)로 렌더링
val leftBitmap = Bitmap.createBitmap(leftPage.width, leftPage.height, ARGB_8888)
leftPage.render(leftBitmap, null, null, RENDER_MODE_FOR_DISPLAY)

// 2단계: createScaledBitmap 으로 다운/업스케일
val leftScaled = Bitmap.createScaledBitmap(leftBitmap, scaledWidth, scaledHeight, true)
```

PDF 네이티브 픽셀로 한 번 래스터화 → 비트맵 단계에서 fractional 비율로 또 스케일.
이게 §3-1-1 의 fractional scaling 시나리오를 앱 내부에서 만들어냄.

반면 `renderSinglePageDirect()` (라인 172-190) 은 `PdfRenderer.render(bitmap, null, matrix, ...)` 에 Matrix 를 직접 전달해 단일 단계로 목표 픽셀에 래스터화. 단일 페이지 모드는 이 문제에 영향 적음 (다만 사용자가 단일 모드를 거의 안 쓰므로 비교 검증은 어려움).

#### (b) Oversampling 부재 + 0.9× 마진

`PdfPageManager.calculateOptimalScale()` (라인 330-338):

```kotlin
return min(scaleX, scaleY) * 0.9f  // 90% to leave some margin
```

- **CLAUDE.md 와 실제 코드 불일치**: 문서에는 "2-4x 스케일링으로 선명한 이미지" 라고 적혀 있으나, 현재 코드는 화면 맞춤의 90% 로 렌더. v0.1.5 PageCache 도입 때 "스마트 스케일 계산" 으로 변경되면서 oversample 이 사실상 사라진 것으로 추정.
- **0.9× 마진의 2차 fractional scaling 위험**: 렌더 비트맵이 화면보다 작게 나오므로, 표시 단계에서 ImageView 가 다시 스케일하면 fractional scaling 이 한 번 더 발생 가능.

#### (c) Pixel snapping 불가 (구조적 한계)

`PdfRenderer` 는 블랙박스이므로 stroke alignment / horizontal line snapping 을 직접 제어 불가. 다만 oversample + 정수 픽셀 다운스케일로 효과를 흉내낼 수 있음.

---

## 4. 해결 전략

§2-2 제약으로 인해 **앱 코드 (P1~P3, P5) + 사용자 환경 (P4, P6, P7)** 의 양쪽에서 접근해야 함.

### 🔴 P1. 두 페이지 모드 단일 단계 렌더로 전환 (최우선)

`renderTwoPages`, `combineTwoPages`, `renderSinglePageOnLeft` 에서 `createScaledBitmap` 을 제거하고, `PdfRenderer.Page.render(bitmap, null, matrix, RENDER_MODE_FOR_DISPLAY)` 에 Matrix 를 직접 전달하여 한 번에 목표 픽셀로 래스터화.

- 영향 파일: `PdfPageManager.kt`
- 위험: 낮음 (단일 페이지 모드와 동일 패턴)
- 효과: 앱 내부 fractional scaling 1회 제거 → 두 페이지 모드 오선 품질 개선
- 정당화 근거: 사용자가 두 페이지 모드 중심으로 사용하므로 효과 직접 체감.

### 🔴 P2. Oversampling 도입

`calculateOptimalScale` 에 oversample factor (1.5× 또는 2.0×) 를 추가, 최종 출력은 고품질 다운스케일.

- 영향 파일: `PdfPageManager.kt`, `PageCache.kt`
- 위험: 메모리 증가 — Google TV Streamer 는 Z18TV 대비 스펙이 좋아 부담 완화. 1080p × ARGB8888 × 6 페이지 × 4배 ≈ 200MB대, 실측 필요.
- 효과: aliasing 감소, 오선 두께 안정화. 1080p 출력에 갇힌 환경에서는 HDMI 로 내보내는 1080p 프레임 자체의 품질을 끌어올리는 핵심 수단.
- 1080p 출력 고정이라는 §2-2 제약 때문에 우선순위 격상.

### 🔴 P4. 사용자 환경 가이드 (격상)

모니터가 스케일러로 동작하는 환경이므로 모니터 / TV 영상 보정 OFF 가 특히 중요.

README 또는 설정 화면 도움말에 다음 안내:

- **Sharpness 0 또는 최소** (가장 효과 큼)
- HDR / Dynamic Contrast / Noise Reduction OFF
- Overscan → Just Scan / 1:1 Pixel 모드
- ~~가능하면 4K 출력~~ → §2-2 제약으로 현재 환경에서는 불가

근본 해결책은 아니지만, 코드로 해결되지 않는 영상 보정 문제는 사용자 측 설정으로만 잡을 수 있음.

### 🟡 P3. 0.9× 마진 제거 또는 레이아웃 차원으로 이동

화면 맞춤 마진은 렌더 단계가 아니라 layout (padding) 으로 표현. 렌더는 1.0× (또는 oversample 배율) 로 고정.

- 영향 파일: `PdfPageManager.kt`, 관련 layout XML
- 위험: 낮음
- 효과: ImageView 의 2차 스케일링 제거

### 🟡 P6. 모니터 1:1 픽셀 모드 검토 (사용자 환경)

UPerfect OSD 에 "1:1 Pixel" / "Pixel Perfect" / "Dot by Dot" / "Just Scan" 모드가 있는지 확인.
있다면 1080p 입력을 패널 중앙에 스케일 없이 1920×1080 영역으로 표시 (주변 letterbox).

- 장점: 모니터 스케일러 완전 우회 → §3-1 의 1·2 항목 통째로 해소 → 오선이 가장 깨끗
- 단점: 패널 약 56% 만 사용 (24인치 → 약 18인치급 영역), 두 페이지 모드 가독성 영향 가능
- 평가 방법: 사용자 시청 거리에서 18인치급 영역으로도 충분한지 확인

### ⚪ P7. 1440p 출력 가능한 소스 기기로 교체 (장기, 하드웨어)

Google TV Streamer 대신 1440p HDMI 출력을 지원하는 Android TV 기기 (NVIDIA Shield, 일부 미니 PC + Android TV 등) 로 교체하면 모니터 단 스케일링 자체 제거 가능.

- 트레이드오프: 가격, 폼팩터, OS 지원, 합주 모드 등 기존 기능 호환성 검토 필요
- P1 ~ P6 모두 적용 후에도 부족할 때 검토

### ⚪ P5. PDFium / MuPDF 도입 (장기, 코드)

위 P1 ~ P3 적용 후에도 오선 품질이 부족하면 그때 검토.

- minSdk 21 환경에서 native lib 통합 부담
- 빌드 크기 증가
- 현재 PdfRenderer 의존 파이프라인 (PageCache, PdfPageManager) 전면 재작성 필요
- 우선순위 낮음

---

## 5. 권장 진행 순서

### 5-1. 우선 결정 사항

**하드웨어 시나리오 A vs B 결정이 작업 범위를 크게 바꿈** (§2-1, §2-3).

- 시나리오 A (QHD) 확정 → 아래 전체 진행
- 시나리오 B (UHD) 확정 → **P1 만 진행**, P2·P4·P6 보류 (단 4K 렌더링 메모리/성능 실측 필요)
- 미정 → P1 만 먼저 (어느 쪽이든 의미 있음)

### 5-2. 시나리오 A (QHD) 또는 미정 시 진행 순서

1. **P1 (코드)** — 위험 낮고 효과 명확. 첫 착수 대상. 하드웨어 시나리오 무관하게 가치 있음.
2. **P4 (가이드)** — 코드 변경 없이 사용자 측에서 즉시 적용 가능. P1 과 병행.
3. **P6 (모니터 OSD 확인)** — 사용자 환경 차원. P1 결과를 보기 전에 확인 가능.
4. **P2 (oversampling)** — P1 + P4 + P6 적용 후에도 부족하면. 메모리 실측 후 도입.
5. **P3 (마진 정리)** — P2 와 묶어서 함께. 단독으로는 효과 작음.
6. **P5 / P7** — 위 단계 모두 적용 후에도 부족할 때만.

### 5-3. 시나리오 B (UHD) 확정 시 진행 순서

1. **P1 (코드)** — 4K 환경에서도 2단계 스케일링은 안티패턴. 단 fractional scaling 비율이 작아져 체감 차이는 시나리오 A 보다 작을 수 있음.
2. **4K 렌더 메모리/성능 실측** — 신규 작업. PageCache 사이즈 / OOM / 페이지 전환 지연 확인. 필요 시 캐시 사이즈 조정.
3. **P3 (마진 정리)** — 여전히 가치 있음.
4. P2 / P4 / P6 / P7 — 거의 불필요.

---

## 6. 미해결 / 추후 확인 항목

1. **하드웨어 시나리오 확정**: 시나리오 A (QHD) vs B (UHD). 비용 vs 개발 노력 trade-off (§2-3).
2. **시나리오 A 채택 시: UPerfect 24" QHD 1:1 픽셀 모드 지원 여부** (OSD 확인).
3. **시나리오 B 채택 시: 4K 환경 메모리/성능 실측** (PageCache 사이즈, OOM, 페이지 전환 지연).
4. **특정 PDF 의존성**: 모든 악보 PDF 에서 발생하는가, 스캔 이미지 PDF 에서만 발생하는가?
   - 벡터 PDF 만이라면 → P1 으로 거의 해결될 가능성
   - 스캔 이미지 PDF 도 동일하다면 → P2 필요성 더 큼 (시나리오 A 에서만 의미 있음)

---

## 7. 참고

- 코드 진단 근거: `app/src/main/java/com/mrgq/pdfviewer/PdfPageManager.kt`, `PageCache.kt`
- 관련 문서: `CLAUDE.md` 의 "고해상도 PDF 렌더링 (2-4x 스케일링)" 설명은 현재 코드와 불일치 — P2 적용 시 문서도 함께 갱신 필요. "Z18TV Pro" 단일 언급도 하드웨어 전환 완료 시 갱신.
- 관련 메모리: hardware-transition-plan, staff-line-rendering-issue
- 다음 단계: §5 권장 진행 순서대로 P1 부터 착수
