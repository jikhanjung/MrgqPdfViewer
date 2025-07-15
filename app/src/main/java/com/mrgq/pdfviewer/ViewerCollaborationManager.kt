package com.mrgq.pdfviewer

import android.content.Context
import android.util.Log

/**
 * Manages real-time collaboration features for the PDF viewer.
 * Single responsibility: Handle all collaboration-related operations.
 */
class ViewerCollaborationManager(
    private val context: Context,
    private val globalCollaborationManager: GlobalCollaborationManager,
    private val listener: CollaborationListener
) {
    
    private var isInitialized = false
    private var currentMode: CollaborationMode = CollaborationMode.NONE
    
    enum class CollaborationMode {
        CONDUCTOR,
        PERFORMER,
        NONE
    }
    
    interface CollaborationListener {
        fun onRemotePageChange(pageIndex: Int)
        fun onRemoteFileChange(fileName: String, page: Int)
        fun onCollaborationStatusChanged(isConnected: Boolean)
        fun onBackToList()
        fun onDownloadFileRequest(fileName: String)
        fun onClientConnected(deviceName: String)
        fun onClientDisconnected()
    }
    
    /**
     * Initialize collaboration mode based on global manager state
     */
    fun initialize() {
        currentMode = globalCollaborationManager.getCurrentMode()
        Log.d("ViewerCollaborationManager", "Initializing collaboration mode: $currentMode")
        
        when (currentMode) {
            CollaborationMode.CONDUCTOR -> setupConductorMode()
            CollaborationMode.PERFORMER -> setupPerformerMode()
            CollaborationMode.NONE -> {
                // No collaboration setup needed
            }
        }
        
        isInitialized = true
    }
    
    /**
     * Broadcast page change to connected performers (Conductor mode)
     */
    fun broadcastPageChange(pageIndex: Int, fileName: String) {
        if (currentMode == CollaborationMode.CONDUCTOR) {
            val actualPageNumber = pageIndex + 1 // Convert to 1-based
            Log.d("ViewerCollaborationManager", "🎵 Broadcasting page change: $actualPageNumber in $fileName")
            globalCollaborationManager.broadcastPageChange(actualPageNumber, fileName)
        }
    }
    
    /**
     * Broadcast file change to connected performers (Conductor mode)
     */
    fun broadcastFileChange(fileName: String, page: Int) {
        if (currentMode == CollaborationMode.CONDUCTOR) {
            Log.d("ViewerCollaborationManager", "🎵 Broadcasting file change: $fileName at page $page")
            globalCollaborationManager.broadcastFileChange(fileName, page)
        }
    }
    
    /**
     * Broadcast back to list signal (Conductor mode)
     */
    fun broadcastBackToList() {
        if (currentMode == CollaborationMode.CONDUCTOR) {
            Log.d("ViewerCollaborationManager", "🎵 Broadcasting back to list signal")
            globalCollaborationManager.broadcastBackToList()
        }
    }
    
    /**
     * Add file to collaboration server (Conductor mode)
     */
    fun addFileToServer(fileName: String, filePath: String) {
        if (currentMode == CollaborationMode.CONDUCTOR) {
            Log.d("ViewerCollaborationManager", "🎵 Adding file to server: $fileName")
            globalCollaborationManager.addFileToServer(fileName, filePath)
        }
    }
    
    /**
     * Check if currently in collaboration mode
     */
    fun isInCollaborationMode(): Boolean = currentMode != CollaborationMode.NONE && isInitialized
    
    /**
     * Check if in conductor mode
     */
    fun isConductor(): Boolean = currentMode == CollaborationMode.CONDUCTOR
    
    /**
     * Check if in performer mode
     */
    fun isPerformer(): Boolean = currentMode == CollaborationMode.PERFORMER
    
    /**
     * Get current collaboration mode
     */
    fun getCurrentMode(): CollaborationMode = currentMode
    
    /**
     * Setup conductor mode callbacks
     */
    private fun setupConductorMode() {
        Log.d("ViewerCollaborationManager", "Setting up conductor mode callbacks")
        
        globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
            Log.d("ViewerCollaborationManager", "🎵 New performer connected: $deviceName")
            listener.onClientConnected(deviceName)
            listener.onCollaborationStatusChanged(true)
        }
        
        globalCollaborationManager.setOnServerClientDisconnected { clientId ->
            Log.d("ViewerCollaborationManager", "🎵 Performer disconnected")
            listener.onClientDisconnected()
            listener.onCollaborationStatusChanged(false)
        }
    }
    
    /**
     * Setup performer mode callbacks
     */
    private fun setupPerformerMode() {
        Log.d("ViewerCollaborationManager", "Setting up performer mode callbacks")
        
        globalCollaborationManager.setOnPageChangeReceived { page, file ->
            Log.d("ViewerCollaborationManager", "🎼 Received page change: $page in $file")
            // Convert to 0-based index and notify listener
            val targetIndex = page - 1
            if (targetIndex >= 0) {
                listener.onRemotePageChange(targetIndex)
            }
        }
        
        globalCollaborationManager.setOnFileChangeReceived { file, page ->
            Log.d("ViewerCollaborationManager", "🎼 Received file change: $file at page $page")
            listener.onRemoteFileChange(file, page)
        }
        
        globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
            Log.d("ViewerCollaborationManager", "🎼 Connection status changed: $isConnected")
            listener.onCollaborationStatusChanged(isConnected)
        }
        
        globalCollaborationManager.setOnBackToListReceived {
            Log.d("ViewerCollaborationManager", "🎼 Received back to list signal")
            listener.onBackToList()
        }
    }
    
    /**
     * Update collaboration status display
     */
    fun updateCollaborationStatus(): String {
        return when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                val connectedCount = globalCollaborationManager.getConnectedClientsCount()
                "지휘자 모드 ($connectedCount 명 연결됨)"
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                if (isConnected) "연주자 모드 (연결됨)" else "연주자 모드 (연결 끊김)"
            }
            CollaborationMode.NONE -> ""
        }
    }
    
    /**
     * Check if should show collaboration status
     */
    fun shouldShowCollaborationStatus(): Boolean = currentMode != CollaborationMode.NONE
    
    /**
     * Clean up collaboration resources
     */
    fun cleanup() {
        Log.d("ViewerCollaborationManager", "Cleaning up collaboration manager")
        // The global collaboration manager handles its own cleanup
        // We just reset our local state
        currentMode = CollaborationMode.NONE
        isInitialized = false
    }
}