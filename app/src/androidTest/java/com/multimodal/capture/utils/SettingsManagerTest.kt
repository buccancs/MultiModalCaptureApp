package com.multimodal.capture.utils

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multimodal.capture.data.CameraConfig
import com.multimodal.capture.data.ShimmerConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsManagerTest {

    private lateinit var settingsManager: SettingsManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Get a fresh instance for each test
        settingsManager = SettingsManager.getInstance(context)
    }

    @Test
    fun saveAndLoadShimmerConfig_returnsSameValues() {
        // 1. Arrange
        val originalConfig = ShimmerConfig(
            sampleRate = 256,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "TestShimmer"
        )

        // 2. Act
        settingsManager.saveShimmerConfig(originalConfig)
        val loadedConfig = settingsManager.loadShimmerConfig()

        // 3. Assert
        assertEquals(originalConfig.sampleRate, loadedConfig.sampleRate)
        assertEquals(originalConfig.deviceAddress, loadedConfig.deviceAddress)
        assertEquals(originalConfig.deviceName, loadedConfig.deviceName)
    }

    @Test
    fun saveAndLoadCameraConfig_returnsSameValues() {
        // 1. Arrange
        val originalConfig = CameraConfig(
            cameraId = "1",
            resolution = "1280x720",
            fps = 60,
            autoFocus = false
        )

        // 2. Act
        settingsManager.saveCameraConfig(originalConfig)
        val loadedConfig = settingsManager.loadCameraConfig()

        // 3. Assert
        assertEquals(originalConfig.cameraId, loadedConfig.cameraId)
        assertEquals(originalConfig.resolution, loadedConfig.resolution)
        assertEquals(originalConfig.fps, loadedConfig.fps)
        assertEquals(originalConfig.autoFocus, loadedConfig.autoFocus)
    }
}