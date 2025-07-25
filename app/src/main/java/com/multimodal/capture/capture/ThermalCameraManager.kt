package com.multimodal.capture.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import com.multimodal.capture.capture.thermal.ThermalDataRecorder
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.multimodal.capture.utils.TimestampManager
import com.multimodal.capture.network.NetworkManager
import com.multimodal.capture.network.CommandProtocol
import com.multimodal.capture.thermal.USBMonitorManager
import com.multimodal.capture.thermal.ThermalDataParser
import com.multimodal.capture.thermal.OnUSBConnectListener
import com.multimodal.capture.thermal.Const
import com.multimodal.capture.ui.components.ThermalPreviewView
import com.energy.iruvc.ircmd.IRCMD
import com.energy.iruvc.usb.USBMonitor
import com.energy.iruvc.utils.CommonParams
import com.energy.iruvc.utils.IFrameCallback
import com.energy.iruvc.uvc.UVCCamera
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * ThermalCameraManager handles integration with Topdon TC001 thermal camera via USB-C.
 * Simplified implementation using USBMonitorManager from IRCamera project.
 */
class ThermalCameraManager(
    private val context: Context,
    private val networkManager: com.multimodal.capture.network.NetworkManager? = null
) : OnUSBConnectListener {

    private val timestampManager = TimestampManager()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dataRecorder = ThermalDataRecorder()

    // USB Monitor Manager from IRCamera
    private val usbMonitorManager = USBMonitorManager.getInstance()
    
    // IRCMD for thermal camera commands
    private var ircmd: IRCMD? = null

    // Recording state
    private val isConnected = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // Connection status for UI indicators
    enum class ConnectionStatus {
        DISCONNECTED,    // Red - device not connected
        INITIALIZING,    // Yellow - connecting/initializing
        CONNECTED        // Green - connected and communicating
    }
    
    private var connectionStatus = ConnectionStatus.DISCONNECTED

    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    private var connectionStatusCallback: ((ConnectionStatus, String) -> Unit)? = null
    private var thermalFrameCallback: ((ByteArray, Long) -> Unit)? = null
    private var previewImageView: ImageView? = null
    private var thermalPreviewView: ThermalPreviewView? = null
    private var temperatureCallback: ((max: Float, min: Float, center: Float) -> Unit)? = null

    // Capture job and output
    private var captureJob: Job? = null

    init {
        Timber.d("[DEBUG_LOG] ThermalCameraManager initialized")
        updateConnectionStatus(ConnectionStatus.DISCONNECTED, "Thermal camera disconnected")
    }

    /**
     * Initialize thermal camera system
     */
    fun initialize(): Boolean {
        return try {
            Timber.d("[DEBUG_LOG] Initializing thermal camera system...")
            
            // Add this manager as USB connect listener
            usbMonitorManager.addOnUSBConnectListener(this)
            
            // Initialize USB monitor with TC001 PID
            usbMonitorManager.init(
                context,
                Const.PID, // 0x5840 for TC001
                true, // Use IRISP
                CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT
            )
            
            // Register to start listening for USB events
            usbMonitorManager.registerUSB()
            
            updateStatus("Thermal camera system initialized")
            Timber.d("[DEBUG_LOG] Thermal camera system initialized successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to initialize thermal camera: ${e.message}")
            updateStatus("Failed to initialize thermal camera: ${e.message}")
            false
        }
    }

    /**
     * Connect to thermal camera (backward compatibility method)
     */
    fun connectToThermalCamera(): Boolean {
        return initialize()
    }

    /**
     * Start recording thermal data
     */
    fun startRecording(sessionId: String, outputDirectory: File): Boolean {
        if (dataRecorder.isRecording) {
            Timber.w("[DEBUG_LOG] Recording already in progress")
            return false
        }

        if (!isConnected.get()) {
            Timber.w("[DEBUG_LOG] Cannot start recording - thermal camera not connected")
            updateStatus("Thermal camera not connected")
            return false
        }

        return try {
            currentSessionId = sessionId
            
            // Create thermal data output file
            val thermalFile = File(outputDirectory, "thermal_data.bin")
            if (!dataRecorder.start(thermalFile)) return false
            updateStatus("Recording thermal data...")
            
            Timber.d("[DEBUG_LOG] Started thermal recording to: ${thermalFile.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to start thermal recording: ${e.message}")
            updateStatus("Failed to start thermal recording")
            false
        }
    }

    /**
     * Stop recording thermal data
     */
    fun stopRecording(): Boolean {
        if (!dataRecorder.isRecording) {
            Timber.w("[DEBUG_LOG] No recording in progress")
            return false
        }

        return try {
            dataRecorder.stop()
            updateStatus("Thermal recording stopped")
            Timber.d("[DEBUG_LOG] Thermal recording stopped successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to stop thermal recording: ${e.message}")
            false
        }
    }

    /**
     * Set status callback
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    /**
     * Set connection status callback with color indicators
     */
    fun setConnectionStatusCallback(callback: (ConnectionStatus, String) -> Unit) {
        connectionStatusCallback = callback
    }

    /**
     * Set thermal frame callback
     */
    fun setThermalFrameCallback(callback: (ByteArray, Long) -> Unit) {
        thermalFrameCallback = callback
    }

    /**
     * Set preview image view (legacy support)
     */
    fun setPreviewImageView(imageView: ImageView) {
        previewImageView = imageView
    }

    /**
     * Set enhanced thermal preview view
     */
    fun setThermalPreviewView(previewView: ThermalPreviewView) {
        thermalPreviewView = previewView
        
        // Set up temperature listener
        previewView.temperatureListener = { max, min, center ->
            temperatureCallback?.invoke(max, min, center)
            
            // Send real-time thermal data to PC if network streaming is enabled
            networkManager?.let { network ->
                if (network.isDataStreamingActive()) {
                    val dataPacket = CommandProtocol.createThermalDataPacket(
                        maxTemp = max,
                        minTemp = min,
                        centerTemp = center,
                        sessionId = currentSessionId
                    )
                    network.sendDataPacket(dataPacket)
                }
            }
        }
        
        Timber.d("[DEBUG_LOG] Enhanced thermal preview view set")
    }

    /**
     * Set temperature measurement callback
     */
    fun setTemperatureCallback(callback: (max: Float, min: Float, center: Float) -> Unit) {
        temperatureCallback = callback
    }

    /**
     * Set pseudocolor mode for thermal display
     */
    fun setPseudocolorMode(mode: Int) {
        thermalPreviewView?.setPseudocolorMode(mode)
        Timber.d("[DEBUG_LOG] Pseudocolor mode set to: $mode")
    }

    /**
     * Set temperature measurement region mode
     */
    fun setTemperatureRegionMode(mode: Int) {
        thermalPreviewView?.temperatureRegionMode = mode
        Timber.d("[DEBUG_LOG] Temperature region mode set to: $mode")
    }

    /**
     * Enable/disable temperature measurement touch interaction
     */
    fun setTemperatureMeasurementEnabled(enabled: Boolean) {
        thermalPreviewView?.canTouch = enabled
        Timber.d("[DEBUG_LOG] Temperature measurement touch enabled: $enabled")
    }

    /**
     * Clear temperature measurements
     */
    fun clearTemperatureMeasurements() {
        thermalPreviewView?.clearTemperatureMeasurements()
        Timber.d("[DEBUG_LOG] Temperature measurements cleared")
    }

    /**
     * Check if thermal camera is connected
     */
    fun isConnected(): Boolean = isConnected.get()

    /**
     * Check if recording is in progress
     */
    fun isRecording(): Boolean = dataRecorder.isRecording

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Timber.d("[DEBUG_LOG] Cleaning up thermal camera resources...")
            
            stopRecording()
            
            // Remove USB connect listener
            usbMonitorManager.removeOnUSBConnectListener(this)

            // Unregister from USB events to prevent leaks
            usbMonitorManager.unregisterUSB()
            
            isConnected.set(false)
            updateConnectionStatus(ConnectionStatus.DISCONNECTED, "Thermal camera disconnected")
            
            Timber.d("[DEBUG_LOG] Thermal camera cleanup completed")
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Error during thermal camera cleanup: ${e.message}")
        }
    }

    // OnUSBConnectListener implementation
    override fun onAttach(device: UsbDevice) {
        Timber.d("[DEBUG_LOG] USB device attached: ${device.deviceName}, VID: 0x${device.vendorId.toString(16)}, PID: 0x${device.productId.toString(16)}")
        
        if (device.productId == Const.PID) {
            Timber.d("[DEBUG_LOG] TC001 thermal camera detected")
            updateConnectionStatus(ConnectionStatus.INITIALIZING, "TC001 thermal camera detected - initializing...")
        }
    }

    override fun onGranted(usbDevice: UsbDevice, granted: Boolean) {
        Timber.d("[DEBUG_LOG] USB permission granted: $granted for device: ${usbDevice.deviceName}")
        
        if (granted && usbDevice.productId == Const.PID) {
            updateConnectionStatus(ConnectionStatus.INITIALIZING, "USB permission granted - connecting to TC001...")
        } else if (!granted) {
            updateConnectionStatus(ConnectionStatus.DISCONNECTED, "USB permission denied")
        }
    }

    override fun onDettach(device: UsbDevice) {
        Timber.d("[DEBUG_LOG] USB device detached: ${device.deviceName}")
        
        if (device.productId == Const.PID) {
            isConnected.set(false)
            updateConnectionStatus(ConnectionStatus.DISCONNECTED, "TC001 thermal camera disconnected")
            
            // Stop recording if in progress
            if (isRecording()) {
                stopRecording()
            }
        }
    }

    override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
        Timber.d("[DEBUG_LOG] USB device connected: ${device.deviceName}")
        
        if (device.productId == Const.PID) {
            isConnected.set(true)
            updateConnectionStatus(ConnectionStatus.INITIALIZING, "TC001 thermal camera connected - initializing interface...")
            Timber.d("[DEBUG_LOG] TC001 thermal camera successfully connected")
        }
    }

    override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        Timber.d("[DEBUG_LOG] USB device disconnected: ${device.deviceName}")
        
        if (device.productId == Const.PID) {
            isConnected.set(false)
            updateConnectionStatus(ConnectionStatus.DISCONNECTED, "TC001 thermal camera disconnected")
            
            // Stop recording if in progress
            if (isRecording()) {
                stopRecording()
            }
        }
    }

    override fun onCancel(device: UsbDevice) {
        Timber.d("[DEBUG_LOG] USB connection cancelled for device: ${device.deviceName}")
        updateConnectionStatus(ConnectionStatus.DISCONNECTED, "USB connection cancelled")
    }

    override fun onIRCMDInit(ircmd: IRCMD) {
        Timber.d("[DEBUG_LOG] IRCMD initialized")
        this.ircmd = ircmd
        updateConnectionStatus(ConnectionStatus.INITIALIZING, "Thermal camera command interface ready - finalizing...")
    }

    override fun onCompleteInit() {
        Timber.d("[DEBUG_LOG] Thermal camera initialization completed")
        updateConnectionStatus(ConnectionStatus.CONNECTED, "Thermal camera ready and communicating")
        
        // Start preview stream when initialization is complete
        startPreviewStream()
    }
    
    /**
     * Start thermal camera preview stream
     */
    private fun startPreviewStream() {
        try {
            Timber.d("[DEBUG_LOG] Starting thermal camera preview stream")
            
            val cmd = ircmd
            if (cmd == null) {
                handleIrcmdNotInitialized()
                return
            }
            
            val uvcCamera = usbMonitorManager.getUvcCamera()
            if (uvcCamera == null) {
                handleUvcCameraNotAvailable()
                return
            }
            
            initializeUvcPreview(uvcCamera)
            startThermalPreview(cmd)
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to start thermal preview stream")
            updateStatus("Preview failed: ${e.message}")
        }
    }
    
    /**
     * Handle case when IRCMD is not initialized
     */
    private fun handleIrcmdNotInitialized() {
        Timber.w("[DEBUG_LOG] Cannot start preview - IRCMD not initialized")
        updateStatus("Preview unavailable - camera not ready")
    }
    
    /**
     * Handle case when UVCCamera is not available
     */
    private fun handleUvcCameraNotAvailable() {
        Timber.w("[DEBUG_LOG] Cannot start preview - UVCCamera not available")
        updateStatus("Preview unavailable - UVC camera not ready")
    }
    
    /**
     * Initialize UVC preview with frame callback
     */
    private fun initializeUvcPreview(uvcCamera: UVCCamera) {
        setupFrameCallback(uvcCamera)
        uvcCamera.setOpenStatus(true)
        uvcCamera.onStartPreview()
    }
    
    /**
     * Start thermal preview using IRCMD API
     */
    private fun startThermalPreview(cmd: IRCMD) {
        val result = cmd.startPreview(
            CommonParams.PreviewPathChannel.PREVIEW_PATH0,
            CommonParams.StartPreviewSource.SOURCE_SENSOR,
            25, // Frame rate
            CommonParams.StartPreviewMode.VOC_DVP_MODE,
            CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT
        )
        
        if (result == 0) {
            handlePreviewStartSuccess(cmd)
        } else {
            handlePreviewStartFailure(result)
        }
    }
    
    /**
     * Handle successful preview start
     */
    private fun handlePreviewStartSuccess(cmd: IRCMD) {
        Timber.d("[DEBUG_LOG] Thermal preview stream started successfully")
        updateStatus("Thermal preview active")
        configureMirrorFlipProperty(cmd)
    }
    
    /**
     * Handle preview start failure
     */
    private fun handlePreviewStartFailure(result: Int) {
        Timber.e("[DEBUG_LOG] Failed to start thermal preview, result: $result")
        updateStatus("Preview failed to start")
    }
    
    /**
     * Configure mirror flip property for thermal camera
     */
    private fun configureMirrorFlipProperty(cmd: IRCMD) {
        try {
            cmd.setPropImageParams(
                CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
                CommonParams.PropImageParamsValue.MirrorFlipType.NO_MIRROR_FLIP
            )
        } catch (e: Exception) {
            Timber.w(e, "[DEBUG_LOG] Failed to set mirror flip property: ${e.message}")
        }
    }
    
    /**
     * Set up frame callback to receive and process thermal frames
     */
    private fun setupFrameCallback(uvcCamera: UVCCamera) {
        try {
            val frameCallback = IFrameCallback { frame ->
                try {
                    if (frame != null && frame.isNotEmpty()) {
                        Timber.d("[DEBUG_LOG] Received thermal frame: ${frame.size} bytes")
                        
                        // Process thermal frame data
                        processThermalFrameData(frame)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[DEBUG_LOG] Error in frame callback: ${e.message}")
                }
            }
            
            // Set the frame callback on UVCCamera
            uvcCamera.setFrameCallback(frameCallback)
            Timber.d("[DEBUG_LOG] Frame callback set up successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to set up frame callback: ${e.message}")
        }
    }
    
    /**
     * Process thermal frame data and update preview
     */
    private fun processThermalFrameData(frame: ByteArray) {
        try {
            if (!isValidThermalFrame(frame)) return
            
            // Write frame data to file if recording is active
            if (dataRecorder.isRecording) {
                dataRecorder.writeFrame(frame)
            }
            
            val imageData = extractImageDataFromFrame(frame)
            val temperatureData = extractTemperatureDataFromFrame(frame)

            imageData?.let { updateThermalPreview(it) }

            temperatureData?.let { data ->
                ThermalDataParser.parse(data)?.let { parsedData ->
                    temperatureCallback?.invoke(parsedData.maxTemp, parsedData.minTemp, parsedData.avgTemp)
                    
                    // Send real-time thermal data to PC if network streaming is enabled
                    networkManager?.let { network ->
                        if (network.isDataStreamingActive()) {
                            val dataPacket = CommandProtocol.createThermalDataPacket(
                                maxTemp = parsedData.maxTemp,
                                minTemp = parsedData.minTemp,
                                centerTemp = parsedData.avgTemp,
                                sessionId = currentSessionId
                            )
                            network.sendDataPacket(dataPacket)
                        }
                    }
                }
            }
            notifyThermalFrameCallback(frame)
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Error processing thermal frame data: ${e.message}")
        }
    }
    
    /**
     * Validate thermal frame data
     */
    private fun isValidThermalFrame(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        
        // Check for bad frame (last byte == 1 indicates bad frame)
        if (frame[frame.size - 1] == 1.toByte()) {
            Timber.w("[DEBUG_LOG] Bad frame detected, skipping")
            return false
        }
        
        return true
    }
    
    /**
     * Extract image data from thermal frame
     */
    private fun extractImageDataFromFrame(frame: ByteArray): ByteArray? {
        val dataLength = frame.size - 1 // Exclude status byte
        val imageDataLength = dataLength / 2
        
        if (dataLength < imageDataLength * 2) {
            Timber.w("[DEBUG_LOG] Insufficient frame data: ${frame.size} bytes")
            return null
        }
        
        // Extract image data (first half)
        val imageData = ByteArray(imageDataLength)
        System.arraycopy(frame, 0, imageData, 0, imageDataLength)
        return imageData
    }

    /**
     * Extracts the dedicated temperature data block from the second half of the raw frame.
     * The format of this block is specific to the camera's firmware.
     */
    private fun extractTemperatureDataFromFrame(frame: ByteArray): ByteArray? {
        val dataLength = frame.size - 1 // Exclude status byte
        val imageDataLength = dataLength / 2
        val temperatureDataLength = dataLength - imageDataLength

        if (dataLength < imageDataLength + temperatureDataLength) {
            Timber.w("[DEBUG_LOG] Insufficient frame data for temperature extraction: ${frame.size} bytes")
            return null
        }

        // Extract temperature data (second half of the payload)
        val temperatureData = ByteArray(temperatureDataLength)
        System.arraycopy(frame, imageDataLength, temperatureData, 0, temperatureDataLength)
        Timber.v("Extracted temperature data block: ${temperatureData.size} bytes")
        return temperatureData
    }
    
    /**
     * Update thermal preview with processed image data
     */
    private fun updateThermalPreview(imageData: ByteArray) {
        // Use enhanced thermal preview if available
        thermalPreviewView?.let { previewView ->
            mainHandler.post {
                previewView.updateThermalFrame(imageData, 256, 192)
            }
            return
        }
        
        // Fallback to legacy ImageView preview
        val bitmap = convertThermalDataToBitmap(imageData)
        mainHandler.post {
            previewImageView?.setImageBitmap(bitmap)
        }
    }
    
    /**
     * Notify thermal frame callback if set
     */
    private fun notifyThermalFrameCallback(frame: ByteArray) {
        thermalFrameCallback?.invoke(frame, System.currentTimeMillis())
    }
    
    /**
     * Convert thermal data to displayable bitmap
     */
    private fun convertThermalDataToBitmap(thermalData: ByteArray): Bitmap? {
        return try {
            val width = 256
            val height = 192
            
            if (!isValidThermalDataSize(thermalData, width, height)) {
                return null
            }
            
            val pixels = processThermalPixels(thermalData, width * height)
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Error converting thermal data to bitmap: ${e.message}")
            null
        }
    }
    
    /**
     * Validate thermal data size for bitmap conversion
     */
    private fun isValidThermalDataSize(thermalData: ByteArray, width: Int, height: Int): Boolean {
        val requiredSize = width * height * 2 // 16-bit thermal data
        if (thermalData.size < requiredSize) {
            Timber.w("[DEBUG_LOG] Thermal data size insufficient: ${thermalData.size}")
            return false
        }
        return true
    }
    
    /**
     * Process thermal data into pixel array
     */
    private fun processThermalPixels(thermalData: ByteArray, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        
        for (i in 0 until pixelCount) {
            val thermalValue = extractThermalValue(thermalData, i)
            val grayValue = convertThermalToGrayscale(thermalValue)
            pixels[i] = createArgbPixel(grayValue)
        }
        
        return pixels
    }
    
    /**
     * Extract 16-bit thermal value from data array
     */
    private fun extractThermalValue(thermalData: ByteArray, pixelIndex: Int): Int {
        val byteIndex = pixelIndex * 2
        return ((thermalData[byteIndex].toInt() and 0xFF) or 
                ((thermalData[byteIndex + 1].toInt() and 0xFF) shl 8))
    }
    
    /**
     * Convert thermal value to grayscale (0-255)
     */
    private fun convertThermalToGrayscale(thermalValue: Int): Int {
        return ((thermalValue.toFloat() / 65535.0f) * 255.0f).toInt().coerceIn(0, 255)
    }
    
    /**
     * Create ARGB pixel from grayscale value
     */
    private fun createArgbPixel(grayValue: Int): Int {
        return (0xFF shl 24) or (grayValue shl 16) or (grayValue shl 8) or grayValue
    }

    override fun onSetPreviewSizeFail() {
        Timber.w("[DEBUG_LOG] Failed to set preview size")
        updateStatus("Failed to set thermal camera preview size")
    }

    /**
     * Update connection status with color indicator
     */
    private fun updateConnectionStatus(newStatus: ConnectionStatus, message: String) {
        connectionStatus = newStatus
        mainHandler.post {
            connectionStatusCallback?.invoke(newStatus, message)
            statusCallback?.invoke(message) // Also update legacy status callback
        }
        Timber.d("[DEBUG_LOG] Connection Status: $newStatus - $message")
    }

    /**
     * Update status and notify callback (legacy method)
     */
    private fun updateStatus(status: String) {
        mainHandler.post {
            statusCallback?.invoke(status)
        }
        Timber.d("[DEBUG_LOG] Status: $status")
    }
}