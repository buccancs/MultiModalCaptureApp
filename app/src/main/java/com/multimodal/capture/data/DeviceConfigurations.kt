package com.multimodal.capture.data

/**
 * Data classes for device configurations in the multimodal capture system.
 * These classes hold the settings for each device type and can be serialized for network transmission.
 */

/**
 * Camera configuration settings
 */
data class CameraConfig(
    val enabled: Boolean = true,
    val cameraId: String = "0",
    val resolution: String = "1920x1080",
    val fps: Int = 30,
    val autoIso: Boolean = true,
    val autoFocus: Boolean = true,
    val processingStage: String = "stage1",
    val videoQuality: String = "HD"
) {
    
    /**
     * Get resolution as width x height pair
     */
    fun getResolutionPair(): Pair<Int, Int> {
        val parts = resolution.split("x")
        return if (parts.size == 2) {
            Pair(parts[0].toIntOrNull() ?: 1920, parts[1].toIntOrNull() ?: 1080)
        } else {
            Pair(1920, 1080)
        }
    }
    
    /**
     * Check if this is a high-resolution configuration
     */
    fun isHighResolution(): Boolean {
        val (width, height) = getResolutionPair()
        return width >= 1920 && height >= 1080
    }
    
    /**
     * Get configuration summary for display
     */
    fun getSummary(): String {
        return if (enabled) {
            "Camera $cameraId: ${resolution} @ ${fps}fps"
        } else {
            "Camera disabled"
        }
    }
}

/**
 * Thermal/IR camera configuration settings
 */
data class IRConfig(
    val enabled: Boolean = false,
    val resolution: String = "256x192",
    val fps: Int = 30,
    val colorPalette: String = "iron",
    val autoRange: Boolean = true,
    val minTemperature: Float = -20f,
    val maxTemperature: Float = 120f
) {
    
    /**
     * Get resolution as width x height pair
     */
    fun getResolutionPair(): Pair<Int, Int> {
        val parts = resolution.split("x")
        return if (parts.size == 2) {
            Pair(parts[0].toIntOrNull() ?: 256, parts[1].toIntOrNull() ?: 192)
        } else {
            Pair(256, 192)
        }
    }
    
    /**
     * Get temperature range
     */
    fun getTemperatureRange(): Pair<Float, Float> {
        return Pair(minTemperature, maxTemperature)
    }
    
    /**
     * Get configuration summary for display
     */
    fun getSummary(): String {
        return if (enabled) {
            "Thermal: ${resolution} @ ${fps}fps (${colorPalette})"
        } else {
            "Thermal camera disabled"
        }
    }
}

/**
 * Shimmer GSR sensor configuration settings
 */
data class ShimmerConfig(
    val enabled: Boolean = false,
    val sampleRate: Int = 128,
    val enablePpgHeartRate: Boolean = true,
    val autoReconnect: Boolean = true,
    val deviceAddress: String = "",
    val deviceName: String = "",
    val connectionTimeout: Int = 15000,
    val dataBufferSize: Int = 1000
) {
    
    /**
     * Check if device is configured (has address)
     */
    fun isConfigured(): Boolean {
        return deviceAddress.isNotBlank()
    }
    
    /**
     * Check if this is a high-performance configuration
     */
    fun isHighPerformance(): Boolean {
        return sampleRate >= 128
    }
    
    /**
     * Get configuration summary for display
     */
    fun getSummary(): String {
        return if (enabled && isConfigured()) {
            "Shimmer: ${deviceName.ifBlank { "Unknown" }} @ ${sampleRate}Hz"
        } else if (enabled) {
            "Shimmer enabled (not configured)"
        } else {
            "Shimmer disabled"
        }
    }
}

/**
 * Audio recording configuration settings
 */
data class AudioConfig(
    val enabled: Boolean = true,
    val sampleRate: Int = 44100,
    val bitRate: Int = 128000,
    val channels: Int = 2,
    val format: String = "AAC"
) {
    
    /**
     * Check if this is high-quality audio
     */
    fun isHighQuality(): Boolean {
        return sampleRate >= 44100 && bitRate >= 128000
    }
    
    /**
     * Get configuration summary for display
     */
    fun getSummary(): String {
        return if (enabled) {
            "Audio: ${sampleRate}Hz, ${bitRate/1000}kbps"
        } else {
            "Audio disabled"
        }
    }
}

/**
 * Complete recording configuration containing all device settings
 */
data class RecordingConfig(
    val sessionIdPrefix: String = "Session",
    val camera: CameraConfig = CameraConfig(),
    val thermal: IRConfig = IRConfig(),
    val shimmer: ShimmerConfig = ShimmerConfig(),
    val audio: AudioConfig = AudioConfig(),
    val timestamp: Long = System.currentTimeMillis()
) {
    
    /**
     * Get list of enabled devices
     */
    fun getEnabledDevices(): List<String> {
        val devices = mutableListOf<String>()
        if (camera.enabled) devices.add("Camera")
        if (thermal.enabled) devices.add("Thermal")
        if (shimmer.enabled) devices.add("Shimmer")
        if (audio.enabled) devices.add("Audio")
        return devices
    }
    
    /**
     * Check if any devices are enabled
     */
    fun hasEnabledDevices(): Boolean {
        return camera.enabled || thermal.enabled || shimmer.enabled || audio.enabled
    }
    
    /**
     * Get configuration summary for display
     */
    fun getSummary(): String {
        val enabledDevices = getEnabledDevices()
        return if (enabledDevices.isNotEmpty()) {
            "Recording: ${enabledDevices.joinToString(", ")}"
        } else {
            "No devices enabled"
        }
    }
    
    /**
     * Convert to metadata map for network transmission
     */
    fun toMetadataMap(): Map<String, Any> {
        return mapOf(
            "sessionIdPrefix" to sessionIdPrefix,
            "timestamp" to timestamp,
            "enabledDevices" to getEnabledDevices(),
            "camera" to mapOf(
                "enabled" to camera.enabled,
                "cameraId" to camera.cameraId,
                "resolution" to camera.resolution,
                "fps" to camera.fps,
                "autoIso" to camera.autoIso,
                "autoFocus" to camera.autoFocus,
                "processingStage" to camera.processingStage,
                "videoQuality" to camera.videoQuality
            ),
            "thermal" to mapOf(
                "enabled" to thermal.enabled,
                "resolution" to thermal.resolution,
                "fps" to thermal.fps,
                "colorPalette" to thermal.colorPalette,
                "autoRange" to thermal.autoRange,
                "temperatureRange" to "${thermal.minTemperature}-${thermal.maxTemperature}"
            ),
            "shimmer" to mapOf(
                "enabled" to shimmer.enabled,
                "sampleRate" to shimmer.sampleRate,
                "enablePpgHeartRate" to shimmer.enablePpgHeartRate,
                "autoReconnect" to shimmer.autoReconnect,
                "deviceAddress" to shimmer.deviceAddress,
                "deviceName" to shimmer.deviceName
            ),
            "audio" to mapOf(
                "enabled" to audio.enabled,
                "sampleRate" to audio.sampleRate,
                "bitRate" to audio.bitRate,
                "channels" to audio.channels,
                "format" to audio.format
            )
        )
    }
}