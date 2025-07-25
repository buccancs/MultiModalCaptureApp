package com.multimodal.capture.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.multimodal.capture.R
import com.multimodal.capture.capture.CameraManager
import com.multimodal.capture.capture.ThermalCameraManager
import com.multimodal.capture.capture.GSRSensorManager
import com.multimodal.capture.capture.AudioRecorderManager
import com.multimodal.capture.network.NetworkManager
import com.multimodal.capture.service.RecordingService
import com.multimodal.capture.ui.components.ThermalPreviewView
import com.multimodal.capture.utils.TimestampManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainViewModel manages the application state and coordinates between different capture modules.
 * Handles recording control, sensor status updates, and data synchronization.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = getApplication<Application>()
    
    // Service connection
    private var recordingService: RecordingService? = null
    
    // Capture managers
    private lateinit var cameraManager: CameraManager
    private lateinit var thermalCameraManager: ThermalCameraManager
    private lateinit var gsrSensorManager: GSRSensorManager
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var networkManager: NetworkManager
    private lateinit var timestampManager: TimestampManager
    
    // Recording state
    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean> = _isRecording
    
    private val _currentSessionId = MutableLiveData<String>("")
    val currentSessionId: LiveData<String> = _currentSessionId
    
    // Preview mode
    private val _previewMode = MutableLiveData<String>(context.getString(R.string.preview_mode_rgb))
    val previewMode: LiveData<String> = _previewMode
    
    // Status indicators
    private val _cameraStatus = MutableLiveData<String>(context.getString(R.string.status_camera_ready))
    val cameraStatus: LiveData<String> = _cameraStatus
    
    private val _thermalStatus = MutableLiveData<String>(context.getString(R.string.status_thermal_disconnected))
    val thermalStatus: LiveData<String> = _thermalStatus
    
    private val _gsrStatus = MutableLiveData<String>(context.getString(R.string.status_gsr_disconnected))
    val gsrStatus: LiveData<String> = _gsrStatus
    
    private val _networkStatus = MutableLiveData<String>(context.getString(R.string.status_network_disconnected))
    val networkStatus: LiveData<String> = _networkStatus
    
    // Connection status tracking
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected
    
    // Sensor values
    private val _gsrValue = MutableLiveData<Double>(0.0)
    val gsrValue: LiveData<Double> = _gsrValue
    
    private val _heartRate = MutableLiveData<Int>(0)
    val heartRate: LiveData<Int> = _heartRate
    
    private val _packetReceptionRate = MutableLiveData<Double>(0.0)
    val packetReceptionRate: LiveData<Double> = _packetReceptionRate
    
    // Error handling
    private val _errorMessage = MutableLiveData<String>("")
    val errorMessage: LiveData<String> = _errorMessage
    
    // Initialization status
    private val _initializationComplete = MutableLiveData<Boolean>(false)
    val initializationComplete: LiveData<Boolean> = _initializationComplete
    
    init {
        Timber.d("MainViewModel initialized")
    }
    
    /**
     * Set the bound RecordingService instance
     */
    fun setRecordingService(service: RecordingService?) {
        recordingService = service
        Timber.d("RecordingService set in MainViewModel: ${service != null}")
    }
    
    /**
     * Get the GSR manager from the bound service
     */
    fun getGSRManager(): GSRSensorManager? {
        return recordingService?.getGSRManager()
    }
    
    /**
     * Get the thermal camera manager from the bound service
     */
    fun getThermalManager(): ThermalCameraManager? {
        return recordingService?.getThermalManager()
    }
    
    /**
     * Get the camera manager (placeholder for future service integration)
     */
    fun getCameraManager(): CameraManager? {
        return if (::cameraManager.isInitialized) cameraManager else null
    }
    
    /**
     * Setup camera preview surface
     */
    fun setupCameraPreview(previewView: PreviewView) {
        if (::cameraManager.isInitialized) {
            cameraManager.getPreviewSurface()?.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    /**
     * Setup thermal camera preview ImageView
     */
    fun setupThermalPreview(imageView: android.widget.ImageView) {
        if (::thermalCameraManager.isInitialized) {
            thermalCameraManager.setPreviewImageView(imageView)
        }
    }
    
    /**
     * Initialize all capture modules
     */
    fun initializeCaptureModules(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        viewModelScope.launch {
            try {
                Timber.d("Initializing capture modules...")
                
                // Initialize timestamp manager
                timestampManager = TimestampManager()
                
                // Initialize camera manager
                cameraManager = CameraManager(context, lifecycleOwner)
                cameraManager.setStatusCallback { status ->
                    _cameraStatus.postValue(status)
                }
                
                // Initialize thermal camera manager
                Timber.d("Initializing ThermalCameraManager...")
                try {
                    thermalCameraManager = ThermalCameraManager(context)
                    thermalCameraManager.setStatusCallback { status ->
                        _thermalStatus.postValue(status)
                    }
                    Timber.d("ThermalCameraManager initialized successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize ThermalCameraManager")
                    throw e // Re-throw to be caught by outer try-catch
                }
                
                // Initialize GSR sensor manager
                gsrSensorManager = GSRSensorManager(context)
                gsrSensorManager.setStatusCallback { status ->
                    _gsrStatus.postValue(status)
                    // Update connection status based on GSR status
                    val isConnected = status.contains("connected", ignoreCase = true) || 
                                    status.contains("ready", ignoreCase = true)
                    _isConnected.postValue(isConnected)
                }
                gsrSensorManager.setDataCallback { gsrValue, heartRate, prr ->
                    _gsrValue.postValue(gsrValue)
                    _heartRate.postValue(heartRate)
                    _packetReceptionRate.postValue(prr)
                }
                
                // Initialize audio recorder manager
                audioRecorderManager = AudioRecorderManager(context)
                
                // Initialize network manager
                networkManager = NetworkManager(context)
                networkManager.setStatusCallback { status ->
                    _networkStatus.postValue(status)
                }
                networkManager.setCommandCallback { command ->
                    handleNetworkCommand(command)
                }
                
                Timber.d("All capture modules initialized successfully")
                
                // Notify that initialization is complete
                _initializationComplete.postValue(true)
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize capture modules")
                _errorMessage.postValue("Failed to initialize capture modules: ${e.message}")
                _initializationComplete.postValue(false)
            }
        }
    }
    
    /**
     * Start recording session with configured parameters
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        viewModelScope.launch {
            try {
                Timber.d("Starting recording session: $sessionId")
                
                // Load current recording configuration
                val settingsManager = com.multimodal.capture.utils.SettingsManager.getInstance(context)
                val recordingConfig = settingsManager.loadRecordingConfig()
                
                Timber.d("Using recording configuration: ${recordingConfig.getSummary()}")
                
                _currentSessionId.postValue(sessionId)
                _isRecording.postValue(true)
                
                // Set unified timestamp reference
                timestampManager.setSessionStartTime(startTimestamp)
                
                // Start capture modules based on configuration
                if (recordingConfig.camera.enabled) {
                    Timber.d("Starting camera recording with config: ${recordingConfig.camera.getSummary()}")
                    cameraManager.startRecording(sessionId, startTimestamp)
                } else {
                    Timber.d("Camera recording disabled in configuration")
                }
                
                if (recordingConfig.thermal.enabled) {
                    Timber.d("Starting thermal recording with config: ${recordingConfig.thermal.getSummary()}")
                    if (::thermalCameraManager.isInitialized) {
                        // Create output directory for thermal data
                        val outputDir = java.io.File(getApplication<Application>().filesDir, "sessions/$sessionId")
                        outputDir.mkdirs()
                        thermalCameraManager.startRecording(sessionId, outputDir)
                    } else {
                        Timber.w("ThermalCameraManager not initialized - skipping thermal recording")
                        _errorMessage.postValue("Thermal camera not initialized - thermal recording skipped")
                    }
                } else {
                    Timber.d("Thermal recording disabled in configuration")
                }
                
                if (recordingConfig.shimmer.enabled && recordingConfig.shimmer.isConfigured()) {
                    Timber.d("Starting GSR recording with config: ${recordingConfig.shimmer.getSummary()}")
                    gsrSensorManager.startRecording(sessionId, startTimestamp)
                } else {
                    Timber.d("GSR recording disabled or not configured")
                }
                
                if (recordingConfig.audio.enabled) {
                    Timber.d("Starting audio recording with config: ${recordingConfig.audio.getSummary()}")
                    audioRecorderManager.startRecording(sessionId, startTimestamp)
                } else {
                    Timber.d("Audio recording disabled in configuration")
                }
                
                // Notify network manager with metadata
                networkManager.notifyRecordingStarted(sessionId)
                
                Timber.d("Recording session started successfully with configuration")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to start recording")
                _errorMessage.postValue("Failed to start recording: ${e.message}")
                _isRecording.postValue(false)
            }
        }
    }
    
    /**
     * Connect to thermal camera after USB permission is granted
     */
    fun connectToThermalCamera() {
        viewModelScope.launch {
            try {
                Timber.d("Attempting to connect to thermal camera...")
                
                if (::thermalCameraManager.isInitialized) {
                    val connected = thermalCameraManager.connectToThermalCamera()
                    if (connected) {
                        Timber.d("Successfully connected to thermal camera")
                        _thermalStatus.postValue("Thermal Camera Connected")
                    } else {
                        Timber.w("Failed to connect to thermal camera")
                        _thermalStatus.postValue("Thermal Camera Connection Failed")
                    }
                } else {
                    Timber.e("ThermalCameraManager not initialized")
                    _errorMessage.postValue("Thermal camera manager not initialized")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error connecting to thermal camera")
                _errorMessage.postValue("Failed to connect to thermal camera: ${e.message}")
                _thermalStatus.postValue("Thermal Camera Error")
            }
        }
    }
    
    /**
     * Set thermal preview ImageView for displaying thermal frames
     */
    fun setThermalPreviewImageView(imageView: android.widget.ImageView?) {
        if (::thermalCameraManager.isInitialized && imageView != null) {
            thermalCameraManager.setPreviewImageView(imageView)
            Timber.d("Thermal preview ImageView set")
        } else if (imageView == null) {
            Timber.w("ImageView is null - cannot set thermal preview")
        } else {
            Timber.w("ThermalCameraManager not initialized when setting preview ImageView")
        }
    }

    /**
     * Set thermal preview ThermalPreviewView for enhanced thermal display
     */
    fun setThermalPreviewView(previewView: ThermalPreviewView?) {
        if (::thermalCameraManager.isInitialized && previewView != null) {
            thermalCameraManager.setThermalPreviewView(previewView)
            Timber.d("Thermal preview ThermalPreviewView set")
        } else if (previewView == null) {
            Timber.w("ThermalPreviewView is null - cannot set thermal preview")
        } else {
            Timber.w("ThermalCameraManager not initialized when setting preview ThermalPreviewView")
        }
    }

    /**
     * Stop recording session
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                Timber.d("Stopping recording session")
                
                val sessionId = _currentSessionId.value ?: ""
                
                // Stop all capture modules
                cameraManager.stopRecording()
                if (::thermalCameraManager.isInitialized) {
                    thermalCameraManager.stopRecording()
                } else {
                    Timber.w("ThermalCameraManager not initialized - skipping thermal recording stop")
                }
                gsrSensorManager.stopRecording()
                audioRecorderManager.stopRecording()
                
                // Notify network manager
                networkManager.notifyRecordingStopped(sessionId)
                
                _isRecording.postValue(false)
                _currentSessionId.postValue("")
                
                Timber.d("Recording session stopped successfully")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop recording")
                _errorMessage.postValue("Failed to stop recording: ${e.message}")
            }
        }
    }
    
    /**
     * Toggle preview mode between RGB and thermal
     */
    fun togglePreviewMode() {
        val currentMode = _previewMode.value
        val newMode = if (currentMode == context.getString(R.string.preview_mode_rgb)) {
            context.getString(R.string.preview_mode_thermal)
        } else {
            context.getString(R.string.preview_mode_rgb)
        }
        _previewMode.postValue(newMode)
        
        Timber.d("Preview mode changed to: $newMode")
    }
    
    /**
     * Scan for Bluetooth devices (GSR sensor)
     */
    fun scanForBluetoothDevices() {
        viewModelScope.launch {
            try {
                Timber.d("Starting Bluetooth device scan")
                gsrSensorManager.scanForDevices()
            } catch (e: Exception) {
                Timber.e(e, "Failed to scan for Bluetooth devices")
                _errorMessage.postValue("Failed to scan for devices: ${e.message}")
            }
        }
    }
    
    /**
     * Connect to a specific GSR device
     */
    fun connectToGSRDevice(deviceAddress: String) {
        viewModelScope.launch {
            try {
                Timber.d("Connecting to GSR device: $deviceAddress")
                gsrSensorManager.connectToDevice(deviceAddress)
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to GSR device")
                _errorMessage.postValue("Failed to connect to device: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect from GSR device
     */
    fun disconnectGSRDevice() {
        viewModelScope.launch {
            try {
                Timber.d("Disconnecting from GSR device")
                gsrSensorManager.disconnect()
                _isConnected.postValue(false)
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect from GSR device")
                _errorMessage.postValue("Failed to disconnect from device: ${e.message}")
            }
        }
    }
    
    /**
     * Handle network commands from PC
     */
    private fun handleNetworkCommand(command: String) {
        viewModelScope.launch {
            try {
                Timber.d("Received network command: $command")
                
                when (command) {
                    "CMD_START" -> {
                        if (_isRecording.value != true) {
                            val sessionId = "RemoteSession_${System.currentTimeMillis()}"
                            val startTimestamp = SystemClock.elapsedRealtimeNanos()
                            // Send recording metadata to PC before starting
                            networkManager.sendStatusUpdate(getRecordingMetadata())
                            startRecording(sessionId, startTimestamp)
                        }
                    }
                    "CMD_STOP" -> {
                        if (_isRecording.value == true) {
                            stopRecording()
                        }
                    }
                    "CMD_STATUS" -> {
                        networkManager.sendStatusUpdate(getCurrentStatus())
                    }
                    "CMD_GET_METADATA" -> {
                        networkManager.sendStatusUpdate(getRecordingMetadata())
                    }
                    "CMD_PREPARE" -> {
                        // Send current recording configuration to PC
                        val metadata = getRecordingMetadata()
                        networkManager.sendStatusUpdate(metadata)
                        Timber.d("Sent recording metadata to PC: $metadata")
                    }
                    else -> {
                        Timber.w("Unknown network command: $command")
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle network command: $command")
                _errorMessage.postValue("Failed to handle network command: ${e.message}")
            }
        }
    }
    
    /**
     * Get current system status with recording configuration metadata
     */
    private fun getCurrentStatus(): Map<String, Any> {
        return mapOf(
            "isRecording" to (_isRecording.value ?: false),
            "sessionId" to (_currentSessionId.value ?: ""),
            "cameraStatus" to (_cameraStatus.value ?: ""),
            "thermalStatus" to (_thermalStatus.value ?: ""),
            "gsrStatus" to (_gsrStatus.value ?: ""),
            "networkStatus" to (_networkStatus.value ?: ""),
            "isConnected" to (_isConnected.value ?: false),
            "gsrValue" to (_gsrValue.value ?: 0.0),
            "heartRate" to (_heartRate.value ?: 0),
            "packetReceptionRate" to (_packetReceptionRate.value ?: 0.0),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * Get current recording configuration metadata for PC transmission
     */
    fun getRecordingMetadata(): Map<String, Any> {
        return try {
            val settingsManager = com.multimodal.capture.utils.SettingsManager.getInstance(context)
            val recordingConfig = settingsManager.loadRecordingConfig()
            recordingConfig.toMetadataMap()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get recording metadata")
            mapOf(
                "error" to "Failed to load recording configuration",
                "timestamp" to System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Clean up capture modules when permissions are denied (without destroying ViewModel)
     */
    fun cleanupCaptureModules() {
        viewModelScope.launch {
            try {
                Timber.d("Cleaning up capture modules due to permission denial")
                
                if (_isRecording.value == true) {
                    stopRecording()
                }
                
                // Clean up managers
                if (::cameraManager.isInitialized) {
                    cameraManager.cleanup()
                }
                if (::thermalCameraManager.isInitialized) {
                    thermalCameraManager.cleanup()
                }
                if (::gsrSensorManager.isInitialized) {
                    gsrSensorManager.cleanup()
                }
                if (::audioRecorderManager.isInitialized) {
                    audioRecorderManager.cleanup()
                }
                if (::networkManager.isInitialized) {
                    networkManager.cleanup()
                }
                
                // Reset initialization state to allow re-initialization
                _initializationComplete.postValue(false)
                
                // Reset status values
                _cameraStatus.postValue("Camera Disconnected")
                _thermalStatus.postValue("Thermal Disconnected")
                _gsrStatus.postValue("GSR Disconnected")
                _networkStatus.postValue("Network Disconnected")
                
                Timber.d("Capture modules cleaned up successfully")
                
            } catch (e: Exception) {
                Timber.e(e, "Error during capture modules cleanup")
                _errorMessage.postValue("Failed to cleanup capture modules: ${e.message}")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Clean up resources when ViewModel is destroyed
        viewModelScope.launch {
            try {
                if (_isRecording.value == true) {
                    stopRecording()
                }
                
                // Clean up managers
                if (::cameraManager.isInitialized) {
                    cameraManager.cleanup()
                }
                if (::thermalCameraManager.isInitialized) {
                    thermalCameraManager.cleanup()
                }
                if (::gsrSensorManager.isInitialized) {
                    gsrSensorManager.cleanup()
                }
                if (::audioRecorderManager.isInitialized) {
                    audioRecorderManager.cleanup()
                }
                if (::networkManager.isInitialized) {
                    networkManager.cleanup()
                }
                
                Timber.d("MainViewModel cleaned up")
                
            } catch (e: Exception) {
                Timber.e(e, "Error during ViewModel cleanup")
            }
        }
    }
}