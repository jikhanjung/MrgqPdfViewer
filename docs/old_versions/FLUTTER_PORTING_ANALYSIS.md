# Flutter 포팅 분석 및 개발 전략

## 📋 개요
- **작성일**: 2025-07-07
- **목적**: MrgqPdfViewer 앱의 iOS 지원을 위한 Flutter 포팅 방안 분석
- **현재 상태**: Android TV 앱 v0.1.5+ 완성, iOS 버전 필요

## 🔧 포트 바인딩 문제 현황

### 해결 상태
- **구현 완료**: SimpleWebSocketServer에 1초 소켓 타임아웃 적용
- **검증 필요**: 실제 효과 확인 필요

### 테스트 방법
1. 앱 실행 → 지휘자 모드 활성화
2. 앱 강제 종료 (최근 앱에서 스와이프)
3. 즉시 앱 재실행 → 지휘자 모드 다시 활성화 시도
4. 성공하면 해결, "Address already in use" 에러 시 미해결

### 추가 확인 명령어
```bash
# 포트 상태 확인
adb shell netstat -tlpn | grep 9090

# 로그 확인
adb logcat | grep "Accept timeout"
```

## 🚀 iOS 포팅 방안 비교

### 1. Swift 네이티브 개발

#### 장점
- iOS/tvOS 플랫폼 특화 최적화
- PDFKit 네이티브 API 활용 (고성능)
- Apple TV 리모컨 완벽 지원
- 기존 Android 코드와 비슷한 구조 가능

#### 단점
- Android와 iOS 별도 유지보수 필요
- 코드 중복 (네트워킹, 비즈니스 로직)
- 개발 시간 2배
- 협업 모드 프로토콜 2번 구현

### 2. Flutter 크로스플랫폼

#### 장점
- 단일 코드베이스로 Android/iOS 동시 지원
- 빠른 개발 및 유지보수
- 네트워킹/WebSocket 코드 공유
- UI 일관성 보장

#### 단점
- PDF 렌더링 성능 이슈 가능성
- TV OS 지원 제한적 (비공식)
- 네이티브 기능 접근 시 플러그인 필요
- 앱 크기 증가 (Flutter 엔진 포함)

### 🎯 추천: Flutter 기반 재구현

**이유**:
1. 코드 재사용: WebSocket, 파일 관리, 협업 로직 공유
2. 유지보수: 버그 수정 및 기능 추가 한 번에 처리
3. PDF 렌더링: `syncfusion_flutter_pdfviewer` 등 성숙한 패키지 존재
4. TV 지원: 비공식이지만 커뮤니티 솔루션 존재

## 💻 Flutter 개발환경

### 기본 구성
- **Flutter SDK**: 크로스플랫폼 프레임워크 코어
- **Dart SDK**: Flutter에 포함
- **IDE**: VS Code (추천) 또는 Android Studio
- **플랫폼별 도구**:
  - Android: Android Studio, Android SDK
  - iOS: Xcode, CocoaPods (Mac 필수)

### 주요 특징
- **Hot Reload**: 코드 변경 즉시 반영 (1초 이내)
- **위젯 시스템**: 모든 UI를 위젯으로 구성
- **Flutter Inspector**: 위젯 트리 시각화 및 디버깅
- **DevTools**: 성능 프로파일링 도구

### 필요한 패키지
```yaml
dependencies:
  # PDF 렌더링
  syncfusion_flutter_pdfviewer: ^24.1.41  # 상용, 고성능
  flutter_pdfview: ^1.3.2                  # 오픈소스
  
  # 네트워킹
  dio: ^5.4.0                    # HTTP 클라이언트
  web_socket_channel: ^2.4.0     # WebSocket
  shelf: ^1.4.1                  # HTTP 서버
```

## 🔄 Claude Code + Flutter 개발 워크플로우

### 1. WSL2 환경 설정
```bash
# Flutter SDK 설치
cd ~
git clone https://github.com/flutter/flutter.git
echo 'export PATH="$PATH:$HOME/flutter/bin"' >> ~/.bashrc

# Windows ADB 연결
echo 'export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037' >> ~/.bashrc

# 개발 별칭 설정
cat >> ~/.bashrc << 'EOF'
alias fr='flutter run'
alias fb='flutter build'
alias fpg='flutter pub get'
alias fclean='flutter clean'
alias fdoctor='flutter doctor'
EOF
```

### 2. 효율적인 개발 전략

#### Phase 1: Android 우선 개발
1. WSL2 Claude Code에서 전체 기능 구현
2. Windows Android 에뮬레이터로 실시간 테스트
3. Hot Reload로 즉시 확인
4. Android TV 실기기 테스트

#### Phase 2: iOS 포팅
1. GitHub Actions 또는 Codemagic CI/CD 활용
2. TestFlight로 베타 테스트
3. 필요시 Mac mini 렌탈/구매

### 3. 플랫폼별 테스트 방법

| 플랫폼 | 개발 | 테스트 | 빌드 |
|--------|------|--------|------|
| Android | WSL2 직접 | 에뮬레이터 | WSL2 |
| Android TV | WSL2 직접 | 에뮬레이터/실기기 | WSL2 |
| iOS | 코드만 작성 | CI/CD | GitHub Actions |
| tvOS | 코드만 작성 | CI/CD | GitHub Actions |

### 4. 실용적인 워크플로우

```bash
# 1. Claude Code에서 코딩
flutter create mrgq_pdf_viewer
cd mrgq_pdf_viewer

# 2. Windows에서 에뮬레이터 실행
emulator -avd Android_TV_1080p_API_30

# 3. WSL2에서 Hot Reload 개발
flutter run -d emulator-5554

# 4. 파일 저장 시 자동 리로드
# 코드 수정 → Ctrl+S → 1초 내 반영
```

### 5. iOS 빌드 자동화 (GitHub Actions)
```yaml
name: iOS Build
on: push
jobs:
  build:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      - uses: subosito/flutter-action@v2
      - run: flutter build ios --no-codesign
      - uses: actions/upload-artifact@v3
        with:
          name: ios-build
          path: build/ios/iphoneos/
```

## 📊 개발 효율성 비교

| 항목 | 현재 (Kotlin) | Flutter 포팅 |
|------|---------------|--------------|
| 코드베이스 | Android 전용 | Android + iOS |
| 개발 시간 | 100% | 150% (초기) |
| 유지보수 | 플랫폼별 | 통합 관리 |
| 성능 | 네이티브 | 95% 수준 |
| 앱 크기 | 작음 | +5MB |
| TV 지원 | 공식 | 비공식 |

## 🎯 결론 및 권장사항

1. **Flutter 재구현 추천**: 장기적 유지보수 효율성
2. **개발 순서**: Android 완성 → iOS 포팅
3. **개발 환경**: WSL2 Claude Code + Windows 에뮬레이터
4. **iOS 전략**: CI/CD 활용으로 Mac 없이도 개발 가능
5. **예상 기간**: 2-3주 (Android 1.5주 + iOS 1주 + 테스트 0.5주)

## 📝 다음 단계

1. Flutter 개발환경 구축
2. 핵심 기능 프로토타입 개발
3. PDF 렌더링 성능 벤치마크
4. TV 플랫폼 지원 검증
5. 협업 모드 구현 및 테스트