package com.multimodal.capture.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.multimodal.capture.R
import timber.log.Timber
import java.util.*
import kotlin.math.*

/**
 * Enhanced RGB camera preview view with advanced interaction capabilities
 * Based on IRCamera CameraPreView implementation with multimodal capture enhancements
 */
class RGBCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs), ScaleGestureDetector.OnScaleGestureListener {

    companion object {
        private const val TAG = "RGBCameraPreviewView"
        
        // Camera facing constants
        const val CAMERA_FACING_BACK = 0
        const val CAMERA_FACING_FRONT = 1
        
        // Focus modes
        const val FOCUS_MODE_AUTO = 0
        const val FOCUS_MODE_MANUAL = 1
        const val FOCUS_MODE_CONTINUOUS_VIDEO = 2
        const val FOCUS_MODE_CONTINUOUS_PICTURE = 3
        
        // Exposure modes
        const val EXPOSURE_MODE_AUTO = 0
        const val EXPOSURE_MODE_MANUAL = 1
    }

    // TextureView for camera preview
    private lateinit var textureView: TextureView
    
    // Camera2 API components
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    
    // Camera properties
    private var cameraId: String = "0"
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var previewSize: Size? = null
    private var captureSize: Size? = null
    private var currentCameraFacing = CAMERA_FACING_BACK
    
    // Background thread handling
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Touch and gesture handling
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isScaling = false
    private var currentScale = 1f
    private var startX = 0f
    private var startY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var scaleWidth = 0f
    private var scaleHeight = 0f
    private var parentViewWidth = 0f
    private var parentViewHeight = 0f
    
    // Camera settings
    private var focusMode = FOCUS_MODE_AUTO
    private var exposureMode = EXPOSURE_MODE_AUTO
    private var exposureCompensation = 0
    private var zoomLevel = 1f
    private var isFlashEnabled = false
    
    // Callbacks
    var cameraStateCallback: ((String) -> Unit)? = null
    var frameCallback: ((Bitmap?) -> Unit)? = null
    var focusCallback: ((Boolean) -> Unit)? = null
    var exposureCallback: ((Int) -> Unit)? = null
    
    // Preview state
    var isPreviewing = false
        private set

    init {
        initializeView()
        Timber.d("[DEBUG_LOG] RGBCameraPreviewView initialized")
    }

    private fun initializeView() {
        // Create TextureView for camera preview
        textureView = TextureView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = surfaceTextureListener
        }
        addView(textureView)
        
        // Initialize gesture detector
        scaleGestureDetector = ScaleGestureDetector(context, this)
        
        // Initialize camera manager
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        
        // Start background thread
        startBackgroundThread()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Timber.d("[DEBUG_LOG] Surface texture available: ${width}x${height}")
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Timber.d("[DEBUG_LOG] Surface texture size changed: ${width}x${height}")
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Timber.d("[DEBUG_LOG] Surface texture destroyed")
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // Frame updated - can capture bitmap if needed
            frameCallback?.invoke(textureView.getBitmap())
        }
    }

    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Timber.d("[DEBUG_LOG] Camera opened successfully")
            cameraDevice = camera
            createCameraPreviewSession()
            this@RGBCameraPreviewView.cameraStateCallback?.invoke("Camera opened")
        }

        override fun onDisconnected(camera: CameraDevice) {
            Timber.d("[DEBUG_LOG] Camera disconnected")
            camera.close()
            cameraDevice = null
            this@RGBCameraPreviewView.cameraStateCallback?.invoke("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Timber.e("[DEBUG_LOG] Camera error: $error")
            camera.close()
            cameraDevice = null
            this@RGBCameraPreviewView.cameraStateCallback?.invoke("Camera error: $error")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val manager = cameraManager ?: return
            
            // Get camera characteristics
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId)
            val map = cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Choose preview and capture sizes
            previewSize = chooseOptimalSize(
                map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(),
                textureView.width,
                textureView.height
            )
            
            captureSize = chooseOptimalSize(
                map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(),
                1920,
                1080
            )
            
            // Configure transform
            configureTransform(textureView.width, textureView.height)
            
            // Open camera
            manager.openCamera(cameraId, cameraDeviceStateCallback, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Timber.e(e, "[DEBUG_LOG] Failed to open camera")
            cameraStateCallback?.invoke("Failed to open camera: ${e.message}")
        } catch (e: SecurityException) {
            Timber.e(e, "[DEBUG_LOG] Camera permission denied")
            cameraStateCallback?.invoke("Camera permission denied")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val device = cameraDevice ?: return
            val texture = textureView.surfaceTexture ?: return
            
            // Set preview size
            texture.setDefaultBufferSize(previewSize?.width ?: 1920, previewSize?.height ?: 1080)
            val surface = Surface(texture)
            
            // Create capture request builder
            captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                
                // Configure camera settings
                configureCameraSettings(this)
            }
            
            // Create capture session
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        
                        cameraCaptureSession = session
                        updatePreview()
                        isPreviewing = true
                        
                        Timber.d("[DEBUG_LOG] Camera preview session configured")
                        cameraStateCallback?.invoke("Preview started")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Timber.e("[DEBUG_LOG] Failed to configure camera preview session")
                        cameraStateCallback?.invoke("Preview configuration failed")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: CameraAccessException) {
            Timber.e(e, "[DEBUG_LOG] Failed to create camera preview session")
            cameraStateCallback?.invoke("Preview session failed: ${e.message}")
        }
    }

    private fun configureCameraSettings(requestBuilder: CaptureRequest.Builder) {
        // Configure auto-focus
        when (focusMode) {
            FOCUS_MODE_AUTO -> {
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            FOCUS_MODE_CONTINUOUS_VIDEO -> {
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            FOCUS_MODE_CONTINUOUS_PICTURE -> {
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
        
        // Configure exposure
        when (exposureMode) {
            EXPOSURE_MODE_AUTO -> {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            }
            EXPOSURE_MODE_MANUAL -> {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            }
        }
        
        // Configure flash
        if (isFlashEnabled) {
            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else {
            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        
        // Configure zoom
        val characteristics = cameraCharacteristics
        if (characteristics != null) {
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val zoomRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (zoomRect != null && zoomLevel > 1f) {
                val cropWidth = (zoomRect.width() / zoomLevel).toInt()
                val cropHeight = (zoomRect.height() / zoomLevel).toInt()
                val cropX = (zoomRect.width() - cropWidth) / 2
                val cropY = (zoomRect.height() - cropHeight) / 2
                val cropRect = android.graphics.Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
                requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return
        
        try {
            val requestBuilder = captureRequestBuilder ?: return
            val session = cameraCaptureSession ?: return
            
            configureCameraSettings(requestBuilder)
            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Timber.e(e, "[DEBUG_LOG] Failed to update camera preview")
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()
        val w = textureViewWidth
        val h = textureViewHeight
        
        for (option in choices) {
            if (option.width <= 1920 && option.height <= 1080) {
                if (option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }
        }
        
        return when {
            bigEnough.isNotEmpty() -> Collections.min(bigEnough) { lhs, rhs ->
                lhs.width * lhs.height - rhs.width * rhs.height
            }
            notBigEnough.isNotEmpty() -> Collections.max(notBigEnough) { lhs, rhs ->
                lhs.width * lhs.height - rhs.width * rhs.height
            }
            else -> choices[0]
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val previewSize = this.previewSize ?: return
        val rotation = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        
        textureView.setTransform(matrix)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isScaling && event.action != MotionEvent.ACTION_UP) {
            return scaleGestureDetector.onTouchEvent(event)
        }
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scaleWidth = textureView.width * (currentScale - 1) / 2f
                scaleHeight = textureView.height * (currentScale - 1) / 2f
                startX = event.x - textureView.x
                startY = event.y - textureView.y
                parentViewWidth = width.toFloat()
                parentViewHeight = height.toFloat()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveX = event.x - startX
                moveY = event.y - startY
                
                // Apply boundary constraints
                if (moveX - scaleWidth < 0f) moveX = 0f + scaleWidth
                if (moveY - scaleHeight < 0f) moveY = 0f + scaleHeight
                if (moveX + scaleWidth > parentViewWidth - textureView.width) {
                    moveX = parentViewWidth - textureView.width - scaleWidth
                }
                if (moveY + scaleHeight > parentViewHeight - textureView.height) {
                    moveY = parentViewHeight - textureView.height - scaleHeight
                }
                
                textureView.x = moveX
                textureView.y = moveY
                return true
            }
            MotionEvent.ACTION_UP -> {
                isScaling = false
                
                // Handle tap-to-focus
                if (abs(event.x - (startX + textureView.x)) < 10 && abs(event.y - (startY + textureView.y)) < 10) {
                    handleTapToFocus(event.x, event.y)
                }
                return true
            }
        }
        
        return scaleGestureDetector.onTouchEvent(event)
    }

    private fun handleTapToFocus(x: Float, y: Float) {
        val characteristics = cameraCharacteristics ?: return
        val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        // Convert touch coordinates to sensor coordinates
        val sensorX = (x / width * activeArraySize.width()).toInt()
        val sensorY = (y / height * activeArraySize.height()).toInt()
        
        val focusAreaSize = 200
        val left = maxOf(sensorX - focusAreaSize / 2, 0)
        val top = maxOf(sensorY - focusAreaSize / 2, 0)
        val right = minOf(left + focusAreaSize, activeArraySize.width())
        val bottom = minOf(top + focusAreaSize, activeArraySize.height())
        
        val focusArea = android.graphics.Rect(left, top, right, bottom)
        
        try {
            val requestBuilder = captureRequestBuilder ?: return
            val session = cameraCaptureSession ?: return
            
            // Set focus area
            requestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(
                android.hardware.camera2.params.MeteringRectangle(focusArea, 1000)
            ))
            requestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(
                android.hardware.camera2.params.MeteringRectangle(focusArea, 1000)
            ))
            
            // Trigger focus
            requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(requestBuilder.build(), null, backgroundHandler)
            
            // Reset trigger
            requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            
            focusCallback?.invoke(true)
            Timber.d("[DEBUG_LOG] Tap-to-focus triggered at ($x, $y)")
            
        } catch (e: CameraAccessException) {
            Timber.e(e, "[DEBUG_LOG] Failed to trigger tap-to-focus")
            focusCallback?.invoke(false)
        }
    }

    // ScaleGestureDetector.OnScaleGestureListener implementation
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        isScaling = true
        val scaleFactor = detector.scaleFactor - 1
        
        if (scaleFactor < 0) {
            if (currentScale > 0.1f) {
                currentScale += scaleFactor
                textureView.scaleX = currentScale
                textureView.scaleY = currentScale
            }
        } else {
            if (currentScale < 5f) {
                currentScale += scaleFactor
                textureView.scaleX = currentScale
                textureView.scaleY = currentScale
            }
        }
        
        Timber.d("[DEBUG_LOG] Scale changed to: $currentScale")
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        isScaling = true
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        // Scale gesture ended
    }

    /**
     * Switch between front and back cameras
     */
    fun switchCamera() {
        currentCameraFacing = if (currentCameraFacing == CAMERA_FACING_BACK) {
            CAMERA_FACING_FRONT
        } else {
            CAMERA_FACING_BACK
        }
        
        cameraId = if (currentCameraFacing == CAMERA_FACING_BACK) "0" else "1"
        
        closeCamera()
        openCamera()
        
        Timber.d("[DEBUG_LOG] Switched to camera: $cameraId")
        cameraStateCallback?.invoke("Switched to ${if (currentCameraFacing == CAMERA_FACING_BACK) "back" else "front"} camera")
    }

    /**
     * Set focus mode
     */
    fun setFocusMode(mode: Int) {
        focusMode = mode
        updatePreview()
        Timber.d("[DEBUG_LOG] Focus mode set to: $mode")
    }

    /**
     * Set exposure mode
     */
    fun setExposureMode(mode: Int) {
        exposureMode = mode
        updatePreview()
        Timber.d("[DEBUG_LOG] Exposure mode set to: $mode")
    }

    /**
     * Set exposure compensation
     */
    fun setExposureCompensation(compensation: Int) {
        exposureCompensation = compensation
        updatePreview()
        exposureCallback?.invoke(compensation)
        Timber.d("[DEBUG_LOG] Exposure compensation set to: $compensation")
    }

    /**
     * Set zoom level
     */
    fun setZoomLevel(zoom: Float) {
        zoomLevel = zoom.coerceIn(1f, 10f)
        updatePreview()
        Timber.d("[DEBUG_LOG] Zoom level set to: $zoomLevel")
    }

    /**
     * Toggle flash
     */
    fun toggleFlash() {
        isFlashEnabled = !isFlashEnabled
        updatePreview()
        Timber.d("[DEBUG_LOG] Flash ${if (isFlashEnabled) "enabled" else "disabled"}")
        cameraStateCallback?.invoke("Flash ${if (isFlashEnabled) "on" else "off"}")
    }

    /**
     * Capture current frame as bitmap
     */
    fun captureFrame(): Bitmap? {
        return if (textureView.isAvailable) {
            textureView.getBitmap()
        } else {
            null
        }
    }

    /**
     * Get current camera facing
     */
    fun getCurrentCameraFacing(): Int = currentCameraFacing

    /**
     * Get current zoom level
     */
    fun getCurrentZoomLevel(): Float = zoomLevel

    /**
     * Check if flash is enabled
     */
    fun isFlashEnabled(): Boolean = isFlashEnabled

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper ?: Looper.getMainLooper())
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Timber.e(e, "[DEBUG_LOG] Error stopping background thread")
        }
    }

    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
            
            isPreviewing = false
            
            Timber.d("[DEBUG_LOG] Camera closed")
            cameraStateCallback?.invoke("Camera closed")
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Error closing camera")
        }
    }

    /**
     * Resume camera operations
     */
    fun onResume() {
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        }
        Timber.d("[DEBUG_LOG] RGBCameraPreviewView resumed")
    }

    /**
     * Pause camera operations
     */
    fun onPause() {
        closeCamera()
        stopBackgroundThread()
        Timber.d("[DEBUG_LOG] RGBCameraPreviewView paused")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        closeCamera()
        stopBackgroundThread()
        Timber.d("[DEBUG_LOG] RGBCameraPreviewView cleanup completed")
    }
}