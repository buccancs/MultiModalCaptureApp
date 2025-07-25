package com.multimodal.capture.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Defines the structured command and control protocol for PC communication.
 * This protocol uses JSON for all communication to ensure type safety and extensibility.
 */
object CommandProtocol {
    @JvmStatic
    val gson = Gson()

    // --- Commands from PC to App ---
    sealed class Command(val type: String) {
        data class StartRecording(
            @SerializedName("session_id") val sessionId: String?
        ) : Command("START_RECORDING")

        object StopRecording : Command("STOP_RECORDING")
        
        object GetStatus : Command("GET_STATUS")
        
        data class ConnectDevice(
            @SerializedName("device_type") val deviceType: String, // "GSR", "THERMAL", "CAMERA"
            @SerializedName("device_address") val deviceAddress: String?
        ) : Command("CONNECT_DEVICE")
        
        data class ConfigureDevice(
            @SerializedName("device_type") val deviceType: String,
            @SerializedName("configuration") val configuration: Map<String, Any>
        ) : Command("CONFIGURE_DEVICE")
        
        object DisconnectAllDevices : Command("DISCONNECT_ALL_DEVICES")
        
        data class SetDataStreaming(
            @SerializedName("enabled") val enabled: Boolean,
            @SerializedName("data_types") val dataTypes: List<String> // ["GSR", "THERMAL_TEMP", "CAMERA_FRAME"]
        ) : Command("SET_DATA_STREAMING")
    }

    // --- Responses from App to PC ---
    sealed class Response(val type: String) {
        data class Acknowledgment(
            val command: String,
            val message: String,
            val success: Boolean = true
        ) : Response("ACK")

        data class ErrorResponse(
            val command: String,
            @SerializedName("error_message") val errorMessage: String,
            @SerializedName("error_code") val errorCode: String? = null
        ) : Response("ERROR")

        data class StatusUpdate(
            @SerializedName("is_recording") val isRecording: Boolean,
            @SerializedName("session_id") val sessionId: String?,
            @SerializedName("device_status") val deviceStatus: Map<String, DeviceStatus>,
            @SerializedName("system_info") val systemInfo: SystemInfo,
            val timestamp: Long = System.currentTimeMillis()
        ) : Response("STATUS_UPDATE")
        
        data class DeviceConnected(
            @SerializedName("device_type") val deviceType: String,
            @SerializedName("device_name") val deviceName: String,
            @SerializedName("device_address") val deviceAddress: String?,
            @SerializedName("capabilities") val capabilities: List<String>
        ) : Response("DEVICE_CONNECTED")
        
        data class DeviceDisconnected(
            @SerializedName("device_type") val deviceType: String,
            @SerializedName("device_name") val deviceName: String,
            val reason: String
        ) : Response("DEVICE_DISCONNECTED")
    }
    
    // --- Real-time Data Packets from App to PC (sent over UDP) ---
    data class DataPacket(
        val type: String, // e.g., "GSR", "THERMAL_TEMP", "CAMERA_FRAME"
        val timestamp: Long,
        @SerializedName("session_id") val sessionId: String?,
        val payload: Map<String, Any>
    )
    
    // --- Supporting Data Classes ---
    data class DeviceStatus(
        val connected: Boolean,
        val status: String, // "CONNECTED", "DISCONNECTED", "CONNECTING", "ERROR"
        @SerializedName("last_data_timestamp") val lastDataTimestamp: Long?,
        @SerializedName("error_message") val errorMessage: String? = null,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class SystemInfo(
        @SerializedName("device_name") val deviceName: String,
        @SerializedName("app_version") val appVersion: String,
        @SerializedName("android_version") val androidVersion: String,
        @SerializedName("available_storage_mb") val availableStorageMb: Long,
        @SerializedName("battery_level") val batteryLevel: Int,
        @SerializedName("network_interface") val networkInterface: String,
        @SerializedName("ip_address") val ipAddress: String
    )
    
    // --- Discovery Protocol ---
    data class DiscoveryRequest(
        val message: String = "DISCOVER_MULTIMODAL_CAPTURE_APP",
        val version: String = "1.0"
    )
    
    data class DiscoveryResponse(
        @SerializedName("device_name") val deviceName: String,
        @SerializedName("app_name") val appName: String = "MultiModalCapture",
        @SerializedName("app_version") val appVersion: String,
        @SerializedName("tcp_port") val tcpPort: Int,
        @SerializedName("udp_streaming_port") val udpStreamingPort: Int,
        @SerializedName("device_capabilities") val deviceCapabilities: List<String>,
        @SerializedName("ip_address") val ipAddress: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // --- Utility Functions ---
    fun toJson(obj: Any): String = gson.toJson(obj)
    
    inline fun <reified T> fromJson(json: String): T? {
        return try {
            gson.fromJson(json, T::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse incoming command from PC
     */
    fun parseCommand(json: String): Command? {
        return try {
            val baseCommand = gson.fromJson(json, Map::class.java)
            val type = baseCommand["type"] as? String ?: return null
            
            when (type) {
                "START_RECORDING" -> fromJson<Command.StartRecording>(json)
                "STOP_RECORDING" -> Command.StopRecording
                "GET_STATUS" -> Command.GetStatus
                "CONNECT_DEVICE" -> fromJson<Command.ConnectDevice>(json)
                "CONFIGURE_DEVICE" -> fromJson<Command.ConfigureDevice>(json)
                "DISCONNECT_ALL_DEVICES" -> Command.DisconnectAllDevices
                "SET_DATA_STREAMING" -> fromJson<Command.SetDataStreaming>(json)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Create standard GSR data packet
     */
    fun createGSRDataPacket(
        gsrValue: Double,
        heartRate: Int,
        packetReceptionRate: Double,
        sessionId: String?
    ): DataPacket {
        return DataPacket(
            type = "GSR",
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            payload = mapOf(
                "gsr_value" to gsrValue,
                "heart_rate" to heartRate,
                "packet_reception_rate" to packetReceptionRate,
                "unit" to "microSiemens"
            )
        )
    }
    
    /**
     * Create thermal temperature data packet
     */
    fun createThermalDataPacket(
        maxTemp: Float,
        minTemp: Float,
        centerTemp: Float,
        sessionId: String?
    ): DataPacket {
        return DataPacket(
            type = "THERMAL_TEMP",
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            payload = mapOf(
                "max_temperature" to maxTemp,
                "min_temperature" to minTemp,
                "center_temperature" to centerTemp,
                "unit" to "celsius"
            )
        )
    }
    
    /**
     * Create camera frame data packet
     */
    fun createCameraFramePacket(
        frameNumber: Long,
        width: Int,
        height: Int,
        format: String,
        sessionId: String?
    ): DataPacket {
        return DataPacket(
            type = "CAMERA_FRAME",
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            payload = mapOf(
                "frame_number" to frameNumber,
                "width" to width,
                "height" to height,
                "format" to format
            )
        )
    }
}