package com.multimodal.capture.simulation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.multimodal.capture.data.GSRDataPoint
import com.multimodal.capture.utils.TimestampManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * SimulationManager provides synthetic data generation for testing without hardware.
 * Generates realistic GSR, thermal, and audio data patterns for development and testing.
 */
class SimulationManager(private val context: Context) {
    
    private val timestampManager = TimestampManager()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Simulation state
    private val isSimulating = AtomicBoolean(false)
    private var currentSessionId: String = ""
    
    // Simulation jobs
    private var gsrSimulationJob: Job? = null
    private var thermalSimulationJob: Job? = null
    private var audioSimulationJob: Job? = null
    
    // Callbacks
    private var gsrDataCallback: ((Double, Int, Double) -> Unit)? = null
    private var thermalFrameCallback: ((ByteArray, Long) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    
    // Simulation parameters
    private val gsrSampleRate = 128.0 // Hz
    private val thermalFrameRate = 30.0 // Hz
    private val audioSampleRate = 44100 // Hz
    
    // Synthetic data generators
    private var timeOffset = 0.0
    private val random = Random.Default
    
    // Gaussian random number generator using Box-Muller transform
    private var hasNextGaussian = false
    private var nextGaussian = 0.0
    
    private fun nextGaussian(): Double {
        if (hasNextGaussian) {
            hasNextGaussian = false
            return nextGaussian
        } else {
            var v1: Double
            var v2: Double
            var s: Double
            do {
                v1 = 2 * random.nextDouble() - 1
                v2 = 2 * random.nextDouble() - 1
                s = v1 * v1 + v2 * v2
            } while (s >= 1 || s == 0.0)
            
            val multiplier = sqrt(-2 * ln(s) / s)
            nextGaussian = v2 * multiplier
            hasNextGaussian = true
            return v1 * multiplier
        }
    }
    
    init {
        Timber.d("SimulationManager initialized")
    }
    
    /**
     * Start simulation mode for all sensors
     */
    fun startSimulation(sessionId: String, startTimestamp: Long) {
        if (isSimulating.get()) {
            Timber.w("Simulation already running")
            return
        }
        
        try {
            currentSessionId = sessionId
            timestampManager.setSessionStartTime(startTimestamp)
            isSimulating.set(true)
            timeOffset = 0.0
            
            // Start individual simulations
            startGSRSimulation()
            startThermalSimulation()
            startAudioSimulation()
            
            updateStatus("Simulation mode active")
            Timber.d("Simulation started for session: $sessionId")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start simulation")
            updateStatus("Simulation Error: ${e.message}")
        }
    }
    
    /**
     * Stop all simulations
     */
    fun stopSimulation() {
        if (!isSimulating.get()) {
            Timber.w("No simulation running")
            return
        }
        
        try {
            isSimulating.set(false)
            
            // Cancel all simulation jobs
            gsrSimulationJob?.cancel()
            thermalSimulationJob?.cancel()
            audioSimulationJob?.cancel()
            
            gsrSimulationJob = null
            thermalSimulationJob = null
            audioSimulationJob = null
            
            updateStatus("Simulation stopped")
            Timber.d("Simulation stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping simulation")
        }
    }
    
    /**
     * Start GSR data simulation
     */
    private fun startGSRSimulation() {
        gsrSimulationJob = CoroutineScope(Dispatchers.IO).launch {
            val sampleInterval = (1000.0 / gsrSampleRate).toLong() // ms between samples
            
            while (isSimulating.get()) {
                try {
                    val timestamp = timestampManager.getCurrentTimestamp()
                    val timeSeconds = timeOffset
                    
                    // Generate realistic GSR data with physiological patterns
                    val baseGSR = generateGSRValue(timeSeconds)
                    val ppgValue = generatePPGValue(timeSeconds)
                    val heartRate = calculateSimulatedHeartRate(ppgValue, timeSeconds)
                    val prr = 95.0 + random.nextDouble() * 5.0 // 95-100% reception rate
                    
                    // Create data point
                    val dataPoint = GSRDataPoint(
                        timestamp = timestamp,
                        shimmerTimestamp = timestamp / 1_000_000,
                        gsrValue = baseGSR,
                        ppgValue = ppgValue,
                        packetReceptionRate = prr,
                        sessionId = currentSessionId,
                        deviceId = "SIM_DEVICE",
                        sampleRate = gsrSampleRate,
                        batteryLevel = 85.0 + random.nextDouble() * 10.0,
                        temperature = 25.0 + random.nextDouble() * 5.0
                    )
                    
                    // Notify callback
                    gsrDataCallback?.invoke(baseGSR, heartRate, prr)
                    
                    timeOffset += 1.0 / gsrSampleRate
                    delay(sampleInterval)
                    
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Timber.e(e, "Error in GSR simulation")
                }
            }
        }
    }
    
    /**
     * Start thermal camera simulation
     */
    private fun startThermalSimulation() {
        thermalSimulationJob = CoroutineScope(Dispatchers.IO).launch {
            val frameInterval = (1000.0 / thermalFrameRate).toLong() // ms between frames
            val frameWidth = 256
            val frameHeight = 192
            val frameSize = frameWidth * frameHeight * 2 // 16-bit thermal data
            
            while (isSimulating.get()) {
                try {
                    val timestamp = timestampManager.getCurrentTimestamp()
                    val timeSeconds = timeOffset
                    
                    // Generate synthetic thermal frame
                    val thermalFrame = generateThermalFrame(frameWidth, frameHeight, timeSeconds)
                    
                    // Notify callback
                    thermalFrameCallback?.invoke(thermalFrame, timestamp)
                    
                    delay(frameInterval)
                    
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Timber.e(e, "Error in thermal simulation")
                }
            }
        }
    }
    
    /**
     * Start audio simulation
     */
    private fun startAudioSimulation() {
        audioSimulationJob = CoroutineScope(Dispatchers.IO).launch {
            // Audio simulation would generate synthetic audio data
            // For now, just log that it's running
            while (isSimulating.get()) {
                try {
                    // Generate audio chunks periodically
                    delay(100) // 100ms chunks
                    
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Timber.e(e, "Error in audio simulation")
                }
            }
        }
    }
    
    /**
     * Generate realistic GSR values with physiological patterns
     */
    private fun generateGSRValue(timeSeconds: Double): Double {
        // Base GSR level with slow drift
        val baseLevel = 8.0 + 3.0 * sin(timeSeconds * 0.01)
        
        // Add breathing pattern (0.2-0.3 Hz)
        val breathingPattern = 1.5 * sin(timeSeconds * 2.0 * PI * 0.25)
        
        // Add heart rate variability (1-2 Hz)
        val heartRatePattern = 0.8 * sin(timeSeconds * 2.0 * PI * 1.2)
        
        // Add random noise
        val noise = nextGaussian() * 0.5
        
        // Occasional stress responses
        val stressResponse = if (random.nextDouble() < 0.001) {
            5.0 * exp(-(timeSeconds % 60.0) / 10.0)
        } else 0.0
        
        return (baseLevel + breathingPattern + heartRatePattern + noise + stressResponse)
            .coerceIn(1.0, 25.0)
    }
    
    /**
     * Generate realistic PPG values
     */
    private fun generatePPGValue(timeSeconds: Double): Double {
        // Heart rate around 70 BPM with variability
        val heartRate = 70.0 + 10.0 * sin(timeSeconds * 0.1) + nextGaussian() * 2.0
        val heartRateHz = heartRate / 60.0
        
        // PPG waveform with realistic shape
        val phase = timeSeconds * 2.0 * PI * heartRateHz
        val ppgWave = sin(phase) + 0.3 * sin(2.0 * phase) + 0.1 * sin(3.0 * phase)
        
        // Add baseline and noise
        val baseline = 2048.0 // Mid-range for 12-bit ADC
        val amplitude = 200.0
        val noise = nextGaussian() * 10.0
        
        return baseline + amplitude * ppgWave + noise
    }
    
    /**
     * Calculate simulated heart rate from PPG
     */
    private fun calculateSimulatedHeartRate(ppgValue: Double, timeSeconds: Double): Int {
        // Simple heart rate calculation based on time
        val baseHeartRate = 70.0 + 10.0 * sin(timeSeconds * 0.1)
        val variability = nextGaussian() * 3.0
        return (baseHeartRate + variability).toInt().coerceIn(50, 120)
    }
    
    /**
     * Generate synthetic thermal frame data
     */
    private fun generateThermalFrame(width: Int, height: Int, timeSeconds: Double): ByteArray {
        val frameData = ByteArray(width * height * 2)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Create thermal patterns with hot spots and gradients
                val centerX = width / 2.0
                val centerY = height / 2.0
                val distance = sqrt((x - centerX).pow(2) + (y - centerY).pow(2))
                
                // Base temperature with spatial variation
                val baseTemp = 25.0 + 5.0 * sin(timeSeconds * 0.1)
                val spatialPattern = 3.0 * exp(-distance / 50.0)
                val noise = nextGaussian() * 0.5
                
                // Convert to 16-bit thermal value
                val thermalValue = ((baseTemp + spatialPattern + noise) * 100).toInt()
                    .coerceIn(0, 65535)
                
                val index = (y * width + x) * 2
                frameData[index] = (thermalValue and 0xFF).toByte()
                frameData[index + 1] = ((thermalValue shr 8) and 0xFF).toByte()
            }
        }
        
        return frameData
    }
    
    /**
     * Set GSR data callback
     */
    fun setGSRDataCallback(callback: (Double, Int, Double) -> Unit) {
        gsrDataCallback = callback
    }
    
    /**
     * Set thermal frame callback
     */
    fun setThermalFrameCallback(callback: (ByteArray, Long) -> Unit) {
        thermalFrameCallback = callback
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
     * Check if simulation is running
     */
    fun isSimulating(): Boolean {
        return isSimulating.get()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            stopSimulation()
            Timber.d("SimulationManager cleaned up")
        } catch (e: Exception) {
            Timber.e(e, "Error during simulation cleanup")
        }
    }
}