# MrgqPdfViewer 프로젝트 가이드

## 프로젝트 개요
Android TV OS (Z18TV Pro)용 PDF 악보 리더 앱으로, 무선 파일 업로드와 리모컨을 이용한 탐색 기능을 제공합니다.

## 주요 기능
- **파일 목록 뷰어**: `/sdcard/Download/` 또는 앱 내부 디렉토리의 PDF 파일 목록 표시
- **웹 서버 업로드**: 8080 포트의 HTTP 서버를 통한 브라우저 기반 무선 파일 업로드
- **PDF 뷰어**: Android PdfRenderer를 이용한 내장 PDF 렌더링
- **리모컨 탐색**: DPAD 및 ENTER 키 완벽 지원

## 기술 스택
- **플랫폼**: Android TV OS
- **언어**: Kotlin/Java
- **PDF 렌더링**: PdfRenderer (Android 5.0+ 내장)
- **웹 서버**: Ktor embedded HTTP server 또는 NanoHTTPD
- **입력 처리**: 리모컨용 KeyEvent 처리

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

## 테스트 명령어
기능 구현 시 다음 항목들을 테스트하세요:
- 파일 목록 기능
- PDF 렌더링 성능
- 웹 서버 업로드/다운로드
- 리모컨 반응성
- 저장소 권한 처리

## 향후 확장 가능성
- 음성 인식으로 페이지 넘기기
- 박수나 소리 감지 기반 탐색
- 북마크/즐겨찾기 시스템
- PDF 썸네일 또는 첫 페이지 미리보기
- JSON 기반 메타데이터 관리