"""
Device Manager for PC Controller Application.
Handles Android device discovery, connection management, and communication.
"""

import asyncio
import json
import logging
import socket
import threading
import time
from typing import Dict, List, Optional, Callable
from dataclasses import dataclass, asdict
from enum import Enum
from PyQt6.QtCore import QObject, pyqtSignal

class DeviceStatus(Enum):
    """Device connection status enumeration."""
    UNKNOWN = "unknown"
    DISCOVERED = "discovered"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    RECORDING = "recording"
    ERROR = "error"
    DISCONNECTED = "disconnected"

@dataclass
class AndroidDevice:
    """Represents an Android capture device."""
    device_id: str
    device_name: str
    ip_address: str
    server_port: int
    capabilities: List[str]
    status: DeviceStatus = DeviceStatus.DISCOVERED
    last_seen: float = 0.0
    connection_time: Optional[float] = None
    battery_level: Optional[int] = None
    storage_available: Optional[int] = None
    
    def to_dict(self) -> Dict:
        """Convert device to dictionary."""
        data = asdict(self)
        data['status'] = self.status.value
        return data
    
    @classmethod
    def from_discovery_data(cls, data: Dict, ip_address: str) -> 'AndroidDevice':
        """Create device from discovery response data."""
        return cls(
            device_id=data.get('deviceId', 'unknown'),
            device_name=data.get('deviceName', 'Android Device'),
            ip_address=ip_address,
            server_port=data.get('serverPort', 8888),
            capabilities=data.get('capabilities', []),
            last_seen=time.time()
        )

class DeviceManager(QObject):
    """Manages Android device discovery and connections."""
    
    # Qt signals for UI updates
    device_discovered = pyqtSignal(dict)  # AndroidDevice.to_dict()
    device_connected = pyqtSignal(str)    # device_id
    device_disconnected = pyqtSignal(str) # device_id
    device_status_changed = pyqtSignal(str, str)  # device_id, status
    device_data_received = pyqtSignal(str, dict)  # device_id, data
    
    def __init__(self, config):
        super().__init__()
        self.config = config
        self.devices: Dict[str, AndroidDevice] = {}
        self.connections: Dict[str, 'DeviceConnection'] = {}
        
        # Discovery components
        self.discovery_socket: Optional[socket.socket] = None
        self.discovery_thread: Optional[threading.Thread] = None
        self.discovery_running = False
        
        # Cleanup timer
        self.cleanup_thread: Optional[threading.Thread] = None
        self.cleanup_running = False
        
        logging.info("DeviceManager initialized")
    
    def start_discovery(self):
        """Start device discovery service."""
        if self.discovery_running:
            logging.warning("Discovery already running")
            return
        
        try:
            self.discovery_running = True
            self.discovery_thread = threading.Thread(
                target=self._discovery_worker,
                daemon=True
            )
            self.discovery_thread.start()
            
            # Start cleanup thread
            self.cleanup_running = True
            self.cleanup_thread = threading.Thread(
                target=self._cleanup_worker,
                daemon=True
            )
            self.cleanup_thread.start()
            
            logging.info("Device discovery started")
            
        except Exception as e:
            logging.error(f"Failed to start discovery: {e}")
            self.discovery_running = False
    
    def stop_discovery(self):
        """Stop device discovery service."""
        self.discovery_running = False
        self.cleanup_running = False
        
        if self.discovery_socket:
            self.discovery_socket.close()
            self.discovery_socket = None
        
        if self.discovery_thread:
            self.discovery_thread.join(timeout=2.0)
            self.discovery_thread = None
        
        if self.cleanup_thread:
            self.cleanup_thread.join(timeout=2.0)
            self.cleanup_thread = None
        
        logging.info("Device discovery stopped")
    
    def _discovery_worker(self):
        """Discovery worker thread."""
        try:
            # Create UDP socket for discovery
            self.discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.discovery_socket.settimeout(1.0)
            
            discovery_message = "DISCOVER_ANDROID_CAPTURE"
            broadcast_interval = 5.0  # seconds
            last_broadcast = 0.0
            
            while self.discovery_running:
                current_time = time.time()
                
                # Send discovery broadcast
                if current_time - last_broadcast >= broadcast_interval:
                    try:
                        self.discovery_socket.sendto(
                            discovery_message.encode(),
                            ('<broadcast>', self.config.network.discovery_port)
                        )
                        last_broadcast = current_time
                    except Exception as e:
                        logging.debug(f"Discovery broadcast error: {e}")
                
                # Listen for responses
                try:
                    data, addr = self.discovery_socket.recvfrom(1024)
                    self._handle_discovery_response(data, addr[0])
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.discovery_running:
                        logging.debug(f"Discovery receive error: {e}")
                
        except Exception as e:
            logging.error(f"Discovery worker error: {e}")
        finally:
            if self.discovery_socket:
                self.discovery_socket.close()
                self.discovery_socket = None
    
    def _handle_discovery_response(self, data: bytes, ip_address: str):
        """Handle discovery response from Android device."""
        try:
            response = json.loads(data.decode())
            
            if response.get('type') == 'ANDROID_CAPTURE_DEVICE':
                device = AndroidDevice.from_discovery_data(response, ip_address)
                
                # Update or add device
                if device.device_id in self.devices:
                    existing_device = self.devices[device.device_id]
                    existing_device.last_seen = device.last_seen
                    existing_device.ip_address = ip_address
                else:
                    self.devices[device.device_id] = device
                    self.device_discovered.emit(device.to_dict())
                    logging.info(f"Discovered device: {device.device_name} ({device.device_id})")
                
        except Exception as e:
            logging.debug(f"Invalid discovery response from {ip_address}: {e}")
    
    def _cleanup_worker(self):
        """Cleanup worker thread to remove stale devices."""
        while self.cleanup_running:
            try:
                current_time = time.time()
                stale_devices = []
                
                for device_id, device in self.devices.items():
                    # Remove devices not seen for 30 seconds
                    if current_time - device.last_seen > 30.0:
                        stale_devices.append(device_id)
                
                for device_id in stale_devices:
                    device = self.devices.pop(device_id, None)
                    if device:
                        logging.info(f"Removed stale device: {device.device_name}")
                        if device.status == DeviceStatus.CONNECTED:
                            self.disconnect_device(device_id)
                
                time.sleep(10.0)  # Check every 10 seconds
                
            except Exception as e:
                logging.error(f"Cleanup worker error: {e}")
    
    def connect_device(self, device_id: str) -> bool:
        """Connect to a specific Android device."""
        if device_id not in self.devices:
            logging.error(f"Device not found: {device_id}")
            return False
        
        if device_id in self.connections:
            logging.warning(f"Device already connected: {device_id}")
            return True
        
        device = self.devices[device_id]
        device.status = DeviceStatus.CONNECTING
        self.device_status_changed.emit(device_id, device.status.value)
        
        try:
            connection = DeviceConnection(device, self.config)
            connection.data_received.connect(
                lambda data: self.device_data_received.emit(device_id, data)
            )
            connection.status_changed.connect(
                lambda status: self._handle_connection_status_change(device_id, status)
            )
            
            if connection.connect():
                self.connections[device_id] = connection
                device.status = DeviceStatus.CONNECTED
                device.connection_time = time.time()
                self.device_connected.emit(device_id)
                self.device_status_changed.emit(device_id, device.status.value)
                logging.info(f"Connected to device: {device.device_name}")
                return True
            else:
                device.status = DeviceStatus.ERROR
                self.device_status_changed.emit(device_id, device.status.value)
                return False
                
        except Exception as e:
            logging.error(f"Failed to connect to device {device_id}: {e}")
            device.status = DeviceStatus.ERROR
            self.device_status_changed.emit(device_id, device.status.value)
            return False
    
    def disconnect_device(self, device_id: str):
        """Disconnect from a specific Android device."""
        if device_id in self.connections:
            connection = self.connections.pop(device_id)
            connection.disconnect()
        
        if device_id in self.devices:
            device = self.devices[device_id]
            device.status = DeviceStatus.DISCONNECTED
            device.connection_time = None
            self.device_disconnected.emit(device_id)
            self.device_status_changed.emit(device_id, device.status.value)
            logging.info(f"Disconnected from device: {device.device_name}")
    
    def _handle_connection_status_change(self, device_id: str, status: str):
        """Handle connection status changes."""
        if device_id in self.devices:
            device = self.devices[device_id]
            device.status = DeviceStatus(status)
            self.device_status_changed.emit(device_id, status)
    
    def send_command(self, device_id: str, command: str, payload: Optional[Dict] = None) -> bool:
        """Send command to a connected device."""
        if device_id not in self.connections:
            logging.error(f"Device not connected: {device_id}")
            return False
        
        connection = self.connections[device_id]
        return connection.send_command(command, payload)
    
    def get_connected_devices(self) -> List[AndroidDevice]:
        """Get list of connected devices."""
        return [
            device for device in self.devices.values()
            if device.status == DeviceStatus.CONNECTED
        ]
    
    def get_all_devices(self) -> List[AndroidDevice]:
        """Get list of all discovered devices."""
        return list(self.devices.values())
    
    def cleanup(self):
        """Cleanup device manager resources."""
        logging.info("Cleaning up DeviceManager")
        
        # Disconnect all devices
        for device_id in list(self.connections.keys()):
            self.disconnect_device(device_id)
        
        # Stop discovery
        self.stop_discovery()
        
        self.devices.clear()
        self.connections.clear()

class DeviceConnection(QObject):
    """Manages connection to a single Android device."""
    
    # Qt signals
    data_received = pyqtSignal(dict)
    status_changed = pyqtSignal(str)
    
    def __init__(self, device: AndroidDevice, config):
        super().__init__()
        self.device = device
        self.config = config
        self.socket: Optional[socket.socket] = None
        self.connected = False
        
        # Message handling
        self.receive_thread: Optional[threading.Thread] = None
        self.receive_running = False
    
    def connect(self) -> bool:
        """Connect to the Android device."""
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.settimeout(self.config.network.connection_timeout / 1000.0)
            
            self.socket.connect((self.device.ip_address, self.device.server_port))
            self.connected = True
            
            # Start receive thread
            self.receive_running = True
            self.receive_thread = threading.Thread(
                target=self._receive_worker,
                daemon=True
            )
            self.receive_thread.start()
            
            return True
            
        except Exception as e:
            logging.error(f"Connection failed to {self.device.device_name}: {e}")
            self.cleanup_connection()
            return False
    
    def disconnect(self):
        """Disconnect from the Android device."""
        self.connected = False
        self.receive_running = False
        self.cleanup_connection()
    
    def cleanup_connection(self):
        """Cleanup connection resources."""
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
        
        if self.receive_thread:
            self.receive_thread.join(timeout=2.0)
            self.receive_thread = None
    
    def _receive_worker(self):
        """Worker thread for receiving messages."""
        buffer = ""
        
        while self.receive_running and self.connected:
            try:
                data = self.socket.recv(1024).decode('utf-8')
                if not data:
                    break
                
                buffer += data
                
                # Process complete messages (assuming line-delimited JSON)
                while '\n' in buffer:
                    line, buffer = buffer.split('\n', 1)
                    if line.strip():
                        self._handle_message(line.strip())
                        
            except socket.timeout:
                continue
            except Exception as e:
                if self.receive_running:
                    logging.error(f"Receive error from {self.device.device_name}: {e}")
                break
        
        self.connected = False
        self.status_changed.emit(DeviceStatus.DISCONNECTED.value)
    
    def _handle_message(self, message: str):
        """Handle received message from Android device."""
        try:
            data = json.loads(message)
            self.data_received.emit(data)
            
            # Handle specific message types
            msg_type = data.get('type', '')
            if msg_type == 'STATUS_UPDATE':
                self._handle_status_update(data.get('payload', {}))
            
        except Exception as e:
            logging.debug(f"Invalid message from {self.device.device_name}: {e}")
    
    def _handle_status_update(self, status_data: Dict):
        """Handle status update from device."""
        # Update device status information
        if 'batteryLevel' in status_data:
            self.device.battery_level = status_data['batteryLevel']
        if 'storageAvailable' in status_data:
            self.device.storage_available = status_data['storageAvailable']
        if 'isRecording' in status_data:
            if status_data['isRecording']:
                self.device.status = DeviceStatus.RECORDING
            else:
                self.device.status = DeviceStatus.CONNECTED
            self.status_changed.emit(self.device.status.value)
    
    def send_command(self, command: str, payload: Optional[Dict] = None) -> bool:
        """Send command to the Android device."""
        if not self.connected or not self.socket:
            return False
        
        try:
            message = {
                'type': 'COMMAND',
                'payload': command,
                'timestamp': time.time() * 1000
            }
            
            if payload:
                message['data'] = payload
            
            message_str = json.dumps(message) + '\n'
            self.socket.send(message_str.encode('utf-8'))
            return True
            
        except Exception as e:
            logging.error(f"Failed to send command to {self.device.device_name}: {e}")
            return False