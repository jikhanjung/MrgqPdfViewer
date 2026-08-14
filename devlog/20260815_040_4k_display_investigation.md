# 4K 디스플레이 계단현상 조사 — 기기 한계로 종결

작성일: 2026-08-15
대상 버전: v0.2.x (선행 커밋 `822db08`, 2026-07-26)
상태: 🔴 **조사 종결 — 앱 수정으로 해결 불가 (기기 하드 제약)**
기기: Google TV Streamer (kirkwood, Android 14 / API 34) + UPerfect 23.8" 4K 모니터
관련: [`036`](20260530_036_P2_oversample_bump.md) (P2 oversample) · [`035`](20260528_035_staff_line_rendering_fix_implementation.md) (P01)

---

## 0. 요약 (결론 먼저)

Google TV Streamer 는 빌드 프로퍼티로 **SurfaceFlinger 그래픽 레이어 최대 크기를 1920×1080 으로
고정**해 두었다. 물리 패널은 3840×2160 이지만 앱이 그리는 세계는 영구히 1080p 이고,
그 결과가 SurfaceFlinger 에 의해 4K 로 업스케일되어 출력된다.

```
[ro.surface_flinger.max_graphics_width]:  [1920]
[ro.surface_flinger.max_graphics_height]: [1080]
```

`ro.` = read-only, 부팅 시 고정. **루팅 없이 변경 불가.**

따라서 슬러·오선의 계단현상은 렌더 품질 문제가 아니라 **1080p → 4K 업스케일(정확히 2×2 픽셀
블록 확대)** 이 원인이며, 앱 코드로 우회할 방법이 없다. 07-26 에 검토 항목으로 남겨둔
"4K SurfaceView 경로"도 이 제약 아래서는 성립하지 않는다(§4).

---

## 1. 발단

07-26 커밋 `822db08` 에서 4K 대응을 선반영하면서, 실제로 기기가 앱에 어떤 해상도를 보고하는지
판정하기 위해 `PdfViewerActivity.onCreate()` 에 `DISPLAY INFO` 로그를 심어두었다
(`getMetrics` → `getRealMetrics` 교체 포함). 이번에 실기기를 연결해 그 로그를 확인했다.

## 2. 1차 판정 — DISPLAY INFO

```
=== DISPLAY INFO === app=1920x1080 (320dpi), physicalMode=3840x2160@60Hz,
supportedModes=[1280x720@60, 1920x1080@60, 3840x2160@29, 3840x2160@30,
                3840x2160@60, 3840x2160@50, 1280x720@59, 1920x1080@59, 3840x2160@59]
```

- 앱 논리 해상도 **1920×1080** / 물리 출력 **3840×2160** → 시스템 다운스케일 확정.
- `supportedModes` 에 `3840x2160@60` 존재 → **케이블·모니터는 무죄**.

## 3. 2차 판정 — wm 오버라이드 시도 (실패)

### 3-1. `wm size 3840x2160` + `wm density 640`

적용되지 않았다. 화면 글씨만 2배로 커졌는데, 이는 `wm size` 는 무시되고 `wm density` 만 먹어
**1080p 화면에 640dpi** 가 걸린 결과였다 (960dp → 480dp 폭).

### 3-2. `wm size reset`

override 를 지우면 physical(4K)로 떨어질 것을 기대했으나, 조회하면 그대로였다:

```
$ adb shell wm size
Physical size: 3840x2160
Override size: 1920x1080     ← reset 후에도 살아남음
```

`Override size 1920x1080` 은 **사용자가 건 값이 아니라 기기가 출고 시부터 걸어둔 값**이고,
지워도 SurfaceFlinger 가 즉시 되돌린다.

### 3-3. `dumpsys display` — 두 세계의 분리

```
mBaseDisplayInfo    : real 3840 x 2160, density 640    ← 물리 패널
mOverrideDisplayInfo: real 1920 x 1080, density 320    ← 앱이 사는 세계
```

여기서 `getprop` 을 훑어 §0 의 `ro.surface_flinger.max_graphics_*` 를 발견하며 원인이 확정됐다.

### 3-4. 부수 발견 — 30Hz 로 출력 중

```
DisplayDeviceInfo: modeId 6, renderFrameRate 30.000002    (mode 6 = 3840x2160@30)
mOverrideDisplayInfo: refreshRateOverride 30.000002
```

지원 목록에 `3840x2160@60`(id=7)이 있는데 **30Hz** 로 잡혀 있다. 화질과는 별개지만 350ms
페이지 전환 애니메이션의 부드러움에 직접 영향을 준다. 화질 이슈와 분리해 별도로 다룰 것(§5).

---

## 4. SurfaceView 경로를 접는 이유

`SurfaceHolder.setFixedSize(3840, 2160)` 으로 버퍼만 4K 로 잡는 안을 검토했으나 성립하지 않는다.

- `max_graphics_*` 제한은 개별 레이어가 아니라 **논리 디스플레이 레벨**에 걸린다. SurfaceView
  버퍼를 4K 로 만들어도 최종 합성이 1080p 논리 디스플레이에서 일어나므로 다운스케일 →
  업스케일로 원위치한다.
- 4K 비디오가 4K 로 나가는 것은 하드웨어 디코더가 **HWC 오버레이 예외 경로**를 타기 때문이며,
  PDF 래스터(GPU/CPU 가 채우는 그래픽 버퍼)는 그 경로에 올릴 수 없다.
- 즉 이 기기 설계는 **"UI/그래픽 = 1080p, 4K = 비디오 레이어 전용"** 이다.

`CLAUDE.md` 에 남겨둔 "4K SurfaceView 경로 검토" 항목은 여기서 **종결**한다.

---

## 5. 후속 (실기기에서 A/B 로 확인할 것)

앱 수정 없이 시도할 수 있는 두 가지. 둘 다 설정 변경만으로 5분이면 비교 가능하다.

1. **4K 30Hz → 60Hz 고정** — 설정 → 디스플레이 및 소리 → 해상도. 애니메이션 부드러움 개선.
   30Hz 로 협상된 원인이 HDMI 케이블(2.0 미만)일 수 있으므로 케이블도 함께 의심.
2. **HDMI 출력을 1080p60 으로 내리기** — 역발상. 어차피 앱은 1080p 로 그리므로, 4K 신호로
   내보내 SurfaceFlinger 가 업스케일하게 두는 대신 1080p 신호를 그대로 내보내
   **모니터 자체 스케일러**가 확대하게 한다. 모니터 보간이 더 나으면 곡선이 부드러워질 수 있고,
   60Hz 도 덤으로 따라온다.

## 6. 그래도 남는 근본 한계

§5 로도 부족하면 선택지는 둘뿐이다.

- **1080p 안에서 렌더 품질 극대화** — P5(PDFium/MuPDF 교체)로 AA 품질 개선. 단 P01/P2 로
  PdfRenderer 한계까지는 이미 짜냈으므로 개선 폭은 제한적일 것으로 본다.
- **기기 교체** — UI 를 4K 로 렌더하는 박스(예: NVIDIA Shield TV Pro)로 바꾸면 앱 코드 변경
  없이 해결된다. 23.8" 4K 모니터에서 1080p 를 2×2 블록으로 확대해 보는 구조가 계단현상의
  진짜 원인이므로, 근본 해결은 결국 이쪽이다.

---

## 7. `822db08` 커밋의 처분 — 유지

이 기기에서는 발동하지 않는 코드지만 되돌리지 않는다.

- `getRealMetrics` 교체 / `DISPLAY INFO` 로그: **이번 판정을 5분 만에 끝낸 도구**. 향후 기기
  교체 시에도 첫 확인 수단으로 그대로 쓰인다.
- `PageCache.effectiveOversampleFactor()` (transient 비트맵 34MP 상한): 1080p 동작은 불변이며,
  4K 를 받는 기기에서 페이지당 ~210MB 할당을 막는 안전장치로 유효하다.

### ⚠️ 다만 4K 기기로 가면 함께 손봐야 할 것

`PageCache` 의 캐시가 **바이트가 아니라 페이지 수(6장) 기준**이다 (`PageCache.kt:20`).

| 화면 | 페이지당 비트맵 | 6장 합계 |
|---|---|---|
| 1080p | 1920×1080×4 ≈ **8.3MB** | ≈ 50MB |
| 4K | 3840×2160×4 ≈ **33MB** | ≈ **200MB** |

4K 논리 해상도를 받는 기기에서는 그대로 OOM 위험이다. **캐시를 바이트 기반(가용 heap 비율)으로
재설계**하는 작업이 4K 대응의 전제 조건으로 남는다.

---

## 8. 참고 명령

```powershell
# 연결 (Google TV Streamer, 개발자 옵션 → 네트워크 디버깅)
adb connect 192.168.55.31:5555

# 판정 3종
adb shell wm size
adb shell wm density
adb shell getprop | findstr surface_flinger
adb shell dumpsys display | findstr mOverrideDisplayInfo

# 앱 로그 (PDF 를 실제로 열어야 찍힘 — PdfViewerActivity.onCreate)
adb logcat -s PdfViewerActivity | findstr "DISPLAY INFO"

# wm 복구
adb shell wm size 1920x1080
adb shell wm density reset
```
