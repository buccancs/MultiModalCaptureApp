package com.multimodal.capture.viewmodel

import android.app.Application
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.multimodal.capture.capture.CameraManager
import com.multimodal.capture.capture.ThermalCameraManager
import com.multimodal.capture.utils.TimestampManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * PreviewViewModel manages multi-camera preview functionality.
 * Handles switching between Front, Back Main, Wide Angle, Telephoto, and Thermal cameras.
 */
class PreviewViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = getApplication<Application>()
    
    // Camera managers
    private lateinit var cameraManager: CameraManager
    private lateinit var thermalCameraManager: ThermalCameraManager
    private lateinit var timestampManager: TimestampManager
    
    // LiveData for UI updates
    private val _cameraStatus = MutableLiveData<String>("Camera Disconnected")
    val cameraStatus: LiveData<String> = _cameraStatus
    
    private val _thermalCameraStatus = MutableLiveData<String>("Thermal Disconnected")
    val thermalCameraStatus: LiveData<String> = _thermalCameraStatus
    
    private val _currentCameraInfo = MutableLiveData<CameraInfo>()
    val currentCameraInfo: LiveData<CameraInfo> = _currentCameraInfo
    
    private val _availableCameras = MutableLiveData<List<Int>>()
    val availableCameras: LiveData<List<Int>> = _availableCameras
    
    private val _isThermalCameraAvailable = MutableLiveData<Boolean>(false)
    val isThermalCameraAvailable: LiveData<Boolean> = _isThermalCameraAvailable
    
    private val _errorMessage = MutableLiveData<String>("")
    val errorMessage: LiveData<String> = _errorMessage
    
    // Current camera state
    private var currentCameraId = 0 // Default to back main camera
    private var isInitialized = false
    
    /**
     * Data class for camera information
     */
    data class CameraInfo(
        val name: String,
        val resolution: String,
        val fps: String
    )
    
    init {
        Timber.d("PreviewViewModel initialized")
    }
    
    /**
     * Initialize the camera system
     */
    fun initializeCameraSystem(lifecycleOwner: LifecycleOwner) {
        if (isInitialized) {
            Timber.d("Camera system already initialized")
            return
        }
        
        viewModelScope.launch {
            try {
                Timber.d("Initializing camera system...")
                
                // Initialize timestamp manager
                timestampManager = TimestampManager()
                
                // Initialize camera manager
                cameraManager = CameraManager(context, lifecycleOwner)
                cameraManager.setStatusCallback { status ->
                    _cameraStatus.postValue(status)
                }
                
                // Initialize thermal camera manager
                thermalCameraManager = ThermalCameraManager(context)
                thermalCameraManager.setStatusCallback { status ->
                    _thermalCameraStatus.postValue(status)
                }
                
                // Discover available cameras
                discoverAvailableCameras()
                
                // Check thermal camera availability
                checkThermalCameraAvailability()
                
                isInitialized = true
                _cameraStatus.postValue("Camera System Ready")
                
                Timber.d("Camera system initialized successfully")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize camera system")
                _errorMessage.postValue("Failed to initialize camera system: ${e.message}")
            }
        }
    }
    
    /**
     * Switch to RGB camera with specified ID
     */
    fun switchToRGBCamera(cameraId: Int, previewView: PreviewView) {
        if (!isInitialized) {
            _errorMessage.postValue("Camera system not initialized")
            return
        }
        
        viewModelScope.launch {
            try {
                Timber.d("Switching to RGB camera ID: $cameraId")
                
                // Stop thermal camera if active
                if (thermalCameraManager.isConnected()) {
                    thermalCameraManager.cleanup()
                }
                
                // Switch camera
                cameraManager.switchCamera(cameraId)
                
                // Setup preview
                cameraManager.getPreviewSurface()?.setSurfaceProvider(previewView.surfaceProvider)
                
                currentCameraId = cameraId
                
                val cameraName = getCameraName(cameraId)
                _cameraStatus.postValue("$cameraName Ready")
                
                Timber.d("Successfully switched to $cameraName")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to switch to RGB camera $cameraId")
                _errorMessage.postValue("Failed to switch camera: ${e.message}")
            }
        }
    }
    
    /**
     * Switch to thermal camera
     */
    fun switchToThermalCamera(imageView: ImageView) {
        if (!isInitialized) {
            _errorMessage.postValue("Camera system not initialized")
            return
        }
        
        viewModelScope.launch {
            try {
                Timber.d("Switching to thermal camera")
                
                // Setup thermal camera preview
                thermalCameraManager.setPreviewImageView(imageView)
                
                // Try to connect to thermal camera
                val connected = thermalCameraManager.connectToThermalCamera()
                if (connected) {
                    _thermalCameraStatus.postValue("Thermal Camera Ready")
                    Timber.d("Successfully switched to thermal camera")
                } else {
                    _thermalCameraStatus.postValue("Thermal Camera Connection Failed")
                    _errorMessage.postValue("Failed to connect to thermal camera")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to switch to thermal camera")
                _errorMessage.postValue("Failed to switch to thermal camera: ${e.message}")
            }
        }
    }
    
    /**
     * Update current camera information
     */
    fun updateCurrentCameraInfo(cameraInfo: CameraInfo) {
        _currentCameraInfo.postValue(cameraInfo)
    }
    
    /**
     * Discover available RGB cameras
     */
    private fun discoverAvailableCameras() {
        try {
            // For now, assume standard camera configuration
            // In a real implementation, you would query CameraManager for available cameras
            val availableCameraIds = mutableListOf<Int>()
            
            // Most devices have these cameras
            availableCameraIds.add(0) // Back main camera
            availableCameraIds.add(1) // Front camera
            
            // Some devices have additional cameras
            // You would need to check camera characteristics to determine if they exist
            availableCameraIds.add(2) // Wide angle (if available)
            availableCameraIds.add(3) // Telephoto (if available)
            
            _availableCameras.postValue(availableCameraIds)
            
            Timber.d("Discovered cameras: ${availableCameraIds.joinToString(", ")}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to discover available cameras")
            _availableCameras.postValue(listOf(0, 1)) // Fallback to basic cameras
        }
    }
    
    /**
     * Check thermal camera availability
     */
    private fun checkThermalCameraAvailability() {
        try {
            // Check if thermal camera is available
            // This would typically involve checking USB devices or SDK availability
            val isAvailable = thermalCameraManager.isConnected() || checkForThermalCameraHardware()
            _isThermalCameraAvailable.postValue(isAvailable)
            
            Timber.d("Thermal camera available: $isAvailable")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to check thermal camera availability")
            _isThermalCameraAvailable.postValue(false)
        }
    }
    
    /**
     * Check for thermal camera hardware
     */
    private fun checkForThermalCameraHardware(): Boolean {
        // This would check for connected USB thermal cameras
        // For now, return true to allow testing
        return true
    }
    
    /**
     * Get camera name from ID
     */
    private fun getCameraName(cameraId: Int): String {
        return when (cameraId) {
            0 -> "Back Camera (Main)"
            1 -> "Front Camera"
            2 -> "Wide Angle Camera"
            3 -> "Telephoto Camera"
            else -> "Camera $cameraId"
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        viewModelScope.launch {
            try {
                if (isInitialized) {
                    if (::cameraManager.isInitialized) {
                        cameraManager.cleanup()
                    }
                    if (::thermalCameraManager.isInitialized) {
                        thermalCameraManager.cleanup()
                    }
                    
                    isInitialized = false
                    _cameraStatus.postValue("Camera Disconnected")
                    _thermalCameraStatus.postValue("Thermal Disconnected")
                    
                    Timber.d("PreviewViewModel cleanup completed")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during PreviewViewModel cleanup")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}