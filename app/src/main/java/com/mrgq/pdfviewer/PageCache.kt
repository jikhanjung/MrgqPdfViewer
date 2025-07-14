package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Rect

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
        // Safety check: ensure PDF renderer is still valid
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
            // Safety check: ensure PDF renderer is still valid before opening page
            val page = try {
                pdfRenderer.openPage(pageIndex)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "PdfRenderer is closed, cannot render page $pageIndex", e)
                return null
            }
            
            val bitmap = createBitmapForPage(page)
            
            // 렌더링
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            // 설정 적용
            val finalBitmap = applyDisplaySettings(bitmap)
            
            // 캐시에 저장 (설정이 적용된 비트맵)
            bitmapCache.put(pageIndex, finalBitmap)
            
            Log.d(TAG, "페이지 $pageIndex 동기 렌더링 완료")
            finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "페이지 $pageIndex 동기 렌더링 실패", e)
            null
        }
    }
    
    /**
     * 비동기 페이지 렌더링 (프리렌더링용)
     */
    private suspend fun renderPageAsync(pageIndex: Int) = withContext(Dispatchers.IO) {
        // Safety check: ensure PDF renderer is still valid
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
        
        // 이미 캐시에 있으면 스킵
        if (bitmapCache.get(pageIndex) != null) {
            return@withContext
        }
        
        try {
            // Safety check: ensure PDF renderer is still valid before opening page
            val page = try {
                pdfRenderer.openPage(pageIndex)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "PdfRenderer is closed, cannot async render page $pageIndex", e)
                return@withContext
            }
            
            val bitmap = createBitmapForPage(page)
            
            // 렌더링
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            // 설정 적용
            val finalBitmap = applyDisplaySettings(bitmap)
            
            // 캐시에 저장 (설정이 적용된 비트맵)
            bitmapCache.put(pageIndex, finalBitmap)
            
            Log.d(TAG, "페이지 $pageIndex 비동기 렌더링 완료")
        } catch (e: Exception) {
            Log.w(TAG, "페이지 $pageIndex 비동기 렌더링 실패", e)
        }
    }
    
    /**
     * 페이지에 맞는 Bitmap 생성
     */
    private fun createBitmapForPage(page: PdfRenderer.Page): Bitmap {
        val pdfWidth = page.width
        val pdfHeight = page.height
        
        // 화면 크기에 맞춰 스케일 계산
        val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val pdfAspectRatio = pdfWidth.toFloat() / pdfHeight.toFloat()
        
        val targetWidth: Int
        val targetHeight: Int
        
        if (isTwoPageMode) {
            // 두 페이지 모드: 원본 PDF 비율 유지하면서 스케일 적용
            targetWidth = (pdfWidth * renderScale).toInt()
            targetHeight = (pdfHeight * renderScale).toInt()
        } else {
            // 단일 페이지 모드: 화면에 맞춤
            if (pdfAspectRatio > screenAspectRatio) {
                // PDF가 더 가로로 길면 폭에 맞춤
                targetWidth = (screenWidth * renderScale).toInt()
                targetHeight = (screenWidth / pdfAspectRatio * renderScale).toInt()
            } else {
                // PDF가 더 세로로 길면 높이에 맞춤
                targetWidth = (screenHeight * pdfAspectRatio * renderScale).toInt()
                targetHeight = (screenHeight * renderScale).toInt()
            }
        }
        
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }
    
    /**
     * 클리핑과 여백 설정을 비트맵에 적용
     */
    private fun applyDisplaySettings(originalBitmap: Bitmap): Bitmap {
        val settings = displaySettingsProvider?.invoke()
        if (settings == null) {
            Log.d(TAG, "설정 프로바이더가 null입니다")
            return originalBitmap
        }
        
        val (topClipping, bottomClipping, centerPadding) = settings
        Log.d(TAG, "PageCache에서 받은 설정: 위 클리핑 ${topClipping * 100}%, 아래 클리핑 ${bottomClipping * 100}%, 여백 ${centerPadding * 100}%, 두페이지모드: $isTwoPageMode")
        
        val hasClipping = topClipping > 0f || bottomClipping > 0f
        // PageCache는 개별 페이지만 처리하므로 여백은 PdfViewerActivity에서 처리
        
        if (!hasClipping) {
            Log.d(TAG, "적용할 클리핑이 없습니다")
            return originalBitmap
        }
        
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        
        // 클리핑 계산만 수행 (여백은 PdfViewerActivity에서 처리)
        val topClipPixels = (originalHeight * topClipping).toInt()
        val bottomClipPixels = (originalHeight * bottomClipping).toInt()
        val clippedHeight = originalHeight - topClipPixels - bottomClipPixels
        
        val finalWidth = originalWidth
        val finalHeight = clippedHeight
        
        if (finalWidth <= 0 || finalHeight <= 0) {
            Log.w(TAG, "클리핑 결과가 유효하지 않음: ${finalWidth}x${finalHeight}")
            return originalBitmap
        }
        
        // 새 비트맵 생성
        val resultBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        // PageCache는 개별 페이지만 처리하므로 클리핑만 적용
        val srcRect = Rect(0, topClipPixels, originalWidth, originalHeight - bottomClipPixels)
        val dstRect = Rect(0, 0, originalWidth, clippedHeight)
        canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
        
        // 원본 비트맵을 재사용하지 않을 때만 해제
        if (resultBitmap != originalBitmap) {
            originalBitmap.recycle()
        }
        
        Log.d(TAG, "PageCache 클리핑 적용: ${originalWidth}x${originalHeight} → ${finalWidth}x${finalHeight}")
        return resultBitmap
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