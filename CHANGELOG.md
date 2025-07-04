# 📝 변경 이력 (Changelog)

모든 주요 변경사항이 이 파일에 기록됩니다.

---

## [0.1.1] - 2025-07-05

### 🐛 버그 수정
- **PDF 파일 탐색 실패 문제 해결**
  - 파일 간 이동 시 PdfRenderer 생성 실패 문제 수정
  - 리소스 정리 및 재생성 로직 개선
  - 상세한 디버깅 로그 추가

### ✨ 개선된 기능

#### 🎮 향상된 파일 탐색 UX
- **키 입력 기반 파일 탐색**
  - Alert 대화상자 제거
  - 직관적인 키 조작으로 파일 간 이동
  - 화면 하단에 안내 메시지 표시

#### 📱 새로운 탐색 인터페이스
- **마지막 페이지에서**:
  - 첫 번째 `→` 키: 안내 메시지 표시
  - 두 번째 `→` 키: 다음 파일로 이동
  - `←` 키: 파일 목록으로 돌아가기
- **첫 페이지에서**:
  - 첫 번째 `←` 키: 안내 메시지 표시
  - 두 번째 `←` 키: 이전 파일로 이동
  - `→` 키: 파일 목록으로 돌아가기
- **추가 기능**:
  - `Enter` 키: 안내 메시지 숨기기
  - 5초 후 자동 숨김
  - 부드러운 페이드 애니메이션

### 🔧 기술적 개선

#### 🖥️ 개발 환경 정리
- **하이브리드 개발 워크플로우 확립**
  - WSL2 Claude Code: 소스 코드 편집
  - Windows 11 Android Studio: 빌드/테스트/디버깅
  - 개발 환경 정보 문서화

#### 🔍 디버깅 강화
- **PdfRenderer 생성 과정 로그 추가**
  - ParcelFileDescriptor 생성 단계별 로깅
  - 예외 상황 상세 정보 수집
  - 리소스 정리 과정 추적

### 📋 코드 변경 사항

#### 레이아웃 추가
- **activity_pdf_viewer.xml**
  - `navigationGuide` LinearLayout 추가
  - 안내 메시지 표시용 TextView들 추가
  - 반응형 레이아웃 구성

#### 로직 개선
- **PdfViewerActivity.kt**
  - Alert 대화상자 제거
  - 키 입력 기반 탐색 로직 구현
  - 안내 메시지 표시/숨김 애니메이션
  - 상태 관리 변수 추가
  - 디버깅 로그 강화

#### 문서 업데이트
- **CLAUDE.md**
  - 개발 환경 워크플로우 추가
  - 빌드 명령어 위치 명시

---

## [0.1.0] - 2025-07-04

### ✨ 추가된 기능

#### 🏗️ 프로젝트 초기 설정
- **Android Studio 프로젝트 구조 생성**
  - Kotlin 기반 Android TV 프로젝트
  - minSdkVersion 21, targetSdkVersion 34
  - Android TV 런처 지원 설정

#### 📦 빌드 환경 구성
- **Gradle 설정 완료**
  - `build.gradle.kts` (프로젝트 레벨)
  - `app/build.gradle.kts` (앱 레벨)  
  - `settings.gradle.kts`
  - `gradle.properties`
- **Gradle Wrapper 추가**
  - `gradlew`, `gradlew.bat` 실행 스크립트
  - `gradle/wrapper/gradle-wrapper.properties`
- **ProGuard 설정**
  - `app/proguard-rules.pro`
  - NanoHTTPD 라이브러리 보호 규칙

#### 🎯 핵심 기능 구현

##### 1. 메인 화면 (파일 목록)
- **MainActivity.kt** 구현
  - PDF 파일 스캔 및 목록 표시
  - 권한 처리 (Android 11+ MANAGE_EXTERNAL_STORAGE 지원)
  - 리모컨 탐색 지원 (DPAD UP/DOWN)
  - 웹 서버 토글 기능

##### 2. PDF 뷰어
- **PdfViewerActivity.kt** 구현
  - Android PdfRenderer 사용
  - 비동기 PDF 로딩
  - 리모컨 페이지 이동 (DPAD LEFT/RIGHT)
  - 페이지 정보 표시 (현재/전체)
  - 메모리 효율적 리소스 관리

##### 3. 웹 서버 (파일 업로드)
- **WebServerManager.kt** 구현
  - NanoHTTPD 2.3.1 기반 HTTP 서버
  - 8080 포트 웹 서버
  - 모던 웹 인터페이스 (HTML5)
  - 드래그 앤 드롭 파일 업로드
  - 다중 파일 업로드 지원
  - 실시간 업로드 진행률 표시
  - 자동 파일 목록 갱신

#### 🎨 UI/UX 구현

##### 레이아웃 파일
- **activity_main.xml**: 메인 화면 레이아웃
  - 파일 목록 RecyclerView
  - 웹 서버 토글 및 상태 표시
  - 빈 상태 메시지
- **activity_pdf_viewer.xml**: PDF 뷰어 레이아웃
  - 전체 화면 ImageView
  - 페이지 정보 오버레이
  - 로딩 프로그레스바
- **item_pdf_file.xml**: 파일 목록 아이템
  - PDF 아이콘 및 파일명
  - 포커스 효과 (확대/그림자)

##### 어댑터 및 모델
- **PdfFileAdapter.kt**: RecyclerView 어댑터
  - 파일 목록 표시
  - 포커스 관리 및 애니메이션
  - 클릭 이벤트 처리
- **PdfFile.kt**: 데이터 모델
  - 파일명 및 경로 정보

#### 🎨 리소스 및 테마

##### 색상 및 테마
- **colors.xml**: TV 최적화 다크 테마 색상
  - Primary: #007AFF (iOS 블루)
  - Background: #1C1C1E (다크 그레이)
  - Surface: #2C2C2E
  - 텍스트 색상 계층 구조
- **themes.xml**: Android TV 전용 테마
  - NoActionBar 기반
  - 전체 화면 설정
  - 포커스 하이라이트 색상

##### 치수 및 텍스트
- **dimens.xml**: TV 화면 최적화 치수
  - 큰 터치 영역 (48dp 아이콘)
  - 적절한 패딩 및 마진
  - 카드 스타일 요소
- **strings.xml**: 다국어 지원 텍스트
  - 모든 UI 텍스트 리소스화
  - 에러 메시지 및 상태 텍스트

#### 🖼️ 아이콘 및 이미지

##### 앱 아이콘
- **Adaptive Icon 지원**
  - `ic_launcher_foreground.xml`: 전경 벡터
  - `ic_launcher_background.xml`: 배경 색상
  - `mipmap-anydpi-v26/ic_launcher.xml`: Adaptive Icon 설정
- **다해상도 PNG 아이콘**
  - mdpi (48x48) ~ xxxhdpi (192x192)
  - 일반 및 라운드 아이콘 모두 지원

##### TV 배너 및 기타 아이콘
- **tv_banner.png**: Android TV 런처용 배너 (320x180)
- **ic_pdf.xml**: PDF 파일 아이콘 벡터

#### 📋 매니페스트 설정
- **AndroidManifest.xml** 완성
  - TV 런처 지원 (`LEANBACK_LAUNCHER`)
  - 필요한 권한 선언
  - TV 전용 기능 설정
  - 가로 모드 고정
  - 액티비티 구성 변경 처리

#### 🛠️ 개발 환경 도구

##### 버전 관리
- **.gitignore** 생성
  - Android 프로젝트 표준 ignore 규칙
  - IDE 파일 제외
  - 빌드 아티팩트 제외

##### 종속성 관리
- **주요 라이브러리 추가**
  - androidx.leanback:leanback (1.0.0) - TV UI
  - androidx.core:core-ktx (1.12.0) - Kotlin 확장
  - kotlinx-coroutines-android (1.7.3) - 비동기 처리
  - nanohttpd (2.3.1) - HTTP 서버
  - Material Design Components (1.11.0)

#### 📚 문서화
- **BUILD_GUIDE.md** 작성
  - 개발 환경 요구사항
  - 빌드 및 실행 방법
  - 테스트 시나리오
  - 디버깅 가이드
  - 배포 준비 방법

- **DEVELOPMENT_SUMMARY.md** 작성
  - 프로젝트 전체 요약
  - 구현된 기능 상세 설명
  - 기술적 구현 세부사항
  - 향후 개선 계획

- **CLAUDE.md** 업데이트
  - 현재 개발 상태 반영
  - 빌드 명령어 추가
  - 완료된 기능 체크리스트
  - 테스트 필요 항목 명시

### 🔧 기술적 구현 세부사항

#### 권한 처리
- **Android 11+ Scoped Storage 지원**
  - `MANAGE_EXTERNAL_STORAGE` 권한 처리
  - 설정 화면으로 자동 이동
  - 하위 버전 호환성 유지

#### PDF 렌더링 최적화
- **메모리 효율성**
  - PdfRenderer 리소스 자동 해제
  - 페이지별 Bitmap 생성/해제
  - 백그라운드 스레드에서 렌더링

#### 웹 서버 보안
- **파일 업로드 제한**
  - PDF 파일만 허용
  - 50MB 최대 파일 크기
  - 안전한 파일 저장 경로
  - 중복 파일명 처리

#### TV 최적화
- **리모컨 입력 처리**
  - DPAD 키 완벽 지원
  - 포커스 관리 및 하이라이트
  - 큰 터치 영역 및 폰트
  - 애니메이션 효과

### 📊 프로젝트 통계

- **총 파일 수**: 25개 이상
- **코드 라인**: 약 1,500+ 라인
- **Kotlin 파일**: 4개 (MainActivity, PdfViewerActivity, WebServerManager, PdfFileAdapter)
- **레이아웃 파일**: 3개
- **리소스 파일**: 7개
- **지원 해상도**: 5가지 (mdpi ~ xxxhdpi)

### 🎯 달성된 목표

#### 기능적 목표
- ✅ PDF 파일 목록 표시
- ✅ 리모컨으로 파일 선택
- ✅ PDF 페이지 렌더링  
- ✅ 페이지 이동 기능
- ✅ 웹 기반 파일 업로드
- ✅ Android TV 최적화

#### 기술적 목표
- ✅ Kotlin 기반 개발
- ✅ 모던 Android 아키텍처
- ✅ TV UI 가이드라인 준수
- ✅ 메모리 효율적 구현
- ✅ 완전한 문서화

### 🔮 다음 단계 (v0.2.0 예정)

#### 테스트 및 최적화
- [ ] 실제 TV 기기 테스트
- [ ] 성능 최적화
- [ ] 메모리 사용량 분석
- [ ] 사용자 피드백 수집

#### 추가 기능 (v0.3.0+)
- [ ] 북마크 시스템
- [ ] 파일 관리 기능
- [ ] 썸네일 미리보기
- [ ] 줌 기능

#### 정식 릴리스 (v1.0.0)
- [ ] 모든 기능 테스트 완료
- [ ] 성능 최적화 완료
- [ ] 사용자 문서 완성

---

**개발자**: Claude (Anthropic)  
**프로젝트 시작**: 2025-07-04  
**1단계 완료**: 2025-07-04 (v0.1.0)  
**예상 다음 릴리스**: v0.2.0 (테스트 완료 후)