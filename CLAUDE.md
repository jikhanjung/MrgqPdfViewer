# MrgqPdfViewer 프로젝트 가이드

## 프로젝트 개요
Android TV OS (Z18TV Pro)용 PDF 악보 리더 앱으로, 무선 파일 업로드와 리모컨을 이용한 탐색 기능을 제공합니다.

**현재 버전**: v0.1.3 (2025-07-06)  
**빌드 상태**: 🟢 빌드 가능  
**테스트 상태**: 🟢 실제 TV 기기 테스트 완료

## 주요 기능
- **파일 목록 뷰어**: `/sdcard/Download/` 디렉토리의 PDF 파일 목록 표시
- **정렬 및 관리**: 이름순/시간순 정렬, 개별 파일 삭제 기능
- **상세 파일 정보**: 파일 크기, 수정 시간 표시
- **웹 서버 업로드**: 8080 포트의 HTTP 서버를 통한 브라우저 기반 무선 파일 업로드  
- **PDF 뷰어**: Android PdfRenderer를 이용한 내장 PDF 렌더링
- **혁신적인 파일 탐색**: 좌우 분할 카드 UI로 직관적인 파일 간 이동
- **리모컨 탐색**: DPAD 및 ENTER 키 완벽 지원
- **TV 최적화**: Android TV UI 가이드라인 준수

## 기술 스택
- **플랫폼**: Android TV OS (minSdk 21, targetSdk 30)
- **언어**: Kotlin
- **PDF 렌더링**: PdfRenderer (Android 5.0+ 내장)
- **웹 서버**: NanoHTTPD 2.3.1
- **입력 처리**: 리모컨용 KeyEvent 처리
- **비동기**: Kotlin Coroutines

## 디렉토리 구조
```
/storage/emulated/0/Download/
├── 악보1.pdf
├── 악보2.pdf
└── 악보3.pdf
```

## 필요한 권한
- `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (API 28 이하)
- `MANAGE_EXTERNAL_STORAGE` 또는 scoped storage (API 29+)
- `INTERNET`, `ACCESS_NETWORK_STATE` (웹 서버 기능용)

## 핵심 컴포넌트

### 1. PDF 파일 목록
- PDF 파일만 필터링하여 표시
- 파일명 정렬 (A-Z)
- 썸네일 없는 단순 리스트 뷰
- 리모컨 탐색 (DPAD_UP/DOWN, ENTER)

### 2. 웹 서버
- 기본 포트: 8080
- HTML 업로드 폼 (단일/다중 파일 지원)
- 업로드 후 자동 파일 저장
- ON/OFF 토글 기능

### 3. PDF 뷰어
- 한 번에 한 페이지 표시
- 고정 배율 또는 화면 맞춤 비율
- 리모컨으로 페이지 이동 (LEFT/RIGHT)
- 선택적 페이지 번호 표시

## 리모컨 키 매핑
- **DPAD_UP/DOWN**: 파일 목록 탐색
- **DPAD_LEFT/RIGHT**: PDF 뷰어에서 이전/다음 페이지
- **ENTER**: 파일 선택 또는 동작 확인

## 개발 가이드라인
- Android SDK 네이티브 컴포넌트 사용
- 파일 작업용 적절한 에러 처리 구현
- TV 하드웨어에서 원활한 성능 보장
- Android TV UI 가이드라인 준수
- 리모컨 입력으로 철저한 테스트

## 개발 환경

### 개발 워크플로우
- **코딩**: WSL2의 Claude Code에서 소스 코드 편집
- **빌드/테스트**: Windows 11의 Android Studio에서 실행
- **디버깅**: Windows 11에서 로그 확인 및 에뮬레이터/실기기 테스트

### 빌드 명령어 (Windows 11에서 실행)
```bash
# Debug 빌드
./gradlew assembleDebug

# Release 빌드  
./gradlew assembleRelease

# 앱 설치 (ADB 연결된 상태)
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 테스트 항목
기능 구현 시 다음 항목들을 테스트하세요:
- 파일 목록 기능 (PDF 파일 인식 및 표시)
- PDF 렌더링 성능 (다양한 크기 파일)
- 웹 서버 업로드/다운로드 (브라우저 테스트)
- 리모컨 반응성 (모든 키 입력)
- 저장소 권한 처리 (Android 11+ 포함)

## 프로젝트 파일 구조

### 핵심 파일
- `MainActivity.kt`: 메인 화면 및 파일 목록
- `PdfViewerActivity.kt`: PDF 뷰어 화면
- `WebServerManager.kt`: HTTP 서버 관리
- `PdfFileAdapter.kt`: 파일 목록 어댑터

### 리소스 파일
- `colors.xml`: TV 최적화 색상 팔레트
- `themes.xml`: TV 전용 테마
- `dimens.xml`: TV 화면 크기 고려 치수
- `strings.xml`: 다국어 지원 텍스트

## 현재 구현 상태

### ✅ 완료된 기능
- [x] 프로젝트 초기 설정 및 구조
- [x] MainActivity 파일 목록 표시
- [x] 파일 정렬 기능 (이름순/시간순)
- [x] 파일 삭제 기능 및 확인 대화상자
- [x] 상세 파일 정보 표시 (크기, 수정시간)
- [x] PdfViewerActivity PDF 렌더링
- [x] 혁신적인 파일 탐색 UI (좌우 분할 카드)
- [x] 큰 화살표 아이콘과 직관적인 안내
- [x] WebServerManager 파일 업로드
- [x] 하단 전체폭 메시지 시스템
- [x] 리모컨 입력 처리
- [x] TV UI 최적화 및 페이지 정보 조정
- [x] 권한 처리 (Android TV OS 11 지원)
- [x] 새로운 MRGQ 브랜딩 아이콘
- [x] 빌드 설정 완료
- [x] 파일 간 탐색 기능 (다음/이전 파일)
- [x] 키 입력 기반 탐색 인터페이스
- [x] PDF 렌더링 디버깅 및 안정성 개선
- [x] UI 메시지 최적화 (불필요한 토스트 제거)

### 🟢 테스트 완료
- [x] 파일 목록 표시 및 탐색
- [x] 파일 정렬 기능 (이름순/시간순)
- [x] 파일 삭제 기능
- [x] PDF 렌더링 기본 기능
- [x] 웹서버 파일 업로드
- [x] 파일 간 탐색 (다음/이전)
- [x] 새로운 탐색 UI (좌우 분할 카드)
- [x] 키 입력 기반 UI 탐색
- [x] 실제 Android TV OS 11 기기 테스트
- [x] 권한 처리 안정성 테스트

### 🟡 지속적인 개선 영역
- [ ] 다양한 PDF 파일 크기 최적화
- [ ] 네트워크 업로드 대용량 파일 테스트
- [ ] 메모리 사용량 최적화
- [ ] 장시간 사용 안정성 개선

## 향후 확장 가능성 (2-3단계)
- 북마크/즐겨찾기 시스템
- 마지막 읽은 페이지 기억 기능
- PDF 썸네일 또는 첫 페이지 미리보기
- 파일 삭제/이름 변경 기능
- 줌 인/아웃 기능
- 음성 인식으로 페이지 넘기기
- 박수나 소리 감지 기반 탐색
- JSON 기반 메타데이터 관리