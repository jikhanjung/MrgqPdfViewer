package com.mrgq.pdfviewer.server

import android.content.Context
import android.os.Environment
import android.util.Log
import com.mrgq.pdfviewer.R
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

class WebServerManager private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: WebServerManager? = null
        
        fun getInstance(): WebServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebServerManager().also { INSTANCE = it }
            }
        }
    }
    
    private var server: PdfUploadServer? = null
    private var logCallback: ((String) -> Unit)? = null
    
    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
        server?.setLogCallback(callback)
    }
    
    fun clearLogCallback() {
        logCallback = null
        server?.setLogCallback(null)
    }
    
    fun startServer(context: Context, callback: (Boolean) -> Unit) {
        try {
            // Stop any existing server first
            stopServer()
            
            // Get port from settings
            val preferences = context.getSharedPreferences("pdf_viewer_prefs", Context.MODE_PRIVATE)
            val port = preferences.getInt("web_server_port", 8080)
            
            // Create and start new server
            server = PdfUploadServer(context, port)
            // Apply log callback if set
            logCallback?.let { server?.setLogCallback(it) }
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
        
        private var logCallback: ((String) -> Unit)? = null
        
        companion object {
            private const val TAG = "PdfUploadServer"
            private const val MAX_FILE_SIZE = 50 * 1024 * 1024 // 50MB
        }
        
        fun setLogCallback(callback: ((String) -> Unit)?) {
            logCallback = callback
        }
        
        private fun addLog(message: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val logMessage = "[$timestamp] $message"
            Log.d(TAG, logMessage)
            logCallback?.invoke(logMessage)
        }
        
        /** 설정 화면에 보이는 활동 로그 줄 — 앱 언어(리소스)를 따른다. 페이지 · 응답 글은 브라우저 언어([WebText]). */
        private fun addLog(id: Int, vararg args: Any) {
            addLog(context.getString(id, *args))
        }

        override fun serve(session: IHTTPSession): Response {
            val clientIp = session.headers["http-client-ip"] ?: session.headers["x-forwarded-for"] ?: "unknown"
            val lang = WebText.pickLanguage(session.headers["accept-language"])

            return when {
                session.method == Method.GET && session.uri == "/" -> {
                    addLog(R.string.web_log_page_opened, clientIp)
                    handleGetRequest(lang)
                }
                session.method == Method.GET && session.uri == "/list" -> {
                    addLog(R.string.web_log_list_requested, clientIp)
                    handleListRequest()
                }
                session.method == Method.DELETE && session.uri.startsWith("/delete/") -> {
                    val fileName = session.uri.substring("/delete/".length)
                    val decodedFileName = java.net.URLDecoder.decode(fileName, "UTF-8")
                    addLog(R.string.web_log_delete_requested, decodedFileName, clientIp)
                    handleDeleteRequest(session)
                }
                session.method == Method.DELETE && session.uri == "/deleteAll" -> {
                    addLog(R.string.web_log_delete_all_requested, clientIp)
                    handleDeleteAllRequest()
                }
                session.method == Method.POST && session.uri == "/upload" -> {
                    addLog(R.string.web_log_upload_started, clientIp)
                    handlePostRequest(session, lang)
                }
                else -> {
                    addLog(R.string.web_log_unknown_request, session.method.toString(), session.uri, clientIp)
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
        }
        
        private fun handleGetRequest(lang: WebLang): Response {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", UploadPage.html(lang))
        }
        
        private fun handlePostRequest(session: IHTTPSession, lang: WebLang): Response {
            val text = WebText.of(lang)
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                
                val uploadedFiles = mutableListOf<String>()
                
                // Process uploaded files with proper mapping
                val fileParams = files.keys.filter { it.startsWith("files") }.sortedBy { key ->
                    // Extract numeric part for proper sorting
                    if (key == "files") {
                        0 // "files" without number is first
                    } else {
                        key.removePrefix("files").toIntOrNull() ?: Int.MAX_VALUE
                    }
                }
                
                Log.d(TAG, "Sorted file params: $fileParams")
                
                for ((index, paramName) in fileParams.withIndex()) {
                    val tempLocation = files[paramName] ?: continue
                    Log.d(TAG, "Processing [$index] $paramName at $tempLocation")
                    
                    val tempFile = File(tempLocation)
                    
                    // Look for Base64 encoded filename with correct index
                    val filenameKey = "filename_$index"
                    val base64Filename = session.parms[filenameKey]
                    
                    var fileName = "uploaded_$index.pdf"
                    
                    if (!base64Filename.isNullOrBlank()) {
                        try {
                            // Decode Base64 filename
                            val decodedBytes = android.util.Base64.decode(base64Filename, android.util.Base64.DEFAULT)
                            fileName = String(decodedBytes, Charsets.UTF_8)
                            Log.d(TAG, "Decoded Base64 filename for $paramName: $fileName")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decode Base64 filename: $base64Filename", e)
                            fileName = "uploaded_$index.pdf"
                        }
                    }
                    
                    // Ensure it's a PDF file
                    if (!fileName.endsWith(".pdf", ignoreCase = true)) {
                        fileName = "$fileName.pdf"
                    }
                    
                    Log.d(TAG, "Saving $paramName as: $fileName")
                    addLog(R.string.web_log_saving, fileName)
                    
                    val targetFile = saveFile(tempFile, fileName)
                    if (targetFile != null) {
                        uploadedFiles.add(fileName)
                        addLog(R.string.web_log_saved, fileName, formatFileSize(targetFile.length()))
                    } else {
                        addLog(R.string.web_log_save_failed, fileName)
                    }
                    
                    tempFile.delete()
                }
                
                return if (uploadedFiles.isNotEmpty()) {
                    addLog(context.resources.getQuantityString(
                        R.plurals.web_log_upload_done, uploadedFiles.size, uploadedFiles.size, uploadedFiles.joinToString(", ")
                    ))
                    
                    // Refresh file list in MainActivity
                    (context as? com.mrgq.pdfviewer.MainActivity)?.runOnUiThread {
                        context.refreshFileList()
                    }
                    
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_PLAINTEXT,
                        text.getValue("resp_uploaded") + uploadedFiles.joinToString(", ")
                    )
                } else {
                    addLog(R.string.web_log_upload_no_pdf)
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        text.getValue("resp_no_pdf")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling upload", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    text.getValue("resp_upload_error") + e.message
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
                        com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(fileToDelete).delete() // 메모도 함께 (P11)
                        addLog(R.string.web_log_deleted, decodedFileName)
                        
                        // Refresh file list in MainActivity
                        (context as? com.mrgq.pdfviewer.MainActivity)?.runOnUiThread {
                            context.refreshFileList()
                        }
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "File deleted successfully")
                    } else {
                        addLog(R.string.web_log_delete_failed, decodedFileName)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to delete file")
                    }
                } else {
                    addLog(R.string.web_log_not_found, decodedFileName)
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
        
        private fun handleDeleteAllRequest(): Response {
            try {
                val pdfDir = File(context.getExternalFilesDir(null), "PDFs")
                var deletedCount = 0
                var failedCount = 0
                
                if (pdfDir.exists() && pdfDir.isDirectory) {
                    pdfDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
                    }?.forEach { file ->
                        if (file.delete()) {
                            com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(file).delete()
                            deletedCount++
                        } else {
                            failedCount++
                        }
                    }
                }
                
                // Refresh file list in MainActivity
                (context as? com.mrgq.pdfviewer.MainActivity)?.runOnUiThread {
                    context.refreshFileList()
                }
                
                return if (failedCount == 0) {
                    addLog(context.resources.getQuantityString(R.plurals.web_log_delete_all_done, deletedCount, deletedCount))
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_PLAINTEXT,
                        "Deleted $deletedCount files successfully"
                    )
                } else {
                    addLog(R.string.web_log_delete_all_partial, deletedCount, failedCount)
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Deleted $deletedCount files, failed to delete $failedCount files"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting all files", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Error: ${e.message}"
                )
            }
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