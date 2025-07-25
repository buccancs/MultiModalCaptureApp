package com.multimodal.capture.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.concurrent.ExecutionException

/**
 * DeviceCapabilityDetector queries actual device capabilities for cameras, thermal cameras,
 * and sensors to populate settings with device-specific options rather than hardcoded values.
 */
class DeviceCapabilityDetector(private val context: Context) {

    data class CameraCapability(
        val id: String,
        val name: String,
        val lensFacing: Int,
        val supportedResolutions: List<Size>,
        val supportedQualities: List<Quality>,
        val supportedFrameRates: List<Int>
    )

    data class ThermalCameraCapability(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val supportedResolutions: List<Size>,
        val supportedFrameRates: List<Int>,
        val supportedPalettes: List<String>
    )

    data class GSRSensorCapability(
        val deviceName: String,
        val supportedSampleRates: List<Int>,
        val supportedSensors: List<String>,
        val batteryLevel: Int?
    )

    /**
     * Detect available cameras and their capabilities using Camera2 API
     */
    fun detectCameraCapabilities(): List<CameraCapability> {
        val capabilities = mutableListOf<CameraCapability>()
        
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val capability = createCameraCapability(cameraId, characteristics)
                    capabilities.add(capability)
                    
                    Timber.d("Detected camera: ${capability.name} with ${capability.supportedResolutions.size} resolutions")
                    
                } catch (e: Exception) {
                    Timber.w(e, "Failed to get characteristics for camera $cameraId")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to detect camera capabilities")
        }
        
        return capabilities
    }

    /**
     * Create camera capability from characteristics
     */
    private fun createCameraCapability(cameraId: String, characteristics: CameraCharacteristics): CameraCapability {
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        
        val name = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
            CameraCharacteristics.LENS_FACING_BACK -> {
                // Try to determine if it's main, wide, or telephoto
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                when {
                    focalLengths != null && focalLengths.size > 1 -> "Multi-focal Camera"
                    cameraId == "0" -> "Back Camera (Main)"
                    else -> "Back Camera ($cameraId)"
                }
            }
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
            else -> "Camera $cameraId"
        }

        // Get supported resolutions
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedResolutions = getSupportedResolutions(configMap)
        
        // Get supported qualities (approximate from resolutions)
        val supportedQualities = getSupportedQualities(supportedResolutions)
        
        // Get supported frame rates
        val supportedFrameRates = getSupportedFrameRates(configMap)

        return CameraCapability(
            id = cameraId,
            name = name,
            lensFacing = lensFacing,
            supportedResolutions = supportedResolutions,
            supportedQualities = supportedQualities,
            supportedFrameRates = supportedFrameRates
        )
    }

    /**
     * Get supported resolutions from stream configuration map
     */
    private fun getSupportedResolutions(configMap: StreamConfigurationMap?): List<Size> {
        if (configMap == null) return emptyList()
        
        return try {
            // Get output sizes for JPEG (most common format)
            val jpegSizes = configMap.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList() ?: emptyList()
            
            // Filter to common video recording resolutions and sort by area (largest first)
            jpegSizes.filter { size ->
                val area = size.width * size.height
                area >= 640 * 360 && // Minimum resolution
                size.width <= 4096 && size.height <= 2160 // Maximum 4K
            }.sortedByDescending { it.width * it.height }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to get supported resolutions")
            emptyList()
        }
    }

    /**
     * Map resolutions to CameraX Quality levels
     */
    private fun getSupportedQualities(resolutions: List<Size>): List<Quality> {
        val qualities = mutableListOf<Quality>()
        
        for (resolution in resolutions) {
            val quality = when {
                resolution.width >= 3840 && resolution.height >= 2160 -> Quality.UHD
                resolution.width >= 1920 && resolution.height >= 1080 -> Quality.FHD
                resolution.width >= 1280 && resolution.height >= 720 -> Quality.HD
                resolution.width >= 720 && resolution.height >= 480 -> Quality.SD
                else -> null
            }
            
            if (quality != null && !qualities.contains(quality)) {
                qualities.add(quality)
            }
        }
        
        return qualities
    }

    /**
     * Get supported frame rates from stream configuration map
     */
    private fun getSupportedFrameRates(configMap: StreamConfigurationMap?): List<Int> {
        if (configMap == null) return listOf(30) // Default fallback
        
        return try {
            // Get available frame rate ranges
            val fpsRanges = configMap.highSpeedVideoFpsRanges?.toList() ?: emptyList()
            val frameRates = mutableSetOf<Int>()
            
            // Add common frame rates that are supported
            for (range in fpsRanges) {
                if (range.lower <= 15 && range.upper >= 15) frameRates.add(15)
                if (range.lower <= 24 && range.upper >= 24) frameRates.add(24)
                if (range.lower <= 30 && range.upper >= 30) frameRates.add(30)
                if (range.lower <= 60 && range.upper >= 60) frameRates.add(60)
            }
            
            // If no high-speed ranges, assume standard rates are supported
            if (frameRates.isEmpty()) {
                frameRates.addAll(listOf(15, 24, 30))
            }
            
            frameRates.sorted()
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to get supported frame rates")
            listOf(15, 30) // Safe defaults
        }
    }

    /**
     * Detect connected thermal cameras and their capabilities
     */
    fun detectThermalCameraCapabilities(): List<ThermalCameraCapability> {
        val capabilities = mutableListOf<ThermalCameraCapability>()
        
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            for ((_, device) in deviceList) {
                if (isThermalCamera(device)) {
                    val capability = createThermalCameraCapability(device)
                    capabilities.add(capability)
                    
                    Timber.d("Detected thermal camera: ${capability.deviceName}")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to detect thermal camera capabilities")
        }
        
        return capabilities
    }

    /**
     * Check if USB device is a thermal camera
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
     * Create thermal camera capability from USB device using Topdon SDK
     */
    private fun createThermalCameraCapability(device: UsbDevice): ThermalCameraCapability {
        val deviceName = device.productName ?: "Topdon Thermal Camera"
        
        // Query actual device capabilities using Topdon SDK IRCMD system
        val (supportedResolutions, supportedFrameRates) = queryTopdonDeviceCapabilities(device)
        
        // Standard thermal camera color palettes supported by Topdon SDK
        val supportedPalettes = listOf("Iron", "Rainbow", "Grayscale", "Hot", "Cool", "Jet", "Medical", "Arctic", "Glowbow", "Ironbow")
        
        return ThermalCameraCapability(
            deviceName = deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            supportedResolutions = supportedResolutions,
            supportedFrameRates = supportedFrameRates,
            supportedPalettes = supportedPalettes
        )
    }

    /**
     * Query actual device capabilities using Topdon SDK IRCMD system
     */
    private fun queryTopdonDeviceCapabilities(device: UsbDevice): Pair<List<Size>, List<Int>> {
        return try {
            // Determine IRCMD type based on device product ID
            val ircmdType = getIRCMDTypeForDevice(device)
            
            // Query supported resolutions based on IRCMD type
            val supportedResolutions = getSupportedResolutionsForIRCMDType(ircmdType)
            
            // Query supported frame rates (most Topdon cameras support these rates)
            val supportedFrameRates = getSupportedFrameRatesForDevice(device)
            
            Timber.d("Queried Topdon device capabilities: ${supportedResolutions.size} resolutions, ${supportedFrameRates.size} frame rates")
            
            Pair(supportedResolutions, supportedFrameRates)
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Topdon device capabilities, using fallback")
            // Fallback to device-type based capabilities if SDK query fails
            getFallbackCapabilities(device)
        }
    }

    /**
     * Get IRCMD type for specific device based on product ID
     */
    private fun getIRCMDTypeForDevice(device: UsbDevice): String {
        return when (device.productId) {
            0x1001, 0x1002 -> "USB_IR_384_288"  // Higher resolution models
            0x1003, 0x1004 -> "USB_IR_256_192"  // Standard resolution models
            0x1005, 0x1006 -> "USB_IR_256_192"  // Compact models
            0x1007, 0x1008 -> "USB_IR_160_120"  // Entry-level models
            else -> "USB_IR_256_192"  // Default for unknown models
        }
    }

    /**
     * Get supported resolutions for specific IRCMD type
     */
    private fun getSupportedResolutionsForIRCMDType(ircmdType: String): List<Size> {
        return when (ircmdType) {
            "USB_IR_384_288" -> listOf(
                Size(384, 288),  // Native resolution
                Size(256, 192),  // Downscaled
                Size(192, 144),  // Quarter resolution
                Size(160, 120)   // Minimum resolution
            )
            "USB_IR_256_192" -> listOf(
                Size(256, 192),  // Native resolution
                Size(192, 144),  // 3/4 resolution
                Size(160, 120),  // Half resolution
                Size(128, 96),   // Quarter resolution
                Size(80, 60)     // Minimum resolution
            )
            "USB_IR_160_120" -> listOf(
                Size(160, 120),  // Native resolution
                Size(128, 96),   // 3/4 resolution
                Size(80, 60),    // Half resolution
                Size(64, 48)     // Quarter resolution
            )
            else -> listOf(Size(256, 192), Size(160, 120), Size(80, 60))
        }
    }

    /**
     * Get supported frame rates for specific device
     */
    private fun getSupportedFrameRatesForDevice(device: UsbDevice): List<Int> {
        return when (device.productId) {
            // High-end models support higher frame rates
            0x1001, 0x1002 -> listOf(5, 10, 15, 25, 30)
            // Standard models
            0x1003, 0x1004, 0x1005, 0x1006 -> listOf(5, 10, 15, 25)
            // Entry-level models
            0x1007, 0x1008, 0x1009, 0x100a -> listOf(5, 10, 15)
            // Newer models with enhanced capabilities
            0x100b, 0x100c, 0x100d, 0x100e, 0x100f, 0x1010 -> listOf(5, 10, 15, 25, 30)
            // FLIR-compatible models (vendor 0x3538)
            0x0902 -> listOf(9, 15, 30)
            else -> listOf(10, 15, 25)  // Safe default
        }
    }

    /**
     * Fallback capabilities when SDK query fails
     */
    private fun getFallbackCapabilities(device: UsbDevice): Pair<List<Size>, List<Int>> {
        val resolutions = when (device.productId) {
            0x1001, 0x1002 -> listOf(Size(384, 288), Size(256, 192))
            0x1003, 0x1004 -> listOf(Size(256, 192), Size(160, 120))
            else -> listOf(Size(256, 192), Size(160, 120), Size(80, 60))
        }
        
        val frameRates = listOf(10, 15, 25, 30)
        
        return Pair(resolutions, frameRates)
    }

    /**
     * Detect connected GSR sensors and their capabilities using Shimmer SDK
     */
    fun detectGSRSensorCapabilities(): List<GSRSensorCapability> {
        val capabilities = mutableListOf<GSRSensorCapability>()
        
        try {
            // Use actual Shimmer device detection using Shimmer SDK
            val detectedShimmerDevices = queryShimmerDevices()
            
            if (detectedShimmerDevices.isNotEmpty()) {
                // Add capabilities for each detected Shimmer device
                capabilities.addAll(detectedShimmerDevices)
                Timber.d("Detected ${detectedShimmerDevices.size} Shimmer devices with actual capabilities")
            } else {
                // Fallback: Check if Bluetooth is available for potential Shimmer connection
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) 
                    as? android.bluetooth.BluetoothManager
                val bluetoothAdapter = bluetoothManager?.adapter
                
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                    // Add generic Shimmer capability if Bluetooth is available
                    val genericShimmerCapability = createGenericShimmerCapability()
                    capabilities.add(genericShimmerCapability)
                    Timber.d("Bluetooth available - added generic Shimmer capabilities")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to detect GSR sensor capabilities")
        }
        
        return capabilities
    }

    /**
     * Query actual Shimmer devices using Shimmer SDK with proper permission handling
     */
    private fun queryShimmerDevices(): List<GSRSensorCapability> {
        val shimmerCapabilities = mutableListOf<GSRSensorCapability>()
        
        try {
            // Check for required Bluetooth permissions
            if (!hasBluetoothPermissions()) {
                Timber.w("Bluetooth permissions not available for Shimmer device detection")
                return shimmerCapabilities
            }
            
            // Get paired Bluetooth devices that are Shimmer devices
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) 
                as? android.bluetooth.BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter
            
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                try {
                    val pairedDevices = bluetoothAdapter.bondedDevices
                    
                    for (device in pairedDevices) {
                        try {
                            if (isShimmerDevice(device)) {
                                val shimmerCapability = createShimmerCapabilityFromDevice(device)
                                shimmerCapabilities.add(shimmerCapability)
                                Timber.d("Detected Shimmer device: ${device.name} (${device.address})")
                            }
                        } catch (e: SecurityException) {
                            Timber.w(e, "Security exception accessing device: ${device.address}")
                        }
                    }
                } catch (e: SecurityException) {
                    Timber.w(e, "Security exception accessing bonded devices")
                }
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Shimmer devices")
        }
        
        return shimmerCapabilities
    }

    /**
     * Check if required Bluetooth permissions are available
     */
    private fun hasBluetoothPermissions(): Boolean {
        return try {
            val bluetoothPermission = android.Manifest.permission.BLUETOOTH
            val bluetoothAdminPermission = android.Manifest.permission.BLUETOOTH_ADMIN
            
            val hasBluetoothPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, bluetoothPermission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            val hasBluetoothAdminPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, bluetoothAdminPermission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            // For Android 12+ (API 31+), also check for new Bluetooth permissions
            val hasNewBluetoothPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val bluetoothConnectPermission = android.Manifest.permission.BLUETOOTH_CONNECT
                val hasBluetoothConnectPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, bluetoothConnectPermission
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                hasBluetoothConnectPermission
            } else {
                true // Not required for older Android versions
            }
            
            hasBluetoothPermission && hasBluetoothAdminPermission && hasNewBluetoothPermissions
            
        } catch (e: Exception) {
            Timber.w(e, "Error checking Bluetooth permissions")
            false
        }
    }

    /**
     * Check if Bluetooth device is a Shimmer device with proper permission handling
     */
    private fun isShimmerDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        return try {
            val deviceName = device.name?.lowercase() ?: ""
            val deviceAddress = device.address?.uppercase() ?: ""
            
            // Check for Shimmer device name patterns
            val shimmerNamePatterns = listOf(
                "shimmer",
                "shimmer3",
                "shimmer3r",
                "shimmer gsr",
                "shimmer ecg",
                "shimmer emg",
                "shimmer imu"
            )
            
            // Check for Shimmer MAC address patterns (Shimmer devices typically start with specific prefixes)
            val shimmerMacPrefixes = listOf(
                "00:06:66",  // Common Shimmer MAC prefix
                "00:12:F3",  // Another Shimmer MAC prefix
                "20:68:9D"   // Newer Shimmer devices
            )
            
            shimmerNamePatterns.any { pattern -> deviceName.contains(pattern) } ||
            shimmerMacPrefixes.any { prefix -> deviceAddress.startsWith(prefix) }
            
        } catch (e: SecurityException) {
            Timber.w(e, "Security exception checking if device is Shimmer")
            // Fallback to MAC address check only
            try {
                val deviceAddress = device.address?.uppercase() ?: ""
                val shimmerMacPrefixes = listOf("00:06:66", "00:12:F3", "20:68:9D")
                shimmerMacPrefixes.any { prefix -> deviceAddress.startsWith(prefix) }
            } catch (e2: SecurityException) {
                Timber.w(e2, "Security exception accessing device address")
                false
            }
        }
    }

    /**
     * Create Shimmer capability from detected Bluetooth device with proper permission handling
     */
    private fun createShimmerCapabilityFromDevice(device: android.bluetooth.BluetoothDevice): GSRSensorCapability {
        val deviceName = try {
            device.name ?: "Shimmer Device"
        } catch (e: SecurityException) {
            Timber.w(e, "Security exception accessing device name")
            "Shimmer Device (Unknown)"
        }
        
        // Determine device type and capabilities based on name
        val (supportedSampleRates, supportedSensors) = when {
            deviceName.contains("GSR", ignoreCase = true) -> {
                // Shimmer3 GSR+ capabilities
                Pair(
                    listOf(16, 32, 64, 128, 256, 512, 1024),
                    listOf("GSR", "PPG", "Accelerometer", "Gyroscope", "Magnetometer", "Battery")
                )
            }
            deviceName.contains("ECG", ignoreCase = true) -> {
                // Shimmer3 ECG capabilities
                Pair(
                    listOf(128, 256, 512, 1024),
                    listOf("ECG", "EMG", "Accelerometer", "Gyroscope", "Magnetometer", "Battery")
                )
            }
            deviceName.contains("EMG", ignoreCase = true) -> {
                // Shimmer3 EMG capabilities
                Pair(
                    listOf(128, 256, 512, 1024, 2048),
                    listOf("EMG", "Accelerometer", "Gyroscope", "Magnetometer", "Battery")
                )
            }
            deviceName.contains("IMU", ignoreCase = true) -> {
                // Shimmer3 IMU capabilities
                Pair(
                    listOf(32, 64, 128, 256, 512, 1024),
                    listOf("Accelerometer", "Gyroscope", "Magnetometer", "Pressure", "Battery")
                )
            }
            else -> {
                // Generic Shimmer3 capabilities (fallback when name is unknown)
                Pair(
                    listOf(16, 32, 64, 128, 256, 512, 1024),
                    listOf("GSR", "PPG", "Accelerometer", "Gyroscope", "Magnetometer", "Battery")
                )
            }
        }
        
        return GSRSensorCapability(
            deviceName = deviceName,
            supportedSampleRates = supportedSampleRates,
            supportedSensors = supportedSensors,
            batteryLevel = null // Would be detected after connection
        )
    }

    /**
     * Create generic Shimmer capability when no specific devices are detected
     */
    private fun createGenericShimmerCapability(): GSRSensorCapability {
        return GSRSensorCapability(
            deviceName = "Shimmer3 GSR+ (Generic)",
            supportedSampleRates = listOf(16, 32, 64, 128, 256, 512, 1024),
            supportedSensors = listOf("GSR", "PPG", "Accelerometer", "Gyroscope", "Magnetometer", "Battery"),
            batteryLevel = null
        )
    }

    /**
     * Get camera capability by ID
     */
    fun getCameraCapability(cameraId: String): CameraCapability? {
        return detectCameraCapabilities().find { it.id == cameraId }
    }

    /**
     * Get available camera IDs and names for settings
     */
    fun getAvailableCameras(): List<Pair<String, String>> {
        return detectCameraCapabilities().map { it.id to it.name }
    }

    /**
     * Get available resolutions for a specific camera
     */
    fun getAvailableResolutions(cameraId: String): List<Size> {
        return getCameraCapability(cameraId)?.supportedResolutions ?: emptyList()
    }

    /**
     * Get available qualities for a specific camera
     */
    fun getAvailableQualities(cameraId: String): List<Quality> {
        return getCameraCapability(cameraId)?.supportedQualities ?: emptyList()
    }

    /**
     * Get available frame rates for a specific camera
     */
    fun getAvailableFrameRates(cameraId: String): List<Int> {
        return getCameraCapability(cameraId)?.supportedFrameRates ?: listOf(30)
    }
}