package com.mrgq.pdfviewer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.net.*
import java.nio.charset.StandardCharsets

/**
 * 지휘자 발견 서비스
 * UDP 브로드캐스트를 사용하여 같은 네트워크의 지휘자를 자동 발견
 */
class ConductorDiscovery {
    
    companion object {
        private const val TAG = "ConductorDiscovery"
        private const val DISCOVERY_PORT = 9091
        private const val BROADCAST_INTERVAL = 2000L // 2초마다 브로드캐스트
        private const val LISTEN_TIMEOUT = 1000 // 1초 타임아웃
        private const val MAX_DISCOVERY_TIME = 12000L // 최대 12초 발견 시도
    }
    
    private val gson = Gson()
    private var discoveryJob: Job? = null
    private var broadcastJob: Job? = null
    private var listenSocket: DatagramSocket? = null
    
    data class ConductorInfo(
        val name: String,
        val ipAddress: String,
        val port: Int,
        val timestamp: Long
    )
    
    /**
     * 지휘자 브로드캐스트 서비스 시작 (지휘자가 실행)
     */
    fun startConductorBroadcast(conductorName: String, webSocketPort: Int = 9090): Boolean {
        return try {
            Log.d(TAG, "Starting conductor broadcast service: $conductorName")
            
            stopConductorBroadcast() // 기존 브로드캐스트 중지
            
            broadcastJob = CoroutineScope(Dispatchers.IO).launch {
                val socket = DatagramSocket().apply {
                    broadcast = true
                }
                
                val message = createConductorMessage(conductorName, webSocketPort)
                val data = message.toByteArray(StandardCharsets.UTF_8)
                
                Log.d(TAG, "Conductor broadcast message: $message")
                Log.d(TAG, "Conductor broadcast message size: ${data.size} bytes")
                
                try {
                    var broadcastCount = 0
                    while (isActive) {
                        // 브로드캐스트 주소로 전송
                        val broadcastAddresses = getBroadcastAddresses()
                        
                        Log.d(TAG, "Broadcasting to ${broadcastAddresses.size} addresses (broadcast #${++broadcastCount})")
                        
                        broadcastAddresses.forEach { address ->
                            try {
                                val packet = DatagramPacket(
                                    data, data.size,
                                    InetAddress.getByName(address), DISCOVERY_PORT
                                )
                                socket.send(packet)
                                Log.d(TAG, "✓ Sent conductor broadcast to $address:$DISCOVERY_PORT")
                            } catch (e: Exception) {
                                Log.w(TAG, "✗ Failed to send broadcast to $address", e)
                            }
                        }
                        
                        // Also try unicast to local network range as fallback
                        try {
                            val localIP = NetworkUtils.getLocalIpAddress()
                            if (localIP != "Unknown" && localIP.contains(".")) {
                                val parts = localIP.split(".")
                                if (parts.size == 4) {
                                    val networkBase = "${parts[0]}.${parts[1]}.${parts[2]}"
                                    // Send to a few common addresses in the network
                                    val commonAddresses = listOf("$networkBase.1", "$networkBase.255")
                                    commonAddresses.forEach { unicastAddress ->
                                        try {
                                            val packet = DatagramPacket(
                                                data, data.size,
                                                InetAddress.getByName(unicastAddress), DISCOVERY_PORT
                                            )
                                            socket.send(packet)
                                            Log.v(TAG, "✓ Sent conductor unicast to $unicastAddress:$DISCOVERY_PORT")
                                        } catch (e: Exception) {
                                            Log.v(TAG, "✗ Failed to send unicast to $unicastAddress", e)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.v(TAG, "Failed unicast fallback", e)
                        }
                        
                        delay(BROADCAST_INTERVAL)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in conductor broadcast", e)
                    }
                } finally {
                    socket.close()
                    Log.d(TAG, "Conductor broadcast socket closed")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start conductor broadcast", e)
            false
        }
    }
    
    /**
     * 지휘자 브로드캐스트 중지
     */
    fun stopConductorBroadcast() {
        Log.d(TAG, "Stopping conductor broadcast")
        broadcastJob?.cancel()
        broadcastJob = null
    }
    
    /**
     * 지휘자 발견 시작 (연주자가 실행)
     */
    fun startConductorDiscovery(
        onConductorFound: (ConductorInfo) -> Unit,
        onDiscoveryTimeout: () -> Unit
    ): Boolean {
        return try {
            Log.d(TAG, "Starting conductor discovery...")
            
            stopConductorDiscovery() // 기존 발견 중지
            
            discoveryJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    listenSocket = DatagramSocket(DISCOVERY_PORT).apply {
                        soTimeout = LISTEN_TIMEOUT
                    }
                    Log.d(TAG, "Successfully bound to port $DISCOVERY_PORT for discovery")
                    
                    val buffer = ByteArray(1024)
                    val startTime = System.currentTimeMillis()
                    val foundConductors = mutableSetOf<String>() // 중복 방지
                    
                    Log.d(TAG, "Listening for conductors on port $DISCOVERY_PORT")
                    
                    while (isActive && (System.currentTimeMillis() - startTime) < MAX_DISCOVERY_TIME) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            Log.v(TAG, "Waiting for discovery packet on port $DISCOVERY_PORT...")
                            
                            listenSocket?.receive(packet)
                            
                            val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                            val senderIP = packet.address.hostAddress
                            
                            Log.d(TAG, "Received discovery message from $senderIP: $message")
                            
                            val conductorInfo = parseConductorMessage(message, senderIP)
                            if (conductorInfo != null) {
                                val conductorKey = "${conductorInfo.ipAddress}:${conductorInfo.port}"
                                
                                if (!foundConductors.contains(conductorKey)) {
                                    foundConductors.add(conductorKey)
                                    Log.d(TAG, "Found new conductor: ${conductorInfo.name} at $conductorKey")
                                    
                                    withContext(Dispatchers.Main) {
                                        onConductorFound(conductorInfo)
                                    }
                                } else {
                                    Log.v(TAG, "Duplicate conductor ignored: $conductorKey")
                                }
                            } else {
                                Log.w(TAG, "Failed to parse conductor message: $message")
                            }
                        } catch (e: SocketTimeoutException) {
                            // 타임아웃은 정상 - 계속 대기
                            Log.v(TAG, "Discovery timeout - continue listening...")
                        } catch (e: Exception) {
                            if (isActive) {
                                Log.w(TAG, "Error receiving discovery packet", e)
                            }
                        }
                    }
                    
                    Log.d(TAG, "Discovery timeout after ${MAX_DISCOVERY_TIME}ms")
                    withContext(Dispatchers.Main) {
                        onDiscoveryTimeout()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in conductor discovery", e)
                    withContext(Dispatchers.Main) {
                        onDiscoveryTimeout()
                    }
                } finally {
                    listenSocket?.close()
                    listenSocket = null
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start conductor discovery", e)
            false
        }
    }
    
    /**
     * 지휘자 발견 중지
     */
    fun stopConductorDiscovery() {
        Log.d(TAG, "Stopping conductor discovery")
        
        // Cancel discovery job first
        discoveryJob?.cancel()
        discoveryJob = null
        
        // Force close socket with timeout handling
        try {
            listenSocket?.let { socket ->
                if (!socket.isClosed) {
                    Log.d(TAG, "Forcefully closing discovery socket on port $DISCOVERY_PORT")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing discovery socket", e)
        } finally {
            listenSocket = null
        }
        
        // Give time for port to be released
        Thread.sleep(100)
        Log.d(TAG, "Discovery socket cleanup complete")
    }
    
    /**
     * 모든 발견 서비스 중지
     */
    fun stopAll() {
        stopConductorBroadcast()
        stopConductorDiscovery()
    }
    
    /**
     * 네트워크 연결 테스트 (디버깅용)
     */
    fun testNetworkConnectivity(): String {
        val results = StringBuilder()
        
        try {
            // 1. 로컬 IP 주소 확인
            val localIP = NetworkUtils.getLocalIpAddress()
            results.append("Local IP: $localIP\n")
            
            // 2. 브로드캐스트 주소 확인
            val broadcastAddresses = getBroadcastAddresses()
            results.append("Broadcast addresses: $broadcastAddresses\n")
            
            // 3. 소켓 바인딩 테스트
            try {
                val testSocket = DatagramSocket(DISCOVERY_PORT + 100).apply {
                    reuseAddress = true
                }
                results.append("Socket binding: SUCCESS\n")
                testSocket.close()
            } catch (e: Exception) {
                results.append("Socket binding: FAILED - ${e.message}\n")
            }
            
            // 4. 네트워크 인터페이스 확인
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var interfaceCount = 0
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    interfaceCount++
                    results.append("Network interface: ${networkInterface.name} (${networkInterface.displayName})\n")
                }
            }
            results.append("Active network interfaces: $interfaceCount\n")
            
        } catch (e: Exception) {
            results.append("Network test error: ${e.message}\n")
        }
        
        return results.toString()
    }
    
    private fun createConductorMessage(conductorName: String, webSocketPort: Int): String {
        val message = JsonObject().apply {
            addProperty("type", "conductor_announcement")
            addProperty("conductor_name", conductorName)
            addProperty("websocket_port", webSocketPort)
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("ip_address", NetworkUtils.getLocalIpAddress())
        }
        return message.toString()
    }
    
    private fun parseConductorMessage(message: String, senderIP: String): ConductorInfo? {
        return try {
            val json = gson.fromJson(message, JsonObject::class.java)
            
            if (json.get("type")?.asString == "conductor_announcement") {
                ConductorInfo(
                    name = json.get("conductor_name")?.asString ?: "Unknown Conductor",
                    ipAddress = senderIP ?: json.get("ip_address")?.asString ?: "",
                    port = json.get("websocket_port")?.asInt ?: 9090,
                    timestamp = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse conductor message: $message", e)
            null
        }
    }
    
    private fun getBroadcastAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcastAddress = interfaceAddress.broadcast
                    if (broadcastAddress != null) {
                        val broadcastIP = broadcastAddress.hostAddress
                        if (broadcastIP != null && !addresses.contains(broadcastIP)) {
                            addresses.add(broadcastIP)
                            Log.d(TAG, "Found broadcast address: $broadcastIP")
                        }
                    }
                }
            }
            
            // 현재 IP 주소를 기반으로 계산된 브로드캐스트 주소 추가
            val localIP = NetworkUtils.getLocalIpAddress()
            if (localIP != "Unknown" && localIP.contains(".")) {
                try {
                    val parts = localIP.split(".")
                    if (parts.size == 4) {
                        // 일반적인 /24 네트워크 가정
                        val calculatedBroadcast = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                        if (!addresses.contains(calculatedBroadcast)) {
                            addresses.add(calculatedBroadcast)
                            Log.d(TAG, "Added calculated broadcast address: $calculatedBroadcast")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to calculate broadcast address from $localIP", e)
                }
            }
            
            // 기본 브로드캐스트 주소 추가
            if (!addresses.contains("255.255.255.255")) {
                addresses.add("255.255.255.255")
                Log.d(TAG, "Added default broadcast address: 255.255.255.255")
            }
            
            Log.d(TAG, "Final broadcast addresses: $addresses")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast addresses", e)
            addresses.clear()
            addresses.add("255.255.255.255") // 폴백
        }
        
        return addresses
    }
}