"""
Time Synchronization Module for PC Controller.
Implements NTP-like protocol for precise clock alignment between PC and Android devices.
"""

import time
import asyncio
import logging
import statistics
import socket
import json
from typing import Dict, List, Optional, Tuple, Callable
from dataclasses import dataclass, field
from enum import Enum
from datetime import datetime, timedelta

class SyncState(Enum):
    """Time synchronization states."""
    IDLE = "idle"
    SYNCING = "syncing"
    SYNCHRONIZED = "synchronized"
    ERROR = "error"
    NETWORK_ERROR = "network_error"
    TIMEOUT = "timeout"
    RECOVERING = "recovering"

class SyncQuality(Enum):
    """Synchronization quality levels."""
    EXCELLENT = "excellent"  # < 5ms uncertainty
    GOOD = "good"           # < 20ms uncertainty
    FAIR = "fair"           # < 50ms uncertainty
    POOR = "poor"           # >= 50ms uncertainty

@dataclass
class NetworkFailureInfo:
    """Information about network failures."""
    device_id: str
    failure_time: float
    failure_type: str
    retry_count: int = 0
    last_retry_time: Optional[float] = None
    recovery_strategy: str = "exponential_backoff"

@dataclass
class TimeSyncMeasurement:
    """Single time synchronization measurement."""
    device_id: str
    request_time: float
    device_time: float
    response_time: float
    round_trip_time: float
    offset: float
    delay: float
    
    @property
    def quality(self) -> float:
        """Calculate measurement quality (0-1, higher is better)."""
        # Quality decreases with round trip time
        max_acceptable_rtt = 0.1  # 100ms
        if self.round_trip_time > max_acceptable_rtt:
            return 0.0
        return 1.0 - (self.round_trip_time / max_acceptable_rtt)

@dataclass
class DeviceSyncStatus:
    """Synchronization status for a device."""
    device_id: str
    state: SyncState
    offset: Optional[float] = None
    uncertainty: Optional[float] = None
    last_sync_time: Optional[float] = None
    measurements: List[TimeSyncMeasurement] = None
    
    def __post_init__(self):
        if self.measurements is None:
            self.measurements = []
    
    @property
    def is_synchronized(self) -> bool:
        """Check if device is properly synchronized."""
        return (self.state == SyncState.SYNCHRONIZED and 
                self.offset is not None and 
                self.uncertainty is not None and
                self.uncertainty < 0.050)  # 50ms uncertainty threshold

class TimeSynchronizer:
    """
    Time synchronization manager for multi-device coordination.
    
    Implements a simplified NTP-like protocol to synchronize clocks
    between PC and Android devices for precise data alignment.
    """
    
    def __init__(self, max_measurements: int = 10, sync_interval: float = 30.0):
        """
        Initialize enhanced time synchronizer.
        
        Args:
            max_measurements: Maximum number of measurements to keep per device
            sync_interval: Interval between automatic sync attempts (seconds)
        """
        self.max_measurements = max_measurements
        self.sync_interval = sync_interval
        
        # Device synchronization status
        self.device_status: Dict[str, DeviceSyncStatus] = {}
        
        # Synchronization tasks
        self.sync_tasks: Dict[str, asyncio.Task] = {}
        self.auto_sync_task: Optional[asyncio.Task] = None
        
        # Configuration
        self.min_measurements = 3
        self.max_uncertainty = 0.050  # 50ms
        self.outlier_threshold = 3.0  # Standard deviations
        
        # Enhanced features
        self.network_failures: Dict[str, NetworkFailureInfo] = {}
        self.adaptive_intervals: Dict[str, float] = {}
        self.sync_event_callbacks: List[Callable] = []
        
        logging.info("Enhanced TimeSynchronizer initialized")
    
    async def start_auto_sync(self):
        """Start automatic synchronization for all devices."""
        if self.auto_sync_task and not self.auto_sync_task.done():
            return
        
        self.auto_sync_task = asyncio.create_task(self._auto_sync_loop())
        logging.info("Automatic time synchronization started")
    
    async def stop_auto_sync(self):
        """Stop automatic synchronization."""
        if self.auto_sync_task:
            self.auto_sync_task.cancel()
            try:
                await self.auto_sync_task
            except asyncio.CancelledError:
                pass
            self.auto_sync_task = None
        
        logging.info("Automatic time synchronization stopped")
    
    async def _auto_sync_loop(self):
        """Automatic synchronization loop."""
        while True:
            try:
                # Sync all registered devices
                for device_id in list(self.device_status.keys()):
                    if self.device_status[device_id].state != SyncState.SYNCING:
                        await self.sync_device(device_id)
                
                await asyncio.sleep(self.sync_interval)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in auto sync loop: {e}")
                await asyncio.sleep(5.0)  # Wait before retrying
    
    def register_device(self, device_id: str):
        """Register a device for time synchronization."""
        if device_id not in self.device_status:
            self.device_status[device_id] = DeviceSyncStatus(
                device_id=device_id,
                state=SyncState.IDLE
            )
            logging.info(f"Device {device_id} registered for time synchronization")
    
    def unregister_device(self, device_id: str):
        """Unregister a device from time synchronization."""
        # Cancel any ongoing sync task
        if device_id in self.sync_tasks:
            self.sync_tasks[device_id].cancel()
            del self.sync_tasks[device_id]
        
        # Remove device status
        if device_id in self.device_status:
            del self.device_status[device_id]
            logging.info(f"Device {device_id} unregistered from time synchronization")
    
    async def sync_device(self, device_id: str, num_measurements: int = 5) -> bool:
        """
        Synchronize time with a specific device.
        
        Args:
            device_id: Device to synchronize with
            num_measurements: Number of measurements to take
            
        Returns:
            True if synchronization successful, False otherwise
        """
        if device_id not in self.device_status:
            logging.error(f"Device {device_id} not registered for synchronization")
            return False
        
        # Check if already syncing
        if device_id in self.sync_tasks and not self.sync_tasks[device_id].done():
            logging.warning(f"Synchronization already in progress for device {device_id}")
            return False
        
        # Start synchronization task
        self.sync_tasks[device_id] = asyncio.create_task(
            self._perform_sync(device_id, num_measurements)
        )
        
        try:
            return await self.sync_tasks[device_id]
        except asyncio.CancelledError:
            logging.info(f"Synchronization cancelled for device {device_id}")
            return False
        finally:
            if device_id in self.sync_tasks:
                del self.sync_tasks[device_id]
    
    async def _perform_sync(self, device_id: str, num_measurements: int) -> bool:
        """Perform actual synchronization with device."""
        status = self.device_status[device_id]
        status.state = SyncState.SYNCING
        
        try:
            logging.info(f"Starting time synchronization with device {device_id}")
            
            # Take multiple measurements
            measurements = []
            for i in range(num_measurements):
                measurement = await self._take_measurement(device_id)
                if measurement:
                    measurements.append(measurement)
                    logging.debug(f"Measurement {i+1}/{num_measurements}: offset={measurement.offset:.3f}ms, RTT={measurement.round_trip_time:.3f}ms")
                
                # Small delay between measurements
                if i < num_measurements - 1:
                    await asyncio.sleep(0.1)
            
            if len(measurements) < self.min_measurements:
                logging.error(f"Insufficient measurements for device {device_id}: {len(measurements)}/{self.min_measurements}")
                status.state = SyncState.ERROR
                return False
            
            # Filter outliers and calculate final offset
            filtered_measurements = self._filter_outliers(measurements)
            if len(filtered_measurements) < self.min_measurements:
                logging.error(f"Too many outliers for device {device_id}")
                status.state = SyncState.ERROR
                return False
            
            # Calculate final synchronization parameters
            offsets = [m.offset for m in filtered_measurements]
            uncertainties = [m.round_trip_time / 2 for m in filtered_measurements]
            
            final_offset = statistics.median(offsets)
            final_uncertainty = statistics.median(uncertainties)
            
            # Update device status
            status.offset = final_offset
            status.uncertainty = final_uncertainty
            status.last_sync_time = time.time()
            status.measurements.extend(filtered_measurements)
            
            # Keep only recent measurements
            if len(status.measurements) > self.max_measurements:
                status.measurements = status.measurements[-self.max_measurements:]
            
            # Check if synchronization is acceptable
            if final_uncertainty <= self.max_uncertainty:
                status.state = SyncState.SYNCHRONIZED
                logging.info(f"Device {device_id} synchronized: offset={final_offset:.3f}ms, uncertainty={final_uncertainty:.3f}ms")
                return True
            else:
                status.state = SyncState.ERROR
                logging.warning(f"Device {device_id} synchronization uncertainty too high: {final_uncertainty:.3f}ms")
                return False
                
        except Exception as e:
            logging.error(f"Error synchronizing device {device_id}: {e}")
            status.state = SyncState.ERROR
            return False
    
    async def _take_measurement(self, device_id: str) -> Optional[TimeSyncMeasurement]:
        """Take a single time synchronization measurement."""
        try:
            # Record request time
            request_time = time.time()
            
            # Send sync request to device (this would be implemented by the network layer)
            device_time = await self._send_sync_request(device_id)
            
            # Record response time
            response_time = time.time()
            
            if device_time is None:
                return None
            
            # Calculate synchronization parameters
            round_trip_time = response_time - request_time
            estimated_network_delay = round_trip_time / 2
            estimated_device_time_at_request = device_time - estimated_network_delay
            offset = estimated_device_time_at_request - request_time
            
            return TimeSyncMeasurement(
                device_id=device_id,
                request_time=request_time,
                device_time=device_time,
                response_time=response_time,
                round_trip_time=round_trip_time,
                offset=offset,
                delay=estimated_network_delay
            )
            
        except Exception as e:
            logging.error(f"Error taking measurement for device {device_id}: {e}")
            return None
    
    async def _send_sync_request(self, device_id: str) -> Optional[float]:
        """
        Send synchronization request to device.
        
        This is a placeholder that would be implemented by the network layer.
        The actual implementation would send a SYNC_PING message to the device
        and receive the device's current timestamp.
        """
        # Placeholder implementation - would be replaced with actual network call
        await asyncio.sleep(0.01)  # Simulate network delay
        return time.time() + 0.005  # Simulate device time with small offset
    
    def _filter_outliers(self, measurements: List[TimeSyncMeasurement]) -> List[TimeSyncMeasurement]:
        """Filter outlier measurements using statistical methods."""
        if len(measurements) < 3:
            return measurements
        
        # Calculate statistics for offset values
        offsets = [m.offset for m in measurements]
        mean_offset = statistics.mean(offsets)
        stdev_offset = statistics.stdev(offsets) if len(offsets) > 1 else 0
        
        # Filter measurements within threshold
        filtered = []
        for measurement in measurements:
            if stdev_offset == 0 or abs(measurement.offset - mean_offset) <= self.outlier_threshold * stdev_offset:
                filtered.append(measurement)
        
        logging.debug(f"Filtered {len(measurements) - len(filtered)} outliers from {len(measurements)} measurements")
        return filtered
    
    def get_device_offset(self, device_id: str) -> Optional[float]:
        """Get the current time offset for a device."""
        if device_id in self.device_status:
            status = self.device_status[device_id]
            if status.is_synchronized:
                return status.offset
        return None
    
    def get_synchronized_time(self, device_id: str, device_time: float) -> Optional[float]:
        """Convert device time to synchronized PC time."""
        offset = self.get_device_offset(device_id)
        if offset is not None:
            return device_time - offset
        return None
    
    def get_device_status(self, device_id: str) -> Optional[DeviceSyncStatus]:
        """Get synchronization status for a device."""
        return self.device_status.get(device_id)
    
    def get_all_device_status(self) -> Dict[str, DeviceSyncStatus]:
        """Get synchronization status for all devices."""
        return self.device_status.copy()
    
    def is_device_synchronized(self, device_id: str) -> bool:
        """Check if a device is properly synchronized."""
        status = self.get_device_status(device_id)
        return status.is_synchronized if status else False
    
    def get_sync_quality(self, device_id: str) -> Optional[float]:
        """Get synchronization quality for a device (0-1, higher is better)."""
        status = self.get_device_status(device_id)
        if not status or not status.measurements:
            return None
        
        # Calculate quality based on recent measurements
        recent_measurements = status.measurements[-5:]  # Last 5 measurements
        if not recent_measurements:
            return None
        
        qualities = [m.quality for m in recent_measurements]
        return statistics.mean(qualities)
    
    async def force_resync_all(self):
        """Force resynchronization of all devices."""
        logging.info("Forcing resynchronization of all devices")
        
        tasks = []
        for device_id in list(self.device_status.keys()):
            task = asyncio.create_task(self.sync_device(device_id))
            tasks.append(task)
        
        if tasks:
            results = await asyncio.gather(*tasks, return_exceptions=True)
            successful = sum(1 for r in results if r is True)
            logging.info(f"Resynchronization completed: {successful}/{len(tasks)} devices synchronized")

    # Enhanced networking and synchronization methods

    def add_sync_event_callback(self, callback: Callable):
        """Add callback for synchronization events."""
        self.sync_event_callbacks.append(callback)

    def remove_sync_event_callback(self, callback: Callable):
        """Remove synchronization event callback."""
        if callback in self.sync_event_callbacks:
            self.sync_event_callbacks.remove(callback)

    def _notify_sync_event(self, event_type: str, device_id: str, data: Dict = None):
        """Notify all callbacks of synchronization events."""
        event_data = {
            'type': event_type,
            'device_id': device_id,
            'timestamp': time.time(),
            'data': data or {}
        }
        
        for callback in self.sync_event_callbacks:
            try:
                callback(event_data)
            except Exception as e:
                logging.error(f"Error in sync event callback: {e}")

    async def handle_network_failure(self, device_id: str, failure_type: str):
        """Handle network failure with recovery strategies."""
        current_time = time.time()
        
        if device_id not in self.network_failures:
            self.network_failures[device_id] = NetworkFailureInfo(
                device_id=device_id,
                failure_time=current_time,
                failure_type=failure_type
            )
        
        failure_info = self.network_failures[device_id]
        failure_info.retry_count += 1
        failure_info.last_retry_time = current_time
        
        # Update device status
        if device_id in self.device_status:
            self.device_status[device_id].state = SyncState.NETWORK_ERROR
        
        # Notify event
        self._notify_sync_event('network_failure', device_id, {
            'failure_type': failure_type,
            'retry_count': failure_info.retry_count
        })
        
        # Implement recovery strategy
        await self._execute_recovery_strategy(device_id, failure_info)

    async def _execute_recovery_strategy(self, device_id: str, failure_info: NetworkFailureInfo):
        """Execute recovery strategy based on failure type and history."""
        if failure_info.recovery_strategy == "exponential_backoff":
            # Exponential backoff with jitter
            base_delay = min(2 ** failure_info.retry_count, 60)  # Max 60 seconds
            jitter = base_delay * 0.1 * (0.5 - time.time() % 1)  # Random jitter
            delay = base_delay + jitter
            
            logging.info(f"Network recovery for {device_id}: waiting {delay:.1f}s (attempt {failure_info.retry_count})")
            await asyncio.sleep(delay)
            
            # Attempt recovery
            success = await self._attempt_recovery(device_id)
            if success:
                # Clear failure info on successful recovery
                del self.network_failures[device_id]
                self._notify_sync_event('network_recovery', device_id, {
                    'attempts': failure_info.retry_count
                })
            elif failure_info.retry_count >= 5:
                # Give up after 5 attempts
                logging.error(f"Network recovery failed for {device_id} after {failure_info.retry_count} attempts")
                self._notify_sync_event('network_recovery_failed', device_id, {
                    'attempts': failure_info.retry_count
                })

    async def _attempt_recovery(self, device_id: str) -> bool:
        """Attempt to recover connection with device."""
        try:
            # Update device status to recovering
            if device_id in self.device_status:
                self.device_status[device_id].state = SyncState.RECOVERING
            
            # Try a simple sync to test connectivity
            success = await self.sync_device(device_id, num_measurements=1)
            return success
            
        except Exception as e:
            logging.error(f"Recovery attempt failed for {device_id}: {e}")
            return False

    def get_adaptive_sync_interval(self, device_id: str) -> float:
        """Get adaptive synchronization interval for device."""
        if device_id not in self.adaptive_intervals:
            self.adaptive_intervals[device_id] = self.sync_interval
        
        status = self.device_status.get(device_id)
        if not status:
            return self.adaptive_intervals[device_id]
        
        # Adjust interval based on sync quality and stability
        if status.state == SyncState.SYNCHRONIZED:
            quality = self.get_sync_quality(device_id)
            if quality and quality > 0.9:
                # High quality - can increase interval
                self.adaptive_intervals[device_id] = min(
                    self.adaptive_intervals[device_id] * 1.2,
                    self.sync_interval * 3
                )
            elif quality and quality < 0.5:
                # Low quality - decrease interval
                self.adaptive_intervals[device_id] = max(
                    self.adaptive_intervals[device_id] * 0.8,
                    self.sync_interval * 0.3
                )
        elif status.state in [SyncState.ERROR, SyncState.NETWORK_ERROR]:
            # Error states - decrease interval for more frequent attempts
            self.adaptive_intervals[device_id] = max(
                self.adaptive_intervals[device_id] * 0.5,
                5.0  # Minimum 5 seconds
            )
        
        return self.adaptive_intervals[device_id]

    def get_sync_quality_level(self, device_id: str) -> SyncQuality:
        """Get synchronization quality level for device."""
        status = self.device_status.get(device_id)
        if not status or not status.uncertainty:
            return SyncQuality.POOR
        
        uncertainty_ms = status.uncertainty * 1000  # Convert to milliseconds
        
        if uncertainty_ms < 5:
            return SyncQuality.EXCELLENT
        elif uncertainty_ms < 20:
            return SyncQuality.GOOD
        elif uncertainty_ms < 50:
            return SyncQuality.FAIR
        else:
            return SyncQuality.POOR

    async def coordinate_multi_device_sync(self, device_ids: List[str]) -> Dict[str, bool]:
        """Coordinate synchronization across multiple devices."""
        logging.info(f"Coordinating multi-device sync for {len(device_ids)} devices")
        
        # Create sync marker event
        sync_marker_id = f"multi_sync_{int(time.time())}"
        self._notify_sync_event('multi_device_sync_start', 'coordinator', {
            'device_ids': device_ids,
            'sync_marker_id': sync_marker_id
        })
        
        # Perform parallel synchronization
        tasks = {}
        for device_id in device_ids:
            if device_id in self.device_status:
                tasks[device_id] = asyncio.create_task(
                    self.sync_device(device_id, num_measurements=7)  # More measurements for better accuracy
                )
        
        # Wait for all synchronizations to complete
        results = {}
        if tasks:
            completed_tasks = await asyncio.gather(*tasks.values(), return_exceptions=True)
            for device_id, result in zip(tasks.keys(), completed_tasks):
                results[device_id] = result if isinstance(result, bool) else False
        
        # Analyze synchronization quality across devices
        sync_quality_report = self._analyze_multi_device_sync_quality(device_ids, results)
        
        self._notify_sync_event('multi_device_sync_complete', 'coordinator', {
            'device_ids': device_ids,
            'sync_marker_id': sync_marker_id,
            'results': results,
            'quality_report': sync_quality_report
        })
        
        return results

    def _analyze_multi_device_sync_quality(self, device_ids: List[str], results: Dict[str, bool]) -> Dict:
        """Analyze synchronization quality across multiple devices."""
        successful_devices = [did for did, success in results.items() if success]
        
        if len(successful_devices) < 2:
            return {
                'overall_quality': 'poor',
                'synchronized_devices': len(successful_devices),
                'total_devices': len(device_ids),
                'relative_sync_quality': 0.0
            }
        
        # Calculate relative synchronization quality between devices
        offsets = []
        uncertainties = []
        
        for device_id in successful_devices:
            status = self.device_status.get(device_id)
            if status and status.offset is not None and status.uncertainty is not None:
                offsets.append(status.offset)
                uncertainties.append(status.uncertainty)
        
        if len(offsets) >= 2:
            offset_range = max(offsets) - min(offsets)
            avg_uncertainty = statistics.mean(uncertainties)
            
            # Quality based on offset consistency and individual uncertainties
            relative_sync_quality = max(0.0, 1.0 - (offset_range + avg_uncertainty) / 0.1)  # 100ms reference
            
            if relative_sync_quality > 0.8:
                overall_quality = 'excellent'
            elif relative_sync_quality > 0.6:
                overall_quality = 'good'
            elif relative_sync_quality > 0.4:
                overall_quality = 'fair'
            else:
                overall_quality = 'poor'
        else:
            relative_sync_quality = 0.0
            overall_quality = 'poor'
        
        return {
            'overall_quality': overall_quality,
            'synchronized_devices': len(successful_devices),
            'total_devices': len(device_ids),
            'relative_sync_quality': relative_sync_quality,
            'offset_range_ms': (max(offsets) - min(offsets)) * 1000 if offsets else 0,
            'avg_uncertainty_ms': statistics.mean(uncertainties) * 1000 if uncertainties else 0
        }

    async def cleanup(self):
        """Cleanup synchronizer resources."""
        # Stop auto sync
        await self.stop_auto_sync()
        
        # Cancel all sync tasks
        for task in list(self.sync_tasks.values()):
            task.cancel()
        
        if self.sync_tasks:
            await asyncio.gather(*self.sync_tasks.values(), return_exceptions=True)
        
        self.sync_tasks.clear()
        self.device_status.clear()
        
        logging.info("TimeSynchronizer cleaned up")


class MultiDeviceCoordinator:
    """
    Coordinator for managing synchronization across multiple devices.
    Provides advanced coordination features for complex multi-device scenarios.
    """
    
    def __init__(self):
        """Initialize multi-device coordinator."""
        self.device_groups: Dict[str, List[str]] = {}
        self.sync_sessions: Dict[str, Dict] = {}
        self.coordination_callbacks: List[Callable] = []
        
        logging.info("MultiDeviceCoordinator initialized")
    
    def create_device_group(self, group_name: str, device_ids: List[str]):
        """Create a group of devices for coordinated operations."""
        self.device_groups[group_name] = device_ids.copy()
        logging.info(f"Created device group '{group_name}' with {len(device_ids)} devices")
    
    def remove_device_group(self, group_name: str):
        """Remove a device group."""
        if group_name in self.device_groups:
            del self.device_groups[group_name]
            logging.info(f"Removed device group '{group_name}'")
    
    def get_device_groups(self) -> Dict[str, List[str]]:
        """Get all device groups."""
        return self.device_groups.copy()
    
    def add_coordination_callback(self, callback: Callable):
        """Add callback for coordination events."""
        self.coordination_callbacks.append(callback)
    
    def remove_coordination_callback(self, callback: Callable):
        """Remove coordination callback."""
        if callback in self.coordination_callbacks:
            self.coordination_callbacks.remove(callback)
    
    def _notify_coordination_event(self, event_type: str, data: Dict = None):
        """Notify all callbacks of coordination events."""
        event_data = {
            'type': event_type,
            'timestamp': time.time(),
            'data': data or {}
        }
        
        for callback in self.coordination_callbacks:
            try:
                callback(event_data)
            except Exception as e:
                logging.error(f"Error in coordination callback: {e}")
    
    async def coordinate_group_sync(self, group_name: str, time_synchronizer) -> Dict[str, bool]:
        """Coordinate synchronization for a device group."""
        if group_name not in self.device_groups:
            logging.error(f"Device group '{group_name}' not found")
            return {}
        
        device_ids = self.device_groups[group_name]
        session_id = f"group_sync_{group_name}_{int(time.time())}"
        
        self._notify_coordination_event('group_sync_start', {
            'group_name': group_name,
            'session_id': session_id,
            'device_count': len(device_ids)
        })
        
        # Use the time synchronizer's multi-device coordination
        results = await time_synchronizer.coordinate_multi_device_sync(device_ids)
        
        # Store session results
        self.sync_sessions[session_id] = {
            'group_name': group_name,
            'device_ids': device_ids,
            'results': results,
            'timestamp': time.time()
        }
        
        self._notify_coordination_event('group_sync_complete', {
            'group_name': group_name,
            'session_id': session_id,
            'results': results
        })
        
        return results
    
    def get_sync_session_history(self, limit: int = 10) -> List[Dict]:
        """Get recent synchronization session history."""
        sessions = list(self.sync_sessions.values())
        sessions.sort(key=lambda x: x['timestamp'], reverse=True)
        return sessions[:limit]
    
    def cleanup(self):
        """Cleanup coordinator resources."""
        self.device_groups.clear()
        self.sync_sessions.clear()
        self.coordination_callbacks.clear()
        logging.info("MultiDeviceCoordinator cleaned up")