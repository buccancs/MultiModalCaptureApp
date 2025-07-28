package com.multimodal.capture.data.interfaces

import androidx.lifecycle.LiveData
import com.multimodal.capture.data.DeviceState
import java.io.File

/**
 * Generic interface for all data sources in the multi-modal capture system.
 * Provides a unified API for hardware managers to decouple RecordingService
 * from their concrete implementations.
 * 
 * This interface enables:
 * - Easy addition of new sensors by implementing this interface
 * - Simulation mode by providing fake implementations
 * - Consistent lifecycle management across all data sources
 * - Type-safe operations through the unified interface
 */
interface IDataSource {
    
    /**
     * LiveData for observing the current device state
     */
    val status: LiveData<DeviceState>
    
    /**
     * Initialize the data source and prepare it for use.
     * This should set up any necessary connections, permissions, or configurations.
     */
    fun initialize()
    
    /**
     * Start recording data from this source.
     * 
     * @param sessionId Unique identifier for the recording session
     * @param outputDirectory Directory where recorded data should be saved
     */
    fun startRecording(sessionId: String, outputDirectory: File)
    
    /**
     * Stop recording data from this source.
     * Should properly close any open files and release resources.
     */
    fun stopRecording()
    
    /**
     * Clean up resources and disconnect from the data source.
     * Should be called when the data source is no longer needed.
     */
    fun cleanup()
    
    /**
     * Get a human-readable name for this data source.
     * Used for logging and debugging purposes.
     */
    fun getDataSourceName(): String
    
    /**
     * Check if the data source is currently recording.
     * 
     * @return true if recording is active, false otherwise
     */
    fun isRecording(): Boolean
    
    /**
     * Check if the data source is properly initialized and ready to use.
     * 
     * @return true if ready, false otherwise
     */
    fun isReady(): Boolean
}