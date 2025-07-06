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
        
        setupRecyclerView()
        checkPermissions()
        setupWebServer()
        setupSortButtons()
        setupSettingsButton()
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
    
    override fun onDestroy() {
        super.onDestroy()
        webServerManager.stopServer()
    }
    
    fun refreshFileList() {
        loadPdfFiles()
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
}