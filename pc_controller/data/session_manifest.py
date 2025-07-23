#!/usr/bin/env python3
"""
Session Manifest Generation for PC Controller Application
Creates comprehensive session metadata and manifest files for data organization.
"""

import json
import logging
import os
import time
from typing import Dict, Any, Optional, List, Tuple
from datetime import datetime, timezone
from dataclasses import dataclass, asdict, field
from pathlib import Path
from enum import Enum

from core.device_manager import AndroidDevice, DeviceStatus
from core.recording_controller import SessionInfo
from core.sync_events import SyncEvent
from core.time_sync import SyncQuality, DeviceSyncStatus


class DataModality(Enum):
    """Types of data modalities captured."""
    RGB_VIDEO = "rgb_video"
    THERMAL_VIDEO = "thermal_video"
    AUDIO = "audio"
    GSR = "gsr"
    PPG = "ppg"
    HEART_RATE = "heart_rate"
    ACCELEROMETER = "accelerometer"
    GYROSCOPE = "gyroscope"
    MAGNETOMETER = "magnetometer"
    TEMPERATURE = "temperature"
    SYNC_EVENTS = "sync_events"
    SYSTEM_LOGS = "system_logs"

class SyncAnalysisResult(Enum):
    """Results of synchronization analysis."""
    EXCELLENT = "excellent"
    GOOD = "good"
    FAIR = "fair"
    POOR = "poor"
    FAILED = "failed"

@dataclass
class DeviceSyncMetrics:
    """Synchronization metrics for a specific device."""
    device_id: str
    sync_quality: str  # SyncQuality enum value as string
    average_offset_ms: float
    offset_std_dev_ms: float
    max_offset_ms: float
    sync_success_rate: float
    total_sync_attempts: int
    failed_sync_attempts: int
    last_sync_timestamp: float
    coordination_id: str = ""
    network_metrics: Dict[str, Any] = field(default_factory=dict)

@dataclass
class MultiDeviceCoordinationInfo:
    """Information about multi-device coordination."""
    session_id: str
    coordinator_device: str
    participating_devices: List[str]
    sync_markers: List[Dict[str, Any]] = field(default_factory=list)
    coordination_events: List[Dict[str, Any]] = field(default_factory=list)
    inter_device_sync_quality: str = "poor"  # SyncAnalysisResult enum value as string
    max_inter_device_offset_ms: float = 0.0
    coordination_success_rate: float = 0.0

@dataclass
class EnhancedQualityMetrics:
    """Enhanced quality metrics for session analysis."""
    overall_score: float = 0.0
    sync_quality_score: float = 0.0
    data_completeness_score: float = 0.0
    temporal_alignment_score: float = 0.0
    multi_device_coordination_score: float = 0.0
    device_sync_metrics: Dict[str, DeviceSyncMetrics] = field(default_factory=dict)
    coordination_info: Optional[MultiDeviceCoordinationInfo] = None
    quality_issues: List[str] = field(default_factory=list)
    recommendations: List[str] = field(default_factory=list)


@dataclass
class FileMetadata:
    """Metadata for a recorded data file."""
    file_path: str
    file_name: str
    file_size: int
    modality: DataModality
    device_id: str
    start_timestamp: float
    end_timestamp: float
    duration_seconds: float
    sample_rate: Optional[float] = None
    resolution: Optional[Tuple[int, int]] = None
    format: str = ""
    compression: str = ""
    checksum: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        data = asdict(self)
        data['modality'] = self.modality.value
        return data
        
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'FileMetadata':
        """Create from dictionary."""
        data = data.copy()
        data['modality'] = DataModality(data['modality'])
        return cls(**data)


@dataclass
class DeviceConfiguration:
    """Configuration settings for a device during the session."""
    device_id: str
    device_name: str
    device_model: str
    os_version: str
    app_version: str
    ip_address: str
    connection_type: str
    recording_settings: Dict[str, Any] = field(default_factory=dict)
    sensor_settings: Dict[str, Any] = field(default_factory=dict)
    calibration_data: Dict[str, Any] = field(default_factory=dict)
    sync_quality: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return asdict(self)
        
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'DeviceConfiguration':
        """Create from dictionary."""
        return cls(**data)


@dataclass
class SessionManifest:
    """Complete session manifest with all metadata."""
    session_id: str
    session_name: str
    description: str
    participant_id: str
    experiment_type: str
    created_timestamp: float
    start_timestamp: float
    end_timestamp: Optional[float] = None
    duration_seconds: Optional[float] = None
    pc_info: Dict[str, Any] = field(default_factory=dict)
    devices: List[DeviceConfiguration] = field(default_factory=list)
    files: List[FileMetadata] = field(default_factory=list)
    sync_events: List[Dict[str, Any]] = field(default_factory=list)
    sync_statistics: Dict[str, Any] = field(default_factory=dict)
    quality_metrics: Dict[str, Any] = field(default_factory=dict)
    notes: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    version: str = "1.0"
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        data = asdict(self)
        # Convert device configurations
        data['devices'] = [device.to_dict() for device in self.devices]
        # Convert file metadata
        data['files'] = [file_meta.to_dict() for file_meta in self.files]
        return data
        
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'SessionManifest':
        """Create from dictionary."""
        data = data.copy()
        # Convert device configurations
        if 'devices' in data:
            data['devices'] = [DeviceConfiguration.from_dict(d) for d in data['devices']]
        # Convert file metadata
        if 'files' in data:
            data['files'] = [FileMetadata.from_dict(f) for f in data['files']]
        return cls(**data)


class SessionManifestGenerator:
    """Generates comprehensive session manifests."""
    
    def __init__(self, output_directory: str = "sessions"):
        self.output_directory = Path(output_directory)
        self.output_directory.mkdir(parents=True, exist_ok=True)
        self.current_manifest: Optional[SessionManifest] = None
        self.file_watchers = {}  # file_path -> metadata
        
    def create_session_manifest(self, session_info: SessionInfo, 
                               devices: List[AndroidDevice],
                               pc_info: Dict[str, Any] = None) -> SessionManifest:
        """Create a new session manifest."""
        current_time = time.time()
        
        # Create PC info if not provided
        if pc_info is None:
            pc_info = self.get_pc_info()
            
        # Create device configurations
        device_configs = []
        for device in devices:
            config = DeviceConfiguration(
                device_id=device.device_id,
                device_name=device.name,
                device_model=device.model or "Unknown",
                os_version=device.os_version or "Unknown",
                app_version=device.app_version or "Unknown",
                ip_address=device.ip_address,
                connection_type="WiFi"  # Default, could be enhanced
            )
            device_configs.append(config)
            
        # Create manifest
        manifest = SessionManifest(
            session_id=session_info.session_id,
            session_name=session_info.session_name or session_info.session_id,
            description=session_info.description or "",
            participant_id=session_info.participant_id or "",
            experiment_type=session_info.experiment_type or "General",
            created_timestamp=current_time,
            start_timestamp=session_info.start_time or current_time,
            pc_info=pc_info,
            devices=device_configs
        )
        
        self.current_manifest = manifest
        logging.info(f"Created session manifest for {manifest.session_id}")
        return manifest
        
    def add_file_metadata(self, file_path: str, modality: DataModality, 
                         device_id: str, start_timestamp: float,
                         end_timestamp: float, **kwargs) -> FileMetadata:
        """Add file metadata to the current manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        file_path_obj = Path(file_path)
        
        # Get file information
        file_size = file_path_obj.stat().st_size if file_path_obj.exists() else 0
        
        # Create file metadata
        file_metadata = FileMetadata(
            file_path=str(file_path_obj.absolute()),
            file_name=file_path_obj.name,
            file_size=file_size,
            modality=modality,
            device_id=device_id,
            start_timestamp=start_timestamp,
            end_timestamp=end_timestamp,
            duration_seconds=end_timestamp - start_timestamp,
            **kwargs
        )
        
        # Calculate checksum if file exists
        if file_path_obj.exists():
            file_metadata.checksum = self.calculate_file_checksum(file_path)
            
        self.current_manifest.files.append(file_metadata)
        logging.debug(f"Added file metadata: {file_metadata.file_name}")
        return file_metadata
        
    def add_sync_events(self, events: List[SyncEvent]):
        """Add synchronization events to the manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        for event in events:
            event_dict = event.to_dict() if hasattr(event, 'to_dict') else event
            self.current_manifest.sync_events.append(event_dict)
            
        logging.debug(f"Added {len(events)} sync events to manifest")
        
    def update_sync_statistics(self, statistics: Dict[str, Any]):
        """Update synchronization statistics in the manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        self.current_manifest.sync_statistics.update(statistics)
        
    def update_quality_metrics(self, metrics: Dict[str, Any]):
        """Update quality metrics in the manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        self.current_manifest.quality_metrics.update(metrics)
        
    def add_device_configuration(self, device_id: str, config_data: Dict[str, Any]):
        """Add or update device configuration."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        # Find existing device configuration
        for device_config in self.current_manifest.devices:
            if device_config.device_id == device_id:
                # Update existing configuration
                if 'recording_settings' in config_data:
                    device_config.recording_settings.update(config_data['recording_settings'])
                if 'sensor_settings' in config_data:
                    device_config.sensor_settings.update(config_data['sensor_settings'])
                if 'calibration_data' in config_data:
                    device_config.calibration_data.update(config_data['calibration_data'])
                if 'sync_quality' in config_data:
                    device_config.sync_quality.update(config_data['sync_quality'])
                break
                
    def finalize_session(self, end_timestamp: Optional[float] = None):
        """Finalize the session manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        if end_timestamp is None:
            end_timestamp = time.time()
            
        self.current_manifest.end_timestamp = end_timestamp
        self.current_manifest.duration_seconds = (
            end_timestamp - self.current_manifest.start_timestamp
        )
        
        # Update file metadata for any files that are still being written
        self.update_file_metadata()
        
        logging.info(f"Finalized session manifest: {self.current_manifest.session_id}")
        
    def save_manifest(self, filename: Optional[str] = None) -> str:
        """Save the manifest to a JSON file."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        if filename is None:
            filename = f"{self.current_manifest.session_id}_manifest.json"
            
        output_path = self.output_directory / filename
        
        try:
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(self.current_manifest.to_dict(), f, indent=2, default=str)
                
            logging.info(f"Saved session manifest to {output_path}")
            return str(output_path)
            
        except Exception as e:
            logging.error(f"Failed to save manifest: {e}")
            raise
            
    def load_manifest(self, filename: str) -> SessionManifest:
        """Load a manifest from a JSON file."""
        file_path = Path(filename)
        if not file_path.is_absolute():
            file_path = self.output_directory / file_path
            
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                
            manifest = SessionManifest.from_dict(data)
            logging.info(f"Loaded session manifest from {file_path}")
            return manifest
            
        except Exception as e:
            logging.error(f"Failed to load manifest: {e}")
            raise
            
    def get_pc_info(self) -> Dict[str, Any]:
        """Get PC system information."""
        import platform
        import psutil
        
        try:
            return {
                'hostname': platform.node(),
                'platform': platform.platform(),
                'processor': platform.processor(),
                'python_version': platform.python_version(),
                'memory_gb': round(psutil.virtual_memory().total / (1024**3), 2),
                'disk_free_gb': round(psutil.disk_usage('/').free / (1024**3), 2),
                'timestamp': time.time()
            }
        except Exception as e:
            logging.warning(f"Failed to get PC info: {e}")
            return {
                'hostname': 'unknown',
                'platform': platform.platform(),
                'timestamp': time.time()
            }
            
    def calculate_file_checksum(self, file_path: str, algorithm: str = 'md5') -> str:
        """Calculate file checksum."""
        import hashlib
        
        try:
            hash_obj = hashlib.new(algorithm)
            with open(file_path, 'rb') as f:
                for chunk in iter(lambda: f.read(4096), b""):
                    hash_obj.update(chunk)
            return hash_obj.hexdigest()
        except Exception as e:
            logging.warning(f"Failed to calculate checksum for {file_path}: {e}")
            return ""
            
    def update_file_metadata(self):
        """Update metadata for files that may have changed."""
        if not self.current_manifest:
            return
            
        for file_meta in self.current_manifest.files:
            file_path = Path(file_meta.file_path)
            if file_path.exists():
                # Update file size
                current_size = file_path.stat().st_size
                if current_size != file_meta.file_size:
                    file_meta.file_size = current_size
                    # Recalculate checksum
                    file_meta.checksum = self.calculate_file_checksum(str(file_path))
                    
    def generate_summary_report(self) -> Dict[str, Any]:
        """Generate a summary report of the session."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        manifest = self.current_manifest
        
        # Calculate file statistics
        total_files = len(manifest.files)
        total_size_mb = sum(f.file_size for f in manifest.files) / (1024 * 1024)
        
        # Group files by modality
        modality_counts = {}
        modality_sizes = {}
        for file_meta in manifest.files:
            modality = file_meta.modality.value
            modality_counts[modality] = modality_counts.get(modality, 0) + 1
            modality_sizes[modality] = modality_sizes.get(modality, 0) + file_meta.file_size
            
        # Device statistics
        device_count = len(manifest.devices)
        
        return {
            'session_id': manifest.session_id,
            'session_name': manifest.session_name,
            'duration_minutes': round(manifest.duration_seconds / 60, 2) if manifest.duration_seconds else 0,
            'device_count': device_count,
            'total_files': total_files,
            'total_size_mb': round(total_size_mb, 2),
            'modality_counts': modality_counts,
            'modality_sizes_mb': {k: round(v / (1024 * 1024), 2) for k, v in modality_sizes.items()},
            'sync_events_count': len(manifest.sync_events),
            'quality_score': self.calculate_quality_score(),
            'created_at': datetime.fromtimestamp(manifest.created_timestamp).isoformat(),
            'completed_at': datetime.fromtimestamp(manifest.end_timestamp).isoformat() if manifest.end_timestamp else None
        }
        
    def calculate_quality_score(self) -> float:
        """Calculate an overall quality score for the session."""
        if not self.current_manifest or not self.current_manifest.quality_metrics:
            return 0.0
            
        # This is a simplified quality score calculation
        # In practice, this would be more sophisticated
        metrics = self.current_manifest.quality_metrics
        
        score = 100.0  # Start with perfect score
        
        # Deduct points for sync issues
        if 'average_offset_ms' in metrics:
            offset = metrics['average_offset_ms']
            if offset > 50:
                score -= 30
            elif offset > 25:
                score -= 15
            elif offset > 10:
                score -= 5
                
        # Deduct points for missing data
        expected_modalities = len(DataModality) - 2  # Exclude sync_events and system_logs
        actual_modalities = len(set(f.modality for f in self.current_manifest.files))
        completeness = actual_modalities / expected_modalities
        score *= completeness
        
        return max(0.0, min(100.0, score))
        
    def add_note(self, note: str):
        """Add a note to the session manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        timestamp = datetime.now().isoformat()
        self.current_manifest.notes.append(f"[{timestamp}] {note}")
        
    def add_tags(self, tags: List[str]):
        """Add tags to the session manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        for tag in tags:
            if tag not in self.current_manifest.tags:
                self.current_manifest.tags.append(tag)
                
    def export_manifest_formats(self, formats: List[str] = None) -> Dict[str, str]:
        """Export manifest in multiple formats."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        if formats is None:
            formats = ['json', 'yaml', 'csv']
            
        exported_files = {}
        
        for format_type in formats:
            try:
                if format_type == 'json':
                    filename = f"{self.current_manifest.session_id}_manifest.json"
                    exported_files['json'] = self.save_manifest(filename)
                    
                elif format_type == 'yaml':
                    filename = f"{self.current_manifest.session_id}_manifest.yaml"
                    exported_files['yaml'] = self.export_yaml(filename)
                    
                elif format_type == 'csv':
                    filename = f"{self.current_manifest.session_id}_files.csv"
                    exported_files['csv'] = self.export_csv(filename)
                    
            except Exception as e:
                logging.error(f"Failed to export {format_type} format: {e}")
                
        return exported_files
        
    def export_yaml(self, filename: str) -> str:
        """Export manifest as YAML."""
        try:
            import yaml
            output_path = self.output_directory / filename
            
            with open(output_path, 'w', encoding='utf-8') as f:
                yaml.dump(self.current_manifest.to_dict(), f, default_flow_style=False)
                
            return str(output_path)
        except ImportError:
            logging.warning("PyYAML not available for YAML export")
            return ""
            
    def export_csv(self, filename: str) -> str:
        """Export file metadata as CSV."""
        import csv
        
        output_path = self.output_directory / filename
        
        with open(output_path, 'w', newline='', encoding='utf-8') as f:
            if not self.current_manifest.files:
                return str(output_path)
                
            writer = csv.DictWriter(f, fieldnames=self.current_manifest.files[0].to_dict().keys())
            writer.writeheader()
            
            for file_meta in self.current_manifest.files:
                writer.writerow(file_meta.to_dict())
                
        return str(output_path)

    # Enhanced methods for comprehensive session analysis

    def analyze_device_synchronization(self, device_sync_statuses: Dict[str, DeviceSyncStatus]) -> Dict[str, DeviceSyncMetrics]:
        """Analyze synchronization quality for all devices."""
        device_metrics = {}
        
        for device_id, sync_status in device_sync_statuses.items():
            if not sync_status.measurements:
                continue
                
            # Calculate synchronization metrics
            offsets = [m.offset * 1000 for m in sync_status.measurements]  # Convert to ms
            rtts = [m.round_trip_time * 1000 for m in sync_status.measurements]
            
            import statistics
            avg_offset = statistics.mean(offsets) if offsets else 0.0
            offset_std = statistics.stdev(offsets) if len(offsets) > 1 else 0.0
            max_offset = max(abs(o) for o in offsets) if offsets else 0.0
            
            # Determine sync quality
            if sync_status.uncertainty and sync_status.uncertainty < 0.005:  # 5ms
                quality = "excellent"
            elif sync_status.uncertainty and sync_status.uncertainty < 0.020:  # 20ms
                quality = "good"
            elif sync_status.uncertainty and sync_status.uncertainty < 0.050:  # 50ms
                quality = "fair"
            else:
                quality = "poor"
            
            # Calculate success rate
            total_attempts = len(sync_status.measurements)
            successful_attempts = sum(1 for m in sync_status.measurements if m.quality > 0.5)
            success_rate = successful_attempts / total_attempts if total_attempts > 0 else 0.0
            
            device_metrics[device_id] = DeviceSyncMetrics(
                device_id=device_id,
                sync_quality=quality,
                average_offset_ms=avg_offset,
                offset_std_dev_ms=offset_std,
                max_offset_ms=max_offset,
                sync_success_rate=success_rate,
                total_sync_attempts=total_attempts,
                failed_sync_attempts=total_attempts - successful_attempts,
                last_sync_timestamp=sync_status.last_sync_time or 0.0,
                network_metrics={
                    'average_rtt_ms': statistics.mean(rtts) if rtts else 0.0,
                    'max_rtt_ms': max(rtts) if rtts else 0.0,
                    'uncertainty_ms': (sync_status.uncertainty or 0.0) * 1000
                }
            )
            
        return device_metrics

    def analyze_multi_device_coordination(self, device_metrics: Dict[str, DeviceSyncMetrics]) -> MultiDeviceCoordinationInfo:
        """Analyze coordination quality across multiple devices."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
            
        device_ids = list(device_metrics.keys())
        if len(device_ids) < 2:
            return MultiDeviceCoordinationInfo(
                session_id=self.current_manifest.session_id,
                coordinator_device="",
                participating_devices=device_ids
            )
        
        # Find coordinator device (best sync quality)
        coordinator = min(device_ids, key=lambda d: device_metrics[d].average_offset_ms)
        
        # Calculate inter-device synchronization quality
        offsets = [device_metrics[d].average_offset_ms for d in device_ids]
        max_inter_device_offset = max(offsets) - min(offsets)
        
        # Determine overall coordination quality
        if max_inter_device_offset < 10:  # 10ms
            coord_quality = "excellent"
        elif max_inter_device_offset < 25:  # 25ms
            coord_quality = "good"
        elif max_inter_device_offset < 50:  # 50ms
            coord_quality = "fair"
        else:
            coord_quality = "poor"
        
        # Calculate coordination success rate
        success_rates = [device_metrics[d].sync_success_rate for d in device_ids]
        coord_success_rate = min(success_rates) if success_rates else 0.0
        
        # Extract coordination events from sync events
        coordination_events = []
        for event in self.current_manifest.sync_events:
            if hasattr(event, 'event_type') and 'multi_device' in event.event_type.lower():
                coordination_events.append({
                    'event_type': event.event_type,
                    'timestamp': event.timestamp,
                    'description': getattr(event, 'description', ''),
                    'data': getattr(event, 'data', {})
                })
        
        return MultiDeviceCoordinationInfo(
            session_id=self.current_manifest.session_id,
            coordinator_device=coordinator,
            participating_devices=device_ids,
            coordination_events=coordination_events,
            inter_device_sync_quality=coord_quality,
            max_inter_device_offset_ms=max_inter_device_offset,
            coordination_success_rate=coord_success_rate
        )

    def calculate_enhanced_quality_metrics(self, device_sync_statuses: Dict[str, DeviceSyncStatus] = None) -> EnhancedQualityMetrics:
        """Calculate comprehensive quality metrics for the session."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
        
        # Analyze device synchronization
        device_metrics = {}
        coordination_info = None
        
        if device_sync_statuses:
            device_metrics = self.analyze_device_synchronization(device_sync_statuses)
            if len(device_metrics) > 1:
                coordination_info = self.analyze_multi_device_coordination(device_metrics)
        
        # Calculate individual quality scores
        sync_quality_score = self._calculate_sync_quality_score(device_metrics)
        data_completeness_score = self._calculate_data_completeness_score()
        temporal_alignment_score = self._calculate_temporal_alignment_score()
        multi_device_score = self._calculate_multi_device_coordination_score(coordination_info)
        
        # Calculate overall score (weighted average)
        weights = {
            'sync': 0.3,
            'completeness': 0.25,
            'temporal': 0.25,
            'multi_device': 0.2
        }
        
        overall_score = (
            sync_quality_score * weights['sync'] +
            data_completeness_score * weights['completeness'] +
            temporal_alignment_score * weights['temporal'] +
            multi_device_score * weights['multi_device']
        )
        
        # Generate quality issues and recommendations
        quality_issues, recommendations = self._generate_quality_analysis(
            device_metrics, coordination_info, sync_quality_score, 
            data_completeness_score, temporal_alignment_score, multi_device_score
        )
        
        return EnhancedQualityMetrics(
            overall_score=overall_score,
            sync_quality_score=sync_quality_score,
            data_completeness_score=data_completeness_score,
            temporal_alignment_score=temporal_alignment_score,
            multi_device_coordination_score=multi_device_score,
            device_sync_metrics=device_metrics,
            coordination_info=coordination_info,
            quality_issues=quality_issues,
            recommendations=recommendations
        )

    def _calculate_sync_quality_score(self, device_metrics: Dict[str, DeviceSyncMetrics]) -> float:
        """Calculate synchronization quality score."""
        if not device_metrics:
            return 0.0
        
        quality_scores = []
        for metrics in device_metrics.values():
            if metrics.sync_quality == "excellent":
                score = 100.0
            elif metrics.sync_quality == "good":
                score = 80.0
            elif metrics.sync_quality == "fair":
                score = 60.0
            else:
                score = 30.0
            
            # Adjust based on success rate
            score *= metrics.sync_success_rate
            quality_scores.append(score)
        
        return sum(quality_scores) / len(quality_scores)

    def _calculate_data_completeness_score(self) -> float:
        """Calculate data completeness score."""
        if not self.current_manifest.files:
            return 0.0
        
        # Expected modalities (excluding system logs and sync events)
        expected_modalities = {
            DataModality.RGB_VIDEO, DataModality.THERMAL_VIDEO, 
            DataModality.AUDIO, DataModality.GSR
        }
        
        # Actual modalities present
        actual_modalities = set(f.modality for f in self.current_manifest.files)
        
        # Calculate completeness
        completeness = len(actual_modalities & expected_modalities) / len(expected_modalities)
        
        # Bonus for additional modalities
        bonus_modalities = actual_modalities - expected_modalities - {DataModality.SYNC_EVENTS, DataModality.SYSTEM_LOGS}
        bonus = min(len(bonus_modalities) * 0.1, 0.2)  # Max 20% bonus
        
        return min(100.0, (completeness + bonus) * 100)

    def _calculate_temporal_alignment_score(self) -> float:
        """Calculate temporal alignment score."""
        if not self.current_manifest.files:
            return 0.0
        
        # Group files by device
        device_files = {}
        for file_meta in self.current_manifest.files:
            if file_meta.device_id not in device_files:
                device_files[file_meta.device_id] = []
            device_files[file_meta.device_id].append(file_meta)
        
        if len(device_files) < 2:
            return 100.0  # Single device, perfect alignment
        
        # Calculate alignment score based on start/end time differences
        alignment_scores = []
        device_ids = list(device_files.keys())
        
        for i in range(len(device_ids)):
            for j in range(i + 1, len(device_ids)):
                device1_files = device_files[device_ids[i]]
                device2_files = device_files[device_ids[j]]
                
                # Compare start times
                if device1_files and device2_files:
                    start_diff = abs(device1_files[0].start_timestamp - device2_files[0].start_timestamp)
                    end_diff = abs(device1_files[-1].end_timestamp - device2_files[-1].end_timestamp)
                    
                    # Score based on alignment (lower difference = higher score)
                    start_score = max(0, 100 - start_diff * 10)  # 10 points per second difference
                    end_score = max(0, 100 - end_diff * 10)
                    
                    alignment_scores.append((start_score + end_score) / 2)
        
        return sum(alignment_scores) / len(alignment_scores) if alignment_scores else 100.0

    def _calculate_multi_device_coordination_score(self, coordination_info: MultiDeviceCoordinationInfo) -> float:
        """Calculate multi-device coordination score."""
        if not coordination_info or len(coordination_info.participating_devices) < 2:
            return 100.0  # Single device, no coordination needed
        
        # Base score from coordination quality
        quality_scores = {
            "excellent": 100.0,
            "good": 80.0,
            "fair": 60.0,
            "poor": 30.0
        }
        
        base_score = quality_scores.get(coordination_info.inter_device_sync_quality, 0.0)
        
        # Adjust based on success rate
        final_score = base_score * coordination_info.coordination_success_rate
        
        return final_score

    def _generate_quality_analysis(self, device_metrics: Dict[str, DeviceSyncMetrics], 
                                 coordination_info: MultiDeviceCoordinationInfo,
                                 sync_score: float, completeness_score: float,
                                 temporal_score: float, multi_device_score: float) -> Tuple[List[str], List[str]]:
        """Generate quality issues and recommendations."""
        issues = []
        recommendations = []
        
        # Sync quality issues
        if sync_score < 70:
            issues.append("Poor synchronization quality detected")
            recommendations.append("Check network connectivity and reduce network latency")
            recommendations.append("Increase synchronization frequency")
        
        # Data completeness issues
        if completeness_score < 80:
            issues.append("Incomplete data capture detected")
            recommendations.append("Verify all sensors are properly connected and configured")
            recommendations.append("Check device storage capacity")
        
        # Temporal alignment issues
        if temporal_score < 70:
            issues.append("Poor temporal alignment between devices")
            recommendations.append("Ensure all devices start recording simultaneously")
            recommendations.append("Implement better session coordination")
        
        # Multi-device coordination issues
        if multi_device_score < 70 and coordination_info and len(coordination_info.participating_devices) > 1:
            issues.append("Multi-device coordination problems detected")
            recommendations.append("Improve network stability between devices")
            recommendations.append("Use dedicated coordinator device")
        
        # Device-specific issues
        for device_id, metrics in device_metrics.items():
            if metrics.sync_success_rate < 0.8:
                issues.append(f"Device {device_id} has low sync success rate ({metrics.sync_success_rate:.1%})")
                recommendations.append(f"Check network connection for device {device_id}")
            
            if metrics.average_offset_ms > 50:
                issues.append(f"Device {device_id} has high average offset ({metrics.average_offset_ms:.1f}ms)")
                recommendations.append(f"Recalibrate time synchronization for device {device_id}")
        
        return issues, recommendations

    def add_coordination_event(self, event_type: str, device_id: str, event_data: Dict[str, Any]):
        """Add a coordination event to the session manifest."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
        
        # Create a coordination event (simplified SyncEvent-like structure)
        coordination_event = {
            'event_type': event_type,
            'device_id': device_id,
            'timestamp': time.time(),
            'data': event_data
        }
        
        # Add to sync events (assuming sync_events can hold coordination events)
        if not hasattr(self.current_manifest, 'coordination_events'):
            self.current_manifest.coordination_events = []
        
        self.current_manifest.coordination_events.append(coordination_event)
        logging.info(f"Added coordination event: {event_type} for device {device_id}")

    def export_enhanced_manifest(self, include_analysis: bool = True) -> str:
        """Export enhanced manifest with comprehensive analysis."""
        if not self.current_manifest:
            raise ValueError("No active session manifest")
        
        # Get basic manifest data
        manifest_data = self.current_manifest.to_dict()
        
        # Add enhanced analysis if requested
        if include_analysis:
            try:
                # Note: In a real implementation, you would pass actual device sync statuses
                enhanced_metrics = self.calculate_enhanced_quality_metrics()
                manifest_data['enhanced_quality_metrics'] = {
                    'overall_score': enhanced_metrics.overall_score,
                    'sync_quality_score': enhanced_metrics.sync_quality_score,
                    'data_completeness_score': enhanced_metrics.data_completeness_score,
                    'temporal_alignment_score': enhanced_metrics.temporal_alignment_score,
                    'multi_device_coordination_score': enhanced_metrics.multi_device_coordination_score,
                    'quality_issues': enhanced_metrics.quality_issues,
                    'recommendations': enhanced_metrics.recommendations
                }
                
                if enhanced_metrics.coordination_info:
                    manifest_data['multi_device_coordination'] = {
                        'coordinator_device': enhanced_metrics.coordination_info.coordinator_device,
                        'participating_devices': enhanced_metrics.coordination_info.participating_devices,
                        'inter_device_sync_quality': enhanced_metrics.coordination_info.inter_device_sync_quality,
                        'max_inter_device_offset_ms': enhanced_metrics.coordination_info.max_inter_device_offset_ms,
                        'coordination_success_rate': enhanced_metrics.coordination_info.coordination_success_rate
                    }
                
            except Exception as e:
                logging.warning(f"Could not generate enhanced analysis: {e}")
        
        # Save enhanced manifest
        filename = f"{self.current_manifest.session_id}_enhanced_manifest.json"
        output_path = self.output_directory / filename
        
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(manifest_data, f, indent=2, default=str)
        
        return str(output_path)