package com.multimodal.capture.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.multimodal.capture.R
import com.multimodal.capture.data.managers.CameraManager
import com.multimodal.capture.data.managers.ThermalCameraManager
import com.multimodal.capture.data.managers.GSRSensorManager
import com.multimodal.capture.data.managers.AudioRecorderManager
import com.multimodal.capture.data.DeviceState
import com.multimodal.capture.data.network.NetworkManager
import com.multimodal.capture.service.RecordingService
import com.multimodal.capture.ui.components.ThermalPreviewView
import com.multimodal.capture.utils.TimestampManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MainViewModel acts as a bridge between the UI and RecordingService.
 * Exposes the service's unified device state machine to UI components.
 * No longer owns hardware managers - they are centralized in RecordingService.
 */
@HiltViewModel
class MainViewModel @Inject constructor(application: Application) : AndroidViewModel(application) {
    
    private val context = getApplication<Application>()
    
    // Service connection - Single source of truth for hardware state
    private var recordingService: RecordingService? = null
    
    // Recording state
    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean> = _isRecording
    
    private val _currentSessionId = MutableLiveData<String>("")
    val currentSessionId: LiveData<String> = _currentSessionId
    
    // Preview mode
    private val _previewMode = MutableLiveData<String>(context.getString(R.string.preview_mode_rgb))
    val previewMode: LiveData<String> = _previewMode
    
    // Device States - Bridge to RecordingService's unified state machine
    val cameraState: LiveData<DeviceState>
        get() = recordingService?.cameraState ?: MutableLiveData(DeviceState.DISCONNECTED)
    
    val thermalState: LiveData<DeviceState>
        get() = recordingService?.thermalState ?: MutableLiveData(DeviceState.DISCONNECTED)
    
    val gsrState: LiveData<DeviceState>
        get() = recordingService?.gsrState ?: MutableLiveData(DeviceState.DISCONNECTED)
    
    // Legacy status strings for backward compatibility with existing UI
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
     * Get the camera manager from the bound service
     */
    fun getCameraManager(): CameraManager? {
        return recordingService?.getCameraManager()
    }
    
    /**
     * Setup camera preview surface
     */
    fun setupCameraPreview(previewView: PreviewView) {
        recordingService?.getCameraManager()?.getPreviewSurface()?.setSurfaceProvider(previewView.surfaceProvider)
    }

    /**
     * Setup thermal camera preview ImageView
     */
    fun setupThermalPreview(imageView: android.widget.ImageView) {
        recordingService?.getThermalManager()?.setPreviewImageView(imageView)
    }
    
    /**
     * Initialize capture modules - Now delegates to RecordingService
     * Sets up observers for service's DeviceState LiveData
     */
    fun initializeCaptureModules(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        viewModelScope.launch {
            try {
                Timber.d("Setting up service observers...")
                
                // Initialize CameraManager in service if needed
                recordingService?.getCameraManager(lifecycleOwner)
                
                // Set up observers for DeviceState changes to update legacy status strings
                setupDeviceStateObservers(lifecycleOwner)
                
                // Set up GSR data callbacks if service is available
                setupGSRDataCallbacks()
                
                Timber.d("Service observers initialized successfully")
                _initializationComplete.postValue(true)
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize service observers")
                _errorMessage.postValue("Failed to initialize service observers: ${e.message}")
                _initializationComplete.postValue(false)
            }
        }
    }
    
    /**
     * Set up observers for DeviceState changes to maintain backward compatibility
     */
    private fun setupDeviceStateObservers(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        // Observe camera state changes
        cameraState.observe(lifecycleOwner) { state ->
            val statusText = mapDeviceStateToStatusString(state, "Camera")
            _cameraStatus.postValue(statusText)
        }
        
        // Observe thermal state changes
        thermalState.observe(lifecycleOwner) { state ->
            val statusText = mapDeviceStateToStatusString(state, "Thermal")
            _thermalStatus.postValue(statusText)
        }
        
        // Observe GSR state changes
        gsrState.observe(lifecycleOwner) { state ->
            val statusText = mapDeviceStateToStatusString(state, "GSR")
            _gsrStatus.postValue(statusText)
            
            // Update connection status based on GSR state
            val isConnected = state == DeviceState.READY || state == DeviceState.STREAMING
            _isConnected.postValue(isConnected)
        }
    }
    
    /**
     * Map DeviceState to legacy status strings for backward compatibility
     */
    private fun mapDeviceStateToStatusString(state: DeviceState, deviceType: String): String {
        return when (state) {
            DeviceState.DISCONNECTED -> "$deviceType disconnected"
            DeviceState.PERMISSION_REQUIRED -> "$deviceType requires permission"
            DeviceState.CONNECTING -> "$deviceType connecting..."
            DeviceState.READY -> "$deviceType ready"
            DeviceState.STREAMING -> "$deviceType streaming"
            DeviceState.ERROR -> "$deviceType error"
        }
    }
    
    /**
     * Set up GSR data callbacks from the service
     */
    private fun setupGSRDataCallbacks() {
        recordingService?.getGSRManager()?.setDataCallback { gsrValue, heartRate, prr ->
            _gsrValue.postValue(gsrValue)
            _heartRate.postValue(heartRate)
            _packetReceptionRate.postValue(prr)
        }
    }
    
    /**
     * Start recording session - Delegates to RecordingService's centralized control
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        viewModelScope.launch {
            try {
                Timber.d("Starting recording session via RecordingService: $sessionId")
                
                _currentSessionId.postValue(sessionId)
                
                // Delegate to RecordingService's centralized recording control
                val success = recordingService?.startRecordingSession(sessionId, startTimestamp) ?: false
                
                if (success) {
                    _isRecording.postValue(true)
                    Timber.d("Recording session started successfully via service")
                } else {
                    _isRecording.postValue(false)
                    _errorMessage.postValue("Failed to start recording session")
                    Timber.e("RecordingService failed to start recording session")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to start recording via service")
                _errorMessage.postValue("Failed to start recording: ${e.message}")
                _isRecording.postValue(false)
            }
        }
    }
    
    /**
     * Connect to thermal camera after USB permission is granted - Delegates to service
     */
    fun connectToThermalCamera() {
        viewModelScope.launch {
            try {
                Timber.d("Attempting to connect to thermal camera via service...")
                
                val thermalManager = recordingService?.getThermalManager()
                if (thermalManager != null) {
                    val connected = thermalManager.connectToThermalCamera()
                    if (connected) {
                        Timber.d("Successfully connected to thermal camera")
                        _thermalStatus.postValue("Thermal Camera Connected")
                    } else {
                        Timber.w("Failed to connect to thermal camera")
                        _thermalStatus.postValue("Thermal Camera Connection Failed")
                    }
                } else {
                    Timber.e("ThermalCameraManager not available from service")
                    _errorMessage.postValue("Thermal camera manager not available")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error connecting to thermal camera")
                _errorMessage.postValue("Failed to connect to thermal camera: ${e.message}")
                _thermalStatus.postValue("Thermal Camera Error")
            }
        }
    }
    
    /**
     * Set thermal preview ImageView for displaying thermal frames - Delegates to service
     */
    fun setThermalPreviewImageView(imageView: android.widget.ImageView?) {
        val thermalManager = recordingService?.getThermalManager()
        if (thermalManager != null && imageView != null) {
            thermalManager.setPreviewImageView(imageView)
            Timber.d("Thermal preview ImageView set via service")
        } else if (imageView == null) {
            Timber.w("ImageView is null - cannot set thermal preview")
        } else {
            Timber.w("ThermalCameraManager not available from service")
        }
    }

    /**
     * Set thermal preview ThermalPreviewView for enhanced thermal display - Delegates to service
     */
    fun setThermalPreviewView(previewView: ThermalPreviewView?) {
        val thermalManager = recordingService?.getThermalManager()
        if (thermalManager != null && previewView != null) {
            thermalManager.setThermalPreviewView(previewView)
            Timber.d("Thermal preview ThermalPreviewView set via service")
        } else if (previewView == null) {
            Timber.w("ThermalPreviewView is null - cannot set thermal preview")
        } else {
            Timber.w("ThermalCameraManager not available from service")
        }
    }

    /**
     * Stop recording session - Delegates to RecordingService's centralized control
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                Timber.d("Stopping recording session via RecordingService")
                
                // Delegate to RecordingService's centralized recording control
                val success = recordingService?.stopRecordingSession() ?: false
                
                if (success) {
                    _isRecording.postValue(false)
                    _currentSessionId.postValue("")
                    Timber.d("Recording session stopped successfully via service")
                } else {
                    _errorMessage.postValue("Failed to stop recording session")
                    Timber.e("RecordingService failed to stop recording session")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop recording via service")
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
     * Scan for Bluetooth devices (GSR sensor) - Delegates to service
     */
    fun scanForBluetoothDevices() {
        viewModelScope.launch {
            try {
                Timber.d("Starting Bluetooth device scan via service")
                val gsrManager = recordingService?.getGSRManager()
                if (gsrManager != null) {
                    gsrManager.scanForDevices()
                } else {
                    _errorMessage.postValue("GSR manager not available from service")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to scan for Bluetooth devices")
                _errorMessage.postValue("Failed to scan for devices: ${e.message}")
            }
        }
    }
    
    /**
     * Connect to a specific GSR device - Delegates to service
     */
    fun connectToGSRDevice(deviceAddress: String) {
        viewModelScope.launch {
            try {
                Timber.d("Connecting to GSR device via service: $deviceAddress")
                val gsrManager = recordingService?.getGSRManager()
                if (gsrManager != null) {
                    gsrManager.connectToDevice(deviceAddress)
                } else {
                    _errorMessage.postValue("GSR manager not available from service")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to GSR device")
                _errorMessage.postValue("Failed to connect to device: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect from GSR device - Delegates to service
     */
    fun disconnectGSRDevice() {
        viewModelScope.launch {
            try {
                Timber.d("Disconnecting from GSR device via service")
                val gsrManager = recordingService?.getGSRManager()
                if (gsrManager != null) {
                    gsrManager.disconnect()
                    _isConnected.postValue(false)
                } else {
                    _errorMessage.postValue("GSR manager not available from service")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect from GSR device")
                _errorMessage.postValue("Failed to disconnect from device: ${e.message}")
            }
        }
    }
    
    /**
     * Handle network commands from PC - Delegates to RecordingService
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
                            startRecording(sessionId, startTimestamp)
                        }
                    }
                    "CMD_STOP" -> {
                        if (_isRecording.value == true) {
                            stopRecording()
                        }
                    }
                    "CMD_STATUS" -> {
                        // Status is now handled by RecordingService
                        Timber.d("Status request handled by RecordingService")
                    }
                    "CMD_GET_METADATA" -> {
                        // Metadata is now handled by RecordingService
                        Timber.d("Metadata request handled by RecordingService")
                    }
                    "CMD_PREPARE" -> {
                        // Preparation is now handled by RecordingService
                        Timber.d("Prepare command handled by RecordingService")
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
     * Clean up capture modules when permissions are denied - Now delegates to RecordingService
     */
    fun cleanupCaptureModules() {
        viewModelScope.launch {
            try {
                Timber.d("Cleaning up capture modules via RecordingService")
                
                if (_isRecording.value == true) {
                    stopRecording()
                }
                
                // Cleanup is now handled by RecordingService when it's destroyed
                // No direct manager cleanup needed in ViewModel
                
                // Reset initialization state to allow re-initialization
                _initializationComplete.postValue(false)
                
                // Reset status values
                _cameraStatus.postValue("Camera Disconnected")
                _thermalStatus.postValue("Thermal Disconnected")
                _gsrStatus.postValue("GSR Disconnected")
                _networkStatus.postValue("Network Disconnected")
                
                Timber.d("Capture modules cleanup delegated to RecordingService")
                
            } catch (e: Exception) {
                Timber.e(e, "Error during capture modules cleanup")
                _errorMessage.postValue("Failed to cleanup capture modules: ${e.message}")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Clean up resources when ViewModel is destroyed - Now delegates to RecordingService
        viewModelScope.launch {
            try {
                if (_isRecording.value == true) {
                    stopRecording()
                }
                
                // Cleanup is now handled by RecordingService
                // No direct manager cleanup needed in ViewModel
                
                Timber.d("MainViewModel cleaned up - cleanup delegated to RecordingService")
                
            } catch (e: Exception) {
                Timber.e(e, "Error during ViewModel cleanup")
            }
        }
    }
}