#!/usr/bin/env python3
"""
Calibration Utilities for Multi-Device Synchronization
Provides tools for measuring true sync error and calibrating the synchronization system.
"""

import asyncio
import logging
import time
import statistics
import json
from typing import Dict, List, Optional, Tuple, Callable
from dataclasses import dataclass, field
from pathlib import Path
from datetime import datetime
from enum import Enum

from core.time_sync import TimeSynchronizer, MultiDeviceCoordinator, SyncState
from core.sync_events import SyncEventManager, SyncEventType, SyncEventPriority
from core.device_manager import DeviceManager


class CalibrationEventType(Enum):
    """Types of calibration events."""
    LED_BLINK = "led_blink"
    AUDIO_BEEP = "audio_beep"
    VIBRATION = "vibration"
    SCREEN_FLASH = "screen_flash"
    TIMESTAMP_MARKER = "timestamp_marker"


@dataclass
class CalibrationEvent:
    """A calibration event for sync verification."""
    event_id: str
    event_type: CalibrationEventType
    trigger_timestamp: float
    expected_delay_ms: float = 0.0
    description: str = ""
    metadata: Dict = field(default_factory=dict)


@dataclass
class DeviceCalibrationResponse:
    """Response from a device to a calibration event."""
    device_id: str
    event_id: str
    detected_timestamp: float
    confidence: float  # 0-1, how confident the detection is
    detection_method: str = ""
    metadata: Dict = field(default_factory=dict)


@dataclass
class CalibrationMeasurement:
    """A single calibration measurement."""
    event: CalibrationEvent
    responses: Dict[str, DeviceCalibrationResponse]
    reference_timestamp: float
    measurement_quality: float = 0.0
    
    @property
    def response_count(self) -> int:
        """Number of device responses received."""
        return len(self.responses)
    
    @property
    def detection_delays(self) -> Dict[str, float]:
        """Calculate detection delays for each device."""
        delays = {}
        for device_id, response in self.responses.items():
            delay = (response.detected_timestamp - self.reference_timestamp) * 1000  # Convert to ms
            delays[device_id] = delay
        return delays
    
    @property
    def sync_error_range_ms(self) -> float:
        """Calculate synchronization error range across devices."""
        delays = list(self.detection_delays.values())
        if len(delays) < 2:
            return 0.0
        return max(delays) - min(delays)


@dataclass
class CalibrationSession:
    """A complete calibration session."""
    session_id: str
    start_time: float
    end_time: Optional[float] = None
    device_ids: List[str] = field(default_factory=list)
    measurements: List[CalibrationMeasurement] = field(default_factory=list)
    calibration_parameters: Dict = field(default_factory=dict)
    
    @property
    def duration_seconds(self) -> float:
        """Session duration in seconds."""
        if self.end_time:
            return self.end_time - self.start_time
        return time.time() - self.start_time
    
    @property
    def average_sync_error_ms(self) -> float:
        """Average synchronization error across all measurements."""
        if not self.measurements:
            return 0.0
        errors = [m.sync_error_range_ms for m in self.measurements]
        return statistics.mean(errors)
    
    @property
    def max_sync_error_ms(self) -> float:
        """Maximum synchronization error observed."""
        if not self.measurements:
            return 0.0
        errors = [m.sync_error_range_ms for m in self.measurements]
        return max(errors)


class SyncCalibrator:
    """
    Synchronization calibration system.
    
    Provides tools for measuring true synchronization error using
    simultaneous events that can be detected by all devices.
    """
    
    def __init__(self, device_manager: DeviceManager = None, 
                 time_synchronizer: TimeSynchronizer = None):
        """Initialize the calibrator."""
        self.device_manager = device_manager or DeviceManager()
        self.time_synchronizer = time_synchronizer or TimeSynchronizer()
        self.sync_event_manager = SyncEventManager(
            device_manager=self.device_manager,
            time_synchronizer=self.time_synchronizer
        )
        
        # Calibration state
        self.current_session: Optional[CalibrationSession] = None
        self.calibration_history: List[CalibrationSession] = []
        self.event_handlers: Dict[str, Callable] = {}
        
        # Configuration
        self.default_event_interval = 5.0  # seconds between calibration events
        self.detection_timeout = 2.0  # seconds to wait for device responses
        self.min_confidence_threshold = 0.7  # minimum detection confidence
        
        logging.info("SyncCalibrator initialized")
    
    def register_event_handler(self, event_type: CalibrationEventType, handler: Callable):
        """Register handler for calibration events."""
        self.event_handlers[event_type.value] = handler
        logging.debug(f"Registered handler for {event_type.value}")
    
    async def start_calibration_session(self, device_ids: List[str], 
                                      session_config: Dict = None) -> str:
        """Start a new calibration session."""
        session_id = f"calibration_{int(time.time())}"
        
        self.current_session = CalibrationSession(
            session_id=session_id,
            start_time=time.time(),
            device_ids=device_ids.copy(),
            calibration_parameters=session_config or {}
        )
        
        logging.info(f"Started calibration session {session_id} with {len(device_ids)} devices")
        return session_id
    
    async def end_calibration_session(self) -> Optional[CalibrationSession]:
        """End the current calibration session."""
        if not self.current_session:
            logging.warning("No active calibration session to end")
            return None
        
        self.current_session.end_time = time.time()
        completed_session = self.current_session
        
        # Add to history
        self.calibration_history.append(completed_session)
        self.current_session = None
        
        logging.info(f"Ended calibration session {completed_session.session_id}")
        logging.info(f"Session results: {len(completed_session.measurements)} measurements, "
                    f"avg error: {completed_session.average_sync_error_ms:.2f}ms, "
                    f"max error: {completed_session.max_sync_error_ms:.2f}ms")
        
        return completed_session
    
    async def run_comprehensive_calibration(self, device_ids: List[str], 
                                          duration_minutes: float = 5.0) -> CalibrationSession:
        """Run a comprehensive calibration session."""
        logging.info(f"Starting comprehensive calibration for {duration_minutes} minutes")
        
        # Start session
        session_id = await self.start_calibration_session(device_ids)
        
        try:
            # Run different calibration tests
            await self._run_led_blink_calibration(device_ids, duration_minutes * 0.3)
            await self._run_audio_beep_calibration(device_ids, duration_minutes * 0.3)
            await self._run_timestamp_marker_calibration(device_ids, duration_minutes * 0.4)
            
        except Exception as e:
            logging.error(f"Error during comprehensive calibration: {e}")
        
        finally:
            # End session
            session = await self.end_calibration_session()
        
        return session or self.current_session
    
    async def _run_led_blink_calibration(self, device_ids: List[str], duration_minutes: float):
        """Run LED blink calibration test."""
        logging.info("Running LED blink calibration")
        
        end_time = time.time() + (duration_minutes * 60)
        event_count = 0
        
        while time.time() < end_time:
            # Create LED blink event
            event = CalibrationEvent(
                event_id=f"led_blink_{event_count}",
                event_type=CalibrationEventType.LED_BLINK,
                trigger_timestamp=time.time(),
                expected_delay_ms=50.0,  # Typical LED response time
                description=f"LED blink calibration event {event_count}"
            )
            
            # Trigger LED blink on all devices
            await self._trigger_calibration_event(event, device_ids)
            
            # Wait for responses and create measurement
            measurement = await self._collect_calibration_responses(event, device_ids)
            if measurement and self.current_session:
                self.current_session.measurements.append(measurement)
            
            event_count += 1
            await asyncio.sleep(self.default_event_interval)
    
    async def _run_audio_beep_calibration(self, device_ids: List[str], duration_minutes: float):
        """Run audio beep calibration test."""
        logging.info("Running audio beep calibration")
        
        end_time = time.time() + (duration_minutes * 60)
        event_count = 0
        
        while time.time() < end_time:
            # Create audio beep event
            event = CalibrationEvent(
                event_id=f"audio_beep_{event_count}",
                event_type=CalibrationEventType.AUDIO_BEEP,
                trigger_timestamp=time.time(),
                expected_delay_ms=100.0,  # Typical audio processing delay
                description=f"Audio beep calibration event {event_count}"
            )
            
            # Trigger audio beep on all devices
            await self._trigger_calibration_event(event, device_ids)
            
            # Wait for responses and create measurement
            measurement = await self._collect_calibration_responses(event, device_ids)
            if measurement and self.current_session:
                self.current_session.measurements.append(measurement)
            
            event_count += 1
            await asyncio.sleep(self.default_event_interval)
    
    async def _run_timestamp_marker_calibration(self, device_ids: List[str], duration_minutes: float):
        """Run timestamp marker calibration test."""
        logging.info("Running timestamp marker calibration")
        
        end_time = time.time() + (duration_minutes * 60)
        event_count = 0
        
        while time.time() < end_time:
            # Create timestamp marker event
            event = CalibrationEvent(
                event_id=f"timestamp_marker_{event_count}",
                event_type=CalibrationEventType.TIMESTAMP_MARKER,
                trigger_timestamp=time.time(),
                expected_delay_ms=10.0,  # Minimal processing delay
                description=f"Timestamp marker calibration event {event_count}"
            )
            
            # Send timestamp marker to all devices
            await self._trigger_calibration_event(event, device_ids)
            
            # Wait for responses and create measurement
            measurement = await self._collect_calibration_responses(event, device_ids)
            if measurement and self.current_session:
                self.current_session.measurements.append(measurement)
            
            event_count += 1
            await asyncio.sleep(self.default_event_interval)
    
    async def _trigger_calibration_event(self, event: CalibrationEvent, device_ids: List[str]):
        """Trigger a calibration event on all devices."""
        try:
            # Record precise trigger time
            trigger_time = time.time()
            
            # Send calibration command to devices
            calibration_data = {
                'event_id': event.event_id,
                'event_type': event.event_type.value,
                'trigger_timestamp': trigger_time,
                'expected_delay_ms': event.expected_delay_ms,
                'description': event.description
            }
            
            # Use sync event manager to broadcast
            await self.sync_event_manager.broadcast_event(
                event_type=SyncEventType.CALIBRATION_MARKER,
                data=calibration_data,
                description=f"Calibration: {event.description}",
                target_devices=set(device_ids),
                timeout_seconds=self.detection_timeout
            )
            
            # Call local event handler if registered
            handler = self.event_handlers.get(event.event_type.value)
            if handler:
                await self._call_handler_safely(handler, event)
            
            logging.debug(f"Triggered calibration event {event.event_id}")
            
        except Exception as e:
            logging.error(f"Failed to trigger calibration event {event.event_id}: {e}")
    
    async def _call_handler_safely(self, handler: Callable, event: CalibrationEvent):
        """Safely call event handler."""
        try:
            if asyncio.iscoroutinefunction(handler):
                await handler(event)
            else:
                handler(event)
        except Exception as e:
            logging.error(f"Error in calibration event handler: {e}")
    
    async def _collect_calibration_responses(self, event: CalibrationEvent, 
                                           device_ids: List[str]) -> Optional[CalibrationMeasurement]:
        """Collect responses from devices for a calibration event."""
        responses = {}
        
        # Wait for detection timeout
        await asyncio.sleep(self.detection_timeout)
        
        # In a real implementation, this would collect actual device responses
        # For now, we'll simulate responses based on sync status
        for device_id in device_ids:
            # Simulate device response based on current sync status
            sync_status = self.time_synchronizer.get_device_status(device_id)
            if sync_status and sync_status.is_synchronized:
                # Simulate detection with some noise
                import random
                detection_delay = random.gauss(event.expected_delay_ms, 10.0) / 1000.0  # Convert to seconds
                detected_timestamp = event.trigger_timestamp + detection_delay
                
                response = DeviceCalibrationResponse(
                    device_id=device_id,
                    event_id=event.event_id,
                    detected_timestamp=detected_timestamp,
                    confidence=random.uniform(0.8, 1.0),
                    detection_method=f"{event.event_type.value}_detection",
                    metadata={'simulated': True}
                )
                responses[device_id] = response
        
        if not responses:
            logging.warning(f"No responses received for calibration event {event.event_id}")
            return None
        
        # Create measurement
        measurement = CalibrationMeasurement(
            event=event,
            responses=responses,
            reference_timestamp=event.trigger_timestamp,
            measurement_quality=self._calculate_measurement_quality(responses)
        )
        
        logging.debug(f"Collected {len(responses)} responses for event {event.event_id}, "
                     f"sync error range: {measurement.sync_error_range_ms:.2f}ms")
        
        return measurement
    
    def _calculate_measurement_quality(self, responses: Dict[str, DeviceCalibrationResponse]) -> float:
        """Calculate quality score for a measurement."""
        if not responses:
            return 0.0
        
        # Quality based on confidence and response count
        avg_confidence = statistics.mean([r.confidence for r in responses.values()])
        response_ratio = len(responses) / len(self.current_session.device_ids) if self.current_session else 1.0
        
        return (avg_confidence + response_ratio) / 2.0
    
    def analyze_calibration_results(self, session: CalibrationSession) -> Dict:
        """Analyze calibration session results."""
        if not session.measurements:
            return {
                'error': 'No measurements available for analysis',
                'session_id': session.session_id
            }
        
        # Calculate statistics
        sync_errors = [m.sync_error_range_ms for m in session.measurements]
        measurement_qualities = [m.measurement_quality for m in session.measurements]
        
        # Per-device analysis
        device_analysis = {}
        for device_id in session.device_ids:
            device_delays = []
            device_confidences = []
            
            for measurement in session.measurements:
                if device_id in measurement.responses:
                    response = measurement.responses[device_id]
                    delay = measurement.detection_delays.get(device_id, 0.0)
                    device_delays.append(delay)
                    device_confidences.append(response.confidence)
            
            if device_delays:
                device_analysis[device_id] = {
                    'avg_delay_ms': statistics.mean(device_delays),
                    'delay_std_dev_ms': statistics.stdev(device_delays) if len(device_delays) > 1 else 0.0,
                    'max_delay_ms': max(device_delays),
                    'min_delay_ms': min(device_delays),
                    'avg_confidence': statistics.mean(device_confidences),
                    'response_count': len(device_delays),
                    'response_rate': len(device_delays) / len(session.measurements)
                }
        
        # Overall analysis
        analysis = {
            'session_info': {
                'session_id': session.session_id,
                'duration_seconds': session.duration_seconds,
                'device_count': len(session.device_ids),
                'measurement_count': len(session.measurements)
            },
            'sync_performance': {
                'average_sync_error_ms': statistics.mean(sync_errors),
                'max_sync_error_ms': max(sync_errors),
                'min_sync_error_ms': min(sync_errors),
                'sync_error_std_dev_ms': statistics.stdev(sync_errors) if len(sync_errors) > 1 else 0.0,
                'sync_error_95th_percentile_ms': self._calculate_percentile(sync_errors, 95)
            },
            'measurement_quality': {
                'average_quality': statistics.mean(measurement_qualities),
                'min_quality': min(measurement_qualities),
                'quality_consistency': 1.0 - (statistics.stdev(measurement_qualities) if len(measurement_qualities) > 1 else 0.0)
            },
            'device_analysis': device_analysis,
            'calibration_assessment': self._assess_calibration_quality(sync_errors, measurement_qualities),
            'recommendations': self._generate_calibration_recommendations(sync_errors, device_analysis)
        }
        
        return analysis
    
    def _calculate_percentile(self, values: List[float], percentile: float) -> float:
        """Calculate percentile of values."""
        if not values:
            return 0.0
        
        sorted_values = sorted(values)
        index = int((percentile / 100.0) * len(sorted_values))
        index = min(index, len(sorted_values) - 1)
        return sorted_values[index]
    
    def _assess_calibration_quality(self, sync_errors: List[float], qualities: List[float]) -> Dict:
        """Assess overall calibration quality."""
        avg_error = statistics.mean(sync_errors)
        avg_quality = statistics.mean(qualities)
        
        # Quality thresholds
        if avg_error <= 10.0 and avg_quality >= 0.9:
            assessment = "excellent"
            score = 95
        elif avg_error <= 25.0 and avg_quality >= 0.8:
            assessment = "good"
            score = 80
        elif avg_error <= 50.0 and avg_quality >= 0.7:
            assessment = "fair"
            score = 65
        else:
            assessment = "poor"
            score = 40
        
        return {
            'assessment': assessment,
            'score': score,
            'avg_sync_error_ms': avg_error,
            'avg_measurement_quality': avg_quality
        }
    
    def _generate_calibration_recommendations(self, sync_errors: List[float], 
                                            device_analysis: Dict) -> List[str]:
        """Generate recommendations based on calibration results."""
        recommendations = []
        
        avg_error = statistics.mean(sync_errors)
        max_error = max(sync_errors)
        
        # Overall sync quality recommendations
        if avg_error > 50.0:
            recommendations.append("Average sync error is high (>50ms). Check network latency and time sync configuration.")
        elif avg_error > 25.0:
            recommendations.append("Average sync error is moderate (>25ms). Consider optimizing sync parameters.")
        
        if max_error > 100.0:
            recommendations.append("Maximum sync error is very high (>100ms). Investigate sync stability issues.")
        
        # Device-specific recommendations
        for device_id, analysis in device_analysis.items():
            if analysis['response_rate'] < 0.8:
                recommendations.append(f"Device {device_id} has low response rate ({analysis['response_rate']:.1%}). Check connectivity.")
            
            if analysis['avg_confidence'] < 0.7:
                recommendations.append(f"Device {device_id} has low detection confidence ({analysis['avg_confidence']:.2f}). Check calibration setup.")
            
            if analysis['delay_std_dev_ms'] > 20.0:
                recommendations.append(f"Device {device_id} has high delay variation ({analysis['delay_std_dev_ms']:.1f}ms). Check device performance.")
        
        # Sync error consistency
        if len(sync_errors) > 1:
            error_std_dev = statistics.stdev(sync_errors)
            if error_std_dev > 15.0:
                recommendations.append("Sync error is inconsistent across measurements. Check system stability.")
        
        if not recommendations:
            recommendations.append("Calibration results are good. System is ready for production use.")
        
        return recommendations
    
    def save_calibration_report(self, session: CalibrationSession, output_path: str) -> str:
        """Save calibration analysis report."""
        analysis = self.analyze_calibration_results(session)
        
        # Add raw measurement data
        measurements_data = []
        for measurement in session.measurements:
            measurement_data = {
                'event_id': measurement.event.event_id,
                'event_type': measurement.event.event_type.value,
                'trigger_timestamp': measurement.event.trigger_timestamp,
                'reference_timestamp': measurement.reference_timestamp,
                'sync_error_range_ms': measurement.sync_error_range_ms,
                'measurement_quality': measurement.measurement_quality,
                'detection_delays': measurement.detection_delays,
                'responses': {
                    device_id: {
                        'detected_timestamp': response.detected_timestamp,
                        'confidence': response.confidence,
                        'detection_method': response.detection_method
                    }
                    for device_id, response in measurement.responses.items()
                }
            }
            measurements_data.append(measurement_data)
        
        # Complete report
        report = {
            'calibration_report': analysis,
            'raw_measurements': measurements_data,
            'session_parameters': session.calibration_parameters,
            'generated_at': datetime.now().isoformat()
        }
        
        # Save to file
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(report, f, indent=2, default=str)
        
        logging.info(f"Calibration report saved to {output_path}")
        return output_path
    
    async def run_quick_calibration_check(self, device_ids: List[str]) -> Dict:
        """Run a quick calibration check (1-2 minutes)."""
        logging.info("Running quick calibration check")
        
        session_id = await self.start_calibration_session(device_ids, {
            'type': 'quick_check',
            'duration_minutes': 1.5
        })
        
        try:
            # Run a few timestamp marker events
            for i in range(5):
                event = CalibrationEvent(
                    event_id=f"quick_check_{i}",
                    event_type=CalibrationEventType.TIMESTAMP_MARKER,
                    trigger_timestamp=time.time(),
                    description=f"Quick calibration check {i}"
                )
                
                await self._trigger_calibration_event(event, device_ids)
                measurement = await self._collect_calibration_responses(event, device_ids)
                
                if measurement and self.current_session:
                    self.current_session.measurements.append(measurement)
                
                await asyncio.sleep(2.0)  # 2 second intervals
            
        except Exception as e:
            logging.error(f"Error during quick calibration check: {e}")
        
        finally:
            session = await self.end_calibration_session()
        
        if session:
            return self.analyze_calibration_results(session)
        else:
            return {'error': 'Quick calibration check failed'}
    
    def get_calibration_history(self, limit: int = 10) -> List[Dict]:
        """Get recent calibration session history."""
        recent_sessions = self.calibration_history[-limit:] if limit > 0 else self.calibration_history
        
        history = []
        for session in recent_sessions:
            summary = {
                'session_id': session.session_id,
                'start_time': session.start_time,
                'end_time': session.end_time,
                'duration_seconds': session.duration_seconds,
                'device_count': len(session.device_ids),
                'measurement_count': len(session.measurements),
                'average_sync_error_ms': session.average_sync_error_ms,
                'max_sync_error_ms': session.max_sync_error_ms
            }
            history.append(summary)
        
        return history
    
    async def cleanup(self):
        """Cleanup calibrator resources."""
        if self.current_session:
            await self.end_calibration_session()
        
        await self.sync_event_manager.stop_heartbeat()
        logging.info("SyncCalibrator cleaned up")


class AutoCalibrationScheduler:
    """
    Automatic calibration scheduler for continuous system validation.
    """
    
    def __init__(self, calibrator: SyncCalibrator):
        """Initialize auto calibration scheduler."""
        self.calibrator = calibrator
        self.scheduler_task: Optional[asyncio.Task] = None
        self.calibration_interval = 3600.0  # 1 hour default
        self.is_running = False
        
        logging.info("AutoCalibrationScheduler initialized")
    
    async def start_auto_calibration(self, device_ids: List[str], 
                                   interval_seconds: float = 3600.0):
        """Start automatic calibration scheduling."""
        if self.is_running:
            logging.warning("Auto calibration already running")
            return
        
        self.calibration_interval = interval_seconds
        self.is_running = True
        
        self.scheduler_task = asyncio.create_task(
            self._auto_calibration_loop(device_ids)
        )
        
        logging.info(f"Started auto calibration with {interval_seconds}s interval")
    
    async def stop_auto_calibration(self):
        """Stop automatic calibration scheduling."""
        if not self.is_running:
            return
        
        self.is_running = False
        
        if self.scheduler_task:
            self.scheduler_task.cancel()
            try:
                await self.scheduler_task
            except asyncio.CancelledError:
                pass
            self.scheduler_task = None
        
        logging.info("Stopped auto calibration")
    
    async def _auto_calibration_loop(self, device_ids: List[str]):
        """Automatic calibration loop."""
        while self.is_running:
            try:
                logging.info("Running scheduled calibration check")
                
                # Run quick calibration check
                results = await self.calibrator.run_quick_calibration_check(device_ids)
                
                # Log results
                if 'sync_performance' in results:
                    avg_error = results['sync_performance']['average_sync_error_ms']
                    logging.info(f"Scheduled calibration completed: avg_error={avg_error:.2f}ms")
                    
                    # Alert if error is too high
                    if avg_error > 50.0:
                        logging.warning(f"High sync error detected in scheduled calibration: {avg_error:.2f}ms")
                
                # Wait for next calibration
                await asyncio.sleep(self.calibration_interval)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in auto calibration loop: {e}")
                await asyncio.sleep(60.0)  # Wait 1 minute before retrying


async def main():
    """Example usage of calibration utilities."""
    from utils.logger import setup_logging
    
    setup_logging(level=logging.INFO)
    
    # Example device IDs
    device_ids = ["device_1", "device_2", "device_3"]
    
    # Create calibrator
    calibrator = SyncCalibrator()
    
    try:
        # Run comprehensive calibration
        session = await calibrator.run_comprehensive_calibration(device_ids, duration_minutes=2.0)
        
        # Analyze results
        analysis = calibrator.analyze_calibration_results(session)
        
        # Save report
        report_path = f"calibration_report_{session.session_id}.json"
        calibrator.save_calibration_report(session, report_path)
        
        print(f"Calibration completed. Report saved to {report_path}")
        print(f"Average sync error: {analysis['sync_performance']['average_sync_error_ms']:.2f}ms")
        print(f"Assessment: {analysis['calibration_assessment']['assessment']}")
        
        # Start auto calibration scheduler
        scheduler = AutoCalibrationScheduler(calibrator)
        await scheduler.start_auto_calibration(device_ids, interval_seconds=300)  # 5 minutes for demo
        
        # Let it run for a bit
        await asyncio.sleep(10)
        
        await scheduler.stop_auto_calibration()
        
    except Exception as e:
        logging.error(f"Calibration example failed: {e}")
    
    finally:
        await calibrator.cleanup()


if __name__ == "__main__":
    asyncio.run(main())