package com.mrgq.pdfviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mrgq.pdfviewer.server.WebServerManager

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: SharedPreferences
    
    // Web server manager
    private val webServerManager = WebServerManager()
    private var isWebServerRunning = false
    
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        setupUI()
        updateSettingsInfo()
        setupPdfFileInfo()
        checkWebServerStatus()
        updateWebServerUI()
    }
    
    private fun setupUI() {
        // Load current port setting
        val savedPort = preferences.getInt("web_server_port", 8080)
        binding.portEditText.setText(savedPort.toString())
        
        binding.savePortBtn.setOnClickListener {
            savePortSetting()
        }
        
        binding.resetAllSettingsBtn.setOnClickListener {
            showResetAllSettingsDialog()
        }
        
        binding.resetFileSettingsBtn.setOnClickListener {
            showResetFileSettingsDialog()
        }
        
        binding.backButton.setOnClickListener {
            finish()
        }
        
        binding.deleteAllPdfBtn.setOnClickListener {
            showDeleteAllPdfDialog()
        }
        
        binding.webServerToggleBtn.setOnClickListener {
            toggleWebServer()
        }
        
        // Focus management - 키보드 숨기기
        hideKeyboard()
        binding.savePortBtn.requestFocus() // 저장 버튼에 초기 포커스
        
        // EditText 설정 - Enter 키로 키보드 표시
        binding.portEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 포커스 받으면 키보드 표시
                showKeyboard(binding.portEditText)
            } else {
                // 포커스 잃으면 키보드 숨김
                hideKeyboard()
            }
        }
        
        // Enter 키로도 키보드 표시
        binding.portEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                showKeyboard(binding.portEditText)
                true
            } else {
                false
            }
        }
    }
    
    private fun updateSettingsInfo() {
        val allPrefs = preferences.all
        val fileSettingsCount = allPrefs.keys.count { it.startsWith("file_mode_") }
        
        binding.fileSettingsInfo.text = "저장된 파일별 설정: ${fileSettingsCount}개"
        
        if (fileSettingsCount > 0) {
            val settingsList = StringBuilder()
            allPrefs.entries.filter { it.key.startsWith("file_mode_") }.forEach { entry ->
                val fileKey = entry.key.removePrefix("file_mode_")
                val mode = when (entry.value as String) {
                    "two" -> "두 페이지"
                    "single" -> "한 페이지"
                    else -> "알 수 없음"
                }
                settingsList.append("• $fileKey: $mode\n")
            }
            binding.settingsDetails.text = settingsList.toString().trimEnd()
        } else {
            binding.settingsDetails.text = "저장된 파일별 설정이 없습니다"
        }
    }
    
    private fun showResetAllSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("전체 설정 초기화")
            .setMessage("모든 파일의 페이지 모드 설정을 초기화하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("초기화") { _, _ ->
                clearAllFileSettings()
                Toast.makeText(this, "모든 설정이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                updateSettingsInfo()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun showResetFileSettingsDialog() {
        val allPrefs = preferences.all
        val fileSettings = allPrefs.entries.filter { it.key.startsWith("file_mode_") }
        
        if (fileSettings.isEmpty()) {
            Toast.makeText(this, "삭제할 파일 설정이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fileKeys = fileSettings.map { it.key.removePrefix("file_mode_") }.toTypedArray()
        val checkedItems = BooleanArray(fileKeys.size) { false }
        
        AlertDialog.Builder(this)
            .setTitle("파일별 설정 선택 삭제")
            .setMultiChoiceItems(fileKeys, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("선택 삭제") { _, _ ->
                var deletedCount = 0
                val editor = preferences.edit()
                
                fileKeys.forEachIndexed { index, fileKey ->
                    if (checkedItems[index]) {
                        editor.remove("file_mode_$fileKey")
                        deletedCount++
                    }
                }
                
                editor.apply()
                Toast.makeText(this, "${deletedCount}개 파일 설정이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                updateSettingsInfo()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun clearAllFileSettings() {
        val editor = preferences.edit()
        val allPrefs = preferences.all
        
        for ((key, _) in allPrefs) {
            if (key.startsWith("file_mode_")) {
                editor.remove(key)
            }
        }
        
        editor.apply()
    }
    
    private fun savePortSetting() {
        val portText = binding.portEditText.text.toString().trim()
        
        if (portText.isEmpty()) {
            Toast.makeText(this, "포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        
        val port = try {
            portText.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "유효한 포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, "포트 번호는 1024-65535 범위여야 합니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        preferences.edit().putInt("web_server_port", port).apply()
        Toast.makeText(this, "포트 설정이 저장되었습니다: $port", Toast.LENGTH_SHORT).show()
    }
    
    private fun setupPdfFileInfo() {
        val pdfFiles = getAllPdfFiles()
        val pdfCount = pdfFiles.size
        
        binding.pdfFilesInfo.text = "저장된 PDF 파일: ${pdfCount}개"
    }
    
    private fun getAllPdfFiles(): List<File> {
        val pdfFiles = mutableListOf<File>()
        
        // Check app's external files directory
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            appPdfDir.listFiles { file ->
                file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }?.let { files ->
                pdfFiles.addAll(files)
            }
        }
        
        // Check Downloads directory if accessible
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists() && downloadDir.canRead()) {
                downloadDir.listFiles { file ->
                    file.isFile && file.extension.equals("pdf", ignoreCase = true)
                }?.let { files ->
                    pdfFiles.addAll(files)
                }
            }
        } catch (e: Exception) {
            // Ignore if Downloads directory is not accessible
        }
        
        return pdfFiles
    }
    
    private fun showDeleteAllPdfDialog() {
        val pdfFiles = getAllPdfFiles()
        
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "삭제할 PDF 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("모든 PDF 파일 삭제")
            .setMessage("모든 PDF 파일(${pdfFiles.size}개)을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteAllPdfFiles(pdfFiles)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
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
                if (failedCount == 0) {
                    Toast.makeText(this@SettingsActivity, "${deletedCount}개 파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "${deletedCount}개 파일 삭제 완료, ${failedCount}개 파일 삭제 실패", Toast.LENGTH_LONG).show()
                }
                
                // Update file count display
                setupPdfFileInfo()
            }
        }
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
    
    private fun showKeyboard(editText: android.widget.EditText) {
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Update settings info when returning
        Log.d("SettingsActivity", "onResume - 설정 정보 업데이트")
        updateSettingsInfo()
        setupPdfFileInfo()
        checkWebServerStatus()
        updateWebServerUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 리소스 정리
        Log.d("SettingsActivity", "onDestroy - 리소스 정리")
    }
    
    // 웹서버 관리 함수들
    private fun checkWebServerStatus() {
        // WebServerManager의 실제 상태를 확인
        isWebServerRunning = webServerManager.isServerRunning()
        Log.d("SettingsActivity", "웹서버 상태 확인: $isWebServerRunning")
    }
    
    private fun updateWebServerUI() {
        if (isWebServerRunning) {
            binding.webServerToggleBtn.text = "⏹️ 웹서버 중지"
            binding.webServerToggleBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_error)
            
            val currentPort = preferences.getInt("web_server_port", 8080)
            val serverAddress = webServerManager.getServerAddress()
            binding.webServerStatusText.text = "웹서버: 실행 중 (http://$serverAddress:$currentPort)"
            binding.webServerStatusText.setTextColor(ContextCompat.getColor(this, R.color.tv_secondary))
            
            // 상태 아이콘 업데이트
            binding.statusIcon.text = "🟢"
        } else {
            binding.webServerToggleBtn.text = "▶️ 웹서버 시작"
            binding.webServerToggleBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_primary)
            
            binding.webServerStatusText.text = "웹서버: 중지됨"
            binding.webServerStatusText.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary))
            
            // 상태 아이콘 업데이트
            binding.statusIcon.text = "🔴"
        }
    }
    
    private fun toggleWebServer() {
        if (isWebServerRunning) {
            // 웹서버 중지
            stopWebServer()
        } else {
            // 웹서버 시작
            startWebServer()
        }
    }
    
    private fun startWebServer() {
        binding.webServerToggleBtn.isEnabled = false
        binding.webServerToggleBtn.text = "⏳ 시작 중..."
        
        webServerManager.startServer(this) { success ->
            runOnUiThread {
                binding.webServerToggleBtn.isEnabled = true
                
                if (success) {
                    isWebServerRunning = true
                    updateWebServerUI()
                    
                    val currentPort = preferences.getInt("web_server_port", 8080)
                    val serverAddress = webServerManager.getServerAddress()
                    Toast.makeText(
                        this,
                        "웹서버가 시작되었습니다\nhttp://$serverAddress:$currentPort",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "웹서버 시작 실패", Toast.LENGTH_SHORT).show()
                    updateWebServerUI()
                }
            }
        }
    }
    
    private fun stopWebServer() {
        webServerManager.stopServer()
        isWebServerRunning = false
        updateWebServerUI()
        Toast.makeText(this, "웹서버가 중지되었습니다", Toast.LENGTH_SHORT).show()
    }
    
    
}