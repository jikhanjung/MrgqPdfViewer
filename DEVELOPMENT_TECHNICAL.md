# 🔧 MrgqPdfViewer 기술적 구현 세부사항

이 문서는 v0.1.1 개발 과정에서의 주요 기술적 구현 세부사항을 기록합니다.  
최신 변경사항은 CHANGELOG.md를 참조하세요.

## 🔄 v0.1.1 기술적 구현 분석 (2025-07-05)

### 주요 개선사항

#### 1. 🐛 PDF 탐색 안정성 개선
이전 버전에서 파일 간 탐색 시 발생하던 PDF 렌더링 실패 문제를 해결했습니다.

**문제 상황:**
- 다음/이전 파일로 이동 시 "PDF 파일을 열 수 없습니다" 오류 발생
- 파일은 존재하지만 PdfRenderer 생성 과정에서 실패

**해결 방법:**
- PdfRenderer 리소스 정리 로직 개선
- 상세한 디버깅 로그 추가 (ParcelFileDescriptor, PdfRenderer 생성 단계별)
- 예외 처리 강화 및 에러 정보 상세화

#### 2. 🎮 혁신적인 탐색 UX 개선
기존의 Alert 대화상자를 제거하고 키 입력 기반의 직관적인 탐색 시스템으로 전면 개편했습니다.

**이전 방식:**
```
마지막 페이지 → Alert 대화상자 → 버튼 선택
```

**새로운 방식:**
```
마지막 페이지 → [→] 안내 표시 → [→] 다음 파일 이동
               └→ [←] 파일 목록으로
```

#### 3. 📱 새로운 UI 컴포넌트
**navigation Guide Layout 추가:**
- 화면 하단에 안내 메시지 표시
- 부드러운 페이드 인/아웃 애니메이션
- 5초 후 자동 숨김 기능
- Enter 키로 수동 숨김 가능

### 기술적 구현 세부사항

#### 레이아웃 변경사항
```xml
<!-- activity_pdf_viewer.xml에 추가 -->
<LinearLayout
    android:id="@+id/navigationGuide"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@color/pdf_page_info_bg"
    android:visibility="gone">
    
    <TextView android:id="@+id/navigationTitle" />
    <TextView android:id="@+id/navigationMessage" />
    <TextView android:id="@+id/navigationInstructions" />
</LinearLayout>
```

#### 키 입력 처리 로직
```kotlin
// 상태 관리
private var isNavigationGuideVisible = false
private var navigationGuideType = ""  // "end" or "start"

// 마지막 페이지에서의 처리
private fun handleEndOfFile() {
    if (isNavigationGuideVisible && navigationGuideType == "end") {
        // 두 번째 오른쪽 키 → 다음 파일로
        hideNavigationGuide()
        if (currentFileIndex < filePathList.size - 1) {
            loadNextFile()
        }
    } else {
        // 첫 번째 오른쪽 키 → 안내 표시
        showEndOfFileGuide()
    }
}
```

### 사용자 경험 개선

#### 직관적인 키 조작
| 상황 | 키 입력 | 동작 |
|------|---------|------|
| 마지막 페이지 | 첫 번째 `→` | 안내 메시지 표시 |
| 마지막 페이지 | 두 번째 `→` | 다음 파일로 이동 |
| 마지막 페이지 안내 중 | `←` | 파일 목록으로 |
| 첫 페이지 | 첫 번째 `←` | 안내 메시지 표시 |
| 첫 페이지 | 두 번째 `←` | 이전 파일로 이동 |
| 첫 페이지 안내 중 | `→` | 파일 목록으로 |
| 안내 표시 중 | `Enter` | 안내 숨김 |

#### 시각적 피드백
- **안내 메시지 내용 예시:**
  ```
  [파일 끝]
  마지막 페이지입니다.
  다음 파일: 2-1. 예완예진_듀오.pdf
  
  → 다음 파일로 이동
  ← 파일 목록으로 돌아가기
  ```

### 개발 환경 최적화

#### 하이브리드 워크플로우 확립
```
WSL2 (Ubuntu) + Claude Code
├── 소스 코드 편집
├── 파일 구조 관리
└── 문서 작성

Windows 11 + Android Studio
├── 프로젝트 빌드
├── 에뮬레이터 실행
├── 디버깅 및 로그 확인
└── APK 설치 및 테스트
```

#### 디버깅 강화
```kotlin
// 추가된 로깅 예시
Log.d("PdfViewerActivity", "File permissions OK, creating ParcelFileDescriptor...")
Log.d("PdfViewerActivity", "ParcelFileDescriptor created successfully")
Log.d("PdfViewerActivity", "Creating PdfRenderer...")
Log.d("PdfViewerActivity", "PdfRenderer created successfully")
Log.d("PdfViewerActivity", "Page count retrieved: $pageCount")
```

### 코드 품질 개선

#### Alert 대화상자 제거
- `AlertDialog.Builder` import 제거
- `showEndOfFileDialog()`, `showStartOfFileDialog()` 함수 제거
- 약 100줄의 코드 정리

#### 메모리 관리 강화
```kotlin
private fun loadFile(filePath: String, fileName: String, goToLastPage: Boolean = false) {
    // 리소스 정리 강화
    Log.d("PdfViewerActivity", "Closing current PDF resources...")
    currentPage?.close()
    currentPage = null
    
    pdfRenderer?.close()
    pdfRenderer = null
    // ...
}
```

### 테스트 결과

#### 성공적으로 해결된 문제들
1. ✅ PDF 파일 탐색 실패 → 안정적인 파일 전환
2. ✅ 번거로운 Alert UI → 직관적인 키 조작
3. ✅ 개발 환경 분리 → 효율적인 하이브리드 워크플로우

#### 검증된 기능들
- 파일 목록 표시 및 선택
- PDF 페이지 렌더링 및 탐색
- 웹서버를 통한 파일 업로드
- 다음/이전 파일 간 탐색
- 새로운 키 입력 기반 UI

### 다음 단계 계획

#### 단기 목표 (v0.1.2)
- [ ] 실제 Android TV 기기에서 테스트
- [ ] 성능 최적화 (큰 PDF 파일 처리)
- [ ] 추가 에러 케이스 처리

#### 중기 목표 (v0.2.0)
- [ ] 북마크 기능 추가
- [ ] 마지막 읽은 페이지 기억
- [ ] PDF 썸네일 미리보기

#### 장기 목표 (v1.0.0)
- [ ] 줌 인/아웃 기능
- [ ] 음성 인식 페이지 이동
- [ ] 고급 파일 관리 기능

---

### 프로젝트 통계 (v0.1.1)

**코드 변경사항:**
- 수정된 파일: 2개 (PdfViewerActivity.kt, activity_pdf_viewer.xml)
- 추가된 함수: 6개 (navigation 관련)
- 제거된 함수: 2개 (Alert 대화상자)
- 순 코드 증가: +50줄 (UI 로직 추가, Alert 제거로 상쇄)

**문서 업데이트:**
- CHANGELOG.md: v0.1.1 릴리스 노트 추가
- CLAUDE.md: 버전 정보 및 완료 기능 업데이트
- DEVELOPMENT_SUMMARY_V2.md: 이 문서 작성

**개발 기간:**
- 이슈 분석: 30분
- 디버깅 로그 추가: 15분
- UI 리팩토링: 45분
- 테스트 및 검증: 30분
- 문서화: 30분
- **총 소요시간**: 약 2.5시간

---

## 🎯 결론

v0.1.1 업데이트를 통해 MrgqPdfViewer는 단순한 PDF 뷰어에서 사용자 친화적인 Android TV 전용 악보 리더로 한 단계 성장했습니다. 특히 키 입력 기반의 직관적인 탐색 시스템은 리모컨 환경에서의 사용성을 크게 향상시켰습니다.

**핵심 성취:**
1. **안정성**: PDF 렌더링 안정성 확보
2. **사용성**: 직관적인 키 조작 시스템 구축
3. **효율성**: 개발 워크플로우 최적화
4. **확장성**: 향후 기능 추가를 위한 견고한 기반 마련

이제 프로젝트는 실제 사용자 테스트와 추가 기능 개발 단계로 진입할 준비가 되었습니다.

---

## 🎨 v0.1.3 UI/UX 혁신 (2025-07-06)

### 좌우 분할 카드 탐색 시스템

#### 기존 문제점
```kotlin
// 이전: 단순한 텍스트 안내
binding.navigationMessage.text = "마지막 페이지입니다."
```

#### 혁신적 해결방안
```xml
<!-- 좌우 분할 카드 레이아웃 -->
<LinearLayout
    android:id="@+id/navigationGuide"
    android:orientation="horizontal"
    android:layout_width="0dp"
    android:layout_height="wrap_content">
    
    <LinearLayout
        android:id="@+id/leftNavigation"
        android:layout_width="0dp"
        android:layout_weight="1"
        android:background="@drawable/card_background">
        
        <ImageView
            android:src="@drawable/ic_arrow_left_large"
            android:layout_width="48dp"
            android:layout_height="48dp" />
        
        <TextView android:id="@+id/leftNavText" />
        <TextView android:id="@+id/leftNavSubText" />
    </LinearLayout>
    
    <LinearLayout android:id="@+id/rightNavigation" ... >
        <!-- 오른쪽 카드 구조 동일 -->
    </LinearLayout>
</LinearLayout>
```

#### 동적 컨텍스트 표시
```kotlin
private fun showEndOfFileGuide() {
    val hasNextFile = currentFileIndex < filePathList.size - 1
    
    // 왼쪽 카드: 항상 파일 목록
    binding.leftNavigation.visibility = View.VISIBLE
    binding.leftNavText.text = "파일 목록"
    binding.leftNavSubText.text = "목록으로 돌아가기"
    
    // 오른쪽 카드: 조건부 표시
    if (hasNextFile) {
        binding.rightNavigation.visibility = View.VISIBLE
        binding.rightNavText.text = "다음 파일"
        binding.rightNavSubText.text = fileNameList[currentFileIndex + 1]
    } else {
        binding.rightNavigation.visibility = View.GONE
    }
}
```

### 고해상도 아이콘 시스템

#### 벡터 기반 큰 화살표
```xml
<!-- ic_arrow_left_large.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z"/>
</vector>
```

#### 동적 색상 관리
```kotlin
// 포커스 상태에 따른 색상 변경
private fun updateNavigationColors(focused: Boolean) {
    val iconColor = if (focused) R.color.tv_primary else R.color.tv_text_primary
    binding.leftArrow.setColorFilter(ContextCompat.getColor(this, iconColor))
}
```

### 페이지 정보 최적화 진화

#### 단계별 크기 축소
```xml
<!-- 1단계: 24sp → 과도하게 큰 폰트 -->
<TextView android:textSize="24sp" />

<!-- 2단계: 16sp → 적당한 크기 -->
<TextView android:textSize="16sp" />

<!-- 3단계: 11sp → 최적화된 크기 -->
<TextView android:textSize="11sp" />
```

#### 배경 투명도 조정
```xml
<!-- colors.xml -->
<color name="pdf_page_info_bg_v1">#CC000000</color> <!-- 80% 불투명 -->
<color name="pdf_page_info_bg_v2">#80000000</color> <!-- 50% 불투명 -->
<color name="pdf_page_info_bg_v3">#66000000</color> <!-- 40% 불투명 -->
```

#### 위치 최적화 실험
```kotlin
// 위치 변경 이력
// 1. 오른쪽 하단 → 너무 눈에 띄지 않음
// 2. 화면 정중앙 → PDF 내용 가림
// 3. 하단 중앙 → 최적 위치 발견

private fun positionPageInfo() {
    val layoutParams = binding.pageInfo.layoutParams as ConstraintLayout.LayoutParams
    layoutParams.apply {
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        bottomMargin = resources.getDimensionPixelSize(R.dimen.page_info_margin)
    }
}
```

---

## 🎯 v0.1.4 고급 기능 구현 (2025-07-06)

### 설정 화면 아키텍처

#### SharedPreferences 기반 설정 관리
```kotlin
class SettingsActivity : AppCompatActivity() {
    private lateinit var preferences: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        setupUI()
        updateSettingsInfo()
        setupPdfFileInfo()
    }
}
```

#### 복합 설정 키 시스템
```kotlin
// 파일별 설정 키 생성
private fun getFileKey(filePath: String): String {
    return try {
        val file = File(filePath)
        "${file.name}_${file.length()}" // 파일명 + 크기로 유니크 키
    } catch (e: Exception) {
        filePath.hashCode().toString()
    }
}

// 설정 저장
private fun saveFilePreference(fileKey: String, mode: String) {
    preferences.edit().putString("file_mode_$fileKey", mode).apply()
}
```

### 두 페이지 모드 구현

#### 화면 비율 분석 로직
```kotlin
private fun checkAndSetTwoPageMode(onComplete: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        val firstPage = pdfRenderer?.openPage(0)
        firstPage?.let { page ->
            val pdfWidth = page.width
            val pdfHeight = page.height
            page.close()
            
            val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
            val pdfAspectRatio = pdfWidth.toFloat() / pdfHeight.toFloat()
            
            withContext(Dispatchers.Main) {
                val aspectRatioDiff = kotlin.math.abs(screenAspectRatio - pdfAspectRatio)
                
                if (aspectRatioDiff < 0.3f) {
                    // 비율이 유사하면 단일 페이지 모드
                    isTwoPageMode = false
                    onComplete()
                } else if (screenAspectRatio > 1.0f && pdfAspectRatio < 1.0f) {
                    // 가로 화면 + 세로 PDF = 사용자 선택
                    showTwoPageModeDialog(fileKey, onComplete)
                } else {
                    // 기타 경우는 단일 페이지 모드
                    isTwoPageMode = false
                    onComplete()
                }
            }
        }
    }
}
```

#### 사용자 선택 대화상자
```kotlin
private fun showTwoPageModeDialog(fileKey: String, onComplete: () -> Unit) {
    val linearLayout = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(60, 40, 60, 40)
    }
    
    val messageText = android.widget.TextView(this).apply {
        text = "세로 PDF를 가로 화면에서 보고 있습니다.\n'$pdfFileName' 파일을 어떻게 표시하시겠습니까?"
        textSize = 16f
    }
    
    val rememberCheckbox = android.widget.CheckBox(this).apply {
        text = "이 선택을 기억하기"
        isChecked = true
    }
    
    AlertDialog.Builder(this)
        .setTitle("페이지 표시 모드")
        .setView(linearLayout)
        .setPositiveButton("두 페이지로 보기") { _, _ ->
            isTwoPageMode = true
            if (rememberCheckbox.isChecked) {
                saveFilePreference(fileKey, "two")
            }
            onComplete()
        }
        .setNegativeButton("한 페이지씩 보기") { _, _ ->
            isTwoPageMode = false
            if (rememberCheckbox.isChecked) {
                saveFilePreference(fileKey, "single")
            }
            onComplete()
        }
        .show()
}
```

### 고해상도 PDF 렌더링

#### 최적 스케일 계산 알고리즘
```kotlin
private fun calculateOptimalScale(pageWidth: Int, pageHeight: Int): Float {
    val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
    val pageRatio = pageWidth.toFloat() / pageHeight.toFloat()
    
    val scale = if (pageRatio > screenRatio) {
        // 페이지가 가로로 긴 경우 - 가로 기준 맞춤
        screenWidth.toFloat() / pageWidth.toFloat()
    } else {
        // 페이지가 세로로 긴 경우 - 세로 기준 맞춤
        screenHeight.toFloat() / pageHeight.toFloat()
    }
    
    // 2-4배 스케일링으로 고해상도 보장
    val finalScale = (scale * 2.0f).coerceIn(2.0f, 4.0f)
    return finalScale
}
```

#### Matrix 변환 기반 렌더링
```kotlin
private suspend fun renderSinglePage(index: Int): Bitmap {
    val page = currentPage ?: throw Exception("Failed to open page $index")
    
    val scale = calculateOptimalScale(page.width, page.height)
    val renderWidth = (page.width * scale).toInt()
    val renderHeight = (page.height * scale).toInt()
    
    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    
    // Matrix 변환으로 고품질 스케일링
    val matrix = android.graphics.Matrix()
    matrix.setScale(scale, scale)
    
    val rect = android.graphics.Rect(0, 0, renderWidth, renderHeight)
    page.render(bitmap, rect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    
    return bitmap
}
```

#### 두 페이지 결합 렌더링
```kotlin
private suspend fun renderTwoPages(leftPageIndex: Int): Bitmap {
    // 1. 왼쪽 페이지 렌더링
    val leftPage = pdfRenderer?.openPage(leftPageIndex)
    val leftBitmap = renderPageToBitmap(leftPage)
    leftPage?.close()
    
    // 2. 오른쪽 페이지 렌더링
    val rightPage = pdfRenderer?.openPage(leftPageIndex + 1)
    val rightBitmap = renderPageToBitmap(rightPage)
    rightPage?.close()
    
    // 3. 두 비트맵 결합
    val combinedWidth = leftBitmap.width + rightBitmap.width
    val combinedHeight = maxOf(leftBitmap.height, rightBitmap.height)
    
    val combinedBitmap = Bitmap.createBitmap(
        combinedWidth, combinedHeight, Bitmap.Config.ARGB_8888
    )
    
    val canvas = Canvas(combinedBitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(leftBitmap, 0f, 0f, null)
    canvas.drawBitmap(rightBitmap, leftBitmap.width.toFloat(), 0f, null)
    
    // 메모리 정리
    leftBitmap.recycle()
    rightBitmap.recycle()
    
    return combinedBitmap
}
```

### 웹 인터페이스 고도화

#### XMLHttpRequest 기반 진행률 추적
```javascript
// FormData 생성 및 파일 인덱싱
const formData = new FormData();
selectedFiles.forEach((file, index) => {
    const uploadName = 'file_' + index + '.pdf';
    const newFile = new File([file], uploadName, {type: file.type});
    formData.append('files', newFile);
    
    // Base64 인코딩된 원본 파일명
    const base64Name = btoa(unescape(encodeURIComponent(file.name)));
    formData.append('filename_' + index, base64Name);
});

// XMLHttpRequest로 진행률 추적
const xhr = new XMLHttpRequest();

xhr.upload.addEventListener('progress', (e) => {
    if (e.lengthComputable) {
        const percentComplete = Math.round((e.loaded / e.total) * 100);
        progressBar.style.width = percentComplete + '%';
        progressText.textContent = percentComplete + '%';
        
        if (percentComplete === 100) {
            uploadStatus.textContent = '서버에서 처리 중...';
        } else {
            const loaded = formatFileSize(e.loaded);
            const total = formatFileSize(e.total);
            uploadStatus.textContent = `업로드 중: ${loaded} / ${total}`;
        }
    }
});
```

#### 숫자 정렬 알고리즘
```kotlin
// 서버측 파일 파라미터 정렬
val fileParams = files.keys.filter { it.startsWith("files") }.sortedBy { key ->
    if (key == "files") {
        0 // "files" (인덱스 없음)는 첫 번째
    } else {
        key.removePrefix("files").toIntOrNull() ?: Int.MAX_VALUE
    }
}

// 결과: ["files", "files1", "files2", ..., "files10", "files11"]
// 기존: ["files", "files1", "files10", "files11", "files2", ...]
```

#### Base64 파일명 처리
```kotlin
// 업로드시 인코딩
val base64Filename = session.parms["filename_$index"]
var fileName = "uploaded_$index.pdf"

if (!base64Filename.isNullOrBlank()) {
    try {
        val decodedBytes = android.util.Base64.decode(base64Filename, android.util.Base64.DEFAULT)
        fileName = String(decodedBytes, Charsets.UTF_8)
        Log.d(TAG, "Decoded Base64 filename: $fileName")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode Base64 filename: $base64Filename", e)
        fileName = "uploaded_$index.pdf"
    }
}
```

### 메모리 관리 최적화

#### 안전한 리소스 해제
```kotlin
// PDF 페이지 안전 종료
try {
    currentPage?.close()
} catch (e: Exception) {
    Log.w("PdfViewerActivity", "Current page already closed: ${e.message}")
}
currentPage = null

// PdfRenderer 안전 종료
try {
    pdfRenderer?.close()
} catch (e: Exception) {
    Log.w("PdfViewerActivity", "PdfRenderer already closed: ${e.message}")
}
pdfRenderer = null
```

#### 코루틴 스코프 관리
```kotlin
class SettingsActivity : AppCompatActivity() {
    private fun deleteAllPdfFiles(pdfFiles: List<File>) {
        CoroutineScope(Dispatchers.IO).launch {
            var deletedCount = 0
            var failedCount = 0
            
            for (file in pdfFiles) {
                try {
                    if (file.delete()) {
                        deletedCount++
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                }
            }
            
            withContext(Dispatchers.Main) {
                // UI 업데이트
                updateFileCount()
            }
        }
    }
}
```

---

## 🎯 주요 기술적 성취 요약

### 아키텍처 진화
1. **v0.1.1**: 기본 탐색 시스템 구축
2. **v0.1.3**: UI/UX 혁신 (카드 시스템)
3. **v0.1.4**: 고급 기능 및 설정 시스템

### 핵심 기술 구현
1. **동적 UI 생성**: 코드 기반 대화상자 레이아웃
2. **고해상도 렌더링**: Matrix 변환 기반 PDF 스케일링
3. **파일 관리**: SharedPreferences 기반 설정 시스템
4. **웹 API**: RESTful 엔드포인트 (/list, /delete, /deleteAll)
5. **메모리 최적화**: 안전한 리소스 해제 패턴

### 성능 최적화
- PDF 렌더링: 2-4배 스케일링으로 품질 향상
- 메모리 관리: try-catch 기반 안전한 리소스 해제
- 웹 업로드: 진행률 추적으로 사용자 경험 개선
- 파일 정렬: 숫자 기반 정렬로 순서 문제 해결

이러한 기술적 구현을 통해 MrgqPdfViewer는 단순한 PDF 뷰어에서 완전한 Android TV 최적화 악보 리더로 발전했습니다.