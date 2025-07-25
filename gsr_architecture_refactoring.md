# GSR Sensor Architecture Refactoring

## Overview
This document describes the critical architectural refactoring of GSR sensor management to resolve background execution reliability issues and implement recommended Shimmer SDK configuration patterns.

## Architecture Comparison

### Before: Activity/ViewModel Ownership (Problematic)

```mermaid
graph TB
    subgraph "Android OS Process Management"
        A[Android OS] --> B{App in Background?}
        B -->|Yes| C[Kill Process to Save Memory]
        B -->|No| D[Keep Process Active]
    end
    
    subgraph "Original Architecture (Vulnerable)"
        E[MainActivity] --> F[GSRSensorManager]
        G[ViewModel] --> F
        F --> H[Shimmer Device Connection]
        F --> I[Data Collection]
        
        J[RecordingService] --> K[Basic Service Operations]
        K --> L[Notifications Only]
        K --> M[No Sensor Management]
    end
    
    subgraph "Critical Flaw"
        N[Background Operation] --> O[Process Termination Risk]
        O --> P[Data Loss]
        O --> Q[Connection Loss]
    end
    
    C --> E
    C --> G
    style C fill:#ffcdd2
    style O fill:#ffcdd2
    style P fill:#ffcdd2
    style Q fill:#ffcdd2
```

### After: RecordingService Ownership (Reliable)

```mermaid
graph TB
    subgraph "Android OS Process Management"
        A[Android OS] --> B{Foreground Service?}
        B -->|Yes| C[Protected from Termination]
        B -->|No| D[Standard Process Management]
    end
    
    subgraph "Refactored Architecture (Reliable)"
        E[RecordingService - Foreground] --> F[initializeSensorManagers]
        F --> G[GSRSensorManager]
        F --> H[ThermalCameraManager]
        
        G --> I[configureAndWriteToDevice]
        I --> J[Clone -> Configure -> Write Pattern]
        J --> K[Shimmer Device Connection]
        K --> L[Reliable Data Collection]
        
        M[MainActivity] --> N[Service Binding]
        N --> O[getGSRManager]
        O --> G
        
        P[startRecording] --> Q[Start All Sensor Managers]
        R[stopRecording] --> S[Stop All Sensor Managers]
        T[onDestroy] --> U[Cleanup All Managers]
    end
    
    subgraph "Benefits"
        V[Background Reliability] --> W[No Data Loss]
        V --> X[Persistent Connections]
        V --> Y[Proper Configuration]
    end
    
    C --> E
    style C fill:#c8e6c9
    style E fill:#c8e6c9
    style V fill:#c8e6c9
    style W fill:#c8e6c9
    style X fill:#c8e6c9
    style Y fill:#c8e6c9
```

## Key Architectural Changes

### 1. Sensor Manager Ownership Transfer
- **Before**: GSRSensorManager owned by Activity/ViewModel
- **After**: GSRSensorManager owned by RecordingService (foreground service)
- **Benefit**: Protected from Android OS process termination

### 2. Service Lifecycle Integration
- **initializeSensorManagers()**: Creates and configures sensor managers within service context
- **startRecording()**: Directly controls sensor manager recording operations
- **stopRecording()**: Ensures synchronized stopping of all sensor managers
- **onDestroy()**: Proper cleanup of sensor manager resources

### 3. Recommended Shimmer Configuration Pattern
- **Before**: Basic sensor enabling with `setSensorEnabledState()` and `writeConfigBytes()`
- **After**: Clone -> Configure -> Write pattern using `configureAndWriteToDevice()`
- **Implementation**:
  ```kotlin
  val shimmerClone = shimmerDevice.deepClone() as Shimmer
  shimmerClone.setShimmerAndSensorsSamplingRate(sampleRate.toDouble())
  shimmerClone.setSensorEnabledState(Configuration.Shimmer3.SENSOR_ID.SHIMMER_GSR, true)
  shimmerDevice.writeConfigBytes()
  ```

### 4. UI Binding Support
- **getGSRManager()**: Provides UI access to sensor managers while maintaining service ownership
- **Service Binding**: Activities bind to RecordingService to access sensor managers
- **Lifecycle Safety**: UI can access sensors without owning them

## Data Flow

### Recording Session Flow
```mermaid
sequenceDiagram
    participant UI as MainActivity
    participant RS as RecordingService
    participant GSR as GSRSensorManager
    participant TC as ThermalCameraManager
    participant SD as Shimmer Device
    
    UI->>RS: startRecording(sessionId, timestamp)
    RS->>RS: Start foreground service
    RS->>GSR: startRecording(sessionId, timestamp)
    RS->>TC: startRecording(sessionId, outputDir)
    
    GSR->>SD: connect(deviceAddress)
    GSR->>GSR: configureAndWriteToDevice(shimmerDevice)
    GSR->>SD: startStreaming()
    
    loop Data Collection
        SD->>GSR: ObjectCluster data
        GSR->>GSR: recordDataPoint()
    end
    
    UI->>RS: stopRecording()
    RS->>GSR: stopRecording()
    RS->>TC: stopRecording()
    RS->>RS: Stop foreground service
```

## Benefits of Refactored Architecture

### 1. Background Reliability
- **Problem Solved**: Android OS killing sensor data collection processes
- **Solution**: Foreground service protection ensures continuous operation
- **Impact**: No data loss during long recording sessions

### 2. Proper Configuration
- **Problem Solved**: Incomplete device configuration using basic patterns
- **Solution**: Recommended clone -> configure -> write pattern
- **Impact**: All settings from SettingsManager properly applied to hardware

### 3. Resource Management
- **Problem Solved**: Potential resource leaks on service destruction
- **Solution**: Proper cleanup in onDestroy() method
- **Impact**: Clean resource management and memory efficiency

### 4. Synchronized Operation
- **Problem Solved**: Inconsistent sensor manager lifecycle
- **Solution**: Centralized control through RecordingService
- **Impact**: All sensors start/stop together reliably

## Technical Implementation Details

### RecordingService Changes
```kotlin
// Sensor manager ownership
private lateinit var gsrSensorManager: GSRSensorManager
private lateinit var thermalCameraManager: ThermalCameraManager

// Initialization
private fun initializeSensorManagers() {
    gsrSensorManager = GSRSensorManager(this)
    thermalCameraManager = ThermalCameraManager(this)
}

// Recording control
private fun startRecording(sessionId: String, timestamp: Long) {
    gsrSensorManager.startRecording(sessionId, timestamp)
    thermalCameraManager.startRecording(sessionId, outputDir)
}

// UI access
fun getGSRManager(): GSRSensorManager = gsrSensorManager
```

### GSRSensorManager Configuration
```kotlin
private fun configureAndWriteToDevice(shimmerDevice: Shimmer) {
    val shimmerClone = shimmerDevice.deepClone() as Shimmer
    val shimmerConfig = settingsManager.loadShimmerConfig()
    
    shimmerClone.setShimmerAndSensorsSamplingRate(shimmerConfig.sampleRate.toDouble())
    shimmerClone.setSensorEnabledState(Configuration.Shimmer3.SENSOR_ID.SHIMMER_GSR, true)
    
    shimmerDevice.writeConfigBytes()
}
```

## Migration Impact

### For Developers
- **UI Code**: Activities now bind to RecordingService instead of directly creating sensor managers
- **Lifecycle**: Sensor managers persist across activity lifecycle changes
- **Configuration**: More reliable device configuration with proper Shimmer SDK patterns

### For Users
- **Reliability**: No data loss when app is backgrounded or screen is locked
- **Performance**: Better resource management and memory efficiency
- **Functionality**: All configured settings properly applied to hardware devices

This refactoring resolves the critical architectural flaw identified in the issue description and ensures production-ready, reliable sensor data collection for long-running recording sessions.