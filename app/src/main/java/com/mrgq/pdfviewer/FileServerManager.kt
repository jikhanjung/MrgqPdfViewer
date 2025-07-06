package com.mrgq.pdfviewer

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * HTTP 파일 서버 - 지휘자가 PDF 파일을 연주자에게 제공
 */
class FileServerManager(port: Int = 8090) : NanoHTTPD(port) {
    
    companion object {
        private const val TAG = "FileServerManager"
        private const val DEFAULT_PORT = 8090
        private const val MAX_CONCURRENT_DOWNLOADS = 3
    }
    
    private val availableFiles = mutableMapOf<String, String>() // fileName to filePath
    private var currentDownloads = 0
    
    fun addFile(fileName: String, filePath: String) {
        availableFiles[fileName] = filePath
        Log.d(TAG, "Added file: $fileName at $filePath")
    }
    
    fun removeFile(fileName: String) {
        availableFiles.remove(fileName)
        Log.d(TAG, "Removed file: $fileName")
    }
    
    fun clearFiles() {
        availableFiles.clear()
        Log.d(TAG, "Cleared all files")
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        Log.d(TAG, "Request: $method $uri (NanoHTTPD may have already decoded this)")
        
        return when {
            uri == "/" -> handleListFiles()
            uri.startsWith("/download/") -> handleDownloadFile(uri)
            uri.startsWith("/info/") -> handleFileInfo(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }
    
    private fun handleListFiles(): Response {
        val json = buildString {
            append("{\"files\":[")
            availableFiles.entries.forEachIndexed { index, (fileName, filePath) ->
                if (index > 0) append(",")
                val file = File(filePath)
                append("{")
                append("\"name\":\"$fileName\",")
                append("\"size\":${file.length()},")
                append("\"lastModified\":${file.lastModified()}")
                append("}")
            }
            append("]}")
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
    
    private fun handleDownloadFile(uri: String): Response {
        val encodedFileName = uri.removePrefix("/download/")
        // NanoHTTPD may have already decoded the URL, so try both versions
        var fileName = encodedFileName
        var filePath = availableFiles[fileName]
        
        // If not found, try decoding
        if (filePath == null) {
            try {
                fileName = java.net.URLDecoder.decode(encodedFileName, "UTF-8")
                filePath = availableFiles[fileName]
            } catch (e: Exception) {
                Log.w(TAG, "Error decoding filename: $e")
            }
        }
        
        Log.d(TAG, "Download request - Original: $encodedFileName, Used: $fileName")
        Log.d(TAG, "Available files: ${availableFiles.keys.joinToString(", ")}")
        
        if (filePath == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: $fileName")
        }
        
        val file = File(filePath)
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found on disk: $fileName")
        }
        
        return try {
            Log.d(TAG, "Serving file: $fileName (size: ${file.length()} bytes)")
            
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, "application/pdf", fis, file.length())
            response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
            response.addHeader("Access-Control-Allow-Origin", "*")
            // Add cache headers to reduce repeated downloads
            response.addHeader("Cache-Control", "public, max-age=3600")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: $fileName", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }
    
    private fun handleFileInfo(uri: String): Response {
        val fileName = uri.removePrefix("/info/")
        val filePath = availableFiles[fileName]
        
        if (filePath == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"File not found\"}")
        }
        
        val file = File(filePath)
        val json = """
            {
                "name": "$fileName",
                "size": ${file.length()},
                "exists": ${file.exists()},
                "lastModified": ${file.lastModified()}
            }
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}