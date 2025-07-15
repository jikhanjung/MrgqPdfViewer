package com.mrgq.pdfviewer

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import com.mrgq.pdfviewer.BuildConfig

class SimpleWebSocketServer(
    private val port: Int,
    private val serverManager: CollaborationServerManager,
    private val context: Context? = null,
    private val useSSL: Boolean = false
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
            serverSocket = if (useSSL && context != null) {
                createSSLServerSocket()
            } else {
                ServerSocket(port)
            }.apply {
                // Set socket timeout to 1 second for better shutdown responsiveness
                soTimeout = 1000
                Log.d(TAG, "Server socket timeout set to 1000ms for better shutdown")
            }
            isRunning = true
            
            val protocol = if (useSSL) "WSS" else "WS"
            Log.d(TAG, "Starting $protocol WebSocket server on port $port")
            
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
    
    private fun createSSLServerSocket(): ServerSocket {
        if (context == null) {
            throw IllegalStateException("Context is required for SSL")
        }
        
        try {
            Log.d(TAG, "Creating SSL ServerSocket...")
            
            // Load the keystore from raw resources
            val keyStore = KeyStore.getInstance("PKCS12")
            val keyStoreStream: InputStream = context.resources.openRawResource(R.raw.mrgqpdfviewer_keystore)
            val keystorePassword = BuildConfig.KEYSTORE_PASSWORD.toCharArray()
            
            keyStore.load(keyStoreStream, keystorePassword)
            keyStoreStream.close()
            
            // Create KeyManagerFactory
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, keystorePassword)
            
            // Create SSLContext
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, null)
            
            // Create SSL ServerSocket
            val sslServerSocketFactory: SSLServerSocketFactory = sslContext.serverSocketFactory
            val sslServerSocket = sslServerSocketFactory.createServerSocket(port) as SSLServerSocket
            
            // Configure SSL protocols and cipher suites
            sslServerSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            
            Log.d(TAG, "SSL ServerSocket created successfully")
            Log.d(TAG, "Enabled protocols: ${sslServerSocket.enabledProtocols.joinToString()}")
            Log.d(TAG, "Enabled cipher suites: ${sslServerSocket.enabledCipherSuites.size} suites")
            
            return sslServerSocket
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSL ServerSocket", e)
            throw e
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