package com.multimodal.capture.thermal

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data class to hold the parsed temperature information from the camera's firmware.
 */
data class ParsedTemperatureData(
    val maxTemp: Float,
    val minTemp: Float,
    val avgTemp: Float,
    val fpaTemp: Float // Focal Plane Array temperature, crucial for calibration
)

/**
 * A utility object to parse the dedicated temperature data block from the thermal camera.
 */
object ThermalDataParser {

    // Constants based on SDK documentation for the temperature data block structure.
    // Assuming a 16-byte structure: 4 bytes per float (max, min, avg, fpa).
    private const val TEMP_DATA_BLOCK_SIZE = 16
    private const val OFFSET_MAX_TEMP = 0
    private const val OFFSET_MIN_TEMP = 4
    private const val OFFSET_AVG_TEMP = 8
    private const val OFFSET_FPA_TEMP = 12

    /**
     * Parses the raw byte array from the temperature data half of the frame.
     *
     * @param temperatureData The byte array containing firmware-processed temperature data.
     * @return A ParsedTemperatureData object, or null if the data is invalid.
     */
    fun parse(temperatureData: ByteArray?): ParsedTemperatureData? {
        if (temperatureData == null || temperatureData.size < TEMP_DATA_BLOCK_SIZE) {
            Timber.w("Invalid temperature data block size: ${temperatureData?.size ?: 0}")
            return null
        }

        return try {
            // The SDK specifies Little Endian byte order for float values.
            val buffer = ByteBuffer.wrap(temperatureData).order(ByteOrder.LITTLE_ENDIAN)

            val max = buffer.getFloat(OFFSET_MAX_TEMP)
            val min = buffer.getFloat(OFFSET_MIN_TEMP)
            val avg = buffer.getFloat(OFFSET_AVG_TEMP)
            val fpa = buffer.getFloat(OFFSET_FPA_TEMP)

            ParsedTemperatureData(
                maxTemp = max,
                minTemp = min,
                avgTemp = avg,
                fpaTemp = fpa
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse temperature data block.")
            null
        }
    }
}