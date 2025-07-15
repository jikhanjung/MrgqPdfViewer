package com.mrgq.pdfviewer

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.mrgq.pdfviewer.repository.MusicRepository
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

/**
 * Manages PDF rendering, caching, and display settings.
 * Single responsibility: Handle all PDF page rendering operations.
 */
class PdfPageManager(
    private val context: Context,
    private val repository: MusicRepository,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    
    // Core PDF components
    private var pdfRenderer: PdfRenderer? = null
    private var pageCache: PageCache? = null
    private var currentPage: PdfRenderer.Page? = null
    
    // Current state
    private var currentPageIndex: Int = 0
    private var totalPageCount: Int = 0
    private var isTwoPageMode: Boolean = false
    private var currentFilePath: String = ""
    private var forceDirectRendering: Boolean = false
    
    // Display settings
    data class DisplaySettings(
        val topClipping: Float = 0f,
        val bottomClipping: Float = 0f, 
        val centerPadding: Float = 0f
    )
    
    private var displaySettings = DisplaySettings()
    
    // Lifecycle scope for coroutines
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Load a PDF file and initialize renderer
     */
    suspend fun loadPdf(filePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("PdfPageManager", "Loading PDF: $filePath")
                val file = File(filePath)
                
                if (!file.exists()) {
                    Log.e("PdfPageManager", "File does not exist: $filePath")
                    return@withContext false
                }
                
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                totalPageCount = pdfRenderer?.pageCount ?: 0
                currentFilePath = filePath
                
                Log.d("PdfPageManager", "PDF loaded successfully - page count: $totalPageCount")
                
                if (totalPageCount > 0) {
                    // Initialize page cache
                    pageCache?.destroy()
                    
                    // Calculate optimal scale based on first page
                    val firstPage = pdfRenderer!!.openPage(0)
                    val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                    firstPage.close()
                    
                    pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                    
                    Log.d("PdfPageManager", "PageCache initialized with scale: $calculatedScale")
                    return@withContext true
                }
                
                false
            } catch (e: Exception) {
                Log.e("PdfPageManager", "Error loading PDF: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Render and return a page bitmap
     */
    suspend fun showPage(pageIndex: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= totalPageCount) return null
        
        Log.d("PdfPageManager", "showPage called: index=$pageIndex, isTwoPageMode=$isTwoPageMode")
        
        // Check cache first for instant display
        val cachedBitmap = if (isTwoPageMode) {
            getCachedTwoPageBitmap(pageIndex)
        } else {
            pageCache?.getPageImmediate(pageIndex)
        }
        
        if (cachedBitmap != null) {
            Log.d("PdfPageManager", "⚡ Page $pageIndex served from cache")
            currentPageIndex = pageIndex
            pageCache?.prerenderAround(pageIndex)
            return cachedBitmap
        }
        
        // Cache miss - render directly
        Log.d("PdfPageManager", "⏳ Page $pageIndex cache miss - rendering directly")
        
        return withContext(Dispatchers.IO) {
            try {
                currentPage?.close()
                
                val bitmap = if (isTwoPageMode) {
                    if (pageIndex + 1 < totalPageCount) {
                        renderTwoPages(pageIndex)
                    } else {
                        renderSinglePageOnLeft(pageIndex)
                    }
                } else {
                    if (forceDirectRendering) {
                        forceDirectRendering = false
                        renderSinglePageDirect(pageIndex)
                    } else {
                        pageCache?.getPage(pageIndex) ?: renderSinglePageDirect(pageIndex)
                    }
                }
                
                currentPageIndex = pageIndex
                bitmap
            } catch (e: Exception) {
                Log.e("PdfPageManager", "Error rendering page $pageIndex: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Get cached two-page bitmap
     */
    private fun getCachedTwoPageBitmap(pageIndex: Int): Bitmap? {
        return if (pageIndex + 1 < totalPageCount) {
            val page1 = pageCache?.getPageImmediate(pageIndex)
            val page2 = pageCache?.getPageImmediate(pageIndex + 1)
            if (page1 != null && page2 != null) {
                combineTwoPages(page1, page2)
            } else null
        } else {
            val page1 = pageCache?.getPageImmediate(pageIndex)
            if (page1 != null) {
                combinePageWithEmpty(page1)
            } else null
        }
    }
    
    /**
     * Render single page directly (bypassing cache)
     */
    private suspend fun renderSinglePageDirect(index: Int): Bitmap {
        currentPage = pdfRenderer?.openPage(index)
        val page = currentPage ?: throw Exception("Failed to open page $index")
        
        val scale = calculateOptimalScale(page.width, page.height)
        val renderWidth = (page.width * scale).toInt()
        val renderHeight = (page.height * scale).toInt()
        
        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        return applyDisplaySettings(bitmap, false)
    }
    
    /**
     * Render single page positioned on left side (for two-page mode last page)
     */
    private suspend fun renderSinglePageOnLeft(index: Int): Bitmap {
        currentPage = pdfRenderer?.openPage(index)
        val page = currentPage ?: throw Exception("Failed to open page $index")
        
        try {
            val scale = calculateOptimalScale(page.width, page.height, true)
            val pageWidth = (page.width * scale).toInt()
            val pageHeight = (page.height * scale).toInt()
            
            val totalWidth = screenWidth
            val totalHeight = min(screenHeight, pageHeight)
            
            val combinedBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            combinedBitmap.eraseColor(Color.WHITE)
            
            val canvas = Canvas(combinedBitmap)
            
            val leftBitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            leftBitmap.eraseColor(Color.WHITE)
            page.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val leftScaledBitmap = Bitmap.createScaledBitmap(leftBitmap, pageWidth, pageHeight, true)
            
            val leftX = ((totalWidth / 2 - pageWidth) / 2).coerceAtLeast(0)
            val leftY = ((totalHeight - pageHeight) / 2).coerceAtLeast(0)
            
            canvas.drawBitmap(leftScaledBitmap, leftX.toFloat(), leftY.toFloat(), null)
            
            leftBitmap.recycle()
            leftScaledBitmap.recycle()
            
            return applyDisplaySettings(combinedBitmap, true)
        } finally {
            page.close()
        }
    }
    
    /**
     * Render two pages side by side
     */
    private suspend fun renderTwoPages(leftPageIndex: Int): Bitmap {
        Log.d("PdfPageManager", "Rendering two pages: $leftPageIndex and ${leftPageIndex + 1}")
        
        val leftPage = pdfRenderer?.openPage(leftPageIndex) ?: throw Exception("Failed to open left page")
        
        try {
            val leftBitmap = Bitmap.createBitmap(leftPage.width, leftPage.height, Bitmap.Config.ARGB_8888)
            leftBitmap.eraseColor(Color.WHITE)
            leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val rightPage = pdfRenderer?.openPage(leftPageIndex + 1) ?: throw Exception("Failed to open right page")
            
            try {
                val rightBitmap = Bitmap.createBitmap(rightPage.width, rightPage.height, Bitmap.Config.ARGB_8888)
                rightBitmap.eraseColor(Color.WHITE)
                rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val combinedBitmap = combineTwoPages(leftBitmap, rightBitmap)
                
                leftBitmap.recycle()
                rightBitmap.recycle()
                
                return combinedBitmap
            } finally {
                rightPage.close()
            }
        } finally {
            leftPage.close()
        }
    }
    
    /**
     * Combine two page bitmaps into a single side-by-side bitmap
     */
    private fun combineTwoPages(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap {
        val scale = calculateOptimalScale(leftBitmap.width, leftBitmap.height, true)
        val scaledWidth = (leftBitmap.width * scale).toInt()
        val scaledHeight = (leftBitmap.height * scale).toInt()
        
        val totalWidth = screenWidth
        val totalHeight = min(screenHeight, scaledHeight)
        
        val combinedBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        combinedBitmap.eraseColor(Color.WHITE)
        
        val canvas = Canvas(combinedBitmap)
        
        val leftScaled = Bitmap.createScaledBitmap(leftBitmap, scaledWidth, scaledHeight, true)
        val rightScaled = Bitmap.createScaledBitmap(rightBitmap, scaledWidth, scaledHeight, true)
        
        val availableWidth = totalWidth - displaySettings.centerPadding.toInt()
        val singlePageWidth = availableWidth / 2
        
        val leftX = ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
        val rightX = singlePageWidth + displaySettings.centerPadding.toInt() + ((singlePageWidth - scaledWidth) / 2).coerceAtLeast(0)
        val y = ((totalHeight - scaledHeight) / 2).coerceAtLeast(0)
        
        canvas.drawBitmap(leftScaled, leftX.toFloat(), y.toFloat(), null)
        canvas.drawBitmap(rightScaled, rightX.toFloat(), y.toFloat(), null)
        
        leftScaled.recycle()
        rightScaled.recycle()
        
        return applyDisplaySettings(combinedBitmap, true)
    }
    
    /**
     * Combine single page with empty right side
     */
    private fun combinePageWithEmpty(pageBitmap: Bitmap): Bitmap {
        val scale = calculateOptimalScale(pageBitmap.width, pageBitmap.height, true)
        val scaledWidth = (pageBitmap.width * scale).toInt()
        val scaledHeight = (pageBitmap.height * scale).toInt()
        
        val totalWidth = screenWidth
        val totalHeight = min(screenHeight, scaledHeight)
        
        val combinedBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        combinedBitmap.eraseColor(Color.WHITE)
        
        val canvas = Canvas(combinedBitmap)
        val scaledBitmap = Bitmap.createScaledBitmap(pageBitmap, scaledWidth, scaledHeight, true)
        
        val leftX = ((totalWidth / 2 - scaledWidth) / 2).coerceAtLeast(0)
        val y = ((totalHeight - scaledHeight) / 2).coerceAtLeast(0)
        
        canvas.drawBitmap(scaledBitmap, leftX.toFloat(), y.toFloat(), null)
        scaledBitmap.recycle()
        
        return applyDisplaySettings(combinedBitmap, true)
    }
    
    /**
     * Calculate optimal scale for rendering
     */
    private fun calculateOptimalScale(pageWidth: Int, pageHeight: Int, forTwoPageMode: Boolean = false): Float {
        val targetWidth = if (forTwoPageMode) screenWidth / 2 else screenWidth
        val targetHeight = screenHeight
        
        val scaleX = targetWidth.toFloat() / pageWidth.toFloat()
        val scaleY = targetHeight.toFloat() / pageHeight.toFloat()
        
        return min(scaleX, scaleY) * 0.9f // 90% to leave some margin
    }
    
    /**
     * Apply display settings (clipping, padding) to bitmap
     */
    private fun applyDisplaySettings(originalBitmap: Bitmap, isTwoPageMode: Boolean): Bitmap {
        val hasClipping = displaySettings.topClipping > 0f || displaySettings.bottomClipping > 0f
        val hasPadding = displaySettings.centerPadding > 0f && isTwoPageMode
        
        if (!hasClipping && !hasPadding) {
            return originalBitmap
        }
        
        try {
            val originalWidth = originalBitmap.width
            val originalHeight = originalBitmap.height
            
            val topClipPixels = (originalHeight * displaySettings.topClipping).toInt()
            val bottomClipPixels = (originalHeight * displaySettings.bottomClipping).toInt()
            
            val clippedHeight = originalHeight - topClipPixels - bottomClipPixels
            
            if (clippedHeight <= 0) {
                Log.w("PdfPageManager", "Clipping would result in zero or negative height")
                return originalBitmap
            }
            
            val clippedBitmap = Bitmap.createBitmap(
                originalBitmap,
                0,
                topClipPixels,
                originalWidth,
                clippedHeight
            )
            
            return clippedBitmap
        } catch (e: Exception) {
            Log.e("PdfPageManager", "Error applying display settings: ${e.message}", e)
            return originalBitmap
        }
    }
    
    /**
     * Update display settings (clipping, padding)
     */
    fun updateDisplaySettings(settings: DisplaySettings) {
        this.displaySettings = settings
        forceDirectRendering = true
    }
    
    /**
     * Toggle two-page mode
     */
    fun setTwoPageMode(enabled: Boolean) {
        this.isTwoPageMode = enabled
        pageCache?.clear()
        forceDirectRendering = true
    }
    
    /**
     * Get total page count
     */
    fun getPageCount(): Int = totalPageCount
    
    /**
     * Get current page index
     */
    fun getCurrentPageIndex(): Int = currentPageIndex
    
    /**
     * Check if in two-page mode
     */
    fun isTwoPageMode(): Boolean = isTwoPageMode
    
    /**
     * Set display settings provider for PageCache
     */
    fun setDisplaySettingsProvider(provider: () -> Triple<Float, Float, Float>) {
        pageCache?.setDisplaySettingsProvider(provider)
    }
    
    /**
     * Force cache clear and re-render
     */
    fun forceRerender() {
        pageCache?.clear()
        forceDirectRendering = true
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        pageCache?.destroy()
        currentPage?.close()
        pdfRenderer?.close()
        managerScope.cancel()
    }
}