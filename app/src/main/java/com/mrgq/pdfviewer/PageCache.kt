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
        // PDF vector를 화면 최종 픽셀의 N배로 래스터화. 화면 표시 시 다운스케일에서 anti-alias 효과.
        // combineTwoPagesUnified 의 finalScale 과 반드시 일치해야 함.
        const val OVERSAMPLE_FACTOR = 2.5f
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
     * PDF 페이지를 목표 크기로 직접 래스터화한다.
     *
     * PdfRenderer 가 vector 단계에서 한 번에 (크롭 + fit + oversample) 변환을 수행하도록
     * Matrix 를 명시적으로 넘긴다. 이후 비트맵 재스케일/재크롭이 없어 fractional scaling
     * 으로 인한 오선 두께 불균일이 사라진다.
     *
     * 두 페이지 모드의 비트맵 크기는 combineTwoPagesUnified 가 기대하는 위치+크기에 정확히
     * 맞아야 하므로, 양쪽이 동일한 공식을 사용한다 (화면 절반 - 중앙 여백/2 영역에 fit).
     */
    private fun renderPageToTargetBitmap(page: PdfRenderer.Page): Bitmap {
        val pdfWidth = page.width
        val pdfHeight = page.height

        val settings = displaySettingsProvider?.invoke() ?: Triple(0f, 0f, 0f)
        val topClipping = settings.first.coerceIn(0f, 0.45f)
        val bottomClipping = settings.second.coerceIn(0f, 0.45f)
        val centerPadding = settings.third.coerceIn(0f, 0.5f)
        val visibleFraction = (1f - topClipping - bottomClipping).coerceAtLeast(0.1f)
        val visiblePdfHeight = pdfHeight * visibleFraction

        val fitScale = if (isTwoPageMode) {
            val halfScreenW = screenWidth / 2f
            val halfPadPx = screenWidth * centerPadding / 2f
            val availW = (halfScreenW - halfPadPx).coerceAtLeast(1f)
            minOf(availW / pdfWidth, screenHeight / visiblePdfHeight)
        } else {
            minOf(screenWidth / pdfWidth.toFloat(), screenHeight / visiblePdfHeight)
        }

        val finalScale = fitScale * OVERSAMPLE_FACTOR
        val targetWidth = (pdfWidth * finalScale).toInt().coerceAtLeast(1)
        val targetHeight = (visiblePdfHeight * finalScale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val matrix = Matrix().apply {
            setScale(finalScale, finalScale)
            // PDF 좌표 y=topClipping*pdfHeight 를 비트맵 y=0 으로 매핑하여
            // 크롭을 vector 변환 단계에 흡수.
            postTranslate(0f, -pdfHeight * topClipping * finalScale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
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