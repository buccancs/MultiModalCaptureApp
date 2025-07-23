package com.multimodal.capture.network

/**
 * Network Protocol Constants and Message Definitions
 * 
 * This file defines the core networking protocol for bi-directional communication
 * between PC controller and Android devices, including command types, message formats,
 * and synchronization mechanisms.
 */

object NetworkProtocol {
    
    // Core Command Types (as specified in requirements)
    object Commands {
        const val CMD_START = "CMD_START"
        const val CMD_STOP = "CMD_STOP"
        const val CMD_STATUS = "CMD_STATUS"
        const val SYNC_PING = "SYNC_PING"
        const val CMD_PREPARE = "CMD_PREPARE"
        const val CMD_RESET = "CMD_RESET"
    }
    
    // Message Types
    object MessageTypes {
        const val COMMAND = "COMMAND"
        const val COMMAND_ACK = "COMMAND_ACK"
        const val COMMAND_NACK = "COMMAND_NACK"
        const val STATUS_REQUEST = "STATUS_REQUEST"
        const val STATUS_RESPONSE = "STATUS_RESPONSE"
        const val PING = "PING"
        const val PONG = "PONG"
        const val SYNC_PING = "SYNC_PING"
        const val SYNC_PONG = "SYNC_PONG"
        const val SYNC_MARKER = "SYNC_MARKER"
        const val HEARTBEAT = "HEARTBEAT"
        const val HEARTBEAT_ACK = "HEARTBEAT_ACK"
        const val ERROR = "ERROR"
        const val DEVICE_READY = "DEVICE_READY"
        const val SESSION_MARKER = "SESSION_MARKER"
    }
    
    // Error Codes
    object ErrorCodes {
        const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
        const val INVALID_STATE = "INVALID_STATE"
        const val DEVICE_BUSY = "DEVICE_BUSY"
        const val INSUFFICIENT_STORAGE = "INSUFFICIENT_STORAGE"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val HARDWARE_ERROR = "HARDWARE_ERROR"
        const val NETWORK_ERROR = "NETWORK_ERROR"
        const val TIMEOUT = "TIMEOUT"
    }
    
    // Device States
    object DeviceStates {
        const val IDLE = "IDLE"
        const val PREPARING = "PREPARING"
        const val READY = "READY"
        const val RECORDING = "RECORDING"
        const val STOPPING = "STOPPING"
        const val ERROR = "ERROR"
        const val DISCONNECTED = "DISCONNECTED"
    }
    
    // Sync Marker Types
    object SyncMarkerTypes {
        const val SESSION_START = "SESSION_START"
        const val SESSION_END = "SESSION_END"
        const val CALIBRATION = "CALIBRATION"
        const val CUSTOM = "CUSTOM"
        const val TIME_REFERENCE = "TIME_REFERENCE"
    }
    
    // Network Configuration
    object NetworkConfig {
        const val DEFAULT_DISCOVERY_PORT = 8888
        const val DEFAULT_SERVER_PORT = 8889
        const val DEFAULT_HEARTBEAT_INTERVAL = 5000L // ms
        const val DEFAULT_CONNECTION_TIMEOUT = 10000L // ms
        const val DEFAULT_COMMAND_TIMEOUT = 5000L // ms
        const val MAX_RETRY_ATTEMPTS = 3
        const val SYNC_PING_INTERVAL = 1000L // ms
        const val MAX_CLOCK_OFFSET_MS = 50L // Maximum acceptable clock offset
    }
    
    // Protocol Version
    const val PROTOCOL_VERSION = "1.0"
}

/**
 * Enhanced Network Message with additional fields for robust communication
 */
data class EnhancedNetworkMessage(
    val type: String,
    val payload: Any,
    val timestamp: Long,
    val messageId: String = java.util.UUID.randomUUID().toString(),
    val deviceId: String? = null,
    val sessionId: String? = null,
    val protocolVersion: String = NetworkProtocol.PROTOCOL_VERSION,
    val requiresAck: Boolean = false,
    val retryCount: Int = 0,
    val maxRetries: Int = NetworkProtocol.NetworkConfig.MAX_RETRY_ATTEMPTS
)

/**
 * Command Message with specific command data
 */
data class CommandMessage(
    val command: String,
    val parameters: Map<String, Any> = emptyMap(),
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Status Response Message
 */
data class StatusResponse(
    val deviceId: String,
    val deviceName: String,
    val state: String,
    val batteryLevel: Int,
    val storageAvailable: Long,
    val isRecording: Boolean,
    val capabilities: List<String>,
    val timestamp: Long = System.currentTimeMillis(),
    val networkMetrics: Map<String, Any> = emptyMap()
)

/**
 * Sync Ping Message for time synchronization
 */
data class SyncPingMessage(
    val pingId: String = java.util.UUID.randomUUID().toString(),
    val clientTimestamp: Long,
    val sequenceNumber: Int = 0
)

/**
 * Sync Pong Response Message
 */
data class SyncPongMessage(
    val pingId: String,
    val clientTimestamp: Long,
    val serverReceiveTimestamp: Long,
    val serverSendTimestamp: Long,
    val sequenceNumber: Int = 0
)

/**
 * Sync Marker Message for synchronization verification
 */
data class SyncMarkerMessage(
    val markerId: String = java.util.UUID.randomUUID().toString(),
    val markerType: String,
    val sessionId: String,
    val timestamp: Long,
    val data: Map<String, Any> = emptyMap()
)

/**
 * Error Message
 */
data class ErrorMessage(
    val errorCode: String,
    val errorMessage: String,
    val details: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)