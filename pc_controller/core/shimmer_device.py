"""
PC Shimmer Device Integration
Integrates Shimmer3 GSR+ sensors directly connected to PC via USB/Bluetooth
Based on ShimmerCaptureIntelligent reference implementation
"""

import logging
import time
import threading
import subprocess
import platform
from typing import Dict, List, Optional, Callable, Union
from dataclasses import dataclass, asdict
from enum import Enum
from PyQt6.QtCore import QObject, pyqtSignal

logger = logging.getLogger(__name__)

class ShimmerConnectionType(Enum):
    """Shimmer device connection types"""
    USB_SERIAL = "usb_serial"
    BLUETOOTH = "bluetooth"
    UNKNOWN = "unknown"

class ShimmerDeviceStatus(Enum):
    """Shimmer device status states"""
    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    STREAMING = "streaming"
    ERROR = "error"

@dataclass
class ShimmerDevice:
    """
    Represents a PC-connected Shimmer device
    Integrates with existing device management system
    """
    device_id: str
    device_name: str
    connection_type: ShimmerConnectionType
    com_port: Optional[str] = None
    bluetooth_address: Optional[str] = None
    status: ShimmerDeviceStatus = ShimmerDeviceStatus.DISCONNECTED
    sampling_rate: float = 128.0  # Default GSR sampling rate
    last_seen: float = 0.0
    firmware_version: Optional[str] = None
    battery_level: Optional[int] = None
    signal_strength: Optional[int] = None
    
    def __post_init__(self):
        """Initialize device after creation"""
        self.last_seen = time.time()
        if not self.device_name:
            self.device_name = f"Shimmer-{self.device_id[-4:]}"
    
    def to_dict(self) -> Dict:
        """Convert device to dictionary for serialization"""
        return {
            'device_id': self.device_id,
            'device_name': self.device_name,
            'device_type': 'shimmer_pc',
            'connection_type': self.connection_type.value,
            'com_port': self.com_port,
            'bluetooth_address': self.bluetooth_address,
            'status': self.status.value,
            'sampling_rate': self.sampling_rate,
            'last_seen': self.last_seen,
            'firmware_version': self.firmware_version,
            'battery_level': self.battery_level,
            'signal_strength': self.signal_strength
        }
    
    @classmethod
    def from_discovery_data(cls, data: Dict) -> 'ShimmerDevice':
        """Create ShimmerDevice from discovery data"""
        connection_type = ShimmerConnectionType.UNKNOWN
        if data.get('com_port'):
            connection_type = ShimmerConnectionType.USB_SERIAL
        elif data.get('bluetooth_address'):
            connection_type = ShimmerConnectionType.BLUETOOTH
            
        return cls(
            device_id=data.get('device_id', ''),
            device_name=data.get('device_name', ''),
            connection_type=connection_type,
            com_port=data.get('com_port'),
            bluetooth_address=data.get('bluetooth_address'),
            firmware_version=data.get('firmware_version'),
            battery_level=data.get('battery_level'),
            signal_strength=data.get('signal_strength')
        )
    
    @property
    def ip_address(self) -> Optional[str]:
        """Compatibility with existing device interface"""
        return None  # Shimmer devices don't use IP addresses
    
    def get_connection_string(self) -> str:
        """Get connection string for device"""
        if self.connection_type == ShimmerConnectionType.USB_SERIAL:
            return self.com_port or "Unknown COM Port"
        elif self.connection_type == ShimmerConnectionType.BLUETOOTH:
            return self.bluetooth_address or "Unknown BT Address"
        return "Unknown Connection"

class ShimmerDiscovery:
    """
    Handles discovery of PC-connected Shimmer devices
    Supports both USB serial and Bluetooth connections
    """
    
    def __init__(self):
        self.logger = logging.getLogger(__name__ + '.ShimmerDiscovery')
        self.discovered_devices: Dict[str, ShimmerDevice] = {}
        self.discovery_active = False
        
    def start_discovery(self) -> None:
        """Start discovering Shimmer devices"""
        self.logger.info("Starting Shimmer device discovery")
        self.discovery_active = True
        
        # Start discovery threads
        threading.Thread(target=self._discover_usb_devices, daemon=True).start()
        threading.Thread(target=self._discover_bluetooth_devices, daemon=True).start()
    
    def stop_discovery(self) -> None:
        """Stop device discovery"""
        self.logger.info("Stopping Shimmer device discovery")
        self.discovery_active = False
    
    def _discover_usb_devices(self) -> None:
        """Discover USB-connected Shimmer devices"""
        try:
            if platform.system() == "Windows":
                self._discover_windows_serial_ports()
            else:
                self._discover_unix_serial_ports()
        except Exception as e:
            self.logger.error(f"Error discovering USB devices: {e}")
    
    def _discover_windows_serial_ports(self) -> None:
        """Discover serial ports on Windows"""
        try:
            # Use wmic to list serial ports
            result = subprocess.run([
                'wmic', 'path', 'Win32_SerialPort', 'get', 
                'DeviceID,Description,Name', '/format:csv'
            ], capture_output=True, text=True, timeout=10)
            
            if result.returncode == 0:
                self._parse_windows_serial_output(result.stdout)
        except Exception as e:
            self.logger.error(f"Error scanning Windows serial ports: {e}")
    
    def _discover_unix_serial_ports(self) -> None:
        """Discover serial ports on Unix-like systems"""
        try:
            # Check common serial port locations
            import glob
            ports = glob.glob('/dev/ttyUSB*') + glob.glob('/dev/ttyACM*') + glob.glob('/dev/cu.*')
            
            for port in ports:
                # TODO: Add logic to identify Shimmer devices
                # For now, assume any serial device could be a Shimmer
                device_id = f"shimmer_usb_{port.split('/')[-1]}"
                device = ShimmerDevice(
                    device_id=device_id,
                    device_name=f"Shimmer USB ({port})",
                    connection_type=ShimmerConnectionType.USB_SERIAL,
                    com_port=port
                )
                self.discovered_devices[device_id] = device
                self.logger.info(f"Discovered potential Shimmer device: {port}")
                
        except Exception as e:
            self.logger.error(f"Error scanning Unix serial ports: {e}")
    
    def _parse_windows_serial_output(self, output: str) -> None:
        """Parse Windows serial port output"""
        lines = output.strip().split('\n')[1:]  # Skip header
        for line in lines:
            if line.strip():
                parts = line.split(',')
                if len(parts) >= 4:
                    device_id = parts[1].strip()
                    description = parts[2].strip()
                    name = parts[3].strip()
                    
                    if device_id and device_id != "DeviceID":
                        # TODO: Add logic to identify Shimmer devices by description/name
                        shimmer_device = ShimmerDevice(
                            device_id=f"shimmer_usb_{device_id}",
                            device_name=f"Shimmer USB ({device_id})",
                            connection_type=ShimmerConnectionType.USB_SERIAL,
                            com_port=device_id
                        )
                        self.discovered_devices[shimmer_device.device_id] = shimmer_device
                        self.logger.info(f"Discovered potential Shimmer device: {device_id}")
    
    def _discover_bluetooth_devices(self) -> None:
        """Discover Bluetooth Shimmer devices"""
        try:
            if platform.system() == "Windows":
                self._discover_windows_bluetooth()
            else:
                self._discover_unix_bluetooth()
        except Exception as e:
            self.logger.error(f"Error discovering Bluetooth devices: {e}")
    
    def _discover_windows_bluetooth(self) -> None:
        """Discover Bluetooth devices on Windows"""
        # TODO: Implement Windows Bluetooth discovery for Shimmer devices
        # This would use Windows Bluetooth APIs or PowerShell commands
        pass
    
    def _discover_unix_bluetooth(self) -> None:
        """Discover Bluetooth devices on Unix-like systems"""
        try:
            # Use bluetoothctl to scan for devices
            result = subprocess.run([
                'bluetoothctl', 'devices'
            ], capture_output=True, text=True, timeout=10)
            
            if result.returncode == 0:
                self._parse_bluetoothctl_output(result.stdout)
        except Exception as e:
            self.logger.error(f"Error scanning Bluetooth devices: {e}")
    
    def _parse_bluetoothctl_output(self, output: str) -> None:
        """Parse bluetoothctl output for Shimmer devices"""
        lines = output.strip().split('\n')
        for line in lines:
            if 'Device' in line:
                parts = line.split()
                if len(parts) >= 3:
                    bt_address = parts[1]
                    device_name = ' '.join(parts[2:])
                    
                    # Check if this looks like a Shimmer device
                    if 'shimmer' in device_name.lower() or 'gsr' in device_name.lower():
                        device_id = f"shimmer_bt_{bt_address.replace(':', '')}"
                        shimmer_device = ShimmerDevice(
                            device_id=device_id,
                            device_name=device_name,
                            connection_type=ShimmerConnectionType.BLUETOOTH,
                            bluetooth_address=bt_address
                        )
                        self.discovered_devices[device_id] = shimmer_device
                        self.logger.info(f"Discovered Shimmer Bluetooth device: {device_name} ({bt_address})")
    
    def get_discovered_devices(self) -> Dict[str, ShimmerDevice]:
        """Get all discovered Shimmer devices"""
        return self.discovered_devices.copy()
    
    def clear_discovered_devices(self) -> None:
        """Clear discovered devices list"""
        self.discovered_devices.clear()