# RGB Camera YUV Stage 3 Extraction Architecture

## Overview
This document describes the architectural changes made to implement YUV stage 3 image extraction in the RGB camera module.

## Architecture Diagram

```mermaid
graph TB
    subgraph "CameraManager Class"
        A[CameraManager] --> B[setupCamera]
        B --> C[Preview Use Case]
        B --> D[VideoCapture Use Case]
        B --> E[ImageAnalysis Use Case - NEW]
        
        E --> F[processYuvFrame]
        F --> G{YUV Extraction Enabled?}
        G -->|Yes| H[Extract Frame Metadata]
        G -->|No| I[Close ImageProxy]
        
        H --> J[saveYuvFrameMetadata]
        J --> K[Write JSON Metadata File]
        
        subgraph "YUV State Management"
            L[isYuvExtractionEnabled]
            M[yuvFrameCounter]
            N[yuvOutputDir]
        end
        
        subgraph "Public API Methods - NEW"
            O[setYuvExtractionEnabled]
            P[isYuvExtractionEnabled]
            Q[getYuvFrameCount]
            R[getYuvOutputDirectory]
        end
    end
    
    subgraph "Recording Workflow Integration"
        S[startRecording] --> T[Create YUV Output Directory]
        T --> U[Reset Frame Counter]
        U --> V[Start Video Recording]
        V --> W[Start YUV Processing]
        
        X[stopRecording] --> Y[Stop Video Recording]
        Y --> Z[Cleanup YUV State]
        Z --> AA[Log Frame Count]
    end
    
    subgraph "File System Output"
        BB[Session Directory]
        BB --> CC[RGB Video File]
        BB --> DD[YUV Frames Directory - NEW]
        DD --> EE[Frame Metadata JSON Files]
        EE --> FF[sessionId_yuv_metadata_frameNum_timestamp.json]
    end
    
    subgraph "CameraX Integration"
        GG[ProcessCameraProvider]
        GG --> HH[bindToLifecycle]
        HH --> II[Preview + VideoCapture + ImageAnalysis]
        II --> JJ[YUV_420_888 Format]
        JJ --> KK[STRATEGY_KEEP_ONLY_LATEST]
    end
    
    A --> S
    A --> X
    F --> L
    F --> M
    F --> N
    J --> DD
    W --> E
    
    style E fill:#e1f5fe
    style F fill:#e1f5fe
    style J fill:#e1f5fe
    style O fill:#c8e6c9
    style P fill:#c8e6c9
    style Q fill:#c8e6c9
    style R fill:#c8e6c9
    style DD fill:#fff3e0
    style EE fill:#fff3e0
    style FF fill:#fff3e0
```

## Key Architectural Changes

### 1. ImageAnalysis Use Case Addition
- **Component**: New ImageAnalysis use case in CameraManager
- **Purpose**: Extract YUV frames in real-time during camera operation
- **Configuration**: YUV_420_888 format with STRATEGY_KEEP_ONLY_LATEST backpressure

### 2. YUV Processing Pipeline
- **processYuvFrame()**: Main processing method for YUV frame handling
- **saveYuvFrameMetadata()**: Metadata persistence with JSON format
- **Frame Counter**: Tracks processed frames per session

### 3. State Management
- **isYuvExtractionEnabled**: Runtime control flag
- **yuvFrameCounter**: Session-based frame counting
- **yuvOutputDir**: Session-specific output directory

### 4. Public API Extensions
- **setYuvExtractionEnabled()**: Enable/disable YUV extraction
- **isYuvExtractionEnabled()**: Check current extraction status
- **getYuvFrameCount()**: Get current frame count
- **getYuvOutputDirectory()**: Get output directory path

### 5. Recording Integration
- **Session Management**: YUV directory creation per recording session
- **Lifecycle Integration**: Proper cleanup on recording stop
- **Performance Optimization**: Non-blocking frame processing

## Data Flow

1. **Camera Setup**: ImageAnalysis use case added to existing Preview and VideoCapture
2. **Frame Processing**: YUV frames processed in background thread
3. **Metadata Extraction**: Frame dimensions, timestamp, and format information extracted
4. **File Persistence**: JSON metadata files saved to session-specific directory
5. **Session Management**: Directory creation on recording start, cleanup on stop

## Benefits

- **Non-Intrusive**: Doesn't affect existing video recording functionality
- **Configurable**: Runtime enable/disable capability
- **Performance Optimized**: Uses latest frame strategy to prevent backlog
- **Session Organized**: Metadata organized by recording session
- **Future Ready**: Prepared for full YUV data extraction when experimental APIs stabilize

## Technical Notes

- Uses CameraX ImageAnalysis with YUV_420_888 format
- Handles experimental API requirements with proper annotations
- Maintains cognitive complexity under 15 through method decomposition
- Integrates seamlessly with existing multi-camera preview system
- Verified through comprehensive testing suite