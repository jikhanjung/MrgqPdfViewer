package com.mrgq.pdfviewer

import android.content.Context
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

class CollaborationServerManager(
    private val context: Context? = null
) {
    
    private var webSocketServer: SimpleWebSocketServer? = null
    private val connectedClients = ConcurrentHashMap<String, WebSocket>()
    private val clientDeviceNames = ConcurrentHashMap<String, String>()
    private val gson = Gson()
    private val clientCounter = AtomicInteger(0)
    
    // Add explicit server state tracking
    private var serverStarted = false
    
    private var onClientConnected: ((String, String) -> Unit)? = null
    private var onVersionMismatch: ((String) -> Unit)? = null
    private var onClientDisconnected: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "CollaborationServer"
        private const val DEFAULT_PORT = 9090
    }
    
    fun startServer(port: Int = DEFAULT_PORT, useSSL: Boolean = false): Boolean {
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
            
            // Create WebSocket server with SSL support if enabled
            webSocketServer = if (useSSL && context != null) {
                Log.d(TAG, "Creating WSS (secure) WebSocket server")
                SimpleWebSocketServer(port, this, context, true)
            } else {
                Log.d(TAG, "Creating WS (regular) WebSocket server")
                SimpleWebSocketServer(port, this, null, false)
            }
            
            val started = webSocketServer?.start() ?: false
            
            if (started) {
                serverStarted = true
                val protocol = if (useSSL) "WSS" else "WS"
                Log.d(TAG, "$protocol WebSocket server started on port $port - serverStarted flag set to true")
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
    
    fun broadcastPageChange(pageNumber: Int, fileName: String, turnAt: Long? = null, roll: Boolean = false, rollStart: Boolean = false) {
        // 와이어 포맷은 CollaborationProtocol 이 단일 출처다 (키가 클라이언트와 갈라지지 않도록).
        val message = CollaborationProtocol.buildPageChange(pageNumber, fileName, turnAt, roll = roll, rollStart = rollStart)

        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted page change: page=$pageNumber, file=$fileName" + (turnAt?.let { ", turn_at=$it" } ?: ""))
    }
    
    fun broadcastFileChange(
        fileName: String,
        pageNumber: Int = 1,
        fileServerUrl: String? = null,
        sha256: String? = null,
    ) {
        val message = CollaborationProtocol.buildFileChange(fileName, pageNumber, fileServerUrl, sha256 = sha256)
        
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted file change: $fileName, page: $pageNumber" + if (fileServerUrl != null) " (with file server: $fileServerUrl)" else "")
    }
    
    /** 합주 메트로놈 상태 (#055) — 전체 상태를 보낸다 */
    /** 지휘자 마이크 추적의 지금 마디 (P10) */
    fun broadcastFollowPosition(fileName: String, measure: Int) {
        broadcastToClients(CollaborationProtocol.buildFollowPosition(fileName, measure).toString())
    }

    fun broadcastMetronomeRun(run: com.mrgq.pdfviewer.ensemble.EnsembleRun) {
        val message = CollaborationProtocol.buildMetronomeRun(run)
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted metronome run: ${run.runId} ${run.state} bpm=${run.timeline.bpm} file=${run.file}")
    }

    /** 합주 끝 — 연주자들도 합주 모드를 끝낸다 */
    fun broadcastEnsembleEnd() {
        broadcastToClients(CollaborationProtocol.buildEnsembleEnd().toString())
        Log.d(TAG, "Broadcasted ensemble end")
    }

    fun broadcastBackToList() {
        val message = CollaborationProtocol.buildBackToList()
        
        broadcastToClients(message.toString())
        Log.d(TAG, "Broadcasted back to list")
    }
    
    private fun broadcastToClients(message: String) {
        // Use the simple WebSocket server's broadcast method (already handles threading)
        webSocketServer?.broadcastMessage(message)
        
        // Note: OkHttp WebSocket 클라이언트 지원은 현재 사용하지 않으므로 제거
        // SimpleWebSocketServer만 사용하여 중복 메시지 전송 방지
    }
    
    @Synchronized
    internal fun addClient(clientId: String, webSocket: WebSocket, deviceName: String) {
        // IP 기반 ID로 지나친 중복 처리를 방지합니다.
        // 같은 IP에서 연결이 끚기고 다시 연결될 때만 기존 연결을 제거합니다.
        val existingClient = connectedClients[clientId]
        if (existingClient != null) {
            try {
                Log.d(TAG, "Replacing existing connection for $clientId")
                existingClient.close(1000, "Replaced by new connection")
                connectedClients.remove(clientId)
                clientDeviceNames.remove(clientId)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing existing connection $clientId", e)
            }
        }
        
        // 장치 이름에 고유 식별자 추가 (선택사항)
        val uniqueDeviceName = if (deviceName == "Android TV Device" || deviceName == "Unknown Device") {
            "$deviceName ($clientId)"
        } else {
            deviceName
        }
        
        connectedClients[clientId] = webSocket
        clientDeviceNames[clientId] = uniqueDeviceName
        
        Log.d(TAG, "Client connected: $clientId ($deviceName)")
        onClientConnected?.invoke(clientId, deviceName)
        
        // Send connection confirmation — 버전도 싣는다: 연주자는 이 메시지로도 버전을 비교했고, 없으면 "v0.2.5 이하"로 오판했다
        // (#061 뒤 v0.3.3 에서 발견: 샤오신패드 지휘자 · Z18TV 연주자)
        val response = JsonObject().apply {
            addProperty("action", "connect_response")
            addProperty("status", "success")
            addProperty("master_id", "master_device")
            addProperty("client_id", clientId)
            addProperty("app_version", BuildConfig.VERSION_NAME)
        }
        
        webSocket.send(response.toString())
    }
    
    @Synchronized
    internal fun removeClient(clientId: String) {
        connectedClients.remove(clientId)
        val deviceName = clientDeviceNames.remove(clientId)
        
        Log.d(TAG, "Client disconnected: $clientId ($deviceName)")
        onClientDisconnected?.invoke(clientId)
    }
    
    internal fun handleClientMessage(clientId: String, message: String) {
        // 시계 동기(#055): 받은 순간을 파싱보다 먼저 잡는다
        val receivedNs = System.nanoTime()
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val action = json.get("action")?.asString
            
            Log.d(TAG, "Received message from $clientId: $action")
            
            when (action) {
                CollaborationProtocol.ACTION_CLOCK_PING -> {
                    // 읽기 스레드에서 바로 답한다 — 지연이 한쪽으로만 붙지 않게
                    val t0 = CollaborationProtocol.parseClockPing(json) ?: return
                    connectedClients[clientId]?.send(CollaborationProtocol.buildClockPong(t0, receivedNs).toString())
                }
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
                    val appVersion = json.get("app_version")?.asString
                    val ensembleVersion = json.get("ensemble_version")?.asString // v0.7.1-beta.2 부터 (#086)
                    
                    Log.d(TAG, "Client $clientId connected: $deviceName ($appVersion)")
                    
                    // Send welcome response — app_version · ensemble_version 으로 연주자도 버전을 비교한다 (#061 · #086).
                    // server_version 은 옛 연주자와의 호환용(읽는 곳은 없다)
                    val response = JsonObject().apply {
                        addProperty("action", "connect_response")
                        addProperty("status", "success")
                        addProperty("server_version", "v0.1.5")
                        addProperty("app_version", BuildConfig.VERSION_NAME)
                        addProperty("ensemble_version", com.mrgq.pdfviewer.ensemble.EnsembleVersion.CURRENT)
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    connectedClients[clientId]?.send(response.toString())
                    com.mrgq.pdfviewer.ensemble.EnsembleVersion.check(BuildConfig.VERSION_NAME, appVersion, ensembleVersion)?.let {
                        Log.w(TAG, "연주자 $deviceName 와 버전이 다르다: $it")
                        onVersionMismatch?.invoke(com.mrgq.pdfviewer.ensemble.EnsembleVersion.conductorMessage(deviceName, it))
                    }
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

    /** 연주자와 앱 버전이 다르다 (#061) — 보일 안내문 */
    fun setOnVersionMismatch(callback: ((String) -> Unit)?) {
        onVersionMismatch = callback
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