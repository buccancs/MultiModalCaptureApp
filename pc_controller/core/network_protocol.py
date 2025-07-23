"""
Network Protocol Constants and Message Definitions

This file defines the core networking protocol for bi-directional communication
between PC controller and Android devices, including command types, message formats,
and synchronization mechanisms.

This is the Python equivalent of the Android NetworkProtocol.kt file.
"""

import time
import uuid
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional
from enum import Enum


class NetworkProtocol:
    """Network protocol constants and definitions."""
    
    class Commands:
        """Core command types as specified in requirements."""
        CMD_START = "CMD_START"
        CMD_STOP = "CMD_STOP"
        CMD_STATUS = "CMD_STATUS"
        SYNC_PING = "SYNC_PING"
        CMD_PREPARE = "CMD_PREPARE"
        CMD_RESET = "CMD_RESET"
    
    class MessageTypes:
        """Message types for network communication."""
        COMMAND = "COMMAND"
        COMMAND_ACK = "COMMAND_ACK"
        COMMAND_NACK = "COMMAND_NACK"
        STATUS_REQUEST = "STATUS_REQUEST"
        STATUS_RESPONSE = "STATUS_RESPONSE"
        PING = "PING"
        PONG = "PONG"
        SYNC_PING = "SYNC_PING"
        SYNC_PONG = "SYNC_PONG"
        SYNC_MARKER = "SYNC_MARKER"
        HEARTBEAT = "HEARTBEAT"
        HEARTBEAT_ACK = "HEARTBEAT_ACK"
        ERROR = "ERROR"
        DEVICE_READY = "DEVICE_READY"
        SESSION_MARKER = "SESSION_MARKER"
    
    class ErrorCodes:
        """Error codes for network communication."""
        UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
        INVALID_STATE = "INVALID_STATE"
        DEVICE_BUSY = "DEVICE_BUSY"
        INSUFFICIENT_STORAGE = "INSUFFICIENT_STORAGE"
        PERMISSION_DENIED = "PERMISSION_DENIED"
        HARDWARE_ERROR = "HARDWARE_ERROR"
        NETWORK_ERROR = "NETWORK_ERROR"
        TIMEOUT = "TIMEOUT"
    
    class DeviceStates:
        """Device states for status reporting."""
        IDLE = "IDLE"
        PREPARING = "PREPARING"
        READY = "READY"
        RECORDING = "RECORDING"
        STOPPING = "STOPPING"
        ERROR = "ERROR"
        DISCONNECTED = "DISCONNECTED"
    
    class SyncMarkerTypes:
        """Sync marker types for synchronization verification."""
        SESSION_START = "SESSION_START"
        SESSION_END = "SESSION_END"
        CALIBRATION = "CALIBRATION"
        CUSTOM = "CUSTOM"
        TIME_REFERENCE = "TIME_REFERENCE"
    
    class NetworkConfig:
        """Network configuration constants."""
        DEFAULT_DISCOVERY_PORT = 8888
        DEFAULT_SERVER_PORT = 8889
        DEFAULT_HEARTBEAT_INTERVAL = 5000  # ms
        DEFAULT_CONNECTION_TIMEOUT = 10000  # ms
        DEFAULT_COMMAND_TIMEOUT = 5000  # ms
        MAX_RETRY_ATTEMPTS = 3
        SYNC_PING_INTERVAL = 1000  # ms
        MAX_CLOCK_OFFSET_MS = 50  # Maximum acceptable clock offset
    
    # Protocol Version
    PROTOCOL_VERSION = "1.0"


@dataclass
class EnhancedNetworkMessage:
    """Enhanced Network Message with additional fields for robust communication."""
    type: str
    payload: Any
    timestamp: int
    message_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    device_id: Optional[str] = None
    session_id: Optional[str] = None
    protocol_version: str = NetworkProtocol.PROTOCOL_VERSION
    requires_ack: bool = False
    retry_count: int = 0
    max_retries: int = NetworkProtocol.NetworkConfig.MAX_RETRY_ATTEMPTS
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert message to dictionary for JSON serialization."""
        return {
            'type': self.type,
            'payload': self.payload,
            'timestamp': self.timestamp,
            'messageId': self.message_id,
            'deviceId': self.device_id,
            'sessionId': self.session_id,
            'protocolVersion': self.protocol_version,
            'requiresAck': self.requires_ack,
            'retryCount': self.retry_count,
            'maxRetries': self.max_retries
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'EnhancedNetworkMessage':
        """Create message from dictionary."""
        return cls(
            type=data['type'],
            payload=data['payload'],
            timestamp=data['timestamp'],
            message_id=data.get('messageId', str(uuid.uuid4())),
            device_id=data.get('deviceId'),
            session_id=data.get('sessionId'),
            protocol_version=data.get('protocolVersion', NetworkProtocol.PROTOCOL_VERSION),
            requires_ack=data.get('requiresAck', False),
            retry_count=data.get('retryCount', 0),
            max_retries=data.get('maxRetries', NetworkProtocol.NetworkConfig.MAX_RETRY_ATTEMPTS)
        )


@dataclass
class CommandMessage:
    """Command Message with specific command data."""
    command: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    session_id: Optional[str] = None
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'command': self.command,
            'parameters': self.parameters,
            'sessionId': self.session_id,
            'timestamp': self.timestamp
        }


@dataclass
class StatusResponse:
    """Status Response Message."""
    device_id: str
    device_name: str
    state: str
    battery_level: int
    storage_available: int
    is_recording: bool
    capabilities: List[str]
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))
    network_metrics: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'deviceId': self.device_id,
            'deviceName': self.device_name,
            'state': self.state,
            'batteryLevel': self.battery_level,
            'storageAvailable': self.storage_available,
            'isRecording': self.is_recording,
            'capabilities': self.capabilities,
            'timestamp': self.timestamp,
            'networkMetrics': self.network_metrics
        }


@dataclass
class SyncPingMessage:
    """Sync Ping Message for time synchronization."""
    client_timestamp: int
    ping_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    sequence_number: int = 0
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'pingId': self.ping_id,
            'clientTimestamp': self.client_timestamp,
            'sequenceNumber': self.sequence_number
        }


@dataclass
class SyncPongMessage:
    """Sync Pong Response Message."""
    ping_id: str
    client_timestamp: int
    server_receive_timestamp: int
    server_send_timestamp: int
    sequence_number: int = 0
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'pingId': self.ping_id,
            'clientTimestamp': self.client_timestamp,
            'serverReceiveTimestamp': self.server_receive_timestamp,
            'serverSendTimestamp': self.server_send_timestamp,
            'sequenceNumber': self.sequence_number
        }


@dataclass
class SyncMarkerMessage:
    """Sync Marker Message for synchronization verification."""
    marker_type: str
    session_id: str
    timestamp: int
    marker_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    data: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'markerId': self.marker_id,
            'markerType': self.marker_type,
            'sessionId': self.session_id,
            'timestamp': self.timestamp,
            'data': self.data
        }


@dataclass
class ErrorMessage:
    """Error Message."""
    error_code: str
    error_message: str
    details: Dict[str, Any] = field(default_factory=dict)
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'errorCode': self.error_code,
            'errorMessage': self.error_message,
            'details': self.details,
            'timestamp': self.timestamp
        }


class MessageFactory:
    """Factory class for creating network messages."""
    
    @staticmethod
    def create_command(command: str, parameters: Dict[str, Any] = None, 
                      session_id: str = None, requires_ack: bool = True) -> EnhancedNetworkMessage:
        """Create a command message."""
        cmd_msg = CommandMessage(
            command=command,
            parameters=parameters or {},
            session_id=session_id
        )
        
        return EnhancedNetworkMessage(
            type=NetworkProtocol.MessageTypes.COMMAND,
            payload=cmd_msg.to_dict(),
            timestamp=int(time.time() * 1000),
            session_id=session_id,
            requires_ack=requires_ack
        )
    
    @staticmethod
    def create_status_request(device_id: str = None) -> EnhancedNetworkMessage:
        """Create a status request message."""
        return EnhancedNetworkMessage(
            type=NetworkProtocol.MessageTypes.STATUS_REQUEST,
            payload={},
            timestamp=int(time.time() * 1000),
            device_id=device_id,
            requires_ack=True
        )
    
    @staticmethod
    def create_sync_ping(sequence_number: int = 0) -> EnhancedNetworkMessage:
        """Create a sync ping message."""
        sync_ping = SyncPingMessage(
            client_timestamp=int(time.time() * 1000),
            sequence_number=sequence_number
        )
        
        return EnhancedNetworkMessage(
            type=NetworkProtocol.MessageTypes.SYNC_PING,
            payload=sync_ping.to_dict(),
            timestamp=sync_ping.client_timestamp,
            requires_ack=True
        )
    
    @staticmethod
    def create_sync_marker(marker_type: str, session_id: str, 
                          data: Dict[str, Any] = None) -> EnhancedNetworkMessage:
        """Create a sync marker message."""
        marker = SyncMarkerMessage(
            marker_type=marker_type,
            session_id=session_id,
            timestamp=int(time.time() * 1000),
            data=data or {}
        )
        
        return EnhancedNetworkMessage(
            type=NetworkProtocol.MessageTypes.SYNC_MARKER,
            payload=marker.to_dict(),
            timestamp=marker.timestamp,
            session_id=session_id
        )
    
    @staticmethod
    def create_error(error_code: str, error_message: str, 
                    details: Dict[str, Any] = None) -> EnhancedNetworkMessage:
        """Create an error message."""
        error = ErrorMessage(
            error_code=error_code,
            error_message=error_message,
            details=details or {}
        )
        
        return EnhancedNetworkMessage(
            type=NetworkProtocol.MessageTypes.ERROR,
            payload=error.to_dict(),
            timestamp=error.timestamp
        )