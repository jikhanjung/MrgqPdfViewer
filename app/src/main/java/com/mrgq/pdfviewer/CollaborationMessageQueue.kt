package com.mrgq.pdfviewer

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Message queue system for collaboration mode
 * Ensures message ordering, handles priority, and prevents duplicates
 */
class CollaborationMessageQueue {
    companion object {
        private const val TAG = "CollaborationMessageQueue"
        private const val QUEUE_POLL_TIMEOUT = 100L // milliseconds
        private const val MAX_RETRY_COUNT = 3
        private const val MESSAGE_CACHE_SIZE = 100
        private const val MAX_QUEUE_SIZE = 1000
        private const val MESSAGE_TTL_MS = 30000L // 30 seconds
        private const val CLEANUP_INTERVAL_MS = 5000L // 5 seconds
    }
    
    private val messageQueue = LinkedBlockingQueue<CollaborationMessage>()
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedMessageIds = LruCache<String, Long>(MESSAGE_CACHE_SIZE)
    private var lastProcessedTimestamp = 0L
    private var isProcessing = false
    private var lastCleanupTime = 0L
    
    // Statistics and monitoring
    private var totalMessagesProcessed = 0L
    private var totalMessagesDropped = 0L
    private var totalRetries = 0L
    private var lastProcessingTime = 0L
    
    // Message processing callbacks
    private var onMessageProcessed: ((CollaborationMessage) -> Unit)? = null
    private var onQueueStatsUpdated: ((QueueStats) -> Unit)? = null
    
    data class CollaborationMessage(
        val id: String = UUID.randomUUID().toString(),
        val type: MessageType,
        val payload: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val priority: Priority = Priority.NORMAL
    )
    
    data class QueueStats(
        val queueSize: Int,
        val totalProcessed: Long,
        val totalDropped: Long,
        val totalRetries: Long,
        val lastProcessingTime: Long,
        val averageProcessingTime: Long
    )
    
    enum class MessageType {
        PAGE_CHANGE,
        FILE_CHANGE,
        MOUSE_POINTER,
        ANNOTATION,
        CONNECTION_STATUS,
        BACK_TO_LIST
    }
    
    enum class Priority {
        HIGH,    // Page/file changes - immediate processing needed
        NORMAL,  // General messages
        LOW      // Mouse movements, etc - can be throttled
    }
    
    /**
     * Set callback for message processing
     */
    fun setOnMessageProcessed(callback: (CollaborationMessage) -> Unit) {
        onMessageProcessed = callback
    }
    
    /**
     * Set callback for queue statistics updates
     */
    fun setOnQueueStatsUpdated(callback: (QueueStats) -> Unit) {
        onQueueStatsUpdated = callback
    }
    
    /**
     * Start processing messages from the queue
     */
    fun startProcessing() {
        if (isProcessing) {
            Log.w(TAG, "Message processing already started")
            return
        }
        
        isProcessing = true
        processingScope.launch {
            Log.d(TAG, "Message processing started")
            while (isActive && isProcessing) {
                try {
                    val message = messageQueue.poll(QUEUE_POLL_TIMEOUT, TimeUnit.MILLISECONDS)
                    message?.let { 
                        if (isMessageExpired(it)) {
                            Log.d(TAG, "Dropping expired message ${it.id}, age=${System.currentTimeMillis() - it.timestamp}ms")
                            totalMessagesDropped++
                        } else if (!isDuplicate(it.id) && !isOutOfOrder(it.timestamp)) {
                            processMessage(it)
                        } else {
                            Log.d(TAG, "Skipping message ${it.id}: duplicate=${isDuplicate(it.id)}, outOfOrder=${isOutOfOrder(it.timestamp)}")
                            totalMessagesDropped++
                        }
                    }
                    
                    // Periodic cleanup and stats update
                    performPeriodicMaintenance()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message", e)
                }
            }
            Log.d(TAG, "Message processing stopped")
        }
    }
    
    /**
     * Stop processing messages
     */
    fun stopProcessing() {
        isProcessing = false
        processingScope.coroutineContext.cancelChildren()
    }
    
    /**
     * Enqueue a message with priority handling
     */
    fun enqueueMessage(message: CollaborationMessage) {
        when (message.priority) {
            Priority.HIGH -> {
                // High priority messages go to the front
                val tempList = mutableListOf<CollaborationMessage>()
                messageQueue.drainTo(tempList)
                messageQueue.offer(message)
                messageQueue.addAll(tempList.filter { it.priority != Priority.LOW })
                messageQueue.addAll(tempList.filter { it.priority == Priority.LOW })
                Log.d(TAG, "High priority message ${message.type} added to front of queue")
            }
            Priority.LOW -> {
                // Low priority messages might be dropped if queue is full
                if (messageQueue.size < MAX_QUEUE_SIZE) {
                    messageQueue.offer(message)
                } else {
                    Log.w(TAG, "Dropping low priority message due to queue overflow")
                    totalMessagesDropped++
                }
            }
            else -> {
                messageQueue.offer(message)
                Log.d(TAG, "Normal priority message ${message.type} added to queue")
            }
        }
    }
    
    /**
     * Process a single message
     */
    private suspend fun processMessage(message: CollaborationMessage) {
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Processing message: type=${message.type}, id=${message.id}")
            
            withContext(Dispatchers.Main) {
                onMessageProcessed?.invoke(message)
            }
            
            lastProcessedTimestamp = message.timestamp
            processedMessageIds.put(message.id, System.currentTimeMillis())
            totalMessagesProcessed++
            
            val processingTime = System.currentTimeMillis() - startTime
            lastProcessingTime = processingTime
            
            Log.d(TAG, "Message ${message.id} processed in ${processingTime}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message ${message.id}", e)
            
            // Retry logic for critical messages
            if (message.type in listOf(MessageType.PAGE_CHANGE, MessageType.FILE_CHANGE) 
                && message.retryCount < MAX_RETRY_COUNT) {
                val retryMessage = message.copy(retryCount = message.retryCount + 1)
                delay(100L * (retryMessage.retryCount + 1)) // Exponential backoff
                enqueueMessage(retryMessage)
                totalRetries++
                Log.d(TAG, "Retrying message ${message.id}, attempt ${retryMessage.retryCount}")
            } else {
                totalMessagesDropped++
            }
        }
    }
    
    /**
     * Check if a message is a duplicate
     */
    private fun isDuplicate(messageId: String): Boolean {
        return processedMessageIds.get(messageId) != null
    }
    
    /**
     * Check if a message is out of order
     */
    private fun isOutOfOrder(timestamp: Long): Boolean {
        // Allow some tolerance for network delays (500ms)
        return timestamp < lastProcessedTimestamp - 500
    }
    
    /**
     * Get the last processed time for a specific message type
     */
    fun getLastProcessedTime(messageType: MessageType): Long? {
        // This would require tracking by type, simplified for now
        return if (lastProcessedTimestamp > 0) lastProcessedTimestamp else null
    }
    
    /**
     * Clear the queue
     */
    fun clear() {
        messageQueue.clear()
        processedMessageIds.evictAll()
        lastProcessedTimestamp = 0L
        Log.d(TAG, "Message queue cleared")
    }
    
    /**
     * Get current queue size
     */
    fun getQueueSize(): Int = messageQueue.size
    
    /**
     * Check if a message has expired based on TTL
     */
    private fun isMessageExpired(message: CollaborationMessage): Boolean {
        return System.currentTimeMillis() - message.timestamp > MESSAGE_TTL_MS
    }
    
    /**
     * Perform periodic maintenance tasks
     */
    private fun performPeriodicMaintenance() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupExpiredMessages()
            updateQueueStats()
            lastCleanupTime = currentTime
        }
    }
    
    /**
     * Remove expired messages from the queue
     */
    private fun cleanupExpiredMessages() {
        val currentTime = System.currentTimeMillis()
        val expiredMessages = mutableListOf<CollaborationMessage>()
        val tempList = mutableListOf<CollaborationMessage>()
        
        messageQueue.drainTo(tempList)
        
        for (message in tempList) {
            if (currentTime - message.timestamp > MESSAGE_TTL_MS) {
                expiredMessages.add(message)
                totalMessagesDropped++
            } else {
                messageQueue.offer(message)
            }
        }
        
        if (expiredMessages.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredMessages.size} expired messages")
        }
    }
    
    /**
     * Update and broadcast queue statistics
     */
    private fun updateQueueStats() {
        val stats = QueueStats(
            queueSize = messageQueue.size,
            totalProcessed = totalMessagesProcessed,
            totalDropped = totalMessagesDropped,
            totalRetries = totalRetries,
            lastProcessingTime = lastProcessingTime,
            averageProcessingTime = if (totalMessagesProcessed > 0) lastProcessingTime else 0
        )
        
        onQueueStatsUpdated?.invoke(stats)
    }
    
    /**
     * Get current queue statistics
     */
    fun getQueueStats(): QueueStats {
        return QueueStats(
            queueSize = messageQueue.size,
            totalProcessed = totalMessagesProcessed,
            totalDropped = totalMessagesDropped,
            totalRetries = totalRetries,
            lastProcessingTime = lastProcessingTime,
            averageProcessingTime = if (totalMessagesProcessed > 0) lastProcessingTime else 0
        )
    }
    
    /**
     * Reset statistics (for debugging/testing)
     */
    fun resetStats() {
        totalMessagesProcessed = 0
        totalMessagesDropped = 0
        totalRetries = 0
        lastProcessingTime = 0
        Log.d(TAG, "Queue statistics reset")
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopProcessing()
        clear()
        processingScope.cancel()
        Log.d(TAG, "Message queue cleaned up")
    }
}