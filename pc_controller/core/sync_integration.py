#!/usr/bin/env python3
"""
Synchronization Integration Component for PC Controller Application
Integrates TimeSynchronizer with NetworkManager and SyncEvents for comprehensive time sync.
"""

import logging
import time
import asyncio
from typing import Dict, Any, Optional, List, Callable
from PyQt6.QtCore import QObject, pyqtSignal, QTimer

from .time_sync import TimeSynchronizer, DeviceSyncStatus, SyncState
from .device_manager import DeviceManager, AndroidDevice, DeviceStatus
from .sync_events import SyncEventManager, SyncEventType, SyncEvent


class SyncQualityLevel(Enum):
    """Synchronization quality levels."""
    EXCELLENT = "excellent"  # < 10ms offset
    GOOD = "good"           # < 25ms offset
    FAIR = "fair"           # < 50ms offset
    POOR = "poor"           # < 100ms offset
    CRITICAL = "critical"   # >= 100ms offset


class SyncAlert:
    """Synchronization quality alert."""
    
    def __init__(self, alert_type: str, device_id: str, message: str, 
                 severity: str = "warning", timestamp: float = None):
        self.alert_type = alert_type
        self.device_id = device_id
        self.message = message
        self.severity = severity
        self.timestamp = timestamp or time.time()
        self.acknowledged = False
        
    def to_dict(self) -> Dict[str, Any]:
        """Convert alert to dictionary."""
        return {
            'alert_type': self.alert_type,
            'device_id': self.device_id,
            'message': self.message,
            'severity': self.severity,
            'timestamp': self.timestamp,
            'acknowledged': self.acknowledged
        }


class SyncIntegrationManager(QObject):
    """Manages integration between time synchronization and network components."""
    
    # Signals
    sync_quality_changed = pyqtSignal(str, str)  # device_id, quality_level
    sync_alert_raised = pyqtSignal(dict)  # alert_data
    sync_statistics_updated = pyqtSignal(dict)  # statistics
    device_sync_status_changed = pyqtSignal(str, dict)  # device_id, status_data
    
    def __init__(self, device_manager: DeviceManager, parent=None):
        super().__init__(parent)
        self.device_manager = device_manager
        self.time_synchronizer = TimeSynchronizer()
        self.sync_event_manager = SyncEventManager()
        
        # Quality monitoring
        self.quality_thresholds = {
            SyncQualityLevel.EXCELLENT: 10.0,  # ms
            SyncQualityLevel.GOOD: 25.0,
            SyncQualityLevel.FAIR: 50.0,
            SyncQualityLevel.POOR: 100.0
        }
        
        # Alert management
        self.active_alerts = {}  # device_id -> List[SyncAlert]
        self.alert_history = []
        self.max_alert_history = 1000
        
        # Monitoring timers
        self.quality_monitor_timer = QTimer()
        self.quality_monitor_timer.timeout.connect(self.monitor_sync_quality)
        self.quality_monitor_timer.start(5000)  # Check every 5 seconds
        
        self.statistics_timer = QTimer()
        self.statistics_timer.timeout.connect(self.update_statistics)
        self.statistics_timer.start(10000)  # Update every 10 seconds
        
        # Connect to device manager signals
        self.device_manager.device_connected.connect(self.on_device_connected)
        self.device_manager.device_disconnected.connect(self.on_device_disconnected)
        
        # Connect to sync event manager signals
        self.sync_event_manager.marker_completed.connect(self.on_marker_completed)
        self.sync_event_manager.sync_quality_alert.connect(self.on_sync_quality_alert)
        
        logging.info("SyncIntegrationManager initialized")
        
    def initialize_session(self, session_id: str, device_ids: List[str]):
        """Initialize synchronization for a new session."""
        self.sync_event_manager.set_session_id(session_id)
        self.sync_event_manager.set_device_list(device_ids)
        
        # Register devices with time synchronizer
        for device_id in device_ids:
            self.time_synchronizer.register_device(device_id)
            
        # Create session start event
        self.sync_event_manager.create_event(
            SyncEventType.SESSION_START,
            description=f"Session {session_id} started with {len(device_ids)} devices"
        )
        
        logging.info(f"Initialized sync session {session_id} with devices: {device_ids}")
        
    def start_recording_sync(self, device_ids: Optional[List[str]] = None):
        """Start recording with synchronization."""
        if device_ids is None:
            device_ids = [device.device_id for device in self.device_manager.get_connected_devices()]
            
        # Perform time synchronization before recording
        self.synchronize_devices(device_ids)
        
        # Create recording start marker
        marker_id = self.sync_event_manager.create_marker("recording_start", "Recording start marker")
        
        # Create recording start event
        self.sync_event_manager.create_event(
            SyncEventType.RECORDING_START,
            description="Recording started with synchronization",
            metadata={'marker_id': marker_id, 'device_count': len(device_ids)}
        )
        
        logging.info(f"Started recording sync for {len(device_ids)} devices")
        
    def stop_recording_sync(self):
        """Stop recording with synchronization."""
        # Create recording stop marker
        marker_id = self.sync_event_manager.create_marker("recording_stop", "Recording stop marker")
        
        # Create recording stop event
        self.sync_event_manager.create_event(
            SyncEventType.RECORDING_STOP,
            description="Recording stopped with synchronization",
            metadata={'marker_id': marker_id}
        )
        
        logging.info("Stopped recording sync")
        
    def synchronize_devices(self, device_ids: List[str], num_measurements: int = 5):
        """Synchronize time with specified devices."""
        for device_id in device_ids:
            try:
                self.time_synchronizer.sync_device(device_id, num_measurements)
                logging.debug(f"Synchronized device {device_id}")
            except Exception as e:
                logging.error(f"Failed to sync device {device_id}: {e}")
                self.raise_alert("sync_failure", device_id, 
                               f"Time synchronization failed: {e}", "error")
                               
    def create_sync_marker(self, description: str = "Manual sync marker") -> str:
        """Create a synchronization marker for data alignment verification."""
        return self.sync_event_manager.create_marker("manual", description)
        
    def on_device_connected(self, device_id: str):
        """Handle device connection."""
        self.time_synchronizer.register_device(device_id)
        self.clear_device_alerts(device_id)
        
        # Perform initial synchronization
        try:
            self.time_synchronizer.sync_device(device_id)
            logging.info(f"Initial sync completed for device {device_id}")
        except Exception as e:
            logging.warning(f"Initial sync failed for device {device_id}: {e}")
            
    def on_device_disconnected(self, device_id: str):
        """Handle device disconnection."""
        self.time_synchronizer.unregister_device(device_id)
        self.raise_alert("device_disconnected", device_id, 
                        "Device disconnected - sync lost", "warning")
                        
    def on_marker_completed(self, marker_id: str, quality_metrics: Dict[str, float]):
        """Handle completed synchronization marker."""
        max_offset = quality_metrics.get('max_offset', 0.0)
        std_deviation = quality_metrics.get('std_deviation', 0.0)
        
        # Log marker completion
        logging.info(f"Marker {marker_id} completed - offset: {max_offset:.1f}ms, "
                    f"jitter: {std_deviation:.1f}ms")
                    
        # Update statistics
        self.update_statistics()
        
    def on_sync_quality_alert(self, alert_type: str, alert_data: Dict[str, Any]):
        """Handle sync quality alert from event manager."""
        device_id = alert_data.get('device_id', 'unknown')
        message = alert_data.get('message', 'Sync quality alert')
        severity = alert_data.get('severity', 'warning')
        
        self.raise_alert(alert_type, device_id, message, severity)
        
    def monitor_sync_quality(self):
        """Monitor synchronization quality for all devices."""
        connected_devices = self.device_manager.get_connected_devices()
        
        for device in connected_devices:
            device_id = device.device_id
            
            # Check if device is synchronized
            if not self.time_synchronizer.is_device_synchronized(device_id):
                self.raise_alert("not_synchronized", device_id,
                               "Device not synchronized", "warning")
                continue
                
            # Get sync quality
            quality_info = self.time_synchronizer.get_sync_quality(device_id)
            if not quality_info:
                continue
                
            offset_ms = abs(quality_info.get('offset_ms', 0))
            quality_level = self.determine_quality_level(offset_ms)
            
            # Emit quality change signal
            self.sync_quality_changed.emit(device_id, quality_level.value)
            
            # Check for quality degradation
            if quality_level in [SyncQualityLevel.POOR, SyncQualityLevel.CRITICAL]:
                self.raise_alert("poor_sync_quality", device_id,
                               f"Poor sync quality: {offset_ms:.1f}ms offset",
                               "error" if quality_level == SyncQualityLevel.CRITICAL else "warning")
                               
    def determine_quality_level(self, offset_ms: float) -> SyncQualityLevel:
        """Determine sync quality level based on offset."""
        if offset_ms < self.quality_thresholds[SyncQualityLevel.EXCELLENT]:
            return SyncQualityLevel.EXCELLENT
        elif offset_ms < self.quality_thresholds[SyncQualityLevel.GOOD]:
            return SyncQualityLevel.GOOD
        elif offset_ms < self.quality_thresholds[SyncQualityLevel.FAIR]:
            return SyncQualityLevel.FAIR
        elif offset_ms < self.quality_thresholds[SyncQualityLevel.POOR]:
            return SyncQualityLevel.POOR
        else:
            return SyncQualityLevel.CRITICAL
            
    def raise_alert(self, alert_type: str, device_id: str, message: str, severity: str = "warning"):
        """Raise a synchronization alert."""
        alert = SyncAlert(alert_type, device_id, message, severity)
        
        # Add to active alerts
        if device_id not in self.active_alerts:
            self.active_alerts[device_id] = []
        self.active_alerts[device_id].append(alert)
        
        # Add to history
        self.alert_history.append(alert)
        if len(self.alert_history) > self.max_alert_history:
            self.alert_history.pop(0)
            
        # Emit signal
        self.sync_alert_raised.emit(alert.to_dict())
        
        logging.warning(f"Sync alert [{severity}] for {device_id}: {message}")
        
    def acknowledge_alert(self, device_id: str, alert_type: str):
        """Acknowledge an alert."""
        if device_id in self.active_alerts:
            for alert in self.active_alerts[device_id]:
                if alert.alert_type == alert_type and not alert.acknowledged:
                    alert.acknowledged = True
                    logging.info(f"Acknowledged alert {alert_type} for device {device_id}")
                    break
                    
    def clear_device_alerts(self, device_id: str):
        """Clear all alerts for a device."""
        if device_id in self.active_alerts:
            del self.active_alerts[device_id]
            
    def get_active_alerts(self, device_id: Optional[str] = None) -> List[SyncAlert]:
        """Get active alerts."""
        if device_id:
            return self.active_alerts.get(device_id, [])
        else:
            all_alerts = []
            for alerts in self.active_alerts.values():
                all_alerts.extend(alerts)
            return all_alerts
            
    def update_statistics(self):
        """Update and emit synchronization statistics."""
        connected_devices = self.device_manager.get_connected_devices()
        
        stats = {
            'total_devices': len(connected_devices),
            'synchronized_devices': 0,
            'quality_distribution': {level.value: 0 for level in SyncQualityLevel},
            'average_offset_ms': 0.0,
            'max_offset_ms': 0.0,
            'active_alerts': len(self.get_active_alerts()),
            'sync_events': self.sync_event_manager.get_sync_statistics()
        }
        
        offsets = []
        for device in connected_devices:
            device_id = device.device_id
            
            if self.time_synchronizer.is_device_synchronized(device_id):
                stats['synchronized_devices'] += 1
                
                quality_info = self.time_synchronizer.get_sync_quality(device_id)
                if quality_info:
                    offset_ms = abs(quality_info.get('offset_ms', 0))
                    offsets.append(offset_ms)
                    
                    quality_level = self.determine_quality_level(offset_ms)
                    stats['quality_distribution'][quality_level.value] += 1
                    
        if offsets:
            stats['average_offset_ms'] = sum(offsets) / len(offsets)
            stats['max_offset_ms'] = max(offsets)
            
        # Emit statistics
        self.sync_statistics_updated.emit(stats)
        
    def start_auto_sync(self, interval_seconds: int = 30):
        """Start automatic synchronization."""
        self.time_synchronizer.start_auto_sync()
        self.sync_event_manager.start_auto_markers(interval_seconds)
        logging.info(f"Started auto sync with {interval_seconds}s interval")
        
    def stop_auto_sync(self):
        """Stop automatic synchronization."""
        self.time_synchronizer.stop_auto_sync()
        self.sync_event_manager.stop_auto_markers()
        logging.info("Stopped auto sync")
        
    def force_resync_all(self):
        """Force resynchronization of all devices."""
        self.time_synchronizer.force_resync_all()
        logging.info("Forced resync of all devices")
        
    def get_device_sync_status(self, device_id: str) -> Dict[str, Any]:
        """Get comprehensive sync status for a device."""
        device_status = self.time_synchronizer.get_device_status(device_id)
        quality_info = self.time_synchronizer.get_sync_quality(device_id)
        active_alerts = self.get_active_alerts(device_id)
        
        if not device_status:
            return {'synchronized': False, 'quality': 'unknown'}
            
        offset_ms = abs(quality_info.get('offset_ms', 0)) if quality_info else 0
        quality_level = self.determine_quality_level(offset_ms)
        
        return {
            'synchronized': device_status.is_synchronized(),
            'quality': quality_level.value,
            'offset_ms': offset_ms,
            'last_sync': device_status.last_sync_time,
            'sync_count': len(device_status.measurements),
            'active_alerts': len(active_alerts),
            'alerts': [alert.to_dict() for alert in active_alerts]
        }
        
    def cleanup(self):
        """Cleanup resources."""
        self.quality_monitor_timer.stop()
        self.statistics_timer.stop()
        self.time_synchronizer.cleanup()
        logging.info("SyncIntegrationManager cleaned up")
        
    def set_quality_thresholds(self, excellent_ms: float = 10.0, good_ms: float = 25.0,
                              fair_ms: float = 50.0, poor_ms: float = 100.0):
        """Set custom quality thresholds."""
        self.quality_thresholds = {
            SyncQualityLevel.EXCELLENT: excellent_ms,
            SyncQualityLevel.GOOD: good_ms,
            SyncQualityLevel.FAIR: fair_ms,
            SyncQualityLevel.POOR: poor_ms
        }
        
        # Also update sync event manager thresholds
        self.sync_event_manager.set_quality_thresholds(poor_ms, fair_ms)
        
        logging.info(f"Updated quality thresholds: excellent={excellent_ms}ms, "
                    f"good={good_ms}ms, fair={fair_ms}ms, poor={poor_ms}ms")