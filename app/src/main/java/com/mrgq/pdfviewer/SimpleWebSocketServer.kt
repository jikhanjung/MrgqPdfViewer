package com.mrgq.pdfviewer

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 합주 WebSocket 서버 — 평문 WS (WSS 는 v0.1.8 에 시도했다가 인증서 호환 문제로 롤백, 쓰지 않던 TLS 코드 · 키는 지웠다) */
class SimpleWebSocketServer(
    private val port: Int,
    private val serverManager: CollaborationServerManager,
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val connections = ConcurrentHashMap<String, SimpleWebSocketConnection>()
    private val clientCounter = AtomicInteger(0)
    private var acceptThread: Thread? = null
    
    companion object {
        private const val TAG = "SimpleWebSocketServer"
    }
    
    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(port).apply {
                // Set socket timeout to 1 second for better shutdown responsiveness
                soTimeout = 1000
                Log.d(TAG, "Server socket timeout set to 1000ms for better shutdown")
            }
            isRunning = true
            
            Log.d(TAG, "Starting WS WebSocket server on port $port")
            
            // Start accepting connections in a separate thread
            acceptThread = Thread {
                acceptConnections()
            }.apply {
                name = "WebSocketAcceptThread-$port"
                start()
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }
    
    private fun acceptConnections() {
        Log.d(TAG, "Accept thread started with socket timeout handling")
        
        while (isRunning && serverSocket?.isClosed == false) {
            try {
                val clientSocket = serverSocket?.accept()
                if (clientSocket != null && isRunning) { // Check isRunning again
                    // IP 주소 기반으로 클라이언트 ID 생성
                    val clientAddress = clientSocket.remoteSocketAddress
                    val clientIp = if (clientAddress is java.net.InetSocketAddress) {
                        clientAddress.address.hostAddress
                    } else {
                        "unknown_${clientCounter.incrementAndGet()}"
                    }
                    val clientId = clientIp ?: "client_${clientCounter.incrementAndGet()}"
                    
                    Log.d(TAG, "New client connection: $clientId (IP: $clientIp)")
                    
                    val connection = SimpleWebSocketConnection(
                        clientSocket,
                        clientId,
                        this,
                        serverManager
                    )
                    
                    connections[clientId] = connection
                    connection.start()
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Socket timeout - this is normal, just check isRunning and continue
                // Log.v(TAG, "Accept timeout - checking shutdown signal...") // Verbose logging
                continue
            } catch (e: java.net.SocketException) {
                // Socket was closed during accept() - this is expected during shutdown
                if (isRunning) {
                    Log.w(TAG, "Socket exception during accept (may be normal shutdown)", e)
                } else {
                    Log.d(TAG, "Socket closed during shutdown - this is expected")
                }
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Unexpected error accepting connection", e)
                }
                break
            }
        }
        
        Log.d(TAG, "Accept thread terminated gracefully")
    }
    
    fun shutdown() {
        Log.d(TAG, "Shutting down WebSocket server...")
        isRunning = false
        
        // Close all connections first with extra care
        val connectionIds = connections.keys.toList()
        Log.d(TAG, "Closing ${connectionIds.size} WebSocket connections...")
        
        connectionIds.forEach { connectionId ->
            try {
                val connection = connections[connectionId]
                connection?.close()
                Log.d(TAG, "Closed connection: $connectionId")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing connection $connectionId", e)
            }
        }
        connections.clear()
        
        // Force close server socket to interrupt accept() call
        try {
            serverSocket?.let { socket ->
                if (!socket.isClosed) {
                    Log.d(TAG, "Closing server socket...")
                    socket.close()
                    Log.d(TAG, "Server socket closed - this will interrupt accept() call")
                }
            }
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
            serverSocket = null // Force null even if close failed
        }
        
        // Wait for accept thread to terminate
        acceptThread?.let { thread ->
            try {
                Log.d(TAG, "Waiting for accept thread to terminate...")
                thread.join(1000) // Wait up to 1 second
                if (thread.isAlive) {
                    Log.w(TAG, "Accept thread still alive after 1 second, interrupting...")
                    thread.interrupt()
                    thread.join(500) // Wait another 500ms
                    if (thread.isAlive) {
                        Log.e(TAG, "Accept thread still alive after interrupt!")
                    } else {
                        Log.d(TAG, "Accept thread terminated after interrupt")
                    }
                } else {
                    Log.d(TAG, "Accept thread terminated gracefully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for accept thread", e)
            }
        }
        acceptThread = null
        
        // Give extra time for the port to be fully released
        Log.d(TAG, "Waiting for port release...")
        Thread.sleep(500) // Increased wait time
        
        Log.d(TAG, "WebSocket server shutdown complete")
    }
    
    fun broadcastMessage(message: String) {
        Log.d(TAG, "Broadcasting message to ${connections.size} clients: $message")
        
        // Run network operations in background thread
        Thread {
            val disconnectedClients = mutableListOf<Pair<String, SimpleWebSocketConnection>>()
            
            // 연결 목록의 복사본을 사용하여 동시성 문제 방지
            val connectionsCopy = connections.toMap()
            
            connectionsCopy.forEach { (clientId, connection) ->
                try {
                    if (!connection.sendMessage(message)) {
                        disconnectedClients.add(clientId to connection)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting to client $clientId", e)
                    disconnectedClients.add(clientId to connection)
                }
            }
            
            // Remove disconnected clients
            if (disconnectedClients.isNotEmpty()) {
                disconnectedClients.forEach { (clientId, connection) ->
                    removeClient(clientId, connection)
                }
            }
        }.start()
    }
    
    /**
     * 그 연결이 아직 이 IP 의 연결일 때만 뺀다 — 같은 IP 가 다시 연결하면 [connections] 는 새 연결로 바뀌고,
     * 뒤늦게 닫히는 옛 연결이 새 연결을 지우면 연주자가 연결된 채 방송을 못 받는다
     */
    fun removeClient(clientId: String, connection: SimpleWebSocketConnection) {
        if (connections.remove(clientId, connection)) {
            Log.d(TAG, "Removed client: $clientId")
        } else {
            Log.d(TAG, "옛 연결만 정리: $clientId (지금 연결은 그대로)")
        }
    }
    
    fun getConnectedClientCount(): Int {
        return connections.size
    }
    
    val isAlive: Boolean
        get() {
            val running = isRunning
            val socketNotClosed = serverSocket?.isClosed == false
            val result = running && socketNotClosed
            Log.d(TAG, "isAlive 체크 - isRunning: $running, socketNotClosed: $socketNotClosed, result: $result")
            return result
        }
}