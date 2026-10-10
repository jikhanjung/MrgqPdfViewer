# P14 — iOS 앱 (iPad 먼저 · Kotlin Multiplatform 으로 로직 공유)

작성일: 2026-10-10
상태: 📝 **계획** — 결정 대기(§9). 1단계(공용 모듈 떼기)는 iOS 를 하지 않더라도 Android 쪽에 이득이라 먼저 할 수 있다
관련: [`P12`](20261009_P12_voice_commands_plan.md)(음성 명령 — 파서를 그대로 쓸 첫 후보), [`P10`](20260929_P10_mic_conductor_autoturn_plan.md)(마이크 추적),
[`055`](20260926_055_ensemble_metronome_sync.md)(합주 메트로놈), [`P05`](20260926_P05_scoremate_client_plan.md) · [`P06`](20260927_P06_scoremate_server_requests.md)(ScoreMate · 서버 분석 §12),
[`P07`](20260928_P07_part_view_plan.md)(파트보), [`P11`](20261007_P11_score_annotations_plan.md)(메모)

---

## 0. 한 줄

**iPad 용 악보 리더**를 만든다. 순수 Kotlin 으로 짜 둔 핵심 로직(음성 명령 · 메트로놈 박 계산 · 합주 시계 · 마이크 추적 · 악보 분석 해석기 · 파트 레이아웃 · 메모 모델)은
**Kotlin Multiplatform(KMP) 공용 모듈**로 떼어 Android 와 iOS 가 같은 코드를 쓴다. 화면 · PDF 그리기 · 소리 · 마이크 · 음성 인식 · 네트워크는 iOS 것으로 새로 만든다.
Android 앱의 동작은 바꾸지 않는다.

## 1. 왜 · 무엇을 먼저

- 악보 태블릿으로 **iPad 가 가장 흔하다**(Apple Pencil 메모 · 블루투스 페달). 지금 태블릿 기능(🎤 듣고 넘기기 · 🎙 음성 · 메모)은 대부분 iPad 에서 더 쓸 사람이 많다
- **ScoreMate 와 짝**: 서버 계정 · 세트리스트 · 메모 동기화는 플랫폼과 무관 — iPad 가 같은 서재를 보면 ScoreMate 쓰임새가 넓어진다
- **합주**: Android TV 지휘자 · 연주자 사이에 iPad 연주자가 끼어드는 것이 가장 현실적인 첫 합주 장면
- 먼저 할 것: **로직을 Android 의존 없이 유지해 온 것**(대부분 "JVM 단위 테스트 대상")이 이 계획의 전제다. 그 코드를 공용 모듈로 옮기는 1단계는 iOS 결정과 무관하게 해 둘 만하다
- 범위 밖: Apple TV(tvOS — 원래 타깃은 TV 지만 PDFKit · 입력 여건이 다르고 수요 미확인), Mac, 앱 안 업데이트(§6), 웹 업로드 서버(§6)

## 2. 지금 코드 — 무엇을 공유할 수 있나

`app/src/main/java/com/mrgq/pdfviewer` 조사(2026-10-10, v0.7.4-beta.3). "순수" = android · androidx · PdfBox · Gson · OkHttp · NanoHTTPD 를 import 하지 않는 파일.

| 패키지 | 파일 · 줄 | 순수 | 순수한 것 | 플랫폼에 묶인 것 |
|---|---|---|---|---|
| `voice/` | 11 · 1093 | 9 | 정규화 · 수 읽기 · 파서 · 실행기 · `ViewerCommands` · `PartMatcher` · `WakeWord` | `VoiceListener`(SpeechRecognizer) · `NotificationMute` |
| `metronome/` | 10 · 1354 | 8 | `MetronomeClock` · `TimeSignature` · `TempoSections` · `ScoreFollower` · `ClickSynth` · 반주 2개 | `MetronomeEngine`(AudioTrack) · `MetronomeBeatView` |
| `ensemble/` | 7 · 483 | 6 | `ClockSync` · `BeatTimeline` · `EnsembleSchedule` · `EnsembleRun` · `EnsembleVersion` · `EnsembleFiles`(File) | `VersionNotice` |
| `follow/` | 8 · 1022 | 7 | 크로마 · OLTW · 관성 · 넘김 판단 · 시작 찾기 · 차례 넘김 | `MicScoreFollower`(AudioRecord) |
| `score/` | 17 · 2611 | 11 | `PathContentInterpreter` · 시스템 · 박자표 · 보표 이름 찾기 · `ScoreLayout` · `PartLayout` · `PartClip` · 오버레이 기하 | PdfBox 연결(`PdfBoxContent` · `PartPdfBuilder` · 분석기), DB 캐시, 뷰, `MusicXmlScore`(javax.xml) |
| `notes/` | 6 · 1077 | 4 | 메모 모델 · 기하 · 보표 붙이기 · 곁 파일(File · MessageDigest) | 펜 · 뷰 |
| `scoremate/` | 7 · 1717 | 5 | 프로토콜 · 세트리스트 · 동기화 로직(File · HttpURLConnection 섞임) | 저장소 · 연결 대화상자 |
| `update/` | 4 · 722 | 3 | `AppVersion` · `ReleaseInfo` | (iOS 에선 안 씀, §6) |
| 합주 통신(최상위) | — | — | `CollaborationProtocol`(메시지 키 — Gson `JsonObject` 에 묶임) | OkHttp WebSocket · 자체 WS 서버 · UDP 발견 |
| 화면 | `PdfViewerActivity` 7310 · `MainActivity` 1276 · `SettingsActivity` 1734 | — | — | **전부 Android View** — 어느 쪽으로 가든 iOS 는 새로 짠다 |

### 2.1 공용(commonMain)으로 옮길 때 고칠 JVM 전용 호출

순수 파일도 JVM 표준 라이브러리를 조금 쓴다. 모두 기계적으로 바꿀 수 있다.

| 지금 | 공용 |
|---|---|
| `Math.floorMod` · `Math.round` | `mod()` · `roundToInt()` / `roundToLong()` |
| `System.arraycopy` | `copyInto` |
| `System.nanoTime()`(주석 · 호출부) | `expect fun monotonicNanos()` — iOS 는 `mach_absolute_time` 환산 (오디오 시각과 같은 축이어야 함, §4.3) |
| `@Volatile` | `kotlin.concurrent.Volatile` |
| `java.util.UUID` | `kotlin.uuid.Uuid`(Kotlin 2.0.20+) |
| `java.net.URLDecoder` | 작은 공용 함수 (`ReleaseInfo` 는 iOS 에서 안 쓰므로 Android 에 남겨도 됨) |
| `java.io.File` · `MessageDigest` | okio(KMP) 또는 `expect` — 1단계에서는 이 파일들을 Android 쪽에 남긴다 |
| `javax.xml`(MusicXML) | `xmlutil` 같은 KMP XML 파서 또는 `expect`(iOS `XMLParser`) |
| Gson `JsonObject`(합주 · ScoreMate 메시지) | `kotlinx.serialization` — 합주 메시지 키를 한 곳에 모은 `CollaborationProtocol` 를 공용으로 옮기면 iOS 가 같은 메시지를 보장받는다 |
| `Regex`(뒤 보기 `(?<=…)` 포함) | 공용 `Regex` 그대로 — 단 Kotlin/Native 정규식 엔진이 따로라 **iOS 에서 테스트를 돌려 확인**(2단계) |

### 2.2 빌드 도구 전제

- **Kotlin 1.9.22 → 2.x** 로 올려야 한다(KMP 공용 모듈 · Compose Multiplatform · Room KMP 모두 2.x 기준). Android 만으로 먼저 올리고 CI 통과를 확인한다
- **kapt → KSP**: Room 을 공용으로 옮길 경우에만 필요(kapt 는 iOS 대상이 없다). 처음에는 DB 를 공유하지 않는다(§3)
- 공용 모듈의 Android · JVM 대상은 **지금의 Linux CI 에서 빌드 · 테스트된다**. iOS 대상 컴파일 · 링크 · 시뮬레이터 테스트는 **macOS 러너**가 필요하다

## 3. 구조

```
shared/                      ← KMP 모듈 (새로)
  commonMain/  voice · metronome 계산 · ensemble 계산 · follow 핵심 · score 해석기 · 파트 레이아웃 · notes 모델 · 합주 메시지
  commonTest/  지금 JVM 단위 테스트 중 위 대상 (JUnit → kotlin.test)
  androidMain/ (거의 없음)
  iosMain/     monotonicNanos 등 expect 구현
app/                         ← 지금 Android 앱, shared 에 의존 (동작 그대로)
iosApp/                      ← Xcode 프로젝트, Shared.xcframework 를 씀
```

- DB 는 처음에 **공유하지 않는다**: iOS 는 파일별 설정 · 메트로놈 설정을 단순 저장(JSON · SwiftData)으로 시작. 기능이 늘면 Room KMP 로 옮길지 다시 본다
- 공용 API 는 Swift 에서 부르기 쉽게: `suspend` 대신 콜백 · 단순 값 타입, sealed class 는 Swift 에서 `switch` 가 어색하므로 필요하면 SKIE 같은 도구 검토

## 4. iOS 에서 새로 만들 것 — 플랫폼 대응표

| 기능 | Android (지금) | iOS | 메모 |
|---|---|---|---|
| PDF 그리기 | `PdfRenderer`(PDFium) · 1× 정수 좌표(#042) | PDFKit / `CGPDFPage` → 비트맵 | 얇은 선 품질 다시 확인. 두 쪽 · 클리핑 · 여백은 같은 기하(`PageGeometry` 를 공용으로) |
| 악보 분석 | PdfBox(스트림 · 글꼴) + `PathContentInterpreter` | **처음엔 서버 분석 `.layout.json`(P06 §12)만** → 나중에 `CGPDFStream` 으로 콘텐츠 스트림 바이트를 꺼내 공용 해석기에 | 해석기 자체는 순수. 글꼴로 글자를 읽는 부분(박자표 · 보표 이름 · SMuFL)이 iOS 에서 새 일 |
| 파트보 PDF | PdfBox 폼 가져오기 | CoreGraphics PDF 컨텍스트에 원본 쪽을 잘라(clip) 그려 벡터 PDF | `PartLayout` · `PartClip` 은 공용 |
| 메트로놈 소리 | `AudioTrack` 샘플 단위 · `AudioTimestamp` | `AVAudioEngine` + `AVAudioSourceNode`, 시각은 `AVAudioTime.hostTime` | §4.3 |
| 마이크 추적 | `AudioRecord` → 크로마 | `AVAudioEngine.inputNode` 탭 → 공용 크로마 · OLTW | Kotlin/Native 에서 46ms 칸 처리 속도 측정 |
| 음성 명령 | `SpeechRecognizer`(Google) | `SFSpeechRecognizer`(ko-KR, `contextualStrings`) — iOS 26 의 `SpeechAnalyzer` 도 후보 | 파서 · 실행기 · `ViewerCommands` 는 공용. 기기 안 한국어 인식 여부 · 👂 계속 듣기 시간 제한 확인 |
| 메모 | 펜 · 손가락(✏️ 모드) | Apple Pencil(`UITouch.type == .pencil`) · 손가락 | 모델 · 곁 파일 형식 · 동기화 공용 — 기기 간 그대로 오간다 |
| 넘김 페달 | 키 이벤트(PageUp/Down 등, #066) | `pressesBegan` / `UIKeyCommand` | 블루투스 페달은 대개 키보드로 보임 |
| ScoreMate | `HttpURLConnection` · prefs | `URLSession` 또는 Ktor(KMP) · 토큰은 **Keychain** | 연결 흐름(RFC 8628 QR · 코드) 같음 |
| 합주 연주자 | OkHttp WebSocket | `URLSessionWebSocketTask` 또는 Ktor | 메시지는 공용(§2.1) |
| 합주 지휘자 | 자체 WS 서버 + UDP 방송 발견 | `NWListener`(Network.framework) | §4.4 |
| 파일 가져오기 | 웹 업로드 서버 · ScoreMate | **파일 앱 · 공유 시트("…에서 열기")** · ScoreMate | 웹 서버는 만들지 않음(§6) |

### 4.3 메트로놈 — 실시간 오디오 스레드에 Kotlin 을 넣지 않는다

- iOS 렌더 콜백은 실시간 스레드라 할당 · 잠금 · GC 멈춤이 있으면 소리가 끊긴다. Kotlin/Native 는 GC 가 있다
- 그래서 **공용 `MetronomeClock` · `ClickSynth` 는 보통 스레드에서 다음 몇백 ms 의 샘플을 미리 만들어 링 버퍼에 쓰고**, Swift 렌더 콜백은 버퍼를 복사만 한다 — Android 엔진이 AudioTrack 에 이어 쓰는 구조와 같다
- 박 표시 · 현재 마디는 지금처럼 "실제 재생 위치"를 따른다: `hostTime` ↔ 샘플 위치. 합주 시계(`ClockSync`)는 같은 단조 시계(`mach_absolute_time`)여야 지휘자 시간표를 옮길 수 있다
- 확인할 수치: Android 실측 마디 전환 차이 중앙값 2.5ms(#055) — iPad 연주자도 같은 측정 방식으로

### 4.4 합주 — iOS 네트워크 제약

- **로컬 네트워크 권한**(`NSLocalNetworkUsageDescription`)을 처음에 묻는다 — 거절하면 합주 불가, 안내 필요
- **UDP 방송 · 멀티캐스트 보내기는 Apple 의 별도 권한(`com.apple.developer.networking.multicast`) 신청**이 필요하다. 받기 전 대안: 지휘자 IP 직접 입력 · QR, 또는 양쪽에 Bonjour(`_mrgq._tcp`)를 더함(Android 는 NSD)
- iPad 가 **지휘자**가 되려면 앱이 앞에 있는 동안만 서버가 산다(백그라운드 제한). 처음엔 **iPad = 연주자**만
- 합주 버전(`EnsembleVersion`)은 그대로 쓴다 — iOS 앱도 같은 `ensemble_version` 을 보내고, 합주 메시지를 바꿀 때는 두 앱을 함께 올린다

## 5. 화면 — SwiftUI(제안) vs Compose Multiplatform

| | **SwiftUI + UIKit (제안)** | Compose Multiplatform |
|---|---|---|
| 공유 | 로직만 | 로직 + 화면 대부분 |
| Android 쪽 | 그대로(View) | Android 화면도 Compose 로 다시 짜야 이득 — 7000줄 뷰어를 옮기는 큰 일 |
| 악보 화면 | PDFKit · Pencil · 페달 · 오디오가 모두 iOS 기본 API — 바로 붙는다 | 그리기는 Skia 캔버스, PDF · Pencil 압력 · 키 입력을 iOS 쪽에서 넘겨받는 접착 코드 |
| iPad 느낌 | 기본 | 비슷하지만 손볼 곳 있음 |

**제안: SwiftUI.** 악보 화면의 무게는 대부분 플랫폼 API(PDF · 펜 · 소리 · 마이크 · 음성)에 있고, Android 화면이 Compose 가 아니라 화면 공유의 이득을 당장 못 얻는다.
화면을 함께 가져가고 싶어지면(예: 설정 · 목록) 그때 Compose Multiplatform 을 부분 도입할 수 있다.

## 6. iOS 에서 하지 않는 것 · 다르게 하는 것

- **앱 안 업데이트(#054)**: App Store · TestFlight 가 맡는다 — 만들지 않음 (`update/` 는 Android 에만)
- **웹 업로드 서버**: 백그라운드에서 서버가 죽고, 파일 앱 · 공유 시트 · ScoreMate 가 더 자연스럽다 — 만들지 않음
- **알림 소리 끄기(#092)**: Android 전용 문제 — iOS 는 오디오 세션 설정으로 따로 본다
- **TV 리모컨 · leanback**: 해당 없음
- 저작권 원칙은 같다: 파트 PDF · MusicXML · 서버 분석 파일은 기기 밖으로 보내지 않는다

## 7. 단계

| 단계 | 내용 | 필요한 것 | 끝난 기준 |
|---|---|---|---|
| **0** | Kotlin 2.x · (필요하면) Gradle 정리 — Android 만 | 지금 CI | CI 통과, 동작 변화 없음, beta 하나 |
| **1** | `shared/` KMP 모듈(Android · JVM 대상만): `voice/` → 메트로놈 · 합주 계산 → `follow/` 핵심 → `score/` 해석기 · 파트 레이아웃 → 메모 모델 순으로 옮김, §2.1 치환, 테스트는 처음엔 jvmTest 그대로 | 지금 CI(Linux) | 단위 테스트 수 그대로 통과, 계측 · 스모크 통과. **iOS 를 안 해도 남는 이득**: 경계가 강제된다(Android 가 새어 들어오면 컴파일 오류) |
| **2** | iOS 대상 추가 + **macOS CI 작업**: 공용 모듈을 iosArm64 · iosSimulatorArm64 로 컴파일, 테스트를 kotlin.test 로 바꿔 iOS 시뮬레이터에서도 실행 | macOS 러너 | iOS 에서 공용 테스트 전부 통과(정규식 · 부동소수 차이 잡기) |
| **3** | **iPad 최소 뷰어**: 파일 앱에서 PDF 가져오기 · 목록 · PDFKit 렌더 · 탭 · 밀기 · 페달 넘김 · 세로 한 쪽 · 가로 두 쪽 · 파일별 설정 | Mac + Xcode, Apple 개발자 계정 | 실기기 iPad 에서 악보 넘김, TestFlight 내부 배포 |
| **4** | ScoreMate 연결 · 동기화 · 세트리스트 · **서버 분석으로 마디 박스** · 메트로놈(§4.3) · 악보 연동 | 3 | 같은 계정의 서재가 보이고, 악보 연동 메트로놈이 마디를 따라 넘김 |
| **5** | 🎙 음성 명령 — `SFSpeechRecognizer` + 공용 파서 · 실행기 | 4 | `docs/Voice_Commands.md` 의 명령이 같은 결과 |
| **6** | 합주 **연주자**(WebSocket · 시계 동기 · 시간표 · 쪽 넘김 · 파트 보기) | 4, 로컬 네트워크 권한 | Android TV 지휘자 + iPad 연주자, 마디 전환 차이 측정 |
| **7** | 메모(Apple Pencil) · 파트보 · 🎤 마이크 추적 · 반주 · 기기 안 악보 분석 · (필요하면) iPad 지휘자 · iPhone | 각자 | 기능별로 Android 와 같은 시험 |

- 각 단계는 Android 판 번호와 별개인 **iOS 판 번호**로 낸다(예: iOS 0.1.0). 공용 모듈을 바꾸는 커밋은 Android CI 와 macOS CI 를 둘 다 통과해야 한다
- 1단계는 한 번에 옮기지 않고 패키지 하나씩, 매번 Android beta 로 확인 — 동작이 바뀌면 안 되는 이동이다

## 8. 배포 · 비용

- **Apple 개발자 프로그램 연 $99** — 실기기에 7일 넘게 두기 · TestFlight · App Store 에 필요. 무료 계정은 Xcode 로 직접 설치만, 7일마다 다시
- **TestFlight**: 내부 시험자(팀 계정, 최대 100명)는 심사 없이 바로. 외부 시험자는 베타 심사 한 번
- **App Store**: 심사 — 마이크 · 음성 인식 · 로컬 네트워크 사용 목적 문구, 개인정보 표시(음성은 Apple 서버로 갈 수 있음)
- **macOS CI**: GitHub 호스트 macOS 러너는 Linux 보다 분 단가가 비싸다(공개 저장소는 표준 러너 무료). iOS 작업은 `shared/` · `iosApp/` 가 바뀔 때만 돌리도록 경로 필터
- 서명 인증서 · 프로비저닝 프로필은 Android 키스토어처럼 secrets 로 — 저장소에 넣지 않는다

## 9. 열린 질문 (결정 대기)

1. **Mac 이 있나?** 3단계부터는 Xcode 가 필요하다(시뮬레이터 · 실기기 설치 · 디버깅). CI 만으로는 빌드는 되지만 화면 만들기가 매우 느리다
2. **Apple 개발자 계정** — 개인 vs 조직(앱 판매자 이름이 달라진다, ScoreMate 브랜드와 맞출지)
3. **화면 기술** — SwiftUI(제안, §5) vs Compose Multiplatform
4. **첫 기기** — iPad(제안) · iPhone 함께?
5. **1단계를 iOS 결정과 따로 먼저 할까?** — 제안: 예. 위험이 낮고(이동만), 테스트가 지켜 준다
6. **앱 이름 · 묶음 ID** — Android 와 같은 `com.mrgq.pdfviewer` 를 쓸지(App Store 이름 "MRGQ PDF Viewer"?)
7. **합주 발견** — 멀티캐스트 권한 신청 vs IP · QR 직접 입력 vs Bonjour 추가(§4.4)
