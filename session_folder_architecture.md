# Session Folder View Architecture

## Overview
This document describes the architectural changes made to implement the session folder view functionality in the Multi-Modal Capture Application.

## Architecture Diagram

```mermaid
graph TB
    subgraph "UI Layer"
        MA[MainActivity]
        SFA[SessionFolderActivity]
        SFAdapter[SessionFolderAdapter]
    end
    
    subgraph "Data Layer"
        SF[SessionFolder]
        SFile[SessionFile]
        SFT[SessionFileType]
    end
    
    subgraph "Utils Layer"
        SFM[SessionFolderManager]
    end
    
    subgraph "Storage"
        RD[Recordings Directory]
        subgraph "Session Files"
            AF[Audio Files]
            VF[Video Files]
            GF[GSR Data Files]
            TF[Thermal Files]
            MF[Metadata Files]
        end
    end
    
    subgraph "Existing Capture Managers"
        ARM[AudioRecorderManager]
        CM[CameraManager]
        GSM[GSRSensorManager]
        TCM[ThermalCameraManager]
    end
    
    %% UI Flow
    MA -->|"View Sessions" Button| SFA
    SFA --> SFAdapter
    SFAdapter --> SF
    
    %% Data Management
    SFA --> SFM
    SFM --> RD
    SFM --> SF
    SF --> SFile
    SFile --> SFT
    
    %% File Creation (Existing)
    ARM -->|Creates| AF
    CM -->|Creates| VF
    GSM -->|Creates| GF
    TCM -->|Creates| TF
    ARM -->|Creates| MF
    CM -->|Creates| MF
    GSM -->|Creates| MF
    TCM -->|Creates| MF
    
    %% File Organization
    AF --> RD
    VF --> RD
    GF --> RD
    TF --> RD
    MF --> RD
    
    %% Styling
    classDef newComponent fill:#e1f5fe,stroke:#01579b,stroke-width:2px
    classDef existingComponent fill:#f3e5f5,stroke:#4a148c,stroke-width:1px
    classDef storageComponent fill:#e8f5e8,stroke:#1b5e20,stroke-width:1px
    
    class SFA,SFAdapter,SF,SFile,SFT,SFM newComponent
    class MA,ARM,CM,GSM,TCM existingComponent
    class RD,AF,VF,GF,TF,MF storageComponent
```

## Component Descriptions

### New Components Added

#### UI Layer
- **SessionFolderActivity**: New activity that provides the main interface for browsing session folders
- **SessionFolderAdapter**: RecyclerView adapter that efficiently displays session folders with DiffUtil support

#### Data Layer
- **SessionFolder**: Data class representing a session with metadata (name, date, file count, size)
- **SessionFile**: Data class representing individual files within a session
- **SessionFileType**: Enum categorizing different types of session files (audio, video, GSR, thermal, metadata)

#### Utils Layer
- **SessionFolderManager**: Utility class that scans the recordings directory, groups files by session ID, and provides session management functionality

### Integration Points

#### Navigation
- Added "View Sessions" button to MainActivity
- Implemented Intent-based navigation to SessionFolderActivity
- Added activity declaration to AndroidManifest.xml

#### File Organization
- Sessions are organized by extracting session IDs from filenames
- Files follow the pattern: `{sessionId}_{type}.{extension}`
- All session files are stored in the `recordings` directory under app's external files

#### Data Flow
1. User clicks "View Sessions" in MainActivity
2. SessionFolderActivity launches and initializes SessionFolderManager
3. SessionFolderManager scans recordings directory
4. Files are grouped by session ID and converted to SessionFolder objects
5. SessionFolderAdapter displays the organized sessions in a RecyclerView
6. Users can view session details including file counts and sizes

## Key Features

### Session Organization
- Automatic grouping of files by session ID
- Support for multiple file types per session
- Chronological sorting by creation date

### User Interface
- Material Design cards for session display
- File count and size information
- Empty state handling
- Responsive layout design

### Performance Optimizations
- DiffUtil for efficient RecyclerView updates
- Lazy loading of session data
- Efficient file scanning with error handling

### Extensibility
- Modular design allows easy addition of new file types
- Session management methods ready for future features (delete, export, etc.)
- Clean separation of concerns between UI, data, and utility layers

## Future Enhancements
- Session deletion functionality
- Session export/sharing capabilities
- Detailed file view within sessions
- Session search and filtering
- Session statistics and analytics