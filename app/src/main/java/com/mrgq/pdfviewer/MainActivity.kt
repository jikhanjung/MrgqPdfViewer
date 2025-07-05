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
    }
    
    private fun setupRecyclerView() {
        pdfAdapter = PdfFileAdapter { pdfFile ->
            openPdfFile(pdfFile)
        }
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pdfAdapter
            setHasFixedSize(true)
        }
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, PERMISSION_REQUEST_CODE)
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
            val pdfFiles = mutableListOf<PdfFile>()
            
            // Load from app's external files directory
            val appPdfDir = File(getExternalFilesDir(null), "PDFs")
            if (appPdfDir.exists() && appPdfDir.isDirectory) {
                appPdfDir.listFiles { file ->
                    file.isFile && file.extension.equals("pdf", ignoreCase = true)
                }?.forEach { file ->
                    pdfFiles.add(PdfFile(file.name, file.absolutePath))
                }
            }
            
            // Also check Downloads directory if accessible
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir.exists() && downloadDir.canRead()) {
                    downloadDir.listFiles { file ->
                        file.isFile && file.extension.equals("pdf", ignoreCase = true)
                    }?.forEach { file ->
                        pdfFiles.add(PdfFile(file.name, file.absolutePath))
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Cannot access Downloads folder", e)
            }
            
            pdfFiles.sortBy { it.name }
            
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
                            binding.serverStatus.text = "서버 실행 중: http://$ip:8080"
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
    
    private fun openPdfFile(pdfFile: PdfFile) {
        // 최신 파일 목록을 다시 로드해서 동기화
        lifecycleScope.launch(Dispatchers.IO) {
            val currentPdfFiles = getCurrentPdfFiles()
            
            withContext(Dispatchers.Main) {
                val currentIndex = currentPdfFiles.indexOfFirst { it.path == pdfFile.path }
                
                if (currentIndex == -1) {
                    Toast.makeText(this@MainActivity, "파일을 찾을 수 없습니다: ${pdfFile.name}", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                
                val filePathList = currentPdfFiles.map { it.path }
                val fileNameList = currentPdfFiles.map { it.name }
                
                val intent = Intent(this@MainActivity, PdfViewerActivity::class.java).apply {
                    putExtra("pdf_path", pdfFile.path)
                    putExtra("pdf_name", pdfFile.name)
                    putExtra("current_index", currentIndex)
                    putStringArrayListExtra("file_path_list", ArrayList(filePathList))
                    putStringArrayListExtra("file_name_list", ArrayList(fileNameList))
                }
                startActivity(intent)
            }
        }
    }
    
    private fun getCurrentPdfFiles(): List<PdfFile> {
        val pdfFiles = mutableListOf<PdfFile>()
        
        // Load from app's external files directory
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            appPdfDir.listFiles { file ->
                file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }?.forEach { file ->
                pdfFiles.add(PdfFile(file.name, file.absolutePath))
            }
        }
        
        // Also check Downloads directory if accessible
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists() && downloadDir.canRead()) {
                downloadDir.listFiles { file ->
                    file.isFile && file.extension.equals("pdf", ignoreCase = true)
                }?.forEach { file ->
                    pdfFiles.add(PdfFile(file.name, file.absolutePath))
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Cannot access Downloads folder", e)
        }
        
        pdfFiles.sortBy { it.name }
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
}