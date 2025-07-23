#!/usr/bin/env python3
"""
Data Export Utilities for PC Controller Application
Exports collected session data to various analysis formats (MATLAB, HDF5, CSV, etc.).
"""

import json
import logging
import time
import csv
from typing import Dict, Any, Optional, List, Union
from datetime import datetime
from pathlib import Path
from dataclasses import dataclass, field
from enum import Enum

from data.session_manifest import SessionManifest, FileMetadata, DataModality


class ExportFormat(Enum):
    """Supported export formats."""
    MATLAB = "matlab"
    HDF5 = "hdf5"
    CSV = "csv"
    JSON = "json"
    NUMPY = "numpy"
    PARQUET = "parquet"
    EEGLAB = "eeglab"
    BIDS = "bids"


@dataclass
class ExportConfiguration:
    """Configuration for data export."""
    format: ExportFormat
    output_directory: str
    include_raw_files: bool = True
    include_metadata: bool = True
    compress_output: bool = False
    split_by_modality: bool = True
    split_by_device: bool = False
    time_alignment: bool = True
    resample_rate: Optional[float] = None
    filter_modalities: Optional[List[DataModality]] = None
    custom_parameters: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ExportResult:
    """Result of data export operation."""
    success: bool
    output_files: List[str] = field(default_factory=list)
    error_message: str = ""
    export_time_seconds: float = 0.0
    total_size_mb: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)


class DataExporter:
    """Main data export system."""
    
    def __init__(self):
        self.supported_formats = {
            ExportFormat.MATLAB: self._export_matlab,
            ExportFormat.HDF5: self._export_hdf5,
            ExportFormat.CSV: self._export_csv,
            ExportFormat.JSON: self._export_json,
            ExportFormat.NUMPY: self._export_numpy,
            ExportFormat.PARQUET: self._export_parquet,
            ExportFormat.EEGLAB: self._export_eeglab,
            ExportFormat.BIDS: self._export_bids
        }
        
    def export_session(self, manifest: SessionManifest, 
                      config: ExportConfiguration) -> ExportResult:
        """Export session data according to configuration."""
        start_time = time.time()
        result = ExportResult(success=False)
        
        try:
            # Validate configuration
            if not self._validate_config(config):
                result.error_message = "Invalid export configuration"
                return result
                
            # Create output directory
            output_path = Path(config.output_directory)
            output_path.mkdir(parents=True, exist_ok=True)
            
            # Filter files if needed
            files_to_export = self._filter_files(manifest.files, config)
            
            # Export using appropriate format handler
            if config.format in self.supported_formats:
                export_func = self.supported_formats[config.format]
                result = export_func(manifest, files_to_export, config)
            else:
                result.error_message = f"Unsupported export format: {config.format}"
                
            result.export_time_seconds = time.time() - start_time
            
            # Calculate total output size
            if result.success:
                total_size = 0
                for file_path in result.output_files:
                    if Path(file_path).exists():
                        total_size += Path(file_path).stat().st_size
                result.total_size_mb = total_size / (1024 * 1024)
                
            logging.info(f"Export completed: {config.format.value}, "
                        f"success={result.success}, time={result.export_time_seconds:.1f}s")
                        
        except Exception as e:
            result.error_message = str(e)
            logging.error(f"Export failed: {e}")
            
        return result
        
    def _validate_config(self, config: ExportConfiguration) -> bool:
        """Validate export configuration."""
        if not config.output_directory:
            return False
        if config.format not in self.supported_formats:
            return False
        return True
        
    def _filter_files(self, files: List[FileMetadata], 
                     config: ExportConfiguration) -> List[FileMetadata]:
        """Filter files based on configuration."""
        filtered_files = files
        
        # Filter by modality if specified
        if config.filter_modalities:
            filtered_files = [f for f in filtered_files 
                            if f.modality in config.filter_modalities]
                            
        return filtered_files
        
    def _export_matlab(self, manifest: SessionManifest, files: List[FileMetadata],
                      config: ExportConfiguration) -> ExportResult:
        """Export data to MATLAB format."""
        try:
            import scipy.io as sio
            import numpy as np
        except ImportError:
            return ExportResult(success=False, 
                              error_message="SciPy required for MATLAB export")
            
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            # Prepare MATLAB structure
            matlab_data = {
                'session_info': {
                    'session_id': manifest.session_id,
                    'session_name': manifest.session_name,
                    'participant_id': manifest.participant_id,
                    'start_timestamp': manifest.start_timestamp,
                    'end_timestamp': manifest.end_timestamp,
                    'duration_seconds': manifest.duration_seconds
                },
                'devices': [device.to_dict() for device in manifest.devices],
                'sync_events': manifest.sync_events,
                'data': {}
            }
            
            # Process each file
            for file_meta in files:
                data_key = f"{file_meta.device_id}_{file_meta.modality.value}"
                
                # Load and convert data based on modality
                file_data = self._load_file_data(file_meta)
                if file_data is not None:
                    matlab_data['data'][data_key] = {
                        'values': file_data,
                        'timestamps': self._generate_timestamps(file_meta),
                        'metadata': file_meta.to_dict()
                    }
                    
            # Save MATLAB file
            output_file = output_path / f"{manifest.session_id}_data.mat"
            sio.savemat(str(output_file), matlab_data)
            result.output_files.append(str(output_file))
            
            logging.info(f"MATLAB export completed: {output_file}")
            
        except Exception as e:
            result.success = False
            result.error_message = f"MATLAB export failed: {e}"
            
        return result
        
    def _export_hdf5(self, manifest: SessionManifest, files: List[FileMetadata],
                    config: ExportConfiguration) -> ExportResult:
        """Export data to HDF5 format."""
        try:
            import h5py
            import numpy as np
        except ImportError:
            return ExportResult(success=False, 
                              error_message="h5py required for HDF5 export")
            
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            output_file = output_path / f"{manifest.session_id}_data.h5"
            
            with h5py.File(str(output_file), 'w') as hf:
                # Create session info group
                session_grp = hf.create_group('session_info')
                session_grp.attrs['session_id'] = manifest.session_id
                session_grp.attrs['session_name'] = manifest.session_name or ''
                session_grp.attrs['participant_id'] = manifest.participant_id or ''
                session_grp.attrs['start_timestamp'] = manifest.start_timestamp
                if manifest.end_timestamp:
                    session_grp.attrs['end_timestamp'] = manifest.end_timestamp
                if manifest.duration_seconds:
                    session_grp.attrs['duration_seconds'] = manifest.duration_seconds
                    
                # Create devices group
                devices_grp = hf.create_group('devices')
                for i, device in enumerate(manifest.devices):
                    dev_grp = devices_grp.create_group(f'device_{i}')
                    dev_grp.attrs['device_id'] = device.device_id
                    dev_grp.attrs['device_name'] = device.device_name
                    dev_grp.attrs['device_model'] = device.device_model
                    
                # Create data group
                data_grp = hf.create_group('data')
                
                # Process each file
                for file_meta in files:
                    group_name = f"{file_meta.device_id}_{file_meta.modality.value}"
                    file_grp = data_grp.create_group(group_name)
                    
                    # Load and store data
                    file_data = self._load_file_data(file_meta)
                    if file_data is not None:
                        file_grp.create_dataset('values', data=file_data)
                        
                    # Store timestamps
                    timestamps = self._generate_timestamps(file_meta)
                    if timestamps is not None:
                        file_grp.create_dataset('timestamps', data=timestamps)
                        
                    # Store metadata as attributes
                    file_grp.attrs['file_name'] = file_meta.file_name
                    file_grp.attrs['file_size'] = file_meta.file_size
                    file_grp.attrs['start_timestamp'] = file_meta.start_timestamp
                    file_grp.attrs['end_timestamp'] = file_meta.end_timestamp
                    if file_meta.sample_rate:
                        file_grp.attrs['sample_rate'] = file_meta.sample_rate
                        
            result.output_files.append(str(output_file))
            logging.info(f"HDF5 export completed: {output_file}")
            
        except Exception as e:
            result.success = False
            result.error_message = f"HDF5 export failed: {e}"
            
        return result
        
    def _export_csv(self, manifest: SessionManifest, files: List[FileMetadata],
                   config: ExportConfiguration) -> ExportResult:
        """Export data to CSV format."""
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            # Export session metadata
            metadata_file = output_path / f"{manifest.session_id}_metadata.csv"
            with open(metadata_file, 'w', newline='', encoding='utf-8') as f:
                writer = csv.writer(f)
                writer.writerow(['Property', 'Value'])
                writer.writerow(['Session ID', manifest.session_id])
                writer.writerow(['Session Name', manifest.session_name or ''])
                writer.writerow(['Participant ID', manifest.participant_id or ''])
                writer.writerow(['Start Timestamp', manifest.start_timestamp])
                writer.writerow(['End Timestamp', manifest.end_timestamp or ''])
                writer.writerow(['Duration (seconds)', manifest.duration_seconds or ''])
                
            result.output_files.append(str(metadata_file))
            
            # Export file metadata
            files_metadata_file = output_path / f"{manifest.session_id}_files.csv"
            with open(files_metadata_file, 'w', newline='', encoding='utf-8') as f:
                if files:
                    fieldnames = files[0].to_dict().keys()
                    writer = csv.DictWriter(f, fieldnames=fieldnames)
                    writer.writeheader()
                    for file_meta in files:
                        writer.writerow(file_meta.to_dict())
                        
            result.output_files.append(str(files_metadata_file))
            
            # Export individual data files as CSV (for compatible formats)
            for file_meta in files:
                if file_meta.modality in [DataModality.GSR, DataModality.PPG, 
                                        DataModality.HEART_RATE, DataModality.ACCELEROMETER,
                                        DataModality.GYROSCOPE, DataModality.MAGNETOMETER]:
                    csv_file = output_path / f"{manifest.session_id}_{file_meta.device_id}_{file_meta.modality.value}.csv"
                    if self._export_sensor_data_csv(file_meta, csv_file):
                        result.output_files.append(str(csv_file))
                        
            logging.info(f"CSV export completed: {len(result.output_files)} files")
            
        except Exception as e:
            result.success = False
            result.error_message = f"CSV export failed: {e}"
            
        return result
        
    def _export_json(self, manifest: SessionManifest, files: List[FileMetadata],
                    config: ExportConfiguration) -> ExportResult:
        """Export data to JSON format."""
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            # Create comprehensive JSON export
            json_data = {
                'session_info': {
                    'session_id': manifest.session_id,
                    'session_name': manifest.session_name,
                    'participant_id': manifest.participant_id,
                    'start_timestamp': manifest.start_timestamp,
                    'end_timestamp': manifest.end_timestamp,
                    'duration_seconds': manifest.duration_seconds,
                    'created_timestamp': manifest.created_timestamp
                },
                'devices': [device.to_dict() for device in manifest.devices],
                'files': [file_meta.to_dict() for file_meta in files],
                'sync_events': manifest.sync_events,
                'sync_statistics': manifest.sync_statistics,
                'quality_metrics': manifest.quality_metrics,
                'export_info': {
                    'exported_at': datetime.now().isoformat(),
                    'export_format': config.format.value,
                    'file_count': len(files)
                }
            }
            
            # Add data if requested and feasible
            if config.include_raw_files and len(files) < 10:  # Limit for JSON size
                json_data['data'] = {}
                for file_meta in files:
                    if file_meta.modality in [DataModality.GSR, DataModality.PPG, 
                                            DataModality.HEART_RATE]:
                        data_key = f"{file_meta.device_id}_{file_meta.modality.value}"
                        file_data = self._load_file_data(file_meta)
                        if file_data is not None:
                            json_data['data'][data_key] = {
                                'values': file_data.tolist() if hasattr(file_data, 'tolist') else file_data,
                                'timestamps': self._generate_timestamps(file_meta).tolist() if hasattr(self._generate_timestamps(file_meta), 'tolist') else self._generate_timestamps(file_meta)
                            }
                            
            output_file = output_path / f"{manifest.session_id}_export.json"
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(json_data, f, indent=2, default=str)
                
            result.output_files.append(str(output_file))
            logging.info(f"JSON export completed: {output_file}")
            
        except Exception as e:
            result.success = False
            result.error_message = f"JSON export failed: {e}"
            
        return result
        
    def _export_numpy(self, manifest: SessionManifest, files: List[FileMetadata],
                     config: ExportConfiguration) -> ExportResult:
        """Export data to NumPy format."""
        try:
            import numpy as np
        except ImportError:
            return ExportResult(success=False, 
                              error_message="NumPy required for NumPy export")
            
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            # Create data dictionary
            data_dict = {
                'session_id': manifest.session_id,
                'start_timestamp': manifest.start_timestamp,
                'end_timestamp': manifest.end_timestamp or 0,
                'duration_seconds': manifest.duration_seconds or 0
            }
            
            # Process each file
            for file_meta in files:
                data_key = f"{file_meta.device_id}_{file_meta.modality.value}"
                file_data = self._load_file_data(file_meta)
                if file_data is not None:
                    data_dict[f"{data_key}_data"] = file_data
                    data_dict[f"{data_key}_timestamps"] = self._generate_timestamps(file_meta)
                    
            # Save as compressed NumPy archive
            output_file = output_path / f"{manifest.session_id}_data.npz"
            np.savez_compressed(str(output_file), **data_dict)
            
            result.output_files.append(str(output_file))
            logging.info(f"NumPy export completed: {output_file}")
            
        except Exception as e:
            result.success = False
            result.error_message = f"NumPy export failed: {e}"
            
        return result
        
    def _export_parquet(self, manifest: SessionManifest, files: List[FileMetadata],
                       config: ExportConfiguration) -> ExportResult:
        """Export data to Parquet format."""
        try:
            import pandas as pd
            import pyarrow as pa
            import pyarrow.parquet as pq
        except ImportError:
            return ExportResult(success=False, 
                              error_message="pandas and pyarrow required for Parquet export")
            
        result = ExportResult(success=True)
        output_path = Path(config.output_directory)
        
        try:
            # Create session metadata DataFrame
            session_df = pd.DataFrame([{
                'session_id': manifest.session_id,
                'session_name': manifest.session_name,
                'participant_id': manifest.participant_id,
                'start_timestamp': manifest.start_timestamp,
                'end_timestamp': manifest.end_timestamp,
                'duration_seconds': manifest.duration_seconds
            }])
            
            session_file = output_path / f"{manifest.session_id}_session.parquet"
            session_df.to_parquet(str(session_file))
            result.output_files.append(str(session_file))
            
            # Export sensor data as separate Parquet files
            for file_meta in files:
                if file_meta.modality in [DataModality.GSR, DataModality.PPG, 
                                        DataModality.HEART_RATE, DataModality.ACCELEROMETER,
                                        DataModality.GYROSCOPE, DataModality.MAGNETOMETER]:
                    parquet_file = output_path / f"{manifest.session_id}_{file_meta.device_id}_{file_meta.modality.value}.parquet"
                    if self._export_sensor_data_parquet(file_meta, parquet_file):
                        result.output_files.append(str(parquet_file))
                        
            logging.info(f"Parquet export completed: {len(result.output_files)} files")
            
        except Exception as e:
            result.success = False
            result.error_message = f"Parquet export failed: {e}"
            
        return result
        
    def _export_eeglab(self, manifest: SessionManifest, files: List[FileMetadata],
                      config: ExportConfiguration) -> ExportResult:
        """Export data to EEGLAB format (for EEG-like data)."""
        result = ExportResult(success=False)
        result.error_message = "EEGLAB export not yet implemented"
        return result
        
    def _export_bids(self, manifest: SessionManifest, files: List[FileMetadata],
                    config: ExportConfiguration) -> ExportResult:
        """Export data to BIDS format."""
        result = ExportResult(success=False)
        result.error_message = "BIDS export not yet implemented"
        return result
        
    def _load_file_data(self, file_meta: FileMetadata):
        """Load data from file based on modality."""
        file_path = Path(file_meta.file_path)
        
        if not file_path.exists():
            return None
            
        try:
            if file_meta.modality in [DataModality.GSR, DataModality.PPG, 
                                    DataModality.HEART_RATE, DataModality.ACCELEROMETER,
                                    DataModality.GYROSCOPE, DataModality.MAGNETOMETER]:
                # Load CSV sensor data
                import pandas as pd
                df = pd.read_csv(file_path)
                return df.values if len(df.columns) > 1 else df.iloc[:, 0].values
                
            elif file_meta.modality == DataModality.AUDIO:
                # Load audio data (placeholder)
                return None  # Would need audio processing library
                
            elif file_meta.modality in [DataModality.RGB_VIDEO, DataModality.THERMAL_VIDEO]:
                # Video data not suitable for most export formats
                return None
                
        except Exception as e:
            logging.error(f"Failed to load data from {file_path}: {e}")
            
        return None
        
    def _generate_timestamps(self, file_meta: FileMetadata):
        """Generate timestamp array for file data."""
        try:
            import numpy as np
            
            if file_meta.sample_rate and file_meta.duration_seconds:
                num_samples = int(file_meta.sample_rate * file_meta.duration_seconds)
                return np.linspace(file_meta.start_timestamp, 
                                 file_meta.end_timestamp, 
                                 num_samples)
            else:
                # Return start and end timestamps
                return np.array([file_meta.start_timestamp, file_meta.end_timestamp])
                
        except Exception:
            return None
            
    def _export_sensor_data_csv(self, file_meta: FileMetadata, output_file: Path) -> bool:
        """Export sensor data to CSV format."""
        try:
            data = self._load_file_data(file_meta)
            timestamps = self._generate_timestamps(file_meta)
            
            if data is None or timestamps is None:
                return False
                
            with open(output_file, 'w', newline='', encoding='utf-8') as f:
                writer = csv.writer(f)
                writer.writerow(['timestamp', 'value'])
                
                # Handle different data shapes
                if len(data.shape) == 1:
                    for t, v in zip(timestamps, data):
                        writer.writerow([t, v])
                else:
                    # Multi-channel data
                    header = ['timestamp'] + [f'channel_{i}' for i in range(data.shape[1])]
                    writer = csv.writer(f)
                    writer.writerow(header)
                    for i, t in enumerate(timestamps):
                        if i < len(data):
                            row = [t] + list(data[i])
                            writer.writerow(row)
                            
            return True
            
        except Exception as e:
            logging.error(f"Failed to export sensor data to CSV: {e}")
            return False
            
    def _export_sensor_data_parquet(self, file_meta: FileMetadata, output_file: Path) -> bool:
        """Export sensor data to Parquet format."""
        try:
            import pandas as pd
            
            data = self._load_file_data(file_meta)
            timestamps = self._generate_timestamps(file_meta)
            
            if data is None or timestamps is None:
                return False
                
            # Create DataFrame
            if len(data.shape) == 1:
                df = pd.DataFrame({
                    'timestamp': timestamps,
                    'value': data
                })
            else:
                # Multi-channel data
                df_dict = {'timestamp': timestamps}
                for i in range(data.shape[1]):
                    df_dict[f'channel_{i}'] = data[:, i]
                df = pd.DataFrame(df_dict)
                
            df.to_parquet(str(output_file))
            return True
            
        except Exception as e:
            logging.error(f"Failed to export sensor data to Parquet: {e}")
            return False
            
    def get_supported_formats(self) -> List[ExportFormat]:
        """Get list of supported export formats."""
        return list(self.supported_formats.keys())
        
    def create_export_config(self, format: ExportFormat, output_dir: str, 
                           **kwargs) -> ExportConfiguration:
        """Create export configuration with defaults."""
        return ExportConfiguration(
            format=format,
            output_directory=output_dir,
            **kwargs
        )