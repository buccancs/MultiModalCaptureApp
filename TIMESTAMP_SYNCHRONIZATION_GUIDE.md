# Timestamp Synchronization Guide

## Overview

This guide provides comprehensive documentation for the enhanced timestamp synchronization system implemented across all capture modalities (IR thermal camera, RGB camera, audio recording, and GSR sensor) to ensure accurate alignment with the computer's clock.

## Architecture

### Core Components

#### 1. TimestampManager
- **Location**: `app/src/main/java/com/multimodal/capture/utils/TimestampManager.kt`
- **Purpose**: Provides unified timestamping across all capture modules
- **Features**:
  - Monotonic clock-based timestamps using `SystemClock.elapsedRealtimeNanos()`
  - Session-based timing with common start reference
  - Sub-millisecond precision for accurate synchronization
  - Conversion utilities for different time formats

#### 2. NetworkManager Time Synchronization
- **Location**: `app/src/main/java/com/multimodal/capture/network/NetworkManager.kt`
- **Purpose**: Implements NTP-style clock synchronization with PC
- **Features**:
  - Enhanced sync ping protocol with detailed timestamp exchange
  - Clock offset calculation and quality assessment
  - Real-time sync quality monitoring (0-100 score)
  - Automatic resynchronization based on quality thresholds

#### 3. TimestampValidator
- **Location**: `app/src/main/java/com/multimodal/capture/utils/TimestampValidator.kt`
- **Purpose**: Comprehensive validation and monitoring of timestamp synchronization
- **Features**:
  - Real-time sync quality monitoring
  - Timestamp drift detection and correction
  - Quality reporting and recommendations
  - Export capabilities for analysis

### Enhanced Capture Managers

#### 1. ThermalCameraManager
- **Enhanced Features**:
  - PC-corrected timestamp recording for each thermal frame
  - Dual timestamp format: local + PC-corrected
  - Sync quality metadata embedded in frame data
  - Enhanced frame data structure with timestamp information

#### 2. CameraManager (RGB Video)
- **Enhanced Features**:
  - PC-corrected start timestamp calculation
  - Comprehensive metadata file generation
  - Synchronization quality assessment
  - Device and camera specification metadata

#### 3. AudioRecorderManager
- **Enhanced Features**:
  - PC-corrected timestamp for audio chunks
  - Audio-specific metadata file generation
  - Real-time sync quality logging
  - Enhanced timestamp information for each audio segment

## Implementation Guide

### Basic Usage

#### 1. Initialize Components

```kotlin
// Initialize core components
val timestampManager = TimestampManager()
val networkManager = NetworkManager(context)
val timestampValidator = TimestampValidator(context)

// Initialize capture managers with NetworkManager integration
val cameraManager = CameraManager(context, networkManager)
val thermalCameraManager = ThermalCameraManager(context, networkManager)
val audioRecorderManager = AudioRecorderManager(context, networkManager)
```

#### 2. Start Recording Session

```kotlin
// Set up session
val sessionId = "session_${System.currentTimeMillis()}"
val startTimestamp = SystemClock.elapsedRealtimeNanos()

// Start timestamp validation monitoring
timestampValidator.startMonitoring(sessionId)

// Start recording on all modalities
cameraManager.startRecording(sessionId, startTimestamp)
thermalCameraManager.startRecording(sessionId, startTimestamp)
audioRecorderManager.startRecording(sessionId, startTimestamp)
```

#### 3. Monitor Synchronization Quality

```kotlin
// Validate synchronization for each modality
val cameraValidation = timestampValidator.validateModalitySync(
    modalityName = "rgb_camera",
    localTimestamp = timestampManager.getCurrentTimestamp(),
    pcCorrectedTimestamp = networkManager.getSynchronizedTimestamp(),
    syncQuality = networkManager.getSyncQuality(),
    clockOffset = networkManager.getClockOffset()
)

// Check validation results
if (!cameraValidation.isValid) {
    Log.w("Sync", "Camera sync issues: ${cameraValidation.issues}")
}
```

#### 4. Stop Recording and Generate Reports

```kotlin
// Stop recording
cameraManager.stopRecording()
thermalCameraManager.stopRecording()
audioRecorderManager.stopRecording()

// Stop monitoring and get summary
val validationSummary = timestampValidator.stopMonitoring()
validationSummary?.let { summary ->
    Log.i("Sync", "Overall quality: ${summary.overallQuality}%")
    Log.i("Sync", "Recommendations: ${summary.recommendations}")
}

// Export validation data for analysis
timestampValidator.exportValidationData(sessionId)
```

### Advanced Usage

#### Real-time Sync Quality Monitoring

```kotlin
class SyncMonitoringService {
    private val timestampValidator = TimestampValidator(context)
    private val monitoringJob = CoroutineScope(Dispatchers.IO)
    
    fun startContinuousMonitoring(sessionId: String) {
        timestampValidator.startMonitoring(sessionId)
        
        monitoringJob.launch {
            while (isActive) {
                // Get current validation status
                val status = timestampValidator.getCurrentValidationStatus()
                
                // Check for issues
                status.values.forEach { result ->
                    if (!result.isValid) {
                        handleSyncIssue(result)
                    }
                }
                
                delay(1000) // Check every second
            }
        }
    }
    
    private fun handleSyncIssue(result: ValidationResult) {
        when {
            result.syncQuality < 60 -> {
                // Trigger resynchronization
                networkManager.initiateSyncPing()
            }
            Math.abs(result.clockOffset) > 50_000_000L -> {
                // Clock offset too high
                Log.w("Sync", "High clock offset for ${result.modalityName}")
            }
        }
    }
}
```

#### Custom Timestamp Validation

```kotlin
class CustomTimestampValidator(context: Context) : TimestampValidator(context) {
    
    fun validateCustomThresholds(
        modalityName: String,
        localTimestamp: Long,
        pcCorrectedTimestamp: Long,
        customThresholds: ValidationThresholds
    ): ValidationResult {
        
        // Apply custom validation logic
        val timestampDiff = Math.abs(pcCorrectedTimestamp - localTimestamp)
        val isValid = timestampDiff <= customThresholds.maxTimestampDifference
        
        return ValidationResult(
            modalityName = modalityName,
            isValid = isValid,
            syncQuality = networkManager.getSyncQuality(),
            clockOffset = networkManager.getClockOffset(),
            timestampDifference = timestampDiff,
            issues = if (!isValid) listOf("Custom threshold exceeded") else emptyList(),
            timestamp = System.currentTimeMillis()
        )
    }
}

data class ValidationThresholds(
    val maxTimestampDifference: Long,
    val minSyncQuality: Int,
    val maxClockOffset: Long
)
```

## Data Formats

### Metadata Files

Each capture modality generates a metadata file with timestamp synchronization information:

#### RGB Camera Metadata (`{sessionId}_rgb_video_metadata.json`)

```json
{
  "sessionId": "session_1234567890",
  "recordingType": "rgb_video",
  "timestamps": {
    "localStartTime": 1234567890123456789,
    "pcCorrectedStartTime": 1234567890123456801,
    "systemTimeMillis": 1234567890123,
    "elapsedRealtimeNanos": 1234567890123456789
  },
  "synchronization": {
    "syncQuality": 85,
    "clockOffset": 12,
    "syncTimestamp": 1234567890123,
    "isSyncAcceptable": true
  },
  "device": {
    "model": "Pixel 6",
    "manufacturer": "Google",
    "androidVersion": "13"
  },
  "camera": {
    "resolution": "1920x1080",
    "frameRate": "30fps",
    "codec": "H.264"
  }
}
```

#### Audio Metadata (`{sessionId}_audio_metadata.json`)

```json
{
  "sessionId": "session_1234567890",
  "recordingType": "audio",
  "timestamps": {
    "localStartTime": 1234567890123456789,
    "pcCorrectedStartTime": 1234567890123456801,
    "systemTimeMillis": 1234567890123,
    "elapsedRealtimeNanos": 1234567890123456789
  },
  "synchronization": {
    "syncQuality": 85,
    "clockOffset": 12,
    "syncTimestamp": 1234567890123,
    "isSyncAcceptable": true
  },
  "device": {
    "model": "Pixel 6",
    "manufacturer": "Google",
    "androidVersion": "13"
  },
  "audio": {
    "sampleRate": 44100,
    "channels": 2,
    "bitDepth": 16,
    "format": "WAV",
    "bufferSize": 8192
  }
}
```

#### Thermal Camera Data Format

Thermal frames include enhanced timestamp information:

```
Frame Structure:
- Local timestamp (8 bytes)
- PC-corrected timestamp (8 bytes)
- Sync quality (4 bytes)
- Frame size (4 bytes)
- Frame data (variable)
```

### Validation Reports

#### Timestamp Quality Report (`{sessionId}_timestamp_quality_{timestamp}.json`)

```json
{
  "sessionId": "session_1234567890",
  "timestamp": 1234567890123,
  "modalityResults": [
    {
      "modalityName": "rgb_camera",
      "isValid": true,
      "syncQuality": 85,
      "clockOffset": 12,
      "timestampDifference": 12000000,
      "issues": [],
      "timestamp": 1234567890123
    }
  ],
  "overallQuality": 85,
  "recommendations": [
    "Timestamp synchronization quality is acceptable"
  ]
}
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Low Sync Quality (< 60)

**Symptoms:**
- Sync quality scores consistently below 60
- Large timestamp differences between modalities

**Solutions:**
```kotlin
// Increase sync frequency
networkManager.initiateSyncPing()

// Check network stability
if (!networkManager.isConnected()) {
    // Reconnect to PC
    networkManager.reconnect()
}

// Monitor network conditions
val networkMetrics = networkManager.getNetworkMetrics()
if (networkMetrics["latency"] as? Long ?: 0L > 100) {
    Log.w("Sync", "High network latency affecting sync quality")
}
```

#### 2. High Clock Offset (> 50ms)

**Symptoms:**
- Clock offset consistently above 50ms
- Timestamps drift over time

**Solutions:**
```kotlin
// Force resynchronization
networkManager.initiateSyncPing()

// Check for systematic clock drift
val driftHistory = timestampValidator.getTimestampDriftHistory()
driftHistory.forEach { (modality, drifts) ->
    val avgDrift = drifts.map { it.driftRate }.average()
    if (Math.abs(avgDrift) > 10_000_000) { // 10ms/min
        Log.w("Sync", "Excessive drift detected for $modality: ${avgDrift}ns/min")
    }
}
```

#### 3. Timestamp Validation Failures

**Symptoms:**
- ValidationResult.isValid returns false
- Multiple issues reported in validation results

**Solutions:**
```kotlin
// Analyze specific issues
val validationStatus = timestampValidator.getCurrentValidationStatus()
validationStatus.forEach { (modality, result) ->
    if (!result.isValid) {
        result.issues.forEach { issue ->
            when {
                issue.contains("Low sync quality") -> {
                    // Improve network conditions
                    improveNetworkStability()
                }
                issue.contains("High clock offset") -> {
                    // Increase sync frequency
                    increaseSyncFrequency()
                }
                issue.contains("Large timestamp difference") -> {
                    // Check for processing delays
                    optimizeProcessingPipeline()
                }
            }
        }
    }
}
```

#### 4. Metadata File Generation Issues

**Symptoms:**
- Missing metadata files
- Incomplete timestamp information

**Solutions:**
```kotlin
// Verify NetworkManager integration
if (networkManager == null) {
    Log.e("Sync", "NetworkManager not provided to capture manager")
    // Reinitialize with NetworkManager
    val cameraManager = CameraManager(context, networkManager)
}

// Check file permissions
val outputDir = File(context.getExternalFilesDir(null), "recordings")
if (!outputDir.exists()) {
    outputDir.mkdirs()
}

// Verify sync data availability
val syncQuality = networkManager.getSyncQuality()
val clockOffset = networkManager.getClockOffset()
if (syncQuality == 0 && clockOffset == 0L) {
    Log.w("Sync", "No sync data available - ensure PC connection is established")
}
```

### Performance Optimization

#### 1. Reduce Sync Overhead

```kotlin
// Optimize sync frequency based on quality
class AdaptiveSyncManager(private val networkManager: NetworkManager) {
    
    fun optimizeSyncFrequency() {
        val currentQuality = networkManager.getSyncQuality()
        
        val syncInterval = when {
            currentQuality >= 90 -> 30000L // 30 seconds for excellent quality
            currentQuality >= 70 -> 15000L // 15 seconds for good quality
            currentQuality >= 50 -> 5000L  // 5 seconds for fair quality
            else -> 1000L                  // 1 second for poor quality
        }
        
        scheduleSyncPing(syncInterval)
    }
}
```

#### 2. Optimize Validation Monitoring

```kotlin
// Reduce validation overhead for stable connections
class OptimizedTimestampValidator(context: Context) : TimestampValidator(context) {
    
    fun startAdaptiveMonitoring(sessionId: String) {
        val monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            var monitoringInterval = 5000L // Start with 5 seconds
            
            while (isActive) {
                val overallQuality = calculateOverallQuality()
                
                // Adapt monitoring frequency based on quality
                monitoringInterval = when {
                    overallQuality >= 90 -> 30000L // 30 seconds
                    overallQuality >= 70 -> 15000L // 15 seconds
                    overallQuality >= 50 -> 5000L  // 5 seconds
                    else -> 1000L                  // 1 second
                }
                
                monitorTimestampQuality(sessionId)
                delay(monitoringInterval)
            }
        }
    }
}
```

## Best Practices

### 1. Initialization Order

```kotlin
// Correct initialization sequence
val networkManager = NetworkManager(context)
val timestampManager = TimestampManager()
val timestampValidator = TimestampValidator(context)

// Wait for PC connection before starting recording
networkManager.setConnectionStateCallback { isConnected, message ->
    if (isConnected) {
        // Perform initial sync
        networkManager.initiateSyncPing()
        
        // Wait for sync quality to stabilize
        Handler(Looper.getMainLooper()).postDelayed({
            if (networkManager.getSyncQuality() >= 60) {
                startRecordingSession()
            }
        }, 2000)
    }
}
```

### 2. Error Handling

```kotlin
class RobustRecordingManager {
    
    fun startRecordingWithValidation(sessionId: String) {
        try {
            // Pre-recording validation
            val preValidation = validateSystemReadiness()
            if (!preValidation.isReady) {
                throw IllegalStateException("System not ready: ${preValidation.issues}")
            }
            
            // Start recording with error handling
            val startTimestamp = SystemClock.elapsedRealtimeNanos()
            
            cameraManager.startRecording(sessionId, startTimestamp)
            thermalCameraManager.startRecording(sessionId, startTimestamp)
            audioRecorderManager.startRecording(sessionId, startTimestamp)
            
            // Post-recording validation
            Handler(Looper.getMainLooper()).postDelayed({
                validateRecordingStart(sessionId)
            }, 1000)
            
        } catch (e: Exception) {
            Log.e("Recording", "Failed to start recording", e)
            stopAllRecording()
            throw e
        }
    }
    
    private fun validateSystemReadiness(): SystemReadiness {
        val issues = mutableListOf<String>()
        
        if (!networkManager.isConnected()) {
            issues.add("PC not connected")
        }
        
        if (networkManager.getSyncQuality() < 60) {
            issues.add("Sync quality too low: ${networkManager.getSyncQuality()}")
        }
        
        if (Math.abs(networkManager.getClockOffset()) > 50_000_000L) {
            issues.add("Clock offset too high: ${networkManager.getClockOffset()}ns")
        }
        
        return SystemReadiness(
            isReady = issues.isEmpty(),
            issues = issues
        )
    }
}

data class SystemReadiness(
    val isReady: Boolean,
    val issues: List<String>
)
```

### 3. Data Analysis Integration

```kotlin
// Post-processing timestamp alignment
class TimestampAlignmentProcessor {
    
    fun alignMultiModalData(sessionId: String): AlignedDataSet {
        // Load metadata files
        val rgbMetadata = loadMetadata("${sessionId}_rgb_video_metadata.json")
        val audioMetadata = loadMetadata("${sessionId}_audio_metadata.json")
        val thermalData = loadThermalData("${sessionId}_thermal_video.raw")
        
        // Extract PC-corrected timestamps
        val rgbStartTime = rgbMetadata.timestamps.pcCorrectedStartTime
        val audioStartTime = audioMetadata.timestamps.pcCorrectedStartTime
        
        // Align all data to common time base
        return AlignedDataSet(
            commonTimeBase = minOf(rgbStartTime, audioStartTime),
            rgbVideoOffset = rgbStartTime - commonTimeBase,
            audioOffset = audioStartTime - commonTimeBase,
            thermalFrames = alignThermalFrames(thermalData, commonTimeBase),
            syncQuality = calculateAlignmentQuality(rgbMetadata, audioMetadata)
        )
    }
}
```

## Conclusion

The enhanced timestamp synchronization system provides comprehensive tools for ensuring accurate multi-modal data alignment. By following this guide and implementing the recommended practices, you can achieve sub-50ms synchronization accuracy across all capture modalities, with real-time monitoring and validation capabilities to ensure data quality throughout the recording process.

For additional support or advanced customization, refer to the source code documentation and the TimestampValidator utility for detailed synchronization metrics and analysis capabilities.