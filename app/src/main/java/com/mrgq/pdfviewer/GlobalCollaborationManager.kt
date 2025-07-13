package com.mrgq.pdfviewer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast

/**
 * 전역 협업 매니저 - 앱 전체에서 협업 상태를 관리
 * 액티비티 생명주기와 독립적으로 협업 서버/클라이언트를 유지
 */
class GlobalCollaborationManager private constructor() {
    
    companion object {
        private const val TAG = "GlobalCollaboration"
        
        @Volatile
        private var INSTANCE: GlobalCollaborationManager? = null
        
        fun getInstance(): GlobalCollaborationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlobalCollaborationManager().also { INSTANCE = it }
            }
        }
    }
    
    private var collaborationServerManager: CollaborationServerManager? = null
    private var collaborationClientManager: CollaborationClientManager? = null
    private var fileServerManager: FileServerManager? = null
    private var conductorDiscovery: ConductorDiscovery? = null
    private var currentMode = CollaborationMode.NONE
    private var preferences: SharedPreferences? = null
    
    // Callback functions for UI updates
    private var onServerClientConnected: ((String, String) -> Unit)? = null
    private var onServerClientDisconnected: ((String) -> Unit)? = null
    private var onClientConnectionStatusChanged: ((Boolean) -> Unit)? = null
    private var onPageChangeReceived: ((Int, String) -> Unit)? = null
    private var onFileChangeReceived: ((String, Int) -> Unit)? = null // Updated to include page
    private var onBackToListReceived: (() -> Unit)? = null
    private var onConductorDiscovered: ((ConductorDiscovery.ConductorInfo) -> Unit)? = null
    private var onDiscoveryTimeout: (() -> Unit)? = null
    private var onAutoConnectionResult: ((Boolean, String) -> Unit)? = null
    
    private var isInitialized = false
    
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "GlobalCollaborationManager already initialized - skipping")
            return
        }
        
        preferences = context.getSharedPreferences("pdf_viewer_prefs", Context.MODE_PRIVATE)
        isInitialized = true
        
        // Restore collaboration mode from preferences
        val savedMode = preferences?.getString("collaboration_mode", "none") ?: "none"
        currentMode = when (savedMode) {
            "conductor" -> CollaborationMode.CONDUCTOR
            "performer" -> CollaborationMode.PERFORMER
            else -> CollaborationMode.NONE
        }
        
        // Re-initialize collaboration if it was active
        when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                // Check if server is already running before trying to start a new one
                if (collaborationServerManager?.isServerRunning() == true) {
                    Log.d(TAG, "Conductor mode server is already running - no need to restore")
                } else {
                    Log.d(TAG, "Restoring conductor mode")
                    initializeConductorMode()
                }
            }
            CollaborationMode.PERFORMER -> {
                Log.d(TAG, "Restoring performer mode")
                initializePerformerMode()
            }
            else -> {
                Log.d(TAG, "No collaboration mode to restore")
            }
        }
    }
    
    fun getCurrentMode(): CollaborationMode = currentMode
    
    fun activateConductorMode(): Boolean {
        Log.d(TAG, "Activating conductor mode")
        
        // Force cleanup any existing collaboration with extra verification
        deactivateCollaborationMode()
        
        // Extra wait to ensure cleanup is complete
        Thread.sleep(1000)
        
        currentMode = CollaborationMode.CONDUCTOR
        preferences?.edit()?.putString("collaboration_mode", "conductor")?.apply()
        
        return initializeConductorMode()
    }
    
    fun activatePerformerMode(): Boolean {
        Log.d(TAG, "Activating performer mode")
        
        // Stop any existing collaboration
        deactivateCollaborationMode()
        
        currentMode = CollaborationMode.PERFORMER
        preferences?.edit()?.putString("collaboration_mode", "performer")?.apply()
        
        return initializePerformerMode()
    }
    
    fun deactivateCollaborationMode() {
        Log.d(TAG, "Deactivating collaboration mode - Force cleanup initiated")
        
        currentMode = CollaborationMode.NONE
        preferences?.edit()?.putString("collaboration_mode", "none")?.apply()
        
        // Force stop collaboration server with multiple attempts
        collaborationServerManager?.let { server ->
            try {
                Log.d(TAG, "Attempting to stop collaboration server...")
                server.stopServer()
                
                // Wait longer to ensure port is fully released
                Thread.sleep(500)
                
                // Double-check server is actually stopped
                var retryCount = 0
                while (server.isServerRunning() && retryCount < 3) {
                    Log.w(TAG, "Server still running after shutdown attempt ${retryCount + 1}, trying again...")
                    server.stopServer()
                    Thread.sleep(800)
                    retryCount++
                }
                
                if (server.isServerRunning()) {
                    Log.e(TAG, "Server still running after $retryCount shutdown attempts!")
                    Log.e(TAG, "This indicates a resource leak - server instance will be abandoned")
                } else {
                    Log.d(TAG, "Collaboration server shutdown verified")
                }
                
                Log.d(TAG, "Collaboration server shutdown process complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping collaboration server", e)
            }
        }
        collaborationServerManager = null
        
        // Force stop file server with multiple attempts
        fileServerManager?.let { server ->
            try {
                Log.d(TAG, "Attempting to stop file server...")
                server.stop()
                
                // Give extra time for file server port release
                Thread.sleep(200)
                
                Log.d(TAG, "File server shutdown complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping file server", e)
            }
        }
        fileServerManager = null
        
        // Force disconnect client
        collaborationClientManager?.let { client ->
            try {
                Log.d(TAG, "Attempting to disconnect collaboration client...")
                client.disconnect()
                Log.d(TAG, "Collaboration client disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting collaboration client", e)
            }
        }
        collaborationClientManager = null
        
        // Stop conductor discovery
        conductorDiscovery?.stopAll()
        conductorDiscovery = null
        
        // Clear callbacks
        clearCallbacks()
        
        Log.d(TAG, "Collaboration mode deactivation complete")
    }
    
    private fun initializeConductorMode(): Boolean {
        return try {
            collaborationServerManager = CollaborationServerManager().apply {
                setOnClientConnected { clientId, deviceName ->
                    Log.d(TAG, "🎯 Server: Client connected: $clientId ($deviceName)")
                    onServerClientConnected?.let { callback ->
                        Log.d(TAG, "🎯 Invoking server client connected callback")
                        callback(clientId, deviceName)
                    } ?: Log.w(TAG, "🎯 No server client connected callback set!")
                }
                
                setOnClientDisconnected { clientId ->
                    Log.d(TAG, "🎯 Server: Client disconnected: $clientId")
                    onServerClientDisconnected?.let { callback ->
                        Log.d(TAG, "🎯 Invoking server client disconnected callback")
                        callback(clientId)
                    } ?: Log.w(TAG, "🎯 No server client disconnected callback set!")
                }
            }
            
            val serverStarted = collaborationServerManager?.startServer() ?: false
            if (!serverStarted) {
                Log.e(TAG, "Failed to start collaboration server")
                return false
            }
            
            // Start file server for conductor mode
            try {
                fileServerManager = FileServerManager(8090)
                fileServerManager?.start()
                Log.d(TAG, "File server started on port 8090")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start file server", e)
            }
            
            // Start conductor discovery broadcast
            try {
                conductorDiscovery = ConductorDiscovery()
                val deviceName = android.os.Build.MODEL ?: "Android TV"
                conductorDiscovery?.startConductorBroadcast("$deviceName (지휘자)", 9090)
                Log.d(TAG, "Conductor discovery broadcast started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start conductor broadcast", e)
            }
            
            serverStarted
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing conductor mode", e)
            false
        }
    }
    
    private fun initializePerformerMode(): Boolean {
        return try {
            collaborationClientManager = CollaborationClientManager(
                onPageChangeReceived = { page, file ->
                    Log.d(TAG, "Page change received: $page, $file")
                    onPageChangeReceived?.invoke(page, file)
                },
                onFileChangeReceived = { file, page ->
                    Log.d(TAG, "File change received: $file, page: $page")
                    onFileChangeReceived?.invoke(file, page)
                },
                onConnectionStatusChanged = { isConnected ->
                    Log.d(TAG, "Connection status changed: $isConnected")
                    onClientConnectionStatusChanged?.invoke(isConnected)
                },
                onBackToListReceived = {
                    Log.d(TAG, "Back to list received")
                    onBackToListReceived?.invoke()
                }
            )
            
            // Initialize conductor discovery for performer mode
            conductorDiscovery = ConductorDiscovery()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing performer mode", e)
            false
        }
    }
    
    // Server operations
    fun getServerManager(): CollaborationServerManager? = collaborationServerManager
    
    fun isServerRunning(): Boolean {
        return collaborationServerManager?.isServerRunning() == true
    }
    
    fun getConnectedClientCount(): Int {
        return collaborationServerManager?.getConnectedClientCount() ?: 0
    }
    
    fun getConnectedClients(): List<Pair<String, String>> {
        return collaborationServerManager?.getConnectedClients() ?: emptyList()
    }
    
    fun broadcastPageChange(pageNumber: Int, fileName: String) {
        collaborationServerManager?.broadcastPageChange(pageNumber, fileName)
    }
    
    fun broadcastFileChange(fileName: String, pageNumber: Int = 1) {
        val fileServerUrl = getFileServerUrl()
        collaborationServerManager?.broadcastFileChange(fileName, pageNumber, fileServerUrl)
    }
    
    fun broadcastBackToList() {
        collaborationServerManager?.broadcastBackToList()
    }
    
    // File server operations
    fun addFileToServer(fileName: String, filePath: String) {
        Log.d(TAG, "Adding file to server: $fileName at $filePath")
        if (fileServerManager == null) {
            Log.e(TAG, "File server is not running!")
        } else {
            fileServerManager?.addFile(fileName, filePath)
        }
    }
    
    fun removeFileFromServer(fileName: String) {
        fileServerManager?.removeFile(fileName)
    }
    
    fun clearFilesFromServer() {
        fileServerManager?.clearFiles()
    }
    
    fun getFileServerUrl(): String? {
        return if (fileServerManager != null && isServerRunning()) {
            "http://${NetworkUtils.getLocalIpAddress()}:8090"
        } else {
            null
        }
    }
    
    // Client operations
    fun getClientManager(): CollaborationClientManager? = collaborationClientManager
    
    fun isClientConnected(): Boolean {
        return collaborationClientManager?.isConnected() == true
    }
    
    fun connectToConductor(ipAddress: String, port: Int = 9090, deviceName: String = "Android TV Device"): Boolean {
        return collaborationClientManager?.connectToConductor(ipAddress, port, deviceName) == true
    }
    
    fun disconnectFromConductor() {
        collaborationClientManager?.disconnect()
    }
    
    fun getConductorAddress(): String {
        return collaborationClientManager?.getConductorAddress() ?: ""
    }
    
    // Callback setters with logging for server callbacks
    fun setOnServerClientConnected(callback: (String, String) -> Unit) {
        Log.d(TAG, "🎯 Setting server client connected callback")
        onServerClientConnected = callback
    }
    
    fun setOnServerClientDisconnected(callback: (String) -> Unit) {
        Log.d(TAG, "🎯 Setting server client disconnected callback")
        onServerClientDisconnected = callback
    }
    
    fun setOnClientConnectionStatusChanged(callback: (Boolean) -> Unit) {
        onClientConnectionStatusChanged = callback
    }
    
    fun setOnPageChangeReceived(callback: (Int, String) -> Unit) {
        onPageChangeReceived = callback
    }
    
    fun setOnFileChangeReceived(callback: (String, Int) -> Unit) {
        onFileChangeReceived = callback
    }
    
    fun setOnBackToListReceived(callback: () -> Unit) {
        onBackToListReceived = callback
    }
    
    /**
     * Set conductor discovery callback - this is critical for MainActivity integration
     */
    fun setOnConductorDiscovered(callback: (ConductorDiscovery.ConductorInfo) -> Unit) {
        Log.d(TAG, "🎯 Setting conductor discovered callback")
        onConductorDiscovered = callback
    }
    
    /**
     * Set discovery timeout callback
     */
    fun setOnDiscoveryTimeout(callback: () -> Unit) {
        Log.d(TAG, "🎯 Setting discovery timeout callback")
        onDiscoveryTimeout = callback
    }
    
    fun setOnAutoConnectionResult(callback: (Boolean, String) -> Unit) {
        onAutoConnectionResult = callback
    }
    
    // Conductor Discovery operations
    /**
     * Start conductor discovery with proper callback management
     * This method ensures callbacks are properly connected and managed
     */
    fun startConductorDiscovery(): Boolean {
        if (currentMode != CollaborationMode.PERFORMER) {
            Log.w(TAG, "Conductor discovery is only available in performer mode")
            return false
        }
        
        if (conductorDiscovery == null) {
            Log.e(TAG, "ConductorDiscovery is not initialized")
            return false
        }
        
        Log.d(TAG, "Starting conductor discovery with callbacks: " +
                "onConductorDiscovered=${if (onConductorDiscovered != null) "SET" else "NULL"}, " +
                "onDiscoveryTimeout=${if (onDiscoveryTimeout != null) "SET" else "NULL"}")
        
        return conductorDiscovery!!.startConductorDiscovery(
            onConductorFound = { conductorInfo ->
                Log.d(TAG, "🎯 Conductor discovered: ${conductorInfo.name} at ${conductorInfo.ipAddress}:${conductorInfo.port}")
                
                // Ensure callback is called on main thread
                onConductorDiscovered?.let { callback ->
                    Log.d(TAG, "🎯 Invoking discovery callback")
                    callback(conductorInfo)
                } ?: Log.w(TAG, "🎯 No discovery callback set!")
            },
            onDiscoveryTimeout = {
                Log.d(TAG, "🎯 Conductor discovery timeout")
                onDiscoveryTimeout?.let { callback ->
                    Log.d(TAG, "🎯 Invoking timeout callback")
                    callback()
                } ?: Log.w(TAG, "🎯 No timeout callback set!")
            }
        )
    }
    
    fun stopConductorDiscovery() {
        conductorDiscovery?.stopConductorDiscovery()
    }
    
    fun connectToDiscoveredConductor(conductorInfo: ConductorDiscovery.ConductorInfo): Boolean {
        val deviceName = android.os.Build.MODEL ?: "Android TV"
        return connectToConductor(conductorInfo.ipAddress, conductorInfo.port, "$deviceName (연주자)")
    }
    
    // Note: Auto-discovery method removed in favor of explicit discovery management
    // Discovery is now handled via startConductorDiscovery() with proper callback setup
    
    private fun clearCallbacks() {
        onServerClientConnected = null
        onServerClientDisconnected = null
        onClientConnectionStatusChanged = null
        onPageChangeReceived = null
        onFileChangeReceived = null
        onBackToListReceived = null
        onConductorDiscovered = null
        onDiscoveryTimeout = null
        onAutoConnectionResult = null
    }
    
    // Utility methods
    fun getServerConnectionInfo(): String {
        return if (isServerRunning()) {
            "${NetworkUtils.getLocalIpAddress()}:9090"
        } else {
            "서버 중지됨"
        }
    }
    
    fun getCollaborationStatusText(): String {
        return when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = getConnectedClientCount()
                "합주 모드: 지휘자 (연결된 기기: ${clientCount}대)"
            }
            CollaborationMode.PERFORMER -> {
                val conductorAddress = getConductorAddress()
                if (isClientConnected()) {
                    "합주 모드: 연주자 (연결됨: $conductorAddress)"
                } else {
                    "합주 모드: 연주자 (연결 끊김)"
                }
            }
            else -> "합주 모드: 비활성화"
        }
    }
}