#!/usr/bin/env python3
"""
Data Validation and Quality Reporting System for PC Controller Application
Analyzes collected data files and generates comprehensive quality reports.
"""

import json
import logging
import time
from typing import Dict, Any, Optional, List, Tuple
from datetime import datetime
from pathlib import Path
from dataclasses import dataclass, field
from enum import Enum

from data.session_manifest import SessionManifest, FileMetadata, DataModality


class ValidationSeverity(Enum):
    """Validation issue severity levels."""
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


@dataclass
class ValidationIssue:
    """Represents a data validation issue."""
    issue_type: str
    severity: ValidationSeverity
    message: str
    file_path: Optional[str] = None
    device_id: Optional[str] = None
    modality: Optional[DataModality] = None
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'issue_type': self.issue_type,
            'severity': self.severity.value,
            'message': self.message,
            'file_path': self.file_path,
            'device_id': self.device_id,
            'modality': self.modality.value if self.modality else None,
            'timestamp': self.timestamp,
            'metadata': self.metadata
        }


@dataclass
class QualityMetrics:
    """Quality metrics for a data file or session."""
    completeness_score: float = 0.0  # 0-100
    integrity_score: float = 0.0     # 0-100
    synchronization_score: float = 0.0  # 0-100
    overall_score: float = 0.0       # 0-100
    file_count: int = 0
    missing_files: int = 0
    corrupted_files: int = 0
    sync_issues: int = 0
    total_size_mb: float = 0.0
    duration_minutes: float = 0.0
    
    def calculate_overall_score(self):
        """Calculate overall quality score."""
        weights = {
            'completeness': 0.4,
            'integrity': 0.3,
            'synchronization': 0.3
        }
        
        self.overall_score = (
            self.completeness_score * weights['completeness'] +
            self.integrity_score * weights['integrity'] +
            self.synchronization_score * weights['synchronization']
        )


class DataValidator:
    """Main data validation and quality reporting system."""
    
    def __init__(self):
        self.validation_issues = []
        self.quality_metrics = QualityMetrics()
        
    def validate_session(self, manifest: SessionManifest) -> Tuple[QualityMetrics, List[ValidationIssue]]:
        """Validate an entire session and return quality metrics and issues."""
        self.validation_issues = []
        self.quality_metrics = QualityMetrics()
        
        # Basic session validation
        self._validate_session_metadata(manifest)
        
        # File validation
        self._validate_files(manifest)
        
        # Synchronization validation
        self._validate_synchronization(manifest)
        
        # Calculate final scores
        self._calculate_quality_scores(manifest)
        
        return self.quality_metrics, self.validation_issues
        
    def _validate_session_metadata(self, manifest: SessionManifest):
        """Validate session metadata."""
        if not manifest.session_id:
            self._add_issue("missing_session_id", ValidationSeverity.ERROR,
                          "Session ID is missing")
            
        if not manifest.start_timestamp:
            self._add_issue("missing_start_time", ValidationSeverity.ERROR,
                          "Session start timestamp is missing")
            
        if manifest.end_timestamp and manifest.end_timestamp <= manifest.start_timestamp:
            self._add_issue("invalid_end_time", ValidationSeverity.ERROR,
                          "Session end time is before or equal to start time")
                          
    def _validate_files(self, manifest: SessionManifest):
        """Validate all files in the session."""
        self.quality_metrics.file_count = len(manifest.files)
        
        for file_meta in manifest.files:
            self._validate_file(file_meta)
            
    def _validate_file(self, file_meta: FileMetadata):
        """Validate a single file."""
        file_path = Path(file_meta.file_path)
        
        # Check file existence
        if not file_path.exists():
            self._add_issue("missing_file", ValidationSeverity.ERROR,
                          f"File not found: {file_meta.file_name}",
                          file_path=str(file_path), device_id=file_meta.device_id,
                          modality=file_meta.modality)
            self.quality_metrics.missing_files += 1
            return
            
        # Check file size
        actual_size = file_path.stat().st_size
        if actual_size != file_meta.file_size:
            self._add_issue("size_mismatch", ValidationSeverity.WARNING,
                          f"File size mismatch: expected {file_meta.file_size}, got {actual_size}",
                          file_path=str(file_path), device_id=file_meta.device_id,
                          modality=file_meta.modality)
                          
        # Verify checksum if available
        if file_meta.checksum:
            if not self._verify_checksum(file_path, file_meta.checksum):
                self._add_issue("checksum_mismatch", ValidationSeverity.ERROR,
                              f"File checksum verification failed: {file_meta.file_name}",
                              file_path=str(file_path), device_id=file_meta.device_id,
                              modality=file_meta.modality)
                self.quality_metrics.corrupted_files += 1
                
        # Update total size
        self.quality_metrics.total_size_mb += actual_size / (1024 * 1024)
        
    def _verify_checksum(self, file_path: Path, expected_checksum: str) -> bool:
        """Verify file checksum."""
        import hashlib
        
        try:
            hash_md5 = hashlib.md5()
            with open(file_path, "rb") as f:
                for chunk in iter(lambda: f.read(4096), b""):
                    hash_md5.update(chunk)
            return hash_md5.hexdigest() == expected_checksum
        except Exception as e:
            logging.error(f"Failed to calculate checksum for {file_path}: {e}")
            return False
            
    def _validate_synchronization(self, manifest: SessionManifest):
        """Validate synchronization quality."""
        if not manifest.sync_events:
            self._add_issue("no_sync_events", ValidationSeverity.WARNING,
                          "No synchronization events found")
            return
            
        # Check for sync quality issues
        if manifest.sync_statistics:
            avg_offset = manifest.sync_statistics.get('average_offset_ms', 0)
            if avg_offset > 100:
                self._add_issue("poor_sync_quality", ValidationSeverity.ERROR,
                              f"Poor synchronization quality: {avg_offset:.1f}ms average offset")
                self.quality_metrics.sync_issues += 1
            elif avg_offset > 50:
                self._add_issue("fair_sync_quality", ValidationSeverity.WARNING,
                              f"Fair synchronization quality: {avg_offset:.1f}ms average offset")
                              
    def _calculate_quality_scores(self, manifest: SessionManifest):
        """Calculate quality scores."""
        total_files = self.quality_metrics.file_count
        
        if total_files == 0:
            return
            
        # Completeness score
        missing_ratio = self.quality_metrics.missing_files / total_files
        self.quality_metrics.completeness_score = max(0, (1 - missing_ratio) * 100)
        
        # Integrity score
        corrupted_ratio = self.quality_metrics.corrupted_files / total_files
        self.quality_metrics.integrity_score = max(0, (1 - corrupted_ratio) * 100)
        
        # Synchronization score
        if manifest.sync_statistics:
            avg_offset = manifest.sync_statistics.get('average_offset_ms', 0)
            if avg_offset <= 10:
                self.quality_metrics.synchronization_score = 100
            elif avg_offset <= 25:
                self.quality_metrics.synchronization_score = 85
            elif avg_offset <= 50:
                self.quality_metrics.synchronization_score = 70
            elif avg_offset <= 100:
                self.quality_metrics.synchronization_score = 50
            else:
                self.quality_metrics.synchronization_score = 25
        else:
            self.quality_metrics.synchronization_score = 50  # Default for missing sync data
            
        # Calculate overall score
        self.quality_metrics.calculate_overall_score()
        
        # Duration
        if manifest.duration_seconds:
            self.quality_metrics.duration_minutes = manifest.duration_seconds / 60
            
    def _add_issue(self, issue_type: str, severity: ValidationSeverity, message: str,
                   file_path: Optional[str] = None, device_id: Optional[str] = None,
                   modality: Optional[DataModality] = None):
        """Add a validation issue."""
        issue = ValidationIssue(
            issue_type=issue_type,
            severity=severity,
            message=message,
            file_path=file_path,
            device_id=device_id,
            modality=modality
        )
        self.validation_issues.append(issue)
        
    def generate_quality_report(self, manifest: SessionManifest, 
                               output_path: Optional[str] = None) -> str:
        """Generate a comprehensive quality report."""
        metrics, issues = self.validate_session(manifest)
        
        report = {
            'session_info': {
                'session_id': manifest.session_id,
                'session_name': manifest.session_name,
                'participant_id': manifest.participant_id,
                'duration_minutes': metrics.duration_minutes,
                'device_count': len(manifest.devices),
                'generated_at': datetime.now().isoformat()
            },
            'quality_metrics': {
                'overall_score': round(metrics.overall_score, 1),
                'completeness_score': round(metrics.completeness_score, 1),
                'integrity_score': round(metrics.integrity_score, 1),
                'synchronization_score': round(metrics.synchronization_score, 1),
                'file_count': metrics.file_count,
                'missing_files': metrics.missing_files,
                'corrupted_files': metrics.corrupted_files,
                'sync_issues': metrics.sync_issues,
                'total_size_mb': round(metrics.total_size_mb, 2)
            },
            'validation_issues': [issue.to_dict() for issue in issues],
            'issue_summary': self._generate_issue_summary(issues),
            'recommendations': self._generate_recommendations(metrics, issues)
        }
        
        # Save report if output path provided
        if output_path:
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(report, f, indent=2, default=str)
                
        return json.dumps(report, indent=2, default=str)
        
    def _generate_issue_summary(self, issues: List[ValidationIssue]) -> Dict[str, int]:
        """Generate summary of validation issues by severity."""
        summary = {severity.value: 0 for severity in ValidationSeverity}
        
        for issue in issues:
            summary[issue.severity.value] += 1
            
        return summary
        
    def _generate_recommendations(self, metrics: QualityMetrics, 
                                 issues: List[ValidationIssue]) -> List[str]:
        """Generate recommendations based on validation results."""
        recommendations = []
        
        if metrics.overall_score < 70:
            recommendations.append("Overall data quality is below acceptable threshold. Review all issues.")
            
        if metrics.missing_files > 0:
            recommendations.append(f"Re-collect {metrics.missing_files} missing files from devices.")
            
        if metrics.corrupted_files > 0:
            recommendations.append(f"Verify and re-transfer {metrics.corrupted_files} corrupted files.")
            
        if metrics.synchronization_score < 70:
            recommendations.append("Improve time synchronization setup for future sessions.")
            
        if not any(issue.severity == ValidationSeverity.ERROR for issue in issues):
            recommendations.append("Data quality is acceptable for analysis.")
            
        return recommendations

    # Enhanced validation methods for comprehensive data quality assessment

    def validate_session_comprehensive(self, manifest: SessionManifest, 
                                     device_sync_statuses: Dict[str, Any] = None,
                                     coordination_info: Dict[str, Any] = None) -> Tuple[QualityMetrics, List[ValidationIssue]]:
        """Perform comprehensive session validation with enhanced metrics."""
        self.validation_issues = []
        self.quality_metrics = QualityMetrics()
        
        # Basic validation
        self._validate_session_metadata(manifest)
        self._validate_files(manifest)
        self._validate_synchronization(manifest)
        
        # Enhanced validations
        self._validate_multi_device_coordination(manifest, coordination_info)
        self._validate_temporal_consistency(manifest)
        self._validate_data_completeness_advanced(manifest)
        self._validate_modality_quality(manifest)
        self._validate_device_performance(manifest, device_sync_statuses)
        
        # Calculate enhanced quality scores
        self._calculate_enhanced_quality_scores(manifest, device_sync_statuses, coordination_info)
        
        return self.quality_metrics, self.validation_issues

    def _validate_multi_device_coordination(self, manifest: SessionManifest, coordination_info: Dict[str, Any]):
        """Validate multi-device coordination quality."""
        if not coordination_info or len(manifest.devices) < 2:
            return
        
        try:
            # Check coordination success rate
            coord_success_rate = coordination_info.get('coordination_success_rate', 0.0)
            if coord_success_rate < 0.8:
                self._add_issue(
                    "coordination_quality",
                    ValidationSeverity.WARNING,
                    f"Low coordination success rate: {coord_success_rate:.1%}"
                )
            
            # Check inter-device synchronization
            max_offset = coordination_info.get('max_inter_device_offset_ms', 0.0)
            if max_offset > 50:
                self._add_issue(
                    "inter_device_sync",
                    ValidationSeverity.ERROR,
                    f"High inter-device offset: {max_offset:.1f}ms"
                )
            elif max_offset > 25:
                self._add_issue(
                    "inter_device_sync",
                    ValidationSeverity.WARNING,
                    f"Moderate inter-device offset: {max_offset:.1f}ms"
                )
            
            # Validate coordination events
            coord_events = coordination_info.get('coordination_events', [])
            if len(coord_events) == 0 and len(manifest.devices) > 1:
                self._add_issue(
                    "coordination_events",
                    ValidationSeverity.WARNING,
                    "No coordination events found for multi-device session"
                )
                
        except Exception as e:
            logging.error(f"Error validating multi-device coordination: {e}")

    def _validate_temporal_consistency(self, manifest: SessionManifest):
        """Validate temporal consistency across devices and modalities."""
        try:
            # Group files by device
            device_files = {}
            for file_meta in manifest.files:
                if file_meta.device_id not in device_files:
                    device_files[file_meta.device_id] = []
                device_files[file_meta.device_id].append(file_meta)
            
            # Check temporal alignment between devices
            if len(device_files) > 1:
                device_start_times = {}
                device_end_times = {}
                
                for device_id, files in device_files.items():
                    if files:
                        device_start_times[device_id] = min(f.start_timestamp for f in files)
                        device_end_times[device_id] = max(f.end_timestamp for f in files)
                
                # Check start time alignment
                if len(device_start_times) > 1:
                    start_times = list(device_start_times.values())
                    start_time_range = max(start_times) - min(start_times)
                    
                    if start_time_range > 10:  # 10 seconds
                        self._add_issue(
                            "temporal_alignment",
                            ValidationSeverity.WARNING,
                            f"Devices started recording with {start_time_range:.1f}s difference"
                        )
                
                # Check end time alignment
                if len(device_end_times) > 1:
                    end_times = list(device_end_times.values())
                    end_time_range = max(end_times) - min(end_times)
                    
                    if end_time_range > 10:  # 10 seconds
                        self._add_issue(
                            "temporal_alignment",
                            ValidationSeverity.INFO,
                            f"Devices stopped recording with {end_time_range:.1f}s difference"
                        )
            
            # Check for temporal gaps within device recordings
            for device_id, files in device_files.items():
                if len(files) > 1:
                    sorted_files = sorted(files, key=lambda f: f.start_timestamp)
                    
                    for i in range(len(sorted_files) - 1):
                        gap = sorted_files[i + 1].start_timestamp - sorted_files[i].end_timestamp
                        if gap > 5:  # 5 second gap
                            self._add_issue(
                                "temporal_gaps",
                                ValidationSeverity.WARNING,
                                f"Temporal gap of {gap:.1f}s detected in device {device_id}",
                                device_id=device_id
                            )
                            
        except Exception as e:
            logging.error(f"Error validating temporal consistency: {e}")

    def _validate_data_completeness_advanced(self, manifest: SessionManifest):
        """Advanced data completeness validation."""
        try:
            # Expected modalities per device type
            expected_modalities = {
                DataModality.RGB_VIDEO,
                DataModality.AUDIO,
                DataModality.GSR,
                DataModality.SYSTEM_LOGS
            }
            
            # Optional modalities
            optional_modalities = {
                DataModality.THERMAL_VIDEO,
                DataModality.PPG,
                DataModality.ACCELEROMETER,
                DataModality.GYROSCOPE,
                DataModality.TEMPERATURE
            }
            
            # Check completeness per device
            device_modalities = {}
            for file_meta in manifest.files:
                if file_meta.device_id not in device_modalities:
                    device_modalities[file_meta.device_id] = set()
                device_modalities[file_meta.device_id].add(file_meta.modality)
            
            for device_id, modalities in device_modalities.items():
                missing_required = expected_modalities - modalities
                if missing_required:
                    for modality in missing_required:
                        self._add_issue(
                            "missing_modality",
                            ValidationSeverity.ERROR,
                            f"Missing required modality: {modality.value}",
                            device_id=device_id,
                            modality=modality
                        )
                
                # Check for bonus modalities
                bonus_modalities = modalities & optional_modalities
                if bonus_modalities:
                    self.quality_metrics.bonus_modalities = len(bonus_modalities)
            
            # Check file size consistency within modalities
            modality_sizes = {}
            for file_meta in manifest.files:
                if file_meta.modality not in modality_sizes:
                    modality_sizes[file_meta.modality] = []
                modality_sizes[file_meta.modality].append(file_meta.file_size)
            
            for modality, sizes in modality_sizes.items():
                if len(sizes) > 1:
                    import statistics
                    mean_size = statistics.mean(sizes)
                    std_dev = statistics.stdev(sizes) if len(sizes) > 1 else 0
                    
                    # Flag files that are significantly different in size
                    for i, size in enumerate(sizes):
                        if std_dev > 0 and abs(size - mean_size) > 2 * std_dev:
                            self._add_issue(
                                "size_anomaly",
                                ValidationSeverity.WARNING,
                                f"File size anomaly in {modality.value}: {size} bytes (mean: {mean_size:.0f})",
                                modality=modality
                            )
                            
        except Exception as e:
            logging.error(f"Error in advanced completeness validation: {e}")

    def _validate_modality_quality(self, manifest: SessionManifest):
        """Validate quality of specific data modalities."""
        try:
            modality_files = {}
            for file_meta in manifest.files:
                if file_meta.modality not in modality_files:
                    modality_files[file_meta.modality] = []
                modality_files[file_meta.modality].append(file_meta)
            
            # Video quality checks
            video_modalities = [DataModality.RGB_VIDEO, DataModality.THERMAL_VIDEO]
            for modality in video_modalities:
                if modality in modality_files:
                    self._validate_video_quality(modality_files[modality], modality)
            
            # Audio quality checks
            if DataModality.AUDIO in modality_files:
                self._validate_audio_quality(modality_files[DataModality.AUDIO])
            
            # Sensor data quality checks
            sensor_modalities = [DataModality.GSR, DataModality.PPG, DataModality.ACCELEROMETER]
            for modality in sensor_modalities:
                if modality in modality_files:
                    self._validate_sensor_quality(modality_files[modality], modality)
                    
        except Exception as e:
            logging.error(f"Error validating modality quality: {e}")

    def _validate_video_quality(self, video_files: List[FileMetadata], modality: DataModality):
        """Validate video file quality."""
        for file_meta in video_files:
            # Check minimum file size (rough quality indicator)
            min_size_mb = 10 if modality == DataModality.RGB_VIDEO else 5  # MB
            if file_meta.file_size < min_size_mb * 1024 * 1024:
                self._add_issue(
                    "video_quality",
                    ValidationSeverity.WARNING,
                    f"Small {modality.value} file may indicate quality issues: {file_meta.file_size / (1024*1024):.1f}MB",
                    file_path=file_meta.file_path,
                    modality=modality
                )
            
            # Check duration consistency
            if hasattr(file_meta, 'duration') and file_meta.duration:
                expected_duration = file_meta.end_timestamp - file_meta.start_timestamp
                if abs(file_meta.duration - expected_duration) > 5:  # 5 second tolerance
                    self._add_issue(
                        "duration_mismatch",
                        ValidationSeverity.WARNING,
                        f"Duration mismatch in {modality.value}: file={file_meta.duration}s, expected={expected_duration:.1f}s",
                        file_path=file_meta.file_path,
                        modality=modality
                    )

    def _validate_audio_quality(self, audio_files: List[FileMetadata]):
        """Validate audio file quality."""
        for file_meta in audio_files:
            # Check minimum file size
            min_size_mb = 1  # MB
            if file_meta.file_size < min_size_mb * 1024 * 1024:
                self._add_issue(
                    "audio_quality",
                    ValidationSeverity.WARNING,
                    f"Small audio file may indicate quality issues: {file_meta.file_size / (1024*1024):.1f}MB",
                    file_path=file_meta.file_path,
                    modality=DataModality.AUDIO
                )

    def _validate_sensor_quality(self, sensor_files: List[FileMetadata], modality: DataModality):
        """Validate sensor data quality."""
        for file_meta in sensor_files:
            # Check minimum file size (sensor data should have reasonable size)
            min_size_kb = 10  # KB
            if file_meta.file_size < min_size_kb * 1024:
                self._add_issue(
                    "sensor_quality",
                    ValidationSeverity.WARNING,
                    f"Small {modality.value} file may indicate insufficient data: {file_meta.file_size / 1024:.1f}KB",
                    file_path=file_meta.file_path,
                    modality=modality
                )

    def _validate_device_performance(self, manifest: SessionManifest, device_sync_statuses: Dict[str, Any]):
        """Validate individual device performance."""
        if not device_sync_statuses:
            return
        
        try:
            for device_id, sync_status in device_sync_statuses.items():
                # Check sync success rate
                if hasattr(sync_status, 'measurements') and sync_status.measurements:
                    total_attempts = len(sync_status.measurements)
                    successful_attempts = sum(1 for m in sync_status.measurements if m.quality > 0.5)
                    success_rate = successful_attempts / total_attempts if total_attempts > 0 else 0.0
                    
                    if success_rate < 0.8:
                        self._add_issue(
                            "device_sync_performance",
                            ValidationSeverity.WARNING,
                            f"Device {device_id} has low sync success rate: {success_rate:.1%}",
                            device_id=device_id
                        )
                
                # Check sync uncertainty
                if hasattr(sync_status, 'uncertainty') and sync_status.uncertainty:
                    uncertainty_ms = sync_status.uncertainty * 1000
                    if uncertainty_ms > 50:
                        self._add_issue(
                            "sync_uncertainty",
                            ValidationSeverity.ERROR,
                            f"High sync uncertainty for device {device_id}: {uncertainty_ms:.1f}ms",
                            device_id=device_id
                        )
                    elif uncertainty_ms > 25:
                        self._add_issue(
                            "sync_uncertainty",
                            ValidationSeverity.WARNING,
                            f"Moderate sync uncertainty for device {device_id}: {uncertainty_ms:.1f}ms",
                            device_id=device_id
                        )
                        
        except Exception as e:
            logging.error(f"Error validating device performance: {e}")

    def _calculate_enhanced_quality_scores(self, manifest: SessionManifest, 
                                         device_sync_statuses: Dict[str, Any],
                                         coordination_info: Dict[str, Any]):
        """Calculate enhanced quality scores with additional metrics."""
        # Basic quality scores
        self._calculate_quality_scores(manifest)
        
        # Multi-device coordination score
        if coordination_info and len(manifest.devices) > 1:
            coord_success_rate = coordination_info.get('coordination_success_rate', 0.0)
            max_offset = coordination_info.get('max_inter_device_offset_ms', 100.0)
            
            # Score based on coordination success and inter-device sync
            coord_score = coord_success_rate * 100
            if max_offset <= 10:
                offset_score = 100
            elif max_offset <= 25:
                offset_score = 80
            elif max_offset <= 50:
                offset_score = 60
            else:
                offset_score = 30
            
            self.quality_metrics.coordination_score = (coord_score + offset_score) / 2
        else:
            self.quality_metrics.coordination_score = 100  # Single device, perfect coordination
        
        # Temporal consistency score
        temporal_issues = [issue for issue in self.validation_issues 
                          if issue.issue_type in ['temporal_alignment', 'temporal_gaps']]
        if len(temporal_issues) == 0:
            self.quality_metrics.temporal_consistency_score = 100
        elif len(temporal_issues) <= 2:
            self.quality_metrics.temporal_consistency_score = 80
        elif len(temporal_issues) <= 5:
            self.quality_metrics.temporal_consistency_score = 60
        else:
            self.quality_metrics.temporal_consistency_score = 40
        
        # Modality quality score
        modality_issues = [issue for issue in self.validation_issues 
                          if issue.issue_type in ['video_quality', 'audio_quality', 'sensor_quality']]
        total_files = len(manifest.files)
        if total_files > 0:
            quality_ratio = 1 - (len(modality_issues) / total_files)
            self.quality_metrics.modality_quality_score = max(0, quality_ratio * 100)
        else:
            self.quality_metrics.modality_quality_score = 0
        
        # Recalculate overall score with enhanced metrics
        weights = {
            'completeness': 0.25,
            'integrity': 0.20,
            'synchronization': 0.20,
            'coordination': 0.15,
            'temporal': 0.10,
            'modality': 0.10
        }
        
        self.quality_metrics.overall_score = (
            self.quality_metrics.completeness_score * weights['completeness'] +
            self.quality_metrics.integrity_score * weights['integrity'] +
            self.quality_metrics.synchronization_score * weights['synchronization'] +
            getattr(self.quality_metrics, 'coordination_score', 100) * weights['coordination'] +
            getattr(self.quality_metrics, 'temporal_consistency_score', 100) * weights['temporal'] +
            getattr(self.quality_metrics, 'modality_quality_score', 100) * weights['modality']
        )

    def generate_comprehensive_quality_report(self, manifest: SessionManifest,
                                            device_sync_statuses: Dict[str, Any] = None,
                                            coordination_info: Dict[str, Any] = None,
                                            output_path: Optional[str] = None) -> str:
        """Generate comprehensive quality report with enhanced analysis."""
        metrics, issues = self.validate_session_comprehensive(manifest, device_sync_statuses, coordination_info)
        
        # Group issues by type and severity
        issues_by_type = {}
        issues_by_severity = {severity.value: [] for severity in ValidationSeverity}
        
        for issue in issues:
            if issue.issue_type not in issues_by_type:
                issues_by_type[issue.issue_type] = []
            issues_by_type[issue.issue_type].append(issue)
            issues_by_severity[issue.severity.value].append(issue)
        
        # Generate device-specific analysis
        device_analysis = self._generate_device_analysis(manifest, issues, device_sync_statuses)
        
        # Generate modality analysis
        modality_analysis = self._generate_modality_analysis(manifest, issues)
        
        comprehensive_report = {
            'session_info': {
                'session_id': manifest.session_id,
                'session_name': manifest.session_name,
                'participant_id': manifest.participant_id,
                'duration_minutes': metrics.duration_minutes,
                'device_count': len(manifest.devices),
                'total_files': len(manifest.files),
                'generated_at': datetime.now().isoformat()
            },
            'enhanced_quality_metrics': {
                'overall_score': round(metrics.overall_score, 1),
                'completeness_score': round(metrics.completeness_score, 1),
                'integrity_score': round(metrics.integrity_score, 1),
                'synchronization_score': round(metrics.synchronization_score, 1),
                'coordination_score': round(getattr(metrics, 'coordination_score', 100), 1),
                'temporal_consistency_score': round(getattr(metrics, 'temporal_consistency_score', 100), 1),
                'modality_quality_score': round(getattr(metrics, 'modality_quality_score', 100), 1),
                'file_count': metrics.file_count,
                'missing_files': metrics.missing_files,
                'corrupted_files': metrics.corrupted_files,
                'sync_issues': metrics.sync_issues,
                'total_size_mb': round(metrics.total_size_mb, 2),
                'bonus_modalities': getattr(metrics, 'bonus_modalities', 0)
            },
            'validation_analysis': {
                'total_issues': len(issues),
                'issues_by_severity': {k: len(v) for k, v in issues_by_severity.items()},
                'issues_by_type': {k: len(v) for k, v in issues_by_type.items()},
                'critical_issues': [issue.to_dict() for issue in issues if issue.severity == ValidationSeverity.ERROR],
                'warnings': [issue.to_dict() for issue in issues if issue.severity == ValidationSeverity.WARNING]
            },
            'device_analysis': device_analysis,
            'modality_analysis': modality_analysis,
            'coordination_analysis': self._generate_coordination_analysis(coordination_info) if coordination_info else None,
            'recommendations': self._generate_enhanced_recommendations(metrics, issues, coordination_info)
        }
        
        # Save report if output path provided
        if output_path:
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(comprehensive_report, f, indent=2, default=str)
        
        return json.dumps(comprehensive_report, indent=2, default=str)

    def _generate_device_analysis(self, manifest: SessionManifest, issues: List[ValidationIssue], 
                                device_sync_statuses: Dict[str, Any]) -> Dict[str, Any]:
        """Generate per-device analysis."""
        device_analysis = {}
        
        for device_config in manifest.devices:
            device_id = device_config.device_id
            device_files = [f for f in manifest.files if f.device_id == device_id]
            device_issues = [i for i in issues if i.device_id == device_id]
            
            analysis = {
                'file_count': len(device_files),
                'total_size_mb': sum(f.file_size for f in device_files) / (1024 * 1024),
                'modalities': list(set(f.modality.value for f in device_files)),
                'issue_count': len(device_issues),
                'critical_issues': len([i for i in device_issues if i.severity == ValidationSeverity.ERROR]),
                'warnings': len([i for i in device_issues if i.severity == ValidationSeverity.WARNING])
            }
            
            # Add sync status if available
            if device_sync_statuses and device_id in device_sync_statuses:
                sync_status = device_sync_statuses[device_id]
                if hasattr(sync_status, 'uncertainty') and sync_status.uncertainty:
                    analysis['sync_uncertainty_ms'] = sync_status.uncertainty * 1000
                if hasattr(sync_status, 'measurements') and sync_status.measurements:
                    analysis['sync_attempts'] = len(sync_status.measurements)
                    analysis['sync_success_rate'] = sum(1 for m in sync_status.measurements if m.quality > 0.5) / len(sync_status.measurements)
            
            device_analysis[device_id] = analysis
        
        return device_analysis

    def _generate_modality_analysis(self, manifest: SessionManifest, issues: List[ValidationIssue]) -> Dict[str, Any]:
        """Generate per-modality analysis."""
        modality_analysis = {}
        
        modality_files = {}
        for file_meta in manifest.files:
            if file_meta.modality not in modality_files:
                modality_files[file_meta.modality] = []
            modality_files[file_meta.modality].append(file_meta)
        
        for modality, files in modality_files.items():
            modality_issues = [i for i in issues if i.modality == modality]
            
            analysis = {
                'file_count': len(files),
                'total_size_mb': sum(f.file_size for f in files) / (1024 * 1024),
                'devices': list(set(f.device_id for f in files)),
                'issue_count': len(modality_issues),
                'quality_issues': len([i for i in modality_issues if 'quality' in i.issue_type])
            }
            
            modality_analysis[modality.value] = analysis
        
        return modality_analysis

    def _generate_coordination_analysis(self, coordination_info: Dict[str, Any]) -> Dict[str, Any]:
        """Generate coordination analysis."""
        return {
            'coordinator_device': coordination_info.get('coordinator_device', 'unknown'),
            'participating_devices': coordination_info.get('participating_devices', []),
            'coordination_success_rate': coordination_info.get('coordination_success_rate', 0.0),
            'max_inter_device_offset_ms': coordination_info.get('max_inter_device_offset_ms', 0.0),
            'coordination_events_count': len(coordination_info.get('coordination_events', [])),
            'sync_quality': coordination_info.get('inter_device_sync_quality', 'unknown')
        }

    def _generate_enhanced_recommendations(self, metrics: QualityMetrics, issues: List[ValidationIssue],
                                         coordination_info: Dict[str, Any]) -> List[str]:
        """Generate enhanced recommendations based on comprehensive analysis."""
        recommendations = []
        
        # Overall quality recommendations
        if metrics.overall_score < 60:
            recommendations.append("CRITICAL: Overall data quality is poor. Consider re-collecting data.")
        elif metrics.overall_score < 80:
            recommendations.append("Data quality is below optimal. Review and address identified issues.")
        
        # Specific issue recommendations
        error_issues = [i for i in issues if i.severity == ValidationSeverity.ERROR]
        if error_issues:
            recommendations.append(f"Address {len(error_issues)} critical errors before proceeding with analysis.")
        
        # Coordination recommendations
        if coordination_info and len(coordination_info.get('participating_devices', [])) > 1:
            coord_success = coordination_info.get('coordination_success_rate', 0.0)
            if coord_success < 0.8:
                recommendations.append("Improve multi-device coordination setup for future sessions.")
            
            max_offset = coordination_info.get('max_inter_device_offset_ms', 0.0)
            if max_offset > 50:
                recommendations.append("High inter-device synchronization offset detected. Check time sync configuration.")
        
        # Modality-specific recommendations
        modality_issues = {}
        for issue in issues:
            if issue.modality and 'quality' in issue.issue_type:
                if issue.modality not in modality_issues:
                    modality_issues[issue.modality] = 0
                modality_issues[issue.modality] += 1
        
        for modality, count in modality_issues.items():
            if count > 0:
                recommendations.append(f"Review {modality.value} data quality - {count} issues detected.")
        
        # Temporal recommendations
        temporal_issues = [i for i in issues if 'temporal' in i.issue_type]
        if temporal_issues:
            recommendations.append("Improve temporal synchronization between devices and modalities.")
        
        # Success recommendations
        if metrics.overall_score >= 90:
            recommendations.append("Excellent data quality! Data is ready for analysis.")
        elif metrics.overall_score >= 80:
            recommendations.append("Good data quality. Minor issues can be addressed during analysis.")
        
        return recommendations