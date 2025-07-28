package com.multimodal.capture.data

/**
 * Unified device state enum for all hardware managers.
 * Provides type-safe state representation across the application.
 */
enum class DeviceState {
    /** Device is not connected or detected */
    DISCONNECTED,
    
    /** Device requires permissions to be granted */
    PERMISSION_REQUIRED,
    
    /** Device is in the process of connecting */
    CONNECTING,
    
    /** Device is connected and ready for use */
    READY,
    
    /** Device is actively streaming/recording data */
    STREAMING,
    
    /** Device encountered an error */
    ERROR
}

/**
 * Callback interface for hardware managers to report state changes
 * to the RecordingService.
 */
interface DeviceStateCallback {
    /**
     * Called when a device's state changes.
     * 
     * @param deviceType The type of device (e.g., "camera", "thermal", "gsr")
     * @param newState The new state of the device
     * @param message Optional descriptive message about the state change
     */
    fun onDeviceStateChanged(deviceType: String, newState: DeviceState, message: String = "")
}