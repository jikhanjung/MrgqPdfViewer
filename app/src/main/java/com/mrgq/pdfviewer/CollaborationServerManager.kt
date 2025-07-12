package com.mrgq.pdfviewer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CollaborationServerManager {
    
    private var webSocketServer: SimpleWebSocketServer? = null
    private val connectedClients = ConcurrentHashMap<String, WebSocket>()
    private val clientDeviceNames = ConcurrentHashMap<String, String>()
    private val gson = Gson()
    private val clientCounter = AtomicInteger(0)
    
    // Add explicit server state tracking
    private var serverStarted = false
    
    private var onClientConnected: ((String, String) -> Unit)? = null
    private var onClientDisconnected: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "CollaborationServer"
        private const val DEFAULT_PORT = 9090
    }
    
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        return try {
            if (webSocketServer != null) {
                Log.d(TAG, "Server already running, stopping first")
                stopServer()
                // Wait longer for the port to be released
                Thread.sleep(500)
            }
            
            // Force cleanup any lingering connections
            connectedClients.clear()
            clientDeviceNames.clear()
            
            // Check if port is available with extended retry and force cleanup
            var attempts = 0
            val maxAttempts = 5
            while (!isPortAvailable(port) && attempts < maxAttempts) {
                Log.w(TAG, "Port $port is not available, waiting... (attempt ${attempts + 1}/$maxAttempts)")
                
                if (attempts == 2) {
                    // Try to force cleanup at halfway point
                    Log.w(TAG, "Attempting force cleanup of resources...")
                    System.gc() // Suggest garbage collection
                    Thread.sleep(1000) // Longer wait
                } else {
                    Thread.sleep(800) // Longer individual waits
                }
                attempts++
            }
            
            if (!isPortAvailable(port)) {
                Log.e(TAG, "Port $port is still not available after $attempts attempts")
                Log.e(TAG, "This may indicate the previous server instance is still holding the port")
                
                // Try to find and kill any processes using the port (diagnostic info)
                Log.e(TAG, "Current webSocketServer reference: $webSocketServer")
                return false
            }
            
            webSocketServer = SimpleWebSocketServer(port, this)
            val started = webSocketServer?.start() ?: false
            
            if (started) {
                serverStarted = true
                Log.d(TAG, "WebSocket server started on port $port - serverStarted flag set to true")
            } else {
                serverStarted = false
                Log.e(TAG, "Failed to start WebSocket server - serverStarted flag set to false")
            }
            
            started
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            serverStarted = false
            false
        }
    }
    
    fun stopServer() {
        Log.d(TAG, "Stopping WebSocket server...")
        
        try {
            // First, disconnect all clients gracefully
            val clientIds = connectedClients.keys.toList()
            Log.d(TAG, "Disconnecting ${clientIds.size} clients...")
            
            clientIds.forEach { clientId ->
                try {
                    connectedClients[clientId]?.close(1000, "Server shutting down")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing client $clientId", e)
                }
            }
            
            // Clear client maps
            connectedClients.clear()
            clientDeviceNames.clear()
            
            // Shutdown WebSocket server
            webSocketServer?.let { server ->
                Log.d(TAG, "Shutting down WebSocket server...")
                server.shutdown()
                
                // Give it extra time to fully shutdown
                Thread.sleep(100)
                
                Log.d(TAG, "WebSocket server shutdown initiated")
            }
            
            webSocketServer = null
            serverStarted = false
            
            Log.d(TAG, "WebSocket server stopped successfully - serverStarted flag set to false")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            
            // Force cleanup even if there were errors
            webSocketServer = null
            serverStarted = false
            connectedClients.clear()
            clientDeviceNames.clear()
        }
    }
    
    fun isServerRunning(): Boolean {
        val hasServer = webSocketServer != null
        val isAlive = webSocketServer?.isAlive == true
        val result = serverStarted && hasServer && isAlive
        
        // Debug logging to understand why server status is incorrect
        Log.d(TAG, "서버 상태 확인 - serverStarted: $serverStarted, hasServer: $hasServer, isAlive: $isAlive, result: $result")
        
        return result
    }
    
    fun getConnectedClientCount(): Int {
        return webSocketServer?.getConnectedClientCount() ?: connectedClients.size
    }
    
    fun getConnectedClients(): List<Pair<String, String>> {
        return connectedClients.keys.map { clientId ->
            val deviceName = clientDeviceNames[clientId] ?: "Unknown Device"
            clientId to deviceName
        }
    }
    
    fun broadcastPageChange(pageNumber: Int, fileName: String) {
        val message = JsonObject().apply {
            addProperty("action", "page_change")
            addProperty("page", pageNumber)
            addProperty("file", fileName)
            addProperty("timestamp", System.currentTimeMillis())
        }
        
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted page change: page=$pageNumber, file=$fileName")
    }
    
    fun broadcastFileChange(fileName: String, pageNumber: Int = 1, fileServerUrl: String? = null) {
        val message = JsonObject().apply {
            addProperty("action", "file_change")
            addProperty("file", fileName)
            addProperty("page", pageNumber)
            addProperty("timestamp", System.currentTimeMillis())
            fileServerUrl?.let { addProperty("file_server_url", it) }
        }
        
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted file change: $fileName, page: $pageNumber" + if (fileServerUrl != null) " (with file server: $fileServerUrl)" else "")
    }
    
    fun broadcastBackToList() {
        val message = JsonObject().apply {
            addProperty("action", "back_to_list")
            addProperty("timestamp", System.currentTimeMillis())
        }
        
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted back to list")
    }
    
    private fun broadcastToClients(message: String) {
        // Use the simple WebSocket server's broadcast method (already handles threading)
        webSocketServer?.broadcastMessage(message)
        
        // Note: OkHttp WebSocket 클라이언트 지원은 현재 사용하지 않으므로 제거
        // SimpleWebSocketServer만 사용하여 중복 메시지 전송 방지
    }
    
    internal fun addClient(clientId: String, webSocket: WebSocket, deviceName: String) {
        connectedClients[clientId] = webSocket
        clientDeviceNames[clientId] = deviceName
        
        Log.d(TAG, "Client connected: $clientId ($deviceName)")
        onClientConnected?.invoke(clientId, deviceName)
        
        // Send connection confirmation
        val response = JsonObject().apply {
            addProperty("action", "connect_response")
            addProperty("status", "success")
            addProperty("master_id", "master_device")
            addProperty("client_id", clientId)
        }
        
        webSocket.send(response.toString())
    }
    
    internal fun removeClient(clientId: String) {
        connectedClients.remove(clientId)
        val deviceName = clientDeviceNames.remove(clientId)
        
        Log.d(TAG, "Client disconnected: $clientId ($deviceName)")
        onClientDisconnected?.invoke(clientId)
    }
    
    internal fun handleClientMessage(clientId: String, message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val action = json.get("action")?.asString
            
            Log.d(TAG, "Received message from $clientId: $action")
            
            when (action) {
                "heartbeat" -> {
                    // Respond to heartbeat
                    val response = JsonObject().apply {
                        addProperty("action", "heartbeat_response")
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    connectedClients[clientId]?.send(response.toString())
                }
                "client_connect" -> {
                    // Handle client connection message
                    val deviceId = json.get("device_id")?.asString ?: clientId
                    val deviceName = json.get("device_name")?.asString ?: "Unknown Device"
                    val appVersion = json.get("app_version")?.asString ?: "Unknown"
                    
                    Log.d(TAG, "Client $clientId connected: $deviceName ($appVersion)")
                    
                    // Send welcome response
                    val response = JsonObject().apply {
                        addProperty("action", "connect_response")
                        addProperty("status", "success")
                        addProperty("server_version", "v0.1.5")
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    connectedClients[clientId]?.send(response.toString())
                }
                "request_sync" -> {
                    // Client requesting current state
                    // This could be implemented to send current page/file state
                    Log.d(TAG, "Client $clientId requested sync")
                }
                else -> {
                    Log.w(TAG, "Unknown action from client $clientId: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from client $clientId", e)
        }
    }
    
    fun setOnClientConnected(callback: (String, String) -> Unit) {
        onClientConnected = callback
    }
    
    fun setOnClientDisconnected(callback: (String) -> Unit) {
        onClientDisconnected = callback
    }
    
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                // Successfully bound - port is available
                Log.d(TAG, "Port $port is available")
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Port $port is not available: ${e.message}")
            false
        }
    }
}