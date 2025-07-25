package com.multimodal.capture.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.multimodal.capture.data.GSRDataPoint
import com.multimodal.capture.utils.SettingsManager
import com.multimodal.capture.network.NetworkManager
import com.multimodal.capture.network.CommandProtocol
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.IOException

// Shimmer SDK imports
import com.shimmerresearch.driver.Configuration
import com.shimmerresearch.driver.FormatCluster
import com.shimmerresearch.driver.ObjectCluster
import com.shimmerresearch.android.Shimmer
import com.shimmerresearch.driver.ShimmerDevice
import com.shimmerresearch.managers.bluetoothManager.ShimmerBluetoothManager

/**
 * GSRSensorManager handles Shimmer3 GSR+ sensor integration for real-time GSR data collection.
 * 
 * This class provides full Shimmer SDK integration for connecting to Shimmer3 GSR+ devices,
 * configuring sensors, streaming real-time data, and recording GSR measurements to CSV files.
 * Uses ShimmerBluetooth for device communication and ShimmerBluetoothManager for device discovery.
 */
class GSRSensorManager(
    private val context: Context,
    private val networkManager: NetworkManager? = null
) {
    
    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((Double, Int, Double) -> Unit)? = null
    
    // Recording state
    private var isRecording = false
    private var currentSessionId: String? = null
    private var sessionStartTimestamp: Long = 0L
    private var csvWriter: FileWriter? = null
    private var recordingFile: File? = null
    
    // Shimmer SDK components
    private var bluetoothManager: ShimmerBluetoothManager? = null
    private var connectedDevices = mutableMapOf<String, Shimmer?>()
    private var currentShimmerDevice: Shimmer? = null
    private var isConnected = false
    private var isStreaming = false
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Data processing
    private var lastGSRValue = 0.0
    private var lastHeartRate = 0
    private var lastPacketReceptionRate = 0.0
    
    // Settings
    private val settingsManager = SettingsManager.getInstance(context)
    
    // Shimmer message handler for SDK communication
    private val shimmerHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            handleShimmerMessage(msg)
        }
    }
    
    init {
        Timber.d("GSRSensorManager initialized - initializing Shimmer SDK")
        initializeShimmerManager()
    }
    
    /**
     * Handle Shimmer SDK messages
     */
    private fun handleShimmerMessage(msg: Message) {
        try {
            when (msg.what) {
                // TODO: Add actual Shimmer message constants when Shimmer class is available
                // See backlog.md - "Advanced Sensor Configuration" for enhanced message handling
                else -> {
                    Timber.d("Received Shimmer message: ${msg.what}")
                    if (msg.obj is ObjectCluster) {
                        handleObjectCluster(msg.obj as ObjectCluster)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling Shimmer message")
        }
    }
    
    /**
     * Handle ObjectCluster data from Shimmer device
     */
    private fun handleObjectCluster(objectCluster: ObjectCluster) {
        try {
            Timber.d("Processing ObjectCluster data")
            
            // Extract GSR value
            val gsrValue = extractGSRValue(objectCluster)
            
            // Extract timestamp
            val timestamp = extractTimestamp(objectCluster)
            
            // Extract packet reception rate
            val prr = extractPacketReceptionRate(objectCluster)
            
            // Update last known values
            if (gsrValue > 0) lastGSRValue = gsrValue
            if (prr >= 0) lastPacketReceptionRate = prr
            
            // Send data to callback
            dataCallback?.invoke(lastGSRValue, lastHeartRate, lastPacketReceptionRate)
            
            // Send real-time data to PC if network streaming is enabled
            networkManager?.let { network ->
                if (network.isDataStreamingActive()) {
                    val dataPacket = CommandProtocol.createGSRDataPacket(
                        gsrValue = lastGSRValue,
                        heartRate = lastHeartRate,
                        packetReceptionRate = lastPacketReceptionRate,
                        sessionId = currentSessionId
                    )
                    network.sendDataPacket(dataPacket)
                }
            }
            
            // Record data if recording is active
            if (isRecording && currentSessionId != null) {
                recordDataPoint(timestamp, lastGSRValue, lastHeartRate, lastPacketReceptionRate)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to process ObjectCluster data")
        }
    }
    
    /**
     * Initialize Shimmer SDK components for GSR data collection
     */
    private fun initializeShimmerManager() {
        try {
            Timber.d("Initializing Shimmer SDK for GSR data collection...")
            
            // Initialize SDK configuration constants
            initializeSDKConfiguration()
            
            Timber.d("Shimmer SDK initialized successfully")
            statusCallback?.invoke("GSR Manager Ready - Shimmer SDK Active")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Shimmer SDK")
            statusCallback?.invoke("GSR Manager Error: ${e.message}")
        }
    }
    
    /**
     * Initialize SDK configuration constants and settings
     */
    private fun initializeSDKConfiguration() {
        try {
            // Verify SDK constants are accessible
            val gsrSensorId = Configuration.Shimmer3.SENSOR_ID.SHIMMER_GSR
            val timestampName = Configuration.Shimmer3.ObjectClusterSensorName.TIMESTAMP
            val prrName = Configuration.Shimmer3.ObjectClusterSensorName.PACKET_RECEPTION_RATE_OVERALL
            
            Timber.d("SDK Configuration constants verified: GSR_SENSOR_ID=$gsrSensorId")
            Timber.d("Timestamp sensor name: $timestampName")
            Timber.d("Packet reception rate name: $prrName")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize SDK configuration constants")
            throw e
        }
    }
    
    /**
     * Configure Shimmer device using the recommended clone -> configure -> write pattern
     */
    private fun configureAndWriteToDevice(shimmerDevice: Shimmer) {
        try {
            // The recommended configuration pattern: clone, configure, then write.
            val shimmerClone = shimmerDevice.deepClone() as Shimmer

            val shimmerConfig = settingsManager.loadShimmerConfig()
            
            Timber.d("Configuring Shimmer device ${shimmerDevice.bluetoothAddress} with:")
            Timber.d("- Sample Rate: ${shimmerConfig.sampleRate}Hz")
            Timber.d("- Enable GSR: true")

            // Apply settings to the clone
            shimmerClone.setShimmerAndSensorsSamplingRate(shimmerConfig.sampleRate.toDouble())
            shimmerClone.setSensorEnabledState(Configuration.Shimmer3.SENSOR_ID.SHIMMER_GSR, true)
            // TODO: Add other sensor configurations here (e.g., PPG, Accelerometer)

            // Write the configuration from the clone to the actual device
            shimmerDevice.writeConfigBytes()

            Timber.d("Configuration successfully written to device.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to configure Shimmer device")
            throw e
        }
    }
    
    /**
     * Extract GSR value from ObjectCluster
     */
    private fun extractGSRValue(objectCluster: ObjectCluster): Double {
        return try {
            val gsrFormats = objectCluster.getCollectionOfFormatClusters("GSR")
            val gsrCluster = ObjectCluster.returnFormatCluster(gsrFormats, "CAL") as? FormatCluster
            gsrCluster?.mData ?: 0.0
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract GSR value")
            0.0
        }
    }
    
    /**
     * Extract timestamp from ObjectCluster
     */
    private fun extractTimestamp(objectCluster: ObjectCluster): Long {
        return try {
            val timestampFormats = objectCluster.getCollectionOfFormatClusters(
                Configuration.Shimmer3.ObjectClusterSensorName.TIMESTAMP
            )
            val timestampCluster = ObjectCluster.returnFormatCluster(timestampFormats, "CAL") as? FormatCluster
            timestampCluster?.mData?.toLong() ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract timestamp, using system time")
            System.currentTimeMillis()
        }
    }
    
    /**
     * Extract packet reception rate from ObjectCluster
     */
    private fun extractPacketReceptionRate(objectCluster: ObjectCluster): Double {
        return try {
            val prrFormats = objectCluster.getCollectionOfFormatClusters(
                Configuration.Shimmer3.ObjectClusterSensorName.PACKET_RECEPTION_RATE_OVERALL
            )
            val prrCluster = ObjectCluster.returnFormatCluster(prrFormats, "CAL") as? FormatCluster
            prrCluster?.mData ?: 0.0
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract packet reception rate")
            0.0
        }
    }
    
    /**
     * Set status callback for connection and device status updates
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        this.statusCallback = callback
        Timber.d("Status callback set")
    }
    
    /**
     * Set data callback for real-time GSR, heart rate, and packet reception rate data
     */
    fun setDataCallback(callback: (Double, Int, Double) -> Unit) {
        this.dataCallback = callback
        Timber.d("Data callback set")
    }
    
    /**
     * Check if GSR sensor is connected
     */
    fun isConnected(): Boolean {
        return isConnected
    }
    
    /**
     * Check if GSR sensor is currently recording
     */
    fun isRecording(): Boolean {
        return isRecording
    }
    
    /**
     * Get current session ID if recording
     */
    fun getCurrentSessionId(): String? {
        return currentSessionId
    }
    
    /**
     * Get connected device address
     */
    fun getConnectedDeviceAddress(): String? {
        return currentShimmerDevice?.bluetoothAddress
    }
    
    /**
     * Get connected device name
     */
    fun getConnectedDeviceName(): String? {
        return try {
            currentShimmerDevice?.deviceName
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get last known GSR value
     */
    fun getLastGSRValue(): Double {
        return lastGSRValue
    }
    
    /**
     * Get last known heart rate
     */
    fun getLastHeartRate(): Int {
        return lastHeartRate
    }
    
    /**
     * Get last known packet reception rate
     */
    fun getLastPacketReceptionRate(): Double {
        return lastPacketReceptionRate
    }
    
    /**
     * Scan for available Shimmer devices using Bluetooth discovery
     */
    fun scanForDevices() {
        try {
            Timber.d("Starting Bluetooth scan for Shimmer devices")
            statusCallback?.invoke("Scanning for Shimmer devices...")
            
            coroutineScope.launch {
                try {
                    // Use Android Bluetooth adapter for device discovery
                    val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    
                    if (bluetoothAdapter == null) {
                        statusCallback?.invoke("Bluetooth not available")
                        return@launch
                    }
                    
                    if (!bluetoothAdapter.isEnabled) {
                        statusCallback?.invoke("Please enable Bluetooth")
                        return@launch
                    }
                    
                    statusCallback?.invoke("Scanning for paired Shimmer devices...")
                    
                    try {
                        // Get paired devices and filter for Shimmer devices
                        val pairedDevices = bluetoothAdapter.bondedDevices
                        val shimmerDevices = pairedDevices.filter { device ->
                            try {
                                device.name?.contains("Shimmer", ignoreCase = true) == true ||
                                device.address.startsWith("00:06:66") // Shimmer MAC prefix
                            } catch (e: SecurityException) {
                                Timber.w("Permission denied accessing device info")
                                false
                            }
                        }
                        
                        if (shimmerDevices.isNotEmpty()) {
                            statusCallback?.invoke("Found ${shimmerDevices.size} paired Shimmer device(s)")
                            shimmerDevices.forEach { device ->
                                try {
                                    Timber.d("Found Shimmer device: ${device.name} (${device.address})")
                                } catch (e: SecurityException) {
                                    Timber.d("Found Shimmer device: [Permission denied] (${device.address})")
                                }
                            }
                        } else {
                            statusCallback?.invoke("No paired Shimmer devices found")
                        }
                    } catch (e: SecurityException) {
                        Timber.w(e, "Bluetooth permission denied")
                        statusCallback?.invoke("Bluetooth permission required for device scanning")
                    }
                    
                    statusCallback?.invoke("Scan completed")
                    
                } catch (e: Exception) {
                    Timber.e(e, "Failed to scan for Shimmer devices")
                    statusCallback?.invoke("Scan failed: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Shimmer device scan")
            statusCallback?.invoke("Scan failed: ${e.message}")
        }
    }
    
    
    /**
     * Connect to a specific Shimmer device by MAC address using Shimmer SDK
     */
    fun connectToDevice(deviceAddress: String) {
        try {
            Timber.d("Connecting to Shimmer device: $deviceAddress using SDK")
            statusCallback?.invoke("Connecting to $deviceAddress...")
            
            coroutineScope.launch {
                try {
                    // Attempt SDK-based connection
                    Timber.d("Attempting SDK-based connection to $deviceAddress")
                    statusCallback?.invoke("Initializing SDK connection...")
                    
                    // Create Shimmer instance for this device
                    val shimmerDevice = Shimmer(shimmerHandler, context)
                    
                    // Connect to device using Shimmer SDK
                    shimmerDevice.connect(deviceAddress, "default")

                    // Configure the device with settings *after* connecting
                    configureAndWriteToDevice(shimmerDevice)
                    
                    // Store device reference
                    connectedDevices[deviceAddress] = shimmerDevice
                    currentShimmerDevice = shimmerDevice
                    isConnected = true
                    
                    Timber.d("Shimmer device connected successfully: $deviceAddress")
                    statusCallback?.invoke("GSR Connected - Ready for streaming")
                    
                    // Start data streaming
                    startRealDataCollection(shimmerDevice)
                    
                } catch (e: Exception) {
                    Timber.e(e, "SDK connection failed")
                    statusCallback?.invoke("Connection failed: ${e.message}")
                    isConnected = false
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to Shimmer device: $deviceAddress")
            statusCallback?.invoke("Connection failed: ${e.message}")
        }
    }
    
    /**
     * Disconnect from current Shimmer device
     */
    fun disconnect() {
        try {
            Timber.d("Disconnecting from Shimmer device")
            
            // Stop streaming if active
            if (isStreaming && currentShimmerDevice != null) {
                currentShimmerDevice?.stopStreaming()
                isStreaming = false
                Timber.d("Stopped streaming from Shimmer device")
            }
            
            // Disconnect from device
            currentShimmerDevice?.disconnect()
            
            // Clear device references
            currentShimmerDevice = null
            connectedDevices.clear()
            isConnected = false
            
            statusCallback?.invoke("GSR Disconnected")
            Timber.d("Shimmer device disconnected successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to disconnect from Shimmer device")
            statusCallback?.invoke("Disconnect error: ${e.message}")
        }
    }
    
    /**
     * Start recording GSR data to file
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        try {
            Timber.d("Starting GSR recording for session: $sessionId")
            
            if (!isConnected) {
                Timber.w("No Shimmer device connected")
                statusCallback?.invoke("GSR: No device connected")
                return
            }
            
            currentSessionId = sessionId
            sessionStartTimestamp = startTimestamp
            isRecording = true
            
            // Create recording file
            setupRecordingFile(sessionId)
            
            // Set up data callback to write to CSV file during recording
            setDataCallback { gsrValue, heartRate, prr ->
                if (isRecording) {
                    val timestamp = System.currentTimeMillis() * 1_000_000 // Convert to nanoseconds
                    recordDataPoint(timestamp, gsrValue, heartRate, prr)
                }
            }
            
            // Start real data collection from Shimmer device
            currentShimmerDevice?.let { shimmerDevice ->
                startRealDataCollection(shimmerDevice)
                statusCallback?.invoke("GSR Recording Started - Real Data Collection")
            } ?: run {
                Timber.w("No current Shimmer device available for data collection")
                statusCallback?.invoke("GSR Recording Error: No device available")
                isRecording = false
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start GSR recording")
            statusCallback?.invoke("GSR Recording Error: ${e.message}")
            isRecording = false
        }
    }
    
    /**
     * Stop recording GSR data
     */
    fun stopRecording() {
        try {
            Timber.d("Stopping GSR recording")
            
            isRecording = false
            isStreaming = false
            
            // Close recording file
            closeRecordingFile()
            
            statusCallback?.invoke("GSR Recording Stopped")
            currentSessionId = null
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop GSR recording")
            statusCallback?.invoke("GSR Stop Error: ${e.message}")
        }
    }
    
    /**
     * Start real data collection from Shimmer device
     */
    private fun startRealDataCollection(shimmerDevice: Shimmer) {
        try {
            Timber.d("Starting real GSR data collection from Shimmer device")
            
            // Configuration is now handled by configureAndWriteToDevice()
            // We just need to start the stream.
            
            // Start streaming data
            shimmerDevice.startStreaming()
            
            isStreaming = true
            statusCallback?.invoke("GSR streaming started")
            Timber.d("Shimmer device streaming started successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start real data collection")
            statusCallback?.invoke("Streaming failed: ${e.message}")
            isStreaming = false
        }
    }
    
    
    
    /**
     * Setup recording file for session
     */
    private fun setupRecordingFile(sessionId: String) {
        try {
            val sessionDir = File(context.getExternalFilesDir(null), "sessions/$sessionId")
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
            }
            
            recordingFile = File(sessionDir, "gsr_data.csv")
            csvWriter = FileWriter(recordingFile!!)
            
            // Write CSV header
            csvWriter?.write(GSRDataPoint.getCsvHeader() + "\n")
            csvWriter?.flush()
            
            Timber.d("GSR recording file created: ${recordingFile?.absolutePath}")
        } catch (e: IOException) {
            Timber.e(e, "Failed to setup recording file")
            throw e
        }
    }
    
    /**
     * Record data point to CSV file
     */
    private fun recordDataPoint(timestamp: Long, gsrValue: Double, heartRate: Int, prr: Double) {
        try {
            val dataPoint = GSRDataPoint(
                timestamp = timestamp,
                shimmerTimestamp = timestamp / 1_000_000, // Convert to shimmer time scale
                gsrValue = gsrValue,
                ppgValue = 0.0, // TODO: Extract actual PPG value from ObjectCluster - See backlog.md "GSR Data Analysis"
                packetReceptionRate = prr,
                sessionId = currentSessionId ?: "",
                deviceId = currentShimmerDevice?.bluetoothAddress ?: "SHIMMER_DEVICE", // TODO: Enhanced device management - See backlog.md "Multi-Device Support"
                sampleRate = settingsManager.loadShimmerConfig().sampleRate.toDouble()
            )
            
            csvWriter?.write(dataPoint.toCsvString() + "\n")
            csvWriter?.flush()
            
        } catch (e: IOException) {
            Timber.e(e, "Failed to write data point to file")
        }
    }
    
    /**
     * Close recording file
     */
    private fun closeRecordingFile() {
        try {
            csvWriter?.close()
            csvWriter = null
            
            recordingFile?.let { file ->
                Timber.d("GSR recording saved to: ${file.absolutePath}")
            }
            recordingFile = null
            
        } catch (e: IOException) {
            Timber.e(e, "Failed to close recording file")
        }
    }
    
    // Enhanced Shimmer Data Visualization Support
    private var shimmerDataView: com.multimodal.capture.ui.components.ShimmerDataView? = null
    private var enhancedVisualizationCallback: ((String) -> Unit)? = null
    private var dataVisualizationEnabled = false

    /**
     * Set enhanced Shimmer data visualization view
     */
    fun setShimmerDataView(dataView: com.multimodal.capture.ui.components.ShimmerDataView) {
        shimmerDataView = dataView
        
        // Set up callbacks
        dataView.dataUpdateCallback = { dataPoint ->
            enhancedVisualizationCallback?.invoke("Data updated: GSR=${String.format("%.2f", dataPoint.gsrValue)}")
        }
        
        dataView.statisticsCallback = { gsrAvg, gsrStdDev, hrAvg, prr ->
            val statsMessage = "Stats - GSR Avg: ${String.format("%.2f", gsrAvg)}, HR Avg: $hrAvg, PRR: ${String.format("%.1f", prr)}%"
            enhancedVisualizationCallback?.invoke(statsMessage)
        }
        
        dataView.interactionCallback = { selectedPoint ->
            if (selectedPoint != null) {
                val interactionMessage = "Selected: GSR=${String.format("%.2f", selectedPoint.gsrValue)} at ${java.util.Date(selectedPoint.timestamp / 1_000_000)}"
                enhancedVisualizationCallback?.invoke(interactionMessage)
            }
        }
        
        dataVisualizationEnabled = true
        Timber.d("[DEBUG_LOG] Enhanced Shimmer data visualization view set")
    }

    /**
     * Set enhanced visualization callback
     */
    fun setEnhancedVisualizationCallback(callback: (String) -> Unit) {
        enhancedVisualizationCallback = callback
    }

    /**
     * Enable/disable real-time data visualization
     */
    fun setDataVisualizationEnabled(enabled: Boolean) {
        dataVisualizationEnabled = enabled
        if (enabled) {
            enhancedVisualizationCallback?.invoke("Data visualization enabled")
        } else {
            enhancedVisualizationCallback?.invoke("Data visualization disabled")
        }
        Timber.d("[DEBUG_LOG] Data visualization enabled: $enabled")
    }

    /**
     * Set visualization display mode
     */
    fun setVisualizationDisplayMode(mode: Int) {
        shimmerDataView?.setDisplayMode(mode)
        val modeString = when (mode) {
            0 -> "Real-time"
            1 -> "History"
            2 -> "Statistics"
            else -> "Unknown"
        }
        enhancedVisualizationCallback?.invoke("Display mode: $modeString")
        Timber.d("[DEBUG_LOG] Visualization display mode set to: $modeString")
    }

    /**
     * Set visualization time range
     */
    fun setVisualizationTimeRange(range: Long) {
        shimmerDataView?.setTimeRange(range)
        val rangeString = when (range) {
            30000L -> "30 seconds"
            60000L -> "1 minute"
            300000L -> "5 minutes"
            600000L -> "10 minutes"
            else -> "${range / 1000} seconds"
        }
        enhancedVisualizationCallback?.invoke("Time range: $rangeString")
        Timber.d("[DEBUG_LOG] Visualization time range set to: $rangeString")
    }

    /**
     * Set visualization graph type
     */
    fun setVisualizationGraphType(type: Int) {
        shimmerDataView?.setGraphType(type)
        val typeString = when (type) {
            0 -> "Line"
            1 -> "Bar"
            2 -> "Area"
            else -> "Unknown"
        }
        enhancedVisualizationCallback?.invoke("Graph type: $typeString")
        Timber.d("[DEBUG_LOG] Visualization graph type set to: $typeString")
    }

    /**
     * Set visualization color scheme
     */
    fun setVisualizationColorScheme(scheme: Int) {
        shimmerDataView?.setColorScheme(scheme)
        val schemeString = when (scheme) {
            0 -> "Default"
            1 -> "Medical"
            2 -> "High Contrast"
            else -> "Unknown"
        }
        enhancedVisualizationCallback?.invoke("Color scheme: $schemeString")
        Timber.d("[DEBUG_LOG] Visualization color scheme set to: $schemeString")
    }

    /**
     * Clear visualization data
     */
    fun clearVisualizationData() {
        shimmerDataView?.clearData()
        enhancedVisualizationCallback?.invoke("Visualization data cleared")
        Timber.d("[DEBUG_LOG] Visualization data cleared")
    }

    /**
     * Get current visualization statistics
     */
    fun getVisualizationStatistics(): Map<String, Any>? {
        val stats = shimmerDataView?.getStatistics()
        if (stats != null) {
            enhancedVisualizationCallback?.invoke("Statistics retrieved: ${stats.size} metrics")
        }
        return stats
    }

    /**
     * Export visualization data
     */
    fun exportVisualizationData(): List<GSRDataPoint>? {
        val data = shimmerDataView?.exportData()
        if (data != null) {
            enhancedVisualizationCallback?.invoke("Data exported: ${data.size} points")
            Timber.d("[DEBUG_LOG] Visualization data exported: ${data.size} points")
        }
        return data
    }

    /**
     * Enhanced data callback that feeds visualization
     */
    private fun updateVisualization(gsrValue: Double, heartRate: Int, prr: Double) {
        if (dataVisualizationEnabled && shimmerDataView != null) {
            try {
                shimmerDataView?.addGSRData(gsrValue, heartRate, prr, System.currentTimeMillis(), currentSessionId ?: "live")
                Timber.v("[DEBUG_LOG] Visualization updated: GSR=$gsrValue, HR=$heartRate, PRR=$prr")
            } catch (e: Exception) {
                Timber.e(e, "[DEBUG_LOG] Failed to update visualization")
                enhancedVisualizationCallback?.invoke("Visualization update failed: ${e.message}")
            }
        }
    }

    /**
     * Enhanced data processing with advanced analytics
     */
    private fun processEnhancedGSRData(objectCluster: ObjectCluster) {
        try {
            val gsrValue = extractGSRValue(objectCluster)
            val timestamp = extractTimestamp(objectCluster)
            val prr = extractPacketReceptionRate(objectCluster)
            
            // Calculate heart rate from PPG if available
            val heartRate = calculateHeartRateFromPPG(objectCluster)
            
            // Update visualization if enabled
            updateVisualization(gsrValue, heartRate, prr)
            
            // Apply data filtering and smoothing
            val filteredGSR = applyDataFiltering(gsrValue)
            val smoothedGSR = applyDataSmoothing(filteredGSR)
            
            // Detect anomalies
            val isAnomalous = detectDataAnomalies(smoothedGSR, heartRate, prr)
            if (isAnomalous) {
                enhancedVisualizationCallback?.invoke("Data anomaly detected: GSR=${String.format("%.2f", smoothedGSR)}")
            }
            
            // Call original data callback with processed data
            dataCallback?.invoke(smoothedGSR, heartRate, prr)
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Enhanced data processing failed")
            enhancedVisualizationCallback?.invoke("Data processing error: ${e.message}")
        }
    }

    /**
     * Calculate heart rate from PPG data
     */
    private fun calculateHeartRateFromPPG(objectCluster: ObjectCluster): Int {
        return try {
            // TODO: Implement proper PPG to heart rate conversion - See backlog.md "Advanced GSR Analysis"
            val ppgValue = objectCluster.getFormatClusterValue("PPG_A12", "CAL") ?: 0.0
            // Simplified heart rate estimation (replace with proper algorithm)
            val estimatedHR = (60 + (ppgValue % 100)).toInt().coerceIn(40, 200)
            estimatedHR
        } catch (e: Exception) {
            Timber.w(e, "[DEBUG_LOG] PPG heart rate calculation failed, using default")
            75 // Default heart rate
        }
    }

    /**
     * Apply data filtering to remove noise
     */
    private fun applyDataFiltering(gsrValue: Double): Double {
        // TODO: Implement advanced filtering algorithms - See backlog.md "Signal Processing"
        // Simple outlier filtering for now
        return gsrValue.coerceIn(0.1, 50.0)
    }

    /**
     * Apply data smoothing using moving average
     */
    private fun applyDataSmoothing(gsrValue: Double): Double {
        // TODO: Implement proper smoothing algorithms - See backlog.md "Signal Processing"
        // For now, return the filtered value
        return gsrValue
    }

    /**
     * Detect data anomalies
     */
    private fun detectDataAnomalies(gsrValue: Double, heartRate: Int, prr: Double): Boolean {
        // TODO: Implement advanced anomaly detection - See backlog.md "Quality Assurance"
        return gsrValue < 0.5 || gsrValue > 30.0 || heartRate < 40 || heartRate > 200 || prr < 80.0
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Timber.d("Cleaning up GSRSensorManager")
            
            // Stop recording if active
            if (isRecording) {
                stopRecording()
            }
            
            // Disconnect from devices
            disconnect()
            
            // Clear callbacks
            statusCallback = null
            dataCallback = null
            enhancedVisualizationCallback = null
            
            // Clear visualization
            shimmerDataView = null
            dataVisualizationEnabled = false
            
            // Cancel coroutine scope
            coroutineScope.cancel()
            
            Timber.d("GSRSensorManager cleanup completed")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during GSRSensorManager cleanup")
        }
    }
}