package com.multimodal.capture.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.multimodal.capture.network.NetworkManager
import timber.log.Timber

/**
 * NetworkService handles background networking operations.
 * Maintains connection with PC controller even when app is backgrounded.
 */
class NetworkService : Service() {
    
    private val binder = NetworkBinder()
    private var networkManager: NetworkManager? = null
    private var isRunning = false
    
    inner class NetworkBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("NetworkService created")
        initializeNetworkManager()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NETWORK -> {
                startNetworking()
            }
            ACTION_STOP_NETWORK -> {
                stopNetworking()
            }
            ACTION_SEND_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? Map<String, Any>
                status?.let { sendStatusUpdate(it) }
            }
            ACTION_SEND_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                message?.let { sendMessage(it) }
            }
        }
        
        return START_STICKY // Restart service if killed
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    /**
     * Initialize network manager
     */
    private fun initializeNetworkManager() {
        try {
            networkManager = NetworkManager(this)
            
            // Set up callbacks
            networkManager?.setStatusCallback { status ->
                broadcastNetworkStatus(status)
            }
            
            networkManager?.setCommandCallback { command ->
                broadcastNetworkCommand(command)
            }
            
            Timber.d("NetworkManager initialized in service")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize NetworkManager")
        }
    }
    
    /**
     * Start networking operations
     */
    private fun startNetworking() {
        if (isRunning) {
            Timber.w("Network service already running")
            return
        }
        
        try {
            isRunning = true
            
            Timber.d("Network service started")
            
            // Broadcast service started
            val broadcastIntent = Intent(BROADCAST_NETWORK_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RUNNING, true)
            }
            sendBroadcast(broadcastIntent)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start network service")
            stopSelf()
        }
    }
    
    /**
     * Stop networking operations
     */
    private fun stopNetworking() {
        if (!isRunning) {
            Timber.w("Network service not running")
            return
        }
        
        try {
            isRunning = false
            
            Timber.d("Network service stopped")
            
            // Broadcast service stopped
            val broadcastIntent = Intent(BROADCAST_NETWORK_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RUNNING, false)
            }
            sendBroadcast(broadcastIntent)
            
            // Stop service
            stopSelf()
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping network service")
        }
    }
    
    /**
     * Send status update to PC
     */
    private fun sendStatusUpdate(status: Map<String, Any>) {
        try {
            networkManager?.sendStatusUpdate(status)
            Timber.d("Status update sent: $status")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send status update")
        }
    }
    
    /**
     * Send message to PC
     */
    private fun sendMessage(message: String) {
        try {
            // This would use NetworkManager's sendMessage method if it existed
            // For now, we can log it
            Timber.d("Message to send: $message")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
        }
    }
    
    /**
     * Broadcast network status change
     */
    private fun broadcastNetworkStatus(status: String) {
        val broadcastIntent = Intent(BROADCAST_NETWORK_STATUS_CHANGED).apply {
            putExtra(EXTRA_STATUS_MESSAGE, status)
        }
        sendBroadcast(broadcastIntent)
    }
    
    /**
     * Broadcast network command received
     */
    private fun broadcastNetworkCommand(command: String) {
        val broadcastIntent = Intent(BROADCAST_NETWORK_COMMAND_RECEIVED).apply {
            putExtra(EXTRA_COMMAND, command)
        }
        sendBroadcast(broadcastIntent)
    }
    
    /**
     * Notify recording started
     */
    fun notifyRecordingStarted(sessionId: String) {
        networkManager?.notifyRecordingStarted(sessionId)
    }
    
    /**
     * Notify recording stopped
     */
    fun notifyRecordingStopped(sessionId: String) {
        networkManager?.notifyRecordingStopped(sessionId)
    }
    
    /**
     * Check if connected to PC
     */
    fun isConnectedToPC(): Boolean {
        return networkManager?.isConnected() ?: false
    }
    
    /**
     * Get network manager instance
     */
    fun getNetworkManager(): NetworkManager? {
        return networkManager
    }
    
    /**
     * Check if service is running
     */
    fun isRunning(): Boolean = isRunning
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            if (isRunning) {
                stopNetworking()
            }
            
            networkManager?.cleanup()
            networkManager = null
            
            Timber.d("NetworkService destroyed")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during NetworkService cleanup")
        }
    }
    
    companion object {
        // Actions
        const val ACTION_START_NETWORK = "com.multimodal.capture.START_NETWORK"
        const val ACTION_STOP_NETWORK = "com.multimodal.capture.STOP_NETWORK"
        const val ACTION_SEND_STATUS = "com.multimodal.capture.SEND_STATUS"
        const val ACTION_SEND_MESSAGE = "com.multimodal.capture.SEND_MESSAGE"
        
        // Extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_COMMAND = "command"
        
        // Broadcasts
        const val BROADCAST_NETWORK_STATE_CHANGED = "com.multimodal.capture.NETWORK_STATE_CHANGED"
        const val BROADCAST_NETWORK_STATUS_CHANGED = "com.multimodal.capture.NETWORK_STATUS_CHANGED"
        const val BROADCAST_NETWORK_COMMAND_RECEIVED = "com.multimodal.capture.NETWORK_COMMAND_RECEIVED"
    }
}