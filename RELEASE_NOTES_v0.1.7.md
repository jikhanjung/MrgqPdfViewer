# MrgqPdfViewer v0.1.7 Release Notes

## 주요 업데이트 내용 (2025-07-13)

### 🗄️ Room Database 도입 및 PDF 메타데이터 관리 시스템

#### 새로운 데이터베이스 아키텍처
- **Room Database 구현**: SharedPreferences에서 SQLite 기반 Room 데이터베이스로 전환
- **PDF 파일 엔티티**: 파일별 메타데이터 저장 (파일명, 경로, 페이지 수, 방향, 크기)
- **사용자 설정 엔티티**: 파일별 표시 모드 및 사용자 선호도 저장
- **Repository 패턴**: 데이터 접근 계층 추상화로 유지보수성 향상

#### 새로운 파일 구조
```
database/
├── MusicDatabase.kt           # Room 데이터베이스 설정
├── entity/
│   ├── PdfFile.kt            # PDF 파일 메타데이터 엔티티
│   └── UserPreference.kt     # 사용자 설정 엔티티
├── dao/
│   ├── PdfFileDao.kt         # PDF 파일 데이터 접근 객체
│   └── UserPreferenceDao.kt  # 사용자 설정 데이터 접근 객체
└── converter/
    └── Converters.kt         # Enum 타입 컨버터

repository/
└── MusicRepository.kt        # 데이터 접근 통합 관리

utils/
└── PdfAnalyzer.kt           # PDF 분석 및 메타데이터 추출
```

### 📱 표시 모드 관리 시스템 개선

#### DisplayMode 열거형 도입
- **AUTO**: 화면과 PDF 비율에 따라 자동 결정 (기본값)
- **SINGLE**: 항상 한 페이지씩 표시
- **DOUBLE**: 항상 두 페이지씩 표시

#### 파일별 설정 저장
- 각 PDF 파일마다 개별적인 표시 모드 저장
- 마지막 읽은 페이지 번호 기억
- 북마크 페이지 관리 (향후 확장 가능)

### 🔧 두 페이지 모드 Aspect Ratio 문제 해결

#### 문제 분석 및 해결
**발견된 문제:**
- 두 페이지 모드에서 PDF가 가로로 늘어나 보이는 현상
- PageCache가 개별 페이지를 화면 비율로 강제 변환
- 원본 PDF 비율 0.707 → 왜곡된 비율 0.889로 변형

**해결 방법:**
```kotlin
// 수정 전 (PageCache.kt)
if (isTwoPageMode) {
    targetWidth = (screenWidth / 2 * renderScale).toInt()  // 화면 절반 크기 강제
    targetHeight = (screenHeight * renderScale).toInt()
}

// 수정 후
if (isTwoPageMode) {
    targetWidth = (pdfWidth * renderScale).toInt()   // 원본 PDF 크기 유지
    targetHeight = (pdfHeight * renderScale).toInt()
}
```

**결과:**
- 개별 페이지: 595×841 → 1528×2160 (원본 비율 0.707 유지)
- 합쳐진 비트맵: 3056×2160 (올바른 비율 1.414)
- 화면 표시: 높이 기준으로 올바르게 스케일링

### 🚀 성능 및 안정성 개선

#### 스케일 계산 로직 개선
- `calculateOptimalScale` 함수에 `forTwoPageMode` 매개변수 추가
- 두 페이지 모드에서 합쳐진 크기 기준으로 스케일 계산
- 상세한 디버깅 로그로 aspect ratio 추적 가능

#### 캐시 관리 강화
- 표시 모드 전환 시 캐시 완전 초기화
- 올바른 스케일로 재계산 후 캐시 업데이트
- 메모리 누수 방지 및 성능 최적화

#### 로깅 시스템 확장
```kotlin
Log.d("PdfViewerActivity", "=== INDIVIDUAL PAGE ASPECT RATIOS ===")
Log.d("PdfViewerActivity", "Left page: 595x841, aspect ratio: 0.7074074")
Log.d("PdfViewerActivity", "Right page: 595x841, aspect ratio: 0.7074074")
Log.d("PdfViewerActivity", "Combined: 1190x841, aspect ratio: 1.4148148")
```

### 📊 기술적 세부사항

#### 데이터베이스 스키마
**PdfFile 테이블:**
- id (PRIMARY KEY): 파일 해시 기반 고유 식별자
- filename, filePath: 파일 정보
- totalPages: 총 페이지 수
- orientation: 페이지 방향 (PORTRAIT/LANDSCAPE)
- width, height: PDF 원본 크기
- 음악 메타데이터 필드 (composer, title, genre 등)

**UserPreference 테이블:**
- pdfFileId (PRIMARY KEY): PDF 파일 참조
- displayMode: 표시 모드 설정
- lastPageNumber: 마지막 읽은 페이지
- bookmarkedPages: 북마크된 페이지 목록

#### 비동기 처리
- Kotlin Coroutines를 이용한 데이터베이스 작업
- Flow 기반 반응형 데이터 업데이트
- 백그라운드 스레드에서 PDF 분석 수행

### 🔄 마이그레이션 정보

#### 기존 설정 호환성
- SharedPreferences 기반 설정은 자동으로 데이터베이스로 마이그레이션되지 않음
- 사용자는 파일별로 표시 모드를 다시 설정해야 함
- 기존 캐시 및 임시 파일은 자동으로 정리됨

#### 업그레이드 절차
1. 앱 재설치 또는 업데이트
2. 첫 실행 시 PDF 파일들의 메타데이터 자동 분석
3. 원하는 표시 모드를 파일별로 설정

### 🐛 해결된 버그

1. **두 페이지 모드 aspect ratio 왜곡**: PageCache 렌더링 로직 수정으로 완전 해결
2. **설정 화면 중복 카드**: 기존 "파일별 설정" 카드 제거, "화면 표시 모드" 카드로 통합
3. **스케일 계산 오류**: 두 페이지 모드에서 개별 페이지가 아닌 합쳐진 크기 기준으로 계산
4. **캐시 불일치**: 모드 전환 시 캐시 완전 초기화로 정확한 렌더링 보장

### 📈 성능 지표

#### 렌더링 품질 향상
- 두 페이지 모드에서 원본 PDF 품질 100% 보존
- 고해상도 스케일링 (2-4배) 유지
- 메모리 사용량 최적화

#### 데이터베이스 성능
- Room 데이터베이스로 빠른 설정 조회
- 인덱스 기반 효율적인 파일 검색
- 백그라운드 데이터 처리로 UI 반응성 유지

### 🔮 향후 계획

#### 단기 목표 (v0.1.8)
- 데이터베이스 마이그레이션 도구 개발
- 음악 메타데이터 필드 활용 (작곡가, 제목 등)
- 북마크 시스템 구현

#### 중기 목표 (v0.2.x)
- PDF 썸네일 생성 및 저장
- 고급 검색 기능 (메타데이터 기반)
- 사용자 설정 백업/복원 기능

### 🔧 개발자 정보

#### 빌드 요구사항
- Room 데이터베이스: `androidx.room:room-runtime:2.6.1`
- Kotlin 어노테이션 처리: `kapt` 플러그인 필요
- 최소 API 레벨: 21 (Android 5.0)

#### 테스트 상태
- ✅ 기본 PDF 렌더링 및 두 페이지 모드
- ✅ 데이터베이스 CRUD 작업
- ✅ Aspect ratio 보존 확인
- ✅ 캐시 관리 및 메모리 최적화
- 🟡 대용량 PDF 파일 성능 테스트 필요
- 🟡 다양한 PDF 형식 호환성 검증 필요

---

**버전**: v0.1.7  
**릴리스 날짜**: 2025-07-13  
**주요 기여자**: Claude AI Assistant  
**개발 환경**: WSL2 + Windows 11 Android Studio