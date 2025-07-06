package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.Canvas
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
    }
    
    // LRU 캐시로 메모리 사용량 제한
    private val bitmapCache = LruCache<Int, Bitmap>(maxCacheSize)
    
    // 현재 렌더링 중인 페이지 추적 (중복 렌더링 방지)
    private val renderingPages = ConcurrentHashMap<Int, Job>()
    
    // 백그라운드 렌더링용 스코프
    private val renderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isTwoPageMode = false
    private var renderScale = 1.0f
    
    fun updateSettings(twoPageMode: Boolean, scale: Float) {
        isTwoPageMode = twoPageMode
        renderScale = scale
        Log.d(TAG, "Settings updated - TwoPageMode: $twoPageMode, Scale: $scale")
    }
    
    /**
     * 페이지를 즉시 가져오기 (캐시에 있으면 즉시 반환, 없으면 동기 렌더링)
     */
    fun getPageImmediate(pageIndex: Int): Bitmap? {
        // 캐시에서 먼저 확인
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            Log.d(TAG, "페이지 $pageIndex 캐시 히트")
            return cached
        }
        
        // 캐시에 없으면 동기 렌더링 (빠른 대응)
        Log.d(TAG, "페이지 $pageIndex 캐시 미스 - 즉시 렌더링")
        return renderPageSync(pageIndex)
    }
    
    /**
     * 현재 페이지를 기준으로 주변 페이지들을 프리렌더링
     */
    fun prerenderAround(currentPageIndex: Int) {
        val pageCount = pdfRenderer.pageCount
        
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
        if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) {
            return null
        }
        
        return try {
            val page = pdfRenderer.openPage(pageIndex)
            val bitmap = createBitmapForPage(page)
            
            // 렌더링
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            // 캐시에 저장
            bitmapCache.put(pageIndex, bitmap)
            
            Log.d(TAG, "페이지 $pageIndex 동기 렌더링 완료")
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
        if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) {
            return@withContext
        }
        
        // 이미 캐시에 있으면 스킵
        if (bitmapCache.get(pageIndex) != null) {
            return@withContext
        }
        
        try {
            val page = pdfRenderer.openPage(pageIndex)
            val bitmap = createBitmapForPage(page)
            
            // 렌더링
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            // 캐시에 저장
            bitmapCache.put(pageIndex, bitmap)
            
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
            // 두 페이지 모드: 폭을 절반으로
            targetWidth = (screenWidth / 2 * renderScale).toInt()
            targetHeight = (screenHeight * renderScale).toInt()
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