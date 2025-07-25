"""
PC Shimmer Manager
Core implementation for PC-connected Shimmer3 GSR+ sensors
Based on ShimmerCaptureIntelligent reference implementation
Integrates Java Shimmer SDK with Python PC controller
"""

import logging
import time
import threading
import subprocess
import json
import csv
import os
from typing import Dict, List, Optional, Callable, Any
from dataclasses import dataclass, asdict
from enum import Enum
from pathlib import Path
from PyQt6.QtCore import QObject, pyqtSignal

from .shimmer_device import ShimmerDevice, ShimmerDeviceStatus, ShimmerConnectionType

logger = logging.getLogger(__name__)

@dataclass
class GSRDataPoint:
    """GSR data point structure matching Android implementation"""
    timestamp: float
    gsr_value: float  # GSR resistance in kΩ
    gsr_conductance: float  # GSR conductance in μS
    ppg_value: Optional[float] = None
    heart_rate: Optional[float] = None
    packet_reception_rate: Optional[float] = None
    device_id: Optional[str] = None

    def to_dict(self) -> Dict:
        """Convert to dictionary for JSON serialization"""
        return asdict(self)

    def to_csv_row(self) -> List[str]:
        """Convert to CSV row format"""
        return [
            str(self.timestamp),
            str(self.gsr_value),
            str(self.gsr_conductance),
            str(self.ppg_value or ''),
            str(self.heart_rate or ''),
            str(self.packet_reception_rate or ''),
            str(self.device_id or '')
        ]

class ShimmerPCManager(QObject):
    """
    PC Shimmer Manager
    Handles connection and data streaming from PC-connected Shimmer devices
    Uses Java Shimmer SDK through subprocess calls
    """
    
    # PyQt signals for UI integration
    device_connected = pyqtSignal(str)  # device_id
    device_disconnected = pyqtSignal(str)  # device_id
    data_received = pyqtSignal(str, dict)  # device_id, data
    status_changed = pyqtSignal(str, str)  # device_id, status
    error_occurred = pyqtSignal(str, str)  # device_id, error_message
    
    def __init__(self, config: Dict):
        super().__init__()
        self.config = config
        self.logger = logging.getLogger(__name__ + '.ShimmerPCManager')
        
        # Device management
        self.connected_devices: Dict[str, ShimmerDevice] = {}
        self.device_processes: Dict[str, subprocess.Popen] = {}
        self.data_callbacks: Dict[str, Callable] = {}
        self.recording_active: Dict[str, bool] = {}
        
        # Data storage
        self.data_files: Dict[str, Any] = {}  # CSV file handles
        self.session_data: Dict[str, List[GSRDataPoint]] = {}
        
        # Java SDK paths
        self.shimmer_jar_path = self._find_shimmer_jar()
        self.java_executable = self._find_java_executable()
        
        # Threading
        self.data_threads: Dict[str, threading.Thread] = {}
        self.shutdown_event = threading.Event()
        
        self.logger.info("ShimmerPCManager initialized")
    
    def _find_shimmer_jar(self) -> Optional[str]:
        """Find Shimmer SDK JAR file"""
        possible_paths = [
            "external/libs/shimmerdriverpc-0.11.4_beta.jar",
            "app/libs/shimmerdriverpc-0.11.4_beta.jar",
            "libs/shimmerdriverpc-0.11.4_beta.jar"
        ]
        
        for path in possible_paths:
            full_path = Path(path)
            if full_path.exists():
                self.logger.info(f"Found Shimmer JAR at: {full_path}")
                return str(full_path.absolute())
        
        self.logger.warning("Shimmer SDK JAR not found")
        return None
    
    def _find_java_executable(self) -> str:
        """Find Java executable"""
        try:
            result = subprocess.run(['java', '-version'], 
                                  capture_output=True, text=True)
            if result.returncode == 0:
                return 'java'
        except FileNotFoundError:
            pass
        
        # Try common Java paths
        java_paths = [
            'java',
            '/usr/bin/java',
            '/usr/local/bin/java',
            'C:\\Program Files\\Java\\jdk-17\\bin\\java.exe',
            'C:\\Program Files\\Java\\jre\\bin\\java.exe'
        ]
        
        for java_path in java_paths:
            try:
                result = subprocess.run([java_path, '-version'], 
                                      capture_output=True, text=True)
                if result.returncode == 0:
                    self.logger.info(f"Found Java at: {java_path}")
                    return java_path
            except FileNotFoundError:
                continue
        
        self.logger.warning("Java executable not found")
        return 'java'  # Fallback
    
    def connect_device(self, device: ShimmerDevice) -> bool:
        """
        Connect to a Shimmer device
        Creates a Java subprocess to handle the actual connection
        """
        try:
            self.logger.info(f"Connecting to Shimmer device: {device.device_id}")
            
            if not self.shimmer_jar_path:
                self.logger.error("Shimmer SDK JAR not found")
                self.error_occurred.emit(device.device_id, "Shimmer SDK JAR not found")
                return False
            
            # Update device status
            device.status = ShimmerDeviceStatus.CONNECTING
            self.status_changed.emit(device.device_id, "connecting")
            
            # Create Java command for Shimmer connection
            java_cmd = self._create_java_command(device)
            
            # Start Java subprocess
            process = subprocess.Popen(
                java_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.PIPE,
                text=True,
                bufsize=1,
                universal_newlines=True
            )
            
            # Store process and device
            self.device_processes[device.device_id] = process
            self.connected_devices[device.device_id] = device
            
            # Start data monitoring thread
            data_thread = threading.Thread(
                target=self._monitor_device_data,
                args=(device.device_id, process),
                daemon=True
            )
            data_thread.start()
            self.data_threads[device.device_id] = data_thread
            
            # Update status
            device.status = ShimmerDeviceStatus.CONNECTED
            self.status_changed.emit(device.device_id, "connected")
            self.device_connected.emit(device.device_id)
            
            self.logger.info(f"Successfully connected to device: {device.device_id}")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to connect to device {device.device_id}: {e}")
            device.status = ShimmerDeviceStatus.ERROR
            self.error_occurred.emit(device.device_id, str(e))
            return False
    
    def _create_java_command(self, device: ShimmerDevice) -> List[str]:
        """Create Java command for Shimmer connection"""
        cmd = [
            self.java_executable,
            '-cp', self.shimmer_jar_path,
            'ShimmerPCBridge',  # TODO: Create this Java bridge class
            '--device-id', device.device_id,
            '--sampling-rate', str(device.sampling_rate)
        ]
        
        if device.connection_type == ShimmerConnectionType.USB_SERIAL:
            cmd.extend(['--connection-type', 'serial', '--com-port', device.com_port])
        elif device.connection_type == ShimmerConnectionType.BLUETOOTH:
            cmd.extend(['--connection-type', 'bluetooth', '--bt-address', device.bluetooth_address])
        
        return cmd
    
    def disconnect_device(self, device_id: str) -> bool:
        """Disconnect from a Shimmer device"""
        try:
            self.logger.info(f"Disconnecting device: {device_id}")
            
            # Stop recording if active
            if self.recording_active.get(device_id, False):
                self.stop_recording(device_id)
            
            # Terminate Java process
            if device_id in self.device_processes:
                process = self.device_processes[device_id]
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                del self.device_processes[device_id]
            
            # Clean up data thread
            if device_id in self.data_threads:
                del self.data_threads[device_id]
            
            # Update device status
            if device_id in self.connected_devices:
                device = self.connected_devices[device_id]
                device.status = ShimmerDeviceStatus.DISCONNECTED
                del self.connected_devices[device_id]
            
            # Clean up data storage
            self._cleanup_device_data(device_id)
            
            self.status_changed.emit(device_id, "disconnected")
            self.device_disconnected.emit(device_id)
            
            self.logger.info(f"Successfully disconnected device: {device_id}")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to disconnect device {device_id}: {e}")
            self.error_occurred.emit(device_id, str(e))
            return False
    
    def start_recording(self, device_id: str, session_id: str, output_dir: str) -> bool:
        """Start recording GSR data from device"""
        try:
            if device_id not in self.connected_devices:
                self.logger.error(f"Device not connected: {device_id}")
                return False
            
            device = self.connected_devices[device_id]
            self.logger.info(f"Starting recording for device: {device_id}")
            
            # Create output file
            output_file = Path(output_dir) / f"{session_id}_{device_id}_gsr.csv"
            csv_file = open(output_file, 'w', newline='')
            csv_writer = csv.writer(csv_file)
            
            # Write CSV header
            csv_writer.writerow([
                'timestamp', 'gsr_value', 'gsr_conductance', 
                'ppg_value', 'heart_rate', 'packet_reception_rate', 'device_id'
            ])
            
            # Store file handle
            self.data_files[device_id] = {
                'file': csv_file,
                'writer': csv_writer,
                'path': output_file
            }
            
            # Initialize session data
            self.session_data[device_id] = []
            
            # Send start command to Java process
            if device_id in self.device_processes:
                process = self.device_processes[device_id]
                process.stdin.write("START_RECORDING\n")
                process.stdin.flush()
            
            # Update status
            device.status = ShimmerDeviceStatus.STREAMING
            self.recording_active[device_id] = True
            self.status_changed.emit(device_id, "recording")
            
            self.logger.info(f"Recording started for device: {device_id}")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to start recording for device {device_id}: {e}")
            self.error_occurred.emit(device_id, str(e))
            return False
    
    def stop_recording(self, device_id: str) -> bool:
        """Stop recording GSR data from device"""
        try:
            self.logger.info(f"Stopping recording for device: {device_id}")
            
            # Send stop command to Java process
            if device_id in self.device_processes:
                process = self.device_processes[device_id]
                process.stdin.write("STOP_RECORDING\n")
                process.stdin.flush()
            
            # Close data file
            if device_id in self.data_files:
                file_info = self.data_files[device_id]
                file_info['file'].close()
                del self.data_files[device_id]
                self.logger.info(f"Data saved to: {file_info['path']}")
            
            # Update status
            if device_id in self.connected_devices:
                device = self.connected_devices[device_id]
                device.status = ShimmerDeviceStatus.CONNECTED
            
            self.recording_active[device_id] = False
            self.status_changed.emit(device_id, "connected")
            
            self.logger.info(f"Recording stopped for device: {device_id}")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to stop recording for device {device_id}: {e}")
            self.error_occurred.emit(device_id, str(e))
            return False
    
    def _monitor_device_data(self, device_id: str, process: subprocess.Popen):
        """Monitor data from Java subprocess"""
        try:
            self.logger.info(f"Starting data monitoring for device: {device_id}")
            
            while not self.shutdown_event.is_set():
                if process.poll() is not None:
                    # Process has terminated
                    break
                
                # Read data from Java process
                line = process.stdout.readline()
                if not line:
                    continue
                
                line = line.strip()
                if not line:
                    continue
                
                # Parse data from Java process
                try:
                    if line.startswith("DATA:"):
                        data_json = line[5:]  # Remove "DATA:" prefix
                        data = json.loads(data_json)
                        self._process_device_data(device_id, data)
                    elif line.startswith("STATUS:"):
                        status_json = line[7:]  # Remove "STATUS:" prefix
                        status = json.loads(status_json)
                        self._process_status_update(device_id, status)
                    elif line.startswith("ERROR:"):
                        error_msg = line[6:]  # Remove "ERROR:" prefix
                        self.error_occurred.emit(device_id, error_msg)
                except json.JSONDecodeError as e:
                    self.logger.warning(f"Failed to parse data from device {device_id}: {e}")
                
        except Exception as e:
            self.logger.error(f"Error monitoring device {device_id}: {e}")
            self.error_occurred.emit(device_id, str(e))
    
    def _process_device_data(self, device_id: str, data: Dict):
        """Process GSR data from device"""
        try:
            # Create GSR data point
            data_point = GSRDataPoint(
                timestamp=data.get('timestamp', time.time()),
                gsr_value=data.get('gsr_value', 0.0),
                gsr_conductance=data.get('gsr_conductance', 0.0),
                ppg_value=data.get('ppg_value'),
                heart_rate=data.get('heart_rate'),
                packet_reception_rate=data.get('packet_reception_rate'),
                device_id=device_id
            )
            
            # Store in session data
            if device_id in self.session_data:
                self.session_data[device_id].append(data_point)
            
            # Write to CSV file if recording
            if device_id in self.data_files and self.recording_active.get(device_id, False):
                writer = self.data_files[device_id]['writer']
                writer.writerow(data_point.to_csv_row())
                self.data_files[device_id]['file'].flush()
            
            # Emit signal for UI updates
            self.data_received.emit(device_id, data_point.to_dict())
            
            # Call registered callbacks
            if device_id in self.data_callbacks:
                self.data_callbacks[device_id](data_point)
                
        except Exception as e:
            self.logger.error(f"Error processing data from device {device_id}: {e}")
    
    def _process_status_update(self, device_id: str, status: Dict):
        """Process status update from device"""
        try:
            if device_id in self.connected_devices:
                device = self.connected_devices[device_id]
                
                # Update device properties
                if 'battery_level' in status:
                    device.battery_level = status['battery_level']
                if 'signal_strength' in status:
                    device.signal_strength = status['signal_strength']
                if 'firmware_version' in status:
                    device.firmware_version = status['firmware_version']
                
                device.last_seen = time.time()
            
            # Emit status change signal
            self.status_changed.emit(device_id, status.get('status', 'unknown'))
            
        except Exception as e:
            self.logger.error(f"Error processing status update from device {device_id}: {e}")
    
    def _cleanup_device_data(self, device_id: str):
        """Clean up data storage for device"""
        # Close data file if open
        if device_id in self.data_files:
            try:
                self.data_files[device_id]['file'].close()
            except:
                pass
            del self.data_files[device_id]
        
        # Clear session data
        if device_id in self.session_data:
            del self.session_data[device_id]
        
        # Clear callbacks
        if device_id in self.data_callbacks:
            del self.data_callbacks[device_id]
        
        # Clear recording status
        if device_id in self.recording_active:
            del self.recording_active[device_id]
    
    def register_data_callback(self, device_id: str, callback: Callable[[GSRDataPoint], None]):
        """Register callback for data updates"""
        self.data_callbacks[device_id] = callback
    
    def get_connected_devices(self) -> List[ShimmerDevice]:
        """Get list of connected devices"""
        return list(self.connected_devices.values())
    
    def get_device_data(self, device_id: str) -> List[GSRDataPoint]:
        """Get session data for device"""
        return self.session_data.get(device_id, [])
    
    def is_recording(self, device_id: str) -> bool:
        """Check if device is recording"""
        return self.recording_active.get(device_id, False)
    
    def cleanup(self):
        """Clean up manager resources"""
        self.logger.info("Cleaning up ShimmerPCManager")
        
        # Set shutdown event
        self.shutdown_event.set()
        
        # Disconnect all devices
        device_ids = list(self.connected_devices.keys())
        for device_id in device_ids:
            self.disconnect_device(device_id)
        
        # Wait for threads to finish
        for thread in self.data_threads.values():
            if thread.is_alive():
                thread.join(timeout=2)
        
        self.logger.info("ShimmerPCManager cleanup complete")