package com.multimodal.capture

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.multimodal.capture.databinding.ActivityMainBinding
import com.multimodal.capture.service.RecordingService
import com.multimodal.capture.service.NetworkService
import com.multimodal.capture.viewmodel.MainViewModel
import com.multimodal.capture.utils.PermissionManager
import com.multimodal.capture.utils.SettingsManager
import com.multimodal.capture.data.RecordingConfig
import com.multimodal.capture.ui.BluetoothDeviceActivity
import timber.log.Timber

/**
 * Main activity for the Multi-Modal Capture application.
 * Handles UI interactions, permission requests, and coordinates between different capture modules.
 */
class MainActivity : AppCompatActivity(), PermissionManager.PermissionCallback {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var permissionManager: PermissionManager
    private lateinit var settingsManager: SettingsManager
    
    // USB device handling
    private lateinit var usbManager: UsbManager
    private var usbPermissionReceiver: BroadcastReceiver? = null
    
    // Track if services have been initialized
    private var servicesInitialized = false
    
    // Current recording configuration
    private var currentRecordingConfig: RecordingConfig? = null
    
    // Activity result launcher for Bluetooth device selection
    private val bluetoothDeviceSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deviceAddress = result.data?.getStringExtra(BluetoothDeviceActivity.EXTRA_DEVICE_ADDRESS)
            val deviceName = result.data?.getStringExtra(BluetoothDeviceActivity.EXTRA_DEVICE_NAME)
            
            if (deviceAddress != null) {
                Timber.d("Selected GSR device: $deviceName ($deviceAddress)")
                
                // Update settings with selected device
                settingsManager.updateShimmerDevice(deviceAddress, deviceName ?: "Unknown Device")
                
                // Connect to the selected device
                viewModel.connectToGSRDevice(deviceAddress)
                
                Toast.makeText(this, "Connecting to $deviceName...", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        
        // Initialize Permission Manager
        permissionManager = PermissionManager(this)
        permissionManager.setPermissionCallback(this)
        
        // Initialize Settings Manager
        settingsManager = SettingsManager.getInstance(this)
        
        // Initialize USB Manager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Setup USB permission receiver
        setupUsbPermissionReceiver()
        
        // Load current recording configuration
        loadRecordingConfiguration()
        
        // Setup UI
        setupUI()
        
        // Setup observers
        setupObservers()
        
        // Handle USB device attachment intent
        handleUsbDeviceAttachment(intent)
        
        // Request all permissions at startup
        permissionManager.requestAllPermissions()
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleUsbDeviceAttachment(it) }
    }
    
    /**
     * Setup USB permission receiver for handling USB permission responses
     */
    private fun setupUsbPermissionReceiver() {
        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (ACTION_USB_PERMISSION == action) {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Timber.d("USB permission granted for device: ${it.deviceName}")
                                Toast.makeText(this@MainActivity, "USB permission granted for thermal camera", Toast.LENGTH_SHORT).show()
                                // Notify that USB permission is granted
                                onUsbPermissionGranted(it)
                            }
                        } else {
                            Timber.d("USB permission denied for device: ${device?.deviceName}")
                            Toast.makeText(this@MainActivity, "USB permission denied for thermal camera", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        
        // Register the receiver with proper flags for Android 13+
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }
    
    /**
     * Handle USB device attachment intent
     */
    private fun handleUsbDeviceAttachment(intent: Intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let {
                Timber.d("USB device attached: ${it.deviceName}, VendorId: ${it.vendorId}, ProductId: ${it.productId}")
                
                // Check if this is a Topdon thermal camera
                if (isThermalCamera(it)) {
                    Timber.d("Topdon thermal camera detected")
                    Toast.makeText(this, "Topdon thermal camera detected", Toast.LENGTH_SHORT).show()
                    
                    // Request USB permission
                    requestUsbPermission(it)
                }
            }
        }
    }
    
    /**
     * Check if the USB device is a Topdon thermal camera
     */
    private fun isThermalCamera(device: UsbDevice): Boolean {
        val vendorId = device.vendorId
        val productId = device.productId
        
        // Check against known Topdon vendor/product IDs
        return when (vendorId) {
            0x1f3a -> productId in listOf(0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008, 0x1009, 0x100a, 0x100b, 0x100c, 0x100d, 0x100e, 0x100f, 0x1010)
            0x3538 -> productId in listOf(0x0902)
            else -> false
        }
    }
    
    /**
     * Request USB permission for the device
     */
    private fun requestUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Timber.d("USB permission already granted for device: ${device.deviceName}")
            onUsbPermissionGranted(device)
        } else {
            Timber.d("Requesting USB permission for device: ${device.deviceName}")
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }
    
    /**
     * Handle USB permission granted for thermal camera
     */
    private fun onUsbPermissionGranted(device: UsbDevice) {
        Timber.d("Processing USB permission granted for device: ${device.deviceName}")
        // TODO: Integrate with ThermalCameraManager to establish connection
        // For now, just show a success message
        Toast.makeText(this, "Ready to connect to thermal camera", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Load recording configuration from settings
     */
    private fun loadRecordingConfiguration() {
        try {
            currentRecordingConfig = settingsManager.loadRecordingConfig()
            Timber.d("Loaded recording configuration: ${currentRecordingConfig?.getSummary()}")
            
            // Update UI based on configuration
            updateDeviceStatusIndicators()
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load recording configuration")
            // Use default configuration
            currentRecordingConfig = RecordingConfig()
        }
    }
    
    /**
     * Update device status indicators based on configuration and connectivity
     */
    private fun updateDeviceStatusIndicators() {
        currentRecordingConfig?.let { config ->
            // Update camera status
            updateCameraStatus(config.camera.enabled)
            
            // Update thermal status
            updateThermalStatus(config.thermal.enabled)
            
            // Update shimmer status
            updateShimmerStatus(config.shimmer.enabled, config.shimmer.isConfigured())
        }
    }
    
    /**
     * Update camera status indicator
     */
    private fun updateCameraStatus(enabled: Boolean) {
        if (enabled) {
            binding.tvCameraStatus.text = getString(R.string.status_camera_ready)
            binding.tvCameraStatus.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_camera, 0, 0, 0
            )
            binding.tvCameraStatus.compoundDrawables[0]?.setTint(
                ContextCompat.getColor(this, R.color.status_ready)
            )
        } else {
            binding.tvCameraStatus.text = "Camera Disabled"
            binding.tvCameraStatus.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_camera, 0, 0, 0
            )
            binding.tvCameraStatus.compoundDrawables[0]?.setTint(
                ContextCompat.getColor(this, R.color.status_disabled)
            )
        }
    }
    
    /**
     * Update thermal camera status indicator
     */
    private fun updateThermalStatus(enabled: Boolean) {
        if (enabled) {
            binding.tvThermalStatus.text = "Thermal Ready"
            binding.tvThermalStatus.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_thermal, 0, 0, 0
            )
            binding.tvThermalStatus.compoundDrawables[0]?.setTint(
                ContextCompat.getColor(this, R.color.status_ready)
            )
        } else {
            binding.tvThermalStatus.text = "Thermal Disabled"
            binding.tvThermalStatus.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_thermal, 0, 0, 0
            )
            binding.tvThermalStatus.compoundDrawables[0]?.setTint(
                ContextCompat.getColor(this, R.color.status_disabled)
            )
        }
    }
    
    /**
     * Update Shimmer GSR sensor status indicator
     */
    private fun updateShimmerStatus(enabled: Boolean, configured: Boolean) {
        when {
            !enabled -> {
                binding.tvGsrStatus.text = "GSR Disabled"
                binding.tvGsrStatus.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_sensor, 0, 0, 0
                )
                binding.tvGsrStatus.compoundDrawables[0]?.setTint(
                    ContextCompat.getColor(this, R.color.status_disabled)
                )
            }
            enabled && !configured -> {
                binding.tvGsrStatus.text = "GSR Not Configured"
                binding.tvGsrStatus.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_sensor, 0, 0, 0
                )
                binding.tvGsrStatus.compoundDrawables[0]?.setTint(
                    ContextCompat.getColor(this, R.color.status_warning)
                )
            }
            enabled && configured -> {
                // Check actual connection status from ViewModel
                val isConnected = viewModel.isConnected.value ?: false
                if (isConnected) {
                    binding.tvGsrStatus.text = getString(R.string.status_gsr_connected)
                    binding.tvGsrStatus.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_sensor, 0, 0, 0
                    )
                    binding.tvGsrStatus.compoundDrawables[0]?.setTint(
                        ContextCompat.getColor(this, R.color.status_connected)
                    )
                } else {
                    binding.tvGsrStatus.text = getString(R.string.status_gsr_disconnected)
                    binding.tvGsrStatus.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_sensor, 0, 0, 0
                    )
                    binding.tvGsrStatus.compoundDrawables[0]?.setTint(
                        ContextCompat.getColor(this, R.color.status_disconnected)
                    )
                }
            }
        }
    }
    
    private fun setupUI() {
        // Camera preview button
        binding.btnCameraPreview.setOnClickListener {
            val previewIntent = Intent(this, com.multimodal.capture.ui.PreviewActivity::class.java)
            startActivity(previewIntent)
        }
        
        // Recording control button
        binding.btnRecordingControl.setOnClickListener {
            if (viewModel.isRecording.value == true) {
                stopRecording()
            } else {
                // Check recording permissions before starting
                if (checkRecordingPermissions()) {
                    startRecording()
                } else {
                    requestRecordingPermissions()
                }
            }
        }
        
        
        // Bluetooth scan button
        binding.btnBluetoothScan.setOnClickListener {
            // Check Bluetooth permissions before scanning
            if (checkBluetoothPermissions()) {
                // Launch BluetoothDeviceActivity for device selection
                val bluetoothIntent = Intent(this, BluetoothDeviceActivity::class.java)
                bluetoothDeviceSelectionLauncher.launch(bluetoothIntent)
            } else {
                requestBluetoothPermissions()
            }
        }
        
        // Settings button
        binding.btnSettings.setOnClickListener {
            val settingsIntent = Intent(this, com.multimodal.capture.ui.SettingsActivity::class.java)
            startActivity(settingsIntent)
        }
    }
    
    private fun setupObservers() {
        // Recording state
        viewModel.isRecording.observe(this) { isRecording ->
            updateRecordingButton(isRecording)
        }
        
        
        // Camera status
        viewModel.cameraStatus.observe(this) { status ->
            binding.tvCameraStatus.text = status
        }
        
        // Thermal camera status
        viewModel.thermalStatus.observe(this) { status ->
            binding.tvThermalStatus.text = status
        }
        
        // GSR sensor status
        viewModel.gsrStatus.observe(this) { status ->
            binding.tvGsrStatus.text = status
        }
        
        // Network status
        viewModel.networkStatus.observe(this) { status ->
            binding.tvNetworkStatus.text = status
        }
        
        // GSR value
        viewModel.gsrValue.observe(this) { value ->
            binding.tvGsrValue.text = getString(R.string.gsr_value, value)
        }
        
        // Heart rate
        viewModel.heartRate.observe(this) { heartRate ->
            binding.tvHeartRate.text = getString(R.string.heart_rate_value, heartRate)
        }
        
        // Error messages
        viewModel.errorMessage.observe(this) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        
    }
    
    // PermissionCallback interface implementations
    override fun onPermissionsGranted(grantedPermissions: List<String>) {
        Timber.d("Permissions granted: ${grantedPermissions.joinToString(", ")}")
        // Initialize services if not already done and essential permissions are granted
        if (!servicesInitialized && permissionManager.areEssentialPermissionsGranted()) {
            initializeServices()
        }
    }
    
    override fun onPermissionsDenied(deniedPermissions: List<String>) {
        Timber.w("Permissions denied: ${deniedPermissions.joinToString(", ")}")
        
        // Check if essential permissions (camera, audio) were denied
        val essentialPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        val essentialDenied = deniedPermissions.any { it in essentialPermissions }
        
        if (essentialDenied && servicesInitialized) {
            Timber.d("Essential permissions denied - cleaning up camera resources")
            cleanupCameraResources()
        }
        
        // Show toast to inform user about denied permissions
        Toast.makeText(
            this,
            "Some features may be limited due to denied permissions",
            Toast.LENGTH_LONG
        ).show()
    }
    
    override fun onPermissionsPermanentlyDenied(permanentlyDeniedPermissions: List<String>) {
        Timber.w("Permissions permanently denied: ${permanentlyDeniedPermissions.joinToString(", ")}")
        // Show toast about permanently denied permissions
        Toast.makeText(
            this,
            "Some permissions were permanently denied. You can enable them in Settings.",
            Toast.LENGTH_LONG
        ).show()
    }
    
    override fun onAllEssentialPermissionsGranted() {
        Timber.d("All essential permissions granted - initializing services")
        if (!servicesInitialized) {
            initializeServices()
        }
    }
    
    override fun onEssentialPermissionsDenied() {
        Timber.w("Essential permissions denied - app will run with limited functionality")
        
        // Clean up camera resources when essential permissions are denied
        if (servicesInitialized) {
            Timber.d("Essential permissions denied - cleaning up camera resources")
            cleanupCameraResources()
        }
        
        // Don't crash the app - allow it to continue with limited functionality
        // The PermissionManager will show appropriate dialogs to the user
    }
    
    private fun initializeServices() {
        if (servicesInitialized) {
            Timber.d("Services already initialized")
            return
        }
        
        Timber.d("Initializing services...")
        
        // Start network service
        val networkIntent = Intent(this, NetworkService::class.java)
        startService(networkIntent)
        
        // Initialize capture modules
        viewModel.initializeCaptureModules(this)
        
        servicesInitialized = true
        Timber.d("Services initialized successfully")
    }
    
    /**
     * Clean up camera resources when permissions are denied
     */
    private fun cleanupCameraResources() {
        try {
            Timber.d("Cleaning up camera resources due to permission denial")
            
            // Clean up capture modules through ViewModel
            viewModel.cleanupCaptureModules()
            
            // Reset services initialized flag to allow re-initialization
            servicesInitialized = false
            
            // Stop network service
            stopService(Intent(this, NetworkService::class.java))
            
            Timber.d("Camera resources cleaned up successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during camera resource cleanup")
        }
    }
    
    
    private fun startRecording() {
        Timber.d("Starting recording...")
        
        // Generate session ID with timestamp
        val sessionId = "Session_${System.currentTimeMillis()}"
        val startTimestamp = SystemClock.elapsedRealtimeNanos()
        
        // Start recording service
        val recordingIntent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
            putExtra(RecordingService.EXTRA_START_TIMESTAMP, startTimestamp)
        }
        
        // Use appropriate service start method based on API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(recordingIntent)
        } else {
            startService(recordingIntent)
        }
        
        // Update ViewModel
        viewModel.startRecording(sessionId, startTimestamp)
        
        Timber.d("Recording started with session ID: $sessionId")
    }
    
    private fun stopRecording() {
        Timber.d("Stopping recording...")
        
        // Stop recording service
        val recordingIntent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        startService(recordingIntent)
        
        // Update ViewModel
        viewModel.stopRecording()
        
        Timber.d("Recording stopped")
    }
    
    private fun updateRecordingButton(isRecording: Boolean) {
        if (isRecording) {
            binding.btnRecordingControl.text = getString(R.string.stop_recording)
            binding.btnRecordingControl.setBackgroundColor(
                ContextCompat.getColor(this, R.color.record_stop)
            )
        } else {
            binding.btnRecordingControl.text = getString(R.string.start_recording)
            binding.btnRecordingControl.setBackgroundColor(
                ContextCompat.getColor(this, R.color.record_start)
            )
        }
    }
    
    
    /**
     * Request permissions for Bluetooth scanning feature
     */
    private fun requestBluetoothPermissions() {
        val bluetoothPermissions = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        permissionManager.requestPermissionsForFeature("Bluetooth Scanning", bluetoothPermissions)
    }
    
    /**
     * Request permissions for recording feature
     */
    private fun requestRecordingPermissions() {
        val recordingPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        permissionManager.requestPermissionsForFeature("Recording", recordingPermissions)
    }
    
    /**
     * Check if recording permissions are available before starting recording
     */
    private fun checkRecordingPermissions(): Boolean {
        return permissionManager.isPermissionGranted(Manifest.permission.CAMERA) &&
               permissionManager.isPermissionGranted(Manifest.permission.RECORD_AUDIO)
    }
    
    /**
     * Check if Bluetooth permissions are available before scanning
     */
    private fun checkBluetoothPermissions(): Boolean {
        return permissionManager.isPermissionGranted(Manifest.permission.BLUETOOTH_CONNECT) &&
               permissionManager.isPermissionGranted(Manifest.permission.BLUETOOTH_SCAN) &&
               permissionManager.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister USB permission receiver
        usbPermissionReceiver?.let {
            try {
                unregisterReceiver(it)
                usbPermissionReceiver = null
                Timber.d("USB permission receiver unregistered")
            } catch (e: Exception) {
                Timber.w(e, "Failed to unregister USB permission receiver")
            }
        }
        
        // Stop services
        stopService(Intent(this, RecordingService::class.java))
        stopService(Intent(this, NetworkService::class.java))
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.multimodal.capture.USB_PERMISSION"
    }
}