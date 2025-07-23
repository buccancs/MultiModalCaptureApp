"""
Synchronization Events and Markers System.
Provides reference points for data alignment verification across multiple devices.
"""

import time
import asyncio
import logging
import uuid
from typing import Dict, List, Optional, Set, Callable
from dataclasses import dataclass, field
from enum import Enum
from datetime import datetime

class SyncEventType(Enum):
    """Types of synchronization events."""
    SESSION_START = "session_start"
    SESSION_END = "session_end"
    RECORDING_START = "recording_start"
    RECORDING_STOP = "recording_stop"
    RECORDING_PAUSE = "recording_pause"
    RECORDING_RESUME = "recording_resume"
    MANUAL_MARKER = "manual_marker"
    CALIBRATION_MARKER = "calibration_marker"
    SYNC_VERIFICATION = "sync_verification"
    HEARTBEAT = "heartbeat"

class SyncEventPriority(Enum):
    """Priority levels for synchronization events."""
    LOW = 1
    NORMAL = 2
    HIGH = 3
    CRITICAL = 4

@dataclass
class SyncEvent:
    """A synchronization event that can be broadcast to all devices."""
    event_id: str
    event_type: SyncEventType
    timestamp: float
    priority: SyncEventPriority = SyncEventPriority.NORMAL
    data: Dict = field(default_factory=dict)
    description: str = ""
    source_device: str = "PC"
    
    def to_dict(self) -> Dict:
        """Convert event to dictionary for network transmission."""
        return {
            "event_id": self.event_id,
            "event_type": self.event_type.value,
            "timestamp": self.timestamp,
            "priority": self.priority.value,
            "data": self.data,
            "description": self.description,
            "source_device": self.source_device
        }
    
    @classmethod
    def from_dict(cls, data: Dict) -> 'SyncEvent':
        """Create event from dictionary."""
        return cls(
            event_id=data["event_id"],
            event_type=SyncEventType(data["event_type"]),
            timestamp=data["timestamp"],
            priority=SyncEventPriority(data["priority"]),
            data=data.get("data", {}),
            description=data.get("description", ""),
            source_device=data.get("source_device", "Unknown")
        )

@dataclass
class DeviceEventResponse:
    """Response from a device to a synchronization event."""
    device_id: str
    event_id: str
    received_timestamp: float
    processed_timestamp: float
    local_timestamp: float
    status: str = "acknowledged"
    error_message: str = ""
    
    @property
    def processing_delay(self) -> float:
        """Calculate processing delay in seconds."""
        return self.processed_timestamp - self.received_timestamp
    
    @property
    def network_delay(self) -> float:
        """Estimate network delay (one-way)."""
        # This is an approximation - actual implementation would use
        # time synchronization data for more accurate calculation
        return (self.received_timestamp - self.local_timestamp) / 2

@dataclass
class SyncEventStatus:
    """Status of a synchronization event across all devices."""
    event: SyncEvent
    sent_timestamp: float
    target_devices: Set[str]
    responses: Dict[str, DeviceEventResponse] = field(default_factory=dict)
    timeout_seconds: float = 5.0
    
    @property
    def is_complete(self) -> bool:
        """Check if all target devices have responded."""
        return len(self.responses) >= len(self.target_devices)
    
    @property
    def is_successful(self) -> bool:
        """Check if all responses were successful."""
        return all(r.status == "acknowledged" for r in self.responses.values())
    
    @property
    def response_rate(self) -> float:
        """Calculate response rate (0-1)."""
        if not self.target_devices:
            return 1.0
        return len(self.responses) / len(self.target_devices)
    
    @property
    def max_processing_delay(self) -> float:
        """Get maximum processing delay across all devices."""
        if not self.responses:
            return 0.0
        return max(r.processing_delay for r in self.responses.values())
    
    @property
    def synchronization_quality(self) -> float:
        """Calculate synchronization quality (0-1, higher is better)."""
        if not self.responses:
            return 0.0
        
        # Quality based on response rate and processing delays
        response_quality = self.response_rate
        
        # Penalize high processing delays
        max_delay = self.max_processing_delay
        delay_quality = max(0.0, 1.0 - (max_delay / 0.1))  # 100ms threshold
        
        return (response_quality + delay_quality) / 2

class SyncEventManager:
    """
    Manager for synchronization events and markers.
    
    Handles broadcasting events to devices, collecting responses,
    and providing verification of synchronization quality.
    """
    
    def __init__(self, device_manager=None, time_synchronizer=None):
        """
        Initialize sync event manager.
        
        Args:
            device_manager: Device manager for sending events
            time_synchronizer: Time synchronizer for clock alignment
        """
        self.device_manager = device_manager
        self.time_synchronizer = time_synchronizer
        
        # Event tracking
        self.active_events: Dict[str, SyncEventStatus] = {}
        self.event_history: List[SyncEventStatus] = []
        self.max_history_size = 1000
        
        # Event handlers
        self.event_handlers: Dict[SyncEventType, List[Callable]] = {}
        
        # Heartbeat configuration
        self.heartbeat_interval = 30.0  # seconds
        self.heartbeat_task: Optional[asyncio.Task] = None
        
        # Statistics
        self.total_events_sent = 0
        self.total_events_successful = 0
        
        logging.info("SyncEventManager initialized")
    
    def register_event_handler(self, event_type: SyncEventType, handler: Callable):
        """Register a handler for specific event types."""
        if event_type not in self.event_handlers:
            self.event_handlers[event_type] = []
        self.event_handlers[event_type].append(handler)
        logging.debug(f"Registered handler for {event_type.value}")
    
    def unregister_event_handler(self, event_type: SyncEventType, handler: Callable):
        """Unregister an event handler."""
        if event_type in self.event_handlers:
            try:
                self.event_handlers[event_type].remove(handler)
                logging.debug(f"Unregistered handler for {event_type.value}")
            except ValueError:
                pass
    
    async def start_heartbeat(self):
        """Start periodic heartbeat events."""
        if self.heartbeat_task and not self.heartbeat_task.done():
            return
        
        self.heartbeat_task = asyncio.create_task(self._heartbeat_loop())
        logging.info("Heartbeat started")
    
    async def stop_heartbeat(self):
        """Stop periodic heartbeat events."""
        if self.heartbeat_task:
            self.heartbeat_task.cancel()
            try:
                await self.heartbeat_task
            except asyncio.CancelledError:
                pass
            self.heartbeat_task = None
        
        logging.info("Heartbeat stopped")
    
    async def _heartbeat_loop(self):
        """Periodic heartbeat loop."""
        while True:
            try:
                await self.broadcast_event(
                    SyncEventType.HEARTBEAT,
                    description="Periodic heartbeat for sync verification"
                )
                await asyncio.sleep(self.heartbeat_interval)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in heartbeat loop: {e}")
                await asyncio.sleep(5.0)
    
    async def broadcast_event(
        self,
        event_type: SyncEventType,
        data: Optional[Dict] = None,
        description: str = "",
        priority: SyncEventPriority = SyncEventPriority.NORMAL,
        target_devices: Optional[Set[str]] = None,
        timeout_seconds: float = 5.0
    ) -> str:
        """
        Broadcast a synchronization event to devices.
        
        Args:
            event_type: Type of event to broadcast
            data: Additional event data
            description: Human-readable description
            priority: Event priority level
            target_devices: Specific devices to target (None for all)
            timeout_seconds: Timeout for collecting responses
            
        Returns:
            Event ID for tracking
        """
        # Generate unique event ID
        event_id = str(uuid.uuid4())
        
        # Get current timestamp
        current_time = time.time()
        
        # Create event
        event = SyncEvent(
            event_id=event_id,
            event_type=event_type,
            timestamp=current_time,
            priority=priority,
            data=data or {},
            description=description,
            source_device="PC"
        )
        
        # Determine target devices
        if target_devices is None:
            if self.device_manager:
                target_devices = set(self.device_manager.get_connected_device_ids())
            else:
                target_devices = set()
        
        # Create event status tracker
        event_status = SyncEventStatus(
            event=event,
            sent_timestamp=current_time,
            target_devices=target_devices,
            timeout_seconds=timeout_seconds
        )
        
        # Track active event
        self.active_events[event_id] = event_status
        
        try:
            # Call local event handlers
            await self._call_event_handlers(event)
            
            # Send to devices
            if target_devices and self.device_manager:
                await self._send_event_to_devices(event, target_devices)
            
            # Wait for responses with timeout
            await self._wait_for_responses(event_id, timeout_seconds)
            
            # Update statistics
            self.total_events_sent += 1
            if event_status.is_successful:
                self.total_events_successful += 1
            
            logging.info(f"Event {event_type.value} broadcast complete: "
                        f"{len(event_status.responses)}/{len(target_devices)} responses, "
                        f"quality={event_status.synchronization_quality:.2f}")
            
            return event_id
            
        except Exception as e:
            logging.error(f"Error broadcasting event {event_type.value}: {e}")
            raise
        finally:
            # Move to history
            if event_id in self.active_events:
                self.event_history.append(self.active_events[event_id])
                del self.active_events[event_id]
                
                # Limit history size
                if len(self.event_history) > self.max_history_size:
                    self.event_history = self.event_history[-self.max_history_size:]
    
    async def _call_event_handlers(self, event: SyncEvent):
        """Call registered event handlers."""
        handlers = self.event_handlers.get(event.event_type, [])
        if handlers:
            tasks = []
            for handler in handlers:
                try:
                    if asyncio.iscoroutinefunction(handler):
                        tasks.append(asyncio.create_task(handler(event)))
                    else:
                        handler(event)
                except Exception as e:
                    logging.error(f"Error in event handler: {e}")
            
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
    
    async def _send_event_to_devices(self, event: SyncEvent, target_devices: Set[str]):
        """Send event to target devices."""
        if not self.device_manager:
            logging.warning("No device manager available for sending events")
            return
        
        # Convert event to network message
        event_data = event.to_dict()
        
        # Send to each device
        tasks = []
        for device_id in target_devices:
            task = asyncio.create_task(
                self.device_manager.send_sync_event(device_id, event_data)
            )
            tasks.append(task)
        
        if tasks:
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # Log any failures
            for i, result in enumerate(results):
                if isinstance(result, Exception):
                    device_id = list(target_devices)[i]
                    logging.error(f"Failed to send event to {device_id}: {result}")
    
    async def _wait_for_responses(self, event_id: str, timeout_seconds: float):
        """Wait for device responses to an event."""
        start_time = time.time()
        
        while time.time() - start_time < timeout_seconds:
            event_status = self.active_events.get(event_id)
            if not event_status:
                break
            
            if event_status.is_complete:
                break
            
            await asyncio.sleep(0.1)  # Check every 100ms
        
        # Log timeout if not complete
        event_status = self.active_events.get(event_id)
        if event_status and not event_status.is_complete:
            missing = event_status.target_devices - set(event_status.responses.keys())
            logging.warning(f"Event {event_id} timeout: missing responses from {missing}")
    
    def handle_device_response(self, device_id: str, response_data: Dict):
        """Handle response from a device to a sync event."""
        try:
            event_id = response_data.get("event_id")
            if not event_id or event_id not in self.active_events:
                logging.warning(f"Received response for unknown event: {event_id}")
                return
            
            # Create response object
            response = DeviceEventResponse(
                device_id=device_id,
                event_id=event_id,
                received_timestamp=response_data.get("received_timestamp", 0),
                processed_timestamp=response_data.get("processed_timestamp", 0),
                local_timestamp=response_data.get("local_timestamp", time.time()),
                status=response_data.get("status", "acknowledged"),
                error_message=response_data.get("error_message", "")
            )
            
            # Store response
            event_status = self.active_events[event_id]
            event_status.responses[device_id] = response
            
            logging.debug(f"Received response from {device_id} for event {event_id}: "
                         f"delay={response.processing_delay:.3f}s")
            
        except Exception as e:
            logging.error(f"Error handling device response: {e}")
    
    async def create_manual_marker(self, description: str, data: Optional[Dict] = None) -> str:
        """Create a manual synchronization marker."""
        return await self.broadcast_event(
            SyncEventType.MANUAL_MARKER,
            data=data,
            description=description,
            priority=SyncEventPriority.HIGH
        )
    
    async def create_calibration_marker(self, calibration_type: str, data: Optional[Dict] = None) -> str:
        """Create a calibration marker for sensor alignment."""
        marker_data = {"calibration_type": calibration_type}
        if data:
            marker_data.update(data)
        
        return await self.broadcast_event(
            SyncEventType.CALIBRATION_MARKER,
            data=marker_data,
            description=f"Calibration marker: {calibration_type}",
            priority=SyncEventPriority.HIGH
        )
    
    async def verify_synchronization(self) -> Dict[str, float]:
        """
        Verify synchronization quality across all devices.
        
        Returns:
            Dictionary mapping device IDs to sync quality scores
        """
        event_id = await self.broadcast_event(
            SyncEventType.SYNC_VERIFICATION,
            description="Synchronization quality verification",
            priority=SyncEventPriority.HIGH,
            timeout_seconds=10.0
        )
        
        # Get event status
        event_status = None
        for status in self.event_history:
            if status.event.event_id == event_id:
                event_status = status
                break
        
        if not event_status:
            logging.error("Could not find verification event status")
            return {}
        
        # Calculate quality scores per device
        quality_scores = {}
        for device_id, response in event_status.responses.items():
            # Base quality on processing delay and network delay
            processing_quality = max(0.0, 1.0 - (response.processing_delay / 0.1))
            network_quality = max(0.0, 1.0 - (response.network_delay / 0.05))
            
            # Combine scores
            quality_scores[device_id] = (processing_quality + network_quality) / 2
        
        return quality_scores
    
    def get_event_statistics(self) -> Dict:
        """Get synchronization event statistics."""
        if not self.event_history:
            return {
                "total_events": 0,
                "success_rate": 0.0,
                "average_quality": 0.0,
                "average_response_time": 0.0
            }
        
        # Calculate statistics
        total_events = len(self.event_history)
        successful_events = sum(1 for status in self.event_history if status.is_successful)
        success_rate = successful_events / total_events if total_events > 0 else 0.0
        
        qualities = [status.synchronization_quality for status in self.event_history]
        average_quality = sum(qualities) / len(qualities) if qualities else 0.0
        
        response_times = []
        for status in self.event_history:
            for response in status.responses.values():
                response_times.append(response.processing_delay)
        
        average_response_time = sum(response_times) / len(response_times) if response_times else 0.0
        
        return {
            "total_events": total_events,
            "success_rate": success_rate,
            "average_quality": average_quality,
            "average_response_time": average_response_time,
            "events_sent": self.total_events_sent,
            "events_successful": self.total_events_successful
        }
    
    def get_recent_events(self, count: int = 10) -> List[SyncEventStatus]:
        """Get recent synchronization events."""
        return self.event_history[-count:] if self.event_history else []
    
    async def cleanup(self):
        """Cleanup event manager resources."""
        # Stop heartbeat
        await self.stop_heartbeat()
        
        # Clear active events
        self.active_events.clear()
        
        # Clear handlers
        self.event_handlers.clear()
        
        logging.info("SyncEventManager cleaned up")