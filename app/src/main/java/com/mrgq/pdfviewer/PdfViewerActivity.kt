package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mrgq.pdfviewer.databinding.ActivityPdfViewerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        pdfFilePath = intent.getStringExtra("pdf_path") ?: ""
        pdfFileName = intent.getStringExtra("pdf_name") ?: "PDF"
        currentFileIndex = intent.getIntExtra("current_index", 0)
        filePathList = intent.getStringArrayListExtra("file_path_list") ?: emptyList()
        fileNameList = intent.getStringArrayListExtra("file_name_list") ?: emptyList()
        
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
                        showPage(0)
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
    
    private fun showPage(index: Int) {
        if (index < 0 || index >= pageCount) return
        
        binding.loadingProgress.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                currentPage?.close()
                
                currentPage = pdfRenderer?.openPage(index)
                val page = currentPage ?: return@launch
                
                val bitmap = Bitmap.createBitmap(
                    page.width,
                    page.height,
                    Bitmap.Config.ARGB_8888
                )
                
                // Fill with white background
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
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
    
    private fun updatePageInfo() {
        val fileInfo = if (fileNameList.isNotEmpty()) {
            "[${currentFileIndex + 1}/${filePathList.size}] ${fileNameList[currentFileIndex]} - "
        } else {
            "$pdfFileName - "
        }
        binding.pageInfo.text = "$fileInfo${getString(R.string.page_info, pageIndex + 1, pageCount)}"
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
        currentPage?.close()
        currentPage = null
        Log.d("PdfViewerActivity", "Current page closed")
        
        pdfRenderer?.close()
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
                        val targetPage = if (goToLastPage) pageCount - 1 else 0
                        showPage(targetPage)
                        Toast.makeText(this@PdfViewerActivity, "파일 열림: $fileName", Toast.LENGTH_SHORT).show()
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
                        // 파일 끝 안내에서 왼쪽 키 -> 파일 목록으로
                        hideNavigationGuide()
                        finish()
                        return true
                    } else if (navigationGuideType == "start") {
                        // 파일 시작 안내에서 왼쪽 키 -> 이전 파일로 이동
                        handleStartOfFile()
                        return true
                    }
                } else if (pageIndex > 0) {
                    showPage(pageIndex - 1)
                    return true
                } else {
                    // 첫 페이지에서 안내 표시 또는 이전 파일로 이동
                    handleStartOfFile()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isNavigationGuideVisible) {
                    if (navigationGuideType == "end") {
                        // 파일 끝 안내에서 오른쪽 키 -> 다음 파일로 이동
                        handleEndOfFile()
                        return true
                    } else if (navigationGuideType == "start") {
                        // 파일 시작 안내에서 오른쪽 키 -> 파일 목록으로
                        hideNavigationGuide()
                        finish()
                        return true
                    }
                } else if (pageIndex < pageCount - 1) {
                    showPage(pageIndex + 1)
                    return true
                } else {
                    // 마지막 페이지에서 안내 표시 또는 다음 파일로 이동
                    handleEndOfFile()
                    return true
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
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun handleEndOfFile() {
        if (isNavigationGuideVisible && navigationGuideType == "end") {
            // 안내가 이미 표시된 상태에서 다시 오른쪽 키 -> 다음 파일로 이동
            hideNavigationGuide()
            if (currentFileIndex < filePathList.size - 1) {
                loadNextFile()
            }
        } else {
            // 첫 번째 오른쪽 키 -> 안내 표시
            showEndOfFileGuide()
        }
    }
    
    private fun handleStartOfFile() {
        if (isNavigationGuideVisible && navigationGuideType == "start") {
            // 안내가 이미 표시된 상태에서 다시 왼쪽 키 -> 이전 파일로 이동
            hideNavigationGuide()
            if (currentFileIndex > 0) {
                loadPreviousFile()
            }
        } else {
            // 첫 번째 왼쪽 키 -> 안내 표시
            showStartOfFileGuide()
        }
    }
    
    private fun showEndOfFileGuide() {
        val hasNextFile = currentFileIndex < filePathList.size - 1
        
        if (hasNextFile) {
            val nextFileName = fileNameList[currentFileIndex + 1]
            binding.navigationTitle.text = "파일 끝"
            binding.navigationMessage.text = "마지막 페이지입니다.\n다음 파일: $nextFileName"
            binding.navigationInstructions.text = "→ 다음 파일로 이동\n← 파일 목록으로 돌아가기"
        } else {
            binding.navigationTitle.text = "마지막 파일"
            binding.navigationMessage.text = "모든 파일의 마지막 페이지입니다."
            binding.navigationInstructions.text = "← 파일 목록으로 돌아가기"
        }
        
        showNavigationGuide("end")
    }
    
    private fun showStartOfFileGuide() {
        val hasPreviousFile = currentFileIndex > 0
        
        if (hasPreviousFile) {
            val previousFileName = fileNameList[currentFileIndex - 1]
            binding.navigationTitle.text = "파일 처음"
            binding.navigationMessage.text = "첫 페이지입니다.\n이전 파일: $previousFileName"
            binding.navigationInstructions.text = "← 이전 파일로 이동\n→ 파일 목록으로 돌아가기"
        } else {
            binding.navigationTitle.text = "첫 번째 파일"
            binding.navigationMessage.text = "첫 번째 파일의 첫 페이지입니다."
            binding.navigationInstructions.text = "→ 파일 목록으로 돌아가기"
        }
        
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
            }.duration = 300
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
    }
}