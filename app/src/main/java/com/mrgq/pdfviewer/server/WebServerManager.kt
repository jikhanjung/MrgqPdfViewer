package com.mrgq.pdfviewer.server

import android.content.Context
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

class WebServerManager {
    
    private var server: PdfUploadServer? = null
    
    fun startServer(context: Context, callback: (Boolean) -> Unit) {
        try {
            // Stop any existing server first
            stopServer()
            
            // Create and start new server
            server = PdfUploadServer(context, 8080)
            server?.start()
            callback(true)
        } catch (e: Exception) {
            Log.e("WebServerManager", "Failed to start server", e)
            // Clean up on failure
            server = null
            callback(false)
        }
    }
    
    fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e("WebServerManager", "Error stopping server", e)
        } finally {
            server = null
        }
    }
    
    fun isServerRunning(): Boolean {
        return server?.isAlive == true
    }
    
    fun getServerAddress(): String {
        return getLocalIpAddress() ?: "localhost"
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        // Return IPv4 address
                        if (hostAddress?.contains(':') == false) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebServerManager", "Error getting IP address", e)
        }
        return null
    }
    
    private class PdfUploadServer(private val context: Context, port: Int) : NanoHTTPD(port) {
        
        companion object {
            private const val TAG = "PdfUploadServer"
            private const val MAX_FILE_SIZE = 50 * 1024 * 1024 // 50MB
        }
        
        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.GET && session.uri == "/" -> handleGetRequest()
                session.method == Method.GET && session.uri == "/list" -> handleListRequest()
                session.method == Method.DELETE && session.uri.startsWith("/delete/") -> handleDeleteRequest(session)
                session.method == Method.POST && session.uri == "/upload" -> handlePostRequest(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }
        
        private fun handleGetRequest(): Response {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>PDF 업로드</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            background-color: #1c1c1e;
                            color: #ffffff;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                            margin: 0;
                            padding: 20px;
                        }
                        .container {
                            background-color: #2c2c2e;
                            border-radius: 12px;
                            padding: 40px;
                            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.3);
                            max-width: 1200px;
                            width: 100%;
                        }
                        .main-content {
                            display: flex;
                            gap: 40px;
                            align-items: flex-start;
                        }
                        .upload-section {
                            flex: 1;
                            min-width: 450px;
                        }
                        .file-list-section {
                            flex: 1;
                            min-width: 450px;
                        }
                        h1 {
                            color: #007aff;
                            text-align: center;
                            margin-bottom: 30px;
                        }
                        .upload-area {
                            border: 2px dashed #007aff;
                            border-radius: 8px;
                            padding: 40px;
                            text-align: center;
                            transition: all 0.3s ease;
                        }
                        .upload-area:hover {
                            background-color: #3a3a3c;
                            border-color: #0051d5;
                        }
                        input[type="file"] {
                            display: none;
                        }
                        .upload-label {
                            display: inline-block;
                            padding: 12px 24px;
                            background-color: #007aff;
                            color: white;
                            border-radius: 6px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .upload-label:hover {
                            background-color: #0051d5;
                        }
                        .file-list {
                            margin-top: 20px;
                            padding: 10px;
                            background-color: #3a3a3c;
                            border-radius: 6px;
                            max-height: 200px;
                            overflow-y: auto;
                        }
                        .file-item {
                            padding: 8px;
                            margin: 4px 0;
                            background-color: #48484a;
                            border-radius: 4px;
                        }
                        .submit-btn {
                            width: 100%;
                            padding: 12px;
                            margin-top: 20px;
                            background-color: #30d158;
                            color: white;
                            border: none;
                            border-radius: 6px;
                            font-size: 16px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .submit-btn:hover {
                            background-color: #28a745;
                        }
                        .submit-btn:disabled {
                            background-color: #48484a;
                            cursor: not-allowed;
                        }
                        .message-container {
                            position: fixed;
                            bottom: 0;
                            left: 0;
                            right: 0;
                            z-index: 1000;
                            display: flex;
                            justify-content: center;
                            padding: 20px;
                            pointer-events: none;
                        }
                        .message {
                            padding: 20px 40px;
                            border-radius: 8px;
                            text-align: center;
                            font-weight: 500;
                            font-size: 18px;
                            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
                            animation: slideUp 0.3s ease;
                            pointer-events: auto;
                            max-width: 80%;
                        }
                        .message.fade-out {
                            animation: fadeOut 0.3s ease forwards;
                        }
                        .success {
                            background-color: #30d158;
                            color: #ffffff;
                        }
                        .error {
                            background-color: #ff3b30;
                            color: #ffffff;
                        }
                        @keyframes slideUp {
                            from {
                                opacity: 0;
                                transform: translateY(20px);
                            }
                            to {
                                opacity: 1;
                                transform: translateY(0);
                            }
                        }
                        @keyframes fadeOut {
                            from {
                                opacity: 1;
                                transform: translateY(0);
                            }
                            to {
                                opacity: 0;
                                transform: translateY(20px);
                            }
                        }
                        .file-list-container {
                            margin-top: 0;
                            padding: 0;
                            border-top: none;
                            height: 100%;
                        }
                        .file-list-section h2 {
                            color: #007aff;
                            margin-top: 0;
                            margin-bottom: 20px;
                        }
                        .file-list-header {
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            margin-bottom: 20px;
                        }
                        .file-list-container h2 {
                            color: #007aff;
                            margin: 0;
                        }
                        .sort-controls {
                            display: flex;
                            gap: 10px;
                        }
                        .sort-btn {
                            padding: 8px 16px;
                            background-color: #48484a;
                            color: #ffffff;
                            border: none;
                            border-radius: 4px;
                            cursor: pointer;
                            font-size: 14px;
                            transition: background-color 0.3s ease;
                        }
                        .sort-btn:hover {
                            background-color: #5a5a5c;
                        }
                        .sort-btn.active {
                            background-color: #007aff;
                        }
                        .current-file-item {
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            padding: 12px;
                            margin: 8px 0;
                            background-color: #3a3a3c;
                            border-radius: 6px;
                            transition: background-color 0.3s ease;
                        }
                        .current-file-item:hover {
                            background-color: #48484a;
                        }
                        .file-info {
                            flex: 1;
                        }
                        .file-name {
                            font-weight: bold;
                            margin-bottom: 4px;
                        }
                        .file-meta {
                            font-size: 14px;
                            color: #8e8e93;
                        }
                        .delete-btn {
                            padding: 8px 16px;
                            background-color: #ff3b30;
                            color: white;
                            border: none;
                            border-radius: 4px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .delete-btn:hover {
                            background-color: #ff2d20;
                        }
                        .empty-message {
                            text-align: center;
                            color: #8e8e93;
                            padding: 40px;
                        }
                        .file-list-content {
                            max-height: 600px;
                            overflow-y: auto;
                            padding-right: 10px;
                        }
                        .file-list-content::-webkit-scrollbar {
                            width: 8px;
                        }
                        .file-list-content::-webkit-scrollbar-track {
                            background: #48484a;
                            border-radius: 4px;
                        }
                        .file-list-content::-webkit-scrollbar-thumb {
                            background: #007aff;
                            border-radius: 4px;
                        }
                        @media (max-width: 1000px) {
                            .main-content {
                                flex-direction: column;
                            }
                            .upload-section, .file-list-section {
                                min-width: auto;
                            }
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>PDF 파일 관리</h1>
                        <div class="main-content">
                            <div class="file-list-section">
                                <div class="file-list-container">
                                    <div class="file-list-header">
                                        <h2>현재 파일 목록</h2>
                                        <div class="sort-controls">
                                            <button class="sort-btn active" id="sortByName">이름순</button>
                                            <button class="sort-btn" id="sortByTime">시간순</button>
                                        </div>
                                    </div>
                                    <div class="file-list-content">
                                        <div id="currentFiles"></div>
                                    </div>
                                </div>
                            </div>
                            
                            <div class="upload-section">
                                <h2>파일 업로드</h2>
                                <form id="uploadForm" enctype="multipart/form-data" accept-charset="UTF-8">
                                    <div class="upload-area">
                                        <label for="fileInput" class="upload-label">
                                            PDF 파일 선택
                                        </label>
                                        <input type="file" id="fileInput" name="files" accept=".pdf" multiple>
                                        <p>또는 파일을 여기로 드래그하세요</p>
                                    </div>
                                    <div id="fileList" class="file-list" style="display: none;"></div>
                                    <button type="submit" class="submit-btn" disabled>업로드</button>
                                </form>
                            </div>
                        </div>
                    </div>
                    
                    <div id="messageContainer" class="message-container"></div>
                    
                    <script>
                        const fileInput = document.getElementById('fileInput');
                        const fileList = document.getElementById('fileList');
                        const submitBtn = document.querySelector('.submit-btn');
                        const uploadArea = document.querySelector('.upload-area');
                        const messageContainer = document.getElementById('messageContainer');
                        const form = document.getElementById('uploadForm');
                        const sortByNameBtn = document.getElementById('sortByName');
                        const sortByTimeBtn = document.getElementById('sortByTime');
                        
                        let selectedFiles = [];
                        let currentFiles = [];
                        let currentSortBy = 'name';
                        let messageTimeout = null;
                        
                        function showMessage(text, isSuccess = true) {
                            // Clear any existing timeout
                            if (messageTimeout) {
                                clearTimeout(messageTimeout);
                            }
                            
                            // Create message element
                            const messageEl = document.createElement('div');
                            messageEl.className = 'message ' + (isSuccess ? 'success' : 'error');
                            messageEl.textContent = text;
                            
                            // Clear container and add new message
                            messageContainer.innerHTML = '';
                            messageContainer.appendChild(messageEl);
                            
                            // Auto-hide after 5 seconds
                            messageTimeout = setTimeout(() => {
                                messageEl.classList.add('fade-out');
                                setTimeout(() => {
                                    messageContainer.innerHTML = '';
                                }, 300);
                            }, 5000);
                        }
                        
                        fileInput.addEventListener('change', handleFileSelect);
                        
                        sortByNameBtn.addEventListener('click', () => {
                            currentSortBy = 'name';
                            updateSortButtons();
                            displayFileList();
                        });
                        
                        sortByTimeBtn.addEventListener('click', () => {
                            currentSortBy = 'time';
                            updateSortButtons();
                            displayFileList();
                        });
                        
                        function updateSortButtons() {
                            sortByNameBtn.classList.toggle('active', currentSortBy === 'name');
                            sortByTimeBtn.classList.toggle('active', currentSortBy === 'time');
                        }
                        
                        uploadArea.addEventListener('dragover', (e) => {
                            e.preventDefault();
                            uploadArea.style.backgroundColor = '#3a3a3c';
                        });
                        
                        uploadArea.addEventListener('dragleave', () => {
                            uploadArea.style.backgroundColor = '';
                        });
                        
                        uploadArea.addEventListener('drop', (e) => {
                            e.preventDefault();
                            uploadArea.style.backgroundColor = '';
                            handleFiles(e.dataTransfer.files);
                        });
                        
                        function handleFileSelect(e) {
                            handleFiles(e.target.files);
                        }
                        
                        function handleFiles(files) {
                            selectedFiles = Array.from(files).filter(file => file.type === 'application/pdf');
                            updateFileList();
                        }
                        
                        function updateFileList() {
                            if (selectedFiles.length === 0) {
                                fileList.style.display = 'none';
                                submitBtn.disabled = true;
                                return;
                            }
                            
                            fileList.style.display = 'block';
                            fileList.innerHTML = '<h3>선택된 파일:</h3>';
                            
                            selectedFiles.forEach(file => {
                                const fileItem = document.createElement('div');
                                fileItem.className = 'file-item';
                                fileItem.textContent = file.name + ' (' + formatFileSize(file.size) + ')';
                                fileList.appendChild(fileItem);
                            });
                            
                            submitBtn.disabled = false;
                        }
                        
                        function formatFileSize(bytes) {
                            if (bytes === 0) return '0 Bytes';
                            const k = 1024;
                            const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                            const i = Math.floor(Math.log(bytes) / Math.log(k));
                            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                        }
                        
                        form.addEventListener('submit', async (e) => {
                            e.preventDefault();
                            
                            if (selectedFiles.length === 0) return;
                            
                            const formData = new FormData();
                            selectedFiles.forEach((file, index) => {
                                // Use simple index-based naming for upload
                                const uploadName = 'file_' + index + '.pdf';
                                const newFile = new File([file], uploadName, {type: file.type});
                                formData.append('files', newFile);
                                // Send original filename as Base64 encoded
                                const base64Name = btoa(unescape(encodeURIComponent(file.name)));
                                formData.append('filename_' + index, base64Name);
                            });
                            
                            submitBtn.disabled = true;
                            submitBtn.textContent = '업로드 중...';
                            
                            try {
                                const response = await fetch('/upload', {
                                    method: 'POST',
                                    body: formData
                                });
                                
                                const result = await response.text();
                                
                                if (response.ok) {
                                    showMessage('✅ 업로드 성공!', true);
                                    selectedFiles = [];
                                    fileInput.value = '';
                                    updateFileList();
                                } else {
                                    showMessage('❌ 업로드 실패: ' + result, false);
                                }
                            } catch (error) {
                                showMessage('❌ 업로드 중 오류 발생', false);
                            } finally {
                                submitBtn.textContent = '업로드';
                                loadFileList();
                            }
                        });
                        
                        async function loadFileList() {
                            try {
                                const response = await fetch('/list');
                                currentFiles = await response.json();
                                displayFileList();
                            } catch (e) {
                                console.error('Failed to load file list:', e);
                            }
                        }
                        
                        function displayFileList() {
                            const currentFilesDiv = document.getElementById('currentFiles');
                            
                            if (currentFiles.length === 0) {
                                currentFilesDiv.innerHTML = '<div class="empty-message">업로드된 PDF 파일이 없습니다</div>';
                                return;
                            }
                            
                            // 정렬
                            const sortedFiles = [...currentFiles];
                            if (currentSortBy === 'name') {
                                sortedFiles.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
                            } else {
                                // 시간순 정렬 (최신순) - timestamp 사용
                                sortedFiles.sort((a, b) => b.modifiedTimestamp - a.modifiedTimestamp);
                            }
                            
                            currentFilesDiv.innerHTML = sortedFiles.map(file => `
                                <div class="current-file-item">
                                    <div class="file-info">
                                        <div class="file-name">` + file.name + `</div>
                                        <div class="file-meta">` + file.size + ` • ` + file.modified + `</div>
                                    </div>
                                    <button class="delete-btn" onclick="deleteFile('` + encodeURIComponent(file.name) + `')">삭제</button>
                                </div>
                            `).join('');
                        }
                        
                        async function deleteFile(encodedFileName) {
                            if (confirm('이 파일을 삭제하시겠습니까?')) {
                                try {
                                    const response = await fetch('/delete/' + encodedFileName, {
                                        method: 'DELETE'
                                    });
                                    
                                    if (response.ok) {
                                        showMessage('🗑️ 파일이 삭제되었습니다', true);
                                        loadFileList();
                                    } else {
                                        showMessage('❌ 파일 삭제 실패', false);
                                    }
                                } catch (e) {
                                    showMessage('❌ 삭제 중 오류 발생', false);
                                }
                            }
                        }
                        
                        // Load file list on page load
                        loadFileList();
                    </script>
                </body>
                </html>
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        
        private fun handlePostRequest(session: IHTTPSession): Response {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                
                val uploadedFiles = mutableListOf<String>()
                
                // Process uploaded files
                var fileIndex = 0
                for ((paramName, tempLocation) in files) {
                    Log.d(TAG, "Processing file: $paramName at $tempLocation")
                    
                    val tempFile = File(tempLocation)
                    
                    // Look for Base64 encoded filename
                    val filenameKey = "filename_$fileIndex"
                    val base64Filename = session.parms[filenameKey]
                    
                    var fileName = "uploaded.pdf"
                    
                    if (!base64Filename.isNullOrBlank()) {
                        try {
                            // Decode Base64 filename
                            val decodedBytes = android.util.Base64.decode(base64Filename, android.util.Base64.DEFAULT)
                            fileName = String(decodedBytes, Charsets.UTF_8)
                            Log.d(TAG, "Decoded Base64 filename: $fileName")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decode Base64 filename: $base64Filename", e)
                            fileName = "uploaded_$fileIndex.pdf"
                        }
                    } else {
                        fileName = "uploaded_$fileIndex.pdf"
                    }
                    
                    // Ensure it's a PDF file
                    if (!fileName.endsWith(".pdf", ignoreCase = true)) {
                        fileName = "$fileName.pdf"
                    }
                    
                    Log.d(TAG, "Saving file as: $fileName")
                    
                    fileIndex++
                    
                    val targetFile = saveFile(tempFile, fileName)
                    if (targetFile != null) {
                        uploadedFiles.add(fileName)
                    }
                    
                    tempFile.delete()
                }
                
                return if (uploadedFiles.isNotEmpty()) {
                    // Refresh file list in MainActivity
                    (context as? com.mrgq.pdfviewer.MainActivity)?.runOnUiThread {
                        context.refreshFileList()
                    }
                    
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_PLAINTEXT,
                        "성공적으로 업로드됨: ${uploadedFiles.joinToString(", ")}"
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "PDF 파일이 없습니다"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling upload", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "업로드 중 오류 발생: ${e.message}"
                )
            }
        }
        
        private fun handleListRequest(): Response {
            val pdfFiles = mutableListOf<Map<String, Any>>()
            
            // Get files from app directory
            val pdfDir = File(context.getExternalFilesDir(null), "PDFs")
            if (pdfDir.exists() && pdfDir.isDirectory) {
                pdfDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
                }?.forEach { file ->
                    pdfFiles.add(mapOf(
                        "name" to file.name,
                        "size" to formatFileSize(file.length()),
                        "modified" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(file.lastModified()),
                        "modifiedTimestamp" to file.lastModified()
                    ))
                }
            }
            
            // 기본적으로 이름순 정렬
            pdfFiles.sortBy { it["name"] as String }
            
            val json = pdfFiles.map { file ->
                """{"name":"${file["name"]}","size":"${file["size"]}","modified":"${file["modified"]}","modifiedTimestamp":${file["modifiedTimestamp"]}}"""
            }.joinToString(",", "[", "]")
            
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        }
        
        private fun handleDeleteRequest(session: IHTTPSession): Response {
            try {
                val fileName = session.uri.substring("/delete/".length)
                val decodedFileName = java.net.URLDecoder.decode(fileName, "UTF-8")
                
                val pdfDir = File(context.getExternalFilesDir(null), "PDFs")
                val fileToDelete = File(pdfDir, decodedFileName)
                
                return if (fileToDelete.exists() && fileToDelete.isFile && fileToDelete.name.endsWith(".pdf", ignoreCase = true)) {
                    if (fileToDelete.delete()) {
                        // Refresh file list in MainActivity
                        (context as? com.mrgq.pdfviewer.MainActivity)?.runOnUiThread {
                            context.refreshFileList()
                        }
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "File deleted successfully")
                    } else {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to delete file")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            if (bytes == 0L) return "0 B"
            val k = 1024
            val sizes = arrayOf("B", "KB", "MB", "GB")
            val i = floor(ln(bytes.toDouble()) / ln(k.toDouble())).toInt()
            return String.format("%.2f %s", bytes / k.toDouble().pow(i.toDouble()), sizes[i])
        }
        
        private fun saveFile(tempFile: File, fileName: String): File? {
            return try {
                // Use app's external files directory for Android 10+
                val pdfDir = File(context.getExternalFilesDir(null), "PDFs")
                if (!pdfDir.exists()) {
                    pdfDir.mkdirs()
                }
                
                // Keep original filename including Korean characters
                // Only remove dangerous characters like directory separators
                val safeFileName = fileName
                    .replace("/", "_")
                    .replace("\\", "_")
                    .replace("..", "_")
                
                val targetFile = File(pdfDir, safeFileName)
                tempFile.inputStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                Log.i(TAG, "File saved: ${targetFile.absolutePath}")
                targetFile
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file", e)
                null
            }
        }
    }
}