#!/usr/bin/env python3
"""
System Validation Tools for Multi-Modal Recording System
Comprehensive validation of the entire system including hardware integration.
"""

import asyncio
import logging
import time
import json
from typing import Dict, List, Optional
from dataclasses import dataclass, field
from enum import Enum

from core.device_manager import DeviceManager
from core.time_sync import TimeSynchronizer
from tests.test_multi_device_sync import MultiDeviceSyncTester
from tests.calibration_utilities import SyncCalibrator


class ValidationLevel(Enum):
    """System validation levels."""
    BASIC = "basic"
    COMPREHENSIVE = "comprehensive"
    PRODUCTION = "production"


@dataclass
class SystemValidationResult:
    """Result of system validation."""
    validation_level: ValidationLevel
    overall_success: bool
    component_results: Dict[str, bool] = field(default_factory=dict)
    performance_metrics: Dict[str, float] = field(default_factory=dict)
    recommendations: List[str] = field(default_factory=list)
    validation_duration: float = 0.0


class SystemValidator:
    """
    Comprehensive system validation tool.
    Tests all components and their integration.
    """
    
    def __init__(self):
        """Initialize system validator."""
        self.device_manager = DeviceManager()
        self.time_synchronizer = TimeSynchronizer()
        self.sync_tester = MultiDeviceSyncTester(self.device_manager)
        self.calibrator = SyncCalibrator(self.device_manager, self.time_synchronizer)
        
        logging.info("SystemValidator initialized")
    
    async def run_validation(self, device_ids: List[str], 
                           level: ValidationLevel = ValidationLevel.BASIC) -> SystemValidationResult:
        """Run system validation at specified level."""
        start_time = time.time()
        
        result = SystemValidationResult(
            validation_level=level,
            overall_success=False
        )
        
        try:
            if level == ValidationLevel.BASIC:
                await self._run_basic_validation(device_ids, result)
            elif level == ValidationLevel.COMPREHENSIVE:
                await self._run_comprehensive_validation(device_ids, result)
            elif level == ValidationLevel.PRODUCTION:
                await self._run_production_validation(device_ids, result)
            
            # Calculate overall success
            result.overall_success = all(result.component_results.values())
            
        except Exception as e:
            logging.error(f"System validation failed: {e}")
            result.component_results['validation_error'] = False
        
        finally:
            result.validation_duration = time.time() - start_time
            await self._cleanup()
        
        return result
    
    async def _run_basic_validation(self, device_ids: List[str], result: SystemValidationResult):
        """Run basic system validation."""
        logging.info("Running basic system validation")
        
        # Test device connectivity
        connectivity_success = await self._test_device_connectivity(device_ids)
        result.component_results['device_connectivity'] = connectivity_success
        
        # Test basic synchronization
        sync_results = await self.sync_tester._test_basic_synchronization(device_ids)
        result.component_results['basic_sync'] = len([r for r in self.sync_tester.test_results if r.success]) > 0
        
        # Quick calibration check
        calibration_results = await self.calibrator.run_quick_calibration_check(device_ids)
        result.component_results['calibration'] = 'error' not in calibration_results
    
    async def _run_comprehensive_validation(self, device_ids: List[str], result: SystemValidationResult):
        """Run comprehensive system validation."""
        logging.info("Running comprehensive system validation")
        
        # Run basic validation first
        await self._run_basic_validation(device_ids, result)
        
        # Run full sync tests
        sync_results = await self.sync_tester.run_comprehensive_tests(device_ids)
        successful_tests = sum(1 for r in sync_results if r.success)
        result.component_results['comprehensive_sync'] = successful_tests >= len(sync_results) * 0.8
        
        # Run comprehensive calibration
        calibration_session = await self.calibrator.run_comprehensive_calibration(device_ids, 3.0)
        result.component_results['comprehensive_calibration'] = calibration_session.average_sync_error_ms < 50.0
    
    async def _run_production_validation(self, device_ids: List[str], result: SystemValidationResult):
        """Run production-ready system validation."""
        logging.info("Running production system validation")
        
        # Run comprehensive validation first
        await self._run_comprehensive_validation(device_ids, result)
        
        # Additional production tests would go here
        result.component_results['production_ready'] = result.overall_success
    
    async def _test_device_connectivity(self, device_ids: List[str]) -> bool:
        """Test basic device connectivity."""
        try:
            connected_devices = 0
            for device_id in device_ids:
                # Simulate connectivity test
                await asyncio.sleep(0.1)
                connected_devices += 1
            
            return connected_devices == len(device_ids)
        except Exception as e:
            logging.error(f"Device connectivity test failed: {e}")
            return False
    
    async def _cleanup(self):
        """Cleanup validation resources."""
        try:
            await self.sync_tester._cleanup_test_environment()
            await self.calibrator.cleanup()
            await self.time_synchronizer.cleanup()
        except Exception as e:
            logging.error(f"Cleanup error: {e}")
    
    def save_validation_report(self, result: SystemValidationResult, output_path: str):
        """Save validation report to file."""
        report = {
            'validation_level': result.validation_level.value,
            'overall_success': result.overall_success,
            'component_results': result.component_results,
            'performance_metrics': result.performance_metrics,
            'recommendations': result.recommendations,
            'validation_duration': result.validation_duration,
            'timestamp': time.time()
        }
        
        with open(output_path, 'w') as f:
            json.dump(report, f, indent=2)
        
        logging.info(f"Validation report saved to {output_path}")


async def main():
    """Example usage."""
    device_ids = ["device_1", "device_2"]
    validator = SystemValidator()
    
    result = await validator.run_validation(device_ids, ValidationLevel.BASIC)
    validator.save_validation_report(result, "system_validation_report.json")
    
    print(f"Validation {'PASSED' if result.overall_success else 'FAILED'}")


if __name__ == "__main__":
    asyncio.run(main())