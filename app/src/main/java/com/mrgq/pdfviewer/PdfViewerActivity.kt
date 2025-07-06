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
import java.io.File

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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize preferences
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        currentFileIndex = intent.getIntExtra("current_index", 0)
        filePathList = intent.getStringArrayListExtra("file_path_list") ?: emptyList()
        fileNameList = intent.getStringArrayListExtra("file_name_list") ?: emptyList()
        
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
                        // Check if we should use two-page mode, then show first page
                        checkAndSetTwoPageMode {
                            showPage(0)
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
                // Check if this file already has a saved setting
                val fileKey = getFileKey(pdfFilePath)
                val savedFilePreference = preferences.getString("file_mode_$fileKey", null)
                
                if (savedFilePreference != null) {
                    // File-specific setting exists
                    withContext(Dispatchers.Main) {
                        isTwoPageMode = savedFilePreference == "two"
                        Log.d("PdfViewerActivity", "Using saved file preference: $savedFilePreference for $pdfFileName")
                        onComplete()
                    }
                    return@launch
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
                            showTwoPageModeDialog(fileKey, onComplete)
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
    
    private fun getFileKey(filePath: String): String {
        // Create a unique key for the file based on path and size
        return try {
            val file = File(filePath)
            "${file.name}_${file.length()}"
        } catch (e: Exception) {
            filePath.hashCode().toString()
        }
    }
    
    private fun saveFilePreference(fileKey: String, mode: String) {
        preferences.edit().putString("file_mode_$fileKey", mode).apply()
        Log.d("PdfViewerActivity", "Saved file preference: $mode for key: $fileKey")
    }
    
    private fun showTwoPageModeDialog(fileKey: String, onComplete: () -> Unit) {
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
                    saveFilePreference(fileKey, "two")
                    Log.d("PdfViewerActivity", "User selected two page mode (saved) for $pdfFileName")
                } else {
                    Log.d("PdfViewerActivity", "User selected two page mode (temp) for $pdfFileName")
                }
                onComplete()
            }
            .setNegativeButton("한 페이지씩 보기") { _, _ ->
                isTwoPageMode = false
                if (rememberCheckbox.isChecked) {
                    saveFilePreference(fileKey, "single")
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
        
        binding.loadingProgress.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                try {
                    currentPage?.close()
                } catch (e: Exception) {
                    Log.w("PdfViewerActivity", "Current page already closed or error closing in showPage: ${e.message}")
                }
                
                val bitmap = if (isTwoPageMode && index + 1 < pageCount) {
                    Log.d("PdfViewerActivity", "Rendering two pages: $index and ${index + 1}")
                    // Render two pages side by side
                    renderTwoPages(index)
                } else {
                    Log.d("PdfViewerActivity", "Rendering single page: $index")
                    // Render single page
                    renderSinglePage(index)
                }
                
                withContext(Dispatchers.Main) {
                    binding.pdfView.setImageBitmap(bitmap)
                    pageIndex = index
                    updatePageInfo()
                    binding.loadingProgress.visibility = View.GONE
                    
                    // Show page info briefly
                    binding.pageInfo.animate().alpha(1f).duration = 200
                    binding.pageInfo.postDelayed({
                        binding.pageInfo.animate().alpha(0.3f).duration = 500
                    }, 2000)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(this@PdfViewerActivity, getString(R.string.error_loading_pdf), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private suspend fun renderSinglePage(index: Int): Bitmap {
        currentPage = pdfRenderer?.openPage(index)
        val page = currentPage ?: throw Exception("Failed to open page $index")
        
        // Calculate high-resolution dimensions
        val scale = calculateOptimalScale(page.width, page.height)
        val renderWidth = (page.width * scale).toInt()
        val renderHeight = (page.height * scale).toInt()
        
        Log.d("PdfViewerActivity", "Original: ${page.width}x${page.height}, Render: ${renderWidth}x${renderHeight}, Scale: $scale")
        
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
        
        // Render with scaling
        val rect = android.graphics.Rect(0, 0, renderWidth, renderHeight)
        page.render(bitmap, rect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        return bitmap
    }
    
    private suspend fun renderTwoPages(leftPageIndex: Int): Bitmap {
        Log.d("PdfViewerActivity", "Starting renderTwoPages for pages $leftPageIndex and ${leftPageIndex + 1}")
        
        // Open pages sequentially to avoid conflicts
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
            // Calculate high-resolution dimensions for left page
            val leftScale = calculateOptimalScale(leftPage.width, leftPage.height)
            val leftRenderWidth = (leftPage.width * leftScale).toInt()
            val leftRenderHeight = (leftPage.height * leftScale).toInt()
            
            // Create bitmap for left page
            val leftBitmap = Bitmap.createBitmap(
                leftRenderWidth,
                leftRenderHeight,
                Bitmap.Config.ARGB_8888
            )
            
            // Render left page
            val leftCanvas = Canvas(leftBitmap)
            leftCanvas.drawColor(android.graphics.Color.WHITE)
            val leftMatrix = android.graphics.Matrix()
            leftMatrix.setScale(leftScale, leftScale)
            val leftRect = android.graphics.Rect(0, 0, leftRenderWidth, leftRenderHeight)
            leftPage.render(leftBitmap, leftRect, leftMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Log.d("PdfViewerActivity", "Left page rendered successfully at ${leftRenderWidth}x${leftRenderHeight}")
            
            // Close left page before opening right page
            leftPage.close()
            
            // Now open right page
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
                // Calculate high-resolution dimensions for right page
                val rightScale = calculateOptimalScale(rightPage.width, rightPage.height)
                val rightRenderWidth = (rightPage.width * rightScale).toInt()
                val rightRenderHeight = (rightPage.height * rightScale).toInt()
                
                // Create bitmap for right page
                val rightBitmap = Bitmap.createBitmap(
                    rightRenderWidth,
                    rightRenderHeight,
                    Bitmap.Config.ARGB_8888
                )
                
                // Render right page
                val rightCanvas = Canvas(rightBitmap)
                rightCanvas.drawColor(android.graphics.Color.WHITE)
                val rightMatrix = android.graphics.Matrix()
                rightMatrix.setScale(rightScale, rightScale)
                val rightRect = android.graphics.Rect(0, 0, rightRenderWidth, rightRenderHeight)
                rightPage.render(rightBitmap, rightRect, rightMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                Log.d("PdfViewerActivity", "Right page rendered successfully at ${rightRenderWidth}x${rightRenderHeight}")
                
                // Create combined bitmap
                val combinedWidth = leftBitmap.width + rightBitmap.width
                val combinedHeight = maxOf(leftBitmap.height, rightBitmap.height)
                
                val combinedBitmap = Bitmap.createBitmap(
                    combinedWidth,
                    combinedHeight,
                    Bitmap.Config.ARGB_8888
                )
                
                val combinedCanvas = Canvas(combinedBitmap)
                combinedCanvas.drawColor(android.graphics.Color.WHITE)
                
                // Draw left page
                combinedCanvas.drawBitmap(leftBitmap, 0f, 0f, null)
                
                // Draw right page
                combinedCanvas.drawBitmap(rightBitmap, leftBitmap.width.toFloat(), 0f, null)
                
                Log.d("PdfViewerActivity", "Combined bitmap created successfully")
                
                // Clean up individual bitmaps
                leftBitmap.recycle()
                rightBitmap.recycle()
                
                return combinedBitmap
                
            } finally {
                rightPage.close()
            }
            
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error in renderTwoPages", e)
            leftPage.close()
            return renderSinglePage(leftPageIndex)
        }
    }
    
    private fun calculateOptimalScale(pageWidth: Int, pageHeight: Int): Float {
        // 화면 크기에 맞는 최적 스케일 계산
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val pageRatio = pageWidth.toFloat() / pageHeight.toFloat()
        
        val scale = if (pageRatio > screenRatio) {
            // 페이지가 화면보다 가로가 긴 경우 - 가로 기준으로 맞춤
            screenWidth.toFloat() / pageWidth.toFloat()
        } else {
            // 페이지가 화면보다 세로가 긴 경우 - 세로 기준으로 맞춤  
            screenHeight.toFloat() / pageHeight.toFloat()
        }
        
        // 최소 2배, 최대 4배 스케일링 (고해상도 보장)
        val finalScale = (scale * 2.0f).coerceIn(2.0f, 4.0f)
        
        Log.d("PdfViewerActivity", "Screen: ${screenWidth}x${screenHeight}, Page: ${pageWidth}x${pageHeight}")
        Log.d("PdfViewerActivity", "Calculated scale: $scale, Final scale: $finalScale")
        
        return finalScale
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
        
        binding.pageInfo.text = "$fileInfo$pageInfo"
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
                        // Check two-page mode for this new file, then show the page
                        checkAndSetTwoPageMode {
                            val targetPage = if (goToLastPage) pageCount - 1 else 0
                            showPage(targetPage)
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
                    if (navigationGuideType == "end") {
                        // 마지막 페이지 안내에서 왼쪽 키 -> 파일 목록으로
                        hideNavigationGuide()
                        finish()
                        return true
                    } else if (navigationGuideType == "start") {
                        // 첫 페이지 안내에서 왼쪽 키
                        if (currentFileIndex > 0) {
                            // 이전 파일로 이동
                            hideNavigationGuide()
                            loadPreviousFile()
                        }
                        return true
                    }
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
                    if (navigationGuideType == "end") {
                        // 마지막 페이지 안내에서 오른쪽 키
                        if (currentFileIndex < filePathList.size - 1) {
                            // 다음 파일로 이동
                            hideNavigationGuide()
                            loadNextFile()
                        }
                        return true
                    } else if (navigationGuideType == "start") {
                        // 첫 페이지 안내에서 오른쪽 키 -> 파일 목록으로
                        hideNavigationGuide()
                        finish()
                        return true
                    }
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
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
    
    // handleEndOfFile()과 handleStartOfFile() 메서드 삭제 - 더 이상 필요하지 않음
    
    private fun showEndOfFileGuide() {
        val hasNextFile = currentFileIndex < filePathList.size - 1
        val hasPreviousFile = currentFileIndex > 0
        
        // 왼쪽 네비게이션 설정 (목록으로 돌아가기)
        binding.leftNavigation.visibility = View.VISIBLE
        binding.leftNavText.text = "파일 목록"
        binding.leftNavSubText.text = "목록으로 돌아가기"
        
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
        val hasNextFile = currentFileIndex < filePathList.size - 1
        
        // 왼쪽 네비게이션 설정 (이전 파일 또는 없음)
        if (hasPreviousFile) {
            binding.leftNavigation.visibility = View.VISIBLE
            binding.leftNavText.text = "이전 파일"
            binding.leftNavSubText.text = fileNameList[currentFileIndex - 1]
        } else {
            binding.leftNavigation.visibility = View.GONE
        }
        
        // 오른쪽 네비게이션 설정 (목록으로 돌아가기)
        binding.rightNavigation.visibility = View.VISIBLE
        binding.rightNavText.text = "파일 목록"
        binding.rightNavSubText.text = "목록으로 돌아가기"
        
        showNavigationGuide("start")
    }
    
    private fun showNavigationGuide(type: String) {
        isNavigationGuideVisible = true
        navigationGuideType = type
        binding.navigationGuide.visibility = View.VISIBLE
        binding.navigationGuide.animate().alpha(1f).duration = 300
        
        // 5초 후 자동으로 숨기기
        binding.navigationGuide.postDelayed({
            hideNavigationGuide()
        }, 5000)
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
    
    override fun onDestroy() {
        super.onDestroy()
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