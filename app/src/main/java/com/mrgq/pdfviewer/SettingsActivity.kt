package com.mrgq.pdfviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.adapter.ConductorAdapter
import com.mrgq.pdfviewer.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: SharedPreferences
    
    // Global collaboration manager
    private val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    private var currentCollaborationMode = CollaborationMode.NONE
    
    // Conductor discovery
    private lateinit var conductorAdapter: ConductorAdapter
    private var isDiscovering = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize global collaboration manager
        globalCollaborationManager.initialize(this)
        currentCollaborationMode = globalCollaborationManager.getCurrentMode()
        
        setupUI()
        updateSettingsInfo()
        setupPdfFileInfo()
        setupConductorDiscoveryUI()
        setupCollaborationCallbacks()
        setupCollaborationUI()
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
    
    private fun setupCollaborationClickListeners() {
        binding.masterModeBtn.setOnClickListener {
            activateConductorMode()
        }
        
        binding.slaveModeBtn.setOnClickListener {
            activatePerformerMode()
        }
        
        binding.collaborationOffBtn.setOnClickListener {
            deactivateCollaborationMode()
        }
        
        binding.connectBtn.setOnClickListener {
            connectToConductor()
        }
        
        binding.discoverConductorBtn.setOnClickListener {
            startConductorDiscovery()
        }
        
        binding.scanMastersBtn.setOnClickListener {
            scanForConductors()
        }
        
        binding.webConnectBtn.setOnClickListener {
            showWebConnectDialog()
        }
        
        binding.showClientsBtn.setOnClickListener {
            showConnectedClientsDialog()
        }
    }
    
    private fun setupConductorDiscoveryUI() {
        // Setup RecyclerView for discovered conductors
        conductorAdapter = ConductorAdapter { conductorInfo ->
            connectToDiscoveredConductor(conductorInfo)
        }
        
        binding.discoveredConductorsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = conductorAdapter
        }
    }
    
    private fun setupCollaborationUI() {
        // Load saved collaboration mode
        val savedMode = preferences.getString("collaboration_mode", "none") ?: "none"
        currentCollaborationMode = when (savedMode) {
            "conductor" -> CollaborationMode.CONDUCTOR
            "performer" -> CollaborationMode.PERFORMER
            // Legacy support
            "master" -> CollaborationMode.CONDUCTOR
            "slave" -> CollaborationMode.PERFORMER
            else -> CollaborationMode.NONE
        }
        
        updateCollaborationUI()
        setupCollaborationClickListeners()
        
        // Setup collaboration callbacks if needed
        if (currentCollaborationMode != CollaborationMode.NONE) {
            setupCollaborationCallbacks()
        }
    }
    
    private fun updateCollaborationUI() {
        when (currentCollaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                binding.masterModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_primary)
                binding.slaveModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_surface)
                binding.collaborationStatus.text = "협업 모드: 지휘자"
                binding.connectionSettingsLayout.visibility = View.GONE
                binding.masterInfoLayout.visibility = View.VISIBLE
                updateConductorInfo()
            }
            CollaborationMode.PERFORMER -> {
                binding.masterModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_surface)
                binding.slaveModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_primary)
                binding.collaborationStatus.text = "협업 모드: 연주자"
                binding.connectionSettingsLayout.visibility = View.VISIBLE
                binding.masterInfoLayout.visibility = View.GONE
                updatePerformerInfo()
            }
            CollaborationMode.NONE -> {
                binding.masterModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_surface)
                binding.slaveModeBtn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.tv_surface)
                binding.collaborationStatus.text = "협업 모드: 비활성"
                binding.connectionSettingsLayout.visibility = View.GONE
                binding.masterInfoLayout.visibility = View.GONE
            }
        }
    }
    
    private fun activateConductorMode() {
        if (globalCollaborationManager.activateConductorMode()) {
            currentCollaborationMode = CollaborationMode.CONDUCTOR
            setupCollaborationCallbacks()
            updateCollaborationUI()
            Toast.makeText(this, "지휘자 모드가 활성화되었습니다", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "지휘자 모드 활성화 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun activatePerformerMode() {
        if (globalCollaborationManager.activatePerformerMode()) {
            currentCollaborationMode = CollaborationMode.PERFORMER
            setupCollaborationCallbacks()
            updateCollaborationUI()
            Toast.makeText(this, "연주자 모드가 활성화되었습니다", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "연주자 모드 활성화 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deactivateCollaborationMode() {
        globalCollaborationManager.deactivateCollaborationMode()
        currentCollaborationMode = CollaborationMode.NONE
        updateCollaborationUI()
        Toast.makeText(this, "협업 모드가 비활성화되었습니다", Toast.LENGTH_SHORT).show()
    }
    
    private fun setupCollaborationCallbacks() {
        when (currentCollaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
                    runOnUiThread {
                        updateConductorInfo()
                        Toast.makeText(this@SettingsActivity, "$deviceName 연결됨", Toast.LENGTH_SHORT).show()
                    }
                }
                
                globalCollaborationManager.setOnServerClientDisconnected { clientId ->
                    runOnUiThread {
                        updateConductorInfo()
                        Toast.makeText(this@SettingsActivity, "기기 연결 해제됨", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            CollaborationMode.PERFORMER -> {
                globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
                    runOnUiThread {
                        updatePerformerInfo()
                    }
                }
                
                // Load saved conductor IP
                val savedConductorIp = preferences.getString("conductor_ip", "")
                if (!savedConductorIp.isNullOrBlank()) {
                    binding.masterIpEditText.setText(savedConductorIp)
                }
            }
            
            else -> {
                // No callbacks needed for NONE mode
            }
        }
    }
    
    
    private fun updateConductorInfo() {
        val connectionInfo = globalCollaborationManager.getServerConnectionInfo()
        binding.masterConnectionInfo.text = "연결 주소: $connectionInfo"
        
        val clientCount = globalCollaborationManager.getConnectedClientCount()
        binding.connectedClientsInfo.text = "연결된 기기: ${clientCount}대"
    }
    
    private fun updatePerformerInfo() {
        val isConnected = globalCollaborationManager.isClientConnected()
        val conductorAddress = globalCollaborationManager.getConductorAddress()
        
        if (isConnected) {
            binding.collaborationStatus.text = "협업 모드: 연주자 (연결됨: $conductorAddress)"
        } else {
            binding.collaborationStatus.text = "협업 모드: 연주자 (연결 끊김)"
        }
    }
    
    private fun connectToConductor() {
        val conductorIp = binding.masterIpEditText.text.toString().trim()
        
        if (conductorIp.isEmpty()) {
            Toast.makeText(this, "지휘자 IP 주소를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Validate IP address format
        if (!NetworkUtils.isValidIpAddress(conductorIp)) {
            Toast.makeText(this, "유효한 IP 주소를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save conductor IP
        preferences.edit().putString("conductor_ip", conductorIp).apply()
        
        // Connect to conductor
        val connected = globalCollaborationManager.connectToConductor(conductorIp, 9090, "Android TV Device")
        if (connected) {
            Toast.makeText(this, "지휘자에 연결 시도 중...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "연결 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun scanForConductors() {
        Toast.makeText(this, "지휘자 기기 검색 기능은 준비 중입니다", Toast.LENGTH_SHORT).show()
        // TODO: Implement network scanning for conductor devices
    }
    
    private fun showWebConnectDialog() {
        Toast.makeText(this, "웹 연결 코드 기능은 준비 중입니다", Toast.LENGTH_SHORT).show()
        // TODO: Implement web-based connection with codes
    }
    
    private fun showConnectedClientsDialog() {
        val clients = globalCollaborationManager.getConnectedClients()
        
        if (clients.isEmpty()) {
            Toast.makeText(this, "연결된 기기가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clientNames = clients.map { "${it.second} (${it.first})" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("연결된 기기 목록")
            .setItems(clientNames) { _, _ ->
                // Could implement client management here
            }
            .setPositiveButton("확인", null)
            .show()
    }
    
    private fun startConductorDiscovery() {
        if (currentCollaborationMode != CollaborationMode.PERFORMER) {
            Toast.makeText(this, "연주자 모드에서만 지휘자 찾기가 가능합니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isDiscovering) {
            stopConductorDiscovery()
            return
        }
        
        isDiscovering = true
        conductorAdapter.clearConductors()
        
        // UI 업데이트
        binding.discoverConductorBtn.text = "찾기 중지"
        binding.discoveryStatus.visibility = View.VISIBLE
        binding.discoveredConductorsRecyclerView.visibility = View.VISIBLE
        
        // Setup discovery callbacks
        globalCollaborationManager.setOnConductorDiscovered { conductorInfo ->
            runOnUiThread {
                conductorAdapter.addConductor(conductorInfo)
                val count = conductorAdapter.getConductorCount()
                binding.discoveryStatus.text = "지휘자 ${count}개 발견됨"
            }
        }
        
        globalCollaborationManager.setOnDiscoveryTimeout {
            runOnUiThread {
                stopConductorDiscovery()
                val count = conductorAdapter.getConductorCount()
                if (count == 0) {
                    binding.discoveryStatus.text = "지휘자를 찾을 수 없습니다"
                    Toast.makeText(this@SettingsActivity, "네트워크에서 지휘자를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                } else {
                    binding.discoveryStatus.text = "검색 완료 - 지휘자 ${count}개 발견"
                }
            }
        }
        
        // Start discovery
        val success = globalCollaborationManager.startConductorDiscovery()
        if (!success) {
            Toast.makeText(this, "지휘자 찾기를 시작할 수 없습니다", Toast.LENGTH_SHORT).show()
            stopConductorDiscovery()
        }
    }
    
    private fun stopConductorDiscovery() {
        if (!isDiscovering) return
        
        isDiscovering = false
        globalCollaborationManager.stopConductorDiscovery()
        
        // UI 업데이트
        binding.discoverConductorBtn.text = "지휘자 자동 찾기"
        
        if (conductorAdapter.getConductorCount() == 0) {
            binding.discoveryStatus.visibility = View.GONE
            binding.discoveredConductorsRecyclerView.visibility = View.GONE
        }
    }
    
    private fun connectToDiscoveredConductor(conductorInfo: ConductorDiscovery.ConductorInfo) {
        stopConductorDiscovery()
        
        // Fill in the IP address field
        binding.masterIpEditText.setText(conductorInfo.ipAddress)
        
        // Attempt connection
        val success = globalCollaborationManager.connectToDiscoveredConductor(conductorInfo)
        
        if (success) {
            Toast.makeText(this, "${conductorInfo.name}에 연결 시도 중...", Toast.LENGTH_SHORT).show()
            
            // Hide discovery UI
            binding.discoveryStatus.visibility = View.GONE
            binding.discoveredConductorsRecyclerView.visibility = View.GONE
            conductorAdapter.clearConductors()
            
            // Update collaboration info
            updatePerformerInfo()
        } else {
            Toast.makeText(this, "연결에 실패했습니다", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop discovery if running
        if (isDiscovering) {
            stopConductorDiscovery()
        }
        
        // Note: 협업 리소스는 전역 매니저가 관리하므로 여기서 정리하지 않음
        // 앱이 완전히 종료될 때만 정리됨
    }
    
    private fun setupCollaborationCallbacks() {
        // Set up file change callback for performer mode
        globalCollaborationManager.setOnFileChangeReceived { fileName ->
            runOnUiThread {
                handleRemoteFileChange(fileName)
            }
        }
    }
    
    private fun handleRemoteFileChange(fileName: String) {
        android.util.Log.d("SettingsActivity", "🎼 연주자 모드: 파일 '$fileName' 변경 요청 받음 (SettingsActivity)")
        
        // Switch to MainActivity and open the file
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            // Add flag to indicate this is from file change request
            putExtra("requested_file", fileName)
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        
        // Show a toast to indicate what's happening
        Toast.makeText(this, "지휘자가 '$fileName' 파일을 열었습니다", Toast.LENGTH_SHORT).show()
    }
}