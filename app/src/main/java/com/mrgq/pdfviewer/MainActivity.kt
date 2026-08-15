package com.mrgq.pdfviewer

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.adapter.PdfFileAdapter
import com.mrgq.pdfviewer.databinding.ActivityMainBinding
import com.mrgq.pdfviewer.model.PdfFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfAdapter: PdfFileAdapter
    private var currentSortBy = PdfFileSorter.BY_NAME
    private var isFileManagementMode = false // 파일 관리 모드 상태
    
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Note: Global collaboration manager는 Application에서 이미 초기화됨
        
        setupRecyclerView()
        loadPdfFiles()
        setupCollaborationButton()
        setupSortButtons()
        setupSettingsButton()
        setupFileManagementButton()
        setupCollaborationCallbacks()
        
        // Set version number
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"
        
        // Add fade-in animation for UI elements
        addFadeInAnimations()
        
        // Handle file request from SettingsActivity
        handleFileRequest()
    }
    
    private fun setupRecyclerView() {
        pdfAdapter = PdfFileAdapter(
            onItemClick = { pdfFile, position ->
                openPdfFile(pdfFile, position)
            },
            onDeleteClick = { pdfFile ->
                showDeleteConfirmationDialog(pdfFile)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pdfAdapter
            setHasFixedSize(true)
        }
    }
    
    
    
    override fun onResume() {
        super.onResume()
        
        // ====================[ 핵심 수정 사항 ]====================
        // 액티비티가 다시 활성화될 때마다 협업 콜백을 재등록합니다.
        // 이를 통해 PdfViewerActivity에서 돌아왔을 때 콜백 유실을 방지하고,
        // 지휘자의 파일 변경 메시지를 안정적으로 수신할 수 있습니다.
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        if (globalCollaborationManager.getCurrentMode() != CollaborationMode.NONE) {
            Log.d("MainActivity", "onResume: Re-registering collaboration callbacks")
            setupCollaborationCallbacks()
        }
        // ==========================================================
        
        Log.d("MainActivity", "onResume - 협업 상태 업데이트")
        updateCollaborationStatus()
        
        // Always refresh file list when returning to MainActivity
        // This ensures files deleted/added in Settings are reflected
        Log.d("MainActivity", "onResume - 파일 목록 새로고침")
        loadPdfFiles()
        
        // Clear any refresh flag
        val preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        preferences.edit().putBoolean("refresh_file_list", false).apply()
    }
    
    private fun loadPdfFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            val pdfFiles = getCurrentPdfFiles()
            
            withContext(Dispatchers.Main) {
                pdfAdapter.submitList(pdfFiles)
                binding.emptyView.visibility = if (pdfFiles.isEmpty()) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        }
    }
    
    private fun setupCollaborationButton() {
        binding.collaborationButton.setOnClickListener {
            // Show collaboration options dialog
            showCollaborationDialog()
        }
        
        // Update collaboration status on startup
        updateCollaborationStatus()
    }
    
    private fun showCollaborationDialog() {
        val globalManager = GlobalCollaborationManager.getInstance()
        val currentMode = globalManager.getCurrentMode()
        
        val options = when (currentMode) {
            CollaborationMode.NONE -> arrayOf("지휘자 모드 시작", "연주자 모드 시작", "취소")
            CollaborationMode.CONDUCTOR -> arrayOf("지휘자 모드 종료", "취소")
            CollaborationMode.PERFORMER -> arrayOf("연주자 모드 종료", "취소")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("합주 모드")
            .setItems(options) { _, which ->
                when (currentMode) {
                    CollaborationMode.NONE -> {
                        when (which) {
                            0 -> startConductorMode()
                            1 -> showPerformerDialog()
                        }
                    }
                    CollaborationMode.CONDUCTOR -> {
                        if (which == 0) stopCollaborationMode()
                    }
                    CollaborationMode.PERFORMER -> {
                        if (which == 0) stopCollaborationMode()
                    }
                }
            }
            .show()
    }
    
    /**
     * Clean, consistent flow for conductor mode:
     * 1. Activate conductor mode (this clears callbacks)
     * 2. Setup callbacks after activation 
     * 3. Update status
     */
    private fun startConductorMode() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        Log.d("MainActivity", "🎯 Starting conductor mode")
        
        // STEP 1: Activate conductor mode FIRST (this clears callbacks)
        val success = globalManager.activateConductorMode()
        
        if (success) {
            Toast.makeText(this, "지휘자 모드가 시작되었습니다", Toast.LENGTH_SHORT).show()
            
            // STEP 2: Setup ALL callbacks AFTER mode activation (so they don't get cleared)
            Log.d("MainActivity", "🎯 Setting up conductor callbacks")
            setupCollaborationCallbacks()
            
            // STEP 3: Update collaboration status
            updateCollaborationStatus()
            
            Log.d("MainActivity", "🎯 Conductor mode ready - waiting for performers")
        } else {
            Toast.makeText(this, "지휘자 모드 시작 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPerformerDialog() {
        // 기본값으로 자동 발견 모드로 바로 시작
        startPerformerModeWithAutoDiscovery()
        
        // 자동 발견 실패 시 수동 연결 옵션 제공을 위한 타임아웃 설정
        binding.collaborationStatus.postDelayed({
            // 만약 아직 연결되지 않았다면 수동 연결 옵션 제공
            val globalManager = GlobalCollaborationManager.getInstance()
            if (globalManager.getCurrentMode() == CollaborationMode.PERFORMER && !globalManager.isClientConnected()) {
                showManualConnectionOption()
            }
        }, 15000) // 15초 후 수동 연결 옵션 제공
    }
    
    private fun showManualConnectionOption() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지휘자 연결")
            .setMessage("자동 발견에 실패했습니다.\n수동으로 지휘자 IP를 입력하시겠습니까?")
            .setPositiveButton("수동 입력") { _, _ ->
                showManualConnectionDialog()
            }
            .setNegativeButton("계속 자동 발견", null)
            .setNeutralButton("연주자 모드 종료") { _, _ ->
                stopCollaborationMode()
            }
            .show()
    }
    
    /**
     * Clean, linear flow for performer mode with discovery:
     * 1. Setup all callbacks first
     * 2. Activate performer mode
     * 3. Start discovery
     */
    private fun startPerformerModeWithAutoDiscovery() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        Log.d("MainActivity", "🎯 Starting performer mode with auto-discovery")
        
        // STEP 1: Activate performer mode FIRST (this clears callbacks)
        val success = globalManager.activatePerformerMode()
        
        if (success) {
            updateCollaborationStatus()
            Toast.makeText(this, "연주자 모드 시작 - 지휘자 자동 검색 중...", Toast.LENGTH_SHORT).show()
            
            // STEP 2: Setup ALL callbacks AFTER mode activation (so they don't get cleared)
            Log.d("MainActivity", "🎯 Setting up collaboration callbacks")
            setupCollaborationCallbacks()
            
            // STEP 3: Setup discovery-specific callbacks
            Log.d("MainActivity", "🎯 Setting up discovery callbacks")
            
            globalManager.setOnConductorDiscovered { conductorInfo ->
                runOnUiThread {
                    Log.d("MainActivity", "🎯 Conductor discovered in UI: ${conductorInfo.name} at ${conductorInfo.ipAddress}")
                    
                    // Auto-connect to discovered conductor
                    val connected = globalManager.connectToDiscoveredConductor(conductorInfo)
                    if (connected) {
                        Toast.makeText(this, "지휘자 발견 - 연결 중...", Toast.LENGTH_SHORT).show()
                        // Stop discovery after successful connection attempt
                        globalManager.stopConductorDiscovery()
                    } else {
                        Toast.makeText(this, "지휘자 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            globalManager.setOnDiscoveryTimeout {
                runOnUiThread {
                    Log.d("MainActivity", "🎯 Discovery timeout in UI")
                    Toast.makeText(this, "지휘자를 찾을 수 없습니다. 수동 연결을 시도해보세요.", Toast.LENGTH_LONG).show()
                }
            }
            
            // STEP 4: Start discovery manually with proper callback setup
            Log.d("MainActivity", "🎯 Starting conductor discovery")
            val discoveryStarted = globalManager.startConductorDiscovery()
            
            if (!discoveryStarted) {
                Toast.makeText(this, "자동 검색 시작 실패", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "연주자 모드 시작 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showConductorFoundDialog(conductorInfo: com.mrgq.pdfviewer.ConductorDiscovery.ConductorInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지휘자 발견")
            .setMessage("${conductorInfo.name}\nIP: ${conductorInfo.ipAddress}\n\n연결하시겠습니까?")
            .setPositiveButton("연결") { _, _ ->
                val globalManager = GlobalCollaborationManager.getInstance()
                val connected = globalManager.connectToDiscoveredConductor(conductorInfo)
                if (connected) {
                    Toast.makeText(this, "지휘자에 연결 중...", Toast.LENGTH_SHORT).show()
                    // 상태 업데이트는 콜백에서 처리되도록 함
                } else {
                    Toast.makeText(this, "연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun showManualConnectionDialog() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        // 연주자 모드가 아니면 먼저 시작
        if (globalManager.getCurrentMode() != CollaborationMode.PERFORMER) {
            val success = globalManager.activatePerformerMode()
            if (!success) {
                Toast.makeText(this, "연주자 모드 시작 실패", Toast.LENGTH_SHORT).show()
                return
            }
            updateCollaborationStatus()
        }
        
        val input = android.widget.EditText(this)
        input.hint = "192.168.1.100"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("수동 연결")
            .setMessage("지휘자의 IP 주소를 입력하세요:")
            .setView(input)
            .setPositiveButton("연결") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    connectToManualConductor(ip)
                } else {
                    Toast.makeText(this, "IP 주소를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun connectToManualConductor(ip: String) {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        // Connect to conductor (performer mode should already be active)
        val deviceName = android.os.Build.MODEL ?: "Android TV"
        val connected = globalManager.connectToConductor(ip, 9090, "$deviceName (연주자)")
        
        if (connected) {
            Toast.makeText(this, "지휘자에 연결 중... ($ip)", Toast.LENGTH_SHORT).show()
            // 상태 업데이트는 콜백에서 처리되도록 함
        } else {
            Toast.makeText(this, "연결 실패. IP 주소와 지휘자 상태를 확인해주세요.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopCollaborationMode() {
        val globalManager = GlobalCollaborationManager.getInstance()
        globalManager.deactivateCollaborationMode()
        Toast.makeText(this, "합주 모드가 종료되었습니다", Toast.LENGTH_SHORT).show()
        updateCollaborationStatus()
    }
    
    private fun openPdfFile(pdfFile: PdfFile, position: Int) {
        val currentPdfFiles = pdfAdapter.currentList
        
        // Use the stable file path to find the ACTUAL current index.
        // This is the crucial fix for the race condition.
        val actualIndex = currentPdfFiles.indexOfFirst { it.path == pdfFile.path }
        
        // Safety check: if file was deleted in the meantime, abort.
        if (actualIndex == -1) {
            Toast.makeText(this, getString(R.string.error_file_not_found_or_moved), Toast.LENGTH_SHORT).show()
            return
        }
        
        val filePathList = currentPdfFiles.map { it.path }
        val fileNameList = currentPdfFiles.map { it.name }
        
        Log.d("MainActivity", "Opening file: ${pdfFile.name} at actual index $actualIndex (clicked position was $position)")
        Log.d("MainActivity", "File list size: ${currentPdfFiles.size}")
        Log.d("MainActivity", "Actual file at index $actualIndex: ${currentPdfFiles[actualIndex].name}")
        
        // 지휘자 모드에서 파일 변경 브로드캐스트
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        if (globalCollaborationManager.getCurrentMode() == CollaborationMode.CONDUCTOR) {
            Log.d("MainActivity", "🎵 지휘자 모드: 파일 선택 브로드캐스트 - ${pdfFile.name}")
            globalCollaborationManager.addFileToServer(pdfFile.name, pdfFile.path)
            globalCollaborationManager.broadcastFileChange(pdfFile.name, 1) // 첫 페이지로
        }
        
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            // Use the reliable, just-in-time calculated index.
            putExtra(PdfViewerActivity.EXTRA_CURRENT_INDEX, actualIndex)
            putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_PATH_LIST, ArrayList(filePathList))
            putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_NAME_LIST, ArrayList(fileNameList))
        }
        startActivity(intent)
    }
    
    private fun getCurrentPdfFiles(): List<PdfFile> {
        val pdfFiles = mutableListOf<PdfFile>()
        
        // Load from app's external files directory (uploaded via web server)
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            appPdfDir.listFiles { file ->
                file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }?.forEach { file ->
                val pageCount = getPdfPageCount(file)
                pdfFiles.add(PdfFile(
                    name = file.name,
                    path = file.absolutePath,
                    lastModified = file.lastModified(),
                    size = file.length(),
                    pageCount = pageCount
                ))
            }
        }
        
        // 정렬은 PdfFileSorter 가 담당한다. 목록 순서는 표시 문제가 아니라 **정합성 문제**다 —
        // PdfViewerActivity 에 인덱스로 파일을 넘기므로 순서가 흔들리면 다른 파일이 열린다(#030~#032).
        // 자연 정렬(악보2 < 악보10) + 동률 없는 전순서로 그 흔들림을 없앤다.
        val sorted = PdfFileSorter.sort(pdfFiles, currentSortBy)
        pdfFiles.clear()
        pdfFiles.addAll(sorted)
        
        Log.d("MainActivity", "=== LOADED ${pdfFiles.size} PDF FILES FROM APP DIRECTORY ===")
        pdfFiles.forEachIndexed { index, file ->
            Log.d("MainActivity", "[$index] NAME: '${file.name}' PATH: '${file.path}' PAGES: ${file.pageCount}")
        }
        Log.d("MainActivity", "=== END FILE LIST ===")
        
        return pdfFiles
    }
    
    /**
     * Get page count from PDF file using PdfRenderer
     */
    private fun getPdfPageCount(file: File): Int {
        return try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val pageCount = pdfRenderer.pageCount
            
            pdfRenderer.close()
            fileDescriptor.close()
            
            pageCount
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting page count for ${file.name}", e)
            0
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // RecyclerView will handle these automatically
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                // Menu key reserved for future use
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onPause() {
        super.onPause()
        // Note: 웹서버 관리는 이제 설정 화면에서 담당
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleFileRequest()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clear collaboration callbacks to prevent memory leaks
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        globalCollaborationManager.setOnServerClientConnected { _, _ -> }
        globalCollaborationManager.setOnServerClientDisconnected { _ -> }
        globalCollaborationManager.setOnClientConnectionStatusChanged { _ -> }
        globalCollaborationManager.setOnFileChangeReceived { _, _ -> }
        
        // Force stop all collaboration modes to prevent port conflicts
        Log.d("MainActivity", "Forcing collaboration mode cleanup on app destruction")
        globalCollaborationManager.deactivateCollaborationMode()
    }
    
    fun refreshFileList() {
        loadPdfFiles()
    }
    
    private fun setupCollaborationCallbacks() {
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        
        // Set up file change callback for performer mode
        globalCollaborationManager.setOnFileChangeReceived { fileName, page ->
            runOnUiThread {
                handleRemoteFileChange(fileName, page)
            }
        }
        
        // Set up connection status callbacks
        globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 지휘자 모드: 연주자 연결됨 - $deviceName")
                updateCollaborationStatus()
                Toast.makeText(this, "연주자 '$deviceName'이 연결되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
        
        globalCollaborationManager.setOnServerClientDisconnected { clientId ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 지휘자 모드: 연주자 연결 해제됨")
                updateCollaborationStatus()
                Toast.makeText(this, "연주자 연결이 해제되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
        
        globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 연주자 모드: 연결 상태 콜백 - $isConnected")
                
                // 콜백 직후 실제 상태도 확인
                val actualConnected = globalCollaborationManager.isClientConnected()
                val conductorAddress = globalCollaborationManager.getConductorAddress()
                Log.d("MainActivity", "🎼 실제 연결 상태: $actualConnected, 지휘자 주소: $conductorAddress")
                
                // 즉시 상태 업데이트
                updateCollaborationStatus()
                
                // 추가로 약간 지연 후에도 상태 업데이트 (안전장치)
                binding.collaborationStatus.postDelayed({
                    Log.d("MainActivity", "🎼 200ms 후 추가 상태 업데이트 실행")
                    updateCollaborationStatus()
                }, 200)
                
                // 연결 성공 시에만 토스트 메시지 표시
                if (isConnected) {
                    Toast.makeText(this, "지휘자에 연결되었습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "지휘자와의 연결이 끊어졌습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun handleFileRequest() {
        // Check if we were called with a specific file request
        val requestedFile = intent.getStringExtra("requested_file")
        if (requestedFile != null) {
            Log.d("MainActivity", "🎼 연주자 모드: SettingsActivity로부터 파일 요청 받음 - $requestedFile")
            
            // Wait for file list to load, then try to open the requested file
            binding.recyclerView.post {
                val currentFiles = pdfAdapter.currentList
                if (currentFiles.isNotEmpty()) {
                    val fileIndex = currentFiles.indexOfFirst { it.name == requestedFile }
                    if (fileIndex >= 0) {
                        val pdfFile = currentFiles[fileIndex]
                        openPdfFile(pdfFile, fileIndex)
                    } else {
                        // File not found, try to download
                        val requestedPage = intent.getIntExtra("requested_page", 1)
                        handleRemoteFileChange(requestedFile, requestedPage)
                    }
                } else {
                    // File list not loaded yet, try again after a delay
                    binding.recyclerView.postDelayed({
                        val requestedPage = intent.getIntExtra("requested_page", 1)
                        handleRemoteFileChange(requestedFile, requestedPage)
                    }, 1000)
                }
            }
            
            // Clear the intent extras to prevent re-processing
            intent.removeExtra("requested_file")
            intent.removeExtra("requested_page")
        }
    }
    
    private fun handleRemoteFileChange(fileName: String, page: Int = 1) {
        Log.d("MainActivity", "🎼 연주자 모드: 파일 '$fileName' 변경 요청 받음 (페이지: $page) (MainActivity)")
        
        // Find the file in current list
        val currentFiles = pdfAdapter.currentList
        Log.d("MainActivity", "🎼 현재 파일 목록 크기: ${currentFiles.size}")
        
        // 파일 목록이 비어있으면 잠시 기다린 후 재시도
        if (currentFiles.isEmpty()) {
            Log.d("MainActivity", "🎼 파일 목록이 비어있음, 1초 후 재시도...")
            binding.recyclerView.postDelayed({
                handleRemoteFileChange(fileName, page)
            }, 1000)
            return
        }
        
        currentFiles.forEachIndexed { index, file ->
            Log.d("MainActivity", "🎼 [$index] ${file.name}")
        }
        
        val fileIndex = currentFiles.indexOfFirst { it.name == fileName }
        Log.d("MainActivity", "🎼 요청된 파일 '$fileName'의 인덱스: $fileIndex")
        
        if (fileIndex >= 0) {
            // File found - open it directly with target page
            val pdfFile = currentFiles[fileIndex]
            Log.d("MainActivity", "🎼 연주자 모드: 파일 '$fileName' 발견, PDF 뷰어로 이동 중...")
            
            // Pass the target page to PdfViewerActivity
            val intent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra("current_index", fileIndex)
                putExtra("target_page", page) // Pass target page
                putStringArrayListExtra("file_path_list", ArrayList(currentFiles.map { it.path }))
                putStringArrayListExtra("file_name_list", ArrayList(currentFiles.map { it.name }))
            }
            startActivity(intent)
            Log.d("MainActivity", "🎼 PdfViewerActivity 시작됨")
        } else {
            // File not found - try downloading from conductor
            Log.w("MainActivity", "🎼 연주자 모드: 파일 '$fileName' 을 찾을 수 없음, 다운로드 시도...")
            val conductorAddress = GlobalCollaborationManager.getInstance().getConductorAddress()
            
            if (conductorAddress.isNotEmpty()) {
                // Show download dialog and refresh file list after download
                showDownloadDialog(fileName, conductorAddress)
            } else {
                Toast.makeText(this, "요청된 파일을 찾을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showDownloadDialog(fileName: String, conductorAddress: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("파일 다운로드")
            .setMessage("'$fileName' 파일이 없습니다.\n지휘자로부터 다운로드하시겠습니까?")
            .setPositiveButton("다운로드") { _, _ ->
                downloadFileFromConductor(fileName, conductorAddress)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun downloadFileFromConductor(fileName: String, conductorAddress: String) {
        // Extract IP from address (format: "192.168.1.100:9090")
        val ipAddress = conductorAddress.split(":")[0]
        val fileServerUrl = "http://$ipAddress:8090"
        
        Log.d("MainActivity", "🎼 연주자 모드: 파일 다운로드 시작 - $fileServerUrl")
        
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("다운로드 중...")
            .setMessage("$fileName\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val downloadUrl = "$fileServerUrl/download/$encodedFileName"
                Log.d("MainActivity", "Downloading from: $downloadUrl")
                
                val url = java.net.URL(downloadUrl)
                val connection = url.openConnection()
                connection.connect()
                
                val fileLength = connection.contentLength
                val input = connection.getInputStream()
                // Save to app's external files directory instead of public Downloads
                val appPdfDir = File(getExternalFilesDir(null), "PDFs")
                if (!appPdfDir.exists()) {
                    appPdfDir.mkdirs()
                }
                val downloadPath = File(appPdfDir, fileName)
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
                    Log.d("MainActivity", "🎼 연주자 모드: 파일 다운로드 완료 - $fileName")
                    Toast.makeText(this@MainActivity, "파일 다운로드 완료: $fileName", Toast.LENGTH_SHORT).show()
                    
                    // Trigger media scanner to make file visible
                    android.media.MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(downloadPath.absolutePath),
                        arrayOf("application/pdf"),
                        null
                    )
                    
                    // Refresh file list and try to open the downloaded file
                    loadPdfFiles()
                    
                    // Wait a bit for the file list to refresh, then try to open the file
                    binding.recyclerView.postDelayed({
                        val currentFiles = pdfAdapter.currentList
                        val fileIndex = currentFiles.indexOfFirst { it.name == fileName }
                        if (fileIndex >= 0) {
                            val pdfFile = currentFiles[fileIndex]
                            openPdfFile(pdfFile, fileIndex)
                        }
                    }, 500)
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "🎼 연주자 모드: 파일 다운로드 오류", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "다운로드 오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun setupSortButtons() {
        binding.sortByNameBtn.setOnClickListener {
            currentSortBy = PdfFileSorter.BY_NAME
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        binding.sortByTimeBtn.setOnClickListener {
            currentSortBy = PdfFileSorter.BY_TIME
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        // Set initial button states
        updateSortButtonStates()
    }
    
    private fun updateSortButtonStates() {
        // Update sort buttons with clear visual distinction for TV
        val selectedColor = ContextCompat.getColor(this, R.color.tv_primary)
        val unselectedColor = ContextCompat.getColor(this, R.color.tv_text_secondary)
        
        if (currentSortBy == PdfFileSorter.BY_NAME) {
            binding.sortByNameBtn.setTextColor(selectedColor)
            binding.sortByNameBtn.alpha = 1.0f
            binding.sortByTimeBtn.setTextColor(unselectedColor)
            binding.sortByTimeBtn.alpha = 0.8f
        } else {
            binding.sortByNameBtn.setTextColor(unselectedColor)
            binding.sortByNameBtn.alpha = 0.8f
            binding.sortByTimeBtn.setTextColor(selectedColor)
            binding.sortByTimeBtn.alpha = 1.0f
        }
    }
    
    private fun showDeleteConfirmationDialog(pdfFile: PdfFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("${pdfFile.name} 파일을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deletePdfFile(pdfFile)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun deletePdfFile(pdfFile: PdfFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(pdfFile.path)
                val deleted = file.delete()
                
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toast.makeText(this@MainActivity, "파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                        loadPdfFiles()
                    } else {
                        Toast.makeText(this@MainActivity, "파일 삭제 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "삭제 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun setupFileManagementButton() {
        binding.fileManageBtn.setOnClickListener {
            isFileManagementMode = !isFileManagementMode
            updateFileManagementUI()
            pdfAdapter.setFileManagementMode(isFileManagementMode)
        }
        
        // 초기 상태 설정
        updateFileManagementUI()
    }
    
    private fun updateFileManagementUI() {
        if (isFileManagementMode) {
            binding.fileManageBtn.text = "완료"
            binding.fileManageBtn.alpha = 1.0f
        } else {
            binding.fileManageBtn.text = "파일관리"
            binding.fileManageBtn.alpha = 0.7f
        }
    }
    
    private fun updateCollaborationStatus() {
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        val currentMode = globalCollaborationManager.getCurrentMode()
        
        when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                binding.collaborationStatus.text = "🎼 지휘자 모드 활성 (연결된 연주자: ${clientCount}명)"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
                binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_secondary)) // 녹색으로 변경
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                val conductorAddress = globalCollaborationManager.getConductorAddress()
                val conductorIp = if (conductorAddress.contains(":")) {
                    conductorAddress.split(":")[0]
                } else conductorAddress
                
                Log.d("MainActivity", "🎼 updateCollaborationStatus - 연주자 모드")
                Log.d("MainActivity", "🎼   isConnected: $isConnected")
                Log.d("MainActivity", "🎼   conductorAddress: '$conductorAddress'")
                Log.d("MainActivity", "🎼   conductorIp: '$conductorIp'")
                
                // 더 관대한 연결 상태 판단: isConnected가 true이면 연결된 것으로 간주
                if (isConnected) {
                    val displayIp = if (conductorIp.isNotEmpty()) conductorIp else "지휘자"
                    binding.collaborationStatus.text = "🎵 연주자 모드 (지휘자: $displayIp)"
                    binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_secondary)) // 녹색으로 변경
                    Log.d("MainActivity", "🎼 UI 업데이트: 연결됨 상태로 표시 - '$displayIp'")
                } else {
                    binding.collaborationStatus.text = "🎵 연주자 모드 (연결 끊김)"
                    binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_error))
                    Log.d("MainActivity", "🎼 UI 업데이트: 연결 끊김 상태로 표시")
                }
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.text = "합주 모드: 비활성"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
                binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary))
            }
        }
        
        Log.d("MainActivity", "협업 상태 업데이트: $currentMode")
    }
    
    private fun addFadeInAnimations() {
        // Initially hide all animated elements
        binding.headerSection.alpha = 0f
        binding.controlStrip.alpha = 0f
        binding.recyclerView.alpha = 0f
        
        // Animate elements sequentially
        binding.headerSection.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(100)
            .start()
        
        binding.controlStrip.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(200)
            .start()
        
        binding.recyclerView.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(300)
            .start()
    }
}