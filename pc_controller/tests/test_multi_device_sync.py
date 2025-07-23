#!/usr/bin/env python3
"""
Multi-Device Synchronization Test Suite
Tests synchronization quality and coordination across multiple Android devices.
"""

import asyncio
import logging
import time
import statistics
from typing import Dict, List, Optional
from dataclasses import dataclass
from pathlib import Path

from core.time_sync import TimeSynchronizer, MultiDeviceCoordinator, SyncState, SyncQuality
from core.sync_events import SyncEventManager, SyncEventType, SyncEventPriority
from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from utils.logger import setup_logging


@dataclass
class SyncTestResult:
    """Result of a synchronization test."""
    test_name: str
    success: bool
    device_count: int
    sync_quality: float
    max_offset_ms: float
    avg_uncertainty_ms: float
    test_duration_seconds: float
    error_message: str = ""
    detailed_results: Dict = None


class MultiDeviceSyncTester:
    """
    Comprehensive testing suite for multi-device synchronization.
    """
    
    def __init__(self, device_manager: DeviceManager = None):
        """Initialize the sync tester."""
        self.device_manager = device_manager or DeviceManager()
        self.time_synchronizer = TimeSynchronizer()
        self.coordinator = MultiDeviceCoordinator()
        self.sync_event_manager = SyncEventManager(
            device_manager=self.device_manager,
            time_synchronizer=self.time_synchronizer
        )
        
        self.test_results: List[SyncTestResult] = []
        
        # Test configuration
        self.test_timeout = 60.0  # seconds
        self.sync_quality_threshold = 0.8
        self.max_acceptable_offset_ms = 50.0
        
        logging.info("MultiDeviceSyncTester initialized")
    
    async def run_comprehensive_tests(self, device_ids: List[str]) -> List[SyncTestResult]:
        """Run comprehensive synchronization tests."""
        logging.info(f"Starting comprehensive sync tests for {len(device_ids)} devices")
        
        self.test_results = []
        
        # Register devices
        for device_id in device_ids:
            self.time_synchronizer.register_device(device_id)
        
        try:
            # Test 1: Basic synchronization
            await self._test_basic_synchronization(device_ids)
            
            # Test 2: Multi-device coordination
            await self._test_multi_device_coordination(device_ids)
            
            # Test 3: Sync event broadcasting
            await self._test_sync_event_broadcasting(device_ids)
            
            # Test 4: Network failure recovery
            await self._test_network_failure_recovery(device_ids)
            
            # Test 5: High-frequency synchronization
            await self._test_high_frequency_sync(device_ids)
            
            # Test 6: Sync quality under load
            await self._test_sync_under_load(device_ids)
            
            # Test 7: Long-duration stability
            await self._test_long_duration_stability(device_ids)
            
        except Exception as e:
            logging.error(f"Error during comprehensive tests: {e}")
        
        finally:
            # Cleanup
            await self._cleanup_test_environment()
        
        return self.test_results
    
    async def _test_basic_synchronization(self, device_ids: List[str]):
        """Test basic synchronization functionality."""
        test_name = "Basic Synchronization"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Perform synchronization for each device
            sync_results = {}
            for device_id in device_ids:
                success = await self.time_synchronizer.sync_device(device_id, num_measurements=5)
                sync_results[device_id] = success
            
            # Analyze results
            successful_devices = sum(1 for success in sync_results.values() if success)
            success = successful_devices == len(device_ids)
            
            # Calculate quality metrics
            quality_metrics = self._calculate_sync_quality_metrics(device_ids)
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=quality_metrics.get('avg_quality', 0.0),
                max_offset_ms=quality_metrics.get('max_offset_ms', 0.0),
                avg_uncertainty_ms=quality_metrics.get('avg_uncertainty_ms', 0.0),
                test_duration_seconds=time.time() - start_time,
                detailed_results=sync_results
            )
            
            if not success:
                failed_devices = [did for did, success in sync_results.items() if not success]
                result.error_message = f"Synchronization failed for devices: {failed_devices}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_multi_device_coordination(self, device_ids: List[str]):
        """Test multi-device coordination functionality."""
        test_name = "Multi-Device Coordination"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Create device group
            group_name = "test_group"
            self.coordinator.create_device_group(group_name, device_ids)
            
            # Perform coordinated synchronization
            results = await self.coordinator.coordinate_group_sync(group_name, self.time_synchronizer)
            
            # Analyze coordination quality
            successful_devices = sum(1 for success in results.values() if success)
            success = successful_devices >= len(device_ids) * 0.8  # 80% success threshold
            
            quality_metrics = self._calculate_coordination_quality_metrics(device_ids, results)
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=quality_metrics.get('coordination_quality', 0.0),
                max_offset_ms=quality_metrics.get('max_inter_device_offset_ms', 0.0),
                avg_uncertainty_ms=quality_metrics.get('avg_uncertainty_ms', 0.0),
                test_duration_seconds=time.time() - start_time,
                detailed_results=results
            )
            
            if not success:
                result.error_message = f"Coordination quality below threshold: {quality_metrics.get('coordination_quality', 0.0):.2f}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_sync_event_broadcasting(self, device_ids: List[str]):
        """Test synchronization event broadcasting."""
        test_name = "Sync Event Broadcasting"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Start sync event manager
            await self.sync_event_manager.start_heartbeat()
            
            # Broadcast test events
            event_results = []
            test_events = [
                (SyncEventType.CALIBRATION_MARKER, "Test calibration marker"),
                (SyncEventType.SYNC_VERIFICATION, "Test sync verification"),
                (SyncEventType.MANUAL_MARKER, "Test manual marker")
            ]
            
            for event_type, description in test_events:
                event_id = await self.sync_event_manager.broadcast_event(
                    event_type=event_type,
                    description=description,
                    target_devices=set(device_ids),
                    timeout_seconds=10.0
                )
                
                # Get event status
                event_status = self.sync_event_manager.get_event_status(event_id)
                if event_status:
                    event_results.append({
                        'event_type': event_type.value,
                        'success': event_status.is_successful,
                        'response_rate': event_status.response_rate,
                        'sync_quality': event_status.synchronization_quality
                    })
            
            # Stop heartbeat
            await self.sync_event_manager.stop_heartbeat()
            
            # Analyze results
            avg_response_rate = statistics.mean([r['response_rate'] for r in event_results])
            avg_sync_quality = statistics.mean([r['sync_quality'] for r in event_results])
            success = avg_response_rate >= 0.8 and avg_sync_quality >= 0.7
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=avg_sync_quality,
                max_offset_ms=0.0,  # Not applicable for event broadcasting
                avg_uncertainty_ms=0.0,  # Not applicable for event broadcasting
                test_duration_seconds=time.time() - start_time,
                detailed_results=event_results
            )
            
            if not success:
                result.error_message = f"Event broadcasting quality below threshold: response_rate={avg_response_rate:.2f}, sync_quality={avg_sync_quality:.2f}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_network_failure_recovery(self, device_ids: List[str]):
        """Test network failure recovery mechanisms."""
        test_name = "Network Failure Recovery"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            recovery_results = []
            
            # Simulate network failures for each device
            for device_id in device_ids:
                # Simulate network failure
                await self.time_synchronizer.handle_network_failure(device_id, "connection_timeout")
                
                # Wait for recovery attempt
                await asyncio.sleep(2.0)
                
                # Check if recovery was attempted
                device_status = self.time_synchronizer.get_device_status(device_id)
                recovery_attempted = device_status and device_status.state in [SyncState.RECOVERING, SyncState.SYNCHRONIZED]
                
                recovery_results.append({
                    'device_id': device_id,
                    'recovery_attempted': recovery_attempted,
                    'final_state': device_status.state.value if device_status else 'unknown'
                })
            
            # Analyze recovery success
            successful_recoveries = sum(1 for r in recovery_results if r['recovery_attempted'])
            success = successful_recoveries >= len(device_ids) * 0.7  # 70% recovery threshold
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=successful_recoveries / len(device_ids) if device_ids else 0.0,
                max_offset_ms=0.0,  # Not applicable for recovery test
                avg_uncertainty_ms=0.0,  # Not applicable for recovery test
                test_duration_seconds=time.time() - start_time,
                detailed_results=recovery_results
            )
            
            if not success:
                result.error_message = f"Recovery success rate below threshold: {successful_recoveries}/{len(device_ids)}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_high_frequency_sync(self, device_ids: List[str]):
        """Test high-frequency synchronization performance."""
        test_name = "High-Frequency Synchronization"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Perform rapid synchronization cycles
            sync_cycles = 10
            cycle_results = []
            
            for cycle in range(sync_cycles):
                cycle_start = time.time()
                
                # Sync all devices in parallel
                tasks = []
                for device_id in device_ids:
                    task = asyncio.create_task(
                        self.time_synchronizer.sync_device(device_id, num_measurements=3)
                    )
                    tasks.append(task)
                
                results = await asyncio.gather(*tasks, return_exceptions=True)
                cycle_duration = time.time() - cycle_start
                
                successful_syncs = sum(1 for r in results if r is True)
                cycle_results.append({
                    'cycle': cycle,
                    'successful_syncs': successful_syncs,
                    'total_devices': len(device_ids),
                    'duration_seconds': cycle_duration
                })
                
                # Small delay between cycles
                await asyncio.sleep(0.5)
            
            # Analyze high-frequency performance
            avg_success_rate = statistics.mean([r['successful_syncs'] / r['total_devices'] for r in cycle_results])
            avg_cycle_duration = statistics.mean([r['duration_seconds'] for r in cycle_results])
            success = avg_success_rate >= 0.8 and avg_cycle_duration <= 5.0  # 5 second max per cycle
            
            quality_metrics = self._calculate_sync_quality_metrics(device_ids)
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=avg_success_rate,
                max_offset_ms=quality_metrics.get('max_offset_ms', 0.0),
                avg_uncertainty_ms=quality_metrics.get('avg_uncertainty_ms', 0.0),
                test_duration_seconds=time.time() - start_time,
                detailed_results={
                    'cycle_results': cycle_results,
                    'avg_success_rate': avg_success_rate,
                    'avg_cycle_duration': avg_cycle_duration
                }
            )
            
            if not success:
                result.error_message = f"High-frequency sync performance below threshold: success_rate={avg_success_rate:.2f}, avg_duration={avg_cycle_duration:.2f}s"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_sync_under_load(self, device_ids: List[str]):
        """Test synchronization quality under network load."""
        test_name = "Sync Under Load"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Create background load (multiple concurrent sync operations)
            load_tasks = []
            
            # Start background sync operations
            for _ in range(5):  # 5 background sync cycles
                for device_id in device_ids:
                    task = asyncio.create_task(
                        self._background_sync_load(device_id)
                    )
                    load_tasks.append(task)
            
            # Wait a bit for load to build up
            await asyncio.sleep(2.0)
            
            # Perform test synchronization under load
            test_sync_results = {}
            for device_id in device_ids:
                success = await self.time_synchronizer.sync_device(device_id, num_measurements=7)
                test_sync_results[device_id] = success
            
            # Cancel background load
            for task in load_tasks:
                task.cancel()
            
            await asyncio.gather(*load_tasks, return_exceptions=True)
            
            # Analyze results
            successful_devices = sum(1 for success in test_sync_results.values() if success)
            success = successful_devices >= len(device_ids) * 0.7  # 70% success under load
            
            quality_metrics = self._calculate_sync_quality_metrics(device_ids)
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=quality_metrics.get('avg_quality', 0.0),
                max_offset_ms=quality_metrics.get('max_offset_ms', 0.0),
                avg_uncertainty_ms=quality_metrics.get('avg_uncertainty_ms', 0.0),
                test_duration_seconds=time.time() - start_time,
                detailed_results=test_sync_results
            )
            
            if not success:
                result.error_message = f"Sync under load success rate below threshold: {successful_devices}/{len(device_ids)}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _test_long_duration_stability(self, device_ids: List[str]):
        """Test synchronization stability over extended period."""
        test_name = "Long-Duration Stability"
        start_time = time.time()
        
        try:
            logging.info(f"Running {test_name} test")
            
            # Start auto-sync for stability testing
            await self.time_synchronizer.start_auto_sync()
            
            # Monitor sync quality over time
            stability_measurements = []
            test_duration = 30.0  # 30 seconds for testing (would be longer in production)
            measurement_interval = 5.0  # 5 seconds between measurements
            
            end_time = start_time + test_duration
            while time.time() < end_time:
                # Take quality measurement
                quality_metrics = self._calculate_sync_quality_metrics(device_ids)
                stability_measurements.append({
                    'timestamp': time.time(),
                    'avg_quality': quality_metrics.get('avg_quality', 0.0),
                    'max_offset_ms': quality_metrics.get('max_offset_ms', 0.0),
                    'synchronized_devices': quality_metrics.get('synchronized_devices', 0)
                })
                
                await asyncio.sleep(measurement_interval)
            
            # Stop auto-sync
            await self.time_synchronizer.stop_auto_sync()
            
            # Analyze stability
            if stability_measurements:
                quality_values = [m['avg_quality'] for m in stability_measurements]
                offset_values = [m['max_offset_ms'] for m in stability_measurements]
                
                avg_quality = statistics.mean(quality_values)
                quality_stability = 1.0 - (statistics.stdev(quality_values) if len(quality_values) > 1 else 0.0)
                max_offset = max(offset_values) if offset_values else 0.0
                
                success = avg_quality >= 0.7 and quality_stability >= 0.8 and max_offset <= 100.0
            else:
                avg_quality = 0.0
                max_offset = 0.0
                success = False
            
            result = SyncTestResult(
                test_name=test_name,
                success=success,
                device_count=len(device_ids),
                sync_quality=avg_quality,
                max_offset_ms=max_offset,
                avg_uncertainty_ms=0.0,  # Would need to calculate from measurements
                test_duration_seconds=time.time() - start_time,
                detailed_results={
                    'stability_measurements': stability_measurements,
                    'quality_stability': quality_stability if stability_measurements else 0.0
                }
            )
            
            if not success:
                result.error_message = f"Long-duration stability below threshold: avg_quality={avg_quality:.2f}, stability={quality_stability:.2f}"
            
            self.test_results.append(result)
            logging.info(f"{test_name} test completed: {'PASS' if success else 'FAIL'}")
            
        except Exception as e:
            result = SyncTestResult(
                test_name=test_name,
                success=False,
                device_count=len(device_ids),
                sync_quality=0.0,
                max_offset_ms=0.0,
                avg_uncertainty_ms=0.0,
                test_duration_seconds=time.time() - start_time,
                error_message=str(e)
            )
            self.test_results.append(result)
            logging.error(f"{test_name} test failed: {e}")
    
    async def _background_sync_load(self, device_id: str):
        """Generate background synchronization load."""
        try:
            while True:
                await self.time_synchronizer.sync_device(device_id, num_measurements=2)
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logging.debug(f"Background sync load error for {device_id}: {e}")
    
    def _calculate_sync_quality_metrics(self, device_ids: List[str]) -> Dict:
        """Calculate synchronization quality metrics."""
        metrics = {
            'avg_quality': 0.0,
            'max_offset_ms': 0.0,
            'avg_uncertainty_ms': 0.0,
            'synchronized_devices': 0
        }
        
        try:
            qualities = []
            offsets = []
            uncertainties = []
            synchronized_count = 0
            
            for device_id in device_ids:
                status = self.time_synchronizer.get_device_status(device_id)
                if status:
                    if status.is_synchronized:
                        synchronized_count += 1
                        
                    quality = self.time_synchronizer.get_sync_quality(device_id)
                    if quality is not None:
                        qualities.append(quality)
                    
                    if status.offset is not None:
                        offsets.append(abs(status.offset) * 1000)  # Convert to ms
                    
                    if status.uncertainty is not None:
                        uncertainties.append(status.uncertainty * 1000)  # Convert to ms
            
            if qualities:
                metrics['avg_quality'] = statistics.mean(qualities)
            if offsets:
                metrics['max_offset_ms'] = max(offsets)
            if uncertainties:
                metrics['avg_uncertainty_ms'] = statistics.mean(uncertainties)
            
            metrics['synchronized_devices'] = synchronized_count
            
        except Exception as e:
            logging.error(f"Error calculating sync quality metrics: {e}")
        
        return metrics
    
    def _calculate_coordination_quality_metrics(self, device_ids: List[str], results: Dict[str, bool]) -> Dict:
        """Calculate coordination quality metrics."""
        metrics = {
            'coordination_quality': 0.0,
            'max_inter_device_offset_ms': 0.0,
            'avg_uncertainty_ms': 0.0
        }
        
        try:
            successful_devices = [did for did, success in results.items() if success]
            
            if len(successful_devices) >= 2:
                # Calculate inter-device offset range
                offsets = []
                uncertainties = []
                
                for device_id in successful_devices:
                    status = self.time_synchronizer.get_device_status(device_id)
                    if status and status.offset is not None:
                        offsets.append(status.offset * 1000)  # Convert to ms
                    if status and status.uncertainty is not None:
                        uncertainties.append(status.uncertainty * 1000)  # Convert to ms
                
                if len(offsets) >= 2:
                    metrics['max_inter_device_offset_ms'] = max(offsets) - min(offsets)
                
                if uncertainties:
                    metrics['avg_uncertainty_ms'] = statistics.mean(uncertainties)
                
                # Overall coordination quality
                success_rate = len(successful_devices) / len(device_ids)
                offset_quality = max(0.0, 1.0 - (metrics['max_inter_device_offset_ms'] / 100.0))  # 100ms reference
                metrics['coordination_quality'] = (success_rate + offset_quality) / 2
            
        except Exception as e:
            logging.error(f"Error calculating coordination quality metrics: {e}")
        
        return metrics
    
    async def _cleanup_test_environment(self):
        """Clean up test environment."""
        try:
            await self.time_synchronizer.cleanup()
            await self.sync_event_manager.stop_heartbeat()
            self.coordinator.cleanup()
            logging.info("Test environment cleaned up")
        except Exception as e:
            logging.error(f"Error during test cleanup: {e}")
    
    def generate_test_report(self, output_path: Optional[str] = None) -> str:
        """Generate comprehensive test report."""
        report = {
            'test_summary': {
                'total_tests': len(self.test_results),
                'passed_tests': sum(1 for r in self.test_results if r.success),
                'failed_tests': sum(1 for r in self.test_results if not r.success),
                'overall_success_rate': sum(1 for r in self.test_results if r.success) / len(self.test_results) if self.test_results else 0.0
            },
            'test_results': [
                {
                    'test_name': r.test_name,
                    'success': r.success,
                    'device_count': r.device_count,
                    'sync_quality': r.sync_quality,
                    'max_offset_ms': r.max_offset_ms,
                    'avg_uncertainty_ms': r.avg_uncertainty_ms,
                    'test_duration_seconds': r.test_duration_seconds,
                    'error_message': r.error_message,
                    'detailed_results': r.detailed_results
                }
                for r in self.test_results
            ],
            'recommendations': self._generate_test_recommendations()
        }
        
        import json
        report_json = json.dumps(report, indent=2, default=str)
        
        if output_path:
            with open(output_path, 'w') as f:
                f.write(report_json)
            logging.info(f"Test report saved to {output_path}")
        
        return report_json
    
    def _generate_test_recommendations(self) -> List[str]:
        """Generate recommendations based on test results."""
        recommendations = []
        
        failed_tests = [r for r in self.test_results if not r.success]
        if failed_tests:
            recommendations.append(f"Address {len(failed_tests)} failed tests before production deployment")
        
        # Analyze sync quality
        avg_sync_quality = statistics.mean([r.sync_quality for r in self.test_results if r.sync_quality > 0])
        if avg_sync_quality < 0.8:
            recommendations.append(f"Improve synchronization quality (current average: {avg_sync_quality:.2f})")
        
        # Analyze offset performance
        max_offsets = [r.max_offset_ms for r in self.test_results if r.max_offset_ms > 0]
        if max_offsets and max(max_offsets) > 50:
            recommendations.append(f"Reduce maximum synchronization offset (current max: {max(max_offsets):.1f}ms)")
        
        # Check for specific test failures
        for result in failed_tests:
            if "Network Failure Recovery" in result.test_name:
                recommendations.append("Improve network failure recovery mechanisms")
            elif "High-Frequency" in result.test_name:
                recommendations.append("Optimize high-frequency synchronization performance")
            elif "Long-Duration" in result.test_name:
                recommendations.append("Enhance long-term synchronization stability")
        
        if not recommendations:
            recommendations.append("All tests passed - system ready for production use")
        
        return recommendations


async def main():
    """Main test execution function."""
    setup_logging(level=logging.INFO)
    
    # Example usage
    device_ids = ["device_1", "device_2", "device_3"]  # Replace with actual device IDs
    
    tester = MultiDeviceSyncTester()
    
    try:
        # Run comprehensive tests
        results = await tester.run_comprehensive_tests(device_ids)
        
        # Generate and save report
        report_path = "multi_device_sync_test_report.json"
        report = tester.generate_test_report(report_path)
        
        print(f"Test completed. Report saved to {report_path}")
        print(f"Overall success rate: {sum(1 for r in results if r.success)}/{len(results)}")
        
    except Exception as e:
        logging.error(f"Test execution failed: {e}")


if __name__ == "__main__":
    asyncio.run(main())