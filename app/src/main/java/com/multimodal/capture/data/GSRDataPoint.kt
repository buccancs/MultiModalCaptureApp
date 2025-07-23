package com.multimodal.capture.data

/**
 * Data class representing a single GSR sensor reading.
 * Contains all necessary information for data logging and synchronization.
 */
data class GSRDataPoint(
    val timestamp: Long,                    // Unified timestamp (nanoseconds since session start)
    val shimmerTimestamp: Long,             // Shimmer internal timestamp (most accurate)
    val gsrValue: Double,                   // GSR conductance value in microsiemens (μS)
    val ppgValue: Double,                   // PPG value for heart rate calculation
    val packetReceptionRate: Double,        // Packet reception rate (%)
    val sessionId: String,                  // Session identifier
    val deviceId: String = "",              // Shimmer device MAC address
    val sampleRate: Double = 128.0,         // Sample rate in Hz
    val batteryLevel: Double = 0.0,         // Battery level if available
    val temperature: Double = 0.0           // Temperature if available
) {
    
    /**
     * Convert to CSV format for data export
     */
    fun toCsvString(): String {
        return "$timestamp,$shimmerTimestamp,$gsrValue,$ppgValue,$packetReceptionRate,$sessionId,$deviceId,$sampleRate,$batteryLevel,$temperature"
    }
    
    /**
     * Convert timestamp to milliseconds for easier analysis
     */
    fun getTimestampMillis(): Double {
        return timestamp / 1_000_000.0
    }
    
    /**
     * Convert timestamp to seconds for easier analysis
     */
    fun getTimestampSeconds(): Double {
        return timestamp / 1_000_000_000.0
    }
    
    /**
     * Check if this is a valid GSR reading
     */
    fun isValidReading(): Boolean {
        return gsrValue >= 0.0 && gsrValue <= 100.0 && // Typical GSR range
               packetReceptionRate >= 0.0 && packetReceptionRate <= 100.0
    }
    
    /**
     * Get GSR value in different units
     */
    fun getGSRInKiloOhms(): Double {
        // Convert from microsiemens to kilohms (1/conductance)
        return if (gsrValue > 0) 1000.0 / gsrValue else Double.MAX_VALUE
    }
    
    companion object {
        /**
         * Get CSV header for GSR data export
         */
        fun getCsvHeader(): String {
            return "Timestamp,ShimmerTimestamp,GSRValue,PPGValue,PacketReceptionRate,SessionId,DeviceId,SampleRate,BatteryLevel,Temperature"
        }
        
        /**
         * Create a test/dummy GSR data point for simulation mode
         */
        fun createTestDataPoint(timestamp: Long, sessionId: String): GSRDataPoint {
            // Generate realistic test values
            val baseGSR = 5.0 + (Math.random() * 10.0) // 5-15 μS range
            val basePPG = 500.0 + (Math.random() * 200.0) // Typical PPG range
            
            return GSRDataPoint(
                timestamp = timestamp,
                shimmerTimestamp = timestamp / 1_000_000, // Convert to shimmer time scale
                gsrValue = baseGSR,
                ppgValue = basePPG,
                packetReceptionRate = 95.0 + (Math.random() * 5.0), // 95-100%
                sessionId = sessionId,
                deviceId = "TEST_DEVICE",
                sampleRate = 128.0,
                batteryLevel = 80.0 + (Math.random() * 20.0), // 80-100%
                temperature = 25.0 + (Math.random() * 10.0) // 25-35°C
            )
        }
        
        /**
         * Parse GSR data point from CSV string
         */
        fun fromCsvString(csvLine: String): GSRDataPoint? {
            return try {
                val parts = csvLine.split(",")
                if (parts.size >= 6) {
                    GSRDataPoint(
                        timestamp = parts[0].toLong(),
                        shimmerTimestamp = parts[1].toLong(),
                        gsrValue = parts[2].toDouble(),
                        ppgValue = parts[3].toDouble(),
                        packetReceptionRate = parts[4].toDouble(),
                        sessionId = parts[5],
                        deviceId = if (parts.size > 6) parts[6] else "",
                        sampleRate = if (parts.size > 7) parts[7].toDouble() else 128.0,
                        batteryLevel = if (parts.size > 8) parts[8].toDouble() else 0.0,
                        temperature = if (parts.size > 9) parts[9].toDouble() else 0.0
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
        
        // Constants for GSR analysis
        const val MIN_VALID_GSR = 0.1      // Minimum valid GSR value (μS)
        const val MAX_VALID_GSR = 50.0     // Maximum valid GSR value (μS)
        const val TYPICAL_GSR_RANGE_LOW = 1.0   // Typical low GSR (μS)
        const val TYPICAL_GSR_RANGE_HIGH = 20.0 // Typical high GSR (μS)
        
        // PPG constants
        const val MIN_VALID_PPG = 0.0      // Minimum valid PPG value
        const val MAX_VALID_PPG = 4096.0   // Maximum valid PPG value (12-bit ADC)
        
        // Sample rate constants
        const val TARGET_SAMPLE_RATE = 128.0    // Target sample rate (Hz)
        const val MIN_ACCEPTABLE_PRR = 90.0     // Minimum acceptable packet reception rate (%)
    }
}

/**
 * Data class for GSR session summary statistics
 */
data class GSRSessionSummary(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val totalSamples: Int,
    val averageGSR: Double,
    val minGSR: Double,
    val maxGSR: Double,
    val averagePRR: Double,
    val droppedPackets: Int,
    val averageHeartRate: Double
) {
    
    /**
     * Get session duration in seconds
     */
    fun getDurationSeconds(): Double {
        return (endTime - startTime) / 1_000_000_000.0
    }
    
    /**
     * Get effective sample rate
     */
    fun getEffectiveSampleRate(): Double {
        val durationSeconds = getDurationSeconds()
        return if (durationSeconds > 0) totalSamples / durationSeconds else 0.0
    }
    
    /**
     * Check if session quality is acceptable
     */
    fun isQualityAcceptable(): Boolean {
        return averagePRR >= GSRDataPoint.MIN_ACCEPTABLE_PRR &&
               totalSamples > 0 &&
               averageGSR >= GSRDataPoint.MIN_VALID_GSR &&
               averageGSR <= GSRDataPoint.MAX_VALID_GSR
    }
    
    /**
     * Convert to CSV format
     */
    fun toCsvString(): String {
        return "$sessionId,$startTime,$endTime,$totalSamples,$averageGSR,$minGSR,$maxGSR,$averagePRR,$droppedPackets,$averageHeartRate"
    }
    
    companion object {
        fun getCsvHeader(): String {
            return "SessionId,StartTime,EndTime,TotalSamples,AverageGSR,MinGSR,MaxGSR,AveragePRR,DroppedPackets,AverageHeartRate"
        }
    }
}