package com.mrgq.pdfviewer

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SimpleWebSocketServer(
    private val port: Int,
    private val serverManager: CollaborationServerManager
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
            
            Log.d(TAG, "Starting WebSocket server on port $port")
            
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
                    val clientId = "client_${clientCounter.incrementAndGet()}"
                    Log.d(TAG, "New client connection: $clientId")
                    
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
            val disconnectedClients = mutableListOf<String>()
            
            connections.forEach { (clientId, connection) ->
                if (!connection.sendMessage(message)) {
                    disconnectedClients.add(clientId)
                }
            }
            
            // Remove disconnected clients on main thread
            if (disconnectedClients.isNotEmpty()) {
                // Use handler to run on main thread if needed, or just run directly
                disconnectedClients.forEach { clientId ->
                    removeClient(clientId)
                }
            }
        }.start()
    }
    
    fun removeClient(clientId: String) {
        connections.remove(clientId)
        Log.d(TAG, "Removed client: $clientId")
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