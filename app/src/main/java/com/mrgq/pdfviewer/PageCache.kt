package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF 페이지 프리렌더링 및 캐싱 시스템
 * 빠른 페이지 전환을 위해 앞뒤 페이지를 미리 렌더링
 */
class PageCache(
    private val pdfRenderer: PdfRenderer,
    private val screenWidth: Int,
    private val screenHeight: Int,
    maxCacheSize: Int = 6 // 최대 6페이지 캐시 (현재 + 앞뒤 2페이지씩)
) {
    companion object {
        private const val TAG = "PageCache"
        private const val PRERENDER_DISTANCE = 2 // 현재 페이지 앞뒤로 몇 페이지까지 프리렌더링
        // PDF vector를 화면 최종 픽셀의 N배로 래스터화한 뒤 즉시 화면 크기로 다운스케일.
        //
        // ## 2026-08-15: 기본값 4.0 → 1.0 (supersampling 제거)
        //
        // oversample 의 원래 취지는 "2.5K/4K 화면에서 해상도를 최대한 활용하자"였으나,
        // devlog #040 에서 이 기기의 앱 UI 가 1080p 로 고정돼 있음이 확인됐다. 출력이
        // 표시 해상도로 고정된 상황에서 supersampling 은 이득이 없을 뿐 아니라 **해롭다**:
        //
        // PDFium 은 1픽셀 미만 stroke 를 device pixel 에 스냅한다(데스크톱 뷰어가 오선을
        // 또렷하게 그리는 것과 같은 동작). oversample 을 걸면 그 스냅된 1px 이 표시 기준
        // 1/N px 로 쪼그라들어 다운스케일 시 회색으로 희석된다.
        //
        // 배율 스윕 측정 (A4 악보, 764×1080, 오선 50개 / `data/oversample_sweep.py`):
        //
        // | oversample | darkness 평균 | 최소  | 표준편차 |
        // |------------|--------------|-------|---------|
        // | **1.0×**   | **1.000**    | 1.000 | **0.000** |
        // | 2.5× (v0.1.11) | 0.635    | 0.407 | 0.119   |
        // | 4.0× (v0.1.12) | 0.674    | 0.504 | 0.102   |
        //
        // 1× 에서는 50개 오선이 전부 순수 검정이고 편차가 0 이다. P2 가 dropout 을 잡겠다고
        // 2.5→4 로 올린 것은 방향이 반대였다 (1 로 내렸으면 완전히 해결됐다).
        //
        // ⚠️ 1× 의 선명함은 비트맵이 **정수 좌표에 1:1 로 놓일 때만** 유지된다.
        //    소수점 좌표로 blit/scale 하면 재샘플링돼 도로 뭉개진다
        //    (combineTwoPagesUnified / setImageViewMatrix 의 좌표 반올림 참고).
        //
        // 이전 이력: P2-A 에서 oversample 비트맵을 ImageView 에 직접 넘겼다가 Canvas
        // MAX_BITMAP_SIZE(~100MB) 초과로 크래시 → 렌더 직후 다운스케일하는 P2-B 로 전환.
        const val DEFAULT_OVERSAMPLE_FACTOR = 1.0f

        const val PREF_OVERSAMPLE = "oversample_factor"

        /** 런타임 조정 가능 (설정 다이얼로그). 렌더 스레드에서 읽으므로 @Volatile. */
        @Volatile
        @JvmStatic
        var oversampleFactor: Float = DEFAULT_OVERSAMPLE_FACTOR

        // Transient oversample 비트맵의 픽셀 상한. 1080p 최악 케이스(가로 PDF 전체화면
        // 1920×1080 × 4² ≈ 33MP, ~133MB)는 그대로 허용하는 값이라 1080p 동작은 불변.
        // 4K 화면에서 4× 를 그대로 쓰면 페이지당 ~210MB 이상 필요하므로, 이 상한에 맞춰
        // factor 를 자동 축소한다. 화면 해상도가 높을수록 oversample 필요성 자체가 줄므로
        // (4K 에서 ~3.2×, 가로 전체화면 ~2×) 품질 손실은 없다.
        const val MAX_OVERSAMPLE_PIXELS = 34_000_000L

        /** 표시 크기 기준으로 MAX_OVERSAMPLE_PIXELS 를 넘지 않는 oversample 배율을 반환. */
        fun effectiveOversampleFactor(displayW: Int, displayH: Int): Float {
            val requested = oversampleFactor
            if (requested <= 1.001f) return 1f
            // 각 변을 따로 본다. 곱만 검사하면 음수 × 음수가 양수라 방어를 통과한다
            // (RenderTuningTest 가 잡아낸 실제 결함 — displayW/H 는 호출부에서 coerceAtLeast(1)
            // 되므로 라이브 경로에서는 나타나지 않지만, 가드가 의도한 일을 못 하고 있었다).
            if (displayW <= 0 || displayH <= 0) return 1f
            val displayPixels = displayW.toLong() * displayH
            val maxFactor = kotlin.math.sqrt(MAX_OVERSAMPLE_PIXELS.toDouble() / displayPixels).toFloat()
            return requested.coerceAtMost(maxFactor).coerceAtLeast(1f)
        }
    }
    
    // LRU 캐시로 메모리 사용량 제한
    private val bitmapCache = LruCache<Int, Bitmap>(maxCacheSize)
    
    // 현재 렌더링 중인 페이지 추적 (중복 렌더링 방지)
    private val renderingPages = ConcurrentHashMap<Int, Job>()
    
    // 백그라운드 렌더링용 스코프
    private val renderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isTwoPageMode = false
    private var renderScale = 1.0f
    
    // Display settings callback
    private var displaySettingsProvider: (() -> Triple<Float, Float, Float>)? = null
    
    // Cache invalidation tracking
    private var lastKnownSettings: Triple<Float, Float, Float>? = null
    private var settingsChanged = false
    
    fun updateSettings(twoPageMode: Boolean, scale: Float) {
        isTwoPageMode = twoPageMode
        renderScale = scale
        Log.d(TAG, "Settings updated - TwoPageMode: $twoPageMode, Scale: $scale")
    }
    
    fun setDisplaySettingsProvider(provider: () -> Triple<Float, Float, Float>) {
        val oldProvider = displaySettingsProvider
        displaySettingsProvider = provider
        
        // If settings provider changed, clear cache to ensure fresh rendering
        if (oldProvider != null) {
            Log.d(TAG, "설정 프로바이더 변경으로 캐시 클리어")
            clear()
        }
    }
    
    /**
     * 설정 변경 여부를 확인하고 필요시 캐시 무효화
     */
    private fun checkSettingsAndInvalidateCache() {
        val currentSettings = displaySettingsProvider?.invoke()
        
        if (currentSettings != null && currentSettings != lastKnownSettings) {
            Log.d(TAG, "설정 변경 감지: $lastKnownSettings -> $currentSettings")
            Log.d(TAG, "기존 캐시 무효화 중...")
            
            // Clear existing cache
            bitmapCache.evictAll()
            
            // Cancel ongoing rendering jobs
            renderingPages.values.forEach { it.cancel() }
            renderingPages.clear()
            
            lastKnownSettings = currentSettings
            settingsChanged = true
            
            Log.d(TAG, "캐시 무효화 완료")
        }
    }
    
    /**
     * 페이지를 즉시 가져오기 (캐시에 있으면 즉시 반환, 없으면 동기 렌더링)
     */
    fun getPageImmediate(pageIndex: Int): Bitmap? {
        // Check if settings changed and invalidate cache if needed
        checkSettingsAndInvalidateCache()
        
        // 캐시에서 먼저 확인
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            Log.d(TAG, "페이지 $pageIndex 캐시 히트 (설정 적용된 캐시)")
            return cached
        }
        
        // 캐시에 없으면 동기 렌더링 (빠른 대응)
        Log.d(TAG, "페이지 $pageIndex 캐시 미스 - 새로운 설정으로 즉시 렌더링")
        return renderPageSync(pageIndex)
    }
    
    /**
     * 현재 페이지를 기준으로 주변 페이지들을 프리렌더링
     */
    fun prerenderAround(currentPageIndex: Int) {
        // Safety check: ensure PDF renderer is still valid
        val pageCount = try {
            pdfRenderer.pageCount
        } catch (e: IllegalStateException) {
            Log.w(TAG, "PdfRenderer is closed, skipping prerender", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing page count, skipping prerender", e)
            return
        }
        
        // 두 페이지 모드인 경우 홀수 페이지 인덱스 조정
        val adjustedIndex = if (isTwoPageMode && currentPageIndex % 2 == 1) {
            currentPageIndex - 1
        } else {
            currentPageIndex
        }
        
        // 프리렌더링할 페이지 범위 계산
        val distance = if (isTwoPageMode) PRERENDER_DISTANCE * 2 else PRERENDER_DISTANCE
        val startPage = maxOf(0, adjustedIndex - distance)
        val endPage = minOf(pageCount - 1, adjustedIndex + distance)
        
        Log.d(TAG, "프리렌더링 시작: 현재=$currentPageIndex (조정된=$adjustedIndex), 범위=$startPage-$endPage, 두페이지모드=$isTwoPageMode")
        
        for (pageIndex in startPage..endPage) {
            // 이미 캐시에 있으면 스킵
            if (bitmapCache.get(pageIndex) != null) {
                continue
            }
            
            // 이미 렌더링 중이면 스킵
            if (renderingPages.containsKey(pageIndex)) {
                continue
            }
            
            // 백그라운드에서 비동기 렌더링
            val job = renderScope.launch {
                try {
                    renderPageAsync(pageIndex)
                } catch (e: Exception) {
                    Log.w(TAG, "페이지 $pageIndex 프리렌더링 실패", e)
                } finally {
                    renderingPages.remove(pageIndex)
                }
            }
            
            renderingPages[pageIndex] = job
        }
    }
    
    /**
     * 동기 페이지 렌더링 (즉시 필요한 경우)
     */
    private fun renderPageSync(pageIndex: Int): Bitmap? {
        val pageCount = try {
            pdfRenderer.pageCount
        } catch (e: IllegalStateException) {
            Log.w(TAG, "PdfRenderer is closed, cannot sync render page $pageIndex", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing page count for sync render page $pageIndex", e)
            return null
        }

        if (pageIndex < 0 || pageIndex >= pageCount) {
            return null
        }

        return try {
            val page = try {
                pdfRenderer.openPage(pageIndex)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "PdfRenderer is closed, cannot render page $pageIndex", e)
                return null
            }

            val bitmap = renderPageToTargetBitmap(page)
            page.close()

            bitmapCache.put(pageIndex, bitmap)

            Log.d(TAG, "페이지 $pageIndex 동기 렌더링 완료 (${bitmap.width}x${bitmap.height})")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "페이지 $pageIndex 동기 렌더링 실패", e)
            null
        }
    }

    /**
     * 비동기 페이지 렌더링 (프리렌더링용)
     */
    private suspend fun renderPageAsync(pageIndex: Int) = withContext(Dispatchers.IO) {
        val pageCount = try {
            pdfRenderer.pageCount
        } catch (e: IllegalStateException) {
            Log.w(TAG, "PdfRenderer is closed, skipping async render for page $pageIndex", e)
            return@withContext
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing page count for page $pageIndex", e)
            return@withContext
        }

        if (pageIndex < 0 || pageIndex >= pageCount) {
            return@withContext
        }

        if (bitmapCache.get(pageIndex) != null) {
            return@withContext
        }

        try {
            val page = try {
                pdfRenderer.openPage(pageIndex)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "PdfRenderer is closed, cannot async render page $pageIndex", e)
                return@withContext
            }

            val bitmap = renderPageToTargetBitmap(page)
            page.close()

            bitmapCache.put(pageIndex, bitmap)

            Log.d(TAG, "페이지 $pageIndex 비동기 렌더링 완료 (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.w(TAG, "페이지 $pageIndex 비동기 렌더링 실패", e)
        }
    }

    /**
     * PDF 페이지를 화면 크기 비트맵으로 래스터화한다.
     *
     * 좌표 계산은 [PageGeometry], 래스터화는 [PageRenderer] 가 담당한다 — 캐시 미스 경로
     * (PdfViewerActivity)와 **같은 함수**를 지나므로 경로별로 다른 결과가 나올 수 없다.
     *
     * **크롭(위/아래 클리핑)은 렌더 배율에 먼저 반영된다.** 위·아래 5% 를 자르면
     * `보이는 높이(= pdfHeight × 0.9) × renderScale = 화면 높이` 가 되는 배율로 렌더하고,
     * 잘려나갈 위쪽은 `Matrix.postTranslate` 로 캔버스 밖으로 민다. 즉 **한 번만 래스터화**되며
     * 잘라낸 결과를 나중에 확대하는 단계가 없다 — 그 2차 스케일링이 오선 두께를 들쭉날쭉하게
     * 만들던 원인이었다 (P01, v0.1.11).
     */
    private fun renderPageToTargetBitmap(page: PdfRenderer.Page): Bitmap {
        val settings = displaySettingsProvider?.invoke() ?: Triple(0f, 0f, 0f)
        val geometry = PageGeometry.compute(
            pdfWidth = page.width,
            pdfHeight = page.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topClipping = settings.first,
            bottomClipping = settings.second,
            centerPadding = settings.third,
            twoPageMode = isTwoPageMode,
        )
        return PageRenderer.render(page, geometry)
    }
    
    /**
     * 특정 페이지가 캐시에 있는지 확인
     */
    fun isCached(pageIndex: Int): Boolean {
        val cached = bitmapCache.get(pageIndex)
        return cached != null && !cached.isRecycled
    }
    
    /**
     * 캐시 상태 정보
     */
    fun getCacheInfo(): String {
        val cacheSize = bitmapCache.size()
        val renderingCount = renderingPages.size
        return "캐시: $cacheSize 페이지, 렌더링 중: $renderingCount"
    }
    
    /**
     * 캐시 정리
     */
    fun clear() {
        Log.d(TAG, "캐시 정리 중...")
        
        // 렌더링 작업 취소
        renderingPages.values.forEach { it.cancel() }
        renderingPages.clear()
        
        // 비트맵 캐시 정리
        bitmapCache.evictAll()
        
        Log.d(TAG, "캐시 정리 완료")
    }
    
    /**
     * 리소스 해제
     */
    fun destroy() {
        Log.d(TAG, "PageCache 해제 중...")
        
        // 스코프 취소
        renderScope.cancel()
        
        // 캐시 정리
        clear()
        
        Log.d(TAG, "PageCache 해제 완료")
    }
}