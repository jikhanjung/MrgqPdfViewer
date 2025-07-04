# 🔧 MrgqPdfViewer 빌드 및 실행 가이드

## 📋 개발 환경 요구사항

### 필수 소프트웨어
- **Android Studio** Arctic Fox (2020.3.1) 이상
- **JDK** 8 이상 (Android Studio에 포함)
- **Android SDK** API 21 이상
- **Gradle** 8.2 (Wrapper 포함)

### 권장 시스템 사양
- **RAM**: 8GB 이상
- **저장공간**: 2GB 이상 여유공간
- **OS**: Windows 10/11, macOS 10.14+, Ubuntu 18.04+

---

## 🚀 프로젝트 설정

### 1. 프로젝트 클론 및 열기
```bash
git clone <repository-url>
cd MrgqPdfViewer
```

### 2. Android Studio에서 프로젝트 열기
1. Android Studio 실행
2. "Open an Existing Project" 선택
3. `MrgqPdfViewer` 폴더 선택
4. Gradle 동기화 완료 대기

### 3. SDK 및 도구 설정
Android Studio에서 자동으로 필요한 SDK를 다운로드합니다:
- Android SDK Platform API 34
- Android SDK Build-Tools 34.0.0
- Google Play Services

---

## 🔨 빌드 방법

### Gradle을 통한 빌드

#### Debug 빌드
```bash
./gradlew assembleDebug
```

#### Release 빌드
```bash
./gradlew assembleRelease
```

### Android Studio에서 빌드
1. 메뉴: `Build` → `Make Project` (Ctrl+F9)
2. APK 생성: `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`

---

## 📱 실행 및 테스트

### 1. Android TV 에뮬레이터 설정
1. `Tools` → `AVD Manager`
2. `Create Virtual Device`
3. **TV** 카테고리에서 기기 선택 (예: Android TV 1080p)
4. **API 21** 이상 시스템 이미지 선택
5. 에뮬레이터 생성 및 실행

### 2. 실제 Android TV 기기 연결
```bash
# ADB 디버깅 활성화 후
adb connect <TV_IP_ADDRESS>:5555
adb devices
```

### 3. 앱 설치 및 실행
```bash
# APK 설치
adb install app/build/outputs/apk/debug/app-debug.apk

# 앱 실행
adb shell am start -n com.mrgq.pdfviewer/.MainActivity
```

---

## 🐛 디버깅

### 로그 확인
```bash
# 앱 로그 실시간 확인
adb logcat | grep "MrgqPdfViewer"

# 특정 태그 필터링
adb logcat -s "WebServerManager" "PdfViewer"
```

### 일반적인 문제 해결

#### 1. 권한 문제
- Android 11+ 기기에서 `MANAGE_EXTERNAL_STORAGE` 권한 수동 허용 필요
- 설정 → 앱 → MrgqPdfViewer → 권한에서 파일 접근 허용

#### 2. 네트워크 문제
- TV와 PC가 같은 네트워크에 연결되어 있는지 확인
- 방화벽에서 8080 포트 허용

#### 3. PDF 파일 인식 안됨
- `/sdcard/Download/` 폴더에 PDF 파일 확인
- 파일 확장자가 정확히 `.pdf`인지 확인

---

## 📦 배포 준비

### 1. Release APK 생성
```bash
./gradlew assembleRelease
```

### 2. 서명된 APK 생성 (배포용)
1. Android Studio: `Build` → `Generate Signed Bundle / APK`
2. 키스토어 생성 또는 기존 키스토어 선택
3. Release 버전 선택 후 APK 생성

### 3. 앱 번들 생성 (Google Play)
```bash
./gradlew bundleRelease
```

---

## 🧪 테스트 시나리오

### 기본 기능 테스트
1. **앱 실행**: TV에서 앱이 정상 실행되는지 확인
2. **파일 목록**: PDF 파일들이 목록에 표시되는지 확인
3. **리모컨 탐색**: DPAD UP/DOWN으로 목록 탐색 가능한지 확인
4. **PDF 열기**: 파일 선택 시 PDF 뷰어가 열리는지 확인
5. **페이지 이동**: 리모컨 LEFT/RIGHT로 페이지 이동 가능한지 확인

### 웹 서버 테스트
1. **서버 시작**: 토글 버튼으로 웹 서버 시작
2. **IP 주소 확인**: TV 화면에 IP 주소 표시 확인
3. **브라우저 접속**: PC에서 `http://<TV_IP>:8080` 접속
4. **파일 업로드**: PDF 파일 업로드 후 목록 갱신 확인

### 성능 테스트
- **대용량 PDF**: 10MB+ PDF 파일 렌더링 테스트
- **메모리 사용량**: 장시간 사용 시 메모리 누수 확인
- **페이지 이동 속도**: 페이지 전환 반응성 확인

---

## 🔧 개발 환경 커스터마이징

### 1. 포트 변경
`WebServerManager.kt`에서 포트 변경:
```kotlin
server = PdfUploadServer(context, 8080) // 원하는 포트로 변경
```

### 2. 기본 경로 변경
`MainActivity.kt`에서 경로 변경:
```kotlin
val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
// 다른 경로로 변경 가능
```

### 3. 디버그 로그 활성화
`build.gradle`에서 디버그 설정:
```kotlin
buildTypes {
    debug {
        debuggable true
        minifyEnabled false
        buildConfigField "boolean", "DEBUG_LOG", "true"
    }
}
```

---

## 📖 참고 자료

- [Android TV 개발 가이드](https://developer.android.com/training/tv)
- [PdfRenderer API](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [NanoHTTPD 문서](https://github.com/NanoHttpd/nanohttpd)

---

**문제 발생 시 GitHub Issues에 리포트해주세요!**