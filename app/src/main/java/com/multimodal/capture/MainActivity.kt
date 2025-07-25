package com.multimodal.capture

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.multimodal.capture.databinding.ActivityMainBinding
import com.multimodal.capture.service.RecordingService
import com.multimodal.capture.service.NetworkService
import com.multimodal.capture.viewmodel.MainViewModel
import com.multimodal.capture.utils.PermissionManager
import com.multimodal.capture.utils.SettingsManager
import com.multimodal.capture.utils.LoggingManager
import com.multimodal.capture.data.RecordingConfig
import com.multimodal.capture.ui.BluetoothDeviceActivity
import com.multimodal.capture.ui.adapters.MainPagerAdapter
import com.multimodal.capture.ui.components.CustomBottomNavigationView
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
    
    // Navigation components
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNavigation: CustomBottomNavigationView
    private lateinit var pagerAdapter: MainPagerAdapter
    
    // Service connection
    private var recordingService: RecordingService? = null
    private var isServiceBound = false
    
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
        
        // Initialize comprehensive logging system
        LoggingManager.getInstance().initialize(this, enableFileLogging = true)
        
        // Use enhanced layout with ViewPager2 and CustomBottomNavigationView
        setContentView(R.layout.activity_main_enhanced)
        
        // Initialize navigation components
        initializeNavigation()
        
        // Initialize binding for backward compatibility (create minimal binding)
        binding = ActivityMainBinding.inflate(layoutInflater)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        
        // Initialize Permission Manager
        permissionManager = PermissionManager(this)
        permissionManager.setPermissionCallback(this)
        
        // Initialize Settings Manager
        settingsManager = SettingsManager.getInstance(this)
        
        // Load current recording configuration
        loadRecordingConfiguration()
        
        // Setup observers
        setupObservers()
        
        // Request all permissions at startup
        permissionManager.requestAllPermissions()
    }
    
    override fun onStart() {
        super.onStart()
        // Bind to RecordingService
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    /**
     * Initialize ViewPager2 and CustomBottomNavigationView
     */
    private fun initializeNavigation() {
        try {
            // Find navigation components
            viewPager = findViewById(R.id.view_pager)
            bottomNavigation = findViewById(R.id.bottom_navigation)
            
            // Create and set adapter
            pagerAdapter = MainPagerAdapter(this)
            viewPager.adapter = pagerAdapter
            
            // Configure ViewPager2 settings based on IRCamera patterns
            viewPager.offscreenPageLimit = 3 // Keep all fragments in memory for smooth transitions
            viewPager.isUserInputEnabled = false // Disable user swiping for controlled navigation
            
            // Set default tab to Capture (center tab)
            viewPager.setCurrentItem(MainPagerAdapter.TAB_CAPTURE, false)
            
            // Set up navigation callbacks
            setupNavigationCallbacks()
            
            Timber.d("[DEBUG_LOG] Navigation components initialized successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to initialize navigation components")
        }
    }
    
    /**
     * Set up callbacks between ViewPager2 and CustomBottomNavigationView
     */
    private fun setupNavigationCallbacks() {
        // Handle bottom navigation tab selection
        bottomNavigation.onTabSelectedListener = { tabIndex ->
            Timber.d("[DEBUG_LOG] Bottom navigation tab selected: $tabIndex")
            viewPager.setCurrentItem(tabIndex, false) // false for no smooth scrolling since user input is disabled
        }
        
        // Handle ViewPager2 page changes (in case we need to sync from other sources)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Timber.d("[DEBUG_LOG] ViewPager page selected: $position")
                bottomNavigation.setSelectedTabSilently(position)
            }
        })
    }
    
    /** Defines callbacks for service binding, passed to bindService()  */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            isServiceBound = true
            viewModel.setRecordingService(recordingService)
            Timber.d("RecordingService connected and bound to ViewModel.")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            recordingService = null
            viewModel.setRecordingService(null)
            Timber.w("RecordingService disconnected.")
        }
    }
    
    /**
     * Load recording configuration from settings
     */
    private fun loadRecordingConfiguration() {
        try {
            currentRecordingConfig = settingsManager.loadRecordingConfig()
            Timber.d("Loaded recording configuration: ${currentRecordingConfig?.getSummary()}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load recording configuration")
            // Use default configuration
            currentRecordingConfig = RecordingConfig()
        }
    }
    
    
    private fun setupObservers() {
        // Error messages - only essential observer for MainActivity
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
    
    
    
    /**
     * Enhanced onDestroy method with comprehensive resource cleanup
     * to prevent memory leaks and ensure proper application shutdown.
     */
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            Timber.d("Starting MainActivity cleanup process...")
            
            // Cleanup camera resources first to release hardware locks
            cleanupCameraResources()
            
            // Unbind from service if it's still bound
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
            
            // Clear all ViewModel observers to prevent memory leaks
            // This is critical for preventing activity context leaks
            viewModel.errorMessage.removeObservers(this)
            
            // Stop background services gracefully
            stopService(Intent(this, RecordingService::class.java))
            stopService(Intent(this, NetworkService::class.java))
            
            // Clear object references to help garbage collection
            currentRecordingConfig = null
            
            // Cleanup logging manager resources
            LoggingManager.getInstance().cleanup()
            
            Timber.d("MainActivity cleanup completed successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during MainActivity cleanup - some resources may not have been released properly")
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}