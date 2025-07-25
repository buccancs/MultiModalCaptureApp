package com.multimodal.capture.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import com.multimodal.capture.R
import com.multimodal.capture.network.NetworkProtocol
import com.multimodal.capture.network.EnhancedNetworkMessage
import com.multimodal.capture.network.CommandMessage
import com.multimodal.capture.network.StatusResponse
import com.multimodal.capture.network.SyncPingMessage
import com.multimodal.capture.network.SyncPongMessage
import com.multimodal.capture.network.ErrorMessage
import com.multimodal.capture.network.CommandProtocol
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import timber.log.Timber
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlin.random.Random
import java.util.UUID

/**
 * Connection type enumeration for latency-aware synchronization
 */
enum class ConnectionType {
    WIFI,
    BLUETOOTH,
    ETHERNET,
    UNKNOWN
}

/**
 * Connection-specific latency baselines based on analysis
 */
object LatencyBaselines {
    // WiFi latency baselines (ms)
    const val WIFI_EXCELLENT_THRESHOLD = 50L
    const val WIFI_GOOD_THRESHOLD = 80L
    const val WIFI_FAIR_THRESHOLD = 100L
    const val WIFI_POOR_THRESHOLD = 200L
    
    // Bluetooth latency baselines (ms) - higher thresholds due to inherent latency
    const val BLUETOOTH_EXCELLENT_THRESHOLD = 120L
    const val BLUETOOTH_GOOD_THRESHOLD = 160L
    const val BLUETOOTH_FAIR_THRESHOLD = 200L
    const val BLUETOOTH_POOR_THRESHOLD = 300L
    
    // Ethernet latency baselines (ms) - lowest latency
    const val ETHERNET_EXCELLENT_THRESHOLD = 20L
    const val ETHERNET_GOOD_THRESHOLD = 50L
    const val ETHERNET_FAIR_THRESHOLD = 80L
    const val ETHERNET_POOR_THRESHOLD = 150L
}

/**
 * NetworkManager handles bi-directional communication with PC controller.
 * Implements command protocol for remote START/STOP and status updates.
 * Enhanced with connection-aware latency calculation for WiFi and Bluetooth.
 */
class NetworkManager(private val context: Context) {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    
    // Connection type tracking for latency-aware synchronization
    private var currentConnectionType: ConnectionType = ConnectionType.UNKNOWN
    
    // Network configuration
    private val serverPort = 8888 // Default port for PC communication
    private val discoveryPort = 8889 // Port for PC discovery
    private val connectionTimeout = 5000 // 5 seconds
    private val maxRetryAttempts = 5
    private val baseRetryDelay = 1000L // 1 second
    
    // Connection state
    private val isConnected = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)
    private val isDiscoveryActive = AtomicBoolean(false)
    private val connectionRetryCount = AtomicInteger(0)
    private val lastConnectionAttempt = AtomicLong(0)
    
    // Device state tracking
    private var currentDeviceState = NetworkProtocol.DeviceStates.IDLE
    
    // Time synchronization
    private var clockOffset = AtomicLong(0) // Offset from PC master clock in milliseconds
    private var lastSyncTimestamp = AtomicLong(0)
    private var syncQuality = AtomicInteger(0) // 0-100 quality score
    private val syncMeasurements = mutableListOf<SyncMeasurement>()
    private val maxSyncMeasurements = 10
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: BufferedReader? = null
    private var outputStream: PrintWriter? = null
    
    // Enhanced multi-device support
    private val deviceCoordinationId = generateCoordinationId()
    private val syncEventQueue = ConcurrentHashMap<String, Long>()
    private val networkMetrics = ConcurrentHashMap<String, Any>()
    private val messageSequenceNumber = AtomicInteger(0)
    
    // Background jobs
    private var serverJob: Job? = null
    private var messageHandlerJob: Job? = null
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryJob: Job? = null
    
    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    private var commandCallback: ((String) -> Unit)? = null
    private var syncEventCallback: ((String, Map<String, Any>) -> Unit)? = null
    private var connectionStateCallback: ((Boolean, String) -> Unit)? = null
    
    // Real-time data streaming components
    private var dataStreamingSocket: DatagramSocket? = null
    private var pcAddress: InetAddress? = null
    private val dataStreamingPort = 8890 // Port for PC to listen on
    private val isDataStreamingEnabled = AtomicBoolean(false)
    private var streamingJob: Job? = null
    
    init {
        Timber.d("NetworkManager initialized")
        isDiscoveryActive.set(true)
        startDiscoveryService()
        startServer()
    }
    
    /**
     * Start discovery service to advertise this device to PC
     */
    private fun startDiscoveryService() {
        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            var discoverySocket: DatagramSocket? = null
            
            while (retryCount < maxRetryAttempts && discoverySocket == null) {
                try {
                    discoverySocket = DatagramSocket(discoveryPort).apply {
                        reuseAddress = true
                        soTimeout = 5000 // 5 second timeout to allow graceful shutdown
                    }
                    Timber.d("Discovery service started on port $discoveryPort")
                    
                } catch (e: BindException) {
                    retryCount++
                    Timber.w("Discovery port $discoveryPort in use, retry $retryCount/$maxRetryAttempts")
                    if (retryCount < maxRetryAttempts) {
                        delay(baseRetryDelay * retryCount)
                    } else {
                        Timber.e(e, "Failed to start discovery service after $maxRetryAttempts attempts")
                        return@launch
                    }
                }
            }
            
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isDiscoveryActive.get()) {
                    try {
                        discoverySocket!!.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        
                        if (message == "DISCOVER_ANDROID_CAPTURE") {
                            // Respond with device info
                            val response = createDiscoveryResponse()
                            val responseBytes = response.toByteArray()
                            val responsePacket = DatagramPacket(
                                responseBytes, 
                                responseBytes.size, 
                                packet.address, 
                                packet.port
                            )
                            discoverySocket!!.send(responsePacket)
                            
                            Timber.d("Responded to discovery from ${packet.address}")
                        }
                        
                    } catch (e: Exception) {
                        when {
                            e is CancellationException -> {
                                // Expected when coroutine is cancelled
                                break
                            }
                            e is SocketTimeoutException -> {
                                // Expected timeout - no need to log as error, just continue listening
                                continue
                            }
                            else -> {
                                Timber.e(e, "Error in discovery service")
                            }
                        }
                    }
                }
                
                discoverySocket?.close()
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to start discovery service")
            }
        }
    }
    
    /**
     * Create discovery response with device information
     */
    private fun createDiscoveryResponse(): String {
        val deviceInfo = mapOf(
            "type" to "ANDROID_CAPTURE_DEVICE",
            "deviceId" to getDeviceId(),
            "deviceName" to getDeviceName(),
            "serverPort" to serverPort,
            "capabilities" to listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO"),
            "timestamp" to System.currentTimeMillis()
        )
        return gson.toJson(deviceInfo)
    }
    
    /**
     * Start TCP server to listen for PC connections
     */
    private fun startServer() {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            var serverStarted = false
            
            while (retryCount < maxRetryAttempts && !serverStarted) {
                try {
                    // Update connection type when starting server
                    updateConnectionType()
                    
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        soTimeout = 5000 // 5 second timeout for accept operations
                        bind(InetSocketAddress(serverPort))
                    }
                    isListening.set(true)
                    updateStatus("Listening for PC connection... (Connection: $currentConnectionType)")
                    
                    Timber.d("Server started on port $serverPort (Connection: $currentConnectionType)")
                    serverStarted = true
                    
                } catch (e: BindException) {
                    retryCount++
                    Timber.w("Server port $serverPort in use, retry $retryCount/$maxRetryAttempts")
                    if (retryCount < maxRetryAttempts) {
                        delay(baseRetryDelay * retryCount)
                    } else {
                        Timber.e(e, "Failed to start server after $maxRetryAttempts attempts")
                        updateStatus("Network Error: Unable to bind to port $serverPort")
                        return@launch
                    }
                }
            }
            
            try {
                
                while (isListening.get()) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            handleClientConnection(socket)
                        }
                    } catch (e: SocketException) {
                        if (isListening.get()) {
                            Timber.e(e, "Server socket error")
                        }
                    } catch (e: SocketTimeoutException) {
                        // Expected timeout - no need to log as error, just continue listening
                        continue
                    } catch (e: Exception) {
                        Timber.e(e, "Error accepting client connection")
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to start server")
                updateStatus("Network Error: ${e.message}")
            }
        }
    }
    
    /**
     * Handle incoming client connection from PC
     */
    private suspend fun handleClientConnection(socket: Socket) {
        try {
            // Close existing connection if any
            closeConnection()
            
            clientSocket = socket
            inputStream = BufferedReader(InputStreamReader(socket.getInputStream()))
            outputStream = PrintWriter(socket.getOutputStream(), true)
            
            isConnected.set(true)
            updateStatus(context.getString(R.string.status_network_connected))
            
            Timber.d("PC connected from ${socket.remoteSocketAddress}")
            
            // Send connection acknowledgment
            sendMessage(createConnectionAck())
            
            // Start message handler
            messageHandlerJob = CoroutineScope(Dispatchers.IO).launch {
                handleIncomingMessages()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling client connection")
            closeConnection()
        }
    }
    
    /**
     * Handle incoming messages from PC
     */
    private suspend fun handleIncomingMessages() {
        try {
            val reader = inputStream ?: return
            
            while (isConnected.get()) {
                try {
                    val message = reader.readLine()
                    if (message != null) {
                        processIncomingMessage(message)
                    } else {
                        // Connection closed by PC
                        break
                    }
                } catch (e: SocketTimeoutException) {
                    // Timeout is normal, continue
                } catch (e: IOException) {
                    Timber.w("Connection lost: ${e.message}")
                    break
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling incoming messages")
        } finally {
            closeConnection()
        }
    }
    
    /**
     * Process incoming message from PC
     */
    private fun processIncomingMessage(message: String) {
        try {
            Timber.d("Received message: $message")
            
            // Try to parse as EnhancedNetworkMessage first
            val enhancedMessage = try {
                gson.fromJson(message, EnhancedNetworkMessage::class.java)
            } catch (e: Exception) {
                // Fallback to legacy NetworkMessage for backward compatibility
                val legacyMessage = gson.fromJson(message, NetworkMessage::class.java)
                EnhancedNetworkMessage(
                    type = legacyMessage.type,
                    payload = legacyMessage.payload,
                    timestamp = legacyMessage.timestamp
                )
            }
            
            when (enhancedMessage.type) {
                NetworkProtocol.MessageTypes.COMMAND -> {
                    handleEnhancedCommand(enhancedMessage)
                }
                NetworkProtocol.MessageTypes.STATUS_REQUEST -> {
                    handleEnhancedStatusRequest(enhancedMessage)
                }
                NetworkProtocol.MessageTypes.PING -> {
                    handleEnhancedPing(enhancedMessage)
                }
                NetworkProtocol.MessageTypes.SYNC_PING -> {
                    handleEnhancedSyncPing(enhancedMessage)
                }
                NetworkProtocol.MessageTypes.SYNC_MARKER -> {
                    handleSyncMarker(enhancedMessage)
                }
                NetworkProtocol.MessageTypes.HEARTBEAT -> {
                    handleHeartbeat(enhancedMessage)
                }
                // Legacy support
                "COMMAND" -> {
                    handleCommand(enhancedMessage.payload as String)
                }
                "STATUS_REQUEST" -> {
                    handleStatusRequest()
                }
                "PING" -> {
                    handlePing(NetworkMessage(enhancedMessage.type, enhancedMessage.payload, enhancedMessage.timestamp))
                }
                "SYNC_PING" -> {
                    handleSyncPing(NetworkMessage(enhancedMessage.type, enhancedMessage.payload, enhancedMessage.timestamp))
                }
                else -> {
                    Timber.w("Unknown message type: ${enhancedMessage.type}")
                    sendErrorResponse(
                        NetworkProtocol.ErrorCodes.UNKNOWN_COMMAND,
                        "Unknown message type: ${enhancedMessage.type}",
                        enhancedMessage.messageId
                    )
                }
            }
            
        } catch (e: JsonSyntaxException) {
            Timber.e(e, "Invalid JSON message: $message")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Invalid JSON message format",
                null
            )
        } catch (e: Exception) {
            Timber.e(e, "Error processing message: $message")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Error processing message: ${e.message}",
                null
            )
        }
    }
    
    /**
     * Handle enhanced command from PC with specific command processing
     */
    private fun handleEnhancedCommand(message: EnhancedNetworkMessage) {
        try {
            val commandData = gson.fromJson(gson.toJson(message.payload), CommandMessage::class.java)
            Timber.d("Handling enhanced command: ${commandData.command}")
            
            when (commandData.command) {
                NetworkProtocol.Commands.CMD_START -> {
                    handleStartCommand(commandData, message)
                }
                NetworkProtocol.Commands.CMD_STOP -> {
                    handleStopCommand(commandData, message)
                }
                NetworkProtocol.Commands.CMD_STATUS -> {
                    handleStatusCommand(commandData, message)
                }
                NetworkProtocol.Commands.CMD_PREPARE -> {
                    handlePrepareCommand(commandData, message)
                }
                NetworkProtocol.Commands.CMD_RESET -> {
                    handleResetCommand(commandData, message)
                }
                else -> {
                    Timber.w("Unknown command: ${commandData.command}")
                    sendErrorResponse(
                        NetworkProtocol.ErrorCodes.UNKNOWN_COMMAND,
                        "Unknown command: ${commandData.command}",
                        message.messageId
                    )
                    return
                }
            }
            
            // Send acknowledgment if required
            if (message.requiresAck) {
                sendCommandAck(commandData.command, message.messageId)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling enhanced command")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Error processing command: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle legacy command from PC (for backward compatibility)
     */
    private fun handleCommand(command: String) {
        Timber.d("Handling legacy command: $command")
        
        // Notify command callback
        mainHandler.post {
            commandCallback?.invoke(command)
        }
        
        // Send acknowledgment
        val ack = NetworkMessage(
            type = "COMMAND_ACK",
            payload = command,
            timestamp = System.currentTimeMillis()
        )
        sendMessage(gson.toJson(ack))
    }
    
    /**
     * Handle status request from PC
     */
    private fun handleStatusRequest() {
        // This would be implemented to get current status from the app
        val status = mapOf(
            "connected" to true,
            "timestamp" to System.currentTimeMillis()
        )
        
        val response = NetworkMessage(
            type = "STATUS_RESPONSE",
            payload = status,
            timestamp = System.currentTimeMillis()
        )
        
        sendMessage(gson.toJson(response))
    }
    
    /**
     * Handle ping from PC
     */
    private fun handlePing(message: NetworkMessage) {
        val pong = NetworkMessage(
            type = "PONG",
            payload = message.payload,
            timestamp = System.currentTimeMillis()
        )
        sendMessage(gson.toJson(pong))
    }
    
    /**
     * Handle sync ping for time synchronization
     */
    private fun handleSyncPing(message: NetworkMessage) {
        val receiveTime = System.currentTimeMillis()
        val sendTime = System.currentTimeMillis()
        
        // Extract ping data from payload
        val pingData = message.payload as? Map<*, *>
        val clientSendTime = (pingData?.get("clientTimestamp") as? Number)?.toLong() ?: message.timestamp
        val pingId = pingData?.get("pingId") as? String ?: "legacy_ping"
        
        val response = NetworkMessage(
            type = "SYNC_PONG",
            payload = mapOf(
                "pingId" to pingId,
                "clientTimestamp" to clientSendTime,
                "serverReceiveTimestamp" to receiveTime,
                "serverSendTimestamp" to sendTime,
                "originalTimestamp" to message.timestamp
            ),
            timestamp = sendTime
        )
        sendMessage(gson.toJson(response))
        
        Timber.d("Handled sync ping: $pingId, RTT calculation pending PC response")
    }
    
    /**
     * Send message to PC
     */
    private fun sendMessage(message: String) {
        try {
            outputStream?.println(message)
            Timber.v("Sent message: $message")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
        }
    }
    
    /**
     * Create connection acknowledgment message
     */
    private fun createConnectionAck(): String {
        val ack = NetworkMessage(
            type = "CONNECTION_ACK",
            payload = mapOf(
                "deviceId" to getDeviceId(),
                "deviceName" to getDeviceName(),
                "capabilities" to listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO")
            ),
            timestamp = System.currentTimeMillis()
        )
        return gson.toJson(ack)
    }
    
    /**
     * Notify recording started
     */
    fun notifyRecordingStarted(sessionId: String) {
        val message = NetworkMessage(
            type = "RECORDING_STARTED",
            payload = mapOf(
                "sessionId" to sessionId,
                "timestamp" to System.currentTimeMillis()
            ),
            timestamp = System.currentTimeMillis()
        )
        sendMessage(gson.toJson(message))
    }
    
    /**
     * Notify recording stopped
     */
    fun notifyRecordingStopped(sessionId: String) {
        val message = NetworkMessage(
            type = "RECORDING_STOPPED",
            payload = mapOf(
                "sessionId" to sessionId,
                "timestamp" to System.currentTimeMillis()
            ),
            timestamp = System.currentTimeMillis()
        )
        sendMessage(gson.toJson(message))
    }
    
    /**
     * Send status update to PC
     */
    fun sendStatusUpdate(status: Map<String, Any>) {
        val message = NetworkMessage(
            type = "STATUS_UPDATE",
            payload = status,
            timestamp = System.currentTimeMillis()
        )
        sendMessage(gson.toJson(message))
    }
    
    /**
     * Close current connection
     */
    private fun closeConnection() {
        try {
            isConnected.set(false)
            
            messageHandlerJob?.cancel()
            messageHandlerJob = null
            
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            
            inputStream = null
            outputStream = null
            clientSocket = null
            
            updateStatus(context.getString(R.string.status_network_disconnected))
            
            Timber.d("Connection closed")
            
        } catch (e: Exception) {
            Timber.e(e, "Error closing connection")
        }
    }
    
    /**
     * Get device ID
     */
    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
    
    /**
     * Get device name
     */
    private fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Android Device"
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * Detect current connection type for latency-aware synchronization
     */
    private fun detectConnectionType(): ConnectionType {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return ConnectionType.UNKNOWN
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ConnectionType.UNKNOWN
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    Timber.d("Detected WiFi connection")
                    ConnectionType.WIFI
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                    Timber.d("Detected Bluetooth connection")
                    ConnectionType.BLUETOOTH
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    Timber.d("Detected Ethernet connection")
                    ConnectionType.ETHERNET
                }
                else -> {
                    Timber.d("Unknown connection type")
                    ConnectionType.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error detecting connection type")
            return ConnectionType.UNKNOWN
        }
    }
    
    /**
     * Update current connection type and log changes
     */
    private fun updateConnectionType() {
        val newConnectionType = detectConnectionType()
        if (newConnectionType != currentConnectionType) {
            Timber.i("Connection type changed from $currentConnectionType to $newConnectionType")
            currentConnectionType = newConnectionType
        }
    }
    
    /**
     * Get current connection type
     */
    fun getConnectionType(): ConnectionType {
        return currentConnectionType
    }
    
    /**
     * Calculate sync quality based on connection type and round-trip time
     */
    private fun calculateConnectionAwareQuality(roundTripTime: Long, connectionType: ConnectionType): Int {
        return when (connectionType) {
            ConnectionType.WIFI -> {
                when {
                    roundTripTime <= LatencyBaselines.WIFI_EXCELLENT_THRESHOLD -> 100
                    roundTripTime <= LatencyBaselines.WIFI_GOOD_THRESHOLD -> 80
                    roundTripTime <= LatencyBaselines.WIFI_FAIR_THRESHOLD -> 60
                    roundTripTime <= LatencyBaselines.WIFI_POOR_THRESHOLD -> 40
                    else -> 20
                }
            }
            ConnectionType.BLUETOOTH -> {
                when {
                    roundTripTime <= LatencyBaselines.BLUETOOTH_EXCELLENT_THRESHOLD -> 100
                    roundTripTime <= LatencyBaselines.BLUETOOTH_GOOD_THRESHOLD -> 80
                    roundTripTime <= LatencyBaselines.BLUETOOTH_FAIR_THRESHOLD -> 60
                    roundTripTime <= LatencyBaselines.BLUETOOTH_POOR_THRESHOLD -> 40
                    else -> 20
                }
            }
            ConnectionType.ETHERNET -> {
                when {
                    roundTripTime <= LatencyBaselines.ETHERNET_EXCELLENT_THRESHOLD -> 100
                    roundTripTime <= LatencyBaselines.ETHERNET_GOOD_THRESHOLD -> 80
                    roundTripTime <= LatencyBaselines.ETHERNET_FAIR_THRESHOLD -> 60
                    roundTripTime <= LatencyBaselines.ETHERNET_POOR_THRESHOLD -> 40
                    else -> 20
                }
            }
            ConnectionType.UNKNOWN -> {
                // Fall back to original WiFi-based calculation for unknown connections
                when {
                    roundTripTime < 10 -> 100
                    roundTripTime < 50 -> 80
                    roundTripTime < 100 -> 60
                    roundTripTime < 500 -> 40
                    else -> 20
                }
            }
        }
    }
    
    /**
     * Set status callback
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }
    
    /**
     * Set command callback
     */
    fun setCommandCallback(callback: (String) -> Unit) {
        commandCallback = callback
    }
    
    /**
     * Update status and notify callback
     */
    private fun updateStatus(status: String) {
        mainHandler.post {
            statusCallback?.invoke(status)
        }
    }
    
    /**
     * Check if connected to PC
     */
    fun isConnected(): Boolean {
        return isConnected.get()
    }

    // Enhanced multi-device coordination methods

    /**
     * Generate unique coordination ID for multi-device scenarios
     */
    private fun generateCoordinationId(): String {
        return "${getDeviceId()}_${UUID.randomUUID().toString().take(8)}"
    }

    /**
     * Set sync event callback for coordination
     */
    fun setSyncEventCallback(callback: (String, Map<String, Any>) -> Unit) {
        syncEventCallback = callback
    }

    /**
     * Set connection state callback
     */
    fun setConnectionStateCallback(callback: (Boolean, String) -> Unit) {
        connectionStateCallback = callback
    }

    /**
     * Start heartbeat to maintain connection
     */
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                try {
                    val heartbeat = NetworkMessage(
                        type = "HEARTBEAT",
                        payload = mapOf(
                            "deviceId" to getDeviceId(),
                            "coordinationId" to deviceCoordinationId,
                            "timestamp" to System.currentTimeMillis()
                        ),
                        timestamp = System.currentTimeMillis()
                    )
                    sendMessage(gson.toJson(heartbeat))
                    
                    delay(30000) // Send heartbeat every 30 seconds
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Timber.e(e, "Error sending heartbeat")
                    }
                    break
                }
            }
        }
    }

    /**
     * Handle connection retry with exponential backoff
     */
    private fun startConnectionRetry() {
        if (connectionRetryCount.get() >= maxRetryAttempts) {
            Timber.w("Max retry attempts reached")
            connectionStateCallback?.invoke(false, "Max retry attempts reached")
            return
        }

        retryJob = CoroutineScope(Dispatchers.IO).launch {
            val retryCount = connectionRetryCount.incrementAndGet()
            val delay = baseRetryDelay * (1L shl (retryCount - 1)) + Random.nextLong(1000)
            
            Timber.d("Retrying connection in ${delay}ms (attempt $retryCount)")
            connectionStateCallback?.invoke(false, "Retrying connection (attempt $retryCount)")
            
            delay(delay)
            
            if (retryCount <= maxRetryAttempts) {
                // Attempt to restart server
                startServer()
            }
        }
    }

    /**
     * Send sync event to PC for multi-device coordination
     */
    fun sendSyncEvent(eventType: String, eventData: Map<String, Any> = emptyMap()) {
        val eventId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        
        syncEventQueue[eventId] = timestamp
        
        val syncEvent = NetworkMessage(
            type = "SYNC_EVENT",
            payload = mapOf(
                "eventId" to eventId,
                "eventType" to eventType,
                "deviceId" to getDeviceId(),
                "coordinationId" to deviceCoordinationId,
                "sequenceNumber" to messageSequenceNumber.incrementAndGet(),
                "eventData" to eventData,
                "timestamp" to timestamp
            ),
            timestamp = timestamp
        )
        
        sendMessage(gson.toJson(syncEvent))
        
        // Notify local callback
        syncEventCallback?.invoke(eventType, eventData)
        
        Timber.d("Sent sync event: $eventType")
    }

    /**
     * Send enhanced sync ping with device coordination info
     */
    fun sendEnhancedSyncPing() {
        val timestamp = System.currentTimeMillis()
        val syncPing = NetworkMessage(
            type = "ENHANCED_SYNC_PING",
            payload = mapOf(
                "deviceId" to getDeviceId(),
                "coordinationId" to deviceCoordinationId,
                "sequenceNumber" to messageSequenceNumber.incrementAndGet(),
                "networkMetrics" to getNetworkMetrics(),
                "sendTimestamp" to timestamp
            ),
            timestamp = timestamp
        )
        
        sendMessage(gson.toJson(syncPing))
    }

    /**
     * Get current network metrics
     */
    private fun getNetworkMetrics(): Map<String, Any> {
        // Update connection type before returning metrics
        updateConnectionType()
        
        return mapOf(
            "connectionQuality" to if (isNetworkAvailable()) "good" else "poor",
            "connectionType" to currentConnectionType.name,
            "lastMessageTime" to (networkMetrics["lastMessageTime"] ?: 0L),
            "messageCount" to messageSequenceNumber.get(),
            "retryCount" to connectionRetryCount.get(),
            "connectionTypeDetected" to System.currentTimeMillis()
        )
    }

    /**
     * Handle enhanced status request
     */
    private fun handleEnhancedStatusRequest(message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling enhanced status request")
            
            val statusResponse = StatusResponse(
                deviceId = getDeviceId(),
                deviceName = getDeviceName(),
                state = currentDeviceState,
                batteryLevel = getBatteryLevel(),
                storageAvailable = getAvailableStorage(),
                isRecording = currentDeviceState == NetworkProtocol.DeviceStates.RECORDING,
                capabilities = listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO"),
                timestamp = System.currentTimeMillis(),
                networkMetrics = getNetworkMetrics()
            )
            
            val response = EnhancedNetworkMessage(
                type = NetworkProtocol.MessageTypes.STATUS_RESPONSE,
                payload = statusResponse,
                timestamp = System.currentTimeMillis(),
                deviceId = getDeviceId(),
                messageId = message.messageId
            )
            
            sendMessage(gson.toJson(response))
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling enhanced status request")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Failed to get device status: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle enhanced ping
     */
    private fun handleEnhancedPing(message: EnhancedNetworkMessage) {
        val response = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.PONG,
            payload = message.payload,
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceId(),
            messageId = message.messageId
        )
        sendMessage(gson.toJson(response))
    }
    
    /**
     * Handle sync marker
     */
    private fun handleSyncMarker(message: EnhancedNetworkMessage) {
        try {
            val markerData = gson.fromJson(gson.toJson(message.payload), SyncMarkerMessage::class.java)
            Timber.d("Received sync marker: ${markerData.markerType}")
            
            // Notify sync event callback
            syncEventCallback?.invoke(markerData.markerType, markerData.data)
            
            // Send acknowledgment
            val ack = EnhancedNetworkMessage(
                type = NetworkProtocol.MessageTypes.COMMAND_ACK,
                payload = mapOf(
                    "markerType" to markerData.markerType,
                    "markerId" to markerData.markerId,
                    "status" to "received"
                ),
                timestamp = System.currentTimeMillis(),
                deviceId = getDeviceId(),
                messageId = message.messageId
            )
            
            sendMessage(gson.toJson(ack))
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling sync marker")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Failed to process sync marker: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle heartbeat
     */
    private fun handleHeartbeat(message: EnhancedNetworkMessage) {
        val response = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.HEARTBEAT_ACK,
            payload = mapOf(
                "deviceId" to getDeviceId(),
                "coordinationId" to deviceCoordinationId,
                "timestamp" to System.currentTimeMillis(),
                "networkMetrics" to getNetworkMetrics()
            ),
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceId(),
            messageId = message.messageId
        )
        
        sendMessage(gson.toJson(response))
        
        // Update network metrics
        networkMetrics["lastMessageTime"] = System.currentTimeMillis()
    }

    /**
     * Handle enhanced sync ping for time synchronization
     */
    private fun handleEnhancedSyncPing(message: EnhancedNetworkMessage) {
        val receiveTime = System.currentTimeMillis()
        val sendTime = System.currentTimeMillis()
        
        try {
            val syncPingData = gson.fromJson(gson.toJson(message.payload), SyncPingMessage::class.java)
            
            // Create sync pong response with precise timing
            val syncPongData = SyncPongMessage(
                pingId = syncPingData.pingId,
                clientTimestamp = syncPingData.clientTimestamp,
                serverReceiveTimestamp = receiveTime,
                serverSendTimestamp = sendTime,
                sequenceNumber = syncPingData.sequenceNumber
            )
            
            val response = EnhancedNetworkMessage(
                type = NetworkProtocol.MessageTypes.SYNC_PONG,
                payload = syncPongData,
                timestamp = sendTime,
                deviceId = getDeviceId(),
                messageId = message.messageId
            )
            
            sendMessage(gson.toJson(response))
            
            // Update sync metrics
            lastSyncTimestamp.set(receiveTime)
            networkMetrics["lastSyncTime"] = receiveTime
            networkMetrics["syncSequence"] = syncPingData.sequenceNumber
            
            Timber.d("Handled sync ping ${syncPingData.pingId}, sequence: ${syncPingData.sequenceNumber}")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling enhanced sync ping")
            
            // Fallback to basic response
            val response = EnhancedNetworkMessage(
                type = NetworkProtocol.MessageTypes.SYNC_PONG,
                payload = mapOf(
                    "deviceId" to getDeviceId(),
                    "receiveTimestamp" to receiveTime,
                    "sendTimestamp" to sendTime,
                    "error" to "Failed to parse sync ping: ${e.message}"
                ),
                timestamp = sendTime,
                deviceId = getDeviceId(),
                messageId = message.messageId
            )
            
            sendMessage(gson.toJson(response))
        }
    }
    
    /**
     * Process sync pong response for clock offset calculation
     */
    fun processSyncPongResponse(pongMessage: SyncPongMessage, clientReceiveTime: Long) {
        try {
            val clientSendTime = pongMessage.clientTimestamp
            val serverReceiveTime = pongMessage.serverReceiveTimestamp
            val serverSendTime = pongMessage.serverSendTimestamp
            
            // Calculate round-trip time
            val roundTripTime = clientReceiveTime - clientSendTime
            
            // Calculate clock offset using NTP algorithm
            // offset = ((serverReceiveTime - clientSendTime) + (serverSendTime - clientReceiveTime)) / 2
            val offset = ((serverReceiveTime - clientSendTime) + (serverSendTime - clientReceiveTime)) / 2
            
            // Update connection type before calculating quality
            updateConnectionType()
            
            // Calculate quality based on round-trip time and connection type
            val quality = calculateConnectionAwareQuality(roundTripTime, currentConnectionType)
            
            val measurement = SyncMeasurement(
                clientSendTime = clientSendTime,
                serverReceiveTime = serverReceiveTime,
                serverSendTime = serverSendTime,
                clientReceiveTime = clientReceiveTime,
                roundTripTime = roundTripTime,
                clockOffset = offset,
                quality = quality
            )
            
            // Add measurement if valid
            if (measurement.isValid()) {
                synchronized(syncMeasurements) {
                    syncMeasurements.add(measurement)
                    if (syncMeasurements.size > maxSyncMeasurements) {
                        syncMeasurements.removeAt(0)
                    }
                }
                
                // Update clock offset with filtered average
                updateClockOffset()
                
                Timber.d("Sync measurement: RTT=${roundTripTime}ms, Offset=${offset}ms, Quality=$quality")
            } else {
                Timber.w("Invalid sync measurement: RTT=${roundTripTime}ms")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error processing sync pong response")
        }
    }
    
    /**
     * Update clock offset using filtered measurements
     */
    private fun updateClockOffset() {
        synchronized(syncMeasurements) {
            if (syncMeasurements.isEmpty()) return
            
            // Filter out outliers (measurements with quality < 40)
            val validMeasurements = syncMeasurements.filter { it.quality >= 40 }
            
            if (validMeasurements.isNotEmpty()) {
                // Calculate weighted average offset
                val totalWeight = validMeasurements.sumOf { it.quality }
                val weightedSum = validMeasurements.sumOf { it.clockOffset * it.quality }
                val averageOffset = weightedSum / totalWeight
                
                clockOffset.set(averageOffset)
                
                // Update sync quality based on recent measurements
                val recentQuality = validMeasurements.takeLast(3).map { it.quality }.average().toInt()
                syncQuality.set(recentQuality)
                
                lastSyncTimestamp.set(System.currentTimeMillis())
                
                Timber.d("Updated clock offset: ${averageOffset}ms, Quality: $recentQuality")
            }
        }
    }
    
    /**
     * Get synchronized timestamp using clock offset
     */
    fun getSynchronizedTimestamp(): Long {
        return System.currentTimeMillis() + clockOffset.get()
    }
    
    /**
     * Get current clock offset
     */
    fun getClockOffset(): Long {
        return clockOffset.get()
    }
    
    /**
     * Get sync quality (0-100)
     */
    fun getSyncQuality(): Int {
        return syncQuality.get()
    }
    
    /**
     * Check if device is synchronized within tolerance
     */
    fun isSynchronized(toleranceMs: Long = NetworkProtocol.NetworkConfig.MAX_CLOCK_OFFSET_MS): Boolean {
        val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp.get()
        return syncQuality.get() >= 60 && 
               Math.abs(clockOffset.get()) <= toleranceMs &&
               timeSinceLastSync < 30000 // Last sync within 30 seconds
    }
    
    /**
     * Initiate sync ping to PC for time synchronization
     * Enhanced with connection-aware latency calculation
     */
    fun initiateSyncPing() {
        // Update connection type before sync ping
        updateConnectionType()
        
        val clientSendTime = System.currentTimeMillis()
        val syncPing = SyncPingMessage(
            clientTimestamp = clientSendTime,
            sequenceNumber = messageSequenceNumber.incrementAndGet()
        )
        
        val message = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.SYNC_PING,
            payload = syncPing,
            timestamp = clientSendTime,
            deviceId = getDeviceId(),
            requiresAck = false
        )
        
        sendMessage(gson.toJson(message))
        
        // Log sync ping with connection type information
        Timber.d("Initiated sync ping: ${syncPing.pingId} (Connection: $currentConnectionType)")
        
        // Update network metrics with connection-aware sync attempt
        networkMetrics["lastSyncAttempt"] = clientSendTime
        networkMetrics["syncConnectionType"] = currentConnectionType.name
    }

    /**
     * Create session coordination marker with enhanced synchronization
     */
    fun createSessionMarker(sessionId: String, markerType: String, markerData: Map<String, Any> = emptyMap()) {
        val localTimestamp = System.currentTimeMillis()
        val syncTimestamp = getSynchronizedTimestamp()
        
        val markerMessage = SyncMarkerMessage(
            markerType = markerType,
            sessionId = sessionId,
            timestamp = syncTimestamp,
            data = markerData + mapOf(
                "localTimestamp" to localTimestamp,
                "syncTimestamp" to syncTimestamp,
                "clockOffset" to getClockOffset(),
                "syncQuality" to getSyncQuality(),
                "deviceId" to getDeviceId()
            )
        )
        
        // Send marker to PC
        val message = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.SYNC_MARKER,
            payload = markerMessage,
            timestamp = syncTimestamp,
            deviceId = getDeviceId(),
            sessionId = sessionId,
            requiresAck = true
        )
        
        sendMessage(gson.toJson(message))
        
        // Log marker locally
        logSyncMarker(markerMessage)
        
        // Notify local callback
        syncEventCallback?.invoke(markerType, markerMessage.data)
        
        Timber.i("Created sync marker: $markerType for session: $sessionId")
    }
    
    /**
     * Broadcast sync marker to all connected devices
     */
    fun broadcastSyncMarker(markerType: String, sessionId: String, markerData: Map<String, Any> = emptyMap()) {
        val syncTimestamp = getSynchronizedTimestamp()
        
        val markerMessage = SyncMarkerMessage(
            markerType = markerType,
            sessionId = sessionId,
            timestamp = syncTimestamp,
            data = markerData + mapOf(
                "broadcastSource" to getDeviceId(),
                "syncTimestamp" to syncTimestamp,
                "clockOffset" to getClockOffset(),
                "syncQuality" to getSyncQuality()
            )
        )
        
        // Send broadcast marker
        sendSyncEvent("BROADCAST_MARKER", mapOf(
            "marker" to markerMessage,
            "broadcastType" to "SYNC_MARKER",
            "priority" to "HIGH"
        ))
        
        // Log marker locally
        logSyncMarker(markerMessage)
        
        Timber.i("Broadcasted sync marker: $markerType for session: $sessionId")
    }
    
    /**
     * Log sync marker for verification and analysis
     */
    private fun logSyncMarker(marker: SyncMarkerMessage) {
        try {
            val logEntry = mapOf(
                "markerId" to marker.markerId,
                "markerType" to marker.markerType,
                "sessionId" to marker.sessionId,
                "timestamp" to marker.timestamp,
                "localTime" to System.currentTimeMillis(),
                "clockOffset" to getClockOffset(),
                "syncQuality" to getSyncQuality(),
                "deviceId" to getDeviceId(),
                "data" to marker.data
            )
            
            // Store in network metrics for later retrieval
            val markerLog = networkMetrics.getOrPut("markerLog") { mutableListOf<Map<String, Any>>() } as MutableList<Map<String, Any>>
            markerLog.add(logEntry)
            
            // Keep only last 100 markers
            if (markerLog.size > 100) {
                markerLog.removeAt(0)
            }
            
            Timber.d("Logged sync marker: ${marker.markerType} (${marker.markerId})")
            
        } catch (e: Exception) {
            Timber.e(e, "Error logging sync marker")
        }
    }
    
    /**
     * Get marker log for verification
     */
    fun getMarkerLog(): List<Map<String, Any>> {
        return (networkMetrics["markerLog"] as? List<Map<String, Any>>) ?: emptyList()
    }
    
    /**
     * Verify synchronization during recording
     */
    fun verifySynchronization(sessionId: String): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val syncTime = getSynchronizedTimestamp()
        val quality = getSyncQuality()
        val offset = getClockOffset()
        val timeSinceLastSync = currentTime - lastSyncTimestamp.get()
        
        val isSync = isSynchronized()
        val markerCount = getMarkerLog().count { (it["sessionId"] as? String) == sessionId }
        
        val verificationResult = mapOf(
            "sessionId" to sessionId,
            "deviceId" to getDeviceId(),
            "isSynchronized" to isSync,
            "syncQuality" to quality,
            "clockOffset" to offset,
            "timeSinceLastSync" to timeSinceLastSync,
            "localTimestamp" to currentTime,
            "syncTimestamp" to syncTime,
            "markerCount" to markerCount,
            "networkMetrics" to getNetworkMetrics(),
            "verificationTime" to currentTime
        )
        
        // Create verification marker
        createSessionMarker(
            sessionId, 
            NetworkProtocol.SyncMarkerTypes.CUSTOM,
            mapOf(
                "verificationType" to "SYNC_VERIFICATION",
                "verificationResult" to verificationResult
            )
        )
        
        Timber.i("Sync verification for session $sessionId: synchronized=$isSync, quality=$quality")
        
        return verificationResult
    }
    
    /**
     * Create calibration marker with enhanced data
     */
    fun createCalibrationMarker(sessionId: String, calibrationType: String, calibrationData: Map<String, Any> = emptyMap()) {
        val enhancedData = calibrationData + mapOf(
            "calibrationType" to calibrationType,
            "deviceCapabilities" to listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO"),
            "deviceState" to currentDeviceState,
            "batteryLevel" to getBatteryLevel(),
            "storageAvailable" to getAvailableStorage(),
            "networkQuality" to getNetworkMetrics()
        )
        
        createSessionMarker(sessionId, NetworkProtocol.SyncMarkerTypes.CALIBRATION, enhancedData)
        
        Timber.i("Created calibration marker: $calibrationType for session: $sessionId")
    }
    
    /**
     * Create time reference marker for synchronization verification
     */
    fun createTimeReferenceMarker(sessionId: String) {
        val referenceData = mapOf(
            "systemTime" to System.currentTimeMillis(),
            "nanoTime" to System.nanoTime(),
            "syncTime" to getSynchronizedTimestamp(),
            "clockOffset" to getClockOffset(),
            "syncQuality" to getSyncQuality(),
            "uptimeMillis" to android.os.SystemClock.uptimeMillis(),
            "elapsedRealtime" to android.os.SystemClock.elapsedRealtime()
        )
        
        createSessionMarker(sessionId, NetworkProtocol.SyncMarkerTypes.TIME_REFERENCE, referenceData)
        
        Timber.i("Created time reference marker for session: $sessionId")
    }

    /**
     * Notify device ready for multi-device session with enhanced coordination
     */
    fun notifyDeviceReady(sessionId: String) {
        val readyData = mapOf(
            "sessionId" to sessionId,
            "deviceId" to getDeviceId(),
            "deviceName" to getDeviceName(),
            "coordinationId" to deviceCoordinationId,
            "deviceCapabilities" to listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO"),
            "deviceStatus" to "ready",
            "deviceState" to currentDeviceState,
            "syncQuality" to getSyncQuality(),
            "clockOffset" to getClockOffset(),
            "isSynchronized" to isSynchronized(),
            "batteryLevel" to getBatteryLevel(),
            "storageAvailable" to getAvailableStorage(),
            "networkMetrics" to getNetworkMetrics(),
            "timestamp" to getSynchronizedTimestamp()
        )
        
        sendSyncEvent("DEVICE_READY", readyData)
        
        // Update device state
        currentDeviceState = NetworkProtocol.DeviceStates.READY
        
        Timber.i("Device ready notification sent for session: $sessionId")
    }
    
    /**
     * Register as master clock device for coordinated starts
     */
    fun registerAsMasterClock(sessionId: String): Boolean {
        return try {
            val masterClockData = mapOf(
                "sessionId" to sessionId,
                "deviceId" to getDeviceId(),
                "deviceName" to getDeviceName(),
                "coordinationId" to deviceCoordinationId,
                "masterClockRequest" to true,
                "syncQuality" to getSyncQuality(),
                "clockOffset" to getClockOffset(),
                "networkQuality" to getNetworkMetrics(),
                "timestamp" to getSynchronizedTimestamp()
            )
            
            sendSyncEvent("MASTER_CLOCK_REQUEST", masterClockData)
            
            Timber.i("Requested master clock role for session: $sessionId")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to register as master clock")
            false
        }
    }
    
    /**
     * Handle master clock assignment
     */
    fun handleMasterClockAssignment(sessionId: String, masterDeviceId: String, isMaster: Boolean) {
        try {
            if (isMaster && masterDeviceId == getDeviceId()) {
                // This device is assigned as master clock
                networkMetrics["isMasterClock"] = true
                networkMetrics["masterClockSession"] = sessionId
                
                Timber.i("Assigned as master clock for session: $sessionId")
                
                // Notify callback
                syncEventCallback?.invoke("MASTER_CLOCK_ASSIGNED", mapOf(
                    "sessionId" to sessionId,
                    "isMaster" to true
                ))
                
            } else {
                // This device is a slave
                networkMetrics["isMasterClock"] = false
                networkMetrics["masterDeviceId"] = masterDeviceId
                networkMetrics["masterClockSession"] = sessionId
                
                Timber.i("Assigned as slave device for session: $sessionId, master: $masterDeviceId")
                
                // Notify callback
                syncEventCallback?.invoke("MASTER_CLOCK_ASSIGNED", mapOf(
                    "sessionId" to sessionId,
                    "isMaster" to false,
                    "masterDeviceId" to masterDeviceId
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling master clock assignment")
        }
    }
    
    /**
     * Coordinate multi-device start with master clock
     */
    fun coordinateMultiDeviceStart(sessionId: String, startTime: Long? = null): Boolean {
        return try {
            val isMaster = networkMetrics["isMasterClock"] as? Boolean ?: false
            
            if (isMaster) {
                // Master device coordinates the start
                val coordinatedStartTime = startTime ?: (getSynchronizedTimestamp() + 5000) // Start in 5 seconds
                
                val startCoordinationData = mapOf(
                    "sessionId" to sessionId,
                    "coordinatedStartTime" to coordinatedStartTime,
                    "masterDeviceId" to getDeviceId(),
                    "coordinationId" to deviceCoordinationId,
                    "command" to "COORDINATED_START",
                    "timestamp" to getSynchronizedTimestamp()
                )
                
                sendSyncEvent("COORDINATED_START", startCoordinationData)
                
                // Schedule own start
                scheduleCoordinatedStart(sessionId, coordinatedStartTime)
                
                Timber.i("Master coordinated start for session: $sessionId at time: $coordinatedStartTime")
                
            } else {
                // Slave device requests coordinated start
                val startRequestData = mapOf(
                    "sessionId" to sessionId,
                    "deviceId" to getDeviceId(),
                    "coordinationId" to deviceCoordinationId,
                    "requestType" to "COORDINATED_START_REQUEST",
                    "timestamp" to getSynchronizedTimestamp()
                )
                
                sendSyncEvent("COORDINATED_START_REQUEST", startRequestData)
                
                Timber.i("Requested coordinated start for session: $sessionId")
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Error coordinating multi-device start")
            false
        }
    }
    
    /**
     * Handle coordinated start command from master
     */
    fun handleCoordinatedStart(sessionId: String, startTime: Long, masterDeviceId: String) {
        try {
            val currentTime = getSynchronizedTimestamp()
            val timeUntilStart = startTime - currentTime
            
            if (timeUntilStart > 0) {
                // Schedule the start
                scheduleCoordinatedStart(sessionId, startTime)
                
                Timber.i("Scheduled coordinated start for session: $sessionId in ${timeUntilStart}ms")
                
                // Acknowledge receipt
                val ackData = mapOf(
                    "sessionId" to sessionId,
                    "deviceId" to getDeviceId(),
                    "masterDeviceId" to masterDeviceId,
                    "startTime" to startTime,
                    "acknowledged" to true,
                    "timestamp" to currentTime
                )
                
                sendSyncEvent("COORDINATED_START_ACK", ackData)
                
            } else {
                Timber.w("Coordinated start time has already passed for session: $sessionId")
                
                // Send error acknowledgment
                val errorAck = mapOf(
                    "sessionId" to sessionId,
                    "deviceId" to getDeviceId(),
                    "error" to "START_TIME_PASSED",
                    "startTime" to startTime,
                    "currentTime" to currentTime,
                    "timestamp" to currentTime
                )
                
                sendSyncEvent("COORDINATED_START_ERROR", errorAck)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling coordinated start")
        }
    }
    
    /**
     * Schedule coordinated start using coroutines
     */
    private fun scheduleCoordinatedStart(sessionId: String, startTime: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentTime = getSynchronizedTimestamp()
                val delay = startTime - currentTime
                
                if (delay > 0) {
                    delay(delay)
                }
                
                // Execute coordinated start
                executeCoordinatedStart(sessionId, startTime)
                
            } catch (e: Exception) {
                Timber.e(e, "Error in scheduled coordinated start")
            }
        }
    }
    
    /**
     * Execute the actual coordinated start
     */
    private fun executeCoordinatedStart(sessionId: String, scheduledStartTime: Long) {
        try {
            val actualStartTime = getSynchronizedTimestamp()
            val timingError = actualStartTime - scheduledStartTime
            
            // Create precise start marker
            createSessionMarker(sessionId, NetworkProtocol.SyncMarkerTypes.SESSION_START, mapOf(
                "coordinatedStart" to true,
                "scheduledStartTime" to scheduledStartTime,
                "actualStartTime" to actualStartTime,
                "timingError" to timingError,
                "isMasterClock" to (networkMetrics["isMasterClock"] as? Boolean ?: false)
            ))
            
            // Update device state
            currentDeviceState = NetworkProtocol.DeviceStates.RECORDING
            
            // Notify recording service
            commandCallback?.invoke("START_RECORDING:$sessionId")
            
            // Send start confirmation
            val confirmationData = mapOf(
                "sessionId" to sessionId,
                "deviceId" to getDeviceId(),
                "actualStartTime" to actualStartTime,
                "scheduledStartTime" to scheduledStartTime,
                "timingError" to timingError,
                "status" to "STARTED"
            )
            
            sendSyncEvent("COORDINATED_START_CONFIRMATION", confirmationData)
            
            Timber.i("Executed coordinated start for session: $sessionId, timing error: ${timingError}ms")
            
        } catch (e: Exception) {
            Timber.e(e, "Error executing coordinated start")
            
            // Send error notification
            sendSyncEvent("COORDINATED_START_ERROR", mapOf<String, Any>(
                "sessionId" to sessionId,
                "deviceId" to getDeviceId(),
                "error" to "EXECUTION_FAILED",
                "errorMessage" to (e.message ?: "Unknown error"),
                "timestamp" to getSynchronizedTimestamp()
            ))
        }
    }
    
    /**
     * Get device addressing information
     */
    fun getDeviceAddressing(): Map<String, Any> {
        return mapOf<String, Any>(
            "deviceId" to getDeviceId(),
            "deviceName" to getDeviceName(),
            "coordinationId" to deviceCoordinationId,
            "ipAddress" to getLocalIpAddress(),
            "macAddress" to getMacAddress(),
            "networkInterface" to getNetworkInterface(),
            "isMasterClock" to (networkMetrics["isMasterClock"] as? Boolean ?: false),
            "masterDeviceId" to (networkMetrics["masterDeviceId"] as? String ?: ""),
            "sessionId" to (networkMetrics["masterClockSession"] as? String ?: "")
        )
    }
    
    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "unknown"
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            Timber.w(e, "Failed to get local IP address")
            "unknown"
        }
    }
    
    /**
     * Get MAC address (requires permission)
     */
    private fun getMacAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val mac = networkInterface.hardwareAddress
                if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    return sb.toString()
                }
            }
            "unknown"
        } catch (e: Exception) {
            Timber.w(e, "Failed to get MAC address")
            "unknown"
        }
    }
    
    /**
     * Get network interface information
     */
    private fun getNetworkInterface(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    return networkInterface.name ?: "unknown"
                }
            }
            "unknown"
        } catch (e: Exception) {
            Timber.w(e, "Failed to get network interface")
            "unknown"
        }
    }

    // Enhanced Command Handler Methods
    
    /**
     * Handle CMD_START command from PC
     */
    private fun handleStartCommand(commandData: CommandMessage, message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling START command for session: ${commandData.sessionId}")
            
            val sessionId = commandData.sessionId ?: UUID.randomUUID().toString()
            val parameters = commandData.parameters
            
            // Validate device state
            if (currentDeviceState != NetworkProtocol.DeviceStates.READY && 
                currentDeviceState != NetworkProtocol.DeviceStates.IDLE) {
                sendErrorResponse(
                    NetworkProtocol.ErrorCodes.INVALID_STATE,
                    "Device not ready for recording. Current state: $currentDeviceState",
                    message.messageId
                )
                return
            }
            
            // Update device state
            currentDeviceState = NetworkProtocol.DeviceStates.RECORDING
            
            // Create sync marker for session start
            createSessionMarker(sessionId, NetworkProtocol.SyncMarkerTypes.SESSION_START, parameters)
            
            // Notify recording service to start
            commandCallback?.invoke("START_RECORDING:$sessionId")
            
            // Send success response
            sendCommandResponse(
                NetworkProtocol.Commands.CMD_START,
                mapOf(
                    "sessionId" to sessionId,
                    "status" to "started",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceState" to currentDeviceState
                ),
                message.messageId
            )
            
            Timber.i("Recording started for session: $sessionId")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling START command")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.HARDWARE_ERROR,
                "Failed to start recording: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle CMD_STOP command from PC
     */
    private fun handleStopCommand(commandData: CommandMessage, message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling STOP command for session: ${commandData.sessionId}")
            
            val sessionId = commandData.sessionId ?: "unknown"
            
            // Validate device state
            if (currentDeviceState != NetworkProtocol.DeviceStates.RECORDING) {
                sendErrorResponse(
                    NetworkProtocol.ErrorCodes.INVALID_STATE,
                    "Device not currently recording. Current state: $currentDeviceState",
                    message.messageId
                )
                return
            }
            
            // Update device state
            currentDeviceState = NetworkProtocol.DeviceStates.STOPPING
            
            // Create sync marker for session end
            createSessionMarker(sessionId, NetworkProtocol.SyncMarkerTypes.SESSION_END, commandData.parameters)
            
            // Notify recording service to stop
            commandCallback?.invoke("STOP_RECORDING:$sessionId")
            
            // Update state to idle after stopping
            currentDeviceState = NetworkProtocol.DeviceStates.IDLE
            
            // Send success response
            sendCommandResponse(
                NetworkProtocol.Commands.CMD_STOP,
                mapOf(
                    "sessionId" to sessionId,
                    "status" to "stopped",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceState" to currentDeviceState
                ),
                message.messageId
            )
            
            Timber.i("Recording stopped for session: $sessionId")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling STOP command")
            currentDeviceState = NetworkProtocol.DeviceStates.ERROR
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.HARDWARE_ERROR,
                "Failed to stop recording: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle CMD_STATUS command from PC
     */
    private fun handleStatusCommand(commandData: CommandMessage, message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling STATUS command")
            
            val statusResponse = StatusResponse(
                deviceId = getDeviceId(),
                deviceName = getDeviceName(),
                state = currentDeviceState,
                batteryLevel = getBatteryLevel(),
                storageAvailable = getAvailableStorage(),
                isRecording = currentDeviceState == NetworkProtocol.DeviceStates.RECORDING,
                capabilities = listOf("RGB_VIDEO", "THERMAL_VIDEO", "GSR_SENSOR", "AUDIO"),
                timestamp = System.currentTimeMillis(),
                networkMetrics = getNetworkMetrics()
            )
            
            sendCommandResponse(
                NetworkProtocol.Commands.CMD_STATUS,
                mapOf(
                    "deviceId" to statusResponse.deviceId,
                    "deviceName" to statusResponse.deviceName,
                    "state" to statusResponse.state,
                    "batteryLevel" to statusResponse.batteryLevel,
                    "storageAvailable" to statusResponse.storageAvailable,
                    "isRecording" to statusResponse.isRecording,
                    "capabilities" to statusResponse.capabilities,
                    "timestamp" to statusResponse.timestamp,
                    "networkMetrics" to statusResponse.networkMetrics
                ),
                message.messageId
            )
            
            Timber.d("Status response sent")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling STATUS command")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Failed to get device status: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle CMD_PREPARE command from PC
     */
    private fun handlePrepareCommand(commandData: CommandMessage, message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling PREPARE command")
            
            // Validate current state
            if (currentDeviceState != NetworkProtocol.DeviceStates.IDLE) {
                sendErrorResponse(
                    NetworkProtocol.ErrorCodes.INVALID_STATE,
                    "Device not idle. Current state: $currentDeviceState",
                    message.messageId
                )
                return
            }
            
            // Update device state
            currentDeviceState = NetworkProtocol.DeviceStates.PREPARING
            
            // Prepare recording components
            commandCallback?.invoke("PREPARE_RECORDING:${commandData.parameters}")
            
            // Update state to ready
            currentDeviceState = NetworkProtocol.DeviceStates.READY
            
            // Send success response
            sendCommandResponse(
                NetworkProtocol.Commands.CMD_PREPARE,
                mapOf(
                    "status" to "prepared",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceState" to currentDeviceState
                ),
                message.messageId
            )
            
            Timber.i("Device prepared for recording")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling PREPARE command")
            currentDeviceState = NetworkProtocol.DeviceStates.ERROR
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.HARDWARE_ERROR,
                "Failed to prepare device: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Handle CMD_RESET command from PC
     */
    private fun handleResetCommand(commandData: CommandMessage, message: EnhancedNetworkMessage) {
        try {
            Timber.d("Handling RESET command")
            
            // Reset device state
            currentDeviceState = NetworkProtocol.DeviceStates.IDLE
            
            // Clear any ongoing operations
            commandCallback?.invoke("RESET_DEVICE")
            
            // Clear sync event queue
            syncEventQueue.clear()
            
            // Reset network metrics
            networkMetrics.clear()
            messageSequenceNumber.set(0)
            connectionRetryCount.set(0)
            
            // Send success response
            sendCommandResponse(
                NetworkProtocol.Commands.CMD_RESET,
                mapOf(
                    "status" to "reset",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceState" to currentDeviceState
                ),
                message.messageId
            )
            
            Timber.i("Device reset completed")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling RESET command")
            sendErrorResponse(
                NetworkProtocol.ErrorCodes.NETWORK_ERROR,
                "Failed to reset device: ${e.message}",
                message.messageId
            )
        }
    }
    
    /**
     * Send command response with acknowledgment
     */
    private fun sendCommandResponse(command: String, responseData: Map<String, Any>, messageId: String?) {
        val response = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.COMMAND_ACK,
            payload = mapOf(
                "command" to command,
                "response" to responseData
            ),
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceId(),
            messageId = messageId ?: UUID.randomUUID().toString()
        )
        
        sendMessage(gson.toJson(response))
    }
    
    /**
     * Send command acknowledgment
     */
    private fun sendCommandAck(command: String, messageId: String?) {
        val ack = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.COMMAND_ACK,
            payload = mapOf(
                "command" to command,
                "status" to "acknowledged"
            ),
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceId(),
            messageId = messageId ?: UUID.randomUUID().toString()
        )
        
        sendMessage(gson.toJson(ack))
    }
    
    /**
     * Send error response
     */
    private fun sendErrorResponse(errorCode: String, errorMessage: String, messageId: String?) {
        val error = EnhancedNetworkMessage(
            type = NetworkProtocol.MessageTypes.ERROR,
            payload = ErrorMessage(
                errorCode = errorCode,
                errorMessage = errorMessage,
                details = mapOf(
                    "deviceId" to getDeviceId(),
                    "deviceState" to currentDeviceState
                )
            ),
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceId(),
            messageId = messageId ?: UUID.randomUUID().toString()
        )
        
        sendMessage(gson.toJson(error))
    }
    
    /**
     * Get current battery level
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get battery level")
            -1
        }
    }
    
    /**
     * Get available storage space in bytes
     */
    private fun getAvailableStorage(): Long {
        return try {
            val stat = android.os.StatFs(context.filesDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Timber.w(e, "Failed to get storage info")
            -1L
        }
    }

    /**
     * Connect to PC for data streaming
     */
    fun connectToPC(ipAddress: String) {
        try {
            pcAddress = InetAddress.getByName(ipAddress)
            dataStreamingSocket = DatagramSocket()
            isDataStreamingEnabled.set(true)
            Timber.i("NetworkManager connected to PC at $ipAddress for data streaming")
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup data streaming connection to PC")
            isDataStreamingEnabled.set(false)
        }
    }
    
    /**
     * Send a real-time data packet to the connected PC over UDP
     */
    fun sendDataPacket(packet: CommandProtocol.DataPacket) {
        if (!isDataStreamingEnabled.get()) {
            return
        }
        
        val socket = dataStreamingSocket ?: return
        val address = pcAddress ?: return
        
        // Send data packet in background to avoid blocking
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonData = CommandProtocol.toJson(packet)
                val buffer = jsonData.toByteArray()
                val datagramPacket = DatagramPacket(buffer, buffer.size, address, dataStreamingPort)
                socket.send(datagramPacket)
                
                // Update network metrics
                networkMetrics["last_data_packet_sent"] = System.currentTimeMillis()
                networkMetrics["total_packets_sent"] = (networkMetrics["total_packets_sent"] as? Long ?: 0L) + 1
                
            } catch (e: Exception) {
                // Avoid spamming logs for streaming errors, but log occasionally
                if (System.currentTimeMillis() % 10000 < 100) { // Log roughly every 10 seconds
                    Timber.w(e, "Error sending data packet to PC")
                }
            }
        }
    }
    
    /**
     * Enable or disable data streaming
     */
    fun setDataStreamingEnabled(enabled: Boolean) {
        isDataStreamingEnabled.set(enabled)
        if (enabled) {
            Timber.d("Data streaming enabled")
        } else {
            Timber.d("Data streaming disabled")
        }
    }
    
    /**
     * Check if data streaming is enabled and connected
     */
    fun isDataStreamingActive(): Boolean {
        return isDataStreamingEnabled.get() && pcAddress != null && dataStreamingSocket != null
    }
    
    /**
     * Get data streaming statistics
     */
    fun getDataStreamingStats(): Map<String, Any> {
        return mapOf(
            "enabled" to isDataStreamingEnabled.get(),
            "connected" to (pcAddress != null),
            "pc_address" to (pcAddress?.hostAddress ?: "none"),
            "streaming_port" to dataStreamingPort,
            "last_packet_sent" to (networkMetrics["last_data_packet_sent"] ?: 0L),
            "total_packets_sent" to (networkMetrics["total_packets_sent"] ?: 0L)
        )
    }

    /**
     * Enhanced cleanup with retry job cancellation and data streaming cleanup
     */
    fun cleanup() {
        try {
            isListening.set(false)
            isDiscoveryActive.set(false)
            
            closeConnection()
            
            serverSocket?.close()
            serverSocket = null
            
            // Cancel all background jobs
            serverJob?.cancel()
            discoveryJob?.cancel()
            messageHandlerJob?.cancel()
            heartbeatJob?.cancel()
            retryJob?.cancel()
            streamingJob?.cancel()
            
            // Cleanup data streaming
            isDataStreamingEnabled.set(false)
            dataStreamingSocket?.close()
            dataStreamingSocket = null
            pcAddress = null
            
            // Clear collections
            syncEventQueue.clear()
            networkMetrics.clear()
            
            Timber.d("Enhanced NetworkManager cleaned up")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during network cleanup")
        }
    }
}

/**
 * Data class for network messages
 */
data class NetworkMessage(
    val type: String,
    val payload: Any,
    val timestamp: Long
)

/**
 * Data class for time synchronization measurements
 */
data class SyncMeasurement(
    val clientSendTime: Long,
    val serverReceiveTime: Long,
    val serverSendTime: Long,
    val clientReceiveTime: Long,
    val roundTripTime: Long,
    val clockOffset: Long,
    val quality: Int
) {
    fun isValid(): Boolean {
        return roundTripTime > 0 && roundTripTime < 1000 // Valid if RTT < 1 second
    }
}