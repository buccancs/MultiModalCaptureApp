package com.multimodal.capture.capture

import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.multimodal.capture.R
import com.multimodal.capture.utils.TimestampManager
import com.multimodal.capture.network.NetworkManager
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraManager handles RGB video capture using Camera2 API and CameraX.
 * Provides high-definition video recording with synchronized timestamps.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val networkManager: NetworkManager? = null
) {
    
    private val timestampManager = TimestampManager()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    
    // Recording state
    private val isRecording = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // Camera selection
    private var currentCameraId: Int = 0 // Default to back main camera
    
    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    
    init {
        Timber.d("CameraManager initialized")
        initializeCamera()
    }
    
    /**
     * Initialize camera provider
     */
    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupCamera()
                updateStatus(context.getString(R.string.status_camera_ready))
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize camera")
                updateStatus("Camera Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Setup camera use cases
     */
    private fun setupCamera() {
        try {
            val cameraProvider = this.cameraProvider ?: return
            
            // Preview use case
            preview = Preview.Builder()
                .setTargetResolution(Size(1920, 1080))
                .build()
            
            // Video capture use case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            
            videoCapture = VideoCapture.withOutput(recorder)
            
            // Select camera based on current camera ID
            val cameraSelector = getCameraSelector(currentCameraId)
            
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )
            
            Timber.d("Camera setup completed")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup camera")
            updateStatus("Camera Setup Error: ${e.message}")
        }
    }
    
    /**
     * Start video recording
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        if (isRecording.get()) {
            Timber.w("Recording already in progress")
            return
        }
        
        try {
            currentSessionId = sessionId
            timestampManager.setSessionStartTime(startTimestamp)
            
            // Get PC-corrected timestamp and sync quality for better alignment
            val localStartTime = timestampManager.getCurrentTimestamp()
            val pcCorrectedStartTime = networkManager?.getSynchronizedTimestamp() ?: localStartTime
            val syncQuality = networkManager?.getSyncQuality() ?: 0
            val clockOffset = networkManager?.getClockOffset() ?: 0L
            
            val videoCapture = this.videoCapture ?: throw IllegalStateException("VideoCapture not initialized")
            
            // Create output file
            val outputDir = File(context.getExternalFilesDir(null), "recordings")
            outputDir.mkdirs()
            
            val outputFile = File(outputDir, "${sessionId}_rgb_video.mp4")
            val metadataFile = File(outputDir, "${sessionId}_rgb_video_metadata.json")
            
            // Create timestamp metadata for post-processing analysis
            createTimestampMetadata(metadataFile, sessionId, localStartTime, pcCorrectedStartTime, syncQuality, clockOffset)
            
            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            
            // Start recording
            recording = videoCapture.output
                .prepareRecording(context, outputOptions)
                .apply {
                    // Enable audio recording if permission is available
                    if (ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    handleRecordingEvent(recordEvent)
                }
            
            isRecording.set(true)
            updateStatus(context.getString(R.string.status_camera_recording))
            
            Timber.d("Video recording started: ${outputFile.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start video recording")
            updateStatus("Recording Error: ${e.message}")
        }
    }
    
    /**
     * Stop video recording
     */
    fun stopRecording() {
        if (!isRecording.get()) {
            Timber.w("No recording in progress")
            return
        }
        
        try {
            recording?.stop()
            recording = null
            
            isRecording.set(false)
            updateStatus(context.getString(R.string.status_camera_ready))
            
            Timber.d("Video recording stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop video recording")
            updateStatus("Stop Recording Error: ${e.message}")
        }
    }
    
    /**
     * Handle recording events
     */
    private fun handleRecordingEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Timber.d("Recording started")
            }
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Timber.e("Recording finalized with error: ${event.error}")
                    updateStatus("Recording Error: ${event.cause?.message}")
                } else {
                    Timber.d("Recording finalized successfully: ${event.outputResults.outputUri}")
                }
            }
            is VideoRecordEvent.Status -> {
                // Update recording status if needed
                val duration = event.recordingStats.recordedDurationNanos
                Timber.v("Recording duration: ${duration / 1_000_000}ms")
            }
            is VideoRecordEvent.Pause -> {
                Timber.d("Recording paused")
            }
            is VideoRecordEvent.Resume -> {
                Timber.d("Recording resumed")
            }
        }
    }
    
    /**
     * Get camera selector based on camera ID
     */
    private fun getCameraSelector(cameraId: Int): CameraSelector {
        return when (cameraId) {
            0 -> CameraSelector.DEFAULT_BACK_CAMERA  // Back main camera
            1 -> CameraSelector.DEFAULT_FRONT_CAMERA // Front camera
            2 -> CameraSelector.Builder()            // Wide angle camera (if available)
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            3 -> CameraSelector.Builder()            // Telephoto camera (if available)
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    
    /**
     * Switch to different camera
     */
    fun switchCamera(cameraId: Int) {
        try {
            Timber.d("Switching to camera ID: $cameraId")
            
            if (currentCameraId == cameraId) {
                Timber.d("Already using camera ID: $cameraId")
                return
            }
            
            currentCameraId = cameraId
            
            // Re-setup camera with new camera ID
            setupCamera()
            
            val cameraName = when (cameraId) {
                0 -> "Back Camera (Main)"
                1 -> "Front Camera"
                2 -> "Wide Angle Camera"
                3 -> "Telephoto Camera"
                else -> "Camera $cameraId"
            }
            
            updateStatus("$cameraName Ready")
            Timber.d("Successfully switched to $cameraName")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to switch to camera $cameraId")
            updateStatus("Camera Switch Error: ${e.message}")
        }
    }
    
    /**
     * Get preview surface for UI
     */
    fun getPreviewSurface(): Preview? {
        return preview
    }
    
    /**
     * Set status callback
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }
    
    /**
     * Create timestamp metadata file for post-processing analysis
     */
    private fun createTimestampMetadata(
        metadataFile: File,
        sessionId: String,
        localStartTime: Long,
        pcCorrectedStartTime: Long,
        syncQuality: Int,
        clockOffset: Long
    ) {
        try {
            val metadata = mapOf(
                "sessionId" to sessionId,
                "recordingType" to "rgb_video",
                "timestamps" to mapOf(
                    "localStartTime" to localStartTime,
                    "pcCorrectedStartTime" to pcCorrectedStartTime,
                    "systemTimeMillis" to System.currentTimeMillis(),
                    "elapsedRealtimeNanos" to android.os.SystemClock.elapsedRealtimeNanos()
                ),
                "synchronization" to mapOf(
                    "syncQuality" to syncQuality,
                    "clockOffset" to clockOffset,
                    "syncTimestamp" to System.currentTimeMillis(),
                    "isSyncAcceptable" to (networkManager?.isSynchronized() ?: false)
                ),
                "device" to mapOf(
                    "model" to android.os.Build.MODEL,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "androidVersion" to android.os.Build.VERSION.RELEASE
                ),
                "camera" to mapOf(
                    "resolution" to "1920x1080", // Default HD resolution
                    "frameRate" to "30fps",
                    "codec" to "H.264"
                )
            )
            
            // Write metadata as JSON
            val gson = com.google.gson.Gson()
            metadataFile.writeText(gson.toJson(metadata))
            
            Timber.d("Created timestamp metadata: ${metadataFile.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to create timestamp metadata")
        }
    }
    
    /**
     * Update status and notify callback
     */
    private fun updateStatus(status: String) {
        statusCallback?.invoke(status)
    }
    
    /**
     * Check if camera is recording
     */
    fun isRecording(): Boolean {
        return isRecording.get()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            if (isRecording.get()) {
                stopRecording()
            }
            
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            
            Timber.d("CameraManager cleaned up")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during camera cleanup")
        }
    }
}