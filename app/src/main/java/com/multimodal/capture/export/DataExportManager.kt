package com.multimodal.capture.export

import android.content.Context
import com.multimodal.capture.data.SessionFolder
import com.multimodal.capture.data.SessionFileType
import com.multimodal.capture.utils.TimestampManager
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/**
 * DataExportManager handles advanced data export and analysis capabilities
 * for multi-modal capture sessions.
 * 
 * Features:
 * - CSV export with synchronized timestamps
 * - JSON export with comprehensive metadata
 * - Data correlation analysis across modalities
 * - Export format customization
 */
class DataExportManager(private val context: Context) {
    
    private val timestampManager = TimestampManager()
    
    /**
     * Export session data in multiple formats
     */
    suspend fun exportSessionData(
        sessionFolder: SessionFolder,
        exportFormats: Set<ExportFormat>,
        outputDirectory: File
    ): ExportResult = withContext(Dispatchers.IO) {
        
        try {
            Timber.d("Starting data export for session: ${sessionFolder.name}")
            
            val exportResults = mutableMapOf<ExportFormat, File>()
            val sessionData = analyzeSessionData(sessionFolder)
            
            // Export in requested formats
            exportFormats.forEach { format ->
                val exportedFile = when (format) {
                    ExportFormat.CSV -> exportToCSV(sessionData, outputDirectory)
                    ExportFormat.JSON -> exportToJSON(sessionData, outputDirectory)
                    ExportFormat.SYNCHRONIZED_CSV -> exportSynchronizedCSV(sessionData, outputDirectory)
                    ExportFormat.ANALYSIS_REPORT -> generateAnalysisReport(sessionData, outputDirectory)
                }
                exportResults[format] = exportedFile
            }
            
            Timber.d("Data export completed successfully")
            ExportResult.Success(exportResults)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to export session data")
            ExportResult.Error(e.message ?: "Unknown export error")
        }
    }
    
    /**
     * Analyze session data for correlation and insights
     */
    private suspend fun analyzeSessionData(sessionFolder: SessionFolder): SessionAnalysis {
        return withContext(Dispatchers.Default) {
            
            val analysis = SessionAnalysis(
                sessionName = sessionFolder.name,
                totalDuration = calculateSessionDuration(sessionFolder),
                modalityData = mutableMapOf(),
                correlationData = mutableMapOf(),
                qualityMetrics = mutableMapOf()
            )
            
            // Analyze each modality
            sessionFolder.files.forEach { file ->
                when {
                    file.name.contains("thermal") -> {
                        analysis.modalityData["thermal"] = analyzeThermalData(file)
                    }
                    file.name.contains("rgb_video") -> {
                        analysis.modalityData["camera"] = analyzeCameraData(file)
                    }
                    file.name.contains("gsr_data") -> {
                        analysis.modalityData["gsr"] = analyzeGSRData(file)
                    }
                    file.name.contains("audio") -> {
                        analysis.modalityData["audio"] = analyzeAudioData(file)
                    }
                }
            }
            
            // Calculate cross-modal correlations
            analysis.correlationData.putAll(calculateCorrelations(analysis.modalityData))
            
            // Generate quality metrics
            analysis.qualityMetrics.putAll(generateQualityMetrics(sessionFolder, analysis.modalityData))
            
            analysis
        }
    }
    
    /**
     * Export data to basic CSV format
     */
    private fun exportToCSV(analysis: SessionAnalysis, outputDir: File): File {
        val csvFile = File(outputDir, "${analysis.sessionName}_basic_data.csv")
        
        FileWriter(csvFile).use { writer ->
            // Write header
            writer.append("Modality,Data_Points,Average_Quality,Sync_Quality\n")
            
            // Write modality summary
            analysis.modalityData.forEach { (modality, data) ->
                writer.append("$modality,")
                writer.append("${data.dataPoints.size},")
                writer.append("${data.averageQuality},")
                writer.append("${data.syncQuality}\n")
            }
        }
        
        return csvFile
    }
    
    /**
     * Export data to CSV format with timestamp synchronization
     */
    private fun exportSynchronizedCSV(analysis: SessionAnalysis, outputDir: File): File {
        val csvFile = File(outputDir, "${analysis.sessionName}_synchronized_data.csv")
        
        FileWriter(csvFile).use { writer ->
            // Write header
            writer.append("Timestamp_ms,Modality,Data_Type,Value,Quality_Score,Sync_Status\n")
            
            // Write synchronized data points
            analysis.modalityData.forEach { (modality, data) ->
                data.dataPoints.forEach { point ->
                    writer.append("${point.timestamp},")
                    writer.append("$modality,")
                    writer.append("${point.type},")
                    writer.append("${point.value},")
                    writer.append("${point.quality},")
                    writer.append("${point.syncStatus}\n")
                }
            }
        }
        
        return csvFile
    }
    
    /**
     * Export comprehensive analysis report in JSON format
     */
    private fun exportToJSON(analysis: SessionAnalysis, outputDir: File): File {
        val jsonFile = File(outputDir, "${analysis.sessionName}_analysis_report.json")
        
        val jsonReport = JSONObject().apply {
            put("session_name", analysis.sessionName)
            put("export_timestamp", System.currentTimeMillis())
            put("total_duration_ms", analysis.totalDuration)
            
            // Modality data
            val modalitiesJson = JSONObject()
            analysis.modalityData.forEach { (modality, data) ->
                modalitiesJson.put(modality, JSONObject().apply {
                    put("data_points_count", data.dataPoints.size)
                    put("average_quality", data.averageQuality)
                    put("sync_quality", data.syncQuality)
                    put("data_range", JSONObject().apply {
                        put("min", data.minValue)
                        put("max", data.maxValue)
                        put("mean", data.meanValue)
                    })
                })
            }
            put("modalities", modalitiesJson)
            
            // Correlation data
            val correlationsJson = JSONObject()
            analysis.correlationData.forEach { (pair, correlation) ->
                correlationsJson.put(pair, correlation)
            }
            put("correlations", correlationsJson)
            
            // Quality metrics
            val qualityJson = JSONObject()
            analysis.qualityMetrics.forEach { (metric, value) ->
                qualityJson.put(metric, value)
            }
            put("quality_metrics", qualityJson)
        }
        
        FileWriter(jsonFile).use { writer ->
            writer.write(jsonReport.toString(2))
        }
        
        return jsonFile
    }
    
    /**
     * Generate comprehensive analysis report
     */
    private fun generateAnalysisReport(analysis: SessionAnalysis, outputDir: File): File {
        val reportFile = File(outputDir, "${analysis.sessionName}_analysis_report.txt")
        
        FileWriter(reportFile).use { writer ->
            writer.append("Multi-Modal Capture Session Analysis Report\n")
            writer.append("=".repeat(50) + "\n\n")
            
            writer.append("Session: ${analysis.sessionName}\n")
            writer.append("Duration: ${analysis.totalDuration / 1000.0} seconds\n")
            writer.append("Export Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")
            
            // Modality summary
            writer.append("MODALITY SUMMARY\n")
            writer.append("-".repeat(20) + "\n")
            analysis.modalityData.forEach { (modality, data) ->
                writer.append("$modality:\n")
                writer.append("  Data Points: ${data.dataPoints.size}\n")
                writer.append("  Quality Score: ${String.format("%.2f", data.averageQuality)}\n")
                writer.append("  Sync Quality: ${String.format("%.2f", data.syncQuality)}\n")
                writer.append("  Value Range: ${String.format("%.2f", data.minValue)} - ${String.format("%.2f", data.maxValue)}\n\n")
            }
            
            // Correlation analysis
            writer.append("CORRELATION ANALYSIS\n")
            writer.append("-".repeat(20) + "\n")
            analysis.correlationData.forEach { (pair, correlation) ->
                writer.append("$pair: ${String.format("%.3f", correlation)}\n")
            }
            writer.append("\n")
            
            // Quality metrics
            writer.append("QUALITY METRICS\n")
            writer.append("-".repeat(15) + "\n")
            analysis.qualityMetrics.forEach { (metric, value) ->
                writer.append("$metric: ${String.format("%.2f", value)}\n")
            }
        }
        
        return reportFile
    }
    
    // Helper methods for data analysis
    private fun calculateSessionDuration(sessionFolder: SessionFolder): Long {
        try {
            // First, try to get duration from metadata file
            val metadataFile = sessionFolder.files.find { it.type == SessionFileType.METADATA }
            if (metadataFile != null) {
                val duration = extractDurationFromMetadata(File(metadataFile.path))
                if (duration > 0) return duration
            }
            
            // Fallback: calculate from file timestamps
            val timestamps = sessionFolder.files.map { it.lastModified.time }
            if (timestamps.size >= 2) {
                return timestamps.maxOrNull()!! - timestamps.minOrNull()!!
            }
            
            return 0L
        } catch (e: Exception) {
            Timber.w(e, "Failed to calculate session duration")
            return 0L
        }
    }
    
    private fun extractDurationFromMetadata(metadataFile: File): Long {
        return try {
            val content = metadataFile.readText()
            val json = JSONObject(content)
            val startTime = json.optLong("startTimestamp", 0L)
            val endTime = json.optLong("endTimestamp", 0L)
            if (startTime > 0 && endTime > startTime) endTime - startTime else 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun analyzeThermalData(file: com.multimodal.capture.data.SessionFile): ModalityData {
        return try {
            val dataPoints = mutableListOf<DataPoint>()
            val temperatureValues = mutableListOf<Double>()
            
            // Handle different thermal file types
            when {
                file.name.contains("thermal_raw") -> {
                    analyzeThermalRawData(file, dataPoints, temperatureValues)
                }
                file.name.contains("thermal_yuv") -> {
                    analyzeThermalYUVData(file, dataPoints, temperatureValues)
                }
                file.name.contains("thermal_argb") -> {
                    analyzeThermalARGBData(file, dataPoints, temperatureValues)
                }
                else -> {
                    // Generic thermal data analysis
                    analyzeGenericThermalData(file, dataPoints, temperatureValues)
                }
            }
            
            if (temperatureValues.isEmpty()) {
                return ModalityData()
            }
            
            val minValue = temperatureValues.minOrNull() ?: 0.0
            val maxValue = temperatureValues.maxOrNull() ?: 0.0
            val meanValue = temperatureValues.average()
            val averageQuality = calculateThermalQuality(temperatureValues)
            val syncQuality = dataPoints.count { it.syncStatus == "GOOD" }.toDouble() / dataPoints.size.coerceAtLeast(1)
            
            ModalityData(
                dataPoints = dataPoints,
                averageQuality = averageQuality,
                syncQuality = syncQuality,
                minValue = minValue,
                maxValue = maxValue,
                meanValue = meanValue
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze thermal data")
            ModalityData()
        }
    }
    
    private fun analyzeThermalRawData(file: com.multimodal.capture.data.SessionFile, dataPoints: MutableList<DataPoint>, temperatureValues: MutableList<Double>) {
        // Thermal raw data format: timestamp (8 bytes) + frame size (4 bytes) + frame data
        File(file.path).inputStream().use { input ->
            var frameCount = 0
            while (input.available() > 12) { // At least timestamp + frame size
                val timestamp = readLong(input)
                val frameSize = readInt(input)
                
                if (frameSize > 0 && input.available() >= frameSize) {
                    val frameData = ByteArray(frameSize)
                    input.read(frameData)
                    
                    // Extract temperature data from frame (assuming last half contains temperature data)
                    val tempDataSize = frameSize / 2
                    val tempData = frameData.sliceArray(tempDataSize until frameSize - 1) // Exclude status byte
                    
                    // Convert bytes to temperature values (assuming 16-bit values)
                    for (i in tempData.indices step 2) {
                        if (i + 1 < tempData.size) {
                            val tempValue = ((tempData[i].toInt() and 0xFF) or ((tempData[i + 1].toInt() and 0xFF) shl 8)).toDouble() / 100.0
                            temperatureValues.add(tempValue)
                        }
                    }
                    
                    val avgTemp = if (temperatureValues.isNotEmpty()) temperatureValues.takeLast(tempData.size / 2).average() else 0.0
                    dataPoints.add(DataPoint(
                        timestamp = timestamp,
                        type = "THERMAL",
                        value = avgTemp,
                        quality = if (frameSize > 0) 1.0 else 0.0,
                        syncStatus = "GOOD"
                    ))
                    
                    frameCount++
                    if (frameCount > 1000) break // Limit analysis for performance
                }
            }
        }
    }
    
    private fun analyzeThermalYUVData(file: com.multimodal.capture.data.SessionFile, dataPoints: MutableList<DataPoint>, temperatureValues: MutableList<Double>) {
        // YUV format analysis - simplified implementation
        val fileSize = file.size
        val estimatedFrames = (fileSize / (256 * 192 * 2)).toInt() // Assuming 256x192 resolution, 2 bytes per pixel
        val sampleInterval = (estimatedFrames / 100).coerceAtLeast(1) // Sample every N frames
        
        File(file.path).inputStream().use { input ->
            var frameIndex = 0
            val frameSize = 256 * 192 * 2
            
            while (input.available() >= frameSize && frameIndex < 100) {
                if (frameIndex % sampleInterval == 0) {
                    val frameData = ByteArray(frameSize)
                    input.read(frameData)
                    
                    // Sample temperature values from frame
                    val sampleSize = 1000.coerceAtMost(frameSize / 2)
                    for (i in 0 until sampleSize step 2) {
                        val tempValue = ((frameData[i].toInt() and 0xFF) or ((frameData[i + 1].toInt() and 0xFF) shl 8)).toDouble() / 100.0
                        temperatureValues.add(tempValue)
                    }
                    
                    val avgTemp = temperatureValues.takeLast(sampleSize / 2).average()
                    dataPoints.add(DataPoint(
                        timestamp = System.currentTimeMillis() + frameIndex * 33, // Assume 30fps
                        type = "THERMAL_YUV",
                        value = avgTemp,
                        quality = 1.0,
                        syncStatus = "GOOD"
                    ))
                } else {
                    input.skip(frameSize.toLong())
                }
                frameIndex++
            }
        }
    }
    
    private fun analyzeThermalARGBData(file: com.multimodal.capture.data.SessionFile, dataPoints: MutableList<DataPoint>, temperatureValues: MutableList<Double>) {
        // ARGB format analysis - extract from color values
        val fileSize = file.size
        val estimatedFrames = (fileSize / (256 * 192 * 4)).toInt() // 4 bytes per ARGB pixel
        val sampleInterval = (estimatedFrames / 50).coerceAtLeast(1)
        
        File(file.path).inputStream().use { input ->
            var frameIndex = 0
            val frameSize = 256 * 192 * 4
            
            while (input.available() >= frameSize && frameIndex < 50) {
                if (frameIndex % sampleInterval == 0) {
                    val frameData = ByteArray(frameSize)
                    input.read(frameData)
                    
                    // Extract temperature from color mapping (simplified)
                    for (i in frameData.indices step 16) { // Sample every 4th pixel
                        if (i + 3 < frameData.size) {
                            val r = frameData[i + 1].toInt() and 0xFF
                            val g = frameData[i + 2].toInt() and 0xFF
                            val b = frameData[i + 3].toInt() and 0xFF
                            
                            // Convert RGB to approximate temperature (simplified mapping)
                            val tempValue = (r * 0.3 + g * 0.6 + b * 0.1) / 2.55 // Scale to 0-100
                            temperatureValues.add(tempValue)
                        }
                    }
                    
                    val avgTemp = temperatureValues.takeLast(frameSize / 16).average()
                    dataPoints.add(DataPoint(
                        timestamp = System.currentTimeMillis() + frameIndex * 33,
                        type = "THERMAL_ARGB",
                        value = avgTemp,
                        quality = 1.0,
                        syncStatus = "GOOD"
                    ))
                } else {
                    input.skip(frameSize.toLong())
                }
                frameIndex++
            }
        }
    }
    
    private fun analyzeGenericThermalData(file: com.multimodal.capture.data.SessionFile, dataPoints: MutableList<DataPoint>, temperatureValues: MutableList<Double>) {
        // Generic binary data analysis
        File(file.path).inputStream().use { input ->
            val buffer = ByteArray(1024)
            var bytesRead = 0
            var sampleCount = 0
            
            while (input.read(buffer).also { bytesRead = it } != -1 && sampleCount < 1000) {
                for (i in 0 until bytesRead step 2) {
                    if (i + 1 < bytesRead) {
                        val value = ((buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)).toDouble() / 100.0
                        temperatureValues.add(value)
                        sampleCount++
                    }
                }
            }
            
            if (temperatureValues.isNotEmpty()) {
                dataPoints.add(DataPoint(
                    timestamp = file.lastModified.time,
                    type = "THERMAL_GENERIC",
                    value = temperatureValues.average(),
                    quality = 0.8, // Lower quality for generic analysis
                    syncStatus = "FAIR"
                ))
            }
        }
    }
    
    private fun calculateThermalQuality(temperatureValues: List<Double>): Double {
        if (temperatureValues.isEmpty()) return 0.0
        
        val validRange = temperatureValues.count { it in -40.0..150.0 } // Reasonable temperature range
        val stabilityScore = if (temperatureValues.size > 1) {
            val variance = temperatureValues.map { (it - temperatureValues.average()).let { diff -> diff * diff } }.average()
            (1.0 / (1.0 + variance / 100.0)).coerceIn(0.0, 1.0)
        } else 1.0
        
        return (validRange.toDouble() / temperatureValues.size) * stabilityScore
    }
    
    private fun readLong(input: java.io.InputStream): Long {
        val bytes = ByteArray(8)
        input.read(bytes)
        return bytes.foldIndexed(0L) { index, acc, byte -> acc or ((byte.toLong() and 0xFF) shl (index * 8)) }
    }
    
    private fun readInt(input: java.io.InputStream): Int {
        val bytes = ByteArray(4)
        input.read(bytes)
        return bytes.foldIndexed(0) { index, acc, byte -> acc or ((byte.toInt() and 0xFF) shl (index * 8)) }
    }
    
    private fun analyzeCameraData(file: com.multimodal.capture.data.SessionFile): ModalityData {
        // TODO: Implement camera data analysis
        // See backlog.md - "Camera Data Analysis" (Priority: Medium, Complexity: High)
        return ModalityData()
    }
    
    private fun analyzeGSRData(file: com.multimodal.capture.data.SessionFile): ModalityData {
        return try {
            val dataPoints = mutableListOf<DataPoint>()
            val gsrValues = mutableListOf<Double>()
            
            File(file.path).bufferedReader().use { reader ->
                var isFirstLine = true
                reader.forEachLine { line ->
                    if (isFirstLine) {
                        isFirstLine = false
                        return@forEachLine // Skip header
                    }
                    
                    val parts = line.split(",")
                    if (parts.size >= 4) { // timestamp, gsr_value, heart_rate, packet_reception_rate
                        val timestamp = parts[0].toLongOrNull() ?: 0L
                        val gsrValue = parts[1].toDoubleOrNull() ?: 0.0
                        val heartRate = parts[2].toIntOrNull() ?: 0
                        val prr = parts[3].toDoubleOrNull() ?: 0.0
                        
                        gsrValues.add(gsrValue)
                        dataPoints.add(DataPoint(
                            timestamp = timestamp,
                            type = "GSR",
                            value = gsrValue,
                            quality = prr / 100.0, // Convert PRR to quality score
                            syncStatus = if (prr > 80) "GOOD" else if (prr > 50) "FAIR" else "POOR"
                        ))
                    }
                }
            }
            
            if (gsrValues.isEmpty()) {
                return ModalityData()
            }
            
            val minValue = gsrValues.minOrNull() ?: 0.0
            val maxValue = gsrValues.maxOrNull() ?: 0.0
            val meanValue = gsrValues.average()
            val averageQuality = dataPoints.map { it.quality }.average()
            val syncQuality = dataPoints.count { it.syncStatus == "GOOD" }.toDouble() / dataPoints.size
            
            ModalityData(
                dataPoints = dataPoints,
                averageQuality = averageQuality,
                syncQuality = syncQuality,
                minValue = minValue,
                maxValue = maxValue,
                meanValue = meanValue
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze GSR data")
            ModalityData()
        }
    }
    
    private fun analyzeAudioData(file: com.multimodal.capture.data.SessionFile): ModalityData {
        // TODO: Implement audio data analysis
        // See backlog.md - "Audio Data Analysis" (Priority: Medium, Complexity: High)
        return ModalityData()
    }
    
    private fun calculateCorrelations(modalityData: Map<String, ModalityData>): Map<String, Double> {
        // TODO: Implement cross-modal correlation analysis
        // See backlog.md - "Cross-Modal Correlation Analysis" (Priority: High, Complexity: Very High)
        return emptyMap()
    }
    
    private fun generateQualityMetrics(sessionFolder: SessionFolder, modalityData: Map<String, ModalityData>): Map<String, Double> {
        // TODO: Implement quality metrics calculation
        // See backlog.md - "Quality Metrics Generation" (Priority: Medium, Complexity: Medium)
        return emptyMap()
    }
}

/**
 * Export format options
 */
enum class ExportFormat {
    CSV,
    JSON,
    SYNCHRONIZED_CSV,
    ANALYSIS_REPORT
}

/**
 * Export result sealed class
 */
sealed class ExportResult {
    data class Success(val exportedFiles: Map<ExportFormat, File>) : ExportResult()
    data class Error(val message: String) : ExportResult()
}

/**
 * Session analysis data structure
 */
data class SessionAnalysis(
    val sessionName: String,
    val totalDuration: Long,
    val modalityData: MutableMap<String, ModalityData>,
    val correlationData: MutableMap<String, Double>,
    val qualityMetrics: MutableMap<String, Double>
)

/**
 * Modality-specific data analysis
 */
data class ModalityData(
    val dataPoints: List<DataPoint> = emptyList(),
    val averageQuality: Double = 0.0,
    val syncQuality: Double = 0.0,
    val minValue: Double = 0.0,
    val maxValue: Double = 0.0,
    val meanValue: Double = 0.0
)

/**
 * Individual data point
 */
data class DataPoint(
    val timestamp: Long,
    val type: String,
    val value: Double,
    val quality: Double,
    val syncStatus: String
)