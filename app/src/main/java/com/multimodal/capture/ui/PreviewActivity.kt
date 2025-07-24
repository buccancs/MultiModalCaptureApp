package com.multimodal.capture.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.multimodal.capture.R
import com.multimodal.capture.databinding.ActivityPreviewBinding
import com.multimodal.capture.viewmodel.PreviewViewModel
import com.multimodal.capture.utils.PermissionManager
import timber.log.Timber

/**
 * PreviewActivity provides a dedicated camera preview interface with multi-camera switching.
 * Supports Front, Back Main, Wide Angle, Telephoto, and Thermal (IR) cameras.
 */
class PreviewActivity : AppCompatActivity(), PermissionManager.PermissionCallback {
    
    private lateinit var binding: ActivityPreviewBinding
    private lateinit var viewModel: PreviewViewModel
    private lateinit var permissionManager: PermissionManager
    
    // Camera types enum
    enum class CameraType(val id: Int, val displayName: String) {
        FRONT(1, "Front Camera"),
        BACK_MAIN(0, "Back Camera (Main)"),
        WIDE_ANGLE(2, "Wide Angle Camera"),
        TELEPHOTO(3, "Telephoto Camera"),
        THERMAL(-1, "Thermal Camera (IR)")
    }
    
    private var currentCameraType = CameraType.BACK_MAIN
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[PreviewViewModel::class.java]
        
        // Initialize Permission Manager
        permissionManager = PermissionManager(this)
        permissionManager.setPermissionCallback(this)
        
        // Setup UI
        setupUI()
        
        // Setup observers
        setupObservers()
        
        // Initialize camera system
        initializeCameraSystem()
        
        Timber.d("PreviewActivity created")
    }
    
    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Camera selection buttons
        binding.btnCameraFront.setOnClickListener {
            switchToCamera(CameraType.FRONT)
        }
        
        binding.btnCameraBackMain.setOnClickListener {
            switchToCamera(CameraType.BACK_MAIN)
        }
        
        binding.btnCameraWide.setOnClickListener {
            switchToCamera(CameraType.WIDE_ANGLE)
        }
        
        binding.btnCameraTele.setOnClickListener {
            switchToCamera(CameraType.TELEPHOTO)
        }
        
        binding.btnCameraThermal.setOnClickListener {
            switchToCamera(CameraType.THERMAL)
        }
        
        // Set initial button states
        updateCameraButtonStates()
        updateCameraInfo()
    }
    
    private fun setupObservers() {
        // Camera status
        viewModel.cameraStatus.observe(this) { status ->
            binding.tvCameraStatusOverlay.text = status
            binding.tvCameraStatus.text = "Status: $status"
        }
        
        // Current camera info
        viewModel.currentCameraInfo.observe(this) { cameraInfo ->
            binding.tvCurrentCamera.text = "Current: ${cameraInfo.name}"
            binding.tvResolution.text = "Resolution: ${cameraInfo.resolution}"
            binding.tvFps.text = "FPS: ${cameraInfo.fps}"
        }
        
        // Error messages
        viewModel.errorMessage.observe(this) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        
        // Camera availability
        viewModel.availableCameras.observe(this) { cameras ->
            updateCameraButtonAvailability(cameras)
        }
        
        // Thermal camera status
        viewModel.thermalCameraStatus.observe(this) { status ->
            if (currentCameraType == CameraType.THERMAL) {
                binding.tvCameraStatusOverlay.text = status
            }
        }
    }
    
    private fun initializeCameraSystem() {
        // Check permissions first
        if (permissionManager.areEssentialPermissionsGranted()) {
            viewModel.initializeCameraSystem(this)
        } else {
            permissionManager.requestAllPermissions()
        }
    }
    
    private fun switchToCamera(cameraType: CameraType) {
        if (currentCameraType == cameraType) {
            Timber.d("Already using ${cameraType.displayName}")
            return
        }
        
        Timber.d("Switching to ${cameraType.displayName}")
        
        // Show loading state
        showCameraSwitchingLoading(true)
        disableCameraButtons(true)
        
        // Add haptic feedback
        performHapticFeedback()
        
        // Fade out current preview with animation
        fadeOutCurrentPreview {
            // Update camera type after fade out
            currentCameraType = cameraType
            
            when (cameraType) {
                CameraType.THERMAL -> {
                    // Switch to thermal camera
                    binding.cameraPreview.visibility = android.view.View.GONE
                    binding.thermalPreview.visibility = android.view.View.VISIBLE
                    viewModel.switchToThermalCamera(binding.thermalPreview)
                }
                else -> {
                    // Switch to RGB camera
                    binding.thermalPreview.visibility = android.view.View.GONE
                    binding.cameraPreview.visibility = android.view.View.VISIBLE
                    viewModel.switchToRGBCamera(cameraType.id, binding.cameraPreview)
                }
            }
            
            // Fade in new preview
            fadeInNewPreview {
                // Hide loading state and re-enable buttons
                showCameraSwitchingLoading(false)
                disableCameraButtons(false)
                updateCameraButtonStates()
                updateCameraInfo()
            }
        }
    }
    
    private fun updateCameraButtonStates() {
        // Reset all buttons to default state
        val defaultColor = getColor(R.color.purple_500)
        val selectedColor = getColor(R.color.purple_700)
        val thermalDefaultColor = getColor(R.color.teal_700)
        val thermalSelectedColor = getColor(R.color.teal_200)
        
        binding.btnCameraFront.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultColor)
        binding.btnCameraBackMain.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultColor)
        binding.btnCameraWide.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultColor)
        binding.btnCameraTele.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultColor)
        binding.btnCameraThermal.backgroundTintList = android.content.res.ColorStateList.valueOf(thermalDefaultColor)
        
        // Highlight selected camera
        when (currentCameraType) {
            CameraType.FRONT -> binding.btnCameraFront.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            CameraType.BACK_MAIN -> binding.btnCameraBackMain.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            CameraType.WIDE_ANGLE -> binding.btnCameraWide.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            CameraType.TELEPHOTO -> binding.btnCameraTele.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            CameraType.THERMAL -> binding.btnCameraThermal.backgroundTintList = android.content.res.ColorStateList.valueOf(thermalSelectedColor)
        }
    }
    
    private fun updateCameraInfo() {
        val cameraInfo = when (currentCameraType) {
            CameraType.FRONT -> PreviewViewModel.CameraInfo(
                name = "Front Camera",
                resolution = "1920x1080",
                fps = "30"
            )
            CameraType.BACK_MAIN -> PreviewViewModel.CameraInfo(
                name = "Back Camera (Main)",
                resolution = "1920x1080",
                fps = "30"
            )
            CameraType.WIDE_ANGLE -> PreviewViewModel.CameraInfo(
                name = "Wide Angle Camera",
                resolution = "1920x1080",
                fps = "30"
            )
            CameraType.TELEPHOTO -> PreviewViewModel.CameraInfo(
                name = "Telephoto Camera",
                resolution = "1920x1080",
                fps = "30"
            )
            CameraType.THERMAL -> PreviewViewModel.CameraInfo(
                name = "Thermal Camera (IR)",
                resolution = "256x192",
                fps = "30"
            )
        }
        
        viewModel.updateCurrentCameraInfo(cameraInfo)
    }
    
    private fun updateCameraButtonAvailability(availableCameras: List<Int>) {
        // Enable/disable buttons based on camera availability
        binding.btnCameraFront.isEnabled = availableCameras.contains(CameraType.FRONT.id)
        binding.btnCameraBackMain.isEnabled = availableCameras.contains(CameraType.BACK_MAIN.id)
        binding.btnCameraWide.isEnabled = availableCameras.contains(CameraType.WIDE_ANGLE.id)
        binding.btnCameraTele.isEnabled = availableCameras.contains(CameraType.TELEPHOTO.id)
        
        // Thermal camera availability is handled separately
        viewModel.isThermalCameraAvailable.observe(this) { isAvailable ->
            binding.btnCameraThermal.isEnabled = isAvailable
        }
        
        // Update button appearance for disabled cameras
        val disabledAlpha = 0.5f
        val enabledAlpha = 1.0f
        
        binding.btnCameraFront.alpha = if (binding.btnCameraFront.isEnabled) enabledAlpha else disabledAlpha
        binding.btnCameraBackMain.alpha = if (binding.btnCameraBackMain.isEnabled) enabledAlpha else disabledAlpha
        binding.btnCameraWide.alpha = if (binding.btnCameraWide.isEnabled) enabledAlpha else disabledAlpha
        binding.btnCameraTele.alpha = if (binding.btnCameraTele.isEnabled) enabledAlpha else disabledAlpha
        binding.btnCameraThermal.alpha = if (binding.btnCameraThermal.isEnabled) enabledAlpha else disabledAlpha
    }
    
    // PermissionCallback interface implementations
    override fun onPermissionsGranted(grantedPermissions: List<String>) {
        Timber.d("Permissions granted: ${grantedPermissions.joinToString(", ")}")
        if (permissionManager.areEssentialPermissionsGranted()) {
            viewModel.initializeCameraSystem(this)
        }
    }
    
    override fun onPermissionsDenied(deniedPermissions: List<String>) {
        Timber.w("Permissions denied: ${deniedPermissions.joinToString(", ")}")
        Toast.makeText(
            this,
            "Camera permissions are required for preview functionality",
            Toast.LENGTH_LONG
        ).show()
    }
    
    override fun onPermissionsPermanentlyDenied(permanentlyDeniedPermissions: List<String>) {
        Timber.w("Permissions permanently denied: ${permanentlyDeniedPermissions.joinToString(", ")}")
        Toast.makeText(
            this,
            "Camera permissions were permanently denied. Please enable them in Settings.",
            Toast.LENGTH_LONG
        ).show()
    }
    
    override fun onAllEssentialPermissionsGranted() {
        Timber.d("All essential permissions granted - initializing camera system")
        viewModel.initializeCameraSystem(this)
    }
    
    override fun onEssentialPermissionsDenied() {
        Timber.w("Essential permissions denied - camera preview will not work")
        Toast.makeText(
            this,
            "Essential camera permissions denied. Preview functionality disabled.",
            Toast.LENGTH_LONG
        ).show()
    }
    
    /**
     * Show or hide camera switching loading indicator
     */
    private fun showCameraSwitchingLoading(show: Boolean) {
        binding.tvCameraStatusOverlay.text = if (show) "Switching camera..." else "Camera Ready"
        binding.tvCameraStatusOverlay.visibility = if (show) View.VISIBLE else View.VISIBLE
    }
    
    /**
     * Enable or disable camera selection buttons
     */
    private fun disableCameraButtons(disable: Boolean) {
        val alpha = if (disable) 0.5f else 1.0f
        binding.btnCameraFront.isEnabled = !disable
        binding.btnCameraBackMain.isEnabled = !disable
        binding.btnCameraWide.isEnabled = !disable
        binding.btnCameraTele.isEnabled = !disable
        binding.btnCameraThermal.isEnabled = !disable
        
        binding.btnCameraFront.alpha = alpha
        binding.btnCameraBackMain.alpha = alpha
        binding.btnCameraWide.alpha = alpha
        binding.btnCameraTele.alpha = alpha
        binding.btnCameraThermal.alpha = alpha
    }
    
    /**
     * Provide haptic feedback for camera switching
     */
    private fun performHapticFeedback() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to provide haptic feedback")
        }
    }
    
    /**
     * Fade out current preview with animation
     */
    private fun fadeOutCurrentPreview(onComplete: () -> Unit) {
        val currentView = when (currentCameraType) {
            CameraType.THERMAL -> binding.thermalPreview
            else -> binding.cameraPreview
        }
        
        currentView.animate()
            .alpha(0f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            .start()
    }
    
    /**
     * Fade in new preview with animation
     */
    private fun fadeInNewPreview(onComplete: () -> Unit) {
        val newView = when (currentCameraType) {
            CameraType.THERMAL -> binding.thermalPreview
            else -> binding.cameraPreview
        }
        
        // Start with alpha 0 and fade in
        newView.alpha = 0f
        newView.animate()
            .alpha(1f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            .start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
        Timber.d("PreviewActivity destroyed")
    }
    
    companion object {
        private const val TAG = "PreviewActivity"
    }
}