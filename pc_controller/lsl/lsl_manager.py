#!/usr/bin/env python3
"""
LSL (Lab Streaming Layer) Manager for PC Controller Application
Handles LSL stream discovery, recording, and integration with multi-modal capture system.
"""

import logging
import time
import threading
from typing import Dict, Any, Optional, List, Callable
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from PyQt6.QtCore import QObject, pyqtSignal, QTimer

try:
    import pylsl
    LSL_AVAILABLE = True
except ImportError:
    LSL_AVAILABLE = False
    logging.warning("pylsl not available - LSL functionality will be disabled")


class LSLStreamType(Enum):
    """LSL stream types for multi-modal capture."""
    GSR = "GSR"
    PPG = "PPG"
    HEART_RATE = "HeartRate"
    THERMAL_VIDEO = "ThermalVideo"
    AUDIO = "Audio"
    MARKERS = "Markers"
    ACCELEROMETER = "Accelerometer"
    GYROSCOPE = "Gyroscope"
    MAGNETOMETER = "Magnetometer"


@dataclass
class LSLStreamInfo:
    """Information about an LSL stream."""
    name: str
    type: str
    channel_count: int
    nominal_srate: float
    channel_format: str
    source_id: str
    hostname: str
    uid: str
    created_at: float
    desc: Dict[str, Any] = field(default_factory=dict)
    
    @classmethod
    def from_pylsl_info(cls, info) -> 'LSLStreamInfo':
        """Create LSLStreamInfo from pylsl StreamInfo."""
        if not LSL_AVAILABLE:
            raise RuntimeError("pylsl not available")
            
        return cls(
            name=info.name(),
            type=info.type(),
            channel_count=info.channel_count(),
            nominal_srate=info.nominal_srate(),
            channel_format=info.channel_format_string(),
            source_id=info.source_id(),
            hostname=info.hostname(),
            uid=info.uid(),
            created_at=info.created_at()
        )
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'name': self.name,
            'type': self.type,
            'channel_count': self.channel_count,
            'nominal_srate': self.nominal_srate,
            'channel_format': self.channel_format,
            'source_id': self.source_id,
            'hostname': self.hostname,
            'uid': self.uid,
            'created_at': self.created_at,
            'desc': self.desc
        }


@dataclass
class LSLStreamData:
    """Container for LSL stream data."""
    stream_info: LSLStreamInfo
    samples: List[List[float]] = field(default_factory=list)
    timestamps: List[float] = field(default_factory=list)
    
    def add_sample(self, sample: List[float], timestamp: float):
        """Add a sample with timestamp."""
        self.samples.append(sample)
        self.timestamps.append(timestamp)
        
    def clear(self):
        """Clear all data."""
        self.samples.clear()
        self.timestamps.clear()
        
    def get_sample_count(self) -> int:
        """Get number of samples."""
        return len(self.samples)


class LSLStreamRecorder:
    """Records data from a single LSL stream."""
    
    def __init__(self, stream_info: LSLStreamInfo):
        self.stream_info = stream_info
        self.inlet = None
        self.recording = False
        self.data = LSLStreamData(stream_info)
        self.record_thread = None
        
    def start_recording(self) -> bool:
        """Start recording from the stream."""
        if not LSL_AVAILABLE:
            logging.error("pylsl not available for recording")
            return False
            
        try:
            # Create inlet for this stream
            info = pylsl.StreamInfo(
                self.stream_info.name,
                self.stream_info.type,
                self.stream_info.channel_count,
                self.stream_info.nominal_srate,
                self.stream_info.channel_format,
                self.stream_info.source_id
            )
            
            # Find matching streams
            streams = pylsl.resolve_stream('name', self.stream_info.name, timeout=5.0)
            if not streams:
                logging.error(f"Could not find LSL stream: {self.stream_info.name}")
                return False
                
            # Create inlet
            self.inlet = pylsl.StreamInlet(streams[0])
            
            # Start recording thread
            self.recording = True
            self.record_thread = threading.Thread(target=self._record_loop)
            self.record_thread.daemon = True
            self.record_thread.start()
            
            logging.info(f"Started recording LSL stream: {self.stream_info.name}")
            return True
            
        except Exception as e:
            logging.error(f"Failed to start LSL stream recording: {e}")
            return False
            
    def stop_recording(self):
        """Stop recording from the stream."""
        self.recording = False
        if self.record_thread:
            self.record_thread.join(timeout=5.0)
            
        if self.inlet:
            self.inlet.close_stream()
            self.inlet = None
            
        logging.info(f"Stopped recording LSL stream: {self.stream_info.name}")
        
    def _record_loop(self):
        """Main recording loop."""
        if not self.inlet:
            return
            
        try:
            while self.recording:
                # Pull samples from stream
                samples, timestamps = self.inlet.pull_chunk(timeout=0.1)
                
                if samples:
                    for sample, timestamp in zip(samples, timestamps):
                        self.data.add_sample(sample, timestamp)
                        
        except Exception as e:
            logging.error(f"Error in LSL recording loop: {e}")
            
    def get_data(self) -> LSLStreamData:
        """Get recorded data."""
        return self.data


class LSLManager(QObject):
    """Main LSL manager for stream discovery and recording."""
    
    # Signals
    stream_discovered = pyqtSignal(dict)  # stream_info
    stream_lost = pyqtSignal(str)  # stream_uid
    recording_started = pyqtSignal(str)  # session_id
    recording_stopped = pyqtSignal(str, dict)  # session_id, summary
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.available_streams = {}  # uid -> LSLStreamInfo
        self.stream_recorders = {}  # uid -> LSLStreamRecorder
        self.recording_session = None
        self.discovery_timer = QTimer()
        self.discovery_timer.timeout.connect(self.discover_streams)
        
        # Stream type filters for multi-modal capture
        self.target_stream_types = {
            LSLStreamType.GSR.value,
            LSLStreamType.PPG.value,
            LSLStreamType.HEART_RATE.value,
            LSLStreamType.THERMAL_VIDEO.value,
            LSLStreamType.AUDIO.value,
            LSLStreamType.MARKERS.value
        }
        
        logging.info("LSL Manager initialized")
        
    def start_discovery(self, interval_ms: int = 5000):
        """Start automatic stream discovery."""
        if not LSL_AVAILABLE:
            logging.warning("LSL not available - discovery disabled")
            return
            
        self.discovery_timer.start(interval_ms)
        self.discover_streams()  # Initial discovery
        logging.info("Started LSL stream discovery")
        
    def stop_discovery(self):
        """Stop automatic stream discovery."""
        self.discovery_timer.stop()
        logging.info("Stopped LSL stream discovery")
        
    def discover_streams(self):
        """Discover available LSL streams."""
        if not LSL_AVAILABLE:
            return
            
        try:
            # Resolve all streams
            streams = pylsl.resolve_streams(wait_time=1.0)
            
            current_uids = set()
            
            for stream in streams:
                stream_info = LSLStreamInfo.from_pylsl_info(stream)
                current_uids.add(stream_info.uid)
                
                # Check if this is a new stream
                if stream_info.uid not in self.available_streams:
                    # Filter for relevant stream types
                    if stream_info.type in self.target_stream_types:
                        self.available_streams[stream_info.uid] = stream_info
                        self.stream_discovered.emit(stream_info.to_dict())
                        logging.info(f"Discovered LSL stream: {stream_info.name} ({stream_info.type})")
                        
            # Check for lost streams
            lost_streams = set(self.available_streams.keys()) - current_uids
            for uid in lost_streams:
                stream_info = self.available_streams[uid]
                del self.available_streams[uid]
                self.stream_lost.emit(uid)
                logging.info(f"Lost LSL stream: {stream_info.name}")
                
        except Exception as e:
            logging.error(f"Error during LSL stream discovery: {e}")
            
    def get_available_streams(self) -> List[LSLStreamInfo]:
        """Get list of available streams."""
        return list(self.available_streams.values())
        
    def get_streams_by_type(self, stream_type: str) -> List[LSLStreamInfo]:
        """Get streams of a specific type."""
        return [stream for stream in self.available_streams.values() 
                if stream.type == stream_type]
                
    def start_recording_session(self, session_id: str, 
                               stream_uids: Optional[List[str]] = None) -> bool:
        """Start recording session with specified streams."""
        if self.recording_session:
            logging.warning("Recording session already active")
            return False
            
        if not LSL_AVAILABLE:
            logging.error("LSL not available for recording")
            return False
            
        # Use all available streams if none specified
        if stream_uids is None:
            stream_uids = list(self.available_streams.keys())
            
        # Create recorders for selected streams
        recorders = {}
        for uid in stream_uids:
            if uid in self.available_streams:
                stream_info = self.available_streams[uid]
                recorder = LSLStreamRecorder(stream_info)
                if recorder.start_recording():
                    recorders[uid] = recorder
                else:
                    logging.error(f"Failed to start recording for stream: {stream_info.name}")
                    
        if not recorders:
            logging.error("No streams could be started for recording")
            return False
            
        self.recording_session = session_id
        self.stream_recorders = recorders
        
        self.recording_started.emit(session_id)
        logging.info(f"Started LSL recording session: {session_id} with {len(recorders)} streams")
        return True
        
    def stop_recording_session(self) -> Optional[Dict[str, Any]]:
        """Stop current recording session."""
        if not self.recording_session:
            logging.warning("No active recording session")
            return None
            
        session_id = self.recording_session
        
        # Stop all recorders and collect data
        recorded_data = {}
        total_samples = 0
        
        for uid, recorder in self.stream_recorders.items():
            recorder.stop_recording()
            data = recorder.get_data()
            recorded_data[uid] = {
                'stream_info': data.stream_info.to_dict(),
                'sample_count': data.get_sample_count(),
                'samples': data.samples,
                'timestamps': data.timestamps
            }
            total_samples += data.get_sample_count()
            
        # Create summary
        summary = {
            'session_id': session_id,
            'stream_count': len(self.stream_recorders),
            'total_samples': total_samples,
            'recorded_data': recorded_data,
            'end_time': time.time()
        }
        
        # Clean up
        self.recording_session = None
        self.stream_recorders.clear()
        
        self.recording_stopped.emit(session_id, summary)
        logging.info(f"Stopped LSL recording session: {session_id}")
        
        return summary
        
    def is_recording(self) -> bool:
        """Check if currently recording."""
        return self.recording_session is not None
        
    def get_recording_status(self) -> Dict[str, Any]:
        """Get current recording status."""
        if not self.recording_session:
            return {'recording': False}
            
        status = {
            'recording': True,
            'session_id': self.recording_session,
            'stream_count': len(self.stream_recorders),
            'streams': {}
        }
        
        for uid, recorder in self.stream_recorders.items():
            stream_info = recorder.stream_info
            data = recorder.get_data()
            status['streams'][uid] = {
                'name': stream_info.name,
                'type': stream_info.type,
                'sample_count': data.get_sample_count()
            }
            
        return status
        
    def export_to_xdf(self, session_data: Dict[str, Any], output_path: str) -> bool:
        """Export recorded data to XDF format."""
        try:
            # This would require pyxdf library for XDF export
            # For now, save as JSON with XDF-like structure
            import json
            
            xdf_data = {
                'info': {
                    'version': '1.0',
                    'created': time.time()
                },
                'streams': []
            }
            
            for uid, stream_data in session_data['recorded_data'].items():
                stream_entry = {
                    'info': stream_data['stream_info'],
                    'time_series': stream_data['samples'],
                    'time_stamps': stream_data['timestamps']
                }
                xdf_data['streams'].append(stream_entry)
                
            with open(output_path, 'w') as f:
                json.dump(xdf_data, f, indent=2, default=str)
                
            logging.info(f"Exported LSL data to: {output_path}")
            return True
            
        except Exception as e:
            logging.error(f"Failed to export LSL data: {e}")
            return False
            
    def cleanup(self):
        """Cleanup LSL manager resources."""
        self.stop_discovery()
        if self.recording_session:
            self.stop_recording_session()
        logging.info("LSL Manager cleaned up")


class LSLStreamPublisher:
    """Publishes data as an LSL stream."""
    
    def __init__(self, name: str, stream_type: str, channel_count: int,
                 nominal_srate: float, channel_format: str = 'float32',
                 source_id: str = ''):
        self.name = name
        self.stream_type = stream_type
        self.channel_count = channel_count
        self.nominal_srate = nominal_srate
        self.channel_format = channel_format
        self.source_id = source_id
        self.outlet = None
        self.active = False
        
    def start_stream(self) -> bool:
        """Start publishing the LSL stream."""
        if not LSL_AVAILABLE:
            logging.error("pylsl not available for stream publishing")
            return False
            
        try:
            # Create stream info
            info = pylsl.StreamInfo(
                self.name,
                self.stream_type,
                self.channel_count,
                self.nominal_srate,
                self.channel_format,
                self.source_id
            )
            
            # Add metadata
            desc = info.desc()
            desc.append_child_value("manufacturer", "MultiModalCapture")
            desc.append_child_value("version", "1.0")
            
            # Create outlet
            self.outlet = pylsl.StreamOutlet(info)
            self.active = True
            
            logging.info(f"Started LSL stream: {self.name} ({self.stream_type})")
            return True
            
        except Exception as e:
            logging.error(f"Failed to start LSL stream: {e}")
            return False
            
    def stop_stream(self):
        """Stop publishing the LSL stream."""
        self.active = False
        if self.outlet:
            del self.outlet
            self.outlet = None
        logging.info(f"Stopped LSL stream: {self.name}")
        
    def push_sample(self, sample: List[float], timestamp: Optional[float] = None):
        """Push a sample to the stream."""
        if self.active and self.outlet:
            try:
                if timestamp is not None:
                    self.outlet.push_sample(sample, timestamp)
                else:
                    self.outlet.push_sample(sample)
            except Exception as e:
                logging.error(f"Failed to push LSL sample: {e}")
                
    def push_chunk(self, samples: List[List[float]], timestamps: Optional[List[float]] = None):
        """Push multiple samples to the stream."""
        if self.active and self.outlet:
            try:
                if timestamps is not None:
                    self.outlet.push_chunk(samples, timestamps)
                else:
                    self.outlet.push_chunk(samples)
            except Exception as e:
                logging.error(f"Failed to push LSL chunk: {e}")
                
    def is_active(self) -> bool:
        """Check if stream is active."""
        return self.active