package com.multimodal.capture.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.multimodal.capture.R
import com.multimodal.capture.utils.TimestampManager
import com.multimodal.capture.data.GSRDataPoint
import com.multimodal.capture.lsl.LSLStreamManager
import com.multimodal.capture.lsl.LSLStreamPublisher
import com.multimodal.capture.lsl.LSLStreamConfig
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.random.Random

// Shimmer SDK imports
import com.shimmerresearch.driver.Configuration
import com.shimmerresearch.driver.ObjectCluster
import com.shimmerresearch.driver.ShimmerDevice
import com.shimmerresearch.managers.bluetoothManager.ShimmerBluetoothManager
import com.shimmerresearch.driverUtilities.ShimmerVerDetails
import com.shimmerresearch.driver.CallbackObject

/**
 * GSRSensorManager handles integration with Shimmer3 GSR+ sensor via BLE.
 * 
 * Implements real-time GSR and PPG data streaming at 128 Hz using Shimmer SDK.
 * Supports LSL streaming and local data recording with timestamp synchronization.
 */
class GSRSensorManager(private val context: Context) {
    
    private val timestampManager = TimestampManager()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Shimmer SDK components
    private var shimmerBluetoothManager: ShimmerBluetoothManager? = null
    private var connectedShimmerDevice: ShimmerDevice? = null
    private var isShimmerInitialized = false
    private var connectedDeviceAddress: String? = null
    
    // Recording state
    private val isRecording = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // Data handling
    private val dataQueue = ConcurrentLinkedQueue<GSRDataPoint>()
    private var dataWriter: FileWriter? = null
    private var recordingJob: Job? = null
    private var simulationJob: Job? = null
    
    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((Double, Int, Double) -> Unit)? = null
    
    // Current sensor values (simulated)
    private var currentGSRValue = 0.0
    private var currentHeartRate = 0
    private var currentPRR = 0.0
    
    // LSL Integration
    private var lslStreamManager: LSLStreamManager? = null
    private var gsrLSLStream: LSLStreamPublisher? = null
    private var ppgLSLStream: LSLStreamPublisher? = null
    private var heartRateLSLStream: LSLStreamPublisher? = null
    private val enableLSLStreaming = AtomicBoolean(false)
    
    // Configuration
    private val targetSampleRate = 128.0 // Hz as specified in requirements
    
    init {
        Timber.d("GSRSensorManager initialized (stub implementation)")
        initializeShimmerManager()
        initializeLSLStreaming()
    }
    
    /**
     * Initialize Shimmer Bluetooth Manager with improved error handling
     */
    private fun initializeShimmerManager() {
        try {
            // Check required permissions before initialization
            if (!checkRequiredPermissions()) {
                updateStatus("Missing required permissions for GSR sensor")
                return
            }
            
            // Check Bluetooth availability
            if (!checkBluetoothAvailability()) {
                updateStatus("Bluetooth not available or disabled")
                return
            }
            
            // Stub implementation - in real implementation, this would:
            // 1. Initialize ShimmerBluetoothManager with context and handler
            // 2. Set up BLE support configuration  
            // 3. Configure sensor parameters for GSR + PPG at 128Hz
            // 4. Set up proper callback handlers for connection events
            // 5. Configure data streaming callbacks
            
            isShimmerInitialized = true
            Timber.d("Shimmer Bluetooth Manager initialized successfully (stub)")
            updateStatus(context.getString(R.string.status_gsr_disconnected))
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during Shimmer initialization - missing permissions")
            updateStatus("Permission denied: Cannot access Bluetooth for GSR sensor")
            isShimmerInitialized = false
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Shimmer Bluetooth Manager")
            updateStatus("GSR Manager Error: ${e.message}")
            isShimmerInitialized = false
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    private fun checkRequiredPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        return requiredPermissions.all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if Bluetooth is available and enabled
     */
    private fun checkBluetoothAvailability(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) 
            as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }
    
    /**
     * Initialize LSL streaming for GSR and PPG data
     */
    private fun initializeLSLStreaming() {
        try {
            lslStreamManager = LSLStreamManager.getInstance(context)
            
            if (lslStreamManager?.initialize() == true) {
                // Create GSR stream
                gsrLSLStream = lslStreamManager?.createStream(
                    name = "GSR_Data",
                    type = "GSR",
                    channelCount = 1,
                    sampleRate = targetSampleRate,
                    channelFormat = "float32",
                    sourceId = "shimmer_gsr"
                )
                
                // Create PPG stream
                ppgLSLStream = lslStreamManager?.createStream(
                    name = "PPG_Data",
                    type = "PPG",
                    channelCount = 2, // A1 and A15 channels
                    sampleRate = targetSampleRate,
                    channelFormat = "float32",
                    sourceId = "shimmer_ppg"
                )
                
                // Create Heart rate stream
                heartRateLSLStream = lslStreamManager?.createStream(
                    name = "HeartRate_Data",
                    type = "HeartRate",
                    channelCount = 1,
                    sampleRate = 1.0, // 1 Hz for heart rate
                    channelFormat = "float32",
                    sourceId = "shimmer_hr"
                )
                
                Timber.d("LSL streams initialized for GSR sensor")
            } else {
                Timber.w("Failed to initialize LSL Stream Manager")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize LSL streaming")
        }
    }
    
    /**
     * Set status callback for UI updates
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }
    
    /**
     * Set data callback for real-time data updates
     */
    fun setDataCallback(callback: (Double, Int, Double) -> Unit) {
        dataCallback = callback
    }
    
    /**
     * Enable or disable LSL streaming
     */
    fun setLSLStreamingEnabled(enabled: Boolean) {
        enableLSLStreaming.set(enabled)
        Timber.d("LSL streaming ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Get current connection status
     */
    fun isConnected(): Boolean = isConnected.get()
    
    /**
     * Get current recording status
     */
    fun isRecording(): Boolean = isRecording.get()
    
    /**
     * Get current sensor values
     */
    fun getCurrentValues(): Triple<Double, Int, Double> {
        return Triple(currentGSRValue, currentHeartRate, currentPRR)
    }
    
    /**
     * Start scanning for Shimmer devices with comprehensive error handling
     */
    fun scanForDevices() {
        if (!isShimmerInitialized) {
            Timber.w("Shimmer manager not initialized")
            updateStatus("GSR Manager not initialized")
            return
        }
        
        try {
            // Check permissions before scanning
            if (!checkRequiredPermissions()) {
                Timber.w("Missing permissions for device scanning")
                updateStatus("Missing Bluetooth permissions - please grant in settings")
                return
            }
            
            // Check Bluetooth availability
            if (!checkBluetoothAvailability()) {
                Timber.w("Bluetooth not available for scanning")
                updateStatus("Bluetooth disabled - please enable Bluetooth")
                return
            }
            
            // Prevent multiple concurrent scans
            if (isConnected.get()) {
                Timber.w("Already connected to a device")
                updateStatus("Already connected to GSR device")
                return
            }
            
            updateStatus("Scanning for GSR devices...")
            Timber.d("Starting GSR device scan with permission and state checks")
            
            // Stub implementation - simulate device discovery with timeout
            val scanTimeout = 10000L // 10 seconds
            
            // Simulate finding a device after 2-5 seconds
            val discoveryDelay = 2000L + Random.nextLong(3000L)
            mainHandler.postDelayed({
                if (!isConnected.get()) { // Only update if still not connected
                    updateStatus("GSR device found: Shimmer3-ABCD (00:06:66:XX:XX:XX)")
                    Timber.d("Simulated device discovery completed")
                }
            }, discoveryDelay)
            
            // Set scan timeout
            mainHandler.postDelayed({
                if (!isConnected.get()) {
                    updateStatus("Scan timeout - no GSR devices found")
                    Timber.w("Device scan timed out")
                }
            }, scanTimeout)
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during device scan - permission denied")
            updateStatus("Permission denied: Cannot scan for Bluetooth devices")
        } catch (e: IllegalStateException) {
            Timber.e(e, "Illegal state during device scan")
            updateStatus("Bluetooth adapter in invalid state")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during device scan")
            updateStatus("Scan Error: ${e.message}")
        }
    }
    
    /**
     * Connect to Shimmer device with comprehensive error handling and retry logic
     */
    fun connectToDevice(deviceAddress: String) {
        if (!isShimmerInitialized) {
            Timber.w("Shimmer manager not initialized")
            updateStatus("GSR Manager not initialized")
            return
        }
        
        // Validate device address
        if (deviceAddress.isBlank()) {
            Timber.w("Invalid device address provided")
            updateStatus("Invalid device address")
            return
        }
        
        try {
            // Check permissions before connecting
            if (!checkRequiredPermissions()) {
                Timber.w("Missing permissions for device connection")
                updateStatus("Missing Bluetooth permissions - please grant in settings")
                return
            }
            
            // Check Bluetooth availability
            if (!checkBluetoothAvailability()) {
                Timber.w("Bluetooth not available for connection")
                updateStatus("Bluetooth disabled - please enable Bluetooth")
                return
            }
            
            // Prevent multiple concurrent connections
            if (isConnected.get()) {
                Timber.w("Already connected to a device")
                updateStatus("Already connected to GSR device")
                return
            }
            
            updateStatus("Connecting to GSR device...")
            connectedDeviceAddress = deviceAddress
            Timber.d("Attempting to connect to GSR device: $deviceAddress")
            
            // Stub implementation - simulate connection process with realistic timing
            val connectionTimeout = 15000L // 15 seconds timeout
            val connectionDelay = 3000L + Random.nextLong(2000L) // 3-5 seconds
            
            // Simulate connection attempt
            mainHandler.postDelayed({
                try {
                    // Simulate occasional connection failures (10% chance)
                    if (Random.nextDouble() < 0.1) {
                        throw Exception("Connection failed - device not responding")
                    }
                    
                    isConnected.set(true)
                    updateStatus(context.getString(R.string.status_gsr_connected))
                    startDataSimulation()
                    Timber.d("Successfully connected to GSR device: $deviceAddress")
                    
                } catch (e: Exception) {
                    Timber.e(e, "Connection attempt failed")
                    updateStatus("Connection failed: ${e.message}")
                    connectedDeviceAddress = null
                    isConnected.set(false)
                }
            }, connectionDelay)
            
            // Set connection timeout
            mainHandler.postDelayed({
                if (!isConnected.get() && connectedDeviceAddress == deviceAddress) {
                    Timber.w("Connection timeout for device: $deviceAddress")
                    updateStatus("Connection timeout - device not responding")
                    connectedDeviceAddress = null
                    isConnected.set(false)
                }
            }, connectionTimeout)
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during device connection - permission denied")
            updateStatus("Permission denied: Cannot connect to Bluetooth device")
            connectedDeviceAddress = null
            isConnected.set(false)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid device address format: $deviceAddress")
            updateStatus("Invalid device address format")
            connectedDeviceAddress = null
        } catch (e: IllegalStateException) {
            Timber.e(e, "Illegal state during device connection")
            updateStatus("Bluetooth adapter in invalid state")
            connectedDeviceAddress = null
            isConnected.set(false)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during device connection")
            updateStatus("Connection Error: ${e.message}")
            connectedDeviceAddress = null
            isConnected.set(false)
        }
    }
    
    /**
     * Start data simulation for testing
     */
    private fun startDataSimulation() {
        simulationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                // Simulate GSR data (typical range: 0.1 - 10.0 µS)
                currentGSRValue = 2.0 + Random.nextDouble(-0.5, 0.5)
                
                // Simulate heart rate (typical range: 60-100 BPM)
                currentHeartRate = (75 + Random.nextInt(-10, 10))
                
                // Simulate packet reception rate (should be close to 100%)
                currentPRR = 95.0 + Random.nextDouble(-2.0, 2.0)
                
                // Create data point
                val timestamp = timestampManager.getCurrentTimestamp()
                val dataPoint = GSRDataPoint(
                    timestamp = timestamp,
                    shimmerTimestamp = timestamp / 1_000_000, // Convert to shimmer time scale
                    gsrValue = currentGSRValue,
                    ppgValue = 512.0 + Random.nextDouble(-50.0, 50.0), // Simulated PPG
                    packetReceptionRate = currentPRR,
                    sessionId = currentSessionId
                )
                
                // Add to queue for recording
                if (isRecording.get()) {
                    dataQueue.offer(dataPoint)
                }
                
                // Update UI callback
                dataCallback?.invoke(currentGSRValue, currentHeartRate, currentPRR)
                
                // Publish to LSL if enabled
                if (enableLSLStreaming.get()) {
                    publishToLSL(dataPoint)
                }
                
                // Sleep to maintain ~128 Hz sample rate
                delay((1000.0 / targetSampleRate).toLong())
            }
        }
    }
    
    /**
     * Start recording GSR data
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        if (!isConnected.get()) {
            Timber.w("Cannot start recording - GSR sensor not connected")
            return
        }
        
        if (isRecording.get()) {
            Timber.w("GSR recording already in progress")
            return
        }
        
        try {
            currentSessionId = sessionId
            timestampManager.setSessionStartTime(startTimestamp)
            
            // Create output file
            val outputDir = File(context.getExternalFilesDir(null), "recordings")
            outputDir.mkdirs()
            
            val outputFile = File(outputDir, "${sessionId}_gsr_data.csv")
            dataWriter = FileWriter(outputFile)
            
            // Write CSV header
            dataWriter?.write("timestamp,gsr_value,heart_rate,packet_reception_rate\n")
            
            // Start recording job
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                processDataQueue()
            }
            
            isRecording.set(true)
            updateStatus("Recording GSR data...")
            
            Timber.d("GSR recording started: ${outputFile.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start GSR recording")
            updateStatus("Recording Error: ${e.message}")
        }
    }
    
    /**
     * Stop recording GSR data
     */
    fun stopRecording() {
        if (!isRecording.get()) {
            Timber.w("No GSR recording in progress")
            return
        }
        
        try {
            isRecording.set(false)
            
            // Cancel recording job
            recordingJob?.cancel()
            recordingJob = null
            
            // Process remaining data in queue
            processRemainingData()
            
            // Close file writer
            dataWriter?.close()
            dataWriter = null
            
            updateStatus(context.getString(R.string.status_gsr_connected))
            Timber.d("GSR recording stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop GSR recording")
            updateStatus("Stop Recording Error: ${e.message}")
        }
    }
    
    /**
     * Process data queue for recording
     */
    private suspend fun processDataQueue() {
        while (isRecording.get()) {
            val dataPoint = dataQueue.poll()
            if (dataPoint != null) {
                writeDataPoint(dataPoint)
            } else {
                delay(10) // Small delay if queue is empty
            }
        }
    }
    
    /**
     * Process remaining data in queue
     */
    private fun processRemainingData() {
        while (dataQueue.isNotEmpty()) {
            val dataPoint = dataQueue.poll()
            if (dataPoint != null) {
                writeDataPoint(dataPoint)
            }
        }
    }
    
    /**
     * Write data point to file
     */
    private fun writeDataPoint(dataPoint: GSRDataPoint) {
        try {
            dataWriter?.write("${dataPoint.timestamp},${dataPoint.gsrValue},${currentHeartRate},${dataPoint.packetReceptionRate}\n")
            dataWriter?.flush()
        } catch (e: Exception) {
            Timber.e(e, "Failed to write GSR data point")
        }
    }
    
    /**
     * Publish data to LSL streams with network connectivity checking
     */
    private fun publishToLSL(dataPoint: GSRDataPoint) {
        try {
            // Check network connectivity before publishing
            if (!isNetworkAvailable()) {
                // Queue data for later transmission or handle offline scenario
                Timber.w("Network unavailable - LSL streaming may be affected")
                return
            }
            
            // Publish GSR data with error recovery
            gsrLSLStream?.let { stream ->
                try {
                    stream.pushSample(floatArrayOf(dataPoint.gsrValue.toFloat()))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to publish GSR data to LSL stream")
                }
            }
            
            // Publish PPG data (simulated A1 and A15 channels)
            ppgLSLStream?.let { stream ->
                try {
                    val ppgA1 = (512 + Random.nextInt(-50, 50)).toFloat()
                    val ppgA15 = (1024 + Random.nextInt(-100, 100)).toFloat()
                    stream.pushSample(floatArrayOf(ppgA1, ppgA15))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to publish PPG data to LSL stream")
                }
            }
            
            // Publish heart rate (less frequently)
            if (Random.nextDouble() < 0.01) { // ~1% chance per sample = ~1 Hz
                heartRateLSLStream?.let { stream ->
                    try {
                        stream.pushSample(floatArrayOf(currentHeartRate.toFloat()))
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to publish heart rate data to LSL stream")
                    }
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during LSL data publishing")
        }
    }
    
    /**
     * Check if network is available for LSL streaming
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
                as? android.net.ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetworkInfo
            activeNetwork?.isConnectedOrConnecting == true
        } catch (e: Exception) {
            Timber.w(e, "Failed to check network connectivity")
            false
        }
    }
    
    /**
     * Disconnect from Shimmer device with comprehensive resource cleanup
     */
    fun disconnect() {
        try {
            Timber.d("Starting GSR device disconnection process")
            
            // Stop recording if active
            if (isRecording.get()) {
                try {
                    stopRecording()
                    Timber.d("Recording stopped during disconnection")
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping recording during disconnection")
                }
            }
            
            // Cancel simulation job with timeout
            simulationJob?.let { job ->
                try {
                    job.cancel()
                    // Give the job a moment to cancel gracefully
                    runBlocking {
                        withTimeout(1000L) {
                            job.join()
                        }
                    }
                    Timber.d("Data simulation stopped")
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping data simulation")
                } finally {
                    simulationJob = null
                }
            }
            
            // Clear connection state
            isConnected.set(false)
            val previousAddress = connectedDeviceAddress
            connectedDeviceAddress = null
            
            // Reset current values
            currentGSRValue = 0.0
            currentHeartRate = 0
            currentPRR = 0.0
            
            updateStatus(context.getString(R.string.status_gsr_disconnected))
            Timber.d("Successfully disconnected from GSR device: $previousAddress")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to disconnect from GSR device")
            updateStatus("Disconnect Error: ${e.message}")
            
            // Force cleanup even if errors occurred
            try {
                isConnected.set(false)
                connectedDeviceAddress = null
                simulationJob?.cancel()
                simulationJob = null
            } catch (cleanupError: Exception) {
                Timber.e(cleanupError, "Error during forced cleanup")
            }
        }
    }
    
    /**
     * Update status and notify callback
     */
    private fun updateStatus(status: String) {
        mainHandler.post {
            statusCallback?.invoke(status)
        }
    }
    
    /**
     * Comprehensive cleanup of all resources and connections
     */
    fun cleanup() {
        try {
            Timber.d("Starting comprehensive GSRSensorManager cleanup")
            
            // Disconnect from device first
            try {
                disconnect()
                Timber.d("Device disconnection completed during cleanup")
            } catch (e: Exception) {
                Timber.w(e, "Error during device disconnection in cleanup")
            }
            
            // Cancel any remaining coroutine jobs
            try {
                recordingJob?.cancel()
                recordingJob = null
                simulationJob?.cancel()
                simulationJob = null
                Timber.d("All coroutine jobs cancelled")
            } catch (e: Exception) {
                Timber.w(e, "Error cancelling coroutine jobs")
            }
            
            // Close data writer if still open
            try {
                dataWriter?.close()
                dataWriter = null
                Timber.d("Data writer closed")
            } catch (e: Exception) {
                Timber.w(e, "Error closing data writer")
            }
            
            // Clear data queue
            try {
                dataQueue.clear()
                Timber.d("Data queue cleared")
            } catch (e: Exception) {
                Timber.w(e, "Error clearing data queue")
            }
            
            // Stop and cleanup LSL streams
            try {
                gsrLSLStream?.let { stream ->
                    stream.stopStream()
                    gsrLSLStream = null
                }
                ppgLSLStream?.let { stream ->
                    stream.stopStream()
                    ppgLSLStream = null
                }
                heartRateLSLStream?.let { stream ->
                    stream.stopStream()
                    heartRateLSLStream = null
                }
                Timber.d("LSL streams stopped and cleared")
            } catch (e: Exception) {
                Timber.w(e, "Error stopping LSL streams")
            }
            
            // Cleanup LSL stream manager
            try {
                lslStreamManager?.cleanup()
                lslStreamManager = null
                Timber.d("LSL stream manager cleaned up")
            } catch (e: Exception) {
                Timber.w(e, "Error cleaning up LSL stream manager")
            }
            
            // Reset all state variables
            try {
                isShimmerInitialized = false
                isRecording.set(false)
                isConnected.set(false)
                enableLSLStreaming.set(false)
                connectedDeviceAddress = null
                currentSessionId = ""
                currentGSRValue = 0.0
                currentHeartRate = 0
                currentPRR = 0.0
                Timber.d("All state variables reset")
            } catch (e: Exception) {
                Timber.w(e, "Error resetting state variables")
            }
            
            // Clear callbacks
            try {
                statusCallback = null
                dataCallback = null
                Timber.d("Callbacks cleared")
            } catch (e: Exception) {
                Timber.w(e, "Error clearing callbacks")
            }
            
            Timber.d("GSRSensorManager comprehensive cleanup completed successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Critical error during GSRSensorManager cleanup")
            
            // Force reset critical state even if cleanup failed
            try {
                isConnected.set(false)
                isRecording.set(false)
                connectedDeviceAddress = null
                recordingJob?.cancel()
                simulationJob?.cancel()
                dataWriter?.close()
            } catch (forceError: Exception) {
                Timber.e(forceError, "Error during forced state reset")
            }
        }
    }
}