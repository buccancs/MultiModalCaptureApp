package com.multimodal.capture.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Comprehensive permission manager for the Multi-Modal Capture application.
 * Handles all required permissions with graceful degradation and re-request functionality.
 */
class PermissionManager(private val activity: AppCompatActivity) {
    
    /**
     * Permission categories for better organization and graceful degradation
     */
    enum class PermissionCategory {
        ESSENTIAL,    // App cannot function without these
        CORE,         // Main features require these
        OPTIONAL      // Nice-to-have features
    }
    
    /**
     * Permission status for tracking individual permission states
     */
    enum class PermissionStatus {
        GRANTED,
        DENIED,
        PERMANENTLY_DENIED,
        NOT_REQUESTED
    }
    
    /**
     * Data class to hold permission information
     */
    data class PermissionInfo(
        val permission: String,
        val category: PermissionCategory,
        val description: String,
        val requiredForFeature: String
    )
    
    /**
     * Callback interface for permission results
     */
    interface PermissionCallback {
        fun onPermissionsGranted(grantedPermissions: List<String>)
        fun onPermissionsDenied(deniedPermissions: List<String>)
        fun onPermissionsPermanentlyDenied(permanentlyDeniedPermissions: List<String>)
        fun onAllEssentialPermissionsGranted()
        fun onEssentialPermissionsDenied()
    }
    
    // Permission definitions with categories and descriptions
    private val permissionMap = mapOf(
        // Essential permissions - app cannot function without these
        Manifest.permission.CAMERA to PermissionInfo(
            Manifest.permission.CAMERA,
            PermissionCategory.ESSENTIAL,
            "Camera access is required for video recording",
            "Video Recording"
        ),
        Manifest.permission.RECORD_AUDIO to PermissionInfo(
            Manifest.permission.RECORD_AUDIO,
            PermissionCategory.ESSENTIAL,
            "Microphone access is required for audio recording",
            "Audio Recording"
        ),
        
        // Core permissions - main features require these
        Manifest.permission.BLUETOOTH_CONNECT to PermissionInfo(
            Manifest.permission.BLUETOOTH_CONNECT,
            PermissionCategory.CORE,
            "Bluetooth access is required for GSR sensor connection",
            "GSR Sensor"
        ),
        Manifest.permission.BLUETOOTH_SCAN to PermissionInfo(
            Manifest.permission.BLUETOOTH_SCAN,
            PermissionCategory.CORE,
            "Bluetooth scanning is required to find GSR sensors",
            "GSR Sensor Discovery"
        ),
        Manifest.permission.ACCESS_FINE_LOCATION to PermissionInfo(
            Manifest.permission.ACCESS_FINE_LOCATION,
            PermissionCategory.CORE,
            "Location access is required for Bluetooth device scanning",
            "Bluetooth Scanning"
        ),
        Manifest.permission.ACCESS_COARSE_LOCATION to PermissionInfo(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            PermissionCategory.CORE,
            "Location access is required for Bluetooth device scanning",
            "Bluetooth Scanning"
        ),
        
        // Storage permissions (API level dependent)
        Manifest.permission.WRITE_EXTERNAL_STORAGE to PermissionInfo(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            PermissionCategory.CORE,
            "Storage access is required to save recordings",
            "File Storage"
        ),
        Manifest.permission.READ_EXTERNAL_STORAGE to PermissionInfo(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            PermissionCategory.CORE,
            "Storage access is required to read recordings",
            "File Access"
        ),
        
        // Optional permissions for enhanced functionality
        Manifest.permission.FOREGROUND_SERVICE_CAMERA to PermissionInfo(
            Manifest.permission.FOREGROUND_SERVICE_CAMERA,
            PermissionCategory.OPTIONAL,
            "Foreground service access for background camera recording",
            "Background Recording"
        ),
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE to PermissionInfo(
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            PermissionCategory.OPTIONAL,
            "Foreground service access for background audio recording",
            "Background Recording"
        ),
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE to PermissionInfo(
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
            PermissionCategory.OPTIONAL,
            "Foreground service access for background sensor connection",
            "Background Sensor Access"
        )
    )
    
    // Add API 33+ media permissions
    private val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        mapOf(
            Manifest.permission.READ_MEDIA_VIDEO to PermissionInfo(
                Manifest.permission.READ_MEDIA_VIDEO,
                PermissionCategory.CORE,
                "Media access is required to read video files",
                "Video File Access"
            ),
            Manifest.permission.READ_MEDIA_AUDIO to PermissionInfo(
                Manifest.permission.READ_MEDIA_AUDIO,
                PermissionCategory.CORE,
                "Media access is required to read audio files",
                "Audio File Access"
            )
        )
    } else {
        emptyMap()
    }
    
    // Combined permission map
    private val allPermissions = permissionMap + mediaPermissions
    
    // Permission status tracking
    private val permissionStatus = mutableMapOf<String, PermissionStatus>()
    
    // Callback for permission results
    private var permissionCallback: PermissionCallback? = null
    
    // Permission launcher
    private val permissionLauncher: ActivityResultLauncher<Array<String>> = 
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            handlePermissionResults(permissions)
        }
    
    /**
     * Set callback for permission results
     */
    fun setPermissionCallback(callback: PermissionCallback) {
        this.permissionCallback = callback
    }
    
    /**
     * Request all permissions at app startup
     */
    fun requestAllPermissions() {
        Timber.d("Requesting all permissions at startup")
        
        val permissionsToRequest = getPermissionsToRequest()
        
        if (permissionsToRequest.isEmpty()) {
            Timber.d("All permissions already granted")
            permissionCallback?.onAllEssentialPermissionsGranted()
            return
        }
        
        // Show explanation dialog before requesting permissions
        showPermissionExplanationDialog(permissionsToRequest) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    /**
     * Request specific permissions when needed during runtime
     */
    fun requestPermissionsForFeature(feature: String, permissions: List<String>) {
        Timber.d("Requesting permissions for feature: $feature")
        
        val missingPermissions = permissions.filter { permission ->
            !isPermissionGranted(permission)
        }
        
        if (missingPermissions.isEmpty()) {
            Timber.d("All permissions for $feature already granted")
            permissionCallback?.onPermissionsGranted(permissions)
            return
        }
        
        // Show feature-specific explanation
        showFeaturePermissionDialog(feature, missingPermissions) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    /**
     * Check if a specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if all essential permissions are granted
     */
    fun areEssentialPermissionsGranted(): Boolean {
        return getEssentialPermissions().all { isPermissionGranted(it) }
    }
    
    /**
     * Check if all core permissions are granted
     */
    fun areCorePermissionsGranted(): Boolean {
        return getCorePermissions().all { isPermissionGranted(it) }
    }
    
    /**
     * Get permissions that need to be requested
     */
    private fun getPermissionsToRequest(): List<String> {
        return allPermissions.keys.filter { permission ->
            !isPermissionGranted(permission) && shouldRequestPermission(permission)
        }
    }
    
    /**
     * Check if we should request a specific permission based on API level
     */
    private fun shouldRequestPermission(permission: String): Boolean {
        return when (permission) {
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            Manifest.permission.READ_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
            Manifest.permission.READ_MEDIA_VIDEO, 
            Manifest.permission.READ_MEDIA_AUDIO -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            Manifest.permission.FOREGROUND_SERVICE_CAMERA,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            else -> true
        }
    }
    
    /**
     * Get essential permissions
     */
    private fun getEssentialPermissions(): List<String> {
        return allPermissions.filter { it.value.category == PermissionCategory.ESSENTIAL }.keys.toList()
    }
    
    /**
     * Get core permissions
     */
    private fun getCorePermissions(): List<String> {
        return allPermissions.filter { it.value.category == PermissionCategory.CORE }.keys.toList()
    }
    
    /**
     * Handle permission request results
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val grantedPermissions = mutableListOf<String>()
        val deniedPermissions = mutableListOf<String>()
        val permanentlyDeniedPermissions = mutableListOf<String>()
        
        permissions.forEach { (permission, isGranted) ->
            if (isGranted) {
                grantedPermissions.add(permission)
                permissionStatus[permission] = PermissionStatus.GRANTED
            } else {
                // Check if permission is permanently denied
                if (!activity.shouldShowRequestPermissionRationale(permission)) {
                    permanentlyDeniedPermissions.add(permission)
                    permissionStatus[permission] = PermissionStatus.PERMANENTLY_DENIED
                } else {
                    deniedPermissions.add(permission)
                    permissionStatus[permission] = PermissionStatus.DENIED
                }
            }
        }
        
        Timber.d("Permission results - Granted: ${grantedPermissions.size}, Denied: ${deniedPermissions.size}, Permanently denied: ${permanentlyDeniedPermissions.size}")
        
        // Notify callback
        if (grantedPermissions.isNotEmpty()) {
            permissionCallback?.onPermissionsGranted(grantedPermissions)
        }
        if (deniedPermissions.isNotEmpty()) {
            permissionCallback?.onPermissionsDenied(deniedPermissions)
        }
        if (permanentlyDeniedPermissions.isNotEmpty()) {
            permissionCallback?.onPermissionsPermanentlyDenied(permanentlyDeniedPermissions)
        }
        
        // Check if essential permissions are granted
        if (areEssentialPermissionsGranted()) {
            permissionCallback?.onAllEssentialPermissionsGranted()
        } else {
            val deniedEssential = getEssentialPermissions().filter { !isPermissionGranted(it) }
            if (deniedEssential.isNotEmpty()) {
                permissionCallback?.onEssentialPermissionsDenied()
                showEssentialPermissionsDeniedDialog(deniedEssential)
            }
        }
    }
    
    /**
     * Show explanation dialog before requesting permissions
     */
    private fun showPermissionExplanationDialog(permissions: List<String>, onAccept: () -> Unit) {
        val permissionDescriptions = permissions.mapNotNull { permission ->
            allPermissions[permission]?.let { info ->
                "• ${info.requiredForFeature}: ${info.description}"
            }
        }.joinToString("\n")
        
        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage("This app requires the following permissions to function properly:\n\n$permissionDescriptions\n\nWould you like to grant these permissions?")
            .setPositiveButton("Grant Permissions") { _, _ -> onAccept() }
            .setNegativeButton("Not Now") { _, _ -> 
                permissionCallback?.onEssentialPermissionsDenied()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Show feature-specific permission dialog
     */
    private fun showFeaturePermissionDialog(feature: String, permissions: List<String>, onAccept: () -> Unit) {
        val permissionDescriptions = permissions.mapNotNull { permission ->
            allPermissions[permission]?.description
        }.joinToString("\n• ", "• ")
        
        AlertDialog.Builder(activity)
            .setTitle("Permission Required for $feature")
            .setMessage("To use $feature, the following permissions are needed:\n\n$permissionDescriptions")
            .setPositiveButton("Grant") { _, _ -> onAccept() }
            .setNegativeButton("Cancel") { _, _ -> 
                permissionCallback?.onPermissionsDenied(permissions)
            }
            .show()
    }
    
    /**
     * Show dialog when essential permissions are denied
     */
    private fun showEssentialPermissionsDeniedDialog(deniedPermissions: List<String>) {
        val permissionNames = deniedPermissions.mapNotNull { permission ->
            allPermissions[permission]?.requiredForFeature
        }.joinToString(", ")
        
        AlertDialog.Builder(activity)
            .setTitle("Essential Permissions Required")
            .setMessage("The app cannot function without these permissions: $permissionNames\n\nYou can continue with limited functionality or grant permissions in Settings.")
            .setPositiveButton("Continue with Limited Features") { _, _ ->
                // Continue with graceful degradation
                Timber.d("User chose to continue with limited functionality")
            }
            .setNegativeButton("Open Settings") { _, _ ->
                // Open app settings
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Open app settings for manual permission granting
     */
    private fun openAppSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open app settings")
        }
    }
    
    /**
     * Get permission status for a specific permission
     */
    fun getPermissionStatus(permission: String): PermissionStatus {
        return permissionStatus[permission] ?: if (isPermissionGranted(permission)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.NOT_REQUESTED
        }
    }
    
    /**
     * Get summary of all permission statuses
     */
    fun getPermissionSummary(): Map<String, PermissionStatus> {
        val summary = mutableMapOf<String, PermissionStatus>()
        allPermissions.keys.forEach { permission ->
            summary[permission] = getPermissionStatus(permission)
        }
        return summary
    }
    
    /**
     * Check if app can function with current permissions
     */
    fun canAppFunction(): Boolean {
        return areEssentialPermissionsGranted()
    }
    
    /**
     * Get missing permissions by category
     */
    fun getMissingPermissionsByCategory(): Map<PermissionCategory, List<String>> {
        val missing = mutableMapOf<PermissionCategory, MutableList<String>>()
        
        allPermissions.forEach { (permission, info) ->
            if (!isPermissionGranted(permission) && shouldRequestPermission(permission)) {
                missing.getOrPut(info.category) { mutableListOf() }.add(permission)
            }
        }
        
        return missing
    }
}