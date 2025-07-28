# Phase 2: MVVM + Clean Architecture Implementation

## Architecture Diagram

```mermaid
graph TB
    subgraph "UI Layer"
        MCF[MainCaptureFragment<br/>- Observes DeviceState<br/>- Pre-flight Guard<br/>- Recording Controls]
        DMF[DeviceManagementFragment<br/>- Device List<br/>- Connection Management]
        SF[SessionsFragment<br/>- Session Browsing]
        SIV[StatusIndicatorView<br/>- DeviceState Display<br/>- Color-coded Status]
    end
    
    subgraph "ViewModel Layer (Service Bridge)"
        MVM[MainViewModel<br/>- Service Bridge<br/>- DeviceState to Status String<br/>- Delegates to Service<br/>- Backward Compatibility]
        
        subgraph "DeviceState LiveData Bridge"
            CLS[cameraState: LiveData DeviceState]
            TLS[thermalState: LiveData DeviceState]
            GLS[gsrState: LiveData DeviceState]
        end
        
        subgraph "Legacy Status Strings"
            CSS[cameraStatus: LiveData String]
            TSS[thermalStatus: LiveData String]
            GSS[gsrStatus: LiveData String]
        end
    end
    
    subgraph "Service Layer (Central Orchestrator)"
        RS[RecordingService<br/>- Single Source of Truth<br/>- Hardware Manager Owner<br/>- DeviceStateCallback Implementation]
        
        subgraph "Unified Device State Machine"
            CDS[_cameraState: MutableLiveData DeviceState]
            TDS[_thermalState: MutableLiveData DeviceState]
            GDS[_gsrState: MutableLiveData DeviceState]
        end
        
        subgraph "Centralized Recording Control"
            SRS[startRecordingSession<br/>- Synchronized Start<br/>- All Managers]
            STRS[stopRecordingSession<br/>- Synchronized Stop<br/>- All Managers]
        end
    end
    
    subgraph "Hardware Manager Layer"
        CM[CameraManager<br/>- Status Callbacks<br/>- DeviceState Reports]
        TCM[ThermalCameraManager<br/>- Status Callbacks<br/>- DeviceState Reports]
        GSM[GSRSensorManager<br/>- Status Callbacks<br/>- DeviceState Reports]
        NM[NetworkManager<br/>- Unified Communication]
    end
    
    subgraph "DeviceState Enum"
        DS[DeviceState<br/>- DISCONNECTED<br/>- PERMISSION_REQUIRED<br/>- CONNECTING<br/>- READY<br/>- STREAMING<br/>- ERROR]
        
        DSC[DeviceStateCallback<br/>onDeviceStateChanged<br/>- Type-safe State Reports<br/>- Central State Management]
    end
    
    %% UI to ViewModel connections
    MCF --> MVM
    DMF --> MVM
    SF --> MVM
    MCF --> SIV
    DMF --> SIV
    
    %% ViewModel bridge connections
    MVM --> CLS
    MVM --> TLS
    MVM --> GLS
    CLS --> CSS
    TLS --> TSS
    GLS --> GSS
    
    %% Service binding
    MVM -.->|Service Binding| RS
    
    %% Service to DeviceState connections
    RS --> CDS
    RS --> TDS
    RS --> GDS
    CDS --> CLS
    TDS --> TLS
    GDS --> GLS
    
    %% Service owns managers
    RS --> CM
    RS --> TCM
    RS --> GSM
    RS --> NM
    
    %% Centralized recording control
    RS --> SRS
    RS --> STRS
    SRS --> CM
    SRS --> TCM
    SRS --> GSM
    STRS --> CM
    STRS --> TCM
    STRS --> GSM
    
    %% DeviceState callback flow
    CM -.->|Status Callback| DSC
    TCM -.->|Status Callback| DSC
    GSM -.->|Status Callback| DSC
    DSC --> RS
    DSC --> DS
    
    %% NetworkManager integration
    NM --> GSM
    
    classDef ui fill:#e1f5fe
    classDef viewmodel fill:#fff3e0
    classDef service fill:#e8f5e8
    classDef manager fill:#fce4ec
    classDef state fill:#f3e5f5
    
    class MCF,DMF,SF,SIV ui
    class MVM,CLS,TLS,GLS,CSS,TSS,GSS viewmodel
    class RS,CDS,TDS,GDS,SRS,STRS service
    class CM,TCM,GSM,NM manager
    class DS,DSC state
```

## Key Architectural Benefits

### 1. Single Source of Truth
- **RecordingService** owns all hardware managers
- Centralized state management prevents inconsistencies
- Synchronized recording operations across all sensors

### 2. Type-Safe State Management
- **DeviceState enum** replaces ambiguous string statuses
- **DeviceStateCallback** interface ensures consistent reporting
- Robust state machine with clear state transitions

### 3. Clean Separation of Concerns
- **UI Layer**: Pure presentation logic, observes state
- **ViewModel Layer**: Service bridge, maintains compatibility
- **Service Layer**: Hardware orchestration, business logic
- **Manager Layer**: Hardware-specific implementations

### 4. Backward Compatibility
- Legacy status strings maintained for existing UI
- Automatic conversion between DeviceState and strings
- Gradual migration path for UI components

### 5. Improved Reliability
- Centralized recording control prevents race conditions
- Service-based architecture survives configuration changes
- Proper lifecycle management for all hardware resources

## Implementation Status
- ✅ DeviceState enum and callback interface created
- ✅ RecordingService transformed to central orchestrator
- ✅ Unified device state machine implemented
- ✅ MainViewModel refactored as service bridge
- ✅ Hardware manager delegation completed
- ✅ Centralized recording control methods added
- ✅ DeviceState observer patterns established
- ✅ Status string mapping for compatibility