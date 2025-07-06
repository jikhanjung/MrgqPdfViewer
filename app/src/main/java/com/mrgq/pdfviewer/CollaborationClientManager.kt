package com.mrgq.pdfviewer

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CollaborationClientManager(
    private val onPageChangeReceived: (Int, String) -> Unit,
    private val onFileChangeReceived: (String) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var conductorIpAddress: String = ""
    private var conductorPort: Int = 9090
    private var clientId: String = ""
    private var deviceName: String = ""
    
    private val gson = Gson()
    private val isConnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val maxReconnectAttempts = 5
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    
    private var heartbeatRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    
    companion object {
        private const val TAG = "CollaborationClient"
        private const val DEFAULT_PORT = 9090
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val CONNECTION_TIMEOUT = 10000L // 10 seconds
    }
    
    fun connectToConductor(ipAddress: String, port: Int = DEFAULT_PORT, deviceName: String = ""): Boolean {
        if (isConnected.get()) {
            Log.d(TAG, "Already connected to conductor")
            return true
        }
        
        this.conductorIpAddress = ipAddress
        this.conductorPort = port
        this.deviceName = deviceName.ifEmpty { "Android TV Device" }
        this.clientId = "client_${System.currentTimeMillis()}"
        
        return try {
            // Create OkHttpClient with timeouts
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
                .writeTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
            
            val request = Request.Builder()
                .url("ws://$ipAddress:$port")
                .build()
            
            webSocket = okHttpClient!!.newWebSocket(request, CollaborationWebSocketListener())
            
            Log.d(TAG, "Attempting to connect to conductor at $ipAddress:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to conductor", e)
            false
        }
    }
    
    fun disconnect() {
        try {
            isConnected.set(false)
            reconnectAttempts.set(0)
            
            // Cancel any pending reconnect attempts
            reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
            reconnectRunnable = null
            
            // Cancel heartbeat
            stopHeartbeat()
            
            // Close WebSocket
            webSocket?.close(1000, "Client disconnecting")
            webSocket = null
            
            // Shutdown OkHttpClient
            okHttpClient?.dispatcher?.executorService?.shutdown()
            okHttpClient = null
            
            Log.d(TAG, "Disconnected from conductor")
            onConnectionStatusChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
    
    fun isConnected(): Boolean {
        return isConnected.get()
    }
    
    fun getConductorAddress(): String {
        return if (conductorIpAddress.isNotEmpty()) "$conductorIpAddress:$conductorPort" else ""
    }
    
    fun sendHeartbeat() {
        if (!isConnected.get()) return
        
        val heartbeatMessage = JsonObject().apply {
            addProperty("action", "heartbeat")
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("client_id", clientId)
        }
        
        sendMessage(heartbeatMessage.toString())
    }
    
    fun requestSync() {
        if (!isConnected.get()) return
        
        val syncMessage = JsonObject().apply {
            addProperty("action", "request_sync")
            addProperty("client_id", clientId)
            addProperty("device_name", deviceName)
        }
        
        sendMessage(syncMessage.toString())
    }
    
    private fun sendMessage(message: String): Boolean {
        return try {
            webSocket?.send(message) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }
    
    private fun handleIncomingMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val action = json.get("action")?.asString
            
            Log.d(TAG, "Received message: $action")
            
            when (action) {
                "page_change" -> {
                    val page = json.get("page")?.asInt ?: 1
                    val file = json.get("file")?.asString ?: ""
                    onPageChangeReceived(page, file)
                }
                "file_change" -> {
                    val file = json.get("file")?.asString ?: ""
                    val page = json.get("page")?.asInt ?: 1
                    Log.d(TAG, "Received file change: file=$file, page=$page")
                    
                    // First call the file change callback, then page change
                    onFileChangeReceived(file)
                    
                    // After file change, set the specific page
                    if (page > 1) {
                        onPageChangeReceived(page, file)
                    }
                }
                "connect_response" -> {
                    val status = json.get("status")?.asString
                    if (status == "success") {
                        isConnected.set(true)
                        reconnectAttempts.set(0)
                        onConnectionStatusChanged(true)
                        startHeartbeat()
                        Log.d(TAG, "Successfully connected to conductor")
                    }
                }
                "heartbeat_response" -> {
                    Log.d(TAG, "Heartbeat acknowledged by conductor")
                }
                else -> {
                    Log.w(TAG, "Unknown action received: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming message: $message", e)
        }
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isConnected.get()) {
                    sendHeartbeat()
                    heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
                }
            }
        }
        
        heartbeatHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL)
    }
    
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }
    
    private fun attemptReconnection() {
        if (reconnectAttempts.get() >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnection attempts reached, giving up")
            return
        }
        
        val attempt = reconnectAttempts.incrementAndGet()
        val delay = (2000 * attempt).toLong() // Exponential backoff
        
        Log.d(TAG, "Attempting reconnection #$attempt in ${delay}ms")
        
        reconnectRunnable = Runnable {
            Log.d(TAG, "Reconnecting to conductor...")
            connectToConductor(conductorIpAddress, conductorPort, deviceName)
        }
        
        reconnectHandler.postDelayed(reconnectRunnable!!, delay)
    }
    
    private inner class CollaborationWebSocketListener : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened")
            
            // Send connection request
            val connectMessage = JsonObject().apply {
                addProperty("action", "client_connect")
                addProperty("device_id", clientId)
                addProperty("device_name", deviceName)
                addProperty("app_version", "v0.1.5") // This should be dynamic
            }
            
            webSocket.send(connectMessage.toString())
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncomingMessage(text)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket connection closing: $code - $reason")
            isConnected.set(false)
            onConnectionStatusChanged(false)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket connection closed: $code - $reason")
            isConnected.set(false)
            onConnectionStatusChanged(false)
            
            // Attempt reconnection if not intentionally closed
            if (code != 1000) {
                attemptReconnection()
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket connection failed", t)
            isConnected.set(false)
            onConnectionStatusChanged(false)
            
            // Attempt reconnection on failure
            attemptReconnection()
        }
    }
}