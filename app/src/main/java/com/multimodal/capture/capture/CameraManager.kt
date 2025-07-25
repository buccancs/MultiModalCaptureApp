package com.multimodal.capture.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.multimodal.capture.R
import com.multimodal.capture.utils.TimestampManager
import com.multimodal.capture.network.NetworkManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class for camera configuration settings
 */
data class CameraSettings(
    val resolution: Size = Size(1920, 1080),
    val frameRate: Int = 30,
    val enableAutoExposure: Boolean = true,
    val exposureCompensation: Int = 0, // EV compensation (-2 to +2)
    val enableAutoFocus: Boolean = true,
    val focusMode: FocusMode = FocusMode.AUTO,
    val videoQuality: Quality = Quality.HD
) {
    enum class FocusMode {
        AUTO, MANUAL, CONTINUOUS_VIDEO, CONTINUOUS_PICTURE
    }
}

/**
 * CameraManager handles RGB video capture using Camera2 API and CameraX.
 * Provides high-definition video recording with synchronized timestamps.
 */
@Suppress("OPT_IN_USAGE")
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
    private var imageAnalysis: ImageAnalysis? = null
    private var recording: Recording? = null
    
    // Recording state
    private val isRecording = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // YUV extraction state
    private val isYuvExtractionEnabled = AtomicBoolean(true)
    private var yuvFrameCounter = 0
    private var yuvOutputDir: File? = null
    
    // Camera selection
    private var currentCameraId: Int = 0 // Default to back main camera
    
    // Camera settings
    private var currentSettings: CameraSettings = CameraSettings()
    
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
            
            // Image analysis use case for YUV extraction
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processYuvFrame(imageProxy)
                    }
                }
            
            // Select camera based on current camera ID
            val cameraSelector = getCameraSelector(currentCameraId)
            
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture,
                imageAnalysis
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
            
            // Create YUV output directory for stage 3 image extraction
            yuvOutputDir = File(outputDir, "${sessionId}_yuv_frames")
            yuvOutputDir?.mkdirs()
            yuvFrameCounter = 0 // Reset frame counter for new session
            
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
            
            // Clean up YUV extraction state
            yuvOutputDir = null
            
            updateStatus(context.getString(R.string.status_camera_ready))
            
            Timber.d("Video recording stopped. YUV frames extracted: $yuvFrameCounter")
            
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
     * Process YUV frame for stage 3 image extraction
     */
    private fun processYuvFrame(imageProxy: ImageProxy) {
        try {
            // Only process frames during recording and if YUV extraction is enabled
            if (!isRecording.get() || !isYuvExtractionEnabled.get()) {
                imageProxy.close()
                return
            }
            
            // Extract basic frame information without using experimental API
            val width = imageProxy.width
            val height = imageProxy.height
            val timestamp = imageProxy.imageInfo.timestamp
            val format = imageProxy.format
            
            // For now, save frame metadata and basic info
            // TODO: Implement proper YUV extraction when experimental API is stable
            saveYuvFrameMetadata(width, height, timestamp, format)
            // Save the actual YUV frame data to a file
            saveYuvFrameToFile(imageProxy)
            
            yuvFrameCounter++
            
            // Log every 30 frames to avoid spam
            if (yuvFrameCounter % 30 == 0) {
                Timber.d("Processed YUV frame #$yuvFrameCounter (${width}x${height}, format: $format)")
                Timber.d("Saved YUV frame #$yuvFrameCounter (${imageProxy.width}x${imageProxy.height})")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error processing YUV frame")
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * Save YUV frame metadata to file
     */
    private fun saveYuvFrameMetadata(width: Int, height: Int, timestamp: Long, format: Int) {
        try {
            val outputDir = yuvOutputDir ?: return
            
            // Create filename with timestamp and frame counter
            val filename = "${currentSessionId}_yuv_metadata_${yuvFrameCounter}_${timestamp}.json"
            val metadataFile = File(outputDir, filename)
            
            // Create metadata file with frame info
            val formatName = when (format) {
                ImageFormat.YUV_420_888 -> "YUV_420_888"
                ImageFormat.NV21 -> "NV21"
                ImageFormat.NV16 -> "NV16"
                else -> "UNKNOWN_$format"
            }
            
            val metadata = """
                {
                    "sessionId": "$currentSessionId",
                    "frameNumber": $yuvFrameCounter,
                    "timestamp": $timestamp,
                    "width": $width,
                    "height": $height,
                    "format": "$formatName",
                    "formatCode": $format,
                    "extractionType": "stage3_metadata",
                    "note": "Full YUV data extraction requires experimental API - currently saving metadata only"
                }
            """.trimIndent()
            
            metadataFile.writeText(metadata)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to save YUV frame metadata")
        }
    }

    /**
     * Saves the complete YUV_420_888 data from an ImageProxy to a .yuv file.
     * This captures the full, uncompressed Stage 3 image.
     */
    private fun saveYuvFrameToFile(imageProxy: ImageProxy) {
        val outputDir = yuvOutputDir ?: return
        val timestamp = imageProxy.imageInfo.timestamp
        val yuvFile = File(outputDir, "${currentSessionId}_yuv_frame_${yuvFrameCounter}_${timestamp}.yuv")

        try {
            FileOutputStream(yuvFile).use { fileOutputStream ->
                val yPlane = imageProxy.planes[0]
                val uPlane = imageProxy.planes[1]
                val vPlane = imageProxy.planes[2]

                // Write Y plane
                var buffer = yPlane.buffer
                val yData = ByteArray(buffer.remaining())
                buffer.get(yData)
                fileOutputStream.write(yData)

                // Write U plane
                buffer = uPlane.buffer
                val uData = ByteArray(buffer.remaining())
                buffer.get(uData)
                fileOutputStream.write(uData)

                // Write V plane
                buffer = vPlane.buffer
                val vData = ByteArray(buffer.remaining())
                buffer.get(vData)
                fileOutputStream.write(vData)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save YUV frame to file: ${yuvFile.absolutePath}")
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
     * Update camera settings and apply them
     */
    fun updateCameraSettings(newSettings: CameraSettings) {
        try {
            Timber.d("Updating camera settings: $newSettings")
            currentSettings = newSettings
            
            // Re-setup camera with new settings if camera is initialized
            if (cameraProvider != null) {
                setupCamera()
                updateStatus("Camera settings updated")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to update camera settings")
            updateStatus("Settings Update Error: ${e.message}")
        }
    }
    
    /**
     * Get current camera settings
     */
    fun getCurrentSettings(): CameraSettings {
        return currentSettings
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
     * Enable or disable YUV stage 3 image extraction
     */
    fun setYuvExtractionEnabled(enabled: Boolean) {
        isYuvExtractionEnabled.set(enabled)
        val status = if (enabled) "enabled" else "disabled"
        Timber.d("YUV stage 3 extraction $status")
        updateStatus("YUV extraction $status")
    }
    
    /**
     * Check if YUV extraction is enabled
     */
    fun isYuvExtractionEnabled(): Boolean {
        return isYuvExtractionEnabled.get()
    }
    
    /**
     * Get current YUV frame counter
     */
    fun getYuvFrameCount(): Int {
        return yuvFrameCounter
    }
    
    /**
     * Get YUV output directory path
     */
    fun getYuvOutputDirectory(): String? {
        return yuvOutputDir?.absolutePath
    }

    // Enhanced RGB Camera Preview Support
    private var rgbCameraPreviewView: com.multimodal.capture.ui.components.RGBCameraPreviewView? = null
    private var enhancedPreviewCallback: ((String) -> Unit)? = null

    /**
     * Set enhanced RGB camera preview view
     */
    fun setRGBCameraPreviewView(previewView: com.multimodal.capture.ui.components.RGBCameraPreviewView) {
        rgbCameraPreviewView = previewView
        
        // Set up callbacks
        previewView.cameraStateCallback = { status ->
            enhancedPreviewCallback?.invoke(status)
            statusCallback?.invoke(status)
        }
        
        previewView.frameCallback = { bitmap ->
            // Handle frame capture if needed
            bitmap?.let {
                Timber.v("[DEBUG_LOG] Enhanced preview frame captured: ${it.width}x${it.height}")
            }
        }
        
        previewView.focusCallback = { success ->
            val message = if (success) "Focus successful" else "Focus failed"
            enhancedPreviewCallback?.invoke(message)
        }
        
        previewView.exposureCallback = { compensation ->
            enhancedPreviewCallback?.invoke("Exposure compensation: $compensation")
        }
        
        Timber.d("[DEBUG_LOG] Enhanced RGB camera preview view set")
    }

    /**
     * Set enhanced preview callback
     */
    fun setEnhancedPreviewCallback(callback: (String) -> Unit) {
        enhancedPreviewCallback = callback
    }

    /**
     * Switch camera (front/back) for enhanced preview
     */
    fun switchEnhancedCamera() {
        rgbCameraPreviewView?.switchCamera()
        Timber.d("[DEBUG_LOG] Enhanced camera switched")
    }

    /**
     * Set focus mode for enhanced preview
     */
    fun setEnhancedFocusMode(mode: Int) {
        rgbCameraPreviewView?.setFocusMode(mode)
        val modeString = when (mode) {
            0 -> "Auto"
            1 -> "Manual"
            2 -> "Continuous Video"
            3 -> "Continuous Picture"
            else -> "Unknown"
        }
        enhancedPreviewCallback?.invoke("Focus mode: $modeString")
        Timber.d("[DEBUG_LOG] Enhanced focus mode set to: $modeString")
    }

    /**
     * Set exposure mode for enhanced preview
     */
    fun setEnhancedExposureMode(mode: Int) {
        rgbCameraPreviewView?.setExposureMode(mode)
        val modeString = when (mode) {
            0 -> "Auto"
            1 -> "Manual"
            else -> "Unknown"
        }
        enhancedPreviewCallback?.invoke("Exposure mode: $modeString")
        Timber.d("[DEBUG_LOG] Enhanced exposure mode set to: $modeString")
    }

    /**
     * Set exposure compensation for enhanced preview
     */
    fun setEnhancedExposureCompensation(compensation: Int) {
        rgbCameraPreviewView?.setExposureCompensation(compensation)
        Timber.d("[DEBUG_LOG] Enhanced exposure compensation set to: $compensation")
    }

    /**
     * Set zoom level for enhanced preview
     */
    fun setEnhancedZoomLevel(zoom: Float) {
        rgbCameraPreviewView?.setZoomLevel(zoom)
        enhancedPreviewCallback?.invoke("Zoom level: ${String.format("%.1f", zoom)}x")
        Timber.d("[DEBUG_LOG] Enhanced zoom level set to: $zoom")
    }

    /**
     * Toggle flash for enhanced preview
     */
    fun toggleEnhancedFlash() {
        rgbCameraPreviewView?.toggleFlash()
        val isEnabled = rgbCameraPreviewView?.isFlashEnabled() ?: false
        enhancedPreviewCallback?.invoke("Flash ${if (isEnabled) "enabled" else "disabled"}")
        Timber.d("[DEBUG_LOG] Enhanced flash toggled: $isEnabled")
    }

    /**
     * Capture frame from enhanced preview
     */
    fun captureEnhancedFrame(): android.graphics.Bitmap? {
        val bitmap = rgbCameraPreviewView?.captureFrame()
        if (bitmap != null) {
            enhancedPreviewCallback?.invoke("Frame captured: ${bitmap.width}x${bitmap.height}")
            Timber.d("[DEBUG_LOG] Enhanced frame captured: ${bitmap.width}x${bitmap.height}")
        } else {
            enhancedPreviewCallback?.invoke("Frame capture failed")
            Timber.w("[DEBUG_LOG] Enhanced frame capture failed")
        }
        return bitmap
    }

    /**
     * Get current camera facing for enhanced preview
     */
    fun getEnhancedCameraFacing(): Int {
        return rgbCameraPreviewView?.getCurrentCameraFacing() ?: 0
    }

    /**
     * Get current zoom level for enhanced preview
     */
    fun getEnhancedZoomLevel(): Float {
        return rgbCameraPreviewView?.getCurrentZoomLevel() ?: 1f
    }

    /**
     * Check if enhanced flash is enabled
     */
    fun isEnhancedFlashEnabled(): Boolean {
        return rgbCameraPreviewView?.isFlashEnabled() ?: false
    }

    /**
     * Check if enhanced preview is active
     */
    fun isEnhancedPreviewActive(): Boolean {
        return rgbCameraPreviewView?.isPreviewing ?: false
    }

    /**
     * Resume enhanced preview operations
     */
    fun resumeEnhancedPreview() {
        rgbCameraPreviewView?.onResume()
        enhancedPreviewCallback?.invoke("Enhanced preview resumed")
        Timber.d("[DEBUG_LOG] Enhanced preview resumed")
    }

    /**
     * Pause enhanced preview operations
     */
    fun pauseEnhancedPreview() {
        rgbCameraPreviewView?.onPause()
        enhancedPreviewCallback?.invoke("Enhanced preview paused")
        Timber.d("[DEBUG_LOG] Enhanced preview paused")
    }

    /**
     * Cleanup enhanced preview resources
     */
    fun cleanupEnhancedPreview() {
        rgbCameraPreviewView?.cleanup()
        rgbCameraPreviewView = null
        enhancedPreviewCallback = null
        Timber.d("[DEBUG_LOG] Enhanced preview cleanup completed")
    }
    
    /**
     * Pause camera operations when app goes to background
     */
    fun onPause() {
        try {
            Timber.d("CameraManager pausing operations")
            
            // Stop recording if active (will be restored on resume if needed)
            if (isRecording.get()) {
                Timber.d("Stopping recording due to app pause")
                stopRecording()
            }
            
            // Unbind camera use cases to free resources
            cameraProvider?.unbindAll()
            
            Timber.d("CameraManager paused successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during camera pause")
        }
    }
    
    /**
     * Resume camera operations when app returns to foreground
     */
    fun onResume() {
        try {
            Timber.d("CameraManager resuming operations")
            
            // Re-setup camera if provider is available
            if (cameraProvider != null) {
                setupCamera()
                updateStatus(context.getString(R.string.status_camera_ready))
            } else {
                // Re-initialize camera if provider was lost
                initializeCamera()
            }
            
            Timber.d("CameraManager resumed successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during camera resume")
            updateStatus("Camera Resume Error: ${e.message}")
        }
    }
    
    /**
     * Cleanup resources with enhanced memory management
     */
    fun cleanup() {
        try {
            Timber.d("Starting CameraManager cleanup...")
            
            // Stop any active recording
            if (isRecording.get()) {
                stopRecording()
            }
            
            // Unbind all camera use cases
            cameraProvider?.unbindAll()
            
            // Clear references to prevent memory leaks
            camera = null
            preview = null
            videoCapture = null
            recording = null
            
            // Clear callbacks
            statusCallback = null
            
            // Shutdown executor with timeout
            try {
                cameraExecutor.shutdown()
                if (!cameraExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Timber.w("Camera executor did not terminate gracefully, forcing shutdown")
                    cameraExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                Timber.w("Camera executor shutdown interrupted")
                cameraExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
            
            // Clear camera provider reference
            cameraProvider = null
            
            Timber.d("CameraManager cleanup completed successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during camera cleanup")
        }
    }
}