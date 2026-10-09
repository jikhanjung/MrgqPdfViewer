# 092 — 👂 계속 듣기의 딸깍 소리: 듣는 동안 알림 소리 끄기 (v0.7.4-beta.3)

2026-10-09 · #085 후속 · 사용자: "계속 듣기로 하니까 딸깍 소리가 나네" → (언제?) "몇 초마다" → 알림 소리 끄기를 고름
→ "계속 듣기는 효용성이 떨어지긴 하겠어. 테스트는 계속 해보겠지만 실제로는 꺼놓을 것 같긴 하네."

## 원인
태블릿 로그(2026-10-09 11:30)에서 확인했다. Google 음성 서비스(`com.google.android.tts`)는 듣기를 시작할 때마다 `AudioPlayer: Playing beep com.google.android.tts:raw/open` 을 울리고(못 알아들으면 `raw/failure`),
그 소리는 **알림 채널**로 난다(`AudioPlaybackConfiguration … usage=USAGE_NOTIFICATION_EVENT`).
👂 계속 듣기는 조용하면 몇 초 만에 듣기가 끝나("말 없음") 다시 걸리므로, 그때마다 이 소리가 딸깍처럼 들렸다.
이 시작음만 끄는 API 는 없다.

## 고른 것
- **알림 소리 끄기**(사용자 선택): 악보 화면에서 👂 가 듣고 있는 동안만 알림 채널을 음소거한다(`AudioManager.ADJUST_MUTE`)
  - 화면을 나가거나 👂 를 끄거나 🎤 연주 듣기로 바뀌면 되돌린다(`refreshVoiceButton` · `onPause` · `onDestroy`)
  - **우리가 끈 것만** 되돌린다. 원래 꺼져 있었으면 손대지 않는다
  - 앱이 도중에 죽어도 표시(`notification_muted_by_app`)를 보고 첫 화면 · 악보 화면이 돌아올 때 되돌린다
  - 기기가 막으면(방해 금지 접근 권한이 필요한 기기, `SecurityException`) 그 화면에서 한 번 "딸깍 소리가 날 수 있어요"를 띄운다
  - 알아 둘 것: 그동안 다른 앱 알림음도 나지 않는다. 휴대폰은 알림과 벨소리가 묶인 기기가 많아 전화가 진동으로 올 수 있다(설정 문구에 적음)
- 고르지 않은 것: Android 13 연속 인식(segmented session)으로 다시 거는 횟수 줄이기. Google 서비스의 지원 여부는 시험해야 안다

## 계속 듣기의 자리
사용자는 시험은 계속하되 실제로는 꺼 둘 것 같다고 했다.
🎙 누른 채 말하기가 기본 사용법이고, 👂 는 시험 기능(기본 꺼짐)으로 남긴다. 계속 다듬을지는 실사용을 보고 정한다(TODOs).

## 코드
`voice/NotificationMute.set(context, mute)`, `PdfViewerActivity.muteRecognizerBeep`, `MainActivity.onResume` 에서 되돌리기.

## 확인
- 빌드 · 단위 테스트 통과(화면 · 기기 동작이라 새 테스트 없음)
- 실기기 — 아직:
  - [ ] 👂 를 켠 악보 화면 — 딸깍 소리가 없는지
  - [ ] 화면을 나가면 알림 소리가 돌아오는지, 휴대폰 벨소리 · 진동
