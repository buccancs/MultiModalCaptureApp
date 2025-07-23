package com.multimodal.capture.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import com.google.gson.Gson

/**
 * TimestampValidator provides comprehensive validation and monitoring of timestamp synchronization
 * across all capture modalities (IR thermal, RGB camera, audio, GSR sensor).
 * 
 * This utility helps ensure accurate multi-modal data alignment by:
 * - Monitoring sync quality in real-time
 * - Detecting timestamp drift and inconsistencies
 * - Validating PC-Android clock synchronization
 * - Generating synchronization quality reports
 */
class TimestampValidator(private val context: Context) {
    
    private val gson = Gson()
    
    // Validation metrics
    private val syncQualityHistory = ConcurrentHashMap<String, MutableList<SyncQualityMeasurement>>()
    private val timestampDriftHistory = ConcurrentHashMap<String, MutableList<TimestampDrift>>()
    private val validationResults = ConcurrentHashMap<String, ValidationResult>()
    
    // Monitoring state
    private var monitoringJob: Job? = null
    private val isMonitoring = AtomicLong(0)
    
    // Validation thresholds
    private val maxAcceptableOffset = 50_000_000L // 50ms in nanoseconds
    private val maxAcceptableDrift = 10_000_000L  // 10ms drift per minute
    private val minSyncQuality = 60 // Minimum acceptable sync quality (0-100)
    
    init {
        Timber.d("TimestampValidator initialized")
    }
    
    /**
     * Start real-time timestamp monitoring
     */
    fun startMonitoring(sessionId: String) {
        if (isMonitoring.get() != 0L) {
            Timber.w("Timestamp monitoring already active")
            return
        }
        
        isMonitoring.set(System.currentTimeMillis())
        
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            monitorTimestampQuality(sessionId)
        }
        
        Timber.i("Started timestamp monitoring for session: $sessionId")
    }
    
    /**
     * Stop real-time timestamp monitoring
     */
    fun stopMonitoring(): ValidationSummary? {
        val startTime = isMonitoring.getAndSet(0)
        if (startTime == 0L) {
            Timber.w("Timestamp monitoring not active")
            return null
        }
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        val duration = System.currentTimeMillis() - startTime
        val summary = generateValidationSummary(duration)
        
        Timber.i("Stopped timestamp monitoring. Duration: ${duration}ms")
        return summary
    }
    
    /**
     * Validate timestamp synchronization for a specific modality
     */
    fun validateModalitySync(
        modalityName: String,
        localTimestamp: Long,
        pcCorrectedTimestamp: Long,
        syncQuality: Int,
        clockOffset: Long
    ): ValidationResult {
        
        val measurement = SyncQualityMeasurement(
            timestamp = System.currentTimeMillis(),
            modalityName = modalityName,
            localTimestamp = localTimestamp,
            pcCorrectedTimestamp = pcCorrectedTimestamp,
            syncQuality = syncQuality,
            clockOffset = clockOffset,
            timestampDifference = Math.abs(pcCorrectedTimestamp - localTimestamp)
        )
        
        // Store measurement
        syncQualityHistory.computeIfAbsent(modalityName) { mutableListOf() }.add(measurement)
        
        // Validate against thresholds
        val result = ValidationResult(
            modalityName = modalityName,
            isValid = validateMeasurement(measurement),
            syncQuality = syncQuality,
            clockOffset = clockOffset,
            timestampDifference = measurement.timestampDifference,
            issues = detectIssues(measurement),
            timestamp = System.currentTimeMillis()
        )
        
        validationResults[modalityName] = result
        
        if (!result.isValid) {
            Timber.w("Timestamp validation failed for $modalityName: ${result.issues}")
        }
        
        return result
    }
    
    /**
     * Monitor timestamp quality in real-time
     */
    private suspend fun monitorTimestampQuality(sessionId: String) {
        val monitoringInterval = 5000L // 5 seconds
        
        while (isMonitoring.get() != 0L) {
            try {
                // Check for timestamp drift across modalities
                detectTimestampDrift()
                
                // Validate overall synchronization quality
                validateOverallSyncQuality()
                
                // Generate periodic quality report
                generateQualityReport(sessionId)
                
                delay(monitoringInterval)
                
            } catch (e: Exception) {
                if (e is CancellationException) {
                    break
                } else {
                    Timber.e(e, "Error during timestamp monitoring")
                }
            }
        }
    }
    
    /**
     * Detect timestamp drift between modalities
     */
    private fun detectTimestampDrift() {
        val currentTime = System.currentTimeMillis()
        
        for ((modalityName, measurements) in syncQualityHistory) {
            if (measurements.size < 2) continue
            
            val recent = measurements.takeLast(10) // Last 10 measurements
            if (recent.size < 2) continue
            
            val firstMeasurement = recent.first()
            val lastMeasurement = recent.last()
            
            val timeDelta = lastMeasurement.timestamp - firstMeasurement.timestamp
            val offsetDelta = lastMeasurement.clockOffset - firstMeasurement.clockOffset
            
            if (timeDelta > 0) {
                val driftRate = (offsetDelta.toDouble() / timeDelta) * 60000 // drift per minute
                
                val drift = TimestampDrift(
                    modalityName = modalityName,
                    driftRate = driftRate,
                    timeWindow = timeDelta,
                    offsetChange = offsetDelta,
                    timestamp = currentTime
                )
                
                timestampDriftHistory.computeIfAbsent(modalityName) { mutableListOf() }.add(drift)
                
                if (Math.abs(driftRate) > maxAcceptableDrift) {
                    Timber.w("Excessive timestamp drift detected for $modalityName: ${driftRate}ns/min")
                }
            }
        }
    }
    
    /**
     * Validate overall synchronization quality
     */
    private fun validateOverallSyncQuality() {
        val currentResults = validationResults.values.toList()
        if (currentResults.isEmpty()) return
        
        val averageSyncQuality = currentResults.map { it.syncQuality }.average()
        val maxClockOffset = currentResults.map { Math.abs(it.clockOffset) }.maxOrNull() ?: 0L
        val invalidCount = currentResults.count { !it.isValid }
        
        if (averageSyncQuality < minSyncQuality) {
            Timber.w("Overall sync quality below threshold: $averageSyncQuality < $minSyncQuality")
        }
        
        if (maxClockOffset > maxAcceptableOffset) {
            Timber.w("Clock offset exceeds threshold: ${maxClockOffset}ns > ${maxAcceptableOffset}ns")
        }
        
        if (invalidCount > 0) {
            Timber.w("$invalidCount modalities have invalid timestamp synchronization")
        }
    }
    
    /**
     * Generate periodic quality report
     */
    private fun generateQualityReport(sessionId: String) {
        try {
            val report = TimestampQualityReport(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                modalityResults = validationResults.values.toList(),
                overallQuality = calculateOverallQuality(),
                recommendations = generateRecommendations()
            )
            
            // Save report to file
            val outputDir = File(context.getExternalFilesDir(null), "validation_reports")
            outputDir.mkdirs()
            
            val reportFile = File(outputDir, "${sessionId}_timestamp_quality_${System.currentTimeMillis()}.json")
            reportFile.writeText(gson.toJson(report))
            
            Timber.d("Generated timestamp quality report: ${reportFile.absolutePath}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate quality report")
        }
    }
    
    /**
     * Validate individual measurement against thresholds
     */
    private fun validateMeasurement(measurement: SyncQualityMeasurement): Boolean {
        return measurement.syncQuality >= minSyncQuality &&
               Math.abs(measurement.clockOffset) <= maxAcceptableOffset &&
               measurement.timestampDifference <= maxAcceptableOffset
    }
    
    /**
     * Detect specific issues with timestamp measurement
     */
    private fun detectIssues(measurement: SyncQualityMeasurement): List<String> {
        val issues = mutableListOf<String>()
        
        if (measurement.syncQuality < minSyncQuality) {
            issues.add("Low sync quality: ${measurement.syncQuality} < $minSyncQuality")
        }
        
        if (Math.abs(measurement.clockOffset) > maxAcceptableOffset) {
            issues.add("High clock offset: ${measurement.clockOffset}ns > ${maxAcceptableOffset}ns")
        }
        
        if (measurement.timestampDifference > maxAcceptableOffset) {
            issues.add("Large timestamp difference: ${measurement.timestampDifference}ns > ${maxAcceptableOffset}ns")
        }
        
        return issues
    }
    
    /**
     * Calculate overall synchronization quality
     */
    private fun calculateOverallQuality(): Int {
        val results = validationResults.values.toList()
        if (results.isEmpty()) return 0
        
        val averageQuality = results.map { it.syncQuality }.average()
        val validPercentage = results.count { it.isValid }.toDouble() / results.size * 100
        
        return ((averageQuality + validPercentage) / 2).toInt()
    }
    
    /**
     * Generate recommendations for improving synchronization
     */
    private fun generateRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val results = validationResults.values.toList()
        
        if (results.any { it.syncQuality < minSyncQuality }) {
            recommendations.add("Improve network stability to enhance sync quality")
        }
        
        if (results.any { Math.abs(it.clockOffset) > maxAcceptableOffset }) {
            recommendations.add("Increase sync frequency to reduce clock offset")
        }
        
        val driftIssues = timestampDriftHistory.values.flatten().any { 
            Math.abs(it.driftRate) > maxAcceptableDrift 
        }
        if (driftIssues) {
            recommendations.add("Monitor and correct timestamp drift during long recordings")
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("Timestamp synchronization quality is acceptable")
        }
        
        return recommendations
    }
    
    /**
     * Generate validation summary
     */
    private fun generateValidationSummary(monitoringDuration: Long): ValidationSummary {
        val results = validationResults.values.toList()
        val totalMeasurements = syncQualityHistory.values.sumOf { it.size }
        
        return ValidationSummary(
            monitoringDuration = monitoringDuration,
            totalMeasurements = totalMeasurements,
            modalityCount = results.size,
            overallQuality = calculateOverallQuality(),
            validMeasurements = results.count { it.isValid },
            invalidMeasurements = results.count { !it.isValid },
            averageSyncQuality = if (results.isNotEmpty()) results.map { it.syncQuality }.average() else 0.0,
            maxClockOffset = results.map { Math.abs(it.clockOffset) }.maxOrNull() ?: 0L,
            recommendations = generateRecommendations()
        )
    }
    
    /**
     * Get current validation status for all modalities
     */
    fun getCurrentValidationStatus(): Map<String, ValidationResult> {
        return validationResults.toMap()
    }
    
    /**
     * Clear validation history
     */
    fun clearHistory() {
        syncQualityHistory.clear()
        timestampDriftHistory.clear()
        validationResults.clear()
        Timber.d("Cleared timestamp validation history")
    }
    
    /**
     * Export validation data for analysis
     */
    fun exportValidationData(sessionId: String): File? {
        return try {
            val outputDir = File(context.getExternalFilesDir(null), "validation_exports")
            outputDir.mkdirs()
            
            val exportData = ValidationExport(
                sessionId = sessionId,
                exportTimestamp = System.currentTimeMillis(),
                syncQualityHistory = syncQualityHistory.toMap(),
                timestampDriftHistory = timestampDriftHistory.toMap(),
                validationResults = validationResults.toMap(),
                summary = generateValidationSummary(0)
            )
            
            val exportFile = File(outputDir, "${sessionId}_validation_export.json")
            exportFile.writeText(gson.toJson(exportData))
            
            Timber.i("Exported validation data: ${exportFile.absolutePath}")
            exportFile
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to export validation data")
            null
        }
    }
}

/**
 * Data classes for timestamp validation
 */
data class SyncQualityMeasurement(
    val timestamp: Long,
    val modalityName: String,
    val localTimestamp: Long,
    val pcCorrectedTimestamp: Long,
    val syncQuality: Int,
    val clockOffset: Long,
    val timestampDifference: Long
)

data class TimestampDrift(
    val modalityName: String,
    val driftRate: Double, // nanoseconds per minute
    val timeWindow: Long,
    val offsetChange: Long,
    val timestamp: Long
)

data class ValidationResult(
    val modalityName: String,
    val isValid: Boolean,
    val syncQuality: Int,
    val clockOffset: Long,
    val timestampDifference: Long,
    val issues: List<String>,
    val timestamp: Long
)

data class TimestampQualityReport(
    val sessionId: String,
    val timestamp: Long,
    val modalityResults: List<ValidationResult>,
    val overallQuality: Int,
    val recommendations: List<String>
)

data class ValidationSummary(
    val monitoringDuration: Long,
    val totalMeasurements: Int,
    val modalityCount: Int,
    val overallQuality: Int,
    val validMeasurements: Int,
    val invalidMeasurements: Int,
    val averageSyncQuality: Double,
    val maxClockOffset: Long,
    val recommendations: List<String>
)

data class ValidationExport(
    val sessionId: String,
    val exportTimestamp: Long,
    val syncQualityHistory: Map<String, List<SyncQualityMeasurement>>,
    val timestampDriftHistory: Map<String, List<TimestampDrift>>,
    val validationResults: Map<String, ValidationResult>,
    val summary: ValidationSummary
)