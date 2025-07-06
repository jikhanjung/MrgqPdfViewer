package com.mrgq.pdfviewer

import com.google.gson.annotations.SerializedName

data class CollaborationMessage(
    @SerializedName("action")
    val action: String,
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @SerializedName("client_id")
    val clientId: String? = null,
    
    @SerializedName("device_name")
    val deviceName: String? = null,
    
    @SerializedName("page")
    val page: Int? = null,
    
    @SerializedName("file")
    val file: String? = null,
    
    @SerializedName("two_page_mode")
    val twoPageMode: Boolean? = null,
    
    @SerializedName("status")
    val status: String? = null,
    
    @SerializedName("master_id")
    val masterId: String? = null,
    
    @SerializedName("current_file")
    val currentFile: String? = null,
    
    @SerializedName("current_page")
    val currentPage: Int? = null,
    
    @SerializedName("app_version")
    val appVersion: String? = null,
    
    @SerializedName("error_message")
    val errorMessage: String? = null
) {
    companion object {
        // Action types
        const val ACTION_PAGE_CHANGE = "page_change"
        const val ACTION_FILE_CHANGE = "file_change"
        const val ACTION_CLIENT_CONNECT = "client_connect"
        const val ACTION_CONNECT_RESPONSE = "connect_response"
        const val ACTION_HEARTBEAT = "heartbeat"
        const val ACTION_HEARTBEAT_RESPONSE = "heartbeat_response"
        const val ACTION_REQUEST_SYNC = "request_sync"
        const val ACTION_SYNC_RESPONSE = "sync_response"
        const val ACTION_CLIENT_DISCONNECT = "client_disconnect"
        const val ACTION_CONDUCTOR_SHUTDOWN = "conductor_shutdown"
        const val ACTION_ERROR = "error"
        
        // Status types
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_REJECTED = "rejected"
        
        // Builder methods for common message types
        fun pageChange(page: Int, file: String, twoPageMode: Boolean = false): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_PAGE_CHANGE,
                page = page,
                file = file,
                twoPageMode = twoPageMode
            )
        }
        
        fun fileChange(file: String, page: Int = 1): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_FILE_CHANGE,
                file = file,
                page = page
            )
        }
        
        fun clientConnect(clientId: String, deviceName: String, appVersion: String): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_CLIENT_CONNECT,
                clientId = clientId,
                deviceName = deviceName,
                appVersion = appVersion
            )
        }
        
        fun connectResponse(
            status: String, 
            conductorId: String, 
            clientId: String? = null,
            currentFile: String? = null, 
            currentPage: Int? = null
        ): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_CONNECT_RESPONSE,
                status = status,
                masterId = conductorId,
                clientId = clientId,
                currentFile = currentFile,
                currentPage = currentPage
            )
        }
        
        fun heartbeat(clientId: String? = null): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_HEARTBEAT,
                clientId = clientId
            )
        }
        
        fun heartbeatResponse(): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_HEARTBEAT_RESPONSE
            )
        }
        
        fun requestSync(clientId: String, deviceName: String): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_REQUEST_SYNC,
                clientId = clientId,
                deviceName = deviceName
            )
        }
        
        fun syncResponse(currentFile: String?, currentPage: Int?): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_SYNC_RESPONSE,
                currentFile = currentFile,
                currentPage = currentPage
            )
        }
        
        fun clientDisconnect(clientId: String): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_CLIENT_DISCONNECT,
                clientId = clientId
            )
        }
        
        fun conductorShutdown(): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_CONDUCTOR_SHUTDOWN
            )
        }
        
        fun error(errorMessage: String, clientId: String? = null): CollaborationMessage {
            return CollaborationMessage(
                action = ACTION_ERROR,
                errorMessage = errorMessage,
                clientId = clientId
            )
        }
    }
}

enum class CollaborationMode {
    NONE,
    CONDUCTOR,
    PERFORMER
}

data class CollaborationStatus(
    val mode: CollaborationMode = CollaborationMode.NONE,
    val isConnected: Boolean = false,
    val connectedClients: Int = 0,
    val conductorAddress: String = "",
    val deviceName: String = "",
    val lastSyncTime: Long = 0L
)