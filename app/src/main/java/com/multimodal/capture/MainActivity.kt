package com.multimodal.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import timber.log.Timber

/**
 * Main activity for the Multi-Modal Capture application.
 * Handles UI interactions, permission requests, and coordinates between different capture modules.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // Required permissions for the app
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )
    
    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeServices()
        } else {
            showPermissionError()
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
        
        // Setup UI
        setupUI()
        
        // Setup observers
        setupObservers()
        
        // Check and request permissions
        checkPermissions()
    }
    
    private fun setupUI() {
        // Recording control button
        binding.btnRecordingControl.setOnClickListener {
            if (viewModel.isRecording.value == true) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        
        // Preview mode toggle
        binding.btnPreviewToggle.setOnClickListener {
            viewModel.togglePreviewMode()
        }
        
        // Bluetooth scan button
        binding.btnBluetoothScan.setOnClickListener {
            viewModel.scanForBluetoothDevices()
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
        
        // Preview mode
        viewModel.previewMode.observe(this) { mode ->
            updatePreviewMode(mode)
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
    
    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initializeServices()
        }
    }
    
    private fun initializeServices() {
        Timber.d("Initializing services...")
        
        // Start network service
        val networkIntent = Intent(this, NetworkService::class.java)
        startService(networkIntent)
        
        // Initialize capture modules
        viewModel.initializeCaptureModules()
        
        // Setup camera preview after modules are initialized
        setupCameraPreview()
        
        Timber.d("Services initialized")
    }
    
    /**
     * Setup camera preview
     */
    private fun setupCameraPreview() {
        // Setup camera preview with the PreviewView
        viewModel.setupCameraPreview(binding.cameraPreview)
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
    
    private fun updatePreviewMode(mode: String) {
        binding.btnPreviewToggle.text = mode
        
        // Toggle between RGB and thermal preview
        if (mode == getString(R.string.preview_mode_rgb)) {
            binding.cameraPreview.visibility = android.view.View.VISIBLE
            binding.thermalPreview.visibility = android.view.View.GONE
        } else {
            binding.cameraPreview.visibility = android.view.View.GONE
            binding.thermalPreview.visibility = android.view.View.VISIBLE
        }
    }
    
    private fun showPermissionError() {
        Toast.makeText(
            this,
            "All permissions are required for the app to function properly",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop services
        stopService(Intent(this, RecordingService::class.java))
        stopService(Intent(this, NetworkService::class.java))
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}