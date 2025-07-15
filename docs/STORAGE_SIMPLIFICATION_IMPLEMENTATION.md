# 스토리지 접근 방식 단순화 구현 보고서

## 1. 개요

**날짜**: 2025-07-15  
**버전**: v0.1.8  
**목표**: Downloads 폴더 의존성 제거 및 웹 업로드 전용 방식으로 전환

이 문서는 `docs/STORAGE_IMPROVEMENT_PLAN.md`에서 제안된 개선 계획을 바탕으로, 더 간단하고 효과적인 접근 방식을 채택하여 구현한 내용을 정리합니다.

## 2. 선택한 접근 방식

### 2.1 계획 변경 사유

원래 계획에서는 Storage Access Framework(SAF) 도입을 고려했으나, **Android TV 환경의 특수성**을 고려하여 더 간단한 접근 방식을 채택했습니다:

- **TV 리모컨 사용성**: SAF 파일 피커는 TV 리모컨으로 사용하기 불편함
- **기존 웹 업로드 방식의 우수성**: 이미 앱 전용 디렉토리를 사용하여 권한 문제가 없음
- **사용자 경험**: 크로스 플랫폼 파일 업로드가 TV 환경에 더 적합

### 2.2 최종 구현 방향

**"웹 업로드 전용 방식"**으로 완전 전환:
- Downloads 폴더 스캔 완전 제거
- 웹 서버를 통한 파일 업로드만 사용
- 모든 외부 저장소 권한 제거

## 3. 구현 내용

### 3.1 파일 목록 로직 단순화

#### 변경 전 (`MainActivity.kt`)
```kotlin
private fun getCurrentPdfFiles(): List<PdfFile> {
    val pdfFiles = mutableListOf<PdfFile>()
    val addedPaths = mutableSetOf<String>() // 중복 방지용
    
    // 앱 전용 디렉토리 스캔
    val appPdfDir = File(getExternalFilesDir(null), "PDFs")
    // ... 앱 전용 디렉토리 처리
    
    // Downloads 폴더도 스캔
    try {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // ... Downloads 폴더 처리 (권한 필요)
    } catch (e: Exception) {
        Log.e("MainActivity", "Cannot access Downloads folder", e)
    }
    
    return pdfFiles
}
```

#### 변경 후 (`MainActivity.kt`)
```kotlin
private fun getCurrentPdfFiles(): List<PdfFile> {
    val pdfFiles = mutableListOf<PdfFile>()
    
    // 앱 전용 디렉토리만 스캔 (권한 불필요)
    val appPdfDir = File(getExternalFilesDir(null), "PDFs")
    if (appPdfDir.exists() && appPdfDir.isDirectory) {
        appPdfDir.listFiles { file ->
            file.isFile && file.extension.equals("pdf", ignoreCase = true)
        }?.forEach { file ->
            pdfFiles.add(PdfFile(
                name = file.name,
                path = file.absolutePath,
                lastModified = file.lastModified(),
                size = file.length()
            ))
        }
    }
    
    return pdfFiles
}
```

### 3.2 권한 관련 코드 완전 제거

#### AndroidManifest.xml
```xml
<!-- 제거된 권한들 -->
<!-- <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" /> -->
<!-- <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" /> -->
<!-- <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" /> -->
<!-- android:requestLegacyExternalStorage="true" -->
```

#### MainActivity.kt
```kotlin
// 제거된 함수들
// private fun checkPermissions() { ... }
// override fun onRequestPermissionsResult(...) { ... }
// override fun onActivityResult(...) { ... }

// 제거된 상수
// private const val PERMISSION_REQUEST_CODE = 100
```

### 3.3 합주 모드 파일 다운로드 경로 수정

#### 변경 전
```kotlin
val downloadPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
```

#### 변경 후
```kotlin
val appPdfDir = File(getExternalFilesDir(null), "PDFs")
if (!appPdfDir.exists()) {
    appPdfDir.mkdirs()
}
val downloadPath = File(appPdfDir, fileName)
```

### 3.4 사용자 안내 메시지 개선

#### strings.xml
```xml
<!-- 변경 전 -->
<string name="no_pdf_files">PDF 파일이 없습니다\nDownload 폴더에 PDF 파일을 추가하세요</string>

<!-- 변경 후 -->
<string name="no_pdf_files">PDF 파일이 없습니다\n설정에서 웹 서버를 켜고 브라우저로 파일을 업로드하세요</string>
```

### 3.5 데이터베이스 마이그레이션 오류 수정

#### 문제점
- 두 페이지 모드 설정이 저장되지 않는 문제 발생
- 원인: 데이터베이스 마이그레이션 v2→v3에서 외래키 제약조건 누락

#### 해결책
```kotlin
// MusicDatabase.kt - 버전 4로 업그레이드
@Database(
    entities = [PdfFile::class, UserPreference::class],
    version = 4,
    exportSchema = false
)

// 새로운 마이그레이션 추가
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 외래키 제약조건을 포함한 테이블 재생성
        database.execSQL("DROP TABLE IF EXISTS user_preferences")
        database.execSQL("""
            CREATE TABLE user_preferences (
                pdfFileId TEXT NOT NULL PRIMARY KEY,
                displayMode TEXT NOT NULL,
                lastPageNumber INTEGER NOT NULL DEFAULT 1,
                bookmarkedPages TEXT NOT NULL DEFAULT '',
                topClippingPercent REAL NOT NULL DEFAULT 0.0,
                bottomClippingPercent REAL NOT NULL DEFAULT 0.0,
                centerPadding REAL NOT NULL DEFAULT 0.0,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(pdfFileId) REFERENCES pdf_files(id) ON DELETE CASCADE
            )
        """)
    }
}
```

### 3.6 UI 개선

#### 다이얼로그 텍스트 수정
```kotlin
// PdfViewerActivity.kt - 페이지 모드 선택 다이얼로그
.setPositiveButton("두 페이지씩 보기") { _, _ ->
    // "두 페이지로 보기" → "두 페이지씩 보기"로 변경
```

## 4. 구현 결과

### 4.1 파일 및 권한 변경 사항

#### 수정된 파일
- `MainActivity.kt`: 파일 목록 로직 단순화, 권한 관련 코드 제거
- `AndroidManifest.xml`: 외부 저장소 권한 제거
- `strings.xml`: 사용자 안내 메시지 개선
- `MusicDatabase.kt`: 마이그레이션 오류 수정
- `PdfViewerActivity.kt`: 다이얼로그 텍스트 개선
- `CLAUDE.md`: 문서 업데이트

#### 제거된 코드
- 권한 체크 함수 (`checkPermissions()`)
- 권한 요청 콜백 (`onRequestPermissionsResult()`)
- 활동 결과 콜백 (`onActivityResult()`)
- Downloads 폴더 스캔 로직
- 권한 관련 import 및 상수

### 4.2 디렉토리 구조 변경

#### 변경 전
```
앱이 접근하는 위치:
1. /storage/emulated/0/Download/ (권한 필요)
2. /storage/emulated/0/Android/data/com.mrgq.pdfviewer/files/PDFs/ (권한 불필요)
```

#### 변경 후
```
앱이 접근하는 위치:
1. /storage/emulated/0/Android/data/com.mrgq.pdfviewer/files/PDFs/ (권한 불필요)
```

### 4.3 사용자 워크플로우 개선

#### 변경 전
1. 앱 설치 → 권한 요청 → 수동 권한 설정
2. Downloads 폴더에 PDF 파일 복사
3. 앱에서 파일 목록 확인

#### 변경 후
1. 앱 설치 (권한 요청 없음)
2. 설정 → 웹 서버 ON
3. 브라우저에서 파일 업로드
4. 앱에서 파일 목록 확인

## 5. 기대 효과

### 5.1 보안 강화
- ✅ 앱 샌드박스 내에서만 파일 관리
- ✅ 시스템 파일에 대한 불필요한 접근 제거
- ✅ Android 최신 보안 정책 완전 준수

### 5.2 사용자 경험 개선
- ✅ 복잡한 권한 설정 과정 완전 제거
- ✅ 크로스 플랫폼 파일 업로드 (PC, 스마트폰 → TV)
- ✅ TV 리모컨에 최적화된 단순한 워크플로우

### 5.3 개발 및 유지보수성
- ✅ 권한 관련 코드 제거로 코드베이스 단순화
- ✅ 플랫폼 간 호환성 문제 해결
- ✅ 향후 Android 정책 변경에 대한 안정성 확보

### 5.4 성능 최적화
- ✅ 권한 체크 로직 제거로 앱 시작 시간 단축
- ✅ 불필요한 디렉토리 스캔 제거
- ✅ 네트워크 권한만 사용하여 리소스 사용 최소화

## 6. 남은 과제

### 6.1 단기 과제
- [ ] 실제 TV 기기에서 전체 워크플로우 테스트
- [ ] 대용량 파일 업로드 안정성 테스트
- [ ] 다양한 Android 버전에서 호환성 확인

### 6.2 장기 과제
- [ ] 웹 인터페이스 UI/UX 개선
- [ ] 업로드 진행률 표시 개선
- [ ] 파일 업로드 속도 최적화

## 7. 결론

이번 구현을 통해 **단순함과 효율성**을 동시에 달성했습니다:

1. **목표 달성**: 외부 저장소 권한 완전 제거
2. **사용자 경험**: TV 환경에 최적화된 직관적인 워크플로우
3. **보안 강화**: 앱 샌드박스 내 파일 관리
4. **미래 호환성**: Android 정책 변경에 안정적 대응

원래 계획했던 SAF 도입보다 **더 간단하고 효과적인 해결책**을 찾아 구현함으로써, 프로젝트의 목표를 성공적으로 달성했습니다.