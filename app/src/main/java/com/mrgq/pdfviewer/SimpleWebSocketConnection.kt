package com.mrgq.pdfviewer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okio.ByteString
import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.util.*

class SimpleWebSocketConnection(
    private val socket: Socket,
    private val clientId: String,
    private val server: SimpleWebSocketServer,
    private val serverManager: CollaborationServerManager
) {
    private var isConnected = false
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val gson = Gson()
    
    companion object {
        private const val TAG = "SimpleWebSocketConn"
        private const val WEBSOCKET_MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
    
    fun start() {
        try {
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
            
            // Handle WebSocket handshake
            if (performHandshake()) {
                isConnected = true
                serverManager.addClient(clientId, createDummyWebSocket(), "Android TV Device")
                
                // Start message reading thread
                Thread {
                    readMessages()
                }.start()
            } else {
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting connection for $clientId", e)
            close()
        }
    }
    
    private fun performHandshake(): Boolean {
        return try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val requestLine = reader.readLine()
            
            if (!requestLine.contains("GET") || !requestLine.contains("HTTP/1.1")) {
                return false
            }
            
            var websocketKey: String? = null
            var line: String?
            
            // Read headers
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Sec-WebSocket-Key:")) {
                    websocketKey = line!!.substring("Sec-WebSocket-Key:".length).trim()
                }
            }
            
            if (websocketKey == null) {
                return false
            }
            
            // Generate accept key
            val acceptKey = generateAcceptKey(websocketKey)
            
            // Send handshake response
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n" +
                    "\r\n"
            
            outputStream?.write(response.toByteArray())
            outputStream?.flush()
            
            Log.d(TAG, "WebSocket handshake completed for $clientId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during handshake for $clientId", e)
            false
        }
    }
    
    private fun generateAcceptKey(key: String): String {
        val combined = key + WEBSOCKET_MAGIC_STRING
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(combined.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }
    
    private fun readMessages() {
        val buffer = ByteArray(1024)
        
        try {
            while (isConnected && !socket.isClosed) {
                val bytesRead = inputStream?.read(buffer) ?: -1
                
                if (bytesRead == -1) {
                    break
                }
                
                if (bytesRead > 0) {
                    val frame = parseWebSocketFrame(buffer, bytesRead)
                    if (frame != null) {
                        handleMessage(frame)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading messages for $clientId", e)
        } finally {
            close()
        }
    }
    
    private fun parseWebSocketFrame(buffer: ByteArray, length: Int): String? {
        if (length < 2) return null
        
        try {
            val firstByte = buffer[0].toInt() and 0xFF
            val secondByte = buffer[1].toInt() and 0xFF
            
            val opcode = firstByte and 0x0F
            val masked = (secondByte and 0x80) != 0
            var payloadLength = secondByte and 0x7F
            
            var offset = 2
            
            // Handle extended payload length
            if (payloadLength == 126) {
                if (length < 4) return null
                payloadLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                offset = 4
            } else if (payloadLength == 127) {
                // For simplicity, we don't handle 64-bit payload length
                return null
            }
            
            // Handle masking
            val maskingKey = if (masked) {
                if (length < offset + 4) return null
                ByteArray(4) { buffer[offset + it] }
            } else null
            
            if (masked) offset += 4
            
            if (length < offset + payloadLength) return null
            
            // Extract payload
            val payload = ByteArray(payloadLength)
            System.arraycopy(buffer, offset, payload, 0, payloadLength)
            
            // Unmask if necessary
            if (masked && maskingKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
                }
            }
            
            // Handle different opcodes
            return when (opcode) {
                0x1 -> String(payload, Charsets.UTF_8) // Text frame
                0x8 -> { // Connection close
                    close()
                    null
                }
                0x9 -> { // Ping
                    sendPong(payload)
                    null
                }
                0xA -> null // Pong (ignore)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket frame for $clientId", e)
            return null
        }
    }
    
    private fun handleMessage(message: String) {
        Log.d(TAG, "Received message from $clientId: $message")
        serverManager.handleClientMessage(clientId, message)
    }
    
    fun sendMessage(message: String): Boolean {
        return try {
            if (!isConnected || socket.isClosed) {
                return false
            }
            
            val messageBytes = message.toByteArray(Charsets.UTF_8)
            val frame = createTextFrame(messageBytes)
            
            outputStream?.write(frame)
            outputStream?.flush()
            
            Log.d(TAG, "Sent message to $clientId: $message")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to $clientId", e)
            false
        }
    }
    
    private fun createTextFrame(payload: ByteArray): ByteArray {
        val payloadLength = payload.size
        
        return when {
            payloadLength < 126 -> {
                val frame = ByteArray(2 + payloadLength)
                frame[0] = 0x81.toByte() // FIN + Text frame
                frame[1] = payloadLength.toByte()
                System.arraycopy(payload, 0, frame, 2, payloadLength)
                frame
            }
            payloadLength < 65536 -> {
                val frame = ByteArray(4 + payloadLength)
                frame[0] = 0x81.toByte() // FIN + Text frame
                frame[1] = 126.toByte()
                frame[2] = (payloadLength shr 8).toByte()
                frame[3] = (payloadLength and 0xFF).toByte()
                System.arraycopy(payload, 0, frame, 4, payloadLength)
                frame
            }
            else -> {
                // For simplicity, we don't handle very large payloads
                ByteArray(0)
            }
        }
    }
    
    private fun sendPong(payload: ByteArray) {
        try {
            val frame = ByteArray(2 + payload.size)
            frame[0] = 0x8A.toByte() // FIN + Pong frame
            frame[1] = payload.size.toByte()
            System.arraycopy(payload, 0, frame, 2, payload.size)
            
            outputStream?.write(frame)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending pong to $clientId", e)
        }
    }
    
    fun close() {
        Log.d(TAG, "Closing connection for $clientId")
        isConnected = false
        
        // Remove from server collections first
        server.removeClient(clientId)
        serverManager.removeClient(clientId)
        
        // Force close the underlying socket
        try {
            if (!socket.isClosed) {
                socket.close()
                Log.d(TAG, "Socket closed for $clientId")
            } else {
                Log.d(TAG, "Socket was already closed for $clientId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket for $clientId", e)
        }
        
        Log.d(TAG, "Connection cleanup complete for $clientId")
    }
    
    private fun createDummyWebSocket(): okhttp3.WebSocket {
        // Create a dummy WebSocket that delegates to our simple implementation
        return object : okhttp3.WebSocket {
            override fun cancel() {
                close()
            }
            
            override fun close(code: Int, reason: String?): Boolean {
                close()
                return true
            }
            
            override fun queueSize(): Long = 0
            
            override fun request(): okhttp3.Request {
                throw NotImplementedError("Not needed for our implementation")
            }
            
            override fun send(text: String): Boolean {
                return sendMessage(text)
            }
            
            override fun send(bytes: ByteString): Boolean {
                return sendMessage(bytes.utf8())
            }
        }
    }
}