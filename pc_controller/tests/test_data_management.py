#!/usr/bin/env python3
"""
Comprehensive Test Suite for Data Management Components
Tests session manifest generation, file aggregation, data validation, and export utilities.
"""

import unittest
import tempfile
import json
import time
from pathlib import Path
from unittest.mock import Mock, patch, MagicMock

# Import components to test
from data.session_manifest import (
    SessionManifestGenerator, SessionManifest, FileMetadata, 
    DeviceConfiguration, DataModality
)
from data.file_aggregator import (
    FileAggregator, FileTransferTask, TransferStatus, TransferMethod
)
from data.data_validator import (
    DataValidator, ValidationSeverity, ValidationIssue, QualityMetrics
)
from data.data_exporter import (
    DataExporter, ExportFormat, ExportConfiguration, ExportResult
)
from core.device_manager import AndroidDevice, DeviceStatus


class TestSessionManifestGenerator(unittest.TestCase):
    """Test cases for SessionManifestGenerator."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.generator = SessionManifestGenerator(self.temp_dir)
        
        # Create mock session info
        self.mock_session_info = Mock()
        self.mock_session_info.session_id = "test_session_001"
        self.mock_session_info.session_name = "Test Session"
        self.mock_session_info.description = "Test description"
        self.mock_session_info.participant_id = "P001"
        self.mock_session_info.experiment_type = "Test"
        self.mock_session_info.start_time = time.time()
        
        # Create mock devices
        self.mock_devices = [
            AndroidDevice("device_001", "Test Device 1", "192.168.1.100"),
            AndroidDevice("device_002", "Test Device 2", "192.168.1.101")
        ]
        
    def test_create_session_manifest(self):
        """Test creating a new session manifest."""
        manifest = self.generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        self.assertIsInstance(manifest, SessionManifest)
        self.assertEqual(manifest.session_id, "test_session_001")
        self.assertEqual(manifest.session_name, "Test Session")
        self.assertEqual(len(manifest.devices), 2)
        self.assertEqual(manifest.devices[0].device_id, "device_001")
        
    def test_add_file_metadata(self):
        """Test adding file metadata to manifest."""
        # Create manifest first
        manifest = self.generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        # Create a temporary test file
        test_file = Path(self.temp_dir) / "test_file.csv"
        test_file.write_text("timestamp,value\n1.0,10.5\n2.0,11.2\n")
        
        # Add file metadata
        file_meta = self.generator.add_file_metadata(
            file_path=str(test_file),
            modality=DataModality.GSR,
            device_id="device_001",
            start_timestamp=1.0,
            end_timestamp=2.0,
            sample_rate=1.0
        )
        
        self.assertIsInstance(file_meta, FileMetadata)
        self.assertEqual(file_meta.modality, DataModality.GSR)
        self.assertEqual(file_meta.device_id, "device_001")
        self.assertEqual(file_meta.duration_seconds, 1.0)
        self.assertGreater(file_meta.file_size, 0)
        
    def test_save_and_load_manifest(self):
        """Test saving and loading manifest files."""
        # Create and populate manifest
        manifest = self.generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        # Finalize and save
        self.generator.finalize_session()
        saved_path = self.generator.save_manifest()
        
        self.assertTrue(Path(saved_path).exists())
        
        # Load and verify
        loaded_manifest = self.generator.load_manifest(saved_path)
        self.assertEqual(loaded_manifest.session_id, manifest.session_id)
        self.assertEqual(loaded_manifest.session_name, manifest.session_name)
        
    def test_generate_summary_report(self):
        """Test generating summary report."""
        # Create manifest with some data
        manifest = self.generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        # Add some mock file metadata
        test_file = Path(self.temp_dir) / "test_data.csv"
        test_file.write_text("test data")
        
        self.generator.add_file_metadata(
            file_path=str(test_file),
            modality=DataModality.GSR,
            device_id="device_001",
            start_timestamp=1.0,
            end_timestamp=10.0
        )
        
        self.generator.finalize_session(end_timestamp=time.time())
        
        # Generate report
        report = self.generator.generate_summary_report()
        
        self.assertIn('session_id', report)
        self.assertIn('quality_score', report)
        self.assertIn('total_files', report)
        self.assertEqual(report['total_files'], 1)


class TestFileAggregator(unittest.TestCase):
    """Test cases for FileAggregator."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        
        # Create mock device manager
        self.mock_device_manager = Mock()
        self.mock_device = AndroidDevice("device_001", "Test Device", "192.168.1.100")
        self.mock_device.status = DeviceStatus.CONNECTED
        self.mock_device_manager.get_device.return_value = self.mock_device
        
        # Create mock manifest generator
        self.mock_manifest_generator = Mock()
        
        self.aggregator = FileAggregator(
            self.mock_device_manager,
            self.mock_manifest_generator,
            self.temp_dir
        )
        
    def test_file_transfer_task_creation(self):
        """Test creating file transfer tasks."""
        task = FileTransferTask(
            task_id="test_task_001",
            device_id="device_001",
            remote_path="/remote/path/file.csv",
            local_path=str(Path(self.temp_dir) / "file.csv"),
            file_size=1024,
            modality=DataModality.GSR,
            transfer_method=TransferMethod.WIFI_HTTP
        )
        
        self.assertEqual(task.task_id, "test_task_001")
        self.assertEqual(task.status, TransferStatus.PENDING)
        self.assertEqual(task.progress, 0.0)
        
    def test_generate_local_filename(self):
        """Test local filename generation."""
        filename = self.aggregator.generate_local_filename(
            "device_001", "original_file.csv", "gsr"
        )
        
        # Should follow format: {session_id}_{device_id}_{modality}.csv
        self.assertIn("device_001", filename)
        self.assertIn("gsr", filename)
        self.assertTrue(filename.endswith(".csv"))
        
    @patch.object(FileAggregator, 'get_device_file_list')
    def test_discover_device_files(self, mock_get_files):
        """Test discovering files on devices."""
        # Mock file list from device
        mock_get_files.return_value = [
            {
                'filename': 'test_session_gsr.csv',
                'path': '/storage/recordings/test_session_gsr.csv',
                'size': 1024,
                'modality': 'gsr',
                'checksum': 'abc123'
            }
        ]
        
        self.aggregator.current_session_id = "test_session"
        tasks = self.aggregator.discover_device_files("device_001", TransferMethod.WIFI_HTTP)
        
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0].device_id, "device_001")
        self.assertEqual(tasks[0].modality, DataModality.GSR)
        
    def test_aggregation_statistics(self):
        """Test aggregation statistics tracking."""
        stats = self.aggregator.get_aggregation_statistics()
        
        self.assertIn('total_files', stats)
        self.assertIn('completed_files', stats)
        self.assertIn('failed_files', stats)
        self.assertIn('total_bytes', stats)


class TestDataValidator(unittest.TestCase):
    """Test cases for DataValidator."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.validator = DataValidator()
        self.temp_dir = tempfile.mkdtemp()
        
        # Create mock manifest
        self.mock_manifest = SessionManifest(
            session_id="test_session",
            session_name="Test Session",
            description="Test description",
            participant_id="P001",
            experiment_type="Test",
            created_timestamp=time.time(),
            start_timestamp=time.time(),
            end_timestamp=time.time() + 60,
            duration_seconds=60.0
        )
        
    def test_validation_issue_creation(self):
        """Test creating validation issues."""
        issue = ValidationIssue(
            issue_type="test_issue",
            severity=ValidationSeverity.WARNING,
            message="Test validation issue",
            device_id="device_001"
        )
        
        self.assertEqual(issue.issue_type, "test_issue")
        self.assertEqual(issue.severity, ValidationSeverity.WARNING)
        self.assertIsNotNone(issue.timestamp)
        
    def test_validate_session_metadata(self):
        """Test session metadata validation."""
        # Test valid manifest
        metrics, issues = self.validator.validate_session(self.mock_manifest)
        
        # Should have no critical issues for basic metadata
        critical_issues = [i for i in issues if i.severity == ValidationSeverity.ERROR]
        self.assertEqual(len(critical_issues), 0)
        
        # Test invalid manifest (missing session ID)
        invalid_manifest = SessionManifest(
            session_id="",  # Empty session ID
            session_name="Test",
            description="Test",
            participant_id="P001",
            experiment_type="Test",
            created_timestamp=time.time(),
            start_timestamp=time.time()
        )
        
        metrics, issues = self.validator.validate_session(invalid_manifest)
        error_issues = [i for i in issues if i.severity == ValidationSeverity.ERROR]
        self.assertGreater(len(error_issues), 0)
        
    def test_file_validation(self):
        """Test file validation."""
        # Create a test file
        test_file = Path(self.temp_dir) / "test_file.csv"
        test_content = "timestamp,value\n1.0,10.5\n2.0,11.2\n"
        test_file.write_text(test_content)
        
        # Create file metadata
        file_meta = FileMetadata(
            file_path=str(test_file),
            file_name="test_file.csv",
            file_size=len(test_content),
            modality=DataModality.GSR,
            device_id="device_001",
            start_timestamp=1.0,
            end_timestamp=2.0,
            duration_seconds=1.0
        )
        
        self.mock_manifest.files = [file_meta]
        
        # Validate
        metrics, issues = self.validator.validate_session(self.mock_manifest)
        
        # Should find the file and validate it
        self.assertEqual(metrics.file_count, 1)
        self.assertEqual(metrics.missing_files, 0)
        
    def test_quality_score_calculation(self):
        """Test quality score calculation."""
        # Create manifest with good quality metrics
        self.mock_manifest.sync_statistics = {'average_offset_ms': 5.0}
        
        metrics, issues = self.validator.validate_session(self.mock_manifest)
        
        # Should have high synchronization score for low offset
        self.assertGreaterEqual(metrics.synchronization_score, 90)
        
    def test_generate_quality_report(self):
        """Test quality report generation."""
        report_json = self.validator.generate_quality_report(self.mock_manifest)
        report = json.loads(report_json)
        
        self.assertIn('session_info', report)
        self.assertIn('quality_metrics', report)
        self.assertIn('validation_issues', report)
        self.assertIn('recommendations', report)


class TestDataExporter(unittest.TestCase):
    """Test cases for DataExporter."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.exporter = DataExporter()
        self.temp_dir = tempfile.mkdtemp()
        
        # Create mock manifest with test data
        self.mock_manifest = SessionManifest(
            session_id="test_session",
            session_name="Test Session",
            description="Test description",
            participant_id="P001",
            experiment_type="Test",
            created_timestamp=time.time(),
            start_timestamp=time.time(),
            end_timestamp=time.time() + 60,
            duration_seconds=60.0
        )
        
        # Add mock device
        device_config = DeviceConfiguration(
            device_id="device_001",
            device_name="Test Device",
            device_model="Test Model",
            os_version="Android 10",
            app_version="1.0.0",
            ip_address="192.168.1.100",
            connection_type="WiFi"
        )
        self.mock_manifest.devices = [device_config]
        
    def test_export_configuration_creation(self):
        """Test creating export configurations."""
        config = self.exporter.create_export_config(
            ExportFormat.JSON,
            self.temp_dir,
            include_raw_files=True,
            compress_output=False
        )
        
        self.assertEqual(config.format, ExportFormat.JSON)
        self.assertEqual(config.output_directory, self.temp_dir)
        self.assertTrue(config.include_raw_files)
        self.assertFalse(config.compress_output)
        
    def test_supported_formats(self):
        """Test getting supported export formats."""
        formats = self.exporter.get_supported_formats()
        
        self.assertIn(ExportFormat.JSON, formats)
        self.assertIn(ExportFormat.CSV, formats)
        self.assertIn(ExportFormat.MATLAB, formats)
        self.assertIn(ExportFormat.HDF5, formats)
        
    def test_json_export(self):
        """Test JSON export functionality."""
        config = ExportConfiguration(
            format=ExportFormat.JSON,
            output_directory=self.temp_dir,
            include_raw_files=False  # Skip raw files for test
        )
        
        result = self.exporter.export_session(self.mock_manifest, config)
        
        self.assertTrue(result.success)
        self.assertEqual(len(result.output_files), 1)
        
        # Verify output file exists and contains valid JSON
        output_file = Path(result.output_files[0])
        self.assertTrue(output_file.exists())
        
        with open(output_file, 'r') as f:
            exported_data = json.load(f)
            
        self.assertIn('session_info', exported_data)
        self.assertIn('devices', exported_data)
        self.assertEqual(exported_data['session_info']['session_id'], "test_session")
        
    def test_csv_export(self):
        """Test CSV export functionality."""
        config = ExportConfiguration(
            format=ExportFormat.CSV,
            output_directory=self.temp_dir
        )
        
        result = self.exporter.export_session(self.mock_manifest, config)
        
        self.assertTrue(result.success)
        self.assertGreaterEqual(len(result.output_files), 2)  # At least metadata and files CSV
        
        # Check that metadata CSV was created
        metadata_files = [f for f in result.output_files if 'metadata.csv' in f]
        self.assertEqual(len(metadata_files), 1)
        
    def test_export_validation(self):
        """Test export configuration validation."""
        # Test invalid configuration (empty output directory)
        invalid_config = ExportConfiguration(
            format=ExportFormat.JSON,
            output_directory=""
        )
        
        result = self.exporter.export_session(self.mock_manifest, invalid_config)
        self.assertFalse(result.success)
        self.assertIn("Invalid", result.error_message)
        
    @patch('data.data_exporter.sio')
    @patch('data.data_exporter.np')
    def test_matlab_export_mock(self, mock_np, mock_sio):
        """Test MATLAB export with mocked dependencies."""
        config = ExportConfiguration(
            format=ExportFormat.MATLAB,
            output_directory=self.temp_dir
        )
        
        # Mock successful export
        mock_sio.savemat = Mock()
        
        result = self.exporter._export_matlab(self.mock_manifest, [], config)
        
        self.assertTrue(result.success)
        mock_sio.savemat.assert_called_once()


class TestIntegration(unittest.TestCase):
    """Integration tests for data management components."""
    
    def setUp(self):
        """Set up integration test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        
        # Create components
        self.manifest_generator = SessionManifestGenerator(self.temp_dir)
        self.validator = DataValidator()
        self.exporter = DataExporter()
        
        # Create mock session info
        self.mock_session_info = Mock()
        self.mock_session_info.session_id = "integration_test_001"
        self.mock_session_info.session_name = "Integration Test"
        self.mock_session_info.description = "Integration test session"
        self.mock_session_info.participant_id = "P001"
        self.mock_session_info.experiment_type = "Integration"
        self.mock_session_info.start_time = time.time()
        
        # Create mock devices
        self.mock_devices = [
            AndroidDevice("device_001", "Test Device 1", "192.168.1.100")
        ]
        
    def test_full_workflow(self):
        """Test complete data management workflow."""
        # 1. Create session manifest
        manifest = self.manifest_generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        # 2. Add some test files
        test_file = Path(self.temp_dir) / "test_gsr_data.csv"
        test_content = "timestamp,value\n1.0,10.5\n2.0,11.2\n3.0,12.1\n"
        test_file.write_text(test_content)
        
        file_meta = self.manifest_generator.add_file_metadata(
            file_path=str(test_file),
            modality=DataModality.GSR,
            device_id="device_001",
            start_timestamp=1.0,
            end_timestamp=3.0,
            sample_rate=1.0
        )
        
        # 3. Finalize manifest
        self.manifest_generator.finalize_session(end_timestamp=time.time())
        
        # 4. Validate data
        metrics, issues = self.validator.validate_session(manifest)
        
        # Should have good quality scores
        self.assertGreater(metrics.overall_score, 50)
        self.assertEqual(metrics.file_count, 1)
        self.assertEqual(metrics.missing_files, 0)
        
        # 5. Export data
        export_config = ExportConfiguration(
            format=ExportFormat.JSON,
            output_directory=self.temp_dir
        )
        
        export_result = self.exporter.export_session(manifest, export_config)
        
        self.assertTrue(export_result.success)
        self.assertGreater(len(export_result.output_files), 0)
        
        # 6. Verify exported data
        exported_file = Path(export_result.output_files[0])
        self.assertTrue(exported_file.exists())
        
        with open(exported_file, 'r') as f:
            exported_data = json.load(f)
            
        self.assertEqual(exported_data['session_info']['session_id'], "integration_test_001")
        self.assertEqual(len(exported_data['files']), 1)
        
    def test_error_handling(self):
        """Test error handling in workflow."""
        # Test with missing files
        manifest = self.manifest_generator.create_session_manifest(
            self.mock_session_info, self.mock_devices
        )
        
        # Add metadata for non-existent file
        self.manifest_generator.add_file_metadata(
            file_path="/non/existent/file.csv",
            modality=DataModality.GSR,
            device_id="device_001",
            start_timestamp=1.0,
            end_timestamp=2.0
        )
        
        # Validation should catch missing file
        metrics, issues = self.validator.validate_session(manifest)
        
        self.assertGreater(metrics.missing_files, 0)
        error_issues = [i for i in issues if i.severity == ValidationSeverity.ERROR]
        self.assertGreater(len(error_issues), 0)


def run_tests():
    """Run all tests."""
    # Create test suite
    test_suite = unittest.TestSuite()
    
    # Add test cases
    test_classes = [
        TestSessionManifestGenerator,
        TestFileAggregator,
        TestDataValidator,
        TestDataExporter,
        TestIntegration
    ]
    
    for test_class in test_classes:
        tests = unittest.TestLoader().loadTestsFromTestCase(test_class)
        test_suite.addTests(tests)
    
    # Run tests
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(test_suite)
    
    return result.wasSuccessful()


if __name__ == '__main__':
    success = run_tests()
    exit(0 if success else 1)