# PDF 렌더러 동시성 문제 해결 가이드

**날짜**: 2025-07-27  
**버전**: v0.1.10  
**문제**: 페이지 넘기기 버튼 반복 클릭 시 "PDF 파일을 열 수 없습니다" 에러 발생  
**심각도**: 중간 (기능 영향 없으나 사용자 불안감 조성)

## 📋 문제 개요

### 증상
- 페이지 넘기기 버튼을 빠르게 반복 클릭 시 간헐적으로 "PDF 파일을 열 수 없습니다" 토스트 메시지 표시
- 실제 PDF 파일이나 앱 기능에는 영향 없음
- 이후 정상 작동하지만 사용자에게 불안감 조성

### 발생 조건
- 빠른 연속 페이지 전환 (리모컨 또는 터치)
- 특히 두 페이지 모드에서 더 자주 발생
- 캐시 미스 상황에서 렌더링 중 추가 요청 시

## 🔍 근본 원인 분석

### 1. PdfRenderer 동시성 문제

**문제 위치**: `PdfViewerActivity.kt:718-719`
```kotlin
currentPage = pdfRenderer?.openPage(index)
val page = currentPage ?: throw Exception("Failed to open page $index")
```

**원인**:
- Android의 `PdfRenderer`는 스레드 안전하지 않음
- 여러 코루틴이 동시에 같은 `PdfRenderer` 인스턴스에 접근
- 이전 `openPage()` 작업 완료 전 새로운 요청 발생 시 충돌

### 2. 리소스 경합 시나리오

**시나리오 A**: 단일 페이지 모드
```
Thread 1: pdfRenderer.openPage(5) 시작
Thread 2: pdfRenderer.openPage(6) 시작 ← 충돌 발생
Thread 1: Exception 발생
```

**시나리오 B**: 두 페이지 모드 (더 복잡)
```kotlin
// renderTwoPagesUnified()에서
val leftPage = pdfRenderer?.openPage(leftPageIndex)     // 라인 767
val rightPage = pdfRenderer?.openPage(leftPageIndex + 1) // 라인 791
```

### 3. 예외 전파 체인

```
사용자 입력 → showPage() → CoroutineScope.launch {
    renderTwoPagesUnified() 또는 renderSinglePage()
    ↓ (PdfRenderer 충돌)
    Exception 발생
    ↓
    catch 블록에서 getString(R.string.error_loading_pdf) 표시
}
```

**에러 발생 위치**:
- `PdfViewerActivity.kt:637`: showPage() 메인 catch 블록
- `renderTwoPagesUnified()`: 페이지 열기 실패 시 fallback

## 💡 해결 방안

### 방안 1: Mutex 기반 동기화 (권장)

```kotlin
class PdfViewerActivity : AppCompatActivity() {
    private val renderMutex = Mutex()
    
    private suspend fun renderSinglePage(index: Int): Bitmap {
        return renderMutex.withLock {
            currentPage = pdfRenderer?.openPage(index)
            val page = currentPage ?: throw Exception("Failed to open page $index")
            // ... 기존 렌더링 로직
        }
    }
    
    private suspend fun renderTwoPagesUnified(leftPageIndex: Int, isLastOddPage: Boolean): Bitmap {
        return renderMutex.withLock {
            // 순차적으로 페이지 열기
            val leftPage = pdfRenderer?.openPage(leftPageIndex)
            // ... 왼쪽 페이지 처리
            val rightPage = if (!isLastOddPage) {
                pdfRenderer?.openPage(leftPageIndex + 1)
            } else null
            // ... 오른쪽 페이지 처리
        }
    }
}
```

**장점**:
- 완전한 동시성 문제 해결
- 기존 코드 구조 유지
- Kotlin Coroutines와 자연스럽게 통합

### 방안 2: 렌더링 상태 관리

```kotlin
class PdfViewerActivity : AppCompatActivity() {
    private var isRendering = AtomicBoolean(false)
    
    private fun showPage(index: Int) {
        if (isRendering.get()) {
            Log.d("PdfViewerActivity", "이미 렌더링 중 - 요청 무시")
            return
        }
        
        isRendering.set(true)
        // ... 렌더링 로직
        // finally 블록에서 isRendering.set(false)
    }
}
```

**장점**:
- 중복 요청 방지
- 단순한 구현
- 성능 오버헤드 최소

**단점**:
- 사용자 입력 손실 가능성

### 방안 3: 요청 큐잉 시스템

```kotlin
class PdfViewerActivity : AppCompatActivity() {
    private val renderChannel = Channel<Int>(Channel.UNLIMITED)
    
    init {
        // 렌더링 요청 순차 처리
        CoroutineScope(Dispatchers.IO).launch {
            for (pageIndex in renderChannel) {
                try {
                    // 순차적으로 렌더링 처리
                    performActualRendering(pageIndex)
                } catch (e: Exception) {
                    // 에러 처리
                }
            }
        }
    }
    
    private fun showPage(index: Int) {
        renderChannel.trySend(index)
    }
}
```

**장점**:
- 모든 요청 보장 처리
- 순서 보장
- 완전한 동시성 해결

**단점**:
- 복잡한 구현
- 지연 발생 가능성

### 방안 4: 개선된 에러 처리

```kotlin
private suspend fun renderWithRetry(renderOperation: suspend () -> Bitmap, maxRetries: Int = 2): Bitmap? {
    repeat(maxRetries) { attempt ->
        try {
            return renderOperation()
        } catch (e: Exception) {
            if (attempt < maxRetries - 1) {
                Log.w("PdfViewerActivity", "렌더링 재시도 ${attempt + 1}/$maxRetries", e)
                delay(50) // 짧은 대기 후 재시도
            } else {
                Log.e("PdfViewerActivity", "렌더링 최종 실패", e)
                throw e
            }
        }
    }
    return null
}
```

## 🚀 권장 구현 방안

### 단계별 구현

**1단계: Mutex 동기화 구현**
- `renderMutex` 추가
- `renderSinglePage()`, `renderTwoPagesUnified()` 보호

**2단계: 에러 메시지 개선**
- "일시적 렌더링 지연" 등 덜 불안감을 주는 메시지
- 토스트 대신 로그 또는 간단한 인디케이터

**3단계: 렌더링 상태 표시**
- 렌더링 중일 때 시각적 피드백
- 중복 요청 방지

### 성능 고려사항

- **Mutex 오버헤드**: 미미함 (렌더링 자체가 무거운 작업)
- **사용자 경험**: 에러 메시지 제거로 개선
- **메모리**: 추가 메모리 사용량 거의 없음

## 🧪 테스트 계획

### 테스트 시나리오

1. **빠른 페이지 전환 테스트**
   - 리모컨으로 빠른 연속 페이지 넘기기
   - 터치로 빠른 연속 탭

2. **협업 모드 테스트**
   - 지휘자의 빠른 페이지 변경
   - 연주자의 동시 입력과 동기화

3. **두 페이지 모드 집중 테스트**
   - 두 페이지 모드에서 빠른 전환
   - 캐시 미스 상황에서의 동작

### 성공 기준

- "PDF 파일을 열 수 없습니다" 메시지 완전 제거
- 페이지 전환 반응성 유지
- 메모리 사용량 증가 5% 이내

## 📝 구현 우선순위

1. **높음**: Mutex 기반 동기화 (핵심 문제 해결)
2. **중간**: 에러 메시지 개선 (사용자 경험)
3. **낮음**: 요청 큐잉 시스템 (고급 최적화)

## 🔗 관련 파일

- `PdfViewerActivity.kt`: 주요 수정 대상
- `PdfPageManager.kt`: 추가 동기화 필요 시
- `strings.xml`: 에러 메시지 개선

## 📚 참고 자료

- [Android PdfRenderer Documentation](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Kotlin Coroutines Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/)
- [Thread Safety in Android](https://developer.android.com/guide/background/threading)

---

이 문서는 PDF 렌더러 동시성 문제의 완전한 해결을 위한 종합적인 가이드입니다. 구현 시 단계적 접근을 통해 안정성을 확보하면서 사용자 경험을 개선할 수 있습니다.