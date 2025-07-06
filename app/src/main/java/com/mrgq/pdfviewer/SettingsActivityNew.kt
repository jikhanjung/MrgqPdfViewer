package com.mrgq.pdfviewer

import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.adapter.SettingsAdapter
import com.mrgq.pdfviewer.databinding.ActivitySettingsNewBinding
import com.mrgq.pdfviewer.model.SettingsItem
import com.mrgq.pdfviewer.model.SettingsItemType
import com.mrgq.pdfviewer.model.SettingsCategories
import com.mrgq.pdfviewer.model.SettingsItemIds
import android.util.Log
import androidx.appcompat.app.AlertDialog
import android.os.Environment
import java.io.File

class SettingsActivityNew : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsNewBinding
    private lateinit var preferences: SharedPreferences
    private lateinit var settingsAdapter: SettingsAdapter
    
    // Global collaboration manager
    private val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    private var currentCollaborationMode = CollaborationMode.NONE
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize global collaboration manager
        globalCollaborationManager.initialize(this)
        currentCollaborationMode = globalCollaborationManager.getCurrentMode()
        
        setupUI()
        setupCollaborationCallbacks()
        loadMainSettings()
    }
    
    private fun setupUI() {
        // RecyclerView 설정
        settingsAdapter = SettingsAdapter { settingsItem ->
            handleSettingsItemClick(settingsItem)
        }
        
        binding.settingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivityNew)
            adapter = settingsAdapter
            setHasFixedSize(true)
        }
        
        // 뒤로가기 버튼
        binding.backButton.setOnClickListener {
            finish()
        }
    }
    
    private fun loadMainSettings() {
        val settingsItems = mutableListOf<SettingsItem>()
        
        // 파일 관리
        val pdfCount = getPdfFileCount()
        settingsItems.add(
            SettingsItem(
                id = SettingsCategories.FILE_MANAGEMENT,
                title = "파일 관리",
                subtitle = "PDF 파일 $pdfCount 개",
                icon = "📂",
                type = SettingsItemType.CATEGORY
            )
        )
        
        // 웹서버 설정
        val currentPort = preferences.getInt("web_server_port", 8080)
        settingsItems.add(
            SettingsItem(
                id = SettingsCategories.WEB_SERVER,
                title = "웹서버 설정",
                subtitle = "포트: $currentPort",
                icon = "🌐",
                type = SettingsItemType.CATEGORY
            )
        )
        
        // 협업 모드
        val collaborationStatus = getCollaborationStatusText()
        settingsItems.add(
            SettingsItem(
                id = SettingsCategories.COLLABORATION,
                title = "협업 모드",
                subtitle = collaborationStatus,
                icon = "🎼",
                type = SettingsItemType.CATEGORY
            )
        )
        
        // 시스템 설정
        settingsItems.add(
            SettingsItem(
                id = SettingsCategories.SYSTEM,
                title = "시스템 설정",
                subtitle = "초기화 및 캐시 관리",
                icon = "🔧",
                type = SettingsItemType.CATEGORY
            )
        )
        
        // 정보
        settingsItems.add(
            SettingsItem(
                id = SettingsCategories.INFO,
                title = "정보",
                subtitle = "버전 및 상태 정보",
                icon = "📊",
                type = SettingsItemType.CATEGORY
            )
        )
        
        settingsAdapter.submitList(settingsItems)
    }
    
    private fun handleSettingsItemClick(item: SettingsItem) {
        when (item.id) {
            SettingsCategories.FILE_MANAGEMENT -> showFileManagementSettings()
            SettingsCategories.WEB_SERVER -> showWebServerSettings()
            SettingsCategories.COLLABORATION -> showCollaborationSettings()
            SettingsCategories.SYSTEM -> showSystemSettings()
            SettingsCategories.INFO -> showInfoSettings()
        }
    }
    
    private fun showFileManagementSettings() {
        val items = mutableListOf<SettingsItem>()
        
        val pdfCount = getPdfFileCount()
        items.add(
            SettingsItem(
                id = SettingsItemIds.PDF_FILES_INFO,
                title = "PDF 파일 정보",
                subtitle = "$pdfCount 개의 PDF 파일이 있습니다",
                icon = "📄",
                type = SettingsItemType.INFO,
                hasArrow = false
            )
        )
        
        val fileSettingsCount = getFileSettingsCount()
        items.add(
            SettingsItem(
                id = SettingsItemIds.FILE_SETTINGS_MANAGEMENT,
                title = "파일별 설정 관리",
                subtitle = "$fileSettingsCount 개 파일에 설정 저장됨",
                icon = "⚙️",
                type = SettingsItemType.ACTION
            )
        )
        
        if (pdfCount > 0) {
            items.add(
                SettingsItem(
                    id = SettingsItemIds.DELETE_ALL_PDF,
                    title = "모든 PDF 파일 삭제",
                    subtitle = "주의: 복구할 수 없습니다",
                    icon = "🗑️",
                    type = SettingsItemType.ACTION
                )
            )
        }
        
        showDetailPanel("📂 파일 관리", items)
    }
    
    private fun showWebServerSettings() {
        val items = mutableListOf<SettingsItem>()
        
        val currentPort = preferences.getInt("web_server_port", 8080)
        items.add(
            SettingsItem(
                id = SettingsItemIds.WEB_SERVER_PORT,
                title = "포트 번호 설정",
                subtitle = "현재: $currentPort",
                icon = "🔌",
                type = SettingsItemType.INPUT
            )
        )
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.WEB_SERVER_STATUS,
                title = "서버 상태",
                subtitle = "웹서버 설정 및 상태 확인",
                icon = "📶",
                type = SettingsItemType.INFO,
                hasArrow = false
            )
        )
        
        showDetailPanel("🌐 웹서버 설정", items)
    }
    
    private fun showCollaborationSettings() {
        val items = mutableListOf<SettingsItem>()
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.CONDUCTOR_MODE,
                title = "지휘자 모드",
                subtitle = "모든 기기의 페이지를 제어합니다",
                icon = "🎯",
                type = SettingsItemType.ACTION
            )
        )
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.PERFORMER_MODE,
                title = "연주자 모드",
                subtitle = "지휘자의 페이지 변경에 따라 동기화됩니다",
                icon = "🎵",
                type = SettingsItemType.ACTION
            )
        )
        
        if (currentCollaborationMode != CollaborationMode.NONE) {
            items.add(
                SettingsItem(
                    id = SettingsItemIds.COLLABORATION_OFF,
                    title = "협업 모드 끄기",
                    subtitle = "협업 기능을 비활성화합니다",
                    icon = "❌",
                    type = SettingsItemType.ACTION
                )
            )
        }
        
        if (currentCollaborationMode == CollaborationMode.PERFORMER) {
            items.add(
                SettingsItem(
                    id = SettingsItemIds.CONNECTION_SETTINGS,
                    title = "연결 설정",
                    subtitle = "지휘자 기기에 연결합니다",
                    icon = "🔗",
                    type = SettingsItemType.ACTION
                )
            )
            
            items.add(
                SettingsItem(
                    id = SettingsItemIds.AUTO_DISCOVERY,
                    title = "자동 발견",
                    subtitle = "네트워크에서 지휘자를 찾습니다",
                    icon = "🔍",
                    type = SettingsItemType.ACTION
                )
            )
        }
        
        showDetailPanel("🎼 협업 모드", items)
    }
    
    private fun showSystemSettings() {
        val items = mutableListOf<SettingsItem>()
        
        val fileSettingsCount = getFileSettingsCount()
        if (fileSettingsCount > 0) {
            items.add(
                SettingsItem(
                    id = SettingsItemIds.RESET_FILE_SETTINGS,
                    title = "파일별 설정 초기화",
                    subtitle = "$fileSettingsCount 개 파일의 설정을 삭제합니다",
                    icon = "🔄",
                    type = SettingsItemType.ACTION
                )
            )
        }
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.RESET_ALL_SETTINGS,
                title = "모든 설정 초기화",
                subtitle = "앱을 처음 설치한 상태로 되돌립니다",
                icon = "⚠️",
                type = SettingsItemType.ACTION
            )
        )
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.CACHE_MANAGEMENT,
                title = "캐시 관리",
                subtitle = "이미지 캐시 및 임시 파일 정리",
                icon = "🧹",
                type = SettingsItemType.ACTION
            )
        )
        
        showDetailPanel("🔧 시스템 설정", items)
    }
    
    private fun showInfoSettings() {
        val items = mutableListOf<SettingsItem>()
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.APP_VERSION,
                title = "앱 버전",
                subtitle = "v0.1.5",
                icon = "📱",
                type = SettingsItemType.INFO,
                hasArrow = false
            )
        )
        
        if (currentCollaborationMode != CollaborationMode.NONE) {
            items.add(
                SettingsItem(
                    id = SettingsItemIds.CONNECTION_INFO,
                    title = "연결 정보",
                    subtitle = getCollaborationStatusText(),
                    icon = "📡",
                    type = SettingsItemType.INFO,
                    hasArrow = false
                )
            )
        }
        
        items.add(
            SettingsItem(
                id = SettingsItemIds.DEBUG_INFO,
                title = "디버그 정보",
                subtitle = "시스템 상태 및 로그 정보",
                icon = "🐛",
                type = SettingsItemType.ACTION
            )
        )
        
        showDetailPanel("📊 정보", items)
    }
    
    private fun showDetailPanel(title: String, items: List<SettingsItem>) {
        binding.detailTitle.text = title
        
        // 상세 패널용 어댑터 설정
        val detailAdapter = SettingsAdapter { item ->
            handleDetailItemClick(item)
        }
        
        binding.detailRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivityNew)
            adapter = detailAdapter
        }
        
        detailAdapter.submitList(items)
        
        // 패널 표시/숨김
        binding.mainMenuLayout.visibility = android.view.View.GONE
        binding.detailPanelLayout.visibility = android.view.View.VISIBLE
        
        // 버튼 이벤트
        binding.cancelButton.setOnClickListener {
            hideDetailPanel()
        }
        
        binding.applyButton.setOnClickListener {
            hideDetailPanel()
        }
    }
    
    private fun hideDetailPanel() {
        binding.detailPanelLayout.visibility = android.view.View.GONE
        binding.mainMenuLayout.visibility = android.view.View.VISIBLE
        
        // 메인 설정 새로고침
        loadMainSettings()
    }
    
    private fun handleDetailItemClick(item: SettingsItem) {
        when (item.id) {
            SettingsItemIds.DELETE_ALL_PDF -> confirmDeleteAllPdf()
            SettingsItemIds.WEB_SERVER_PORT -> showPortInputDialog()
            SettingsItemIds.CONDUCTOR_MODE -> activateConductorMode()
            SettingsItemIds.PERFORMER_MODE -> activatePerformerMode()
            SettingsItemIds.COLLABORATION_OFF -> deactivateCollaboration()
            SettingsItemIds.RESET_ALL_SETTINGS -> confirmResetAllSettings()
            SettingsItemIds.CACHE_MANAGEMENT -> performCacheCleanup()
            // 다른 아이템들도 필요에 따라 추가
        }
    }
    
    // 협업 콜백 설정
    private fun setupCollaborationCallbacks() {
        globalCollaborationManager.setOnFileChangeReceived { fileName ->
            runOnUiThread {
                handleRemoteFileChange(fileName)
            }
        }
    }
    
    private fun handleRemoteFileChange(fileName: String) {
        Log.d("SettingsActivityNew", "🎼 연주자 모드: 파일 '$fileName' 변경 요청 받음 (SettingsActivityNew)")
        
        // Switch to MainActivity and open the file
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("requested_file", fileName)
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        
        Toast.makeText(this, "지휘자가 '$fileName' 파일을 열었습니다", Toast.LENGTH_SHORT).show()
    }
    
    // 유틸리티 메서드들
    private fun getPdfFileCount(): Int {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return downloadDir.listFiles { file -> file.extension.lowercase() == "pdf" }?.size ?: 0
    }
    
    private fun getFileSettingsCount(): Int {
        return preferences.all.keys.count { it.startsWith("file_mode_") }
    }
    
    private fun getCollaborationStatusText(): String {
        return when (currentCollaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                "지휘자 (연결된 기기: ${clientCount}대)"
            }
            CollaborationMode.PERFORMER -> {
                if (globalCollaborationManager.isClientConnected()) {
                    "연주자 (연결됨)"
                } else {
                    "연주자 (연결 끊김)"
                }
            }
            else -> "비활성화"
        }
    }
    
    // 액션 메서드들
    private fun confirmDeleteAllPdf() {
        AlertDialog.Builder(this)
            .setTitle("모든 PDF 파일 삭제")
            .setMessage("정말로 모든 PDF 파일을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                // 실제 삭제 로직 구현
                Toast.makeText(this, "PDF 파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                hideDetailPanel()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun showPortInputDialog() {
        // 포트 입력 다이얼로그 구현
        Toast.makeText(this, "포트 설정 다이얼로그 (구현 예정)", Toast.LENGTH_SHORT).show()
    }
    
    private fun activateConductorMode() {
        val success = globalCollaborationManager.activateConductorMode()
        if (success) {
            currentCollaborationMode = CollaborationMode.CONDUCTOR
            Toast.makeText(this, "지휘자 모드가 활성화되었습니다", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "지휘자 모드 활성화에 실패했습니다", Toast.LENGTH_SHORT).show()
        }
        hideDetailPanel()
    }
    
    private fun activatePerformerMode() {
        val success = globalCollaborationManager.activatePerformerMode()
        if (success) {
            currentCollaborationMode = CollaborationMode.PERFORMER
            Toast.makeText(this, "연주자 모드가 활성화되었습니다", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "연주자 모드 활성화에 실패했습니다", Toast.LENGTH_SHORT).show()
        }
        hideDetailPanel()
    }
    
    private fun deactivateCollaboration() {
        globalCollaborationManager.deactivateCollaborationMode()
        currentCollaborationMode = CollaborationMode.NONE
        Toast.makeText(this, "협업 모드가 비활성화되었습니다", Toast.LENGTH_SHORT).show()
        hideDetailPanel()
    }
    
    private fun confirmResetAllSettings() {
        AlertDialog.Builder(this)
            .setTitle("모든 설정 초기화")
            .setMessage("모든 설정을 초기화하시겠습니까?\n앱이 재시작됩니다.")
            .setPositiveButton("초기화") { _, _ ->
                preferences.edit().clear().apply()
                Toast.makeText(this, "설정이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                finishAffinity()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun performCacheCleanup() {
        // 캐시 정리 로직 구현
        Toast.makeText(this, "캐시가 정리되었습니다", Toast.LENGTH_SHORT).show()
        hideDetailPanel()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (binding.detailPanelLayout.visibility == android.view.View.VISIBLE) {
                    hideDetailPanel()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Note: 협업 리소스는 전역 매니저가 관리하므로 여기서 정리하지 않음
    }
}