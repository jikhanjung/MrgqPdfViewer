package com.mrgq.pdfviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.databinding.ActivitySettingsNewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mrgq.pdfviewer.server.WebServerManager
import com.mrgq.pdfviewer.repository.MusicRepository
import com.mrgq.pdfviewer.database.entity.DisplayMode

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsNewBinding
    private lateinit var preferences: SharedPreferences
    
    // Web server manager
    private val webServerManager = WebServerManager()
    private var isWebServerRunning = false
    
    // Database repository
    private lateinit var musicRepository: MusicRepository
    
    // Settings adapter
    private lateinit var settingsAdapter: SettingsAdapter
    private var currentItems = mutableListOf<SettingsItem>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize database repository
        musicRepository = MusicRepository(this)
        
        setupUI()
        checkWebServerStatus()
        setupMainMenu()
    }
    
    private fun setupUI() {
        // RecyclerView 설정
        binding.settingsRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // 뒤로 가기 버튼
        binding.backButton.setOnClickListener {
            if (binding.detailPanelLayout.visibility == View.VISIBLE) {
                hideDetailPanel()
            } else {
                finish()
            }
        }
        
        // 상세 패널 버튼들
        binding.cancelButton.setOnClickListener {
            hideDetailPanel()
        }
        
        binding.applyButton.setOnClickListener {
            // 현재 표시된 상세 패널에 따라 적용 로직 실행
            hideDetailPanel()
        }
    }
    
    private fun setupMainMenu() {
        currentItems.clear()
        
        // 파일 관리 섹션
        val pdfCount = getAllPdfFiles().size
        currentItems.add(SettingsItem(
            id = "file_management",
            icon = "📂",
            title = "파일 관리",
            subtitle = "저장된 PDF 파일: ${pdfCount}개",
            arrow = "▶"
        ))
        
        // 웹서버 섹션
        val webStatus = if (isWebServerRunning) {
            val port = preferences.getInt("web_server_port", 8080)
            val ipAddress = NetworkUtils.getLocalIpAddress()
            "실행 중 ($ipAddress:$port)"
        } else {
            "중지됨"
        }
        currentItems.add(SettingsItem(
            id = "web_server",
            icon = "🌐",
            title = "웹서버",
            subtitle = webStatus,
            arrow = "▶"
        ))
        
        // 협업 모드 섹션
        currentItems.add(SettingsItem(
            id = "collaboration",
            icon = "🎼",
            title = "협업 모드",
            subtitle = "합주 설정 관리",
            arrow = "▶"
        ))
        
        // 애니메이션/사운드 섹션
        currentItems.add(SettingsItem(
            id = "animation_sound",
            icon = "🎵",
            title = "애니메이션 & 사운드",
            subtitle = "페이지 전환 효과 설정",
            arrow = "▶"
        ))
        
        // 표시 모드 섹션 (비동기로 카운트 로드)
        CoroutineScope(Dispatchers.IO).launch {
            val displayModeCount = try {
                musicRepository.getUserPreferenceCount()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error loading display mode count", e)
                0
            }
            
            withContext(Dispatchers.Main) {
                currentItems.add(SettingsItem(
                    id = "display_mode",
                    icon = "🔧",
                    title = "표시 모드",
                    subtitle = "저장된 설정: ${displayModeCount}개",
                    arrow = "▶"
                ))
                
                // 정보 섹션
                currentItems.add(SettingsItem(
                    id = "info",
                    icon = "📊",
                    title = "앱 정보",
                    subtitle = "v${BuildConfig.VERSION_NAME}",
                    arrow = "▶"
                ))
                
                updateAdapter()
            }
        }
    }
    
    private fun updateAdapter() {
        settingsAdapter = SettingsAdapter(currentItems) { item ->
            handleItemClick(item)
        }
        binding.settingsRecyclerView.adapter = settingsAdapter
    }
    
    private fun handleItemClick(item: SettingsItem) {
        when (item.id) {
            "file_management" -> showFileManagementPanel()
            "web_server" -> showWebServerPanel()
            "collaboration" -> showCollaborationPanel()
            "animation_sound" -> showAnimationSoundPanel()
            "display_mode" -> showDisplayModePanel()
            "info" -> showInfoPanel()
        }
    }
    
    private fun showFileManagementPanel() {
        val items = listOf(
            SettingsItem(
                id = "delete_all_pdf",
                icon = "🗑️",
                title = "모든 PDF 파일 삭제",
                subtitle = "저장된 모든 PDF 파일을 삭제합니다",
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("파일 관리", items)
    }
    
    private fun showWebServerPanel() {
        val port = preferences.getInt("web_server_port", 8080)
        val ipAddress = NetworkUtils.getLocalIpAddress()
        val status = if (isWebServerRunning) "실행 중 ($ipAddress:$port)" else "중지됨"
        
        val items = listOf(
            SettingsItem(
                id = "web_server_toggle",
                icon = if (isWebServerRunning) "⏹️" else "▶️",
                title = if (isWebServerRunning) "웹서버 중지" else "웹서버 시작",
                subtitle = "현재 상태: $status",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "web_server_port",
                icon = "🔧",
                title = "포트 설정",
                subtitle = "현재 포트: $port",
                type = SettingsType.INPUT
            )
        )
        
        showDetailPanel("웹서버", items)
    }
    
    private fun showCollaborationPanel() {
        val items = listOf(
            SettingsItem(
                id = "collaboration_info",
                icon = "ℹ️",
                title = "협업 모드 정보",
                subtitle = "메인 화면에서 협업 모드를 시작할 수 있습니다",
                type = SettingsType.INFO
            )
        )
        
        showDetailPanel("협업 모드", items)
    }
    
    private fun showAnimationSoundPanel() {
        val animationEnabled = preferences.getBoolean("page_turn_animation_enabled", true)
        val soundEnabled = preferences.getBoolean("page_turn_sound_enabled", true)
        val volume = preferences.getFloat("page_turn_volume", 0.25f)
        val showPageInfo = preferences.getBoolean("show_page_info", true)
        
        val items = listOf(
            SettingsItem(
                id = "animation_toggle",
                icon = "🎬",
                title = "페이지 전환 애니메이션",
                subtitle = if (animationEnabled) "활성화됨" else "비활성화됨",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "sound_toggle",
                icon = "🔊",
                title = "페이지 넘기기 사운드",
                subtitle = if (soundEnabled) "활성화됨" else "비활성화됨",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "volume_setting",
                icon = "🎚️",
                title = "사운드 볼륨",
                subtitle = "${(volume * 100).toInt()}%",
                type = SettingsType.INPUT,
                enabled = soundEnabled
            ),
            SettingsItem(
                id = "page_info_toggle",
                icon = "📄",
                title = getString(R.string.settings_show_page_info),
                subtitle = if (showPageInfo) "표시함" else "숨김",
                type = SettingsType.TOGGLE
            )
        )
        
        showDetailPanel("애니메이션 & 사운드", items)
    }
    
    private fun showDisplayModePanel() {
        val items = listOf(
            SettingsItem(
                id = "view_display_modes",
                icon = "👁️",
                title = "설정 목록 보기",
                subtitle = "저장된 파일별 표시 모드를 확인합니다",
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "reset_display_modes",
                icon = "🔄",
                title = "설정 초기화",
                subtitle = "모든 파일의 표시 모드를 초기화합니다",
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("표시 모드", items)
    }
    
    private fun showInfoPanel() {
        val items = listOf(
            SettingsItem(
                id = "app_version",
                icon = "📱",
                title = "앱 버전",
                subtitle = "v${BuildConfig.VERSION_NAME}",
                type = SettingsType.INFO
            ),
            SettingsItem(
                id = "app_info",
                icon = "ℹ️",
                title = "앱 정보",
                subtitle = "MRGQ PDF Viewer for Android TV",
                type = SettingsType.INFO
            )
        )
        
        showDetailPanel("앱 정보", items)
    }
    
    private fun showDetailPanel(title: String, items: List<SettingsItem>) {
        binding.detailTitle.text = title
        
        val detailAdapter = SettingsAdapter(items) { item ->
            handleDetailItemClick(item)
        }
        binding.detailRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.detailRecyclerView.adapter = detailAdapter
        
        binding.settingsRecyclerView.visibility = View.GONE
        binding.detailPanelLayout.visibility = View.VISIBLE
    }
    
    private fun hideDetailPanel() {
        binding.detailPanelLayout.visibility = View.GONE
        binding.settingsRecyclerView.visibility = View.VISIBLE
    }
    
    private fun handleDetailItemClick(item: SettingsItem) {
        when (item.id) {
            "delete_all_pdf" -> showDeleteAllPdfDialog()
            "web_server_toggle" -> toggleWebServer()
            "web_server_port" -> showPortSettingDialog()
            "animation_toggle" -> togglePageTurnAnimation()
            "sound_toggle" -> togglePageTurnSound()
            "volume_setting" -> showVolumeSettingDialog()
            "page_info_toggle" -> togglePageInfo()
            "animation_speed" -> showAnimationSpeedDialog()
            "view_display_modes" -> showDisplayModeListDialog()
            "reset_display_modes" -> showResetDisplayModeDialog()
        }
    }
    
    private fun showPortSettingDialog() {
        val currentPort = preferences.getInt("web_server_port", 8080)
        val editText = EditText(this)
        editText.setText(currentPort.toString())
        editText.hint = "포트 번호 (1024-65535)"
        
        AlertDialog.Builder(this)
            .setTitle("웹서버 포트 설정")
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val portText = editText.text.toString().trim()
                if (portText.isNotEmpty()) {
                    try {
                        val port = portText.toInt()
                        if (port in 1024..65535) {
                            preferences.edit().putInt("web_server_port", port).apply()
                            Toast.makeText(this, "포트 설정이 저장되었습니다: $port", Toast.LENGTH_SHORT).show()
                            hideDetailPanel()
                            setupMainMenu()
                        } else {
                            Toast.makeText(this, "포트 번호는 1024-65535 범위여야 합니다", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "유효한 포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
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
                
                hideDetailPanel()
                setupMainMenu()
            }
        }
    }
    
    // 애니메이션/사운드 설정 함수들
    private fun togglePageTurnAnimation() {
        val currentEnabled = preferences.getBoolean("page_turn_animation_enabled", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("page_turn_animation_enabled", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 전환 애니메이션이 활성화되었습니다" else "페이지 전환 애니메이션이 비활성화되었습니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        hideDetailPanel()
        setupMainMenu()
    }
    
    private fun togglePageTurnSound() {
        val currentEnabled = preferences.getBoolean("page_turn_sound_enabled", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("page_turn_sound_enabled", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 넘기기 사운드가 활성화되었습니다" else "페이지 넘기기 사운드가 비활성화되었습니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        hideDetailPanel()
        setupMainMenu()
    }
    
    private fun togglePageInfo() {
        val currentEnabled = preferences.getBoolean("show_page_info", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("show_page_info", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 정보가 표시됩니다" else "페이지 정보가 숨겨집니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        hideDetailPanel()
        setupMainMenu()
    }
    
    private fun showVolumeSettingDialog() {
        val currentVolume = preferences.getFloat("page_turn_volume", 0.25f)
        val currentPercent = (currentVolume * 100).toInt()
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_volume_slider, null)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.volumeSeekBar)
        val valueText = dialogView.findViewById<TextView>(R.id.volumeValueText)
        
        seekBar.progress = currentPercent
        valueText.text = "${currentPercent}%"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    valueText.text = "${progress}%"
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val volumePercent = seekBar.progress
                val volume = volumePercent / 100.0f
                preferences.edit().putFloat("page_turn_volume", volume).apply()
                Toast.makeText(this, "볼륨이 ${volumePercent}%로 설정되었습니다", Toast.LENGTH_SHORT).show()
                hideDetailPanel()
                setupMainMenu()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    // 웹서버 관리 함수들
    private fun checkWebServerStatus() {
        isWebServerRunning = webServerManager.isServerRunning()
        Log.d("SettingsActivity", "웹서버 상태 확인: $isWebServerRunning")
    }
    
    private fun updateWebServerStatus() {
        // 메인 메뉴에서 웹서버 상태 업데이트
        val webServerItem = currentItems.find { it.id == "web_server" }
        if (webServerItem != null && ::settingsAdapter.isInitialized) {
            val index = currentItems.indexOf(webServerItem)
            val status = if (isWebServerRunning) {
                val port = preferences.getInt("web_server_port", 8080)
                val ipAddress = NetworkUtils.getLocalIpAddress()
                "실행 중 ($ipAddress:$port)"
            } else {
                "중지됨"
            }
            
            currentItems[index] = webServerItem.copy(subtitle = status)
            settingsAdapter.notifyItemChanged(index)
        }
    }
    
    private fun toggleWebServer() {
        if (isWebServerRunning) {
            stopWebServer()
        } else {
            startWebServer()
        }
    }
    
    private fun startWebServer() {
        webServerManager.startServer(this) { success ->
            runOnUiThread {
                if (success) {
                    isWebServerRunning = true
                    val currentPort = preferences.getInt("web_server_port", 8080)
                    val serverAddress = webServerManager.getServerAddress()
                    Toast.makeText(
                        this,
                        "웹서버가 시작되었습니다\nhttp://$serverAddress:$currentPort",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "웹서버 시작 실패", Toast.LENGTH_SHORT).show()
                }
                hideDetailPanel()
                setupMainMenu()
            }
        }
    }
    
    private fun stopWebServer() {
        webServerManager.stopServer()
        isWebServerRunning = false
        Toast.makeText(this, "웹서버가 중지되었습니다", Toast.LENGTH_SHORT).show()
        hideDetailPanel()
        setupMainMenu()
    }
    
    // Display mode management functions
    private fun showResetDisplayModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("표시 모드 초기화")
            .setMessage("모든 파일의 표시 모드 설정을 초기화하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("초기화") { _, _ ->
                resetAllDisplayModes()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun resetAllDisplayModes() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                musicRepository.deleteAllUserPreferences()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "모든 표시 모드 설정이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                    hideDetailPanel()
                    setupMainMenu()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error resetting display modes", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "설정 초기화 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showDisplayModeListDialog() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = musicRepository.getAllUserPreferences()
                val pdfFiles = musicRepository.getAllPdfFiles()
                
                val preferenceList = mutableListOf<Pair<String, DisplayMode>>()
                preferences.collect { prefs ->
                    preferenceList.clear()
                    pdfFiles.collect { files ->
                        val fileMap = files.associateBy { it.id }
                        prefs.forEach { pref ->
                            val fileName = fileMap[pref.pdfFileId]?.filename ?: "Unknown"
                            preferenceList.add(fileName to pref.displayMode)
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (preferenceList.isEmpty()) {
                                Toast.makeText(this@SettingsActivity, "저장된 표시 모드 설정이 없습니다", Toast.LENGTH_SHORT).show()
                            } else {
                                showDisplayModeList(preferenceList)
                            }
                        }
                        return@collect
                    }
                    return@collect
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error loading display mode list", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "설정 목록을 불러오는 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showDisplayModeList(preferences: List<Pair<String, DisplayMode>>) {
        val displayText = StringBuilder()
        preferences.forEach { (fileName, mode) ->
            val modeText = when (mode) {
                DisplayMode.AUTO -> "자동"
                DisplayMode.SINGLE -> "한 페이지"
                DisplayMode.DOUBLE -> "두 페이지"
            }
            displayText.append("• $fileName: $modeText\n")
        }
        
        AlertDialog.Builder(this)
            .setTitle("저장된 표시 모드 설정")
            .setMessage(displayText.toString().trimEnd())
            .setPositiveButton("확인", null)
            .show()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (binding.detailPanelLayout.visibility == View.VISIBLE) {
                    hideDetailPanel()
                } else {
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun getAnimationSpeedText(): String {
        val duration = preferences.getLong("page_animation_duration", 350L)
        return when (duration) {
            0L -> "즉시"
            200L -> "빠르게"
            350L -> "보통"
            500L -> "느리게"
            800L -> "매우 느리게"
            else -> "${duration}ms"
        }
    }
    
    private fun showAnimationSpeedDialog() {
        val currentDuration = preferences.getLong("page_animation_duration", 350L)
        val speeds = arrayOf("즉시 (0ms)", "빠르게 (200ms)", "보통 (350ms)", "느리게 (500ms)", "매우 느리게 (800ms)")
        val durations = longArrayOf(0L, 200L, 350L, 500L, 800L)
        
        var selectedIndex = durations.indexOf(currentDuration)
        if (selectedIndex == -1) selectedIndex = 2 // 기본값 "보통"
        
        AlertDialog.Builder(this)
            .setTitle("페이지 넘기기 애니메이션 속도")
            .setSingleChoiceItems(speeds, selectedIndex) { dialog, which ->
                val newDuration = durations[which]
                preferences.edit().putLong("page_animation_duration", newDuration).apply()
                
                val message = "애니메이션 속도가 변경되었습니다: ${speeds[which]}"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                
                dialog.dismiss()
                hideDetailPanel()
                setupMainMenu()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        checkWebServerStatus()
        updateWebServerStatus()
    }
}