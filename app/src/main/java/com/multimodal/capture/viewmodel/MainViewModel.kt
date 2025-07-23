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
import com.multimodal.capture.utils.TimestampManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainViewModel manages the application state and coordinates between different capture modules.
 * Handles recording control, sensor status updates, and data synchronization.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = getApplication<Application>()
    
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
    
    init {
        Timber.d("MainViewModel initialized")
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
     * Initialize all capture modules
     */
    fun initializeCaptureModules() {
        viewModelScope.launch {
            try {
                Timber.d("Initializing capture modules...")
                
                // Initialize timestamp manager
                timestampManager = TimestampManager()
                
                // Initialize camera manager
                cameraManager = CameraManager(context)
                cameraManager.setStatusCallback { status ->
                    _cameraStatus.postValue(status)
                }
                
                // Initialize thermal camera manager
                thermalCameraManager = ThermalCameraManager(context)
                thermalCameraManager.setStatusCallback { status ->
                    _thermalStatus.postValue(status)
                }
                
                // Initialize GSR sensor manager
                gsrSensorManager = GSRSensorManager(context)
                gsrSensorManager.setStatusCallback { status ->
                    _gsrStatus.postValue(status)
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
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize capture modules")
                _errorMessage.postValue("Failed to initialize capture modules: ${e.message}")
            }
        }
    }
    
    /**
     * Start recording session
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        viewModelScope.launch {
            try {
                Timber.d("Starting recording session: $sessionId")
                
                _currentSessionId.postValue(sessionId)
                _isRecording.postValue(true)
                
                // Set unified timestamp reference
                timestampManager.setSessionStartTime(startTimestamp)
                
                // Start all capture modules
                cameraManager.startRecording(sessionId, startTimestamp)
                thermalCameraManager.startRecording(sessionId, startTimestamp)
                gsrSensorManager.startRecording(sessionId, startTimestamp)
                audioRecorderManager.startRecording(sessionId, startTimestamp)
                
                // Notify network manager
                networkManager.notifyRecordingStarted(sessionId)
                
                Timber.d("Recording session started successfully")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to start recording")
                _errorMessage.postValue("Failed to start recording: ${e.message}")
                _isRecording.postValue(false)
            }
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
                thermalCameraManager.stopRecording()
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
     * Get current system status
     */
    private fun getCurrentStatus(): Map<String, Any> {
        return mapOf(
            "isRecording" to (_isRecording.value ?: false),
            "sessionId" to (_currentSessionId.value ?: ""),
            "cameraStatus" to (_cameraStatus.value ?: ""),
            "thermalStatus" to (_thermalStatus.value ?: ""),
            "gsrStatus" to (_gsrStatus.value ?: ""),
            "networkStatus" to (_networkStatus.value ?: ""),
            "gsrValue" to (_gsrValue.value ?: 0.0),
            "heartRate" to (_heartRate.value ?: 0),
            "packetReceptionRate" to (_packetReceptionRate.value ?: 0.0)
        )
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Clean up resources
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