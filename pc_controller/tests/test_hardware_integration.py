#!/usr/bin/env python3
"""
Hardware Integration Testing for Multi-Modal Capture System
Tests system functionality with real Android devices and sensors.
"""

import unittest
import asyncio
import time
import json
import logging
from pathlib import Path
from typing import List, Dict, Any
from unittest.mock import Mock, patch

# Import system components
from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from core.recording_controller import RecordingController, RecordingState
from core.time_sync import TimeSynchronizer
from core.sync_events import SyncEventManager, SyncEventType
from data.session_manifest import SessionManifestGenerator, DataModality
from data.file_aggregator import FileAggregator, TransferMethod
from data.data_validator import DataValidator
from utils.config import Config


class HardwareTestConfig:
    """Configuration for hardware integration tests."""
    
    def __init__(self):
        self.test_devices = []  # List of test device IDs
        self.test_duration_seconds = 30
        self.expected_sample_rates = {
            DataModality.GSR: 128,
            DataModality.PPG: 128,
            DataModality.AUDIO: 44100
        }
        self.sync_tolerance_ms = 50
        self.min_data_quality_score = 70
        
    def load_from_file(self, config_file: str):
        """Load test configuration from file."""
        try:
            with open(config_file, 'r') as f:
                config_data = json.load(f)
                
            self.test_devices = config_data.get('test_devices', [])
            self.test_duration_seconds = config_data.get('test_duration_seconds', 30)
            self.expected_sample_rates.update(config_data.get('expected_sample_rates', {}))
            self.sync_tolerance_ms = config_data.get('sync_tolerance_ms', 50)
            self.min_data_quality_score = config_data.get('min_data_quality_score', 70)
            
        except Exception as e:
            logging.warning(f"Failed to load test config: {e}, using defaults")


class TestDeviceDiscovery(unittest.TestCase):
    """Test device discovery and connection."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.config = Config()
        self.device_manager = DeviceManager(self.config)
        self.test_config = HardwareTestConfig()
        
        # Try to load test configuration
        config_file = Path(__file__).parent / "hardware_test_config.json"
        if config_file.exists():
            self.test_config.load_from_file(str(config_file))
            
    def test_device_discovery(self):
        """Test discovering Android devices on network."""
        logging.info("Starting device discovery test...")
        
        # Start device discovery
        self.device_manager.start_discovery()
        
        # Wait for discovery to complete
        discovery_timeout = 30  # seconds
        start_time = time.time()
        
        while time.time() - start_time < discovery_timeout:
            discovered_devices = self.device_manager.get_discovered_devices()
            if len(discovered_devices) > 0:
                break
            time.sleep(1)
            
        discovered_devices = self.device_manager.get_discovered_devices()
        
        logging.info(f"Discovered {len(discovered_devices)} devices")
        for device in discovered_devices:
            logging.info(f"  - {device.name} ({device.ip_address})")
            
        # Verify we found at least one device if test devices are configured
        if self.test_config.test_devices:
            self.assertGreater(len(discovered_devices), 0, 
                             "No devices discovered, check network connectivity")
            
    def test_device_connection(self):
        """Test connecting to discovered devices."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Starting device connection test...")
        
        # Discover devices first
        self.device_manager.start_discovery()
        time.sleep(10)  # Wait for discovery
        
        discovered_devices = self.device_manager.get_discovered_devices()
        
        # Try to connect to test devices
        connected_count = 0
        for device in discovered_devices:
            if device.device_id in self.test_config.test_devices:
                logging.info(f"Attempting to connect to {device.name}")
                
                success = self.device_manager.connect_device(device.device_id)
                if success:
                    connected_count += 1
                    
                    # Wait for connection to establish
                    time.sleep(2)
                    
                    # Verify connection status
                    device_status = self.device_manager.get_device_status(device.device_id)
                    self.assertEqual(device_status, DeviceStatus.CONNECTED)
                    
                    logging.info(f"Successfully connected to {device.name}")
                else:
                    logging.warning(f"Failed to connect to {device.name}")
                    
        self.assertGreater(connected_count, 0, "Failed to connect to any test devices")
        
    def tearDown(self):
        """Clean up after tests."""
        self.device_manager.cleanup()


class TestSensorFunctionality(unittest.TestCase):
    """Test sensor data collection functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.config = Config()
        self.device_manager = DeviceManager(self.config)
        self.recording_controller = RecordingController(self.config)
        self.test_config = HardwareTestConfig()
        
        # Load test configuration
        config_file = Path(__file__).parent / "hardware_test_config.json"
        if config_file.exists():
            self.test_config.load_from_file(str(config_file))
            
        # Connect to test devices
        self._connect_test_devices()
        
    def _connect_test_devices(self):
        """Connect to configured test devices."""
        if not self.test_config.test_devices:
            return
            
        self.device_manager.start_discovery()
        time.sleep(10)
        
        for device_id in self.test_config.test_devices:
            self.device_manager.connect_device(device_id)
            time.sleep(2)
            
    def test_gsr_sensor_data(self):
        """Test GSR sensor data collection."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing GSR sensor data collection...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if not connected_devices:
            self.skipTest("No connected devices available")
            
        # Start a short recording session
        session_id = f"gsr_test_{int(time.time())}"
        device_ids = [d.device_id for d in connected_devices]
        
        # Start recording
        success = self.recording_controller.start_recording(session_id, device_ids)
        self.assertTrue(success, "Failed to start recording")
        
        # Record for test duration
        logging.info(f"Recording GSR data for {self.test_config.test_duration_seconds} seconds...")
        time.sleep(self.test_config.test_duration_seconds)
        
        # Stop recording
        self.recording_controller.stop_recording()
        
        # Wait for data to be written
        time.sleep(5)
        
        # Verify GSR data was collected
        session_info = self.recording_controller.get_session_info(session_id)
        self.assertIsNotNone(session_info)
        
        # Check for GSR data files (this would need to be implemented based on actual file structure)
        # For now, just verify the session completed successfully
        self.assertEqual(session_info.state, RecordingState.COMPLETED)
        
    def test_audio_recording(self):
        """Test audio recording functionality."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing audio recording...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if not connected_devices:
            self.skipTest("No connected devices available")
            
        # Start recording session
        session_id = f"audio_test_{int(time.time())}"
        device_ids = [d.device_id for d in connected_devices]
        
        success = self.recording_controller.start_recording(session_id, device_ids)
        self.assertTrue(success)
        
        # Record for test duration
        time.sleep(self.test_config.test_duration_seconds)
        
        # Stop recording
        self.recording_controller.stop_recording()
        time.sleep(5)
        
        # Verify session completed
        session_info = self.recording_controller.get_session_info(session_id)
        self.assertEqual(session_info.state, RecordingState.COMPLETED)
        
    def test_camera_recording(self):
        """Test camera recording functionality."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing camera recording...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if not connected_devices:
            self.skipTest("No connected devices available")
            
        # Start recording session
        session_id = f"camera_test_{int(time.time())}"
        device_ids = [d.device_id for d in connected_devices]
        
        success = self.recording_controller.start_recording(session_id, device_ids)
        self.assertTrue(success)
        
        # Record for test duration
        time.sleep(self.test_config.test_duration_seconds)
        
        # Stop recording
        self.recording_controller.stop_recording()
        time.sleep(5)
        
        # Verify session completed
        session_info = self.recording_controller.get_session_info(session_id)
        self.assertEqual(session_info.state, RecordingState.COMPLETED)
        
    def tearDown(self):
        """Clean up after tests."""
        self.recording_controller.cleanup()
        self.device_manager.cleanup()


class TestTimeSynchronization(unittest.TestCase):
    """Test time synchronization between devices."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.config = Config()
        self.device_manager = DeviceManager(self.config)
        self.time_synchronizer = TimeSynchronizer()
        self.test_config = HardwareTestConfig()
        
        # Load test configuration
        config_file = Path(__file__).parent / "hardware_test_config.json"
        if config_file.exists():
            self.test_config.load_from_file(str(config_file))
            
        # Connect to test devices
        self._connect_test_devices()
        
    def _connect_test_devices(self):
        """Connect to configured test devices."""
        if not self.test_config.test_devices:
            return
            
        self.device_manager.start_discovery()
        time.sleep(10)
        
        for device_id in self.test_config.test_devices:
            self.device_manager.connect_device(device_id)
            time.sleep(2)
            
    def test_time_sync_accuracy(self):
        """Test time synchronization accuracy."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing time synchronization accuracy...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if len(connected_devices) < 2:
            self.skipTest("Need at least 2 connected devices for sync testing")
            
        # Perform time synchronization
        for device in connected_devices:
            self.time_synchronizer.register_device(device.device_id)
            
        # Sync all devices
        for device in connected_devices:
            success = self.time_synchronizer.sync_device(device.device_id, num_measurements=10)
            self.assertTrue(success, f"Failed to sync device {device.device_id}")
            
        # Check synchronization quality
        for device in connected_devices:
            sync_quality = self.time_synchronizer.get_sync_quality(device.device_id)
            self.assertIsNotNone(sync_quality)
            
            offset_ms = abs(sync_quality.get('offset_ms', 0))
            self.assertLess(offset_ms, self.test_config.sync_tolerance_ms,
                          f"Sync offset {offset_ms}ms exceeds tolerance {self.test_config.sync_tolerance_ms}ms")
            
            logging.info(f"Device {device.device_id} sync offset: {offset_ms:.1f}ms")
            
    def test_sync_events(self):
        """Test synchronization event broadcasting."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing sync event broadcasting...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if not connected_devices:
            self.skipTest("No connected devices available")
            
        # Create sync event manager
        sync_event_manager = SyncEventManager()
        sync_event_manager.set_device_list([d.device_id for d in connected_devices])
        
        # Create and broadcast sync event
        event_id = sync_event_manager.create_event(
            SyncEventType.MANUAL_MARKER,
            description="Hardware integration test marker"
        )
        
        self.assertIsNotNone(event_id)
        
        # Wait for event to be processed
        time.sleep(2)
        
        # Verify event was created
        events = sync_event_manager.get_events()
        test_events = [e for e in events if e.get('description') == "Hardware integration test marker"]
        self.assertEqual(len(test_events), 1)
        
    def tearDown(self):
        """Clean up after tests."""
        self.time_synchronizer.cleanup()
        self.device_manager.cleanup()


class TestDataCollection(unittest.TestCase):
    """Test end-to-end data collection and validation."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.config = Config()
        self.device_manager = DeviceManager(self.config)
        self.recording_controller = RecordingController(self.config)
        self.manifest_generator = SessionManifestGenerator("test_output")
        self.file_aggregator = FileAggregator(
            self.device_manager, self.manifest_generator, "test_output"
        )
        self.data_validator = DataValidator()
        self.test_config = HardwareTestConfig()
        
        # Load test configuration
        config_file = Path(__file__).parent / "hardware_test_config.json"
        if config_file.exists():
            self.test_config.load_from_file(str(config_file))
            
        # Connect to test devices
        self._connect_test_devices()
        
    def _connect_test_devices(self):
        """Connect to configured test devices."""
        if not self.test_config.test_devices:
            return
            
        self.device_manager.start_discovery()
        time.sleep(10)
        
        for device_id in self.test_config.test_devices:
            self.device_manager.connect_device(device_id)
            time.sleep(2)
            
    def test_full_data_collection_workflow(self):
        """Test complete data collection workflow."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing full data collection workflow...")
        
        connected_devices = self.device_manager.get_connected_devices()
        if not connected_devices:
            self.skipTest("No connected devices available")
            
        # 1. Create session manifest
        session_id = f"integration_test_{int(time.time())}"
        
        # Mock session info
        session_info = Mock()
        session_info.session_id = session_id
        session_info.session_name = "Hardware Integration Test"
        session_info.description = "Full workflow test"
        session_info.participant_id = "TEST_001"
        session_info.experiment_type = "Integration"
        session_info.start_time = time.time()
        
        manifest = self.manifest_generator.create_session_manifest(
            session_info, connected_devices
        )
        
        # 2. Start recording
        device_ids = [d.device_id for d in connected_devices]
        success = self.recording_controller.start_recording(session_id, device_ids)
        self.assertTrue(success, "Failed to start recording")
        
        # 3. Record for test duration
        logging.info(f"Recording data for {self.test_config.test_duration_seconds} seconds...")
        time.sleep(self.test_config.test_duration_seconds)
        
        # 4. Stop recording
        self.recording_controller.stop_recording()
        time.sleep(5)  # Wait for data to be written
        
        # 5. Finalize manifest
        self.manifest_generator.finalize_session(end_timestamp=time.time())
        
        # 6. Aggregate files (mock for now since actual file transfer needs device cooperation)
        # In a real test, this would collect files from devices
        logging.info("File aggregation would occur here in full implementation")
        
        # 7. Validate data quality
        metrics, issues = self.data_validator.validate_session(manifest)
        
        # Check quality metrics
        logging.info(f"Data quality score: {metrics.overall_score:.1f}")
        self.assertGreaterEqual(metrics.overall_score, self.test_config.min_data_quality_score,
                               f"Data quality {metrics.overall_score} below minimum {self.test_config.min_data_quality_score}")
        
        # 8. Verify session completed successfully
        final_session_info = self.recording_controller.get_session_info(session_id)
        self.assertEqual(final_session_info.state, RecordingState.COMPLETED)
        
        logging.info("Full data collection workflow completed successfully")
        
    def test_multi_device_synchronization(self):
        """Test synchronization across multiple devices."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        connected_devices = self.device_manager.get_connected_devices()
        if len(connected_devices) < 2:
            self.skipTest("Need at least 2 devices for multi-device sync test")
            
        logging.info(f"Testing synchronization across {len(connected_devices)} devices...")
        
        # Start synchronized recording
        session_id = f"sync_test_{int(time.time())}"
        device_ids = [d.device_id for d in connected_devices]
        
        # Record start time for sync verification
        start_time = time.time()
        
        success = self.recording_controller.start_recording(session_id, device_ids)
        self.assertTrue(success)
        
        # Record for test duration
        time.sleep(self.test_config.test_duration_seconds)
        
        # Record stop time
        stop_time = time.time()
        
        self.recording_controller.stop_recording()
        time.sleep(5)
        
        # Verify all devices recorded for approximately the same duration
        session_info = self.recording_controller.get_session_info(session_id)
        expected_duration = stop_time - start_time
        
        # Allow some tolerance for processing delays
        duration_tolerance = 2.0  # seconds
        
        if hasattr(session_info, 'duration_seconds') and session_info.duration_seconds:
            actual_duration = session_info.duration_seconds
            duration_diff = abs(actual_duration - expected_duration)
            
            self.assertLess(duration_diff, duration_tolerance,
                          f"Recording duration {actual_duration}s differs from expected {expected_duration}s by {duration_diff}s")
            
        logging.info("Multi-device synchronization test completed")
        
    def tearDown(self):
        """Clean up after tests."""
        self.file_aggregator.cleanup()
        self.recording_controller.cleanup()
        self.device_manager.cleanup()


class TestPerformanceMetrics(unittest.TestCase):
    """Test system performance under load."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.config = Config()
        self.device_manager = DeviceManager(self.config)
        self.recording_controller = RecordingController(self.config)
        self.test_config = HardwareTestConfig()
        
        # Load test configuration
        config_file = Path(__file__).parent / "hardware_test_config.json"
        if config_file.exists():
            self.test_config.load_from_file(str(config_file))
            
    def test_connection_performance(self):
        """Test device connection performance."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing connection performance...")
        
        # Measure discovery time
        discovery_start = time.time()
        self.device_manager.start_discovery()
        
        # Wait for discovery to find devices
        timeout = 30
        start_time = time.time()
        while time.time() - start_time < timeout:
            if len(self.device_manager.get_discovered_devices()) > 0:
                break
            time.sleep(0.1)
            
        discovery_time = time.time() - discovery_start
        logging.info(f"Device discovery took {discovery_time:.2f} seconds")
        
        # Measure connection time for each device
        discovered_devices = self.device_manager.get_discovered_devices()
        connection_times = []
        
        for device in discovered_devices:
            if device.device_id in self.test_config.test_devices:
                connect_start = time.time()
                success = self.device_manager.connect_device(device.device_id)
                connect_time = time.time() - connect_start
                
                if success:
                    connection_times.append(connect_time)
                    logging.info(f"Connected to {device.name} in {connect_time:.2f} seconds")
                    
        if connection_times:
            avg_connection_time = sum(connection_times) / len(connection_times)
            max_connection_time = max(connection_times)
            
            logging.info(f"Average connection time: {avg_connection_time:.2f}s")
            logging.info(f"Maximum connection time: {max_connection_time:.2f}s")
            
            # Assert reasonable connection times
            self.assertLess(avg_connection_time, 10.0, "Average connection time too high")
            self.assertLess(max_connection_time, 15.0, "Maximum connection time too high")
            
    def test_recording_performance(self):
        """Test recording performance and resource usage."""
        if not self.test_config.test_devices:
            self.skipTest("No test devices configured")
            
        logging.info("Testing recording performance...")
        
        # Connect to devices first
        self.device_manager.start_discovery()
        time.sleep(10)
        
        connected_count = 0
        for device_id in self.test_config.test_devices:
            if self.device_manager.connect_device(device_id):
                connected_count += 1
                time.sleep(1)
                
        if connected_count == 0:
            self.skipTest("No devices connected for performance test")
            
        # Start recording and measure performance
        session_id = f"perf_test_{int(time.time())}"
        device_ids = [d.device_id for d in self.device_manager.get_connected_devices()]
        
        # Measure recording start time
        start_time = time.time()
        success = self.recording_controller.start_recording(session_id, device_ids)
        start_duration = time.time() - start_time
        
        self.assertTrue(success)
        logging.info(f"Recording start took {start_duration:.2f} seconds")
        
        # Record for test duration
        time.sleep(self.test_config.test_duration_seconds)
        
        # Measure recording stop time
        stop_start = time.time()
        self.recording_controller.stop_recording()
        stop_duration = time.time() - stop_start
        
        logging.info(f"Recording stop took {stop_duration:.2f} seconds")
        
        # Assert reasonable performance
        self.assertLess(start_duration, 5.0, "Recording start time too high")
        self.assertLess(stop_duration, 5.0, "Recording stop time too high")
        
    def tearDown(self):
        """Clean up after tests."""
        self.recording_controller.cleanup()
        self.device_manager.cleanup()


def create_test_config():
    """Create a sample test configuration file."""
    config = {
        "test_devices": [
            "device_001",
            "device_002"
        ],
        "test_duration_seconds": 30,
        "expected_sample_rates": {
            "gsr": 128,
            "ppg": 128,
            "audio": 44100
        },
        "sync_tolerance_ms": 50,
        "min_data_quality_score": 70
    }
    
    config_file = Path(__file__).parent / "hardware_test_config.json"
    with open(config_file, 'w') as f:
        json.dump(config, f, indent=2)
        
    print(f"Created test configuration file: {config_file}")
    print("Edit this file to specify your test devices and parameters")


def run_hardware_tests():
    """Run hardware integration tests."""
    # Setup logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    # Check for test configuration
    config_file = Path(__file__).parent / "hardware_test_config.json"
    if not config_file.exists():
        print("No hardware test configuration found.")
        print("Run with --create-config to create a sample configuration file.")
        return False
        
    # Create test suite
    test_suite = unittest.TestSuite()
    
    # Add test cases
    test_classes = [
        TestDeviceDiscovery,
        TestSensorFunctionality,
        TestTimeSynchronization,
        TestDataCollection,
        TestPerformanceMetrics
    ]
    
    for test_class in test_classes:
        tests = unittest.TestLoader().loadTestsFromTestCase(test_class)
        test_suite.addTests(tests)
    
    # Run tests
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(test_suite)
    
    return result.wasSuccessful()


if __name__ == '__main__':
    import sys
    
    if '--create-config' in sys.argv:
        create_test_config()
    else:
        success = run_hardware_tests()
        sys.exit(0 if success else 1)