# Networking Architecture Enhancement

## Overview

This document describes the comprehensive Command & Control (C&C) protocol implementation that transforms the basic NetworkService into a robust, production-grade PC communication system with automatic discovery and real-time data streaming capabilities.

## Enhanced Architecture

### Before Enhancement
The original NetworkService had basic functionality but lacked key features for professional PC communication:
- Simple string-based commands
- No automatic discovery
- No real-time data streaming
- Limited error handling

### After Enhancement
The enhanced system provides a complete C&C protocol with:
- Structured JSON-based communication
- UDP automatic discovery
- TCP command server
- Real-time UDP data streaming
- Comprehensive error handling and status reporting

## Architecture Diagram

```mermaid
graph TB
    subgraph "PC Client Application"
        PC[PC Controller]
        PC_UDP[UDP Discovery Client]
        PC_TCP[TCP Command Client]
        PC_STREAM[UDP Streaming Receiver]
    end
    
    subgraph "Android Application"
        subgraph "NetworkService"
            UDP_DISC[UDP Discovery Server<br/>Port 8889]
            TCP_CMD[TCP Command Server<br/>Port 8888]
            CLEANUP[Resource Cleanup]
        end
        
        subgraph "NetworkManager"
            UDP_STREAM[UDP Data Streaming<br/>Port 8890]
            CONN_MGR[Connection Management]
            METRICS[Streaming Statistics]
        end
        
        subgraph "CommandProtocol"
            COMMANDS[Command Classes<br/>START_RECORDING<br/>STOP_RECORDING<br/>GET_STATUS<br/>CONNECT_DEVICE<br/>etc.]
            RESPONSES[Response Classes<br/>Acknowledgment<br/>ErrorResponse<br/>StatusUpdate<br/>etc.]
            DATA_PACKETS[Data Packet Classes<br/>GSR Data<br/>Thermal Data<br/>Camera Data]
        end
        
        subgraph "Sensor Managers"
            GSR_MGR[GSRSensorManager]
            THERMAL_MGR[ThermalCameraManager]
            CAMERA_MGR[CameraManager]
        end
        
        subgraph "RecordingService"
            REC_SERVICE[Recording Control]
        end
    end
    
    %% Discovery Flow
    PC_UDP -->|"DISCOVER_MULTIMODAL_CAPTURE_APP"| UDP_DISC
    UDP_DISC -->|Device Info JSON| PC_UDP
    
    %% Command Flow
    PC_TCP -->|JSON Commands| TCP_CMD
    TCP_CMD -->|Parse Commands| COMMANDS
    COMMANDS -->|Process| REC_SERVICE
    COMMANDS -->|Process| GSR_MGR
    COMMANDS -->|Process| THERMAL_MGR
    TCP_CMD -->|JSON Responses| PC_TCP
    RESPONSES -->|Generate| TCP_CMD
    
    %% Data Streaming Flow
    GSR_MGR -->|Real-time GSR Data| DATA_PACKETS
    THERMAL_MGR -->|Real-time Thermal Data| DATA_PACKETS
    CAMERA_MGR -->|Real-time Camera Data| DATA_PACKETS
    DATA_PACKETS -->|JSON Packets| UDP_STREAM
    UDP_STREAM -->|Stream to PC| PC_STREAM
    
    %% Management Flow
    CONN_MGR -->|Manage Connections| UDP_STREAM
    METRICS -->|Track Statistics| UDP_STREAM
    CLEANUP -->|Resource Management| UDP_DISC
    CLEANUP -->|Resource Management| TCP_CMD
    CLEANUP -->|Resource Management| UDP_STREAM
    
    style PC fill:#e1f5fe
    style UDP_DISC fill:#c8e6c9
    style TCP_CMD fill:#c8e6c9
    style UDP_STREAM fill:#fff3e0
    style COMMANDS fill:#f3e5f5
    style RESPONSES fill:#f3e5f5
    style DATA_PACKETS fill:#fff3e0
```

## Protocol Flow Details

### 1. Device Discovery Flow
```mermaid
sequenceDiagram
    participant PC as PC Application
    participant UDP as UDP Discovery Server
    participant NS as NetworkService
    
    PC->>UDP: Broadcast "DISCOVER_MULTIMODAL_CAPTURE_APP"
    UDP->>NS: Process discovery request
    NS->>NS: Create discovery response with device info
    NS->>UDP: Return device capabilities and connection info
    UDP->>PC: Send JSON response with IP, ports, capabilities
    PC->>PC: Display discovered device in UI
```

### 2. Command Processing Flow
```mermaid
sequenceDiagram
    participant PC as PC Application
    participant TCP as TCP Command Server
    participant CP as CommandProtocol
    participant RS as RecordingService
    participant SM as Sensor Managers
    
    PC->>TCP: Send JSON command (e.g., START_RECORDING)
    TCP->>CP: Parse command using CommandProtocol
    CP->>CP: Validate command structure
    CP->>RS: Execute recording command
    CP->>SM: Configure sensor managers
    SM->>CP: Return execution status
    CP->>TCP: Generate JSON response
    TCP->>PC: Send acknowledgment or error response
```

### 3. Real-time Data Streaming Flow
```mermaid
sequenceDiagram
    participant GSR as GSR Sensor
    participant TH as Thermal Camera
    participant NM as NetworkManager
    participant PC as PC Application
    
    loop Continuous Data Flow
        GSR->>NM: GSR data (value, heart rate, PRR)
        TH->>NM: Thermal data (max, min, center temp)
        NM->>NM: Create data packets using CommandProtocol
        NM->>PC: Stream UDP packets to PC (port 8890)
        PC->>PC: Process and visualize real-time data
    end
```

## Key Components

### CommandProtocol.kt
- **Purpose**: Structured JSON communication protocol
- **Features**: Type-safe sealed classes, automatic serialization/deserialization
- **Commands**: START_RECORDING, STOP_RECORDING, GET_STATUS, CONNECT_DEVICE, CONFIGURE_DEVICE, DISCONNECT_ALL_DEVICES, SET_DATA_STREAMING
- **Responses**: Acknowledgment, ErrorResponse, StatusUpdate, DeviceConnected, DeviceDisconnected
- **Data Packets**: GSR, THERMAL_TEMP, CAMERA_FRAME with standardized payload format

### Enhanced NetworkService
- **TCP Command Server**: Handles structured command processing on port 8888
- **UDP Discovery Server**: Enables automatic device discovery on port 8889
- **Thread Management**: Separate threads for each server to ensure non-blocking operation
- **Resource Cleanup**: Comprehensive cleanup of sockets, threads, and connections

### NetworkManager Data Streaming
- **UDP Streaming**: High-frequency data transmission on port 8890
- **Connection Management**: PC address management and connection state tracking
- **Statistics Tracking**: Packet counts, transmission timestamps, and connection metrics
- **Background Processing**: Coroutine-based streaming to avoid blocking sensor data collection

### Sensor Integration
- **GSRSensorManager**: Real-time GSR data streaming with heart rate and packet reception rate
- **ThermalCameraManager**: Live thermal temperature data streaming with max/min/center values
- **Session Tracking**: Automatic session ID inclusion in all data packets for synchronization

## Network Ports

| Port | Protocol | Purpose | Description |
|------|----------|---------|-------------|
| 8888 | TCP | Command Server | Reliable command/response communication |
| 8889 | UDP | Discovery Server | Automatic device discovery broadcasts |
| 8890 | UDP | Data Streaming | High-frequency sensor data transmission |

## Benefits

1. **Automatic Discovery**: PC applications can automatically find Android devices on the network
2. **Structured Communication**: Type-safe JSON protocol eliminates parsing errors
3. **Real-time Data**: Live sensor data streaming for immediate visualization
4. **Scalable Architecture**: Separate channels for commands and data prevent interference
5. **Professional Grade**: Comprehensive error handling, status reporting, and resource management
6. **Background Operation**: Service-based architecture ensures reliable operation even when app is backgrounded

## Future Enhancements

The architecture is designed to support additional features:
- Multiple PC client connections
- Data compression for high-frequency streaming
- Encryption for secure communication
- Quality of Service (QoS) management
- Network bandwidth adaptation
- Cross-platform discovery protocols

## Integration Points

The enhanced networking system integrates seamlessly with:
- **RecordingService**: For coordinated recording control
- **GSRSensorManager**: For real-time GSR data streaming
- **ThermalCameraManager**: For live thermal data transmission
- **SettingsManager**: For network configuration persistence
- **MainActivity**: For UI status updates and user interaction

This comprehensive networking enhancement transforms the application into a professional-grade research tool capable of real-time PC communication and data streaming.