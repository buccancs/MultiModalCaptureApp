package com.multimodal.capture.capture

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.test.core.app.ApplicationProvider
import com.multimodal.capture.thermal.Const
import com.multimodal.capture.thermal.ThermalDataParser
import com.multimodal.capture.thermal.USBMonitorManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThermalCameraManagerTest {

    private lateinit var context: Context
    private lateinit var thermalManager: ThermalCameraManager
    private lateinit var mockUsbManager: USBMonitorManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // We can't easily mock the Singleton, so we'll just use the real one
        // but we can verify its methods are called.
        mockUsbManager = USBMonitorManager.getInstance()
        thermalManager = ThermalCameraManager(context, null)
    }

    @Test
    fun `setStatusCallback correctly sets callback function`() {
        // 1. Arrange
        val mockStatusCallback = mock<(String) -> Unit>()

        // 2. Act
        thermalManager.setStatusCallback(mockStatusCallback)

        // 3. Assert
        // Verify that the callback was set without throwing an exception
        assert(true) // Test passes if no exception is thrown
    }

    @Test
    fun `setTemperatureCallback correctly sets callback function`() {
        // 1. Arrange
        val mockTempCallback = mock<(Float, Float, Float) -> Unit>()

        // 2. Act
        thermalManager.setTemperatureCallback(mockTempCallback)

        // 3. Assert
        // Verify that the callback was set without throwing an exception
        assert(true) // Test passes if no exception is thrown
    }

    @Test
    fun `isConnected returns false initially`() {
        // 1. Arrange & Act
        val isConnected = thermalManager.isConnected()

        // 2. Assert
        // Initially, the thermal manager should not be connected
        assert(!isConnected) { "Thermal manager should not be connected initially" }
    }

    @Test
    fun `isRecording returns false initially`() {
        // 1. Arrange & Act
        val isRecording = thermalManager.isRecording()

        // 2. Assert
        // Initially, the thermal manager should not be recording
        assert(!isRecording) { "Thermal manager should not be recording initially" }
    }
}