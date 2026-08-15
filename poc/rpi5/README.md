# Phase A PoC — Pi OS Lite 에서 4K 네이티브 PDF 표시

검토 문서: [`devlog/20260815_P04_rpi5_appliance_review.md`](../../devlog/20260815_P04_rpi5_appliance_review.md)

이 PoC 는 앱을 만드는 게 아니라 **전제 하나를 검증**한다:
> "4K 네이티브가 현재 Android 기기의 1080p 대비 실제로 눈에 띄게 나은가?"

여기서 차이가 미미하면 리눅스 전용기 검토는 그 자리에서 종료다.

---

## 1. 데스크톱 없이 그래픽이 되는 이유

Pi OS **Lite** 에는 X11 도 Wayland 도 데스크톱도 없다. 하지만 그래픽 출력은 별개다:

- 커널의 **DRM/KMS 드라이버**(`vc4-kms-v3d`, Bookworm 이후 기본 활성)가 `/dev/dri/card*` 를 제공한다.
- **SDL2 의 KMSDRM 백엔드**가 이 장치에 직접 그린다. 윈도우 시스템도 컴포지터도 필요 없다.

오히려 이게 우리에게 유리하다. devlog #042 에서 확인했듯 얇은 선의 선명도는 비트맵이
**정수 좌표에 1:1 로 놓일 때만** 유지되는데, 합성 계층이 아예 없으면 재샘플링이 끼어들 여지가 없다.

## 2. 설치 — 라이브러리 추가가 전부

```bash
sudo apt update
sudo apt install -y python3-venv libsdl2-2.0-0

python3 -m venv ~/mrgq-venv
~/mrgq-venv/bin/pip install pymupdf pygame-ce
```

- `pymupdf`, `pygame-ce` 모두 **aarch64 휠이 있어 컴파일 없이 설치**된다.
- Bookworm 이후 PEP 668 때문에 시스템 python 에 직접 `pip install` 이 막혀 있다 → venv 사용.
- 라이선스: MuPDF 는 AGPL v3. 개인 사용이면 무관하고, 배포 계획이 생기면
  `pypdfium2`(BSD)로 갈아타면 된다 (devlog #041 측정상 4K 에선 화질 차이 무시 가능).

## 3. 실행

```bash
# Pi OS Lite (콘솔에서 바로)
SDL_VIDEODRIVER=kmsdrm ~/mrgq-venv/bin/python show4k.py 악보.pdf

# 데스크톱이 있는 환경에서 미리 시험할 때는 변수 없이 (창으로 뜸)
python show4k.py 악보.pdf
```

권한: DRM 장치 접근에 `video`/`render` 그룹이 필요하다. 기본 사용자는 대개 이미 속해 있고,
아니면 `sudo usermod -aG video,render $USER` 후 재로그인.

### 조작
| 키 | 동작 |
|---|---|
| → ↓ Space | 다음 페이지 |
| ← ↑ | 이전 페이지 |
| `d` | 두 페이지 모드 토글 |
| `g` | **1픽셀 검정 기준선** 오버레이 (오선과 나란히 비교용) |
| `i` | 렌더 시간 통계 출력 |
| Esc `q` | 종료 |

## 4. 확인할 것

실행 직후 콘솔에 찍히는 값부터 본다:

```
드라이버      : kmsdrm
화면 모드     : 3840x2160        ← 여기가 1920x1080 이면 4K 가 안 잡힌 것
PDF           : 악보.pdf  (13페이지)
페이지 크기   : 595.3 x 841.9 pt
p1 1528x2160 scale 2.566 34.2ms
```

- [ ] **화면 모드가 3840x2160 인가** — 아니면 HDMI 케이블(2.0 이상)/모니터 입력 설정 확인
- [ ] **오선·슬러가 1080p 대비 눈에 띄게 나은가** ← 이게 핵심 판정. 같은 악보를 현재 기기와 나란히
- [ ] **`g` 키 기준선과 비교** — 렌더된 오선이 1픽셀 기준선만큼 또렷한가
- [ ] **렌더 시간** (`i` 키) — 40ms 내외면 프리렌더 캐시로 체감 0
- [ ] **전원** — §5
- [ ] **발열/소음** — `vcgencmd measure_temp`, 패시브 케이스로 버틸 수 있는지
- [ ] **부팅 시간** — `systemd-analyze`

## 5. 전원 — 배터리 구동 예산 확인

현행 셋업은 배터리 2개로 모니터+기기를 구동하는 휴대 구성이다. 급전 가능 여부보다
**소비 전력이 배터리 지속시간에 주는 영향**과 **강제 종료에 의한 SD 손상**이 실질 관심사다.

**Pi 5 는 5V 를 요구한다.** PD 소스가 9V/12V/20V 로 협상하려 들면 안 된다. 확인 순서:

1. 모니터 USB-C/USB-A 포트의 **출력 스펙**을 매뉴얼에서 확인 (5V 몇 A 인가)
2. Pi 5 는 공식적으로 5V/5A(27W)를 요구하지만, 그건 USB 주변장치까지 물렸을 때의 최악값이다.
   **5V/3A(15W)면 부팅·동작한다** (USB 주변장치 전류가 600mA 로 제한된다는 경고가 뜨지만
   우리 구성은 HDMI 출력 + 내장 BT 뿐이라 무관).
3. 실제 소비는 더 낮다: 아이들 3~4W, 렌더 버스트 시 6~8W 예상. **USB 전력계로 실측할 것.**
4. 케이블에서 전압강하가 나면 브라운아웃이 난다 — **짧고 굵은(20AWG) USB-C 케이블** 사용.

```bash
# 저전압 경고가 있었는지 확인 (0x0 이면 정상)
vcgencmd get_throttled
# 부팅 후 dmesg 에서도
dmesg | grep -i -E "under-?voltage|low voltage"
```

USB 전력계로 아이들/렌더 버스트 소비를 실측해 둘 것. 현행 Google TV Streamer(~3~4W) 대비
+2W 내외면 시스템 전체(모니터 15~25W 지배)에서 8% 안팎의 지속시간 감소라 수용 가능하다.

### ⚠️ 강제 종료 대비는 필수
배터리 구동 = 전원을 그냥 끊는다. **overlayfs 읽기전용 rootfs 를 반드시 켤 것**
(`sudo raspi-config` → Performance → Overlay File System). PDF/설정만 별도 쓰기 파티션에 둔다.
