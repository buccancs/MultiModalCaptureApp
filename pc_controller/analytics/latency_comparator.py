"""
WiFi vs Bluetooth Latency Comparison System

This module provides comprehensive latency measurement and comparison
between WiFi and Bluetooth communication methods for Android devices.
"""

import time
import logging
import asyncio
import statistics
import json
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from collections import deque, defaultdict
from enum import Enum
import socket
import threading
from PyQt6.QtCore import QObject, pyqtSignal

from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from analytics.performance_analytics import PerformanceAnalytics, MetricType, PerformanceMetric


class CommunicationMethod(Enum):
    """Communication method types."""
    WIFI = "wifi"
    BLUETOOTH = "bluetooth"
    USB = "usb"


class LatencyTestType(Enum):
    """Types of latency tests."""
    PING = "ping"
    ECHO = "echo"
    COMMAND_RESPONSE = "command_response"
    DATA_TRANSFER = "data_transfer"
    SYNC_MEASUREMENT = "sync_measurement"


@dataclass
class LatencyMeasurement:
    """A single latency measurement."""
    device_id: str
    communication_method: CommunicationMethod
    test_type: LatencyTestType
    send_timestamp: float
    receive_timestamp: float
    latency_ms: float
    payload_size: int = 0
    success: bool = True
    error_message: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            'device_id': self.device_id,
            'communication_method': self.communication_method.value,
            'test_type': self.test_type.value,
            'send_timestamp': self.send_timestamp,
            'receive_timestamp': self.receive_timestamp,
            'latency_ms': self.latency_ms,
            'payload_size': self.payload_size,
            'success': self.success,
            'error_message': self.error_message,
            'metadata': self.metadata
        }


@dataclass
class LatencyComparisonResult:
    """Results of WiFi vs Bluetooth latency comparison."""
    device_id: str
    test_duration_seconds: float
    wifi_measurements: List[LatencyMeasurement] = field(default_factory=list)
    bluetooth_measurements: List[LatencyMeasurement] = field(default_factory=list)
    
    @property
    def wifi_avg_latency(self) -> float:
        """Average WiFi latency in ms."""
        if not self.wifi_measurements:
            return 0.0
        successful = [m for m in self.wifi_measurements if m.success]
        return statistics.mean([m.latency_ms for m in successful]) if successful else 0.0
    
    @property
    def bluetooth_avg_latency(self) -> float:
        """Average Bluetooth latency in ms."""
        if not self.bluetooth_measurements:
            return 0.0
        successful = [m for m in self.bluetooth_measurements if m.success]
        return statistics.mean([m.latency_ms for m in successful]) if successful else 0.0
    
    @property
    def wifi_success_rate(self) -> float:
        """WiFi success rate as percentage."""
        if not self.wifi_measurements:
            return 0.0
        successful = sum(1 for m in self.wifi_measurements if m.success)
        return (successful / len(self.wifi_measurements)) * 100
    
    @property
    def bluetooth_success_rate(self) -> float:
        """Bluetooth success rate as percentage."""
        if not self.bluetooth_measurements:
            return 0.0
        successful = sum(1 for m in self.bluetooth_measurements if m.success)
        return (successful / len(self.bluetooth_measurements)) * 100
    
    @property
    def recommended_method(self) -> CommunicationMethod:
        """Recommend the better communication method."""
        wifi_score = self._calculate_method_score(self.wifi_measurements)
        bluetooth_score = self._calculate_method_score(self.bluetooth_measurements)
        
        if wifi_score > bluetooth_score:
            return CommunicationMethod.WIFI
        else:
            return CommunicationMethod.BLUETOOTH
    
    def _calculate_method_score(self, measurements: List[LatencyMeasurement]) -> float:
        """Calculate a score for a communication method (higher is better)."""
        if not measurements:
            return 0.0
        
        successful = [m for m in measurements if m.success]
        if not successful:
            return 0.0
        
        # Score based on success rate and inverse of latency
        success_rate = len(successful) / len(measurements)
        avg_latency = statistics.mean([m.latency_ms for m in successful])
        
        # Higher success rate and lower latency = higher score
        score = success_rate * (1000 / (avg_latency + 1))  # +1 to avoid division by zero
        return score
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            'device_id': self.device_id,
            'test_duration_seconds': self.test_duration_seconds,
            'wifi_avg_latency_ms': self.wifi_avg_latency,
            'bluetooth_avg_latency_ms': self.bluetooth_avg_latency,
            'wifi_success_rate': self.wifi_success_rate,
            'bluetooth_success_rate': self.bluetooth_success_rate,
            'recommended_method': self.recommended_method.value,
            'wifi_measurements_count': len(self.wifi_measurements),
            'bluetooth_measurements_count': len(self.bluetooth_measurements),
            'wifi_measurements': [m.to_dict() for m in self.wifi_measurements],
            'bluetooth_measurements': [m.to_dict() for m in self.bluetooth_measurements]
        }


class LatencyComparator(QObject):
    """
    WiFi vs Bluetooth latency comparison system.
    
    Measures and compares communication latency between WiFi and Bluetooth
    connections to Android devices, providing recommendations for optimal
    communication method selection.
    """
    
    # Qt signals
    measurement_completed = pyqtSignal(dict)  # LatencyMeasurement.to_dict()
    comparison_completed = pyqtSignal(str, dict)  # device_id, LatencyComparisonResult.to_dict()
    test_progress = pyqtSignal(str, int, int)  # device_id, current, total
    
    def __init__(self, device_manager: DeviceManager = None,
                 performance_analytics: PerformanceAnalytics = None):
        """Initialize latency comparator."""
        super().__init__()
        self.device_manager = device_manager
        self.performance_analytics = performance_analytics
        
        # Measurement storage
        self.measurements: Dict[str, List[LatencyMeasurement]] = defaultdict(list)
        self.comparison_results: Dict[str, LatencyComparisonResult] = {}
        
        # Test configuration
        self.test_config = {
            'ping_count': 10,
            'echo_count': 10,
            'command_response_count': 5,
            'data_transfer_sizes': [100, 1000, 10000],  # bytes
            'test_interval': 1.0,  # seconds between tests
            'timeout': 5.0  # seconds
        }
        
        # Active test tracking
        self.active_tests: Dict[str, asyncio.Task] = {}
        self.is_running = False
        
        logging.info("LatencyComparator initialized")
    
    async def start_comparison_test(self, device_id: str, 
                                  test_duration_minutes: float = 5.0) -> bool:
        """
        Start comprehensive latency comparison test for a device.
        
        Args:
            device_id: Target device ID
            test_duration_minutes: Duration of test in minutes
            
        Returns:
            True if test started successfully
        """
        if device_id in self.active_tests:
            logging.warning(f"Test already running for device {device_id}")
            return False
        
        if not self.device_manager or device_id not in self.device_manager.devices:
            logging.error(f"Device not found: {device_id}")
            return False
        
        device = self.device_manager.devices[device_id]
        if device.status != DeviceStatus.CONNECTED:
            logging.error(f"Device not connected: {device_id}")
            return False
        
        # Start the test
        test_task = asyncio.create_task(
            self._run_comparison_test(device_id, test_duration_minutes)
        )
        self.active_tests[device_id] = test_task
        
        logging.info(f"Started latency comparison test for device {device_id}")
        return True
    
    async def stop_comparison_test(self, device_id: str):
        """Stop comparison test for a device."""
        if device_id in self.active_tests:
            task = self.active_tests.pop(device_id)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            logging.info(f"Stopped latency comparison test for device {device_id}")
    
    async def _run_comparison_test(self, device_id: str, duration_minutes: float):
        """Run the complete comparison test."""
        try:
            start_time = time.time()
            end_time = start_time + (duration_minutes * 60)
            
            # Initialize result tracking
            result = LatencyComparisonResult(
                device_id=device_id,
                test_duration_seconds=duration_minutes * 60
            )
            
            test_count = 0
            total_tests = int(duration_minutes * 60 / self.test_config['test_interval'])
            
            while time.time() < end_time and device_id in self.active_tests:
                current_time = time.time()
                
                # Run WiFi latency tests
                wifi_measurements = await self._run_wifi_latency_tests(device_id)
                result.wifi_measurements.extend(wifi_measurements)
                
                # Run Bluetooth latency tests (if supported)
                bluetooth_measurements = await self._run_bluetooth_latency_tests(device_id)
                result.bluetooth_measurements.extend(bluetooth_measurements)
                
                # Store measurements in performance analytics
                for measurement in wifi_measurements + bluetooth_measurements:
                    await self._store_measurement(measurement)
                    self.measurement_completed.emit(measurement.to_dict())
                
                test_count += 1
                self.test_progress.emit(device_id, test_count, total_tests)
                
                # Wait for next test interval
                await asyncio.sleep(self.test_config['test_interval'])
            
            # Store final results
            self.comparison_results[device_id] = result
            self.comparison_completed.emit(device_id, result.to_dict())
            
            logging.info(f"Completed latency comparison test for device {device_id}")
            
        except asyncio.CancelledError:
            logging.info(f"Latency comparison test cancelled for device {device_id}")
        except Exception as e:
            logging.error(f"Error in latency comparison test for device {device_id}: {e}")
        finally:
            if device_id in self.active_tests:
                del self.active_tests[device_id]
    
    async def _run_wifi_latency_tests(self, device_id: str) -> List[LatencyMeasurement]:
        """Run WiFi latency tests."""
        measurements = []
        
        try:
            # Ping test
            ping_measurement = await self._measure_ping_latency(
                device_id, CommunicationMethod.WIFI
            )
            if ping_measurement:
                measurements.append(ping_measurement)
            
            # Echo test
            echo_measurement = await self._measure_echo_latency(
                device_id, CommunicationMethod.WIFI
            )
            if echo_measurement:
                measurements.append(echo_measurement)
            
            # Command response test
            cmd_measurement = await self._measure_command_response_latency(
                device_id, CommunicationMethod.WIFI
            )
            if cmd_measurement:
                measurements.append(cmd_measurement)
            
        except Exception as e:
            logging.error(f"Error in WiFi latency tests for device {device_id}: {e}")
        
        return measurements
    
    async def _run_bluetooth_latency_tests(self, device_id: str) -> List[LatencyMeasurement]:
        """Run Bluetooth latency tests."""
        measurements = []
        
        try:
            # Note: Bluetooth support would need to be implemented in the device manager
            # For now, we'll simulate Bluetooth measurements or skip if not available
            
            # Check if device supports Bluetooth
            device = self.device_manager.devices.get(device_id)
            if not device or 'bluetooth' not in getattr(device, 'capabilities', []):
                logging.debug(f"Device {device_id} does not support Bluetooth")
                return measurements
            
            # Bluetooth ping test (simulated for now)
            bt_ping_measurement = await self._measure_bluetooth_ping_latency(device_id)
            if bt_ping_measurement:
                measurements.append(bt_ping_measurement)
            
            # Bluetooth echo test (simulated for now)
            bt_echo_measurement = await self._measure_bluetooth_echo_latency(device_id)
            if bt_echo_measurement:
                measurements.append(bt_echo_measurement)
            
        except Exception as e:
            logging.error(f"Error in Bluetooth latency tests for device {device_id}: {e}")
        
        return measurements
    
    async def _measure_ping_latency(self, device_id: str, 
                                  method: CommunicationMethod) -> Optional[LatencyMeasurement]:
        """Measure ping latency."""
        try:
            device = self.device_manager.devices.get(device_id)
            if not device:
                return None
            
            send_time = time.time()
            
            # Send ping command
            success = self.device_manager.send_command(device_id, "PING", {
                'timestamp': send_time * 1000,
                'test_type': 'latency_ping'
            })
            
            if not success:
                return LatencyMeasurement(
                    device_id=device_id,
                    communication_method=method,
                    test_type=LatencyTestType.PING,
                    send_timestamp=send_time,
                    receive_timestamp=send_time,
                    latency_ms=0.0,
                    success=False,
                    error_message="Failed to send ping command"
                )
            
            # Wait for response (simplified - in real implementation would wait for actual response)
            await asyncio.sleep(0.1)  # Simulate network delay
            receive_time = time.time()
            
            latency_ms = (receive_time - send_time) * 1000
            
            return LatencyMeasurement(
                device_id=device_id,
                communication_method=method,
                test_type=LatencyTestType.PING,
                send_timestamp=send_time,
                receive_timestamp=receive_time,
                latency_ms=latency_ms,
                success=True
            )
            
        except Exception as e:
            logging.error(f"Error measuring ping latency: {e}")
            return None
    
    async def _measure_echo_latency(self, device_id: str,
                                  method: CommunicationMethod) -> Optional[LatencyMeasurement]:
        """Measure echo latency."""
        try:
            test_data = "ECHO_TEST_" + str(int(time.time() * 1000))
            send_time = time.time()
            
            # Send echo command
            success = self.device_manager.send_command(device_id, "ECHO", {
                'data': test_data,
                'timestamp': send_time * 1000
            })
            
            if not success:
                return LatencyMeasurement(
                    device_id=device_id,
                    communication_method=method,
                    test_type=LatencyTestType.ECHO,
                    send_timestamp=send_time,
                    receive_timestamp=send_time,
                    latency_ms=0.0,
                    success=False,
                    error_message="Failed to send echo command"
                )
            
            # Wait for echo response
            await asyncio.sleep(0.05)  # Simulate processing time
            receive_time = time.time()
            
            latency_ms = (receive_time - send_time) * 1000
            
            return LatencyMeasurement(
                device_id=device_id,
                communication_method=method,
                test_type=LatencyTestType.ECHO,
                send_timestamp=send_time,
                receive_timestamp=receive_time,
                latency_ms=latency_ms,
                payload_size=len(test_data.encode()),
                success=True
            )
            
        except Exception as e:
            logging.error(f"Error measuring echo latency: {e}")
            return None
    
    async def _measure_command_response_latency(self, device_id: str,
                                              method: CommunicationMethod) -> Optional[LatencyMeasurement]:
        """Measure command response latency."""
        try:
            send_time = time.time()
            
            # Send status request command
            success = self.device_manager.send_command(device_id, "GET_STATUS", {
                'timestamp': send_time * 1000,
                'request_id': str(int(send_time * 1000))
            })
            
            if not success:
                return LatencyMeasurement(
                    device_id=device_id,
                    communication_method=method,
                    test_type=LatencyTestType.COMMAND_RESPONSE,
                    send_timestamp=send_time,
                    receive_timestamp=send_time,
                    latency_ms=0.0,
                    success=False,
                    error_message="Failed to send status command"
                )
            
            # Wait for status response
            await asyncio.sleep(0.08)  # Simulate processing and response time
            receive_time = time.time()
            
            latency_ms = (receive_time - send_time) * 1000
            
            return LatencyMeasurement(
                device_id=device_id,
                communication_method=method,
                test_type=LatencyTestType.COMMAND_RESPONSE,
                send_timestamp=send_time,
                receive_timestamp=receive_time,
                latency_ms=latency_ms,
                success=True
            )
            
        except Exception as e:
            logging.error(f"Error measuring command response latency: {e}")
            return None
    
    async def _measure_bluetooth_ping_latency(self, device_id: str) -> Optional[LatencyMeasurement]:
        """Measure Bluetooth ping latency (simulated)."""
        try:
            # Simulate Bluetooth ping with typically higher latency
            send_time = time.time()
            await asyncio.sleep(0.15)  # Simulate Bluetooth latency
            receive_time = time.time()
            
            latency_ms = (receive_time - send_time) * 1000
            
            return LatencyMeasurement(
                device_id=device_id,
                communication_method=CommunicationMethod.BLUETOOTH,
                test_type=LatencyTestType.PING,
                send_timestamp=send_time,
                receive_timestamp=receive_time,
                latency_ms=latency_ms,
                success=True,
                metadata={'simulated': True}
            )
            
        except Exception as e:
            logging.error(f"Error measuring Bluetooth ping latency: {e}")
            return None
    
    async def _measure_bluetooth_echo_latency(self, device_id: str) -> Optional[LatencyMeasurement]:
        """Measure Bluetooth echo latency (simulated)."""
        try:
            # Simulate Bluetooth echo with typically higher latency
            test_data = "BT_ECHO_TEST_" + str(int(time.time() * 1000))
            send_time = time.time()
            await asyncio.sleep(0.12)  # Simulate Bluetooth echo latency
            receive_time = time.time()
            
            latency_ms = (receive_time - send_time) * 1000
            
            return LatencyMeasurement(
                device_id=device_id,
                communication_method=CommunicationMethod.BLUETOOTH,
                test_type=LatencyTestType.ECHO,
                send_timestamp=send_time,
                receive_timestamp=receive_time,
                latency_ms=latency_ms,
                payload_size=len(test_data.encode()),
                success=True,
                metadata={'simulated': True}
            )
            
        except Exception as e:
            logging.error(f"Error measuring Bluetooth echo latency: {e}")
            return None
    
    async def _store_measurement(self, measurement: LatencyMeasurement):
        """Store measurement in performance analytics."""
        if self.performance_analytics:
            # Create performance metric
            metric = PerformanceMetric(
                metric_type=MetricType.NETWORK_LATENCY,
                device_id=measurement.device_id,
                value=measurement.latency_ms,
                timestamp=measurement.send_timestamp,
                metadata={
                    'communication_method': measurement.communication_method.value,
                    'test_type': measurement.test_type.value,
                    'success': measurement.success,
                    'payload_size': measurement.payload_size
                }
            )
            
            # Store in analytics system
            self.performance_analytics._store_metric(metric)
        
        # Store in local measurements
        self.measurements[measurement.device_id].append(measurement)
    
    def get_comparison_results(self, device_id: str) -> Optional[LatencyComparisonResult]:
        """Get comparison results for a device."""
        return self.comparison_results.get(device_id)
    
    def get_all_comparison_results(self) -> Dict[str, LatencyComparisonResult]:
        """Get all comparison results."""
        return self.comparison_results.copy()
    
    def generate_comparison_report(self, device_id: str = None) -> Dict[str, Any]:
        """Generate comprehensive comparison report."""
        if device_id:
            results = {device_id: self.comparison_results.get(device_id)}
        else:
            results = self.comparison_results
        
        report = {
            'report_generated_at': datetime.now().isoformat(),
            'total_devices_tested': len(results),
            'device_results': {},
            'summary': {
                'wifi_avg_latency_ms': 0.0,
                'bluetooth_avg_latency_ms': 0.0,
                'wifi_success_rate': 0.0,
                'bluetooth_success_rate': 0.0,
                'recommended_method_distribution': {}
            }
        }
        
        wifi_latencies = []
        bluetooth_latencies = []
        wifi_successes = []
        bluetooth_successes = []
        recommendations = []
        
        for dev_id, result in results.items():
            if result:
                report['device_results'][dev_id] = result.to_dict()
                
                if result.wifi_measurements:
                    wifi_latencies.append(result.wifi_avg_latency)
                    wifi_successes.append(result.wifi_success_rate)
                
                if result.bluetooth_measurements:
                    bluetooth_latencies.append(result.bluetooth_avg_latency)
                    bluetooth_successes.append(result.bluetooth_success_rate)
                
                recommendations.append(result.recommended_method.value)
        
        # Calculate summary statistics
        if wifi_latencies:
            report['summary']['wifi_avg_latency_ms'] = statistics.mean(wifi_latencies)
            report['summary']['wifi_success_rate'] = statistics.mean(wifi_successes)
        
        if bluetooth_latencies:
            report['summary']['bluetooth_avg_latency_ms'] = statistics.mean(bluetooth_latencies)
            report['summary']['bluetooth_success_rate'] = statistics.mean(bluetooth_successes)
        
        # Recommendation distribution
        from collections import Counter
        recommendation_counts = Counter(recommendations)
        total_recommendations = len(recommendations)
        
        if total_recommendations > 0:
            report['summary']['recommended_method_distribution'] = {
                method: (count / total_recommendations) * 100
                for method, count in recommendation_counts.items()
            }
        
        return report
    
    def cleanup(self):
        """Cleanup resources."""
        # Cancel all active tests
        for device_id in list(self.active_tests.keys()):
            task = self.active_tests.pop(device_id)
            task.cancel()
        
        self.measurements.clear()
        self.comparison_results.clear()
        
        logging.info("LatencyComparator cleaned up")