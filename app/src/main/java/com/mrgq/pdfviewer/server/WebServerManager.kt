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

class WebServerManager {
    
    private var server: PdfUploadServer? = null
    
    fun startServer(context: Context, callback: (Boolean) -> Unit) {
        try {
            if (server == null) {
                server = PdfUploadServer(context, 8080)
            }
            server?.start()
            callback(true)
        } catch (e: Exception) {
            Log.e("WebServerManager", "Failed to start server", e)
            callback(false)
        }
    }
    
    fun stopServer() {
        server?.stop()
        server = null
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
            return when (session.method) {
                Method.GET -> handleGetRequest()
                Method.POST -> handlePostRequest(session)
                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
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
                            max-width: 500px;
                            width: 100%;
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
                        .message {
                            margin-top: 20px;
                            padding: 12px;
                            border-radius: 6px;
                            text-align: center;
                        }
                        .success {
                            background-color: #30d158;
                            color: white;
                        }
                        .error {
                            background-color: #ff3b30;
                            color: white;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>PDF 파일 업로드</h1>
                        <form id="uploadForm" enctype="multipart/form-data">
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
                        <div id="message"></div>
                    </div>
                    
                    <script>
                        const fileInput = document.getElementById('fileInput');
                        const fileList = document.getElementById('fileList');
                        const submitBtn = document.querySelector('.submit-btn');
                        const uploadArea = document.querySelector('.upload-area');
                        const message = document.getElementById('message');
                        const form = document.getElementById('uploadForm');
                        
                        let selectedFiles = [];
                        
                        fileInput.addEventListener('change', handleFileSelect);
                        
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
                            selectedFiles.forEach(file => {
                                formData.append('files', file);
                            });
                            
                            submitBtn.disabled = true;
                            submitBtn.textContent = '업로드 중...';
                            message.innerHTML = '';
                            
                            try {
                                const response = await fetch('/upload', {
                                    method: 'POST',
                                    body: formData
                                });
                                
                                const result = await response.text();
                                
                                if (response.ok) {
                                    message.innerHTML = '<div class="message success">업로드 성공!</div>';
                                    selectedFiles = [];
                                    fileInput.value = '';
                                    updateFileList();
                                } else {
                                    message.innerHTML = '<div class="message error">업로드 실패: ' + result + '</div>';
                                }
                            } catch (error) {
                                message.innerHTML = '<div class="message error">업로드 중 오류 발생</div>';
                            } finally {
                                submitBtn.textContent = '업로드';
                            }
                        });
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
                for ((paramName, tempLocation) in files) {
                    if (paramName.startsWith("files")) {
                        val tempFile = File(tempLocation)
                        val fileName = session.parms[paramName] ?: "uploaded.pdf"
                        
                        if (fileName.endsWith(".pdf", ignoreCase = true)) {
                            val targetFile = saveFile(tempFile, fileName)
                            if (targetFile != null) {
                                uploadedFiles.add(fileName)
                            }
                        }
                        
                        tempFile.delete()
                    }
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
        
        private fun saveFile(tempFile: File, fileName: String): File? {
            return try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                
                val targetFile = File(downloadDir, fileName)
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