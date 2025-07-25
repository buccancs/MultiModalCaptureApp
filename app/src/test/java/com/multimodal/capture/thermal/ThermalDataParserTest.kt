package com.multimodal.capture.thermal

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThermalDataParserTest {

    @Test
    fun `parse valid temperature data block successfully`() {
        // 1. Arrange: Create a sample byte array representing the temp data.
        // Values: max=120.5f, min=25.2f, avg=75.8f, fpa=35.1f
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(120.5f)
        buffer.putFloat(25.2f)
        buffer.putFloat(75.8f)
        buffer.putFloat(35.1f)
        val validData = buffer.array()

        // 2. Act: Parse the data.
        val result = ThermalDataParser.parse(validData)

        // 3. Assert: Verify the results.
        assertNotNull(result)
        assertEquals(120.5f, result!!.maxTemp, 0.01f)
        assertEquals(25.2f, result.minTemp, 0.01f)
        assertEquals(75.8f, result.avgTemp, 0.01f)
        assertEquals(35.1f, result.fpaTemp, 0.01f)
    }

    @Test
    fun `parse returns null for insufficient data`() {
        // Arrange: Create a byte array that is too short.
        val invalidData = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        // Act
        val result = ThermalDataParser.parse(invalidData)

        // Assert
        assertNull(result)
    }

    @Test
    fun `parse returns null for null data`() {
        // Act
        val result = ThermalDataParser.parse(null)

        // Assert
        assertNull(result)
    }

    @Test
    fun `parse returns null for empty data`() {
        // Arrange
        val emptyData = byteArrayOf()

        // Act
        val result = ThermalDataParser.parse(emptyData)

        // Assert
        assertNull(result)
    }

    @Test
    fun `parse handles negative temperature values correctly`() {
        // Arrange: Create data with negative temperatures
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(10.5f)   // max
        buffer.putFloat(-15.2f)  // min (negative)
        buffer.putFloat(-2.3f)   // avg (negative)
        buffer.putFloat(20.1f)   // fpa
        val negativeData = buffer.array()

        // Act
        val result = ThermalDataParser.parse(negativeData)

        // Assert
        assertNotNull(result)
        assertEquals(10.5f, result!!.maxTemp, 0.01f)
        assertEquals(-15.2f, result.minTemp, 0.01f)
        assertEquals(-2.3f, result.avgTemp, 0.01f)
        assertEquals(20.1f, result.fpaTemp, 0.01f)
    }

    @Test
    fun `parse handles extreme temperature values correctly`() {
        // Arrange: Create data with extreme temperature values
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(999.9f)    // max (very high)
        buffer.putFloat(-273.15f)  // min (absolute zero)
        buffer.putFloat(0.0f)      // avg (zero)
        buffer.putFloat(50.0f)     // fpa
        val extremeData = buffer.array()

        // Act
        val result = ThermalDataParser.parse(extremeData)

        // Assert
        assertNotNull(result)
        assertEquals(999.9f, result!!.maxTemp, 0.01f)
        assertEquals(-273.15f, result.minTemp, 0.01f)
        assertEquals(0.0f, result.avgTemp, 0.01f)
        assertEquals(50.0f, result.fpaTemp, 0.01f)
    }

    @Test
    fun `parse handles exactly minimum required data size`() {
        // Arrange: Create exactly 16 bytes of data
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(30.0f)
        buffer.putFloat(20.0f)
        buffer.putFloat(25.0f)
        buffer.putFloat(35.0f)
        val minSizeData = buffer.array()

        // Act
        val result = ThermalDataParser.parse(minSizeData)

        // Assert
        assertNotNull(result)
        assertEquals(30.0f, result!!.maxTemp, 0.01f)
        assertEquals(20.0f, result.minTemp, 0.01f)
        assertEquals(25.0f, result.avgTemp, 0.01f)
        assertEquals(35.0f, result.fpaTemp, 0.01f)
    }

    @Test
    fun `parse handles data larger than minimum required size`() {
        // Arrange: Create data larger than 16 bytes (should still work)
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(40.0f)
        buffer.putFloat(30.0f)
        buffer.putFloat(35.0f)
        buffer.putFloat(45.0f)
        // Add extra bytes that should be ignored
        buffer.putFloat(999.0f)
        buffer.putFloat(888.0f)
        val largerData = buffer.array()

        // Act
        val result = ThermalDataParser.parse(largerData)

        // Assert
        assertNotNull(result)
        assertEquals(40.0f, result!!.maxTemp, 0.01f)
        assertEquals(30.0f, result.minTemp, 0.01f)
        assertEquals(35.0f, result.avgTemp, 0.01f)
        assertEquals(45.0f, result.fpaTemp, 0.01f)
    }
}