package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.mrgq.pdfviewer.databinding.ActivityPdfViewerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.os.Environment
import java.io.File
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.UserPreference
import com.mrgq.pdfviewer.repository.MusicRepository
import com.mrgq.pdfviewer.utils.PdfAnalyzer
import android.os.Handler
import android.os.Looper

class PdfViewerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var pageIndex = 0
    private var pageCount = 0
    private lateinit var pdfFilePath: String
    private lateinit var pdfFileName: String
    private var currentFileIndex = 0
    private var filePathList: List<String> = emptyList()
    private var fileNameList: List<String> = emptyList()
    
    // Navigation guide state
    private var isNavigationGuideVisible = false
    private var navigationGuideType = ""  // "end" or "start"
    
    // Two-page mode
    private var isTwoPageMode = false
    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var preferences: SharedPreferences
    
    // Collaboration
    private var collaborationMode = CollaborationMode.NONE
    private val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    
    // Page caching for instant page switching
    private var pageCache: PageCache? = null
    
    // Database repository
    private lateinit var musicRepository: MusicRepository
    private var currentPdfFileId: String? = null
    
    // Current display settings
    private var currentTopClipping: Float = 0f
    private var currentBottomClipping: Float = 0f
    private var currentCenterPadding: Float = 0f  // Changed to Float for percentage (0.0 - 0.15)
    
    // Flag to force direct rendering (bypass cache) after settings change
    private var forceDirectRendering: Boolean = false
    
    // Long press handling for OK button
    private var isLongPressing = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (isLongPressing) {
            showPdfDisplayOptions()
        }
    }
    private val longPressDelay = 800L // 800ms for long press
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize preferences
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize database repository
        musicRepository = MusicRepository(this)
        
        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        currentFileIndex = intent.getIntExtra("current_index", 0)
        filePathList = intent.getStringArrayListExtra("file_path_list") ?: emptyList()
        fileNameList = intent.getStringArrayListExtra("file_name_list") ?: emptyList()
        
        // Check if there's a target page from collaboration
        val targetPage = intent.getIntExtra("target_page", -1)
        Log.d("PdfViewerActivity", "Target page from intent: $targetPage")
        
        // 받은 파일 목록 로그
        Log.d("PdfViewerActivity", "=== RECEIVED FILE LIST ===")
        filePathList.forEachIndexed { index, path ->
            val name = if (index < fileNameList.size) fileNameList[index] else "Unknown"
            Log.d("PdfViewerActivity", "[$index] NAME: '$name' PATH: '$path'")
        }
        Log.d("PdfViewerActivity", "Current file index: $currentFileIndex")
        
        // 인덱스에 해당하는 파일을 로드
        if (currentFileIndex >= 0 && currentFileIndex < filePathList.size) {
            pdfFilePath = filePathList[currentFileIndex]
            pdfFileName = fileNameList[currentFileIndex]
            Log.d("PdfViewerActivity", "SELECTED FILE: '$pdfFileName' at '$pdfFilePath'")
        } else {
            Toast.makeText(this, "잘못된 파일 인덱스입니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        if (pdfFilePath.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_loading_pdf), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        initializeCollaboration()
        loadPdf()
    }
    
    private fun setupUI() {
        binding.pdfView.isFocusable = true
        binding.pdfView.isFocusableInTouchMode = true
        binding.pdfView.requestFocus()
        
        // Hide page info after a few seconds
        binding.pageInfo.postDelayed({
            binding.pageInfo.animate().alpha(0.3f).duration = 500
        }, 3000)
    }
    
    private fun loadPdf() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading PDF: $pdfFilePath")
                val file = File(pdfFilePath)
                
                Log.d("PdfViewerActivity", "File exists: ${file.exists()}")
                Log.d("PdfViewerActivity", "File can read: ${file.canRead()}")
                Log.d("PdfViewerActivity", "File size: ${file.length()}")
                
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $pdfFileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                Log.d("PdfViewerActivity", "Initial load - creating ParcelFileDescriptor...")
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                Log.d("PdfViewerActivity", "Initial load - ParcelFileDescriptor created successfully")
                
                Log.d("PdfViewerActivity", "Initial load - creating PdfRenderer...")
                pdfRenderer = PdfRenderer(fileDescriptor)
                Log.d("PdfViewerActivity", "Initial load - PdfRenderer created successfully")
                
                pageCount = pdfRenderer?.pageCount ?: 0
                Log.d("PdfViewerActivity", "Initial load - PDF page count: $pageCount")
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Add file to server if in conductor mode
                        if (collaborationMode == CollaborationMode.CONDUCTOR) {
                            Log.d("PdfViewerActivity", "🎵 지휘자 모드: 파일을 서버에 추가 중...")
                            globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                        }
                        
                        // Initialize page cache with proper scale calculation
                        pageCache?.destroy() // Clean up previous cache
                        
                        // Calculate proper scale based on first page
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // PageCache에 설정 콜백 등록
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 초기화 완료 (calculated scale: $calculatedScale)")
                        
                        // Check if we should use two-page mode, then show target page or first page
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Re-register settings provider after cache operations and settings load
                            registerSettingsCallback()
                            
                            Log.d("PdfViewerActivity", "=== 최종 콜백 등록 완료 ===")
                            Log.d("PdfViewerActivity", "최종 설정 상태: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                            
                            // Navigate to target page if specified, otherwise first page
                            val targetPage = intent.getIntExtra("target_page", -1)
                            val initialPageIndex = if (targetPage > 0) {
                                (targetPage - 1).coerceIn(0, pageCount - 1) // Convert 1-based to 0-based
                            } else {
                                0
                            }
                            
                            Log.d("PdfViewerActivity", "Initial page navigation: targetPage=$targetPage, initialPageIndex=$initialPageIndex")
                            showPage(initialPageIndex)
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "PDF 파일에 페이지가 없습니다", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "PDF 열기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    private fun checkAndSetTwoPageMode(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First, ensure PDF file is in database
                ensurePdfFileInDatabase()
                
                Log.d("PdfViewerActivity", "=== checkAndSetTwoPageMode: PDF 파일 DB 등록 완료 ===")
                Log.d("PdfViewerActivity", "currentPdfFileId: $currentPdfFileId")
                
                // Load display settings after ensuring file is in database
                loadDisplaySettingsSync()
                
                Log.d("PdfViewerActivity", "=== checkAndSetTwoPageMode: 설정 로드 완료 ===")
                Log.d("PdfViewerActivity", "로드된 설정: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                
                // Force cache invalidation to apply loaded settings
                withContext(Dispatchers.Main) {
                    pageCache?.clear()
                    Log.d("PdfViewerActivity", "=== 설정 로드 후 캐시 클리어 완료 ===")
                }
                
                // Check if this file already has a saved setting
                currentPdfFileId?.let { fileId ->
                    val userPreference = musicRepository.getUserPreference(fileId)
                    
                    if (userPreference != null) {
                        // File-specific setting exists
                        withContext(Dispatchers.Main) {
                            isTwoPageMode = when (userPreference.displayMode) {
                                DisplayMode.DOUBLE -> true
                                DisplayMode.SINGLE -> false
                                DisplayMode.AUTO -> {
                                    // AUTO mode: determine based on orientation
                                    val pdfFile = musicRepository.getPdfFileById(fileId)
                                    pdfFile?.let { file ->
                                        screenWidth > screenHeight && file.orientation == PageOrientation.PORTRAIT
                                    } ?: false
                                }
                            }
                            Log.d("PdfViewerActivity", "Using saved display mode: ${userPreference.displayMode} for $pdfFileName")
                            onComplete()
                        }
                        return@launch
                    }
                }
                
                // Get first page to check aspect ratio
                val firstPage = pdfRenderer?.openPage(0)
                firstPage?.let { page ->
                    val pdfWidth = page.width
                    val pdfHeight = page.height
                    page.close()
                    
                    val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
                    val pdfAspectRatio = pdfWidth.toFloat() / pdfHeight.toFloat()
                    
                    Log.d("PdfViewerActivity", "Screen aspect ratio: $screenAspectRatio")
                    Log.d("PdfViewerActivity", "PDF aspect ratio: $pdfAspectRatio")
                    
                    withContext(Dispatchers.Main) {
                        // Check if aspect ratios are compatible (difference < 0.3)
                        val aspectRatioDiff = kotlin.math.abs(screenAspectRatio - pdfAspectRatio)
                        
                        if (aspectRatioDiff < 0.3f) {
                            // Aspect ratios are similar, use single page mode - NO SAVING
                            isTwoPageMode = false
                            Log.d("PdfViewerActivity", "Aspect ratios match (diff: $aspectRatioDiff), using single page mode (no saving)")
                            onComplete()
                        } else if (screenAspectRatio > 1.0f && pdfAspectRatio < 1.0f) {
                            // Screen is landscape and PDF is portrait - ask user
                            Log.d("PdfViewerActivity", "Landscape screen + Portrait PDF, asking user")
                            showTwoPageModeDialog(onComplete)
                        } else {
                            // Other cases (portrait screen, landscape PDF, etc.) - use single page - NO SAVING
                            isTwoPageMode = false
                            Log.d("PdfViewerActivity", "Other aspect ratio case, using single page mode (no saving)")
                            onComplete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error checking aspect ratio", e)
                withContext(Dispatchers.Main) {
                    isTwoPageMode = false
                    onComplete()
                }
            }
        }
    }
    
    private suspend fun ensurePdfFileInDatabase() {
        try {
            val file = File(pdfFilePath)
            if (!file.exists()) return
            
            // Check if file already exists in database
            val existingPdfFile = musicRepository.getPdfFileByPath(pdfFilePath)
            
            if (existingPdfFile != null) {
                currentPdfFileId = existingPdfFile.id
                Log.d("PdfViewerActivity", "Found existing PDF file in database: ${existingPdfFile.id}")
                return
            }
            
            // Analyze and insert new PDF file
            val pdfFile = PdfAnalyzer.analyzePdfFile(file)
            if (pdfFile != null) {
                musicRepository.insertPdfFile(pdfFile)
                currentPdfFileId = pdfFile.id
                Log.d("PdfViewerActivity", "Inserted new PDF file into database: ${pdfFile.id}")
            } else {
                Log.e("PdfViewerActivity", "Failed to analyze PDF file: $pdfFilePath")
            }
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error ensuring PDF file in database", e)
        }
    }
    
    private fun getFileKey(filePath: String): String {
        // Create a unique key for the file based on path and size
        return try {
            val file = File(filePath)
            "${file.name}_${file.length()}"
        } catch (e: Exception) {
            filePath.hashCode().toString()
        }
    }
    
    private fun saveDisplayModePreference(displayMode: DisplayMode) {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    musicRepository.setDisplayModeForFile(fileId, displayMode)
                    Log.d("PdfViewerActivity", "Saved display mode preference: $displayMode for file: $fileId")
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "Error saving display mode preference", e)
                }
            }
        }
    }
    
    private fun saveLastPageNumber(pageNumber: Int) {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    musicRepository.setLastPageForFile(fileId, pageNumber)
                    // Don't log every page change to avoid spam
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "Error saving last page number", e)
                }
            }
        }
    }
    
    private fun showTwoPageModeDialog(onComplete: () -> Unit) {
        // Create custom dialog with checkbox
        val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_multichoice, null)
        
        // Create a simple custom layout
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        // Message text
        val messageText = android.widget.TextView(this).apply {
            text = "세로 PDF를 가로 화면에서 보고 있습니다.\n'$pdfFileName' 파일을 어떻게 표시하시겠습니까?"
            textSize = 16f
            setPadding(0, 0, 0, 30)
        }
        
        // Checkbox for "remember choice"
        val rememberCheckbox = android.widget.CheckBox(this).apply {
            text = "이 선택을 기억하기"
            isChecked = true
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        
        linearLayout.addView(messageText)
        linearLayout.addView(rememberCheckbox)
        
        AlertDialog.Builder(this)
            .setTitle("페이지 표시 모드")
            .setView(linearLayout)
            .setPositiveButton("두 페이지로 보기") { _, _ ->
                isTwoPageMode = true
                if (rememberCheckbox.isChecked) {
                    saveDisplayModePreference(DisplayMode.DOUBLE)
                    Log.d("PdfViewerActivity", "User selected two page mode (saved) for $pdfFileName")
                } else {
                    Log.d("PdfViewerActivity", "User selected two page mode (temp) for $pdfFileName")
                }
                onComplete()
            }
            .setNegativeButton("한 페이지씩 보기") { _, _ ->
                isTwoPageMode = false
                if (rememberCheckbox.isChecked) {
                    saveDisplayModePreference(DisplayMode.SINGLE)
                    Log.d("PdfViewerActivity", "User selected single page mode (saved) for $pdfFileName")
                } else {
                    Log.d("PdfViewerActivity", "User selected single page mode (temp) for $pdfFileName")
                }
                onComplete()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showPage(index: Int) {
        if (index < 0 || index >= pageCount) return
        
        Log.d("PdfViewerActivity", "showPage called: index=$index, isTwoPageMode=$isTwoPageMode, pageCount=$pageCount")
        
        // Check cache first for instant display
        val cachedBitmap = if (isTwoPageMode) {
            if (index + 1 < pageCount) {
                // Two page mode - check if both pages are cached with correct scale
                val page1 = pageCache?.getPageImmediate(index)
                val page2 = pageCache?.getPageImmediate(index + 1)
                if (page1 != null && page2 != null) {
                    Log.d("PdfViewerActivity", "⚡ 페이지 $index, ${index + 1} 캐시에서 즉시 표시 (두 페이지 모드)")
                    combineTwoPages(page1, page2)
                } else {
                    null
                }
            } else {
                // Last page is odd - show on left side with empty right
                val page1 = pageCache?.getPageImmediate(index)
                if (page1 != null) {
                    Log.d("PdfViewerActivity", "⚡ 마지막 페이지 $index 캐시에서 왼쪽에 표시 (두 페이지 모드)")
                    combinePageWithEmpty(page1)
                } else {
                    null
                }
            }
        } else {
            pageCache?.getPageImmediate(index)
        }
        
        if (cachedBitmap != null) {
            // Cache hit - instant display!
            if (!isTwoPageMode) {
                Log.d("PdfViewerActivity", "⚡ 페이지 $index 캐시에서 즉시 표시")
            }
            binding.pdfView.setImageBitmap(cachedBitmap)
            setImageViewMatrix(cachedBitmap)
            pageIndex = index
            updatePageInfo()
            binding.loadingProgress.visibility = View.GONE
            
            // Save last page number to database
            saveLastPageNumber(index + 1)
            
            // Show page info briefly
            binding.pageInfo.animate().alpha(1f).duration = 200
            binding.pageInfo.postDelayed({
                binding.pageInfo.animate().alpha(0.3f).duration = 500
            }, 2000)
            
            // Start prerendering around this page
            pageCache?.prerenderAround(index)
            
            // Broadcast page change if in conductor mode
            if (collaborationMode == CollaborationMode.CONDUCTOR) {
                val actualPageNumber = if (isTwoPageMode) index + 1 else index + 1
                Log.d("PdfViewerActivity", "🎵 지휘자 모드: 페이지 $actualPageNumber 브로드캐스트 중...")
                globalCollaborationManager.broadcastPageChange(actualPageNumber, pdfFileName)
            }
            
            return
        }
        
        // Cache miss - fallback to traditional rendering with loading indicator
        Log.d("PdfViewerActivity", "⏳ 페이지 $index 캐시 미스 - 기존 방식으로 렌더링")
        binding.loadingProgress.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                try {
                    currentPage?.close()
                } catch (e: Exception) {
                    Log.w("PdfViewerActivity", "Current page already closed or error closing in showPage: ${e.message}")
                }
                
                val bitmap = if (isTwoPageMode) {
                    if (index + 1 < pageCount) {
                        Log.d("PdfViewerActivity", "=== 두 페이지 모드 렌더링: $index and ${index + 1} ===")
                        Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                        // For two-page mode, always use direct rendering to preserve aspect ratio
                        if (forceDirectRendering) {
                            forceDirectRendering = false // 플래그 리셋
                        }
                        renderTwoPages(index)
                    } else {
                        Log.d("PdfViewerActivity", "=== 마지막 페이지 왼쪽 표시 렌더링: $index ===")
                        Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                        if (forceDirectRendering) {
                            forceDirectRendering = false // 플래그 리셋
                        }
                        renderSinglePageOnLeft(index)
                    }
                } else {
                    Log.d("PdfViewerActivity", "=== 단일 페이지 모드 렌더링: $index ===")
                    Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                    
                    if (forceDirectRendering) {
                        Log.d("PdfViewerActivity", "설정 변경으로 인한 강제 직접 렌더링 - 캐시 완전 우회")
                        forceDirectRendering = false // 플래그 리셋
                        renderSinglePage(index)
                    } else {
                        Log.d("PdfViewerActivity", "일반 렌더링 - PageCache 자동 설정 관리 사용")
                        // PageCache will automatically handle settings changes and cache invalidation
                        pageCache?.getPageImmediate(index) ?: renderSinglePage(index)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    binding.pdfView.setImageBitmap(bitmap)
                    setImageViewMatrix(bitmap)
                    pageIndex = index
                    updatePageInfo()
                    binding.loadingProgress.visibility = View.GONE
                    
                    // Save last page number to database
                    saveLastPageNumber(index + 1)
                    
                    // Show page info briefly
                    binding.pageInfo.animate().alpha(1f).duration = 200
                    binding.pageInfo.postDelayed({
                        binding.pageInfo.animate().alpha(0.3f).duration = 500
                    }, 2000)
                    
                    // Start prerendering around this page
                    pageCache?.prerenderAround(index)
                    
                    // Broadcast page change if in conductor mode
                    if (collaborationMode == CollaborationMode.CONDUCTOR) {
                        val actualPageNumber = if (isTwoPageMode) index + 1 else index + 1
                        Log.d("PdfViewerActivity", "🎵 지휘자 모드: 페이지 $actualPageNumber 브로드캐스트 중...")
                        globalCollaborationManager.broadcastPageChange(actualPageNumber, pdfFileName)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(this@PdfViewerActivity, getString(R.string.error_loading_pdf), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun combineTwoPages(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap {
        Log.d("PdfViewerActivity", "=== COMBINE TWO PAGES DEBUG ===")
        Log.d("PdfViewerActivity", "Left bitmap: ${leftBitmap.width}x${leftBitmap.height}, aspect ratio: ${leftBitmap.width.toFloat() / leftBitmap.height.toFloat()}")
        Log.d("PdfViewerActivity", "Right bitmap: ${rightBitmap.width}x${rightBitmap.height}, aspect ratio: ${rightBitmap.width.toFloat() / rightBitmap.height.toFloat()}")
        // Calculate center padding in pixels (percentage of one page width)
        val paddingPixels = (leftBitmap.width * currentCenterPadding).toInt()
        Log.d("PdfViewerActivity", "Center padding: ${(currentCenterPadding * 100).toInt()}% (${paddingPixels}px)")
        
        // Create combined bitmap with center padding
        val combinedWidth = leftBitmap.width + rightBitmap.width + paddingPixels
        val combinedHeight = maxOf(leftBitmap.height, rightBitmap.height)
        
        Log.d("PdfViewerActivity", "Combined will be: ${combinedWidth}x${combinedHeight}, aspect ratio: ${combinedWidth.toFloat() / combinedHeight.toFloat()}")
        Log.d("PdfViewerActivity", "===============================")
        
        val combinedBitmap = Bitmap.createBitmap(
            combinedWidth,
            combinedHeight,
            Bitmap.Config.ARGB_8888
        )
        
        val combinedCanvas = Canvas(combinedBitmap)
        combinedCanvas.drawColor(android.graphics.Color.WHITE)
        
        // Draw left page
        combinedCanvas.drawBitmap(leftBitmap, 0f, 0f, null)
        
        // Draw right page with center padding offset
        val rightPageX = leftBitmap.width.toFloat() + paddingPixels
        combinedCanvas.drawBitmap(rightBitmap, rightPageX, 0f, null)
        
        Log.d("PdfViewerActivity", "Combined two cached pages successfully with ${(currentCenterPadding * 100).toInt()}% center padding")
        
        return combinedBitmap
    }
    
    private fun combinePageWithEmpty(leftBitmap: Bitmap): Bitmap {
        Log.d("PdfViewerActivity", "=== COMBINE PAGE WITH EMPTY DEBUG ===")
        Log.d("PdfViewerActivity", "Left bitmap: ${leftBitmap.width}x${leftBitmap.height}")
        // Calculate center padding in pixels (percentage of one page width)
        val paddingPixels = (leftBitmap.width * currentCenterPadding).toInt()
        Log.d("PdfViewerActivity", "Center padding: ${(currentCenterPadding * 100).toInt()}% (${paddingPixels}px)")
        
        // Create combined bitmap with empty right page
        val rightPageWidth = leftBitmap.width  // Same size as left page
        val combinedWidth = leftBitmap.width + rightPageWidth + paddingPixels
        val combinedHeight = leftBitmap.height
        
        Log.d("PdfViewerActivity", "Combined will be: ${combinedWidth}x${combinedHeight}")
        Log.d("PdfViewerActivity", "=====================================")
        
        val combinedBitmap = Bitmap.createBitmap(
            combinedWidth,
            combinedHeight,
            Bitmap.Config.ARGB_8888
        )
        
        val combinedCanvas = Canvas(combinedBitmap)
        combinedCanvas.drawColor(android.graphics.Color.WHITE)
        
        // Draw left page
        combinedCanvas.drawBitmap(leftBitmap, 0f, 0f, null)
        
        // Right side remains empty (white background already drawn)
        
        Log.d("PdfViewerActivity", "Combined page with empty right side successfully")
        
        return combinedBitmap
    }
    
    private suspend fun renderSinglePageOnLeft(pageIndex: Int): Bitmap {
        Log.d("PdfViewerActivity", "Starting renderSinglePageOnLeft for page $pageIndex")
        
        // Open the page
        val page = try {
            pdfRenderer?.openPage(pageIndex)
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Failed to open page $pageIndex", e)
            return renderSinglePage(pageIndex)
        }
        
        if (page == null) {
            Log.e("PdfViewerActivity", "Page is null")
            return renderSinglePage(pageIndex)
        }
        
        try {
            Log.d("PdfViewerActivity", "Page (${pageIndex}): ${page.width}x${page.height}, aspect ratio: ${page.width.toFloat() / page.height.toFloat()}")
            
            // Create original-size bitmap for the page
            val pageBitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            pageBitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            page.close()
            
            // Create combined bitmap with empty right side (same size as two-page layout)
            val paddingPixels = (pageBitmap.width * currentCenterPadding).toInt()
            val combinedWidth = pageBitmap.width * 2 + paddingPixels
            val combinedHeight = pageBitmap.height
            val combinedBitmap = Bitmap.createBitmap(combinedWidth, combinedHeight, Bitmap.Config.ARGB_8888)
            
            val combinedCanvas = Canvas(combinedBitmap)
            combinedCanvas.drawColor(android.graphics.Color.WHITE)
            combinedCanvas.drawBitmap(pageBitmap, 0f, 0f, null)
            // Right side remains empty
            
            // Calculate scale for the combined layout
            val combinedAspectRatio = combinedWidth.toFloat() / combinedHeight.toFloat()
            val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
            
            val scale = if (combinedAspectRatio > screenAspectRatio) {
                screenWidth.toFloat() / combinedWidth.toFloat()
            } else {
                screenHeight.toFloat() / combinedHeight.toFloat()
            }
            
            // Apply high-resolution multiplier
            val finalScale = (scale * 2.5f).coerceIn(1.0f, 4.0f)
            
            Log.d("PdfViewerActivity", "=== SINGLE PAGE ON LEFT SCALING ===")
            Log.d("PdfViewerActivity", "Combined: ${combinedWidth}x${combinedHeight}, aspect ratio: $combinedAspectRatio")
            Log.d("PdfViewerActivity", "Screen: ${screenWidth}x${screenHeight}, aspect ratio: $screenAspectRatio")
            Log.d("PdfViewerActivity", "Base scale: $scale, Final scale: $finalScale")
            Log.d("PdfViewerActivity", "===================================")
            
            // Create final high-resolution bitmap
            val finalWidth = (combinedWidth * finalScale).toInt()
            val finalHeight = (combinedHeight * finalScale).toInt()
            val finalBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
            
            val finalCanvas = Canvas(finalBitmap)
            finalCanvas.drawColor(android.graphics.Color.WHITE)
            
            // Scale and draw the combined bitmap
            val scaleMatrix = android.graphics.Matrix()
            scaleMatrix.setScale(finalScale, finalScale)
            finalCanvas.drawBitmap(combinedBitmap, scaleMatrix, null)
            
            // Clean up intermediate bitmaps
            pageBitmap.recycle()
            combinedBitmap.recycle()
            
            // Apply clipping if needed
            return applyDisplaySettings(finalBitmap, true)
            
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error in renderSinglePageOnLeft", e)
            try {
                page.close()
            } catch (closeError: Exception) {
                Log.w("PdfViewerActivity", "Page already closed or error closing: ${closeError.message}")
            }
            throw e
        }
    }
    
    private suspend fun renderSinglePage(index: Int): Bitmap {
        currentPage = pdfRenderer?.openPage(index)
        val page = currentPage ?: throw Exception("Failed to open page $index")
        
        // Calculate high-resolution dimensions
        val scale = calculateOptimalScale(page.width, page.height)
        val renderWidth = (page.width * scale).toInt()
        val renderHeight = (page.height * scale).toInt()
        
        val originalPageRatio = page.width.toFloat() / page.height.toFloat()
        val renderedPageRatio = renderWidth.toFloat() / renderHeight.toFloat()
        
        Log.d("PdfViewerActivity", "=== SINGLE PAGE RENDER ===")
        Log.d("PdfViewerActivity", "Original PDF page: ${page.width}x${page.height}, aspect ratio: $originalPageRatio")
        Log.d("PdfViewerActivity", "Rendered bitmap: ${renderWidth}x${renderHeight}, aspect ratio: $renderedPageRatio")
        Log.d("PdfViewerActivity", "Scale: $scale, aspect ratio preserved: ${kotlin.math.abs(originalPageRatio - renderedPageRatio) < 0.001f}")
        Log.d("PdfViewerActivity", "==========================")
        
        val bitmap = Bitmap.createBitmap(
            renderWidth,
            renderHeight,
            Bitmap.Config.ARGB_8888
        )
        
        // Fill with white background
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        // Create transform matrix for scaling
        val matrix = android.graphics.Matrix()
        matrix.setScale(scale, scale)
        
        // Render with scaling (Matrix only to preserve aspect ratio)
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        // Apply clipping and padding if needed
        return applyDisplaySettings(bitmap, false)
    }
    
    private suspend fun renderTwoPages(leftPageIndex: Int): Bitmap {
        Log.d("PdfViewerActivity", "Starting renderTwoPages for pages $leftPageIndex and ${leftPageIndex + 1}")
        
        // Open left page
        val leftPage = try {
            pdfRenderer?.openPage(leftPageIndex)
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Failed to open left page $leftPageIndex", e)
            return renderSinglePage(leftPageIndex)
        }
        
        if (leftPage == null) {
            Log.e("PdfViewerActivity", "Left page is null")
            return renderSinglePage(leftPageIndex)
        }
        
        try {
            // Log individual page dimensions
            Log.d("PdfViewerActivity", "=== INDIVIDUAL PAGE ASPECT RATIOS ===")
            Log.d("PdfViewerActivity", "Left page (${leftPageIndex}): ${leftPage.width}x${leftPage.height}, aspect ratio: ${leftPage.width.toFloat() / leftPage.height.toFloat()}")
            
            // Create original-size bitmap for left page (no scaling yet)
            val leftBitmap = Bitmap.createBitmap(leftPage.width, leftPage.height, Bitmap.Config.ARGB_8888)
            leftBitmap.eraseColor(android.graphics.Color.WHITE)
            leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            leftPage.close()
            
            // Open right page
            val rightPage = try {
                pdfRenderer?.openPage(leftPageIndex + 1)
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Failed to open right page ${leftPageIndex + 1}", e)
                return leftBitmap // Return just the left page
            }
            
            if (rightPage == null) {
                Log.e("PdfViewerActivity", "Right page is null")
                return leftBitmap // Return just the left page
            }
            
            try {
                Log.d("PdfViewerActivity", "Right page (${leftPageIndex + 1}): ${rightPage.width}x${rightPage.height}, aspect ratio: ${rightPage.width.toFloat() / rightPage.height.toFloat()}")
                Log.d("PdfViewerActivity", "=====================================")
                
                // Create original-size bitmap for right page (no scaling yet)
                val rightBitmap = Bitmap.createBitmap(rightPage.width, rightPage.height, Bitmap.Config.ARGB_8888)
                rightBitmap.eraseColor(android.graphics.Color.WHITE)
                rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                rightPage.close()
                
                // Combine two pages side by side with center padding (original resolution)
                val paddingPixels = (leftBitmap.width * currentCenterPadding).toInt()
                val combinedWidth = leftBitmap.width + rightBitmap.width + paddingPixels
                val combinedHeight = maxOf(leftBitmap.height, rightBitmap.height)
                val combinedBitmap = Bitmap.createBitmap(combinedWidth, combinedHeight, Bitmap.Config.ARGB_8888)
                
                val combinedCanvas = Canvas(combinedBitmap)
                combinedCanvas.drawColor(android.graphics.Color.WHITE)
                combinedCanvas.drawBitmap(leftBitmap, 0f, 0f, null)
                
                // Draw right page with center padding offset
                val rightPageX = leftBitmap.width.toFloat() + paddingPixels
                combinedCanvas.drawBitmap(rightBitmap, rightPageX, 0f, null)
                
                Log.d("PdfViewerActivity", "Combined pages with ${(currentCenterPadding * 100).toInt()}% center padding")
                
                // Calculate scale based on combined bitmap aspect ratio vs screen
                val combinedAspectRatio = combinedWidth.toFloat() / combinedHeight.toFloat()
                val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
                
                val scale = if (combinedAspectRatio > screenAspectRatio) {
                    // Combined bitmap is wider than screen -> scale by width
                    screenWidth.toFloat() / combinedWidth.toFloat()
                } else {
                    // Combined bitmap is taller than screen -> scale by height
                    screenHeight.toFloat() / combinedHeight.toFloat()
                }
                
                // Apply high-resolution multiplier (2-4x for crisp rendering)
                val finalScale = (scale * 2.5f).coerceIn(1.0f, 4.0f)
                
                Log.d("PdfViewerActivity", "=== TWO PAGE SCALING ===")
                Log.d("PdfViewerActivity", "Combined: ${combinedWidth}x${combinedHeight}, aspect ratio: $combinedAspectRatio")
                Log.d("PdfViewerActivity", "Screen: ${screenWidth}x${screenHeight}, aspect ratio: $screenAspectRatio")
                Log.d("PdfViewerActivity", "Base scale: $scale, Final scale: $finalScale")
                Log.d("PdfViewerActivity", "========================")
                
                // Create final high-resolution bitmap
                val finalWidth = (combinedWidth * finalScale).toInt()
                val finalHeight = (combinedHeight * finalScale).toInt()
                val finalBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                
                val finalCanvas = Canvas(finalBitmap)
                finalCanvas.drawColor(android.graphics.Color.WHITE)
                
                // Scale and draw the combined bitmap
                val scaleMatrix = android.graphics.Matrix()
                scaleMatrix.setScale(finalScale, finalScale)
                finalCanvas.drawBitmap(combinedBitmap, scaleMatrix, null)
                
                // Clean up intermediate bitmaps
                leftBitmap.recycle()
                rightBitmap.recycle()
                combinedBitmap.recycle()
                
                // Apply clipping and padding if needed
                return applyDisplaySettings(finalBitmap, true)
                
            } finally {
                try {
                    rightPage.close()
                } catch (e: Exception) {
                    Log.w("PdfViewerActivity", "Right page already closed or error closing: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error in renderTwoPages", e)
            try {
                leftPage.close()
            } catch (closeError: Exception) {
                Log.w("PdfViewerActivity", "Left page already closed or error closing: ${closeError.message}")
            }
            return renderSinglePage(leftPageIndex)
        }
    }
    
    private fun calculateOptimalScale(pageWidth: Int, pageHeight: Int, forTwoPageMode: Boolean = false): Float {
        // 두 페이지 모드에서는 합쳐진 크기 기준으로 계산
        val effectiveWidth = if (forTwoPageMode) pageWidth * 2 else pageWidth
        val effectiveHeight = pageHeight
        
        // 화면 크기에 맞는 최적 스케일 계산
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val pageRatio = effectiveWidth.toFloat() / effectiveHeight.toFloat()
        
        val scale = if (pageRatio > screenRatio) {
            // 페이지가 화면보다 가로가 긴 경우 - 가로 기준으로 맞춤
            screenWidth.toFloat() / effectiveWidth.toFloat()
        } else {
            // 페이지가 화면보다 세로가 긴 경우 - 세로 기준으로 맞춤  
            screenHeight.toFloat() / effectiveHeight.toFloat()
        }
        
        // 최소 2배, 최대 4배 스케일링 (고해상도 보장)
        val finalScale = (scale * 2.0f).coerceIn(2.0f, 4.0f)
        
        Log.d("PdfViewerActivity", "=== SCALE CALCULATION ===")
        Log.d("PdfViewerActivity", "Input: Page ${pageWidth}x${pageHeight}, forTwoPageMode=$forTwoPageMode")
        Log.d("PdfViewerActivity", "Effective: ${effectiveWidth}x${effectiveHeight}")
        Log.d("PdfViewerActivity", "Screen: ${screenWidth}x${screenHeight}")
        Log.d("PdfViewerActivity", "Screen ratio: $screenRatio, Page ratio: $pageRatio")
        Log.d("PdfViewerActivity", "Scale by ${if (pageRatio > screenRatio) "WIDTH" else "HEIGHT"}: $scale")
        Log.d("PdfViewerActivity", "Final scale: $finalScale")
        Log.d("PdfViewerActivity", "==========================")
        
        return finalScale
    }
    
    private fun setImageViewMatrix(bitmap: Bitmap) {
        val imageMatrix = android.graphics.Matrix()
        
        // Calculate scale to fit the image in the view while preserving aspect ratio
        val viewWidth = binding.pdfView.width
        val viewHeight = binding.pdfView.height
        
        if (viewWidth == 0 || viewHeight == 0) {
            // View not yet measured, set later
            binding.pdfView.post {
                setImageViewMatrix(bitmap)
            }
            return
        }
        
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        
        // Calculate original aspect ratio
        val originalAspectRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        
        val scaleX = viewWidth.toFloat() / bitmapWidth.toFloat()
        val scaleY = viewHeight.toFloat() / bitmapHeight.toFloat()
        
        // Use the smaller scale to ensure the whole image fits
        val scale = minOf(scaleX, scaleY)
        
        // Calculate translation to center the image
        val scaledWidth = bitmapWidth * scale
        val scaledHeight = bitmapHeight * scale
        val dx = (viewWidth - scaledWidth) / 2f
        val dy = (viewHeight - scaledHeight) / 2f
        
        // Calculate final displayed aspect ratio
        val finalAspectRatio = scaledWidth / scaledHeight
        
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        
        binding.pdfView.imageMatrix = imageMatrix
        
        Log.d("PdfViewerActivity", "=== ASPECT RATIO CHECK ===")
        Log.d("PdfViewerActivity", "Original bitmap: ${bitmapWidth}x${bitmapHeight}, aspect ratio: $originalAspectRatio")
        Log.d("PdfViewerActivity", "Final displayed: ${scaledWidth}x${scaledHeight}, aspect ratio: $finalAspectRatio")
        Log.d("PdfViewerActivity", "Aspect ratio preserved: ${kotlin.math.abs(originalAspectRatio - finalAspectRatio) < 0.001f}")
        Log.d("PdfViewerActivity", "ImageView matrix: scale=$scale, translate=($dx, $dy)")
        Log.d("PdfViewerActivity", "========================")
    }
    
    private fun updatePageInfo() {
        val fileInfo = if (fileNameList.isNotEmpty()) {
            "[${currentFileIndex + 1}/${filePathList.size}] ${fileNameList[currentFileIndex]} - "
        } else {
            "$pdfFileName - "
        }
        
        val pageInfo = if (isTwoPageMode && pageIndex + 1 < pageCount) {
            // Two page mode: show "1-2 / 10" format
            "${pageIndex + 1}-${pageIndex + 2} / $pageCount"
        } else {
            // Single page mode: show "1 / 10" format
            "${pageIndex + 1} / $pageCount"
        }
        
        // Add cache info for debugging (only show if cache exists)
        val cacheInfo = pageCache?.let { cache ->
            " [${cache.getCacheInfo()}]"
        } ?: ""
        
        binding.pageInfo.text = "$fileInfo$pageInfo$cacheInfo"
    }
    
    private fun loadNextFile() {
        if (currentFileIndex < filePathList.size - 1) {
            currentFileIndex++
            loadFile(filePathList[currentFileIndex], fileNameList[currentFileIndex])
        }
    }
    
    private fun loadPreviousFile() {
        if (currentFileIndex > 0) {
            currentFileIndex--
            loadFile(filePathList[currentFileIndex], fileNameList[currentFileIndex], true)
        }
    }
    
    private fun loadFileWithTargetPage(filePath: String, fileName: String, targetPage: Int, originalMode: CollaborationMode) {
        // Close current PDF
        Log.d("PdfViewerActivity", "Closing current PDF resources for collaboration file change...")
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Current page already closed or error closing: ${e.message}")
        }
        currentPage = null
        
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "PdfRenderer already closed or error closing: ${e.message}")
        }
        pdfRenderer = null
        
        pdfFilePath = filePath
        pdfFileName = fileName
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading file for collaboration: $filePath")
                val file = File(pdfFilePath)
                
                if (!file.exists() || !file.canRead()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 읽을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        collaborationMode = originalMode
                        finish()
                    }
                    return@launch
                }
                
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                pageCount = pdfRenderer?.pageCount ?: 0
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache for new file
                        pageCache?.destroy()
                        
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // Register settings callback immediately after PageCache creation
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 재초기화 완료 for collaboration file change")
                        
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for collaboration file change, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Navigate to target page (convert from 1-based to 0-based)
                            val targetIndex = (targetPage - 1).coerceIn(0, pageCount - 1)
                            showPage(targetIndex)
                            
                            // Restore collaboration mode
                            collaborationMode = originalMode
                            
                            Log.d("PdfViewerActivity", "🎼 연주자 모드: 파일 '$fileName' 로드 완료, 페이지 $targetPage 로 이동 완료")
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "빈 PDF 파일입니다: $fileName", Toast.LENGTH_SHORT).show()
                        collaborationMode = originalMode
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading file for collaboration", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "파일 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    collaborationMode = originalMode
                    finish()
                }
            }
        }
    }
    
    private fun loadFile(filePath: String, fileName: String, goToLastPage: Boolean = false) {
        // Close current PDF
        Log.d("PdfViewerActivity", "Closing current PDF resources...")
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Current page already closed or error closing: ${e.message}")
        }
        currentPage = null
        Log.d("PdfViewerActivity", "Current page closed")
        
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "PdfRenderer already closed or error closing: ${e.message}")
        }
        pdfRenderer = null
        Log.d("PdfViewerActivity", "PdfRenderer closed")
        
        pdfFilePath = filePath
        pdfFileName = fileName
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading new file: $filePath")
                val file = File(pdfFilePath)
                
                Log.d("PdfViewerActivity", "New file exists: ${file.exists()}")
                Log.d("PdfViewerActivity", "New file path: ${file.absolutePath}")
                
                // 파일 존재 확인
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        // 파일 목록을 다시 로드하고 현재 액티비티 종료
                        finish()
                    }
                    return@launch
                }
                
                if (!file.canRead()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 읽을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }
                
                Log.d("PdfViewerActivity", "File permissions OK, creating ParcelFileDescriptor...")
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                Log.d("PdfViewerActivity", "ParcelFileDescriptor created successfully")
                
                Log.d("PdfViewerActivity", "Creating PdfRenderer...")
                pdfRenderer = PdfRenderer(fileDescriptor)
                Log.d("PdfViewerActivity", "PdfRenderer created successfully")
                
                pageCount = pdfRenderer?.pageCount ?: 0
                Log.d("PdfViewerActivity", "Page count retrieved: $pageCount")
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache for new file with proper scale calculation
                        pageCache?.destroy() // Clean up previous cache
                        
                        // Calculate proper scale based on first page
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // Register settings callback immediately after PageCache creation
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 재초기화 완료 for $fileName (scale: $calculatedScale)")
                        
                        // Check two-page mode for this new file, then show the page
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for loadFile, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            val targetPage = if (goToLastPage) pageCount - 1 else 0
                            showPage(targetPage)
                            
                            // Broadcast file change if in conductor mode
                            if (collaborationMode == CollaborationMode.CONDUCTOR) {
                                // Add file to server first
                                globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                                // Then broadcast the change with the target page number
                                val actualPageNumber = targetPage + 1 // Convert to 1-based index
                                globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber)
                            }
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "빈 PDF 파일입니다: $fileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Exception in loadFile for $fileName", e)
                Log.e("PdfViewerActivity", "Exception type: ${e::class.java.simpleName}")
                Log.e("PdfViewerActivity", "Exception message: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "파일 열기 실패: $fileName - ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isNavigationGuideVisible) {
                    if (navigationGuideType == "start" && currentFileIndex > 0) {
                        // 첫 페이지 안내에서 왼쪽 키 -> 이전 파일로 이동
                        hideNavigationGuide()
                        loadPreviousFile()
                        return true
                    }
                    // 안내가 표시된 상태에서는 일반 페이지 이동 차단
                    return true
                } else if (pageIndex > 0) {
                    val nextPageIndex = if (isTwoPageMode) pageIndex - 2 else pageIndex - 1
                    showPage(maxOf(0, nextPageIndex))
                    return true
                } else {
                    // 첫 페이지에서 안내 표시
                    showStartOfFileGuide()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isNavigationGuideVisible) {
                    if (navigationGuideType == "end" && currentFileIndex < filePathList.size - 1) {
                        // 마지막 페이지 안내에서 오른쪽 키 -> 다음 파일로 이동
                        hideNavigationGuide()
                        loadNextFile()
                        return true
                    }
                    // 안내가 표시된 상태에서는 일반 페이지 이동 차단
                    return true
                } else {
                    val nextPageIndex = if (isTwoPageMode) pageIndex + 2 else pageIndex + 1
                    if (nextPageIndex < pageCount) {
                        showPage(nextPageIndex)
                        return true
                    } else {
                        // 마지막 페이지에서 안내 표시
                        showEndOfFileGuide()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // 지휘자 모드에서 뒤로가기 시 연주자에게 알림
                if (collaborationMode == CollaborationMode.CONDUCTOR) {
                    Log.d("PdfViewerActivity", "🎵 지휘자 모드: 뒤로가기 브로드캐스트")
                    globalCollaborationManager.broadcastBackToList()
                }
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    // Start long press detection
                    isLongPressing = true
                    longPressHandler.postDelayed(longPressRunnable, longPressDelay)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // 메뉴 키로 페이지 정보 표시/숨김 토글
                if (binding.pageInfo.alpha > 0.5f) {
                    binding.pageInfo.animate().alpha(0.3f).duration = 200
                } else {
                    binding.pageInfo.animate().alpha(1f).duration = 200
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Cancel long press and handle short press
                longPressHandler.removeCallbacks(longPressRunnable)
                
                if (isLongPressing && event?.isCanceled != true) {
                    // This was a short press, not a long press
                    if (isNavigationGuideVisible) {
                        // 안내가 표시되어 있으면 숨기기
                        hideNavigationGuide()
                    } else {
                        // Toggle page info visibility
                        if (binding.pageInfo.alpha > 0.5f) {
                            binding.pageInfo.animate().alpha(0.3f).duration = 200
                        } else {
                            binding.pageInfo.animate().alpha(1f).duration = 200
                        }
                    }
                }
                
                isLongPressing = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    
    // handleEndOfFile()과 handleStartOfFile() 메서드 삭제 - 더 이상 필요하지 않음
    
    private fun showEndOfFileGuide() {
        val hasNextFile = currentFileIndex < filePathList.size - 1
        
        // 왼쪽 네비게이션은 숨김 (더 이상 목록으로 돌아가기 없음)
        binding.leftNavigation.visibility = View.GONE
        
        // 오른쪽 네비게이션 설정 (다음 파일 또는 없음)
        if (hasNextFile) {
            binding.rightNavigation.visibility = View.VISIBLE
            binding.rightNavText.text = "다음 파일"
            binding.rightNavSubText.text = fileNameList[currentFileIndex + 1]
        } else {
            binding.rightNavigation.visibility = View.GONE
        }
        
        showNavigationGuide("end")
    }
    
    private fun showStartOfFileGuide() {
        val hasPreviousFile = currentFileIndex > 0
        
        // 왼쪽 네비게이션 설정 (이전 파일 또는 없음)
        if (hasPreviousFile) {
            binding.leftNavigation.visibility = View.VISIBLE
            binding.leftNavText.text = "이전 파일"
            binding.leftNavSubText.text = fileNameList[currentFileIndex - 1]
        } else {
            binding.leftNavigation.visibility = View.GONE
        }
        
        // 오른쪽 네비게이션은 숨김 (더 이상 목록으로 돌아가기 없음)
        binding.rightNavigation.visibility = View.GONE
        
        showNavigationGuide("start")
    }
    
    private fun showNavigationGuide(type: String) {
        isNavigationGuideVisible = true
        navigationGuideType = type
        binding.navigationGuide.visibility = View.VISIBLE
        binding.navigationGuide.animate().alpha(1f).duration = 300
        
        // 3초 후 자동으로 숨기기
        binding.navigationGuide.postDelayed({
            hideNavigationGuide()
        }, 3000)
    }
    
    private fun hideNavigationGuide() {
        if (isNavigationGuideVisible) {
            isNavigationGuideVisible = false
            navigationGuideType = ""
            binding.navigationGuide.animate().alpha(0f).withEndAction {
                binding.navigationGuide.visibility = View.GONE
                binding.leftNavigation.visibility = View.GONE
                binding.rightNavigation.visibility = View.GONE
            }.duration = 300
        }
    }
    
    private fun initializeCollaboration() {
        // Get current collaboration mode from global manager
        collaborationMode = globalCollaborationManager.getCurrentMode()
        
        Log.d("PdfViewerActivity", "Collaboration mode: $collaborationMode")
        
        when (collaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                setupConductorCallbacks()
                updateCollaborationStatus()
            }
            CollaborationMode.PERFORMER -> {
                setupPerformerCallbacks()
                updateCollaborationStatus()
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.visibility = View.GONE
            }
        }
    }
    
    private fun setupConductorCallbacks() {
        globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
            runOnUiThread {
                Log.d("PdfViewerActivity", "🎵 지휘자 모드: 새 연주자 연결됨 - $deviceName")
                Toast.makeText(this@PdfViewerActivity, "$deviceName 연결됨", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
                
                // Send current file and page to newly connected client
                Log.d("PdfViewerActivity", "🎵 지휘자 모드: 현재 상태를 새 연주자에게 전송 중...")
                // Add file to server so performers can download if needed
                globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                
                val actualPageNumber = if (isTwoPageMode) pageIndex + 1 else pageIndex + 1
                globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber)
            }
        }
        
        globalCollaborationManager.setOnServerClientDisconnected { clientId ->
            runOnUiThread {
                Toast.makeText(this@PdfViewerActivity, "기기 연결 해제됨", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
            }
        }
    }
    
    private fun setupPerformerCallbacks() {
        globalCollaborationManager.setOnPageChangeReceived { page, file ->
            runOnUiThread {
                if (file == pdfFileName) {
                    handleRemotePageChange(page)
                }
            }
        }
        
        globalCollaborationManager.setOnFileChangeReceived { file, page ->
            runOnUiThread {
                handleRemoteFileChange(file, page)
            }
        }
        
        globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
            runOnUiThread {
                val status = if (isConnected) "연결됨" else "연결 끊김"
                Toast.makeText(this@PdfViewerActivity, "지휘자: $status", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
            }
        }
        
        globalCollaborationManager.setOnBackToListReceived {
            runOnUiThread {
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 뒤로가기 신호 수신, 파일 목록으로 돌아가기")
                finish()
            }
        }
    }
    
    private fun handleRemotePageChange(page: Int) {
        // Convert to 0-based index
        val targetIndex = page - 1
        
        Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 변경 신호 수신됨 (current file: $pdfFileName, pageCount: $pageCount)")
        
        if (targetIndex >= 0 && targetIndex < pageCount) {
            // Temporarily disable conductor mode to prevent infinite loop
            val originalMode = collaborationMode
            collaborationMode = CollaborationMode.NONE
            
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 중...")
            showPage(targetIndex)
            
            // Restore original mode
            collaborationMode = originalMode
            
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 완료")
        } else {
            Log.w("PdfViewerActivity", "🎼 연주자 모드: 잘못된 페이지 번호 $page (총 $pageCount 페이지)")
        }
    }
    
    private fun handleRemoteFileChange(file: String, targetPage: Int) {
        // Check if the requested file exists in our file list
        val fileIndex = fileNameList.indexOf(file)
        
        if (fileIndex >= 0 && fileIndex < filePathList.size) {
            // Load the requested file
            currentFileIndex = fileIndex
            pdfFilePath = filePathList[fileIndex]
            pdfFileName = fileNameList[fileIndex]
            
            // Temporarily disable collaboration to prevent loops
            val originalMode = collaborationMode
            collaborationMode = CollaborationMode.NONE
            
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 파일 '$file' 로 변경 중... (목표 페이지: $targetPage)")
            
            // Load file and navigate to target page after loading completes
            loadFileWithTargetPage(pdfFilePath, pdfFileName, targetPage, originalMode)
            
        } else {
            Log.w("PdfViewerActivity", "🎼 연주자 모드: 요청된 파일을 찾을 수 없습니다: $file")
            
            // Try to download from conductor
            val conductorAddress = globalCollaborationManager.getConductorAddress()
            if (conductorAddress.isNotEmpty()) {
                showDownloadDialog(file, conductorAddress, targetPage)
            } else {
                Toast.makeText(this, "요청된 파일을 찾을 수 없습니다: $file", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateCollaborationStatus() {
        when (collaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                binding.collaborationStatus.text = "지휘자: ${clientCount}명 연결"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                val status = if (isConnected) "연결됨" else "연결 끊김"
                binding.collaborationStatus.text = "연주자: $status"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.visibility = View.GONE
            }
        }
    }
    
    private fun showDownloadDialog(fileName: String, conductorAddress: String, targetPage: Int = 1) {
        val ipOnly = conductorAddress.split(":").firstOrNull() ?: conductorAddress
        val fileServerUrl = "http://$ipOnly:8090"
        
        AlertDialog.Builder(this)
            .setTitle("파일 다운로드")
            .setMessage("'$fileName' 파일이 없습니다.\n지휘자로부터 다운로드하시겠습니까?")
            .setPositiveButton("다운로드") { _, _ ->
                downloadFileFromConductor(fileName, fileServerUrl, targetPage)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun downloadFileFromConductor(fileName: String, serverUrl: String, targetPage: Int = 1) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("다운로드 중...")
            .setMessage("$fileName\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        Log.d("PdfViewerActivity", "🎼 파일 다운로드 시작: $fileName (목표 페이지: $targetPage)")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val downloadUrl = "$serverUrl/download/$encodedFileName"
                Log.d("PdfViewerActivity", "Downloading from: $downloadUrl")
                
                val url = java.net.URL(downloadUrl)
                val connection = url.openConnection()
                connection.connect()
                
                val fileLength = connection.contentLength
                val input = connection.getInputStream()
                val downloadPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val output = java.io.FileOutputStream(downloadPath)
                
                val buffer = ByteArray(4096)
                var total: Long = 0
                var count: Int
                
                while (input.read(buffer).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.setMessage("$fileName\n$progress%")
                        }
                    }
                    output.write(buffer, 0, count)
                }
                
                output.flush()
                output.close()
                input.close()
                
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PdfViewerActivity, "다운로드 완료: $fileName", Toast.LENGTH_SHORT).show()
                    
                    // Refresh file list and load the downloaded file with target page
                    refreshFileListAndLoad(fileName, downloadPath.absolutePath, targetPage)
                }
                
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PdfViewerActivity, "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun refreshFileListAndLoad(fileName: String, filePath: String, targetPage: Int = 1) {
        // Trigger media scanner to make file visible
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf(filePath),
            arrayOf("application/pdf"),
            null
        )
        
        // Add to current file lists
        fileNameList = fileNameList.toMutableList().apply { add(fileName) }
        filePathList = filePathList.toMutableList().apply { add(filePath) }
        currentFileIndex = fileNameList.size - 1
        
        // Update current file info
        pdfFileName = fileName
        pdfFilePath = filePath
        
        // Notify that file list should be refreshed when returning to MainActivity
        val sharedPrefs = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("refresh_file_list", true).apply()
        
        // Close current PDF if open
        try {
            currentPage?.close()
            currentPage = null
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing current page: ${e.message}")
        }
        
        try {
            pdfRenderer?.close()
            pdfRenderer = null
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing PDF renderer: ${e.message}")
        }
        
        Log.d("PdfViewerActivity", "🎼 다운로드된 파일 로드 중, 목표 페이지: $targetPage")
        
        // Load the new file with target page
        loadPdfWithTargetPage(targetPage)
    }
    
    private fun loadPdfWithTargetPage(targetPage: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading PDF with target page: $pdfFilePath (target: $targetPage)")
                val file = File(pdfFilePath)
                
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $pdfFileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                pageCount = pdfRenderer?.pageCount ?: 0
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache
                        pageCache?.destroy()
                        
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        Log.d("PdfViewerActivity", "PageCache 초기화 완료 for downloaded file")
                        
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for downloaded file, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Navigate to target page
                            val targetIndex = (targetPage - 1).coerceIn(0, pageCount - 1)
                            showPage(targetIndex)
                            
                            Log.d("PdfViewerActivity", "🎼 다운로드된 파일 로드 완료, 페이지 $targetPage 로 이동")
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "PDF 파일에 페이지가 없습니다", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading downloaded PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "PDF 열기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    /**
     * PDF 표시 옵션 다이얼로그 표시 (OK 버튼 길게 누르기)
     */
    private fun showPdfDisplayOptions() {
        Log.d("PdfViewerActivity", "PDF 표시 옵션 다이얼로그 표시")
        
        val options = arrayOf(
            "두 페이지 모드 전환",
            "위/아래 클리핑 설정",
            "가운데 여백 설정",
            "취소"
        )
        
        AlertDialog.Builder(this)
            .setTitle("PDF 표시 옵션")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> showTwoPageModeDialog { 
                        // 두 페이지 모드 변경 완료 후 현재 페이지 다시 렌더링
                        showPage(pageIndex)
                    }
                    1 -> showClippingDialog()
                    2 -> showPaddingDialog()
                    3 -> dialog.dismiss()
                }
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    /**
     * PageCache에 설정 콜백을 등록하는 헬퍼 함수
     */
    private fun registerSettingsCallback() {
        pageCache?.setDisplaySettingsProvider { 
            Log.d("PdfViewerActivity", "설정 콜백 호출: 위 ${(currentTopClipping * 100).toInt()}%, 아래 ${(currentBottomClipping * 100).toInt()}%, 여백 ${(currentCenterPadding * 100).toInt()}%")
            Triple(currentTopClipping, currentBottomClipping, currentCenterPadding) 
        }
    }
    
    /**
     * 현재 클리핑 설정에 해당하는 선택 항목 찾기 (deprecated - 슬라이더 UI로 대체됨)
     */
    @Deprecated("No longer needed with slider UI")
    private fun getCurrentClippingSelection(): Int {
        return when {
            currentTopClipping == 0f && currentBottomClipping == 0f -> 0 // 클리핑 없음
            currentTopClipping == 0.05f && currentBottomClipping == 0f -> 1 // 위 5%
            currentTopClipping == 0.10f && currentBottomClipping == 0f -> 2 // 위 10%
            currentTopClipping == 0.15f && currentBottomClipping == 0f -> 3 // 위 15%
            currentTopClipping == 0f && currentBottomClipping == 0.05f -> 4 // 아래 5%
            currentTopClipping == 0f && currentBottomClipping == 0.10f -> 5 // 아래 10%
            currentTopClipping == 0f && currentBottomClipping == 0.15f -> 6 // 아래 15%
            currentTopClipping == 0.05f && currentBottomClipping == 0.05f -> 7 // 위/아래 각 5%
            currentTopClipping == 0.10f && currentBottomClipping == 0.10f -> 8 // 위/아래 각 10%
            else -> -1 // 사용자 정의
        }
    }
    
    /**
     * 위/아래 클리핑 설정 다이얼로그
     */
    private fun showClippingDialog() {
        // 커스텀 레이아웃 생성
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }
        
        // 위쪽 클리핑 레이블
        val topLabel = android.widget.TextView(this).apply {
            text = "위쪽 클리핑: ${(currentTopClipping * 100).toInt()}%"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        dialogView.addView(topLabel)
        
        // 위쪽 클리핑 슬라이더 (0-30%)
        val topSeekBar = android.widget.SeekBar(this).apply {
            max = 15  // 0-15%
            progress = (currentTopClipping * 100).toInt()
            setPadding(0, 0, 0, 30)
        }
        dialogView.addView(topSeekBar)
        
        // 아래쪽 클리핑 레이블
        val bottomLabel = android.widget.TextView(this).apply {
            text = "아래쪽 클리핑: ${(currentBottomClipping * 100).toInt()}%"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        dialogView.addView(bottomLabel)
        
        // 아래쪽 클리핑 슬라이더 (0-30%)
        val bottomSeekBar = android.widget.SeekBar(this).apply {
            max = 15  // 0-15%
            progress = (currentBottomClipping * 100).toInt()
            setPadding(0, 0, 0, 20)
        }
        dialogView.addView(bottomSeekBar)
        
        // 빠른 설정 버튼들
        val quickButtonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        
        val resetButton = android.widget.Button(this).apply {
            text = "초기화"
            setOnClickListener {
                topSeekBar.progress = 0
                bottomSeekBar.progress = 0
                setupPreview(topSeekBar)
            }
        }
        quickButtonsLayout.addView(resetButton)
        
        val bothButton = android.widget.Button(this).apply {
            text = "위/아래 5%"
            setOnClickListener {
                topSeekBar.progress = 5
                bottomSeekBar.progress = 5
                setupPreview(topSeekBar)
            }
        }
        quickButtonsLayout.addView(bothButton)
        
        dialogView.addView(quickButtonsLayout)
        
        // 미리보기 텍스트
        val previewLabel = android.widget.TextView(this).apply {
            text = "실시간 미리보기가 적용됩니다"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        dialogView.addView(previewLabel)
        
        // 실시간 미리보기를 위한 변수
        var previewHandler: android.os.Handler? = null
        var previewRunnable: Runnable? = null
        
        // 원래 설정 저장
        val originalTop = currentTopClipping
        val originalBottom = currentBottomClipping
        
        val applyPreview = {
            val topPercent = topSeekBar.progress / 100f
            val bottomPercent = bottomSeekBar.progress / 100f
            
            // 임시로 설정 적용 (저장하지 않음)
            currentTopClipping = topPercent
            currentBottomClipping = bottomPercent
            
            // 페이지 다시 렌더링
            forceDirectRendering = true
            showPage(pageIndex)
            
            Log.d("PdfViewerActivity", "미리보기 적용: 위 ${(topPercent * 100).toInt()}%, 아래 ${(bottomPercent * 100).toInt()}%")
        }
        
        val setupPreview = { _: android.widget.SeekBar ->
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            previewRunnable = Runnable { applyPreview() }
            previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
            previewHandler?.postDelayed(previewRunnable!!, 200) // 200ms 딜레이
        }
        
        // 슬라이더에 실시간 미리보기 연결
        topSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                topLabel.text = "위쪽 클리핑: ${progress}%"
                if (fromUser) setupPreview(seekBar!!)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        bottomSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                bottomLabel.text = "아래쪽 클리핑: ${progress}%"
                if (fromUser) setupPreview(seekBar!!)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle("클리핑 설정")
            .setView(dialogView)
            .setPositiveButton("적용") { _, _ ->
                val topPercent = topSeekBar.progress / 100f
                val bottomPercent = bottomSeekBar.progress / 100f
                
                saveClippingSettings(topPercent, bottomPercent)
                Toast.makeText(this, "위 ${(topPercent * 100).toInt()}%, 아래 ${(bottomPercent * 100).toInt()}% 클리핑을 적용했습니다", Toast.LENGTH_SHORT).show()
                
                Log.d("PdfViewerActivity", "=== 클리핑 설정 적용 ===")
                Log.d("PdfViewerActivity", "위: ${(topPercent * 100).toInt()}%, 아래: ${(bottomPercent * 100).toInt()}%")
                
                registerSettingsCallback()
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setNegativeButton("취소") { _, _ ->
                // 원래 설정으로 복원
                currentTopClipping = originalTop
                currentBottomClipping = originalBottom
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setOnCancelListener {
                // 취소 시에도 원래 설정으로 복원
                currentTopClipping = originalTop
                currentBottomClipping = originalBottom
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .show()
    }
    
    /**
     * 사용자 정의 클리핑 설정 다이얼로그 (deprecated - showClippingDialog로 통합됨)
     */
    @Deprecated("Use showClippingDialog instead", ReplaceWith("showClippingDialog()"))
    private fun showCustomClippingDialog() {
        // 커스텀 레이아웃 생성
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }
        
        // 위쪽 클리핑 레이블
        val topLabel = android.widget.TextView(this).apply {
            text = "위쪽 클리핑: ${(currentTopClipping * 100).toInt()}%"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        dialogView.addView(topLabel)
        
        // 위쪽 클리핑 슬라이더 (0-30%)
        val topSeekBar = android.widget.SeekBar(this).apply {
            max = 15  // 0-15%
            progress = (currentTopClipping * 100).toInt()
            setPadding(0, 0, 0, 30)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    topLabel.text = "위쪽 클리핑: ${progress}%"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        dialogView.addView(topSeekBar)
        
        // 아래쪽 클리핑 레이블
        val bottomLabel = android.widget.TextView(this).apply {
            text = "아래쪽 클리핑: ${(currentBottomClipping * 100).toInt()}%"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        dialogView.addView(bottomLabel)
        
        // 아래쪽 클리핑 슬라이더 (0-30%)
        val bottomSeekBar = android.widget.SeekBar(this).apply {
            max = 15  // 0-15%
            progress = (currentBottomClipping * 100).toInt()
            setPadding(0, 0, 0, 20)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    bottomLabel.text = "아래쪽 클리핑: ${progress}%"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        dialogView.addView(bottomSeekBar)
        
        // 미리보기 텍스트
        val previewLabel = android.widget.TextView(this).apply {
            text = "실시간 미리보기가 적용됩니다"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
        }
        dialogView.addView(previewLabel)
        
        // 실시간 미리보기를 위한 변수
        var previewHandler: android.os.Handler? = null
        var previewRunnable: Runnable? = null
        
        val applyPreview = {
            val topPercent = topSeekBar.progress / 100f
            val bottomPercent = bottomSeekBar.progress / 100f
            
            // 임시로 설정 적용 (저장하지 않음)
            val oldTop = currentTopClipping
            val oldBottom = currentBottomClipping
            currentTopClipping = topPercent
            currentBottomClipping = bottomPercent
            
            // 페이지 다시 렌더링
            forceDirectRendering = true
            showPage(pageIndex)
            
            Log.d("PdfViewerActivity", "미리보기 적용: 위 ${(topPercent * 100).toInt()}%, 아래 ${(bottomPercent * 100).toInt()}%")
        }
        
        val setupPreview = { _: android.widget.SeekBar ->
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            previewRunnable = Runnable { applyPreview() }
            previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
            previewHandler?.postDelayed(previewRunnable!!, 200) // 200ms 딜레이
        }
        
        // 슬라이더에 실시간 미리보기 연결
        topSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                topLabel.text = "위쪽 클리핑: ${progress}%"
                if (fromUser) setupPreview(seekBar!!)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        bottomSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                bottomLabel.text = "아래쪽 클리핑: ${progress}%"
                if (fromUser) setupPreview(seekBar!!)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle("사용자 정의 클리핑 설정")
            .setView(dialogView)
            .setPositiveButton("적용") { _, _ ->
                val topPercent = topSeekBar.progress / 100f
                val bottomPercent = bottomSeekBar.progress / 100f
                
                saveClippingSettings(topPercent, bottomPercent)
                Toast.makeText(this, "위 ${(topPercent * 100).toInt()}%, 아래 ${(bottomPercent * 100).toInt()}% 클리핑을 적용했습니다", Toast.LENGTH_SHORT).show()
                
                Log.d("PdfViewerActivity", "=== 사용자 정의 클리핑 설정 적용 ===")
                Log.d("PdfViewerActivity", "위: ${(topPercent * 100).toInt()}%, 아래: ${(bottomPercent * 100).toInt()}%")
                
                registerSettingsCallback()
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setNegativeButton("취소") { _, _ ->
                // 원래 설정으로 복원
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setOnCancelListener {
                // 취소 시에도 원래 설정으로 복원
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .show()
    }
    
    /**
     * 현재 여백 설정에 해당하는 선택 항목 찾기
     */
    @Deprecated("No longer needed with slider UI")
    private fun getCurrentPaddingSelection(): Int {
        return when {
            currentCenterPadding == 0f -> 0    // 여백 없음
            currentCenterPadding == 0.05f -> 1   // 5%
            currentCenterPadding == 0.10f -> 2   // 10%
            else -> -1 // 사용자 정의
        }
    }
    
    /**
     * 가운데 여백 설정 다이얼로그
     */
    private fun showPaddingDialog() {
        // 커스텀 레이아웃 생성
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }
        
        // 가운데 여백 레이블
        val paddingLabel = android.widget.TextView(this).apply {
            text = "가운데 여백: ${(currentCenterPadding * 100).toInt()}%"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        dialogView.addView(paddingLabel)
        
        // 가운데 여백 슬라이더 (0-15%)
        val paddingSeekBar = android.widget.SeekBar(this).apply {
            max = 15  // 0-15%
            progress = (currentCenterPadding * 100).toInt()
            setPadding(0, 0, 0, 20)
        }
        dialogView.addView(paddingSeekBar)
        
        // 빠른 설정 버튼들
        val quickButtonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        
        val resetButton = android.widget.Button(this).apply {
            text = "여백 없음"
            setOnClickListener {
                paddingSeekBar.progress = 0
                setupPreview(paddingSeekBar)
            }
        }
        quickButtonsLayout.addView(resetButton)
        
        val preset5Button = android.widget.Button(this).apply {
            text = "5%"
            setOnClickListener {
                paddingSeekBar.progress = 5
                setupPreview(paddingSeekBar)
            }
        }
        quickButtonsLayout.addView(preset5Button)
        
        val preset10Button = android.widget.Button(this).apply {
            text = "10%"
            setOnClickListener {
                paddingSeekBar.progress = 10
                setupPreview(paddingSeekBar)
            }
        }
        quickButtonsLayout.addView(preset10Button)
        
        dialogView.addView(quickButtonsLayout)
        
        // 미리보기 텍스트
        val previewLabel = android.widget.TextView(this).apply {
            text = "실시간 미리보기가 적용됩니다"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        dialogView.addView(previewLabel)
        
        // 실시간 미리보기를 위한 변수
        var previewHandler: android.os.Handler? = null
        var previewRunnable: Runnable? = null
        
        // 원래 설정 저장
        val originalPadding = currentCenterPadding
        
        val applyPreview = {
            val paddingPercent = paddingSeekBar.progress / 100f
            
            // 임시로 설정 적용 (저장하지 않음)
            currentCenterPadding = paddingPercent
            
            // 페이지 다시 렌더링
            forceDirectRendering = true
            showPage(pageIndex)
            
            Log.d("PdfViewerActivity", "미리보기 적용: 가운데 여백 ${(paddingPercent * 100).toInt()}%")
        }
        
        val setupPreview = { _: android.widget.SeekBar ->
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            previewRunnable = Runnable { applyPreview() }
            previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
            previewHandler?.postDelayed(previewRunnable!!, 200) // 200ms 딜레이
        }
        
        // 슬라이더에 실시간 미리보기 연결
        paddingSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                paddingLabel.text = "가운데 여백: ${progress}%"
                if (fromUser) setupPreview(seekBar!!)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle("가운데 여백 설정")
            .setView(dialogView)
            .setPositiveButton("적용") { _, _ ->
                val paddingPercent = paddingSeekBar.progress / 100f
                
                savePaddingSettings(paddingPercent)
                Toast.makeText(this, "가운데 여백 ${(paddingPercent * 100).toInt()}%를 적용했습니다", Toast.LENGTH_SHORT).show()
                
                Log.d("PdfViewerActivity", "=== 가운데 여백 설정 적용 ===")
                Log.d("PdfViewerActivity", "여백: ${(paddingPercent * 100).toInt()}%")
                
                registerSettingsCallback()
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setNegativeButton("취소") { _, _ ->
                // 원래 설정으로 복원
                currentCenterPadding = originalPadding
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .setOnCancelListener {
                // 취소 시에도 원래 설정으로 복원
                currentCenterPadding = originalPadding
                forceDirectRendering = true
                showPage(pageIndex)
            }
            .show()
    }
    
    /**
     * 클리핑과 여백 설정을 비트맵에 적용
     */
    private fun applyDisplaySettings(originalBitmap: Bitmap, isTwoPageMode: Boolean): Bitmap {
        Log.d("PdfViewerActivity", "=== applyDisplaySettings 호출됨 ===")
        Log.d("PdfViewerActivity", "현재 설정: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
        Log.d("PdfViewerActivity", "isTwoPageMode: $isTwoPageMode, 원본 크기: ${originalBitmap.width}x${originalBitmap.height}")
        
        val hasClipping = currentTopClipping > 0f || currentBottomClipping > 0f
        val hasPadding = currentCenterPadding > 0 && isTwoPageMode
        
        Log.d("PdfViewerActivity", "hasClipping: $hasClipping, hasPadding: $hasPadding")
        
        if (!hasClipping && !hasPadding) {
            Log.d("PdfViewerActivity", "설정이 없어서 원본 반환")
            return originalBitmap
        }
        
        Log.d("PdfViewerActivity", "표시 설정 적용 중: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
        
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        
        // 클리핑 계산
        val topClipPixels = (originalHeight * currentTopClipping).toInt()
        val bottomClipPixels = (originalHeight * currentBottomClipping).toInt()
        val clippedHeight = originalHeight - topClipPixels - bottomClipPixels
        
        // 여백 계산 (두 페이지 모드에서만)
        val paddingWidth = if (isTwoPageMode && currentCenterPadding > 0) {
            (originalWidth * currentCenterPadding).toInt()
        } else {
            0
        }
        val finalWidth = originalWidth + paddingWidth
        val finalHeight = clippedHeight
        
        if (finalWidth <= 0 || finalHeight <= 0) {
            Log.w("PdfViewerActivity", "클리핑 결과가 유효하지 않음: ${finalWidth}x${finalHeight}")
            return originalBitmap
        }
        
        // 새 비트맵 생성
        val resultBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        if (isTwoPageMode) {
            // 두 페이지 모드에서는 여백이 이미 적용되었으므로 클리핑만 적용
            // 여백을 고려한 최종 폭 계산 (여백이 이미 적용된 상태)
            val actualPaddingWidth = if (currentCenterPadding > 0) {
                (originalWidth * currentCenterPadding).toInt()
            } else {
                0
            }
            val leftPageWidth = (originalWidth - actualPaddingWidth) / 2
            val rightPageStartX = leftPageWidth + actualPaddingWidth
            
            Log.d("PdfViewerActivity", "두 페이지 클리핑: 원본 폭=${originalWidth}, 여백=${actualPaddingWidth}, 왼쪽 페이지 폭=${leftPageWidth}")
            
            // 왼쪽 페이지 (클리핑된 부분)
            val leftSrcRect = android.graphics.Rect(0, topClipPixels, leftPageWidth, originalHeight - bottomClipPixels)
            val leftDstRect = android.graphics.Rect(0, 0, leftPageWidth, clippedHeight)
            canvas.drawBitmap(originalBitmap, leftSrcRect, leftDstRect, null)
            
            // 가운데 여백 부분은 흰색으로 유지 (이미 drawColor로 처리됨)
            
            // 오른쪽 페이지 (클리핑된 부분)
            val rightSrcRect = android.graphics.Rect(rightPageStartX, topClipPixels, originalWidth, originalHeight - bottomClipPixels)
            val rightDstRect = android.graphics.Rect(rightPageStartX, 0, finalWidth, clippedHeight)
            canvas.drawBitmap(originalBitmap, rightSrcRect, rightDstRect, null)
            
        } else {
            // 단일 페이지 모드에서 클리핑만 적용
            val srcRect = android.graphics.Rect(0, topClipPixels, originalWidth, originalHeight - bottomClipPixels)
            val dstRect = android.graphics.Rect(0, 0, originalWidth, clippedHeight)
            canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
        }
        
        // 원본 비트맵을 재사용하지 않을 때만 해제
        if (resultBitmap != originalBitmap) {
            originalBitmap.recycle()
        }
        
        Log.d("PdfViewerActivity", "표시 설정 적용 완료: ${originalWidth}x${originalHeight} → ${finalWidth}x${finalHeight}")
        return resultBitmap
    }
    
    /**
     * 데이터베이스에서 표시 설정 로드 (동기)
     */
    private suspend fun loadDisplaySettingsSync() = withContext(Dispatchers.IO) {
        Log.d("PdfViewerActivity", "=== loadDisplaySettingsSync 시작 ===")
        Log.d("PdfViewerActivity", "currentPdfFileId: $currentPdfFileId")
        
        currentPdfFileId?.let { fileId ->
            try {
                val prefs = musicRepository.getUserPreference(fileId)
                Log.d("PdfViewerActivity", "DB에서 조회된 설정: $prefs")
                
                if (prefs != null) {
                    withContext(Dispatchers.Main) {
                        currentTopClipping = prefs.topClippingPercent
                        currentBottomClipping = prefs.bottomClippingPercent
                        currentCenterPadding = prefs.centerPadding
                        Log.d("PdfViewerActivity", "=== 데이터베이스에서 설정 로드 완료 ===")
                        Log.d("PdfViewerActivity", "로드된 설정: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                    }
                } else {
                    // 기본값 사용
                    withContext(Dispatchers.Main) {
                        currentTopClipping = 0f
                        currentBottomClipping = 0f
                        currentCenterPadding = 0f
                    }
                    Log.d("PdfViewerActivity", "표시 설정 없음, 기본값 사용")
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "표시 설정 로드 실패", e)
                // 기본값으로 폴백
                withContext(Dispatchers.Main) {
                    currentTopClipping = 0f
                    currentBottomClipping = 0f
                    currentCenterPadding = 0f
                }
            }
        } ?: run {
            Log.w("PdfViewerActivity", "currentPdfFileId가 null이어서 설정을 로드할 수 없습니다")
        }
    }
    
    /**
     * 데이터베이스에서 표시 설정 로드 (비동기)
     */
    private fun loadDisplaySettings() {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = musicRepository.getUserPreference(fileId)
                    if (prefs != null) {
                        currentTopClipping = prefs.topClippingPercent
                        currentBottomClipping = prefs.bottomClippingPercent
                        currentCenterPadding = prefs.centerPadding
                        Log.d("PdfViewerActivity", "표시 설정 로드 완료: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                    } else {
                        // 기본값 사용
                        currentTopClipping = 0f
                        currentBottomClipping = 0f
                        currentCenterPadding = 0f
                        Log.d("PdfViewerActivity", "표시 설정 없음, 기본값 사용")
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "표시 설정 로드 실패", e)
                    // 기본값으로 폴백
                    currentTopClipping = 0f
                    currentBottomClipping = 0f
                    currentCenterPadding = 0f
                }
            }
        }
    }
    
    /**
     * 클리핑 설정을 데이터베이스에 저장
     */
    private fun saveClippingSettings(topPercent: Float, bottomPercent: Float) {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val currentPrefs = musicRepository.getUserPreference(fileId)
                    val updatedPrefs = if (currentPrefs != null) {
                        currentPrefs.copy(
                            topClippingPercent = topPercent,
                            bottomClippingPercent = bottomPercent,
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        // 기본 설정으로 새로 생성
                        UserPreference(
                            pdfFileId = fileId,
                            displayMode = DisplayMode.AUTO,
                            topClippingPercent = topPercent,
                            bottomClippingPercent = bottomPercent
                        )
                    }
                    musicRepository.insertUserPreference(updatedPrefs)
                    
                    // Update current settings
                    withContext(Dispatchers.Main) {
                        Log.d("PdfViewerActivity", "=== 클리핑 설정 업데이트 ===")
                        Log.d("PdfViewerActivity", "이전: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%")
                        currentTopClipping = topPercent
                        currentBottomClipping = bottomPercent
                        Log.d("PdfViewerActivity", "이후: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%")
                    }
                    
                    Log.d("PdfViewerActivity", "클리핑 설정 저장 완료: 위 ${topPercent * 100}%, 아래 ${bottomPercent * 100}%")
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "클리핑 설정 저장 실패", e)
                }
            }
        }
    }
    
    /**
     * 여백 설정을 데이터베이스에 저장
     */
    private fun savePaddingSettings(padding: Float) {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val currentPrefs = musicRepository.getUserPreference(fileId)
                    val updatedPrefs = if (currentPrefs != null) {
                        currentPrefs.copy(
                            centerPadding = padding,
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        // 기본 설정으로 새로 생성
                        UserPreference(
                            pdfFileId = fileId,
                            displayMode = DisplayMode.AUTO,
                            centerPadding = padding
                        )
                    }
                    musicRepository.insertUserPreference(updatedPrefs)
                    
                    // Update current settings
                    withContext(Dispatchers.Main) {
                        Log.d("PdfViewerActivity", "=== 여백 설정 업데이트 ===")
                        Log.d("PdfViewerActivity", "이전: ${(currentCenterPadding * 100).toInt()}%")
                        currentCenterPadding = padding
                        Log.d("PdfViewerActivity", "이후: ${(currentCenterPadding * 100).toInt()}%")
                    }
                    
                    Log.d("PdfViewerActivity", "여백 설정 저장 완료: ${(padding * 100).toInt()}%")
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "여백 설정 저장 실패", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Clear collaboration callbacks when PdfViewerActivity goes to background
        // This allows MainActivity to properly register its callbacks when it resumes
        Log.d("PdfViewerActivity", "onPause - 협업 콜백 정리")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up long press handler
        longPressHandler.removeCallbacks(longPressRunnable)
        
        // Clean up collaboration resources
        // Note: 전역 매니저가 관리하므로 여기서 서버를 중지하지 않음
        // Note: 전역 매니저가 관리하므로 여기서 클라이언트를 끊지 않음
        
        // Clean up page cache
        try {
            pageCache?.destroy()
            Log.d("PdfViewerActivity", "PageCache 정리 완료")
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error destroying pageCache in onDestroy: ${e.message}")
        }
        
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing currentPage in onDestroy: ${e.message}")
        }
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing pdfRenderer in onDestroy: ${e.message}")
        }
    }
}