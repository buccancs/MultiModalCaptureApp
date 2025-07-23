package com.multimodal.capture.utils

import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * TimestampManager provides unified timestamping across all capture modules.
 * Uses SystemClock.elapsedRealtimeNanos() for sub-millisecond precision and monotonic behavior.
 * 
 * This is critical for multi-modal synchronization as specified in the requirements.
 */
class TimestampManager {
    
    // Session start time in nanoseconds (monotonic clock)
    private val sessionStartTime = AtomicLong(0L)
    
    // Flag to track if session timing has been initialized
    private var isInitialized = false
    
    init {
        Timber.d("TimestampManager initialized")
    }
    
    /**
     * Set the session start time reference point.
     * This should be called when recording starts to establish a common time base.
     * 
     * @param startTimeNanos The session start time in nanoseconds (from SystemClock.elapsedRealtimeNanos())
     */
    fun setSessionStartTime(startTimeNanos: Long) {
        sessionStartTime.set(startTimeNanos)
        isInitialized = true
        Timber.d("Session start time set to: $startTimeNanos ns")
    }
    
    /**
     * Get the current timestamp relative to session start.
     * Returns the elapsed time since session start in nanoseconds.
     * 
     * @return Elapsed nanoseconds since session start, or absolute time if session not started
     */
    fun getCurrentTimestamp(): Long {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        
        return if (isInitialized) {
            currentTime - sessionStartTime.get()
        } else {
            // If session not started, return absolute monotonic time
            currentTime
        }
    }
    
    /**
     * Get the absolute monotonic timestamp.
     * This is useful for internal timing and synchronization.
     * 
     * @return Current monotonic time in nanoseconds
     */
    fun getAbsoluteTimestamp(): Long {
        return SystemClock.elapsedRealtimeNanos()
    }
    
    /**
     * Get the session start time.
     * 
     * @return Session start time in nanoseconds, or 0 if not initialized
     */
    fun getSessionStartTime(): Long {
        return sessionStartTime.get()
    }
    
    /**
     * Check if the timestamp manager has been initialized with a session start time.
     * 
     * @return true if session timing is initialized
     */
    fun isSessionInitialized(): Boolean {
        return isInitialized
    }
    
    /**
     * Reset the timestamp manager.
     * This should be called when a recording session ends.
     */
    fun reset() {
        sessionStartTime.set(0L)
        isInitialized = false
        Timber.d("TimestampManager reset")
    }
    
    /**
     * Convert nanoseconds to milliseconds with precision.
     * 
     * @param nanoseconds Time in nanoseconds
     * @return Time in milliseconds as double for precision
     */
    fun nanosToMillis(nanoseconds: Long): Double {
        return nanoseconds / 1_000_000.0
    }
    
    /**
     * Convert nanoseconds to seconds with precision.
     * 
     * @param nanoseconds Time in nanoseconds
     * @return Time in seconds as double for precision
     */
    fun nanosToSeconds(nanoseconds: Long): Double {
        return nanoseconds / 1_000_000_000.0
    }
    
    /**
     * Create a timestamp entry for data logging.
     * This includes both relative and absolute timestamps for maximum flexibility.
     * 
     * @param eventType Type of event being timestamped
     * @return TimestampEntry with all relevant timing information
     */
    fun createTimestampEntry(eventType: String): TimestampEntry {
        val absoluteTime = getAbsoluteTimestamp()
        val relativeTime = getCurrentTimestamp()
        val sessionStart = getSessionStartTime()
        
        return TimestampEntry(
            eventType = eventType,
            absoluteTimestampNanos = absoluteTime,
            relativeTimestampNanos = relativeTime,
            sessionStartNanos = sessionStart,
            timestampMillis = nanosToMillis(relativeTime),
            timestampSeconds = nanosToSeconds(relativeTime)
        )
    }
    
    /**
     * Calculate time difference between two timestamps.
     * 
     * @param timestamp1 First timestamp in nanoseconds
     * @param timestamp2 Second timestamp in nanoseconds
     * @return Difference in nanoseconds (timestamp2 - timestamp1)
     */
    fun calculateTimeDifference(timestamp1: Long, timestamp2: Long): Long {
        return timestamp2 - timestamp1
    }
    
    /**
     * Check if two timestamps are within a specified tolerance.
     * Useful for synchronization validation.
     * 
     * @param timestamp1 First timestamp in nanoseconds
     * @param timestamp2 Second timestamp in nanoseconds
     * @param toleranceNanos Tolerance in nanoseconds
     * @return true if timestamps are within tolerance
     */
    fun areTimestampsWithinTolerance(
        timestamp1: Long, 
        timestamp2: Long, 
        toleranceNanos: Long
    ): Boolean {
        val difference = kotlin.math.abs(timestamp1 - timestamp2)
        return difference <= toleranceNanos
    }
    
    companion object {
        // Common tolerance values in nanoseconds
        const val TOLERANCE_1_MS = 1_000_000L      // 1 millisecond
        const val TOLERANCE_10_MS = 10_000_000L    // 10 milliseconds
        const val TOLERANCE_50_MS = 50_000_000L    // 50 milliseconds
        const val TOLERANCE_100_MS = 100_000_000L  // 100 milliseconds
        
        // Conversion constants
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Data class representing a complete timestamp entry for logging.
 */
data class TimestampEntry(
    val eventType: String,
    val absoluteTimestampNanos: Long,
    val relativeTimestampNanos: Long,
    val sessionStartNanos: Long,
    val timestampMillis: Double,
    val timestampSeconds: Double
) {
    /**
     * Convert to CSV format for data export.
     */
    fun toCsvString(): String {
        return "$eventType,$absoluteTimestampNanos,$relativeTimestampNanos,$sessionStartNanos,$timestampMillis,$timestampSeconds"
    }
    
    /**
     * Get CSV header for this timestamp entry.
     */
    companion object {
        fun getCsvHeader(): String {
            return "EventType,AbsoluteTimestampNanos,RelativeTimestampNanos,SessionStartNanos,TimestampMillis,TimestampSeconds"
        }
    }
}