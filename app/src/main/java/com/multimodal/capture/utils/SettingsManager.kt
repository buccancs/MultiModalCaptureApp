package com.multimodal.capture.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.multimodal.capture.data.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import timber.log.Timber

/**
 * SettingsManager handles persistence and retrieval of device configurations.
 * Provides a centralized way to manage all app settings and device configurations.
 */
class SettingsManager(private val context: Context) {
    
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()
    
    companion object {
        private const val KEY_RECORDING_CONFIG = "recording_config"
        private const val KEY_CAMERA_CONFIG = "camera_config"
        private const val KEY_THERMAL_CONFIG = "thermal_config"
        private const val KEY_SHIMMER_CONFIG = "shimmer_config"
        private const val KEY_AUDIO_CONFIG = "audio_config"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Load complete recording configuration from preferences
     */
    fun loadRecordingConfig(): RecordingConfig {
        return try {
            val configJson = preferences.getString(KEY_RECORDING_CONFIG, null)
            if (configJson != null) {
                gson.fromJson(configJson, RecordingConfig::class.java)
            } else {
                // Create default config from individual settings
                createDefaultRecordingConfig()
            }
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Failed to parse recording config, using defaults")
            createDefaultRecordingConfig()
        }
    }
    
    /**
     * Save complete recording configuration to preferences
     */
    fun saveRecordingConfig(config: RecordingConfig) {
        try {
            val configJson = gson.toJson(config)
            preferences.edit()
                .putString(KEY_RECORDING_CONFIG, configJson)
                .apply()
            
            // Also save individual configs for backward compatibility
            saveCameraConfig(config.camera)
            saveThermalConfig(config.thermal)
            saveShimmerConfig(config.shimmer)
            saveAudioConfig(config.audio)
            
            Timber.d("Recording configuration saved successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save recording configuration")
        }
    }
    
    /**
     * Load camera configuration from preferences
     */
    fun loadCameraConfig(): CameraConfig {
        return try {
            val configJson = preferences.getString(KEY_CAMERA_CONFIG, null)
            if (configJson != null) {
                gson.fromJson(configJson, CameraConfig::class.java)
            } else {
                createDefaultCameraConfig()
            }
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Failed to parse camera config, using defaults")
            createDefaultCameraConfig()
        }
    }
    
    /**
     * Save camera configuration to preferences
     */
    fun saveCameraConfig(config: CameraConfig) {
        try {
            val configJson = gson.toJson(config)
            preferences.edit()
                .putString(KEY_CAMERA_CONFIG, configJson)
                .apply()
            Timber.d("Camera configuration saved")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save camera configuration")
        }
    }
    
    /**
     * Load thermal camera configuration from preferences
     */
    fun loadThermalConfig(): IRConfig {
        return try {
            val configJson = preferences.getString(KEY_THERMAL_CONFIG, null)
            if (configJson != null) {
                gson.fromJson(configJson, IRConfig::class.java)
            } else {
                createDefaultThermalConfig()
            }
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Failed to parse thermal config, using defaults")
            createDefaultThermalConfig()
        }
    }
    
    /**
     * Save thermal camera configuration to preferences
     */
    fun saveThermalConfig(config: IRConfig) {
        try {
            val configJson = gson.toJson(config)
            preferences.edit()
                .putString(KEY_THERMAL_CONFIG, configJson)
                .apply()
            Timber.d("Thermal configuration saved")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save thermal configuration")
        }
    }
    
    /**
     * Load Shimmer configuration from preferences
     */
    fun loadShimmerConfig(): ShimmerConfig {
        return try {
            val configJson = preferences.getString(KEY_SHIMMER_CONFIG, null)
            if (configJson != null) {
                gson.fromJson(configJson, ShimmerConfig::class.java)
            } else {
                createDefaultShimmerConfig()
            }
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Failed to parse Shimmer config, using defaults")
            createDefaultShimmerConfig()
        }
    }
    
    /**
     * Save Shimmer configuration to preferences
     */
    fun saveShimmerConfig(config: ShimmerConfig) {
        try {
            val configJson = gson.toJson(config)
            preferences.edit()
                .putString(KEY_SHIMMER_CONFIG, configJson)
                .apply()
            Timber.d("Shimmer configuration saved")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save Shimmer configuration")
        }
    }
    
    /**
     * Load audio configuration from preferences
     */
    fun loadAudioConfig(): AudioConfig {
        return try {
            val configJson = preferences.getString(KEY_AUDIO_CONFIG, null)
            if (configJson != null) {
                gson.fromJson(configJson, AudioConfig::class.java)
            } else {
                createDefaultAudioConfig()
            }
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Failed to parse audio config, using defaults")
            createDefaultAudioConfig()
        }
    }
    
    /**
     * Save audio configuration to preferences
     */
    fun saveAudioConfig(config: AudioConfig) {
        try {
            val configJson = gson.toJson(config)
            preferences.edit()
                .putString(KEY_AUDIO_CONFIG, configJson)
                .apply()
            Timber.d("Audio configuration saved")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save audio configuration")
        }
    }
    
    /**
     * Create default recording configuration from preference values
     */
    private fun createDefaultRecordingConfig(): RecordingConfig {
        return RecordingConfig(
            sessionIdPrefix = preferences.getString("session_id_prefix", "Session") ?: "Session",
            camera = createDefaultCameraConfig(),
            thermal = createDefaultThermalConfig(),
            shimmer = createDefaultShimmerConfig(),
            audio = createDefaultAudioConfig()
        )
    }
    
    /**
     * Create default camera configuration from preference values
     */
    private fun createDefaultCameraConfig(): CameraConfig {
        return CameraConfig(
            enabled = preferences.getBoolean("enable_camera", true),
            cameraId = preferences.getString("camera_id", "0") ?: "0",
            resolution = preferences.getString("video_resolution", "1920x1080") ?: "1920x1080",
            fps = preferences.getString("video_fps", "30")?.toIntOrNull() ?: 30,
            autoIso = preferences.getBoolean("auto_iso", true),
            autoFocus = preferences.getBoolean("auto_focus", true),
            processingStage = preferences.getString("processing_stage", "stage1") ?: "stage1",
            videoQuality = preferences.getString("video_quality", "HD") ?: "HD"
        )
    }
    
    /**
     * Create default thermal camera configuration from preference values
     */
    private fun createDefaultThermalConfig(): IRConfig {
        return IRConfig(
            enabled = preferences.getBoolean("enable_thermal", false),
            resolution = preferences.getString("thermal_resolution", "256x192") ?: "256x192",
            fps = preferences.getString("thermal_fps", "30")?.toIntOrNull() ?: 30,
            colorPalette = preferences.getString("thermal_color_palette", "iron") ?: "iron",
            autoRange = preferences.getBoolean("thermal_auto_range", true)
        )
    }
    
    /**
     * Create default Shimmer configuration from preference values
     */
    private fun createDefaultShimmerConfig(): ShimmerConfig {
        return ShimmerConfig(
            enabled = preferences.getBoolean("enable_shimmer", false),
            sampleRate = preferences.getString("gsr_sample_rate", "128")?.toIntOrNull() ?: 128,
            enablePpgHeartRate = preferences.getBoolean("enable_ppg_heart_rate", true),
            autoReconnect = preferences.getBoolean("auto_reconnect_sensors", true)
        )
    }
    
    /**
     * Create default audio configuration from preference values
     */
    private fun createDefaultAudioConfig(): AudioConfig {
        return AudioConfig(
            enabled = true, // Audio is typically always enabled
            sampleRate = preferences.getString("audio_quality", "44100")?.toIntOrNull() ?: 44100
        )
    }
    
    /**
     * Get session ID prefix from preferences
     */
    fun getSessionIdPrefix(): String {
        return preferences.getString("session_id_prefix", "Session") ?: "Session"
    }
    
    /**
     * Check if simulation mode is enabled
     */
    fun isSimulationMode(): Boolean {
        return preferences.getBoolean("simulation_mode", false)
    }
    
    /**
     * Check if debug logging is enabled
     */
    fun isDebugLoggingEnabled(): Boolean {
        return preferences.getBoolean("enable_debug_logging", false)
    }
    
    /**
     * Get network discovery port
     */
    fun getDiscoveryPort(): Int {
        return preferences.getString("discovery_port", "8888")?.toIntOrNull() ?: 8888
    }
    
    /**
     * Get PC server port
     */
    fun getServerPort(): Int {
        return preferences.getString("pc_server_port", "8888")?.toIntOrNull() ?: 8888
    }
    
    /**
     * Check if network discovery is enabled
     */
    fun isNetworkDiscoveryEnabled(): Boolean {
        return preferences.getBoolean("enable_network_discovery", true)
    }
    
    /**
     * Update Shimmer device information
     */
    fun updateShimmerDevice(deviceAddress: String, deviceName: String) {
        val currentConfig = loadShimmerConfig()
        val updatedConfig = currentConfig.copy(
            deviceAddress = deviceAddress,
            deviceName = deviceName
        )
        saveShimmerConfig(updatedConfig)
        
        // Update the complete recording config as well
        val recordingConfig = loadRecordingConfig()
        val updatedRecordingConfig = recordingConfig.copy(shimmer = updatedConfig)
        saveRecordingConfig(updatedRecordingConfig)
        
        Timber.d("Updated Shimmer device: $deviceName ($deviceAddress)")
    }
    
    /**
     * Reset all configurations to defaults
     */
    fun resetToDefaults() {
        preferences.edit().clear().apply()
        Timber.d("All configurations reset to defaults")
    }
    
    /**
     * Export current configuration as JSON string
     */
    fun exportConfiguration(): String {
        val config = loadRecordingConfig()
        return gson.toJson(config)
    }
    
    /**
     * Import configuration from JSON string
     */
    fun importConfiguration(configJson: String): Boolean {
        return try {
            val config = gson.fromJson(configJson, RecordingConfig::class.java)
            saveRecordingConfig(config)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to import configuration")
            false
        }
    }
}