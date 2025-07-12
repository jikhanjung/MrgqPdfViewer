package com.mrgq.pdfviewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.adapter.PdfFileAdapter
import com.mrgq.pdfviewer.databinding.ActivityMainBinding
import com.mrgq.pdfviewer.model.PdfFile
import com.mrgq.pdfviewer.server.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfAdapter: PdfFileAdapter
    private val webServerManager = WebServerManager()
    private var currentSortBy = "name" // "name" or "time"
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Note: Global collaboration manager는 Application에서 이미 초기화됨
        
        setupRecyclerView()
        checkPermissions()
        setupWebServer()
        setupSortButtons()
        setupSettingsButton()
        setupCollaborationCallbacks()
        
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
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Android TV에서는 MANAGE_ALL_FILES_ACCESS_PERMISSION 설정 화면이 없음
                // 권한 요청 대신 사용자에게 수동 설정 안내
                Toast.makeText(
                    this,
                    "저장소 권한이 필요합니다. 설정 > 앱 > MrgqPdfViewer > 권한에서 '파일 및 미디어'를 허용해주세요.",
                    Toast.LENGTH_LONG
                ).show()
                // 권한이 없어도 일단 파일 목록을 로드 시도
                loadPdfFiles()
            } else {
                loadPdfFiles()
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_CODE)
            } else {
                loadPdfFiles()
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadPdfFiles()
            } else {
                Toast.makeText(this, "파일 접근 권한이 필요합니다", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                loadPdfFiles()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Re-register collaboration callbacks when MainActivity resumes
        Log.d("MainActivity", "onResume - 협업 콜백 재등록")
        setupCollaborationCallbacks()
        
        // Update collaboration status display
        updateCollaborationStatus()
        
        // Check if file list needs refresh (e.g., after downloading file in PdfViewerActivity)
        val preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        val needsRefresh = preferences.getBoolean("refresh_file_list", false)
        
        if (needsRefresh) {
            preferences.edit().putBoolean("refresh_file_list", false).apply()
            loadPdfFiles()
        }
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
    
    private fun setupWebServer() {
        binding.serverToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                webServerManager.startServer(this) { success ->
                    runOnUiThread {
                        if (success) {
                            val ip = webServerManager.getServerAddress()
                            val preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
                            val port = preferences.getInt("web_server_port", 8080)
                            binding.serverStatus.text = "서버 실행 중: http://$ip:$port"
                            Toast.makeText(this, "웹 서버가 시작되었습니다", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.serverToggle.isChecked = false
                            Toast.makeText(this, "서버 시작 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                webServerManager.stopServer()
                binding.serverStatus.text = "서버 중지됨"
                Toast.makeText(this, "웹 서버가 중지되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun openPdfFile(pdfFile: PdfFile, position: Int) {
        // 어댑터에서 전달받은 position을 직접 사용
        val currentPdfFiles = pdfAdapter.currentList
        
        if (position < 0 || position >= currentPdfFiles.size) {
            Toast.makeText(this, "잘못된 파일 위치입니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        val filePathList = currentPdfFiles.map { it.path }
        val fileNameList = currentPdfFiles.map { it.name }
        
        Log.d("MainActivity", "Opening file: ${pdfFile.name} at position $position")
        Log.d("MainActivity", "File list size: ${currentPdfFiles.size}")
        Log.d("MainActivity", "Actual file at position $position: ${currentPdfFiles[position].name}")
        
        // 지휘자 모드에서 파일 변경 브로드캐스트
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        if (globalCollaborationManager.getCurrentMode() == CollaborationMode.CONDUCTOR) {
            Log.d("MainActivity", "🎵 지휘자 모드: 파일 선택 브로드캐스트 - ${pdfFile.name}")
            globalCollaborationManager.addFileToServer(pdfFile.name, pdfFile.path)
            globalCollaborationManager.broadcastFileChange(pdfFile.name, 1) // 첫 페이지로
        }
        
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra("current_index", position)  // position을 직접 사용
            putStringArrayListExtra("file_path_list", ArrayList(filePathList))
            putStringArrayListExtra("file_name_list", ArrayList(fileNameList))
        }
        startActivity(intent)
    }
    
    private fun getCurrentPdfFiles(): List<PdfFile> {
        val pdfFiles = mutableListOf<PdfFile>()
        val addedPaths = mutableSetOf<String>() // 중복 방지용
        
        // Load from app's external files directory
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            appPdfDir.listFiles { file ->
                file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }?.forEach { file ->
                val canonicalPath = file.canonicalPath
                if (!addedPaths.contains(canonicalPath)) {
                    pdfFiles.add(PdfFile(
                        name = file.name,
                        path = file.absolutePath,
                        lastModified = file.lastModified(),
                        size = file.length()
                    ))
                    addedPaths.add(canonicalPath)
                }
            }
        }
        
        // Also check Downloads directory if accessible
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists() && downloadDir.canRead()) {
                downloadDir.listFiles { file ->
                    file.isFile && file.extension.equals("pdf", ignoreCase = true)
                }?.forEach { file ->
                    val canonicalPath = file.canonicalPath
                    if (!addedPaths.contains(canonicalPath)) {
                        pdfFiles.add(PdfFile(
                            name = file.name,
                            path = file.absolutePath,
                            lastModified = file.lastModified(),
                            size = file.length()
                        ))
                        addedPaths.add(canonicalPath)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Cannot access Downloads folder", e)
        }
        
        // Sort based on current selection
        when (currentSortBy) {
            "name" -> pdfFiles.sortBy { it.name }
            "time" -> pdfFiles.sortByDescending { it.lastModified }
        }
        
        Log.d("MainActivity", "=== LOADED ${pdfFiles.size} UNIQUE PDF FILES ===")
        pdfFiles.forEachIndexed { index, file ->
            Log.d("MainActivity", "[$index] NAME: '${file.name}' PATH: '${file.path}'")
        }
        Log.d("MainActivity", "=== END FILE LIST ===")
        
        return pdfFiles
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // RecyclerView will handle these automatically
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                binding.serverToggle.isChecked = !binding.serverToggle.isChecked
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onPause() {
        super.onPause()
        // Stop server when app goes to background
        if (binding.serverToggle.isChecked) {
            webServerManager.stopServer()
            binding.serverToggle.isChecked = false
            binding.serverStatus.text = "서버 중지됨"
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleFileRequest()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop web server
        webServerManager.stopServer()
        
        // Force stop all collaboration modes to prevent port conflicts
        Log.d("MainActivity", "Forcing collaboration mode cleanup on app destruction")
        GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
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
            currentSortBy = "name"
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        binding.sortByTimeBtn.setOnClickListener {
            currentSortBy = "time"
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        // Set initial button states
        updateSortButtonStates()
    }
    
    private fun updateSortButtonStates() {
        binding.sortByNameBtn.backgroundTintList = 
            android.content.res.ColorStateList.valueOf(
                if (currentSortBy == "name") 
                    ContextCompat.getColor(this, R.color.tv_primary)
                else 
                    ContextCompat.getColor(this, R.color.tv_surface)
            )
        
        binding.sortByTimeBtn.backgroundTintList = 
            android.content.res.ColorStateList.valueOf(
                if (currentSortBy == "time") 
                    ContextCompat.getColor(this, R.color.tv_primary)
                else 
                    ContextCompat.getColor(this, R.color.tv_surface)
            )
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
    
    private fun updateCollaborationStatus() {
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        val currentMode = globalCollaborationManager.getCurrentMode()
        
        when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                binding.collaborationStatus.text = "협업 모드: 지휘자 (연결된 기기: ${clientCount}대)"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                val status = if (isConnected) "연결됨" else "연결 끊김"
                binding.collaborationStatus.text = "협업 모드: 연주자 ($status)"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.visibility = android.view.View.GONE
            }
        }
        
        Log.d("MainActivity", "협업 상태 업데이트: $currentMode")
    }
}