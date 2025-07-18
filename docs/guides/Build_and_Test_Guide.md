# 빌드 및 테스트 가이드

## 1. 개발 환경

MrgqPdfViewer 프로젝트는 다음 환경에서 개발 및 테스트되었습니다.

*   **운영체제:** Windows 11 (WSL2 환경 포함)
*   **IDE:** Android Studio
*   **Android SDK:** API 21+ (Android 5.0+)
*   **대상 기기:** Android TV 지원 기기 (예: Z18TV Pro)

### 개발 워크플로우

*   **코딩:** WSL2 환경의 Claude Code 또는 선호하는 편집기에서 소스 코드 편집
*   **빌드/테스트/디버깅:** Windows 11의 Android Studio를 통해 실행

## 2. 빌드 및 설치

프로젝트는 Gradle을 빌드 시스템으로 사용합니다. Android Studio에서 직접 빌드하거나, 터미널에서 Gradle Wrapper를 사용할 수 있습니다.

### 2.1 터미널에서 빌드

프로젝트 루트 디렉토리(`/mnt/d/projects/MrgqPdfViewer/`)에서 다음 명령어를 실행합니다.

*   **디버그(Debug) 빌드:**
    ```bash
    ./gradlew assembleDebug
    ```

*   **릴리스(Release) 빌드:**
    ```bash
    ./gradlew assembleRelease
    ```

빌드된 APK 파일은 `app/build/outputs/apk/` 디렉토리 내에 생성됩니다.

### 2.2 기기에 설치

ADB(Android Debug Bridge)가 설정되어 있고 기기가 연결되어 있는 상태에서 다음 명령어를 사용하여 APK를 설치할 수 있습니다.

```bash
# 디버그 APK 설치 예시
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 3. 테스트

기능 구현 및 변경 후에는 다음 테스트 항목들을 확인하여 안정성을 보장해야 합니다.

### 3.1 기능 테스트

*   **파일 목록 기능:**
    *   PDF 파일이 올바르게 인식되고 표시되는지 확인합니다.
    *   이름순/시간순 정렬 기능이 정상 작동하는지 확인합니다.
    *   개별 파일 삭제 기능이 확인 대화상자와 함께 정상 작동하는지 확인합니다.
*   **PDF 렌더링:**
    *   다양한 크기와 복잡도의 PDF 파일이 정상적으로 열리고 렌더링되는지 확인합니다.
    *   고해상도 렌더링(2-4배 스케일링)이 적용되는지 확인합니다.
    *   두 페이지 모드(가로 화면에서 세로 PDF)가 올바르게 작동하는지 확인합니다.
    *   클리핑 및 여백 설정이 실시간으로 적용되고 저장되는지 확인합니다.
*   **웹 업로드 시스템:**
    *   웹 서버 토글이 정상 작동하는지 확인합니다.
    *   PC 브라우저에서 `http://<TV IP>:포트번호`로 접속하여 파일 업로드가 정상적으로 이루어지는지 확인합니다.
    *   업로드 진행률 표시 및 상태 메시지가 올바르게 나타나는지 확인합니다.
    *   웹 인터페이스에서 파일 목록 조회 및 개별/전체 삭제가 가능한지 확인합니다.
*   **합주 모드:**
    *   **지휘자 모드:**
        *   지휘자 모드 활성화 후 다른 기기에서 자동 발견되는지 확인합니다.
        *   페이지 넘김 및 파일 변경 시 연주자 기기가 실시간으로 동기화되는지 확인합니다.
        *   연주자 연결/해제 시 UI 상태가 올바르게 업데이트되는지 확인합니다.
    *   **연주자 모드:**
        *   자동 발견을 통해 지휘자를 찾고 연결되는지 확인합니다.
        *   지휘자의 페이지/파일 변경에 따라 자신의 뷰어가 올바르게 업데이트되는지 확인합니다.
        *   지휘자로부터 파일 다운로드 제안 시 정상적으로 다운로드되고 열리는지 확인합니다.
        *   연결 끊김 시 UI 상태가 올바르게 반영되는지 확인합니다.
    *   **안정성:**
        *   앱 강제 종료 후 지휘자 모드 재시작 시 포트 충돌 없이 정상 작동하는지 확인합니다.
        *   장시간 연결 유지 시 안정성을 확인합니다.
*   **설정 화면:**
    *   모든 설정 항목(웹서버 포트, 파일별 페이지 모드, 설정 초기화 등)이 정상 작동하는지 확인합니다.
    *   새로운 TV 스타일 설정 화면(카테고리별 메뉴)이 리모컨으로 원활하게 탐색되는지 확인합니다.
*   **리모컨 조작:**
    *   DPAD(상하좌우) 및 ENTER 키가 모든 화면에서 예상대로 작동하는지 확인합니다.
    *   ENTER 키 길게 누르기(800ms)로 PDF 표시 옵션 메뉴가 나타나는지 확인합니다.
    *   파일 간 탐색 UI(좌우 분할 카드)가 직관적으로 작동하는지 확인합니다.

### 3.2 보안 테스트

*   **WSS 암호화 확인:**
    *   합주 모드 연결 시 Wireshark와 같은 네트워크 분석 도구를 사용하여 트래픽이 TLS로 암호화되어 있는지 확인합니다.
    *   평문 WebSocket 프로토콜 패킷이 보이지 않아야 합니다.
*   **인증서 피닝 동작 확인:**
    *   연주자 앱의 `network_security_config.xml`에서 `mycert.crt` 참조를 일시적으로 제거한 후, 지휘자에게 연결을 시도하여 SSL 핸드셰이크 오류가 발생하는지 확인합니다. 이는 인증서 피닝이 올바르게 작동함을 의미합니다.

### 3.3 성능 테스트

*   **PDF 렌더링 성능:** 다양한 크기의 PDF 파일(특히 대용량 파일)을 열고 페이지를 넘길 때의 로딩 속도와 부드러움을 확인합니다.
*   **메모리 사용량:** 앱 사용 중 메모리 사용량이 과도하게 증가하지 않는지 모니터링합니다.
*   **CPU 사용량:** 합주 모드 등 실시간 기능 사용 시 CPU 사용량이 적절한지 확인합니다.

## 4. 디버깅 가이드

*   **로그 확인:** `android.util.Log`를 사용하여 중요한 동작 및 오류 메시지를 출력합니다. 특히 합주 모드 관련 로그는 `🎯` 접두사를 사용하여 쉽게 필터링할 수 있습니다.
*   **Android Studio 디버거:** Android Studio의 내장 디버거를 사용하여 코드 실행 흐름을 추적하고 변수 값을 검사합니다.
*   **ADB Logcat:** `adb logcat` 명령어를 사용하여 기기 또는 에뮬레이터의 실시간 로그를 확인합니다.
*   **네트워크 분석 도구:** Wireshark 등을 사용하여 네트워크 트래픽을 분석하고 통신 문제를 진단합니다.

이 가이드를 통해 MrgqPdfViewer의 빌드, 설치, 테스트 및 디버깅 과정을 효율적으로 수행할 수 있습니다.
