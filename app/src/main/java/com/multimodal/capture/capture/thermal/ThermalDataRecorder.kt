package com.multimodal.capture.capture.thermal

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the recording of raw thermal data frames to a binary file.
 * This class encapsulates all file I/O operations for thermal data,
 * ensuring a clean separation of concerns from the camera manager.
 */
class ThermalDataRecorder {

    private var outputStream: FileOutputStream? = null
    val isRecording: Boolean
        get() = outputStream != null

    /**
     * Starts a new recording session.
     * @param outputFile The file to write the data to.
     * @return True if recording started successfully, false otherwise.
     */
    fun start(outputFile: File): Boolean {
        if (isRecording) {
            Timber.w("[ThermalRecorder] A recording is already in progress. Stop it first.")
            return false
        }
        return try {
            outputStream = FileOutputStream(outputFile)
            Timber.i("[ThermalRecorder] Started recording to ${outputFile.absolutePath}")
            true
        } catch (e: IOException) {
            Timber.e(e, "[ThermalRecorder] Failed to start recording.")
            false
        }
    }

    /**
     * Stops the current recording session and closes the file.
     */
    fun stop() {
        if (!isRecording) return
        try {
            outputStream?.flush()
            outputStream?.close()
            Timber.i("[ThermalRecorder] Stopped recording.")
        } catch (e: IOException) {
            Timber.e(e, "[ThermalRecorder] Error while stopping recording.")
        } finally {
            outputStream = null
        }
    }

    /**
     * Writes a single frame to the recording file with a timestamp and size header.
     * Format: [8-byte timestamp (long)] [4-byte frame size (int)] [frame data (byte array)]
     * @param frame The raw byte array of the frame to write.
     */
    fun writeFrame(frame: ByteArray) {
        val stream = outputStream ?: return // Not recording

        try {
            val timestamp = System.currentTimeMillis()
            val frameSize = frame.size

            // Use ByteBuffer for robust, endian-safe header writing.
            val headerBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            headerBuffer.putLong(timestamp)
            headerBuffer.putInt(frameSize)

            // Write header and frame data
            stream.write(headerBuffer.array())
            stream.write(frame)

        } catch (e: IOException) {
            Timber.e(e, "[ThermalRecorder] Failed to write thermal frame to file.")
            // Consider stopping the recording on write failure to prevent further errors.
            stop()
        }
    }
}