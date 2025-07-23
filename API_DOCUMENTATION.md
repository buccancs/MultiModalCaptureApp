# Multi-Modal Recording System API Documentation

## Core Time Synchronization API

### TimeSynchronizer Class

The main class for handling time synchronization across multiple devices.

#### Key Methods

```python
class TimeSynchronizer:
    def __init__(self, max_measurements: int = 10, sync_interval: float = 30.0)
    
    async def sync_device(self, device_id: str, num_measurements: int = 5) -> bool
    """Synchronize time with a specific device."""
    
    async def coordinate_multi_device_sync(self, device_ids: List[str]) -> Dict[str, bool]
    """Coordinate synchronization across multiple devices."""
    
    def get_sync_quality_level(self, device_id: str) -> SyncQuality
    """Get synchronization quality level for device."""
    
    async def handle_network_failure(self, device_id: str, failure_type: str)
    """Handle network failure with recovery strategies."""
```

#### Usage Example

```python
# Initialize synchronizer
sync = TimeSynchronizer(max_measurements=10, sync_interval=30.0)

# Register devices
for device_id in ["device_1", "device_2"]:
    sync.register_device(device_id)

# Perform synchronization
success = await sync.sync_device("device_1", num_measurements=5)
quality = sync.get_sync_quality_level("device_1")
```

### MultiDeviceCoordinator Class

Manages coordination across multiple device groups.

#### Key Methods

```python
class MultiDeviceCoordinator:
    def create_device_group(self, group_name: str, device_ids: List[str])
    """Create a group of devices for coordinated operations."""
    
    async def coordinate_group_sync(self, group_name: str, time_synchronizer) -> Dict[str, bool]
    """Coordinate synchronization for a device group."""
    
    def get_sync_session_history(self, limit: int = 10) -> List[Dict]
    """Get recent synchronization session history."""
```

## Data Management API

### SessionManifestGenerator Class

Generates comprehensive session metadata and manifests.

#### Key Methods

```python
class SessionManifestGenerator:
    def start_session(self, session_name: str, participant_id: str = None) -> str
    """Start a new recording session."""
    
    def add_file_metadata(self, file_path: str, modality: DataModality, 
                         device_id: str, **kwargs) -> FileMetadata
    """Add file metadata to current session."""
    
    def calculate_enhanced_quality_metrics(self, device_sync_statuses: Dict = None) -> EnhancedQualityMetrics
    """Calculate comprehensive quality metrics for the session."""
    
    def export_enhanced_manifest(self, include_analysis: bool = True) -> str
    """Export enhanced manifest with comprehensive analysis."""
```

### FileAggregator Class

Handles coordinated file collection from multiple devices.

#### Key Methods

```python
class FileAggregator:
    def start_coordinated_aggregation(self, session_id: str, device_ids: List[str], 
                                    coordination_info: Dict = None) -> bool
    """Start coordinated aggregation across multiple devices."""
    
    def get_enhanced_statistics(self) -> Dict[str, Any]
    """Get enhanced aggregation statistics with device breakdown."""
    
    def generate_comprehensive_report(self) -> Dict[str, Any]
    """Generate comprehensive aggregation report with analysis."""
```

### DataValidator Class

Provides comprehensive data quality validation.

#### Key Methods

```python
class DataValidator:
    def validate_session_comprehensive(self, manifest: SessionManifest, 
                                     device_sync_statuses: Dict = None,
                                     coordination_info: Dict = None) -> Tuple[QualityMetrics, List[ValidationIssue]]
    """Perform comprehensive session validation with enhanced metrics."""
    
    def generate_comprehensive_quality_report(self, manifest: SessionManifest,
                                            device_sync_statuses: Dict = None,
                                            coordination_info: Dict = None,
                                            output_path: str = None) -> str
    """Generate comprehensive quality report with enhanced analysis."""
```

### DataExporter Class

Exports data to various analysis formats.

#### Key Methods

```python
class DataExporter:
    def export_session(self, manifest: SessionManifest, config: ExportConfiguration) -> ExportResult
    """Export session data in specified format."""
    
    def get_supported_formats(self) -> List[ExportFormat]
    """Get list of supported export formats."""
```

#### Supported Export Formats

- **MATLAB**: `.mat` files with structured data
- **HDF5**: Hierarchical data format for large datasets
- **CSV**: Comma-separated values for sensor data
- **NumPy**: Compressed `.npz` archives
- **Parquet**: Columnar storage format
- **JSON**: Human-readable structured data

## Testing and Validation API

### MultiDeviceSyncTester Class

Comprehensive testing suite for multi-device synchronization.

#### Key Methods

```python
class MultiDeviceSyncTester:
    async def run_comprehensive_tests(self, device_ids: List[str]) -> List[SyncTestResult]
    """Run comprehensive synchronization tests."""
    
    def generate_test_report(self, output_path: str = None) -> str
    """Generate comprehensive test report."""
```

### SyncCalibrator Class

Calibration utilities for measuring true sync error.

#### Key Methods

```python
class SyncCalibrator:
    async def run_comprehensive_calibration(self, device_ids: List[str], 
                                          duration_minutes: float = 5.0) -> CalibrationSession
    """Run a comprehensive calibration session."""
    
    async def run_quick_calibration_check(self, device_ids: List[str]) -> Dict
    """Run a quick calibration check (1-2 minutes)."""
    
    def analyze_calibration_results(self, session: CalibrationSession) -> Dict
    """Analyze calibration session results."""
```

### SystemValidator Class

System-wide validation tools.

#### Key Methods

```python
class SystemValidator:
    async def run_validation(self, device_ids: List[str], 
                           level: ValidationLevel = ValidationLevel.BASIC) -> SystemValidationResult
    """Run system validation at specified level."""
    
    def save_validation_report(self, result: SystemValidationResult, output_path: str)
    """Save validation report to file."""
```

## Android Network Integration

### NetworkManager Class (Kotlin)

Enhanced Android networking with multi-device coordination.

#### Key Methods

```kotlin
class NetworkManager(context: Context) {
    fun sendSyncEvent(eventType: String, eventData: Map<String, Any> = emptyMap())
    // Send sync event to PC for multi-device coordination
    
    fun createSessionMarker(sessionId: String, markerType: String, markerData: Map<String, Any> = emptyMap())
    // Create session coordination marker
    
    fun notifyDeviceReady(sessionId: String)
    // Notify device ready for multi-device session
    
    fun setSyncEventCallback(callback: (String, Map<String, Any>) -> Unit)
    // Set sync event callback for coordination
}
```

## Error Handling and Quality Metrics

### Quality Levels

- **SyncQuality.EXCELLENT**: < 5ms uncertainty
- **SyncQuality.GOOD**: < 20ms uncertainty  
- **SyncQuality.FAIR**: < 50ms uncertainty
- **SyncQuality.POOR**: >= 50ms uncertainty

### Validation Severity Levels

- **ValidationSeverity.CRITICAL**: System-breaking issues
- **ValidationSeverity.ERROR**: Major problems requiring attention
- **ValidationSeverity.WARNING**: Minor issues that should be addressed
- **ValidationSeverity.INFO**: Informational messages

## Configuration Examples

### Basic Multi-Device Setup

```python
# Initialize components
device_manager = DeviceManager()
time_sync = TimeSynchronizer(max_measurements=10, sync_interval=30.0)
coordinator = MultiDeviceCoordinator()

# Setup device group
device_ids = ["phone_1", "phone_2", "phone_3"]
coordinator.create_device_group("experiment_group", device_ids)

# Register devices for sync
for device_id in device_ids:
    time_sync.register_device(device_id)

# Start coordinated session
results = await coordinator.coordinate_group_sync("experiment_group", time_sync)
```

### Data Export Configuration

```python
# Setup export configuration
export_config = ExportConfiguration(
    format=ExportFormat.MATLAB,
    output_directory="/path/to/output",
    include_metadata=True,
    filter_modalities=[DataModality.RGB_VIDEO, DataModality.GSR]
)

# Export session data
exporter = DataExporter()
result = exporter.export_session(session_manifest, export_config)
```

This API provides comprehensive tools for multi-device synchronization, data management, and quality validation in multi-modal recording scenarios.