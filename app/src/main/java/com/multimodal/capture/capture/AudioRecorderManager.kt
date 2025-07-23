package com.multimodal.capture.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.multimodal.capture.R
import com.multimodal.capture.utils.TimestampManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * AudioRecorderManager handles synchronized audio recording via the phone's microphone.
 * Records at 44.1 kHz stereo and saves to WAV format as specified in requirements.
 */
class AudioRecorderManager(
    private val context: Context,
    private val networkManager: com.multimodal.capture.network.NetworkManager? = null
) {
    
    private val timestampManager = TimestampManager()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Audio recording parameters
    private val sampleRate = 44100 // 44.1 kHz as specified
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioSource = MediaRecorder.AudioSource.MIC
    
    // Recording components
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputStream: FileOutputStream? = null
    
    // Recording state
    private val isRecording = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // Buffer management
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val recordingBuffer = ByteArray(bufferSize)
    
    // WAV file parameters
    private var totalAudioBytes = 0L
    private var wavFile: File? = null
    
    // Callbacks
    private var statusCallback: ((String) -> Unit)? = null
    
    init {
        Timber.d("AudioRecorderManager initialized")
        initializeAudioRecord()
    }
    
    /**
     * Initialize AudioRecord for recording
     */
    private fun initializeAudioRecord() {
        try {
            // Check for RECORD_AUDIO permission
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                updateStatus("Audio permission required")
                isInitialized.set(false)
                return
            }
            
            // Check if buffer size is valid
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                throw IllegalStateException("Invalid buffer size for audio recording")
            }
            
            // Create AudioRecord instance
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2 // Double buffer for safety
            )
            
            // Check if AudioRecord was created successfully
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord not initialized properly")
            }
            
            isInitialized.set(true)
            updateStatus("Audio recorder ready")
            
            Timber.d("AudioRecord initialized: sampleRate=$sampleRate, bufferSize=$bufferSize")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AudioRecord")
            updateStatus("Audio Error: ${e.message}")
            isInitialized.set(false)
        }
    }
    
    /**
     * Start audio recording
     */
    fun startRecording(sessionId: String, startTimestamp: Long) {
        if (!isInitialized.get()) {
            Timber.w("Cannot start recording - AudioRecord not initialized")
            return
        }
        
        if (isRecording.get()) {
            Timber.w("Audio recording already in progress")
            return
        }
        
        try {
            currentSessionId = sessionId
            timestampManager.setSessionStartTime(startTimestamp)
            
            // Get PC-corrected timestamp and sync quality for better alignment
            val localStartTime = timestampManager.getCurrentTimestamp()
            val pcCorrectedStartTime = networkManager?.getSynchronizedTimestamp() ?: localStartTime
            val syncQuality = networkManager?.getSyncQuality() ?: 0
            val clockOffset = networkManager?.getClockOffset() ?: 0L
            
            // Create output file
            val outputDir = File(context.getExternalFilesDir(null), "recordings")
            outputDir.mkdirs()
            
            wavFile = File(outputDir, "${sessionId}_audio.wav")
            val metadataFile = File(outputDir, "${sessionId}_audio_metadata.json")
            outputStream = FileOutputStream(wavFile!!)
            
            // Create timestamp metadata for post-processing analysis
            createTimestampMetadata(metadataFile, sessionId, localStartTime, pcCorrectedStartTime, syncQuality, clockOffset)
            
            // Write WAV header (will be updated when recording stops)
            writeWavHeader(outputStream!!, 0)
            
            // Start AudioRecord
            audioRecord?.startRecording()
            
            // Start recording job
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudioData()
            }
            
            isRecording.set(true)
            totalAudioBytes = 0L
            updateStatus(context.getString(R.string.status_audio_recording))
            
            Timber.d("Audio recording started: ${wavFile!!.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio recording")
            updateStatus("Recording Error: ${e.message}")
        }
    }
    
    /**
     * Stop audio recording
     */
    fun stopRecording() {
        if (!isRecording.get()) {
            Timber.w("No audio recording in progress")
            return
        }
        
        try {
            isRecording.set(false)
            
            // Stop AudioRecord
            audioRecord?.stop()
            
            // Cancel recording job
            recordingJob?.cancel()
            recordingJob = null
            
            // Update WAV header with correct file size
            outputStream?.let { stream ->
                updateWavHeader(stream, totalAudioBytes)
                stream.close()
            }
            outputStream = null
            
            updateStatus("Audio recorder ready")
            
            Timber.d("Audio recording stopped. Total bytes: $totalAudioBytes")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio recording")
        }
    }
    
    /**
     * Record audio data in background
     */
    private suspend fun recordAudioData() {
        while (isRecording.get()) {
            try {
                val audioRecord = this.audioRecord ?: break
                
                // Read audio data
                val bytesRead = audioRecord.read(recordingBuffer, 0, recordingBuffer.size)
                
                if (bytesRead > 0) {
                    // Get both local and PC-corrected timestamps for better synchronization
                    val localTimestamp = timestampManager.getCurrentTimestamp()
                    val pcCorrectedTimestamp = networkManager?.getSynchronizedTimestamp() ?: localTimestamp
                    val syncQuality = networkManager?.getSyncQuality() ?: 0
                    
                    // Write audio data to file
                    outputStream?.write(recordingBuffer, 0, bytesRead)
                    totalAudioBytes += bytesRead
                    
                    // Log enhanced timestamp information for synchronization
                    Timber.v("Audio chunk: ${bytesRead} bytes - Local: ${localTimestamp}ns, PC-corrected: ${pcCorrectedTimestamp}ns, Quality: ${syncQuality}")
                    
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Timber.e("AudioRecord invalid operation")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Timber.e("AudioRecord bad value")
                    break
                }
                
                // Small delay to prevent excessive CPU usage
                delay(1)
                
            } catch (e: Exception) {
                if (e is CancellationException) {
                    break
                } else {
                    Timber.e(e, "Error recording audio data")
                }
            }
        }
    }
    
    /**
     * Write WAV file header
     */
    private fun writeWavHeader(outputStream: FileOutputStream, audioDataSize: Long) {
        try {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt((36 + audioDataSize).toInt()) // File size - 8
            header.put("WAVE".toByteArray())
            
            // Format chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // Format chunk size
            header.putShort(1) // Audio format (PCM)
            header.putShort(2) // Number of channels (stereo)
            header.putInt(sampleRate) // Sample rate
            header.putInt(sampleRate * 2 * 2) // Byte rate (sample rate * channels * bytes per sample)
            header.putShort(4) // Block align (channels * bytes per sample)
            header.putShort(16) // Bits per sample
            
            // Data chunk
            header.put("data".toByteArray())
            header.putInt(audioDataSize.toInt()) // Data size
            
            outputStream.write(header.array())
            
        } catch (e: IOException) {
            Timber.e(e, "Failed to write WAV header")
        }
    }
    
    /**
     * Update WAV file header with correct file size
     */
    private fun updateWavHeader(outputStream: FileOutputStream, audioDataSize: Long) {
        try {
            outputStream.close()
            
            // Reopen file for header update
            val file = wavFile ?: return
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")
            
            // Update file size in RIFF header
            randomAccessFile.seek(4)
            randomAccessFile.writeInt(Integer.reverseBytes((36 + audioDataSize).toInt()))
            
            // Update data size in data chunk
            randomAccessFile.seek(40)
            randomAccessFile.writeInt(Integer.reverseBytes(audioDataSize.toInt()))
            
            randomAccessFile.close()
            
            Timber.d("WAV header updated with size: $audioDataSize bytes")
            
        } catch (e: IOException) {
            Timber.e(e, "Failed to update WAV header")
        }
    }
    
    /**
     * Get current audio level for UI feedback
     */
    fun getCurrentAudioLevel(): Int {
        return try {
            if (isRecording.get() && audioRecord != null) {
                // Simple amplitude calculation from recent buffer
                var sum = 0L
                for (i in recordingBuffer.indices step 2) {
                    val sample = (recordingBuffer[i].toInt() and 0xFF) or 
                                (recordingBuffer[i + 1].toInt() shl 8)
                    sum += kotlin.math.abs(sample)
                }
                (sum / (recordingBuffer.size / 2)).toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Check if audio recording is supported
     */
    fun isAudioRecordingSupported(): Boolean {
        return isInitialized.get()
    }
    
    /**
     * Set status callback
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }
    
    /**
     * Update status and notify callback
     */
    private fun updateStatus(status: String) {
        mainHandler.post {
            statusCallback?.invoke(status)
        }
    }
    
    /**
     * Check if audio is recording
     */
    fun isRecording(): Boolean {
        return isRecording.get()
    }
    
    /**
     * Get recording parameters info
     */
    fun getRecordingInfo(): Map<String, Any> {
        return mapOf(
            "sampleRate" to sampleRate,
            "channels" to 2,
            "bitDepth" to 16,
            "format" to "WAV",
            "bufferSize" to bufferSize,
            "isInitialized" to isInitialized.get(),
            "isRecording" to isRecording.get()
        )
    }
    
    /**
     * Create timestamp metadata file for post-processing analysis
     */
    private fun createTimestampMetadata(
        metadataFile: File,
        sessionId: String,
        localStartTime: Long,
        pcCorrectedStartTime: Long,
        syncQuality: Int,
        clockOffset: Long
    ) {
        try {
            val metadata = mapOf(
                "sessionId" to sessionId,
                "recordingType" to "audio",
                "timestamps" to mapOf(
                    "localStartTime" to localStartTime,
                    "pcCorrectedStartTime" to pcCorrectedStartTime,
                    "systemTimeMillis" to System.currentTimeMillis(),
                    "elapsedRealtimeNanos" to android.os.SystemClock.elapsedRealtimeNanos()
                ),
                "synchronization" to mapOf(
                    "syncQuality" to syncQuality,
                    "clockOffset" to clockOffset,
                    "syncTimestamp" to System.currentTimeMillis(),
                    "isSyncAcceptable" to (networkManager?.isSynchronized() ?: false)
                ),
                "device" to mapOf(
                    "model" to android.os.Build.MODEL,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "androidVersion" to android.os.Build.VERSION.RELEASE
                ),
                "audio" to mapOf(
                    "sampleRate" to sampleRate,
                    "channels" to 2,
                    "bitDepth" to 16,
                    "format" to "WAV",
                    "bufferSize" to bufferSize
                )
            )
            
            // Write metadata as JSON
            val gson = com.google.gson.Gson()
            metadataFile.writeText(gson.toJson(metadata))
            
            Timber.d("Created audio timestamp metadata: ${metadataFile.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to create audio timestamp metadata")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            if (isRecording.get()) {
                stopRecording()
            }
            
            audioRecord?.release()
            audioRecord = null
            
            recordingJob?.cancel()
            outputStream?.close()
            
            Timber.d("AudioRecorderManager cleaned up")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during audio cleanup")
        }
    }
}