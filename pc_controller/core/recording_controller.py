"""
Recording Controller for PC Controller Application.
Manages synchronized recording operations across multiple Android devices.
"""

import json
import logging
import time
import threading
from typing import Dict, List, Optional, Set
from dataclasses import dataclass, asdict
from enum import Enum
from pathlib import Path
from PyQt6.QtCore import QObject, pyqtSignal, QTimer

# Import Shimmer device support
from .shimmer_device import ShimmerDevice

class RecordingState(Enum):
    """Recording state enumeration."""
    IDLE = "idle"
    PREPARING = "preparing"
    RECORDING = "recording"
    STOPPING = "stopping"
    ERROR = "error"

@dataclass
class SessionInfo:
    """Information about a recording session."""
    session_id: str
    start_time: float
    end_time: Optional[float] = None
    device_ids: List[str] = None
    output_directory: str = ""
    manifest_file: str = ""
    total_duration: float = 0.0
    
    def __post_init__(self):
        if self.device_ids is None:
            self.device_ids = []
    
    def to_dict(self) -> Dict:
        """Convert to dictionary."""
        return asdict(self)

@dataclass
class DeviceRecordingStatus:
    """Recording status for a single device."""
    device_id: str
    is_recording: bool = False
    start_timestamp: Optional[float] = None
    last_heartbeat: Optional[float] = None
    error_message: Optional[str] = None
    files_created: List[str] = None
    
    def __post_init__(self):
        if self.files_created is None:
            self.files_created = []

class RecordingController(QObject):
    """Controls synchronized recording across multiple Android devices."""
    
    # Qt signals
    recording_state_changed = pyqtSignal(str)  # RecordingState.value
    session_started = pyqtSignal(dict)         # SessionInfo.to_dict()
    session_stopped = pyqtSignal(dict)         # SessionInfo.to_dict()
    device_recording_status = pyqtSignal(str, bool)  # device_id, is_recording
    recording_error = pyqtSignal(str)          # error_message
    sync_status_updated = pyqtSignal(dict)     # sync statistics
    
    def __init__(self, config):
        super().__init__()
        self.config = config
        self.device_manager = None  # Will be set by main application
        
        # Recording state
        self.state = RecordingState.IDLE
        self.current_session: Optional[SessionInfo] = None
        self.device_statuses: Dict[str, DeviceRecordingStatus] = {}
        
        # Synchronization
        self.sync_tolerance_ms = config.recording.sync_tolerance_ms
        self.heartbeat_timer = QTimer()
        self.heartbeat_timer.timeout.connect(self._check_device_heartbeats)
        
        # Session management
        self.sessions_history: List[SessionInfo] = []
        
        logging.info("RecordingController initialized")
    
    def set_device_manager(self, device_manager):
        """Set the device manager reference."""
        self.device_manager = device_manager
        
        # Connect to device manager signals
        device_manager.device_connected.connect(self._on_device_connected)
        device_manager.device_disconnected.connect(self._on_device_disconnected)
        device_manager.device_data_received.connect(self._on_device_data_received)
    
    def start_recording(self, device_ids: Optional[List[str]] = None) -> bool:
        """Start synchronized recording on specified devices."""
        if self.state != RecordingState.IDLE:
            logging.error(f"Cannot start recording in state: {self.state}")
            return False
        
        if not self.device_manager:
            logging.error("Device manager not set")
            return False
        
        # Use all connected devices if none specified
        if device_ids is None:
            connected_devices = self.device_manager.get_connected_devices()
            device_ids = [device.device_id for device in connected_devices]
        
        if not device_ids:
            logging.error("No devices available for recording")
            self.recording_error.emit("No devices available for recording")
            return False
        
        # Validate all devices are connected
        connected_device_ids = {
            device.device_id for device in self.device_manager.get_connected_devices()
        }
        
        invalid_devices = set(device_ids) - connected_device_ids
        if invalid_devices:
            error_msg = f"Devices not connected: {', '.join(invalid_devices)}"
            logging.error(error_msg)
            self.recording_error.emit(error_msg)
            return False
        
        try:
            # Change state to preparing
            self._set_state(RecordingState.PREPARING)
            
            # Create session
            session_id = self.config.get_session_id()
            output_dir = self.config.get_output_directory() / session_id
            output_dir.mkdir(parents=True, exist_ok=True)
            
            self.current_session = SessionInfo(
                session_id=session_id,
                start_time=time.time(),
                device_ids=device_ids.copy(),
                output_directory=str(output_dir)
            )
            
            # Initialize device statuses
            self.device_statuses.clear()
            for device_id in device_ids:
                self.device_statuses[device_id] = DeviceRecordingStatus(device_id=device_id)
            
            # Send start commands to all devices
            start_timestamp = time.time() * 1000  # milliseconds
            success_count = 0
            
            for device_id in device_ids:
                if self._send_start_command(device_id, session_id, start_timestamp):
                    success_count += 1
                else:
                    logging.error(f"Failed to send start command to device: {device_id}")
            
            if success_count == 0:
                self._set_state(RecordingState.ERROR)
                self.recording_error.emit("Failed to start recording on any device")
                return False
            
            # Start heartbeat monitoring
            self.heartbeat_timer.start(5000)  # Check every 5 seconds
            
            # Change state to recording
            self._set_state(RecordingState.RECORDING)
            self.session_started.emit(self.current_session.to_dict())
            
            logging.info(f"Recording started: {session_id} on {success_count}/{len(device_ids)} devices")
            return True
            
        except Exception as e:
            logging.error(f"Failed to start recording: {e}")
            self._set_state(RecordingState.ERROR)
            self.recording_error.emit(f"Failed to start recording: {e}")
            return False
    
    def stop_recording(self) -> bool:
        """Stop synchronized recording on all devices."""
        if self.state != RecordingState.RECORDING:
            logging.error(f"Cannot stop recording in state: {self.state}")
            return False
        
        try:
            # Change state to stopping
            self._set_state(RecordingState.STOPPING)
            
            # Stop heartbeat monitoring
            self.heartbeat_timer.stop()
            
            # Send stop commands to all devices
            if self.current_session:
                for device_id in self.current_session.device_ids:
                    self._send_stop_command(device_id)
            
            # Finalize session
            if self.current_session:
                self.current_session.end_time = time.time()
                self.current_session.total_duration = (
                    self.current_session.end_time - self.current_session.start_time
                )
                
                # Generate session manifest
                self._generate_session_manifest()
                
                # Add to history
                self.sessions_history.append(self.current_session)
                
                # Emit session stopped signal
                self.session_stopped.emit(self.current_session.to_dict())
                
                logging.info(f"Recording stopped: {self.current_session.session_id}")
            
            # Reset state
            self._set_state(RecordingState.IDLE)
            self.current_session = None
            self.device_statuses.clear()
            
            return True
            
        except Exception as e:
            logging.error(f"Failed to stop recording: {e}")
            self._set_state(RecordingState.ERROR)
            self.recording_error.emit(f"Failed to stop recording: {e}")
            return False
    
    def pause_recording(self) -> bool:
        """Pause recording on all devices (if supported)."""
        if self.state != RecordingState.RECORDING:
            logging.error(f"Cannot pause recording in state: {self.state}")
            return False
        
        # Send pause commands to all devices
        if self.current_session:
            for device_id in self.current_session.device_ids:
                self._send_pause_command(device_id)
        
        logging.info("Recording paused")
        return True
    
    def resume_recording(self) -> bool:
        """Resume recording on all devices (if supported)."""
        if self.state != RecordingState.RECORDING:
            logging.error(f"Cannot resume recording in state: {self.state}")
            return False
        
        # Send resume commands to all devices
        if self.current_session:
            for device_id in self.current_session.device_ids:
                self._send_resume_command(device_id)
        
        logging.info("Recording resumed")
        return True
    
    def _send_start_command(self, device_id: str, session_id: str, start_timestamp: float) -> bool:
        """Send start recording command to a device (Android or Shimmer)."""
        try:
            # Get device from device manager
            device = None
            for connected_device in self.device_manager.get_connected_devices():
                if connected_device.device_id == device_id:
                    device = connected_device
                    break
            
            if not device:
                logging.error(f"Device not found: {device_id}")
                return False
            
            # Handle Shimmer devices differently
            if isinstance(device, ShimmerDevice):
                # Use ShimmerPCManager for PC Shimmer devices
                if hasattr(self.device_manager, 'shimmer_pc_manager') and self.device_manager.shimmer_pc_manager:
                    output_dir = str(self.config.get_output_directory() / session_id)
                    return self.device_manager.shimmer_pc_manager.start_recording(
                        device_id, session_id, output_dir
                    )
                else:
                    logging.error("ShimmerPCManager not available")
                    return False
            else:
                # Handle Android devices with network commands
                payload = {
                    'sessionId': session_id,
                    'startTimestamp': start_timestamp,
                    'outputDirectory': str(self.config.get_output_directory())
                }
                return self.device_manager.send_command(device_id, 'CMD_START', payload)
                
        except Exception as e:
            logging.error(f"Failed to send start command to {device_id}: {e}")
            return False
    
    def _send_stop_command(self, device_id: str) -> bool:
        """Send stop recording command to a device (Android or Shimmer)."""
        try:
            # Get device from device manager
            device = None
            for connected_device in self.device_manager.get_connected_devices():
                if connected_device.device_id == device_id:
                    device = connected_device
                    break
            
            if not device:
                logging.error(f"Device not found: {device_id}")
                return False
            
            # Handle Shimmer devices differently
            if isinstance(device, ShimmerDevice):
                # Use ShimmerPCManager for PC Shimmer devices
                if hasattr(self.device_manager, 'shimmer_pc_manager') and self.device_manager.shimmer_pc_manager:
                    return self.device_manager.shimmer_pc_manager.stop_recording(device_id)
                else:
                    logging.error("ShimmerPCManager not available")
                    return False
            else:
                # Handle Android devices with network commands
                return self.device_manager.send_command(device_id, 'CMD_STOP')
                
        except Exception as e:
            logging.error(f"Failed to send stop command to {device_id}: {e}")
            return False
    
    def _send_pause_command(self, device_id: str) -> bool:
        """Send pause recording command to a device."""
        return self.device_manager.send_command(device_id, 'CMD_PAUSE')
    
    def _send_resume_command(self, device_id: str) -> bool:
        """Send resume recording command to a device."""
        return self.device_manager.send_command(device_id, 'CMD_RESUME')
    
    def _set_state(self, new_state: RecordingState):
        """Set recording state and emit signal."""
        if self.state != new_state:
            self.state = new_state
            self.recording_state_changed.emit(new_state.value)
            logging.debug(f"Recording state changed to: {new_state.value}")
    
    def _on_device_connected(self, device_id: str):
        """Handle device connection."""
        logging.debug(f"Device connected: {device_id}")
    
    def _on_device_disconnected(self, device_id: str):
        """Handle device disconnection."""
        logging.warning(f"Device disconnected during recording: {device_id}")
        
        # If recording, mark device as error
        if self.state == RecordingState.RECORDING and device_id in self.device_statuses:
            status = self.device_statuses[device_id]
            status.error_message = "Device disconnected"
            self.device_recording_status.emit(device_id, False)
    
    def _on_device_data_received(self, device_id: str, data: Dict):
        """Handle data received from device."""
        try:
            msg_type = data.get('type', '')
            
            if msg_type == 'RECORDING_STARTED':
                self._handle_recording_started(device_id, data)
            elif msg_type == 'RECORDING_STOPPED':
                self._handle_recording_stopped(device_id, data)
            elif msg_type == 'STATUS_UPDATE':
                self._handle_status_update(device_id, data)
            elif msg_type == 'HEARTBEAT':
                self._handle_heartbeat(device_id, data)
            
        except Exception as e:
            logging.error(f"Error processing data from {device_id}: {e}")
    
    def _handle_recording_started(self, device_id: str, data: Dict):
        """Handle recording started notification from device."""
        if device_id in self.device_statuses:
            status = self.device_statuses[device_id]
            status.is_recording = True
            status.start_timestamp = data.get('timestamp', time.time())
            status.last_heartbeat = time.time()
            
            self.device_recording_status.emit(device_id, True)
            logging.info(f"Device {device_id} started recording")
    
    def _handle_recording_stopped(self, device_id: str, data: Dict):
        """Handle recording stopped notification from device."""
        if device_id in self.device_statuses:
            status = self.device_statuses[device_id]
            status.is_recording = False
            
            # Extract file information if provided
            payload = data.get('payload', {})
            if 'files' in payload:
                status.files_created = payload['files']
            
            self.device_recording_status.emit(device_id, False)
            logging.info(f"Device {device_id} stopped recording")
    
    def _handle_status_update(self, device_id: str, data: Dict):
        """Handle status update from device."""
        if device_id in self.device_statuses:
            status = self.device_statuses[device_id]
            status.last_heartbeat = time.time()
            
            payload = data.get('payload', {})
            if 'isRecording' in payload:
                status.is_recording = payload['isRecording']
                self.device_recording_status.emit(device_id, status.is_recording)
    
    def _handle_heartbeat(self, device_id: str, data: Dict):
        """Handle heartbeat from device."""
        if device_id in self.device_statuses:
            status = self.device_statuses[device_id]
            status.last_heartbeat = time.time()
    
    def _check_device_heartbeats(self):
        """Check device heartbeats and detect stale connections."""
        if self.state != RecordingState.RECORDING:
            return
        
        current_time = time.time()
        stale_devices = []
        
        for device_id, status in self.device_statuses.items():
            if status.last_heartbeat and (current_time - status.last_heartbeat) > 30.0:
                stale_devices.append(device_id)
        
        for device_id in stale_devices:
            logging.warning(f"Device {device_id} heartbeat timeout")
            status = self.device_statuses[device_id]
            status.error_message = "Heartbeat timeout"
            self.device_recording_status.emit(device_id, False)
    
    def _generate_session_manifest(self):
        """Generate session manifest file."""
        if not self.current_session:
            return
        
        try:
            manifest_data = {
                'session_info': self.current_session.to_dict(),
                'devices': {},
                'sync_info': self._calculate_sync_statistics(),
                'generated_at': time.time()
            }
            
            # Add device information
            for device_id, status in self.device_statuses.items():
                device_info = self.device_manager.devices.get(device_id)
                manifest_data['devices'][device_id] = {
                    'device_name': device_info.device_name if device_info else 'Unknown',
                    'ip_address': device_info.ip_address if device_info else 'Unknown',
                    'recording_status': {
                        'is_recording': status.is_recording,
                        'start_timestamp': status.start_timestamp,
                        'error_message': status.error_message,
                        'files_created': status.files_created
                    }
                }
            
            # Save manifest file
            manifest_file = Path(self.current_session.output_directory) / "session_manifest.json"
            with open(manifest_file, 'w') as f:
                json.dump(manifest_data, f, indent=2)
            
            self.current_session.manifest_file = str(manifest_file)
            logging.info(f"Session manifest generated: {manifest_file}")
            
        except Exception as e:
            logging.error(f"Failed to generate session manifest: {e}")
    
    def _calculate_sync_statistics(self) -> Dict:
        """Calculate synchronization statistics."""
        if not self.device_statuses:
            return {}
        
        start_timestamps = [
            status.start_timestamp for status in self.device_statuses.values()
            if status.start_timestamp is not None
        ]
        
        if len(start_timestamps) < 2:
            return {'sync_quality': 'insufficient_data'}
        
        min_timestamp = min(start_timestamps)
        max_timestamp = max(start_timestamps)
        sync_spread_ms = (max_timestamp - min_timestamp) * 1000
        
        sync_quality = "excellent" if sync_spread_ms <= 10 else \
                      "good" if sync_spread_ms <= 50 else \
                      "fair" if sync_spread_ms <= 100 else "poor"
        
        return {
            'sync_quality': sync_quality,
            'sync_spread_ms': sync_spread_ms,
            'device_count': len(start_timestamps),
            'min_timestamp': min_timestamp,
            'max_timestamp': max_timestamp
        }
    
    def get_current_session(self) -> Optional[SessionInfo]:
        """Get current recording session info."""
        return self.current_session
    
    def get_recording_state(self) -> RecordingState:
        """Get current recording state."""
        return self.state
    
    def get_device_recording_status(self, device_id: str) -> Optional[DeviceRecordingStatus]:
        """Get recording status for a specific device."""
        return self.device_statuses.get(device_id)
    
    def get_sessions_history(self) -> List[SessionInfo]:
        """Get list of previous recording sessions."""
        return self.sessions_history.copy()
    
    def cleanup(self):
        """Cleanup recording controller resources."""
        logging.info("Cleaning up RecordingController")
        
        # Stop any active recording
        if self.state == RecordingState.RECORDING:
            self.stop_recording()
        
        # Stop timers
        self.heartbeat_timer.stop()
        
        # Clear state
        self.current_session = None
        self.device_statuses.clear()
        self._set_state(RecordingState.IDLE)