#!/usr/bin/env python3
"""
File Aggregation Utility for PC Controller Application
Collects data files from connected Android devices and organizes them according to session manifest.
"""

import os
import shutil
import logging
import asyncio
import hashlib
from typing import Dict, Any, Optional, List, Tuple, Callable
from datetime import datetime
from pathlib import Path
from dataclasses import dataclass, field
from enum import Enum
from PyQt6.QtCore import QObject, pyqtSignal, QThread, QTimer

from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from core.recording_controller import RecordingController, SessionInfo
from data.session_manifest import SessionManifestGenerator, DataModality, FileMetadata


class TransferStatus(Enum):
    """File transfer status."""
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class TransferMethod(Enum):
    """File transfer methods."""
    WIFI_HTTP = "wifi_http"
    WIFI_FTP = "wifi_ftp"
    USB_ADB = "usb_adb"
    BLUETOOTH = "bluetooth"
    MANUAL = "manual"


@dataclass
class FileTransferTask:
    """Represents a file transfer task."""
    task_id: str
    device_id: str
    remote_path: str
    local_path: str
    file_size: int
    modality: DataModality
    transfer_method: TransferMethod
    status: TransferStatus = TransferStatus.PENDING
    progress: float = 0.0
    start_time: Optional[float] = None
    end_time: Optional[float] = None
    error_message: str = ""
    checksum_remote: str = ""
    checksum_local: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    @property
    def transfer_rate_mbps(self) -> float:
        """Calculate transfer rate in MB/s."""
        if not self.start_time or not self.end_time or self.end_time <= self.start_time:
            return 0.0
        duration = self.end_time - self.start_time
        return (self.file_size / (1024 * 1024)) / duration
        
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            'task_id': self.task_id,
            'device_id': self.device_id,
            'remote_path': self.remote_path,
            'local_path': self.local_path,
            'file_size': self.file_size,
            'modality': self.modality.value,
            'transfer_method': self.transfer_method.value,
            'status': self.status.value,
            'progress': self.progress,
            'start_time': self.start_time,
            'end_time': self.end_time,
            'error_message': self.error_message,
            'transfer_rate_mbps': self.transfer_rate_mbps,
            'metadata': self.metadata
        }


class FileTransferWorker(QThread):
    """Worker thread for file transfers."""
    
    # Signals
    progress_updated = pyqtSignal(str, float)  # task_id, progress
    transfer_completed = pyqtSignal(str, bool, str)  # task_id, success, message
    
    def __init__(self, task: FileTransferTask, parent=None):
        super().__init__(parent)
        self.task = task
        self.cancelled = False
        
    def run(self):
        """Execute the file transfer."""
        try:
            self.task.start_time = time.time()
            self.task.status = TransferStatus.IN_PROGRESS
            
            if self.task.transfer_method == TransferMethod.WIFI_HTTP:
                success = self.transfer_via_http()
            elif self.task.transfer_method == TransferMethod.USB_ADB:
                success = self.transfer_via_adb()
            elif self.task.transfer_method == TransferMethod.WIFI_FTP:
                success = self.transfer_via_ftp()
            else:
                success = False
                self.task.error_message = f"Unsupported transfer method: {self.task.transfer_method}"
                
            self.task.end_time = time.time()
            
            if success and not self.cancelled:
                # Verify file integrity
                if self.verify_file_integrity():
                    self.task.status = TransferStatus.COMPLETED
                    self.transfer_completed.emit(self.task.task_id, True, "Transfer completed successfully")
                else:
                    self.task.status = TransferStatus.FAILED
                    self.task.error_message = "File integrity verification failed"
                    self.transfer_completed.emit(self.task.task_id, False, self.task.error_message)
            elif self.cancelled:
                self.task.status = TransferStatus.CANCELLED
                self.transfer_completed.emit(self.task.task_id, False, "Transfer cancelled")
            else:
                self.task.status = TransferStatus.FAILED
                self.transfer_completed.emit(self.task.task_id, False, self.task.error_message)
                
        except Exception as e:
            self.task.status = TransferStatus.FAILED
            self.task.error_message = str(e)
            self.task.end_time = time.time()
            self.transfer_completed.emit(self.task.task_id, False, str(e))
            
    def transfer_via_http(self) -> bool:
        """Transfer file via HTTP."""
        import requests
        
        try:
            # Construct HTTP URL (assuming Android app provides HTTP server)
            device = self.get_device_info()
            if not device:
                self.task.error_message = "Device not found"
                return False
                
            url = f"http://{device.ip_address}:8080/files{self.task.remote_path}"
            
            # Create local directory
            local_path = Path(self.task.local_path)
            local_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Download file with progress tracking
            response = requests.get(url, stream=True, timeout=30)
            response.raise_for_status()
            
            total_size = int(response.headers.get('content-length', 0))
            if total_size != self.task.file_size:
                logging.warning(f"File size mismatch: expected {self.task.file_size}, got {total_size}")
                
            downloaded = 0
            with open(local_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if self.cancelled:
                        return False
                        
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        
                        if total_size > 0:
                            progress = (downloaded / total_size) * 100
                            self.task.progress = progress
                            self.progress_updated.emit(self.task.task_id, progress)
                            
            return True
            
        except Exception as e:
            self.task.error_message = f"HTTP transfer failed: {e}"
            logging.error(f"HTTP transfer failed for {self.task.task_id}: {e}")
            return False
            
    def transfer_via_adb(self) -> bool:
        """Transfer file via ADB."""
        import subprocess
        
        try:
            # Create local directory
            local_path = Path(self.task.local_path)
            local_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Use ADB to pull file
            cmd = [
                'adb', '-s', self.task.device_id,
                'pull', self.task.remote_path, str(local_path)
            ]
            
            process = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
            )
            
            # Monitor progress (ADB doesn't provide detailed progress, so we estimate)
            while process.poll() is None:
                if self.cancelled:
                    process.terminate()
                    return False
                    
                # Estimate progress based on elapsed time (rough approximation)
                if self.task.start_time:
                    elapsed = time.time() - self.task.start_time
                    # Assume 10MB/s transfer rate for estimation
                    estimated_progress = min(95, (elapsed * 10 * 1024 * 1024 / self.task.file_size) * 100)
                    self.task.progress = estimated_progress
                    self.progress_updated.emit(self.task.task_id, estimated_progress)
                    
                time.sleep(0.5)
                
            stdout, stderr = process.communicate()
            
            if process.returncode == 0:
                self.task.progress = 100.0
                self.progress_updated.emit(self.task.task_id, 100.0)
                return True
            else:
                self.task.error_message = f"ADB pull failed: {stderr}"
                return False
                
        except Exception as e:
            self.task.error_message = f"ADB transfer failed: {e}"
            logging.error(f"ADB transfer failed for {self.task.task_id}: {e}")
            return False
            
    def transfer_via_ftp(self) -> bool:
        """Transfer file via FTP."""
        try:
            from ftplib import FTP
            
            device = self.get_device_info()
            if not device:
                self.task.error_message = "Device not found"
                return False
                
            # Create local directory
            local_path = Path(self.task.local_path)
            local_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Connect to FTP server on device
            ftp = FTP()
            ftp.connect(device.ip_address, 2121)  # Assuming FTP server on port 2121
            ftp.login('anonymous', '')  # Anonymous login
            
            # Download file with progress tracking
            downloaded = 0
            
            def write_callback(data):
                nonlocal downloaded
                if self.cancelled:
                    raise Exception("Transfer cancelled")
                    
                f.write(data)
                downloaded += len(data)
                
                if self.task.file_size > 0:
                    progress = (downloaded / self.task.file_size) * 100
                    self.task.progress = progress
                    self.progress_updated.emit(self.task.task_id, progress)
                    
            with open(local_path, 'wb') as f:
                ftp.retrbinary(f'RETR {self.task.remote_path}', write_callback)
                
            ftp.quit()
            return True
            
        except Exception as e:
            self.task.error_message = f"FTP transfer failed: {e}"
            logging.error(f"FTP transfer failed for {self.task.task_id}: {e}")
            return False
            
    def verify_file_integrity(self) -> bool:
        """Verify file integrity using checksums."""
        try:
            if not self.task.checksum_remote:
                # No remote checksum to compare against
                return True
                
            local_path = Path(self.task.local_path)
            if not local_path.exists():
                return False
                
            # Calculate local file checksum
            self.task.checksum_local = self.calculate_checksum(str(local_path))
            
            # Compare checksums
            return self.task.checksum_local == self.task.checksum_remote
            
        except Exception as e:
            logging.error(f"File integrity verification failed: {e}")
            return False
            
    def calculate_checksum(self, file_path: str) -> str:
        """Calculate MD5 checksum of file."""
        hash_md5 = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                hash_md5.update(chunk)
        return hash_md5.hexdigest()
        
    def get_device_info(self) -> Optional[AndroidDevice]:
        """Get device information from device manager."""
        # This would need access to device manager
        # For now, return None - would be injected in real implementation
        return None
        
    def cancel(self):
        """Cancel the transfer."""
        self.cancelled = True


class FileAggregator(QObject):
    """Main file aggregation system."""
    
    # Signals
    transfer_started = pyqtSignal(str)  # task_id
    transfer_progress = pyqtSignal(str, float)  # task_id, progress
    transfer_completed = pyqtSignal(str, bool, str)  # task_id, success, message
    aggregation_completed = pyqtSignal(str, dict)  # session_id, summary
    
    def __init__(self, device_manager: DeviceManager, 
                 manifest_generator: SessionManifestGenerator,
                 output_directory: str = "aggregated_data"):
        super().__init__()
        self.device_manager = device_manager
        self.manifest_generator = manifest_generator
        self.output_directory = Path(output_directory)
        self.output_directory.mkdir(parents=True, exist_ok=True)
        
        # Transfer management
        self.active_transfers = {}  # task_id -> FileTransferWorker
        self.transfer_queue = []  # List of FileTransferTask
        self.completed_transfers = {}  # task_id -> FileTransferTask
        self.max_concurrent_transfers = 3
        
        # Session management
        self.current_session_id = None
        self.session_directory = None
        
        # Statistics
        self.transfer_statistics = {
            'total_files': 0,
            'completed_files': 0,
            'failed_files': 0,
            'total_bytes': 0,
            'transferred_bytes': 0,
            'average_speed_mbps': 0.0
        }
        
        logging.info("FileAggregator initialized")
        
    def start_session_aggregation(self, session_id: str, device_ids: List[str],
                                 transfer_method: TransferMethod = TransferMethod.WIFI_HTTP) -> bool:
        """Start aggregating files for a session."""
        try:
            self.current_session_id = session_id
            self.session_directory = self.output_directory / session_id
            self.session_directory.mkdir(parents=True, exist_ok=True)
            
            # Reset statistics
            self.transfer_statistics = {
                'total_files': 0,
                'completed_files': 0,
                'failed_files': 0,
                'total_bytes': 0,
                'transferred_bytes': 0,
                'average_speed_mbps': 0.0
            }
            
            # Discover files on each device
            total_tasks = 0
            for device_id in device_ids:
                tasks = self.discover_device_files(device_id, transfer_method)
                self.transfer_queue.extend(tasks)
                total_tasks += len(tasks)
                
            self.transfer_statistics['total_files'] = total_tasks
            
            # Start processing transfer queue
            self.process_transfer_queue()
            
            logging.info(f"Started session aggregation for {session_id} with {total_tasks} files")
            return True
            
        except Exception as e:
            logging.error(f"Failed to start session aggregation: {e}")
            return False
            
    def discover_device_files(self, device_id: str, transfer_method: TransferMethod) -> List[FileTransferTask]:
        """Discover files on a device that need to be transferred."""
        tasks = []
        
        try:
            device = self.device_manager.get_device(device_id)
            if not device or device.status != DeviceStatus.CONNECTED:
                logging.warning(f"Device {device_id} not connected for file discovery")
                return tasks
                
            # Get file list from device
            file_list = self.get_device_file_list(device_id)
            
            for file_info in file_list:
                # Create transfer task
                task_id = f"{device_id}_{len(tasks)}_{int(time.time())}"
                
                # Determine local path
                local_filename = self.generate_local_filename(
                    device_id, file_info['filename'], file_info['modality']
                )
                local_path = self.session_directory / local_filename
                
                # Create task
                task = FileTransferTask(
                    task_id=task_id,
                    device_id=device_id,
                    remote_path=file_info['path'],
                    local_path=str(local_path),
                    file_size=file_info['size'],
                    modality=DataModality(file_info['modality']),
                    transfer_method=transfer_method,
                    checksum_remote=file_info.get('checksum', ''),
                    metadata=file_info.get('metadata', {})
                )
                
                tasks.append(task)
                self.transfer_statistics['total_bytes'] += file_info['size']
                
        except Exception as e:
            logging.error(f"Failed to discover files on device {device_id}: {e}")
            
        return tasks
        
    def get_device_file_list(self, device_id: str) -> List[Dict[str, Any]]:
        """Get list of files from device."""
        # This would communicate with the Android app to get file list
        # For now, return mock data
        mock_files = [
            {
                'filename': f'{self.current_session_id}_rgb_video.mp4',
                'path': f'/storage/emulated/0/recordings/{self.current_session_id}_rgb_video.mp4',
                'size': 50 * 1024 * 1024,  # 50MB
                'modality': 'rgb_video',
                'checksum': 'abc123def456',
                'metadata': {'resolution': '1920x1080', 'fps': 30}
            },
            {
                'filename': f'{self.current_session_id}_thermal_video.raw',
                'path': f'/storage/emulated/0/recordings/{self.current_session_id}_thermal_video.raw',
                'size': 25 * 1024 * 1024,  # 25MB
                'modality': 'thermal_video',
                'checksum': 'def456ghi789',
                'metadata': {'resolution': '256x192', 'fps': 25}
            },
            {
                'filename': f'{self.current_session_id}_gsr_data.csv',
                'path': f'/storage/emulated/0/recordings/{self.current_session_id}_gsr_data.csv',
                'size': 1024 * 1024,  # 1MB
                'modality': 'gsr',
                'checksum': 'ghi789jkl012',
                'metadata': {'sample_rate': 128}
            },
            {
                'filename': f'{self.current_session_id}_audio.wav',
                'path': f'/storage/emulated/0/recordings/{self.current_session_id}_audio.wav',
                'size': 10 * 1024 * 1024,  # 10MB
                'modality': 'audio',
                'checksum': 'jkl012mno345',
                'metadata': {'sample_rate': 44100, 'channels': 2}
            }
        ]
        
        return mock_files
        
    def generate_local_filename(self, device_id: str, original_filename: str, modality: str) -> str:
        """Generate local filename with consistent naming convention."""
        # Extract file extension
        file_path = Path(original_filename)
        extension = file_path.suffix
        
        # Create standardized filename
        # Format: {session_id}_{device_id}_{modality}{extension}
        return f"{self.current_session_id}_{device_id}_{modality}{extension}"
        
    def process_transfer_queue(self):
        """Process the transfer queue."""
        while (len(self.active_transfers) < self.max_concurrent_transfers and 
               self.transfer_queue):
            
            task = self.transfer_queue.pop(0)
            self.start_transfer(task)
            
    def start_transfer(self, task: FileTransferTask):
        """Start a file transfer."""
        try:
            worker = FileTransferWorker(task)
            worker.progress_updated.connect(self.on_transfer_progress)
            worker.transfer_completed.connect(self.on_transfer_completed)
            
            self.active_transfers[task.task_id] = worker
            worker.start()
            
            self.transfer_started.emit(task.task_id)
            logging.info(f"Started transfer: {task.task_id}")
            
        except Exception as e:
            logging.error(f"Failed to start transfer {task.task_id}: {e}")
            task.status = TransferStatus.FAILED
            task.error_message = str(e)
            
    def on_transfer_progress(self, task_id: str, progress: float):
        """Handle transfer progress update."""
        self.transfer_progress.emit(task_id, progress)
        
    def on_transfer_completed(self, task_id: str, success: bool, message: str):
        """Handle transfer completion."""
        if task_id in self.active_transfers:
            worker = self.active_transfers[task_id]
            task = worker.task
            
            # Update statistics
            if success:
                self.transfer_statistics['completed_files'] += 1
                self.transfer_statistics['transferred_bytes'] += task.file_size
                
                # Add file to manifest
                self.add_file_to_manifest(task)
            else:
                self.transfer_statistics['failed_files'] += 1
                
            # Move to completed transfers
            self.completed_transfers[task_id] = task
            del self.active_transfers[task_id]
            
            # Clean up worker
            worker.deleteLater()
            
            self.transfer_completed.emit(task_id, success, message)
            
            # Process next item in queue
            self.process_transfer_queue()
            
            # Check if all transfers are complete
            if not self.active_transfers and not self.transfer_queue:
                self.finalize_aggregation()
                
    def add_file_to_manifest(self, task: FileTransferTask):
        """Add transferred file to session manifest."""
        try:
            # Create file metadata
            file_metadata = self.manifest_generator.add_file_metadata(
                file_path=task.local_path,
                modality=task.modality,
                device_id=task.device_id,
                start_timestamp=task.start_time or time.time(),
                end_timestamp=task.end_time or time.time(),
                checksum=task.checksum_local,
                metadata=task.metadata
            )
            
            logging.debug(f"Added file to manifest: {file_metadata.file_name}")
            
        except Exception as e:
            logging.error(f"Failed to add file to manifest: {e}")
            
    def finalize_aggregation(self):
        """Finalize the aggregation process."""
        try:
            # Calculate final statistics
            total_time = sum(
                (task.end_time - task.start_time) for task in self.completed_transfers.values()
                if task.start_time and task.end_time
            )
            
            if total_time > 0:
                total_mb = self.transfer_statistics['transferred_bytes'] / (1024 * 1024)
                self.transfer_statistics['average_speed_mbps'] = total_mb / total_time
                
            # Create aggregation summary
            summary = {
                'session_id': self.current_session_id,
                'total_files': self.transfer_statistics['total_files'],
                'completed_files': self.transfer_statistics['completed_files'],
                'failed_files': self.transfer_statistics['failed_files'],
                'total_size_mb': self.transfer_statistics['total_bytes'] / (1024 * 1024),
                'transferred_size_mb': self.transfer_statistics['transferred_bytes'] / (1024 * 1024),
                'average_speed_mbps': self.transfer_statistics['average_speed_mbps'],
                'success_rate': (self.transfer_statistics['completed_files'] / 
                               max(1, self.transfer_statistics['total_files'])) * 100,
                'output_directory': str(self.session_directory),
                'completed_at': datetime.now().isoformat()
            }
            
            # Save aggregation report
            self.save_aggregation_report(summary)
            
            # Emit completion signal
            self.aggregation_completed.emit(self.current_session_id, summary)
            
            logging.info(f"Aggregation completed for session {self.current_session_id}")
            logging.info(f"Success rate: {summary['success_rate']:.1f}% "
                        f"({summary['completed_files']}/{summary['total_files']} files)")
                        
        except Exception as e:
            logging.error(f"Failed to finalize aggregation: {e}")
            
    def save_aggregation_report(self, summary: Dict[str, Any]):
        """Save aggregation report to file."""
        try:
            report_path = self.session_directory / "aggregation_report.json"
            
            # Include detailed transfer information
            detailed_report = {
                'summary': summary,
                'transfers': [task.to_dict() for task in self.completed_transfers.values()],
                'failed_transfers': [
                    task.to_dict() for task in self.completed_transfers.values()
                    if task.status == TransferStatus.FAILED
                ]
            }
            
            with open(report_path, 'w', encoding='utf-8') as f:
                json.dump(detailed_report, f, indent=2, default=str)
                
            logging.info(f"Saved aggregation report to {report_path}")
            
        except Exception as e:
            logging.error(f"Failed to save aggregation report: {e}")
            
    def cancel_transfer(self, task_id: str) -> bool:
        """Cancel a specific transfer."""
        if task_id in self.active_transfers:
            worker = self.active_transfers[task_id]
            worker.cancel()
            return True
        return False
        
    def cancel_all_transfers(self):
        """Cancel all active transfers."""
        for worker in self.active_transfers.values():
            worker.cancel()
            
        # Clear transfer queue
        self.transfer_queue.clear()
        
    def get_transfer_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        """Get status of a specific transfer."""
        if task_id in self.active_transfers:
            return self.active_transfers[task_id].task.to_dict()
        elif task_id in self.completed_transfers:
            return self.completed_transfers[task_id].to_dict()
        return None
        
    def get_aggregation_statistics(self) -> Dict[str, Any]:
        """Get current aggregation statistics."""
        return self.transfer_statistics.copy()

    # Enhanced methods for improved file aggregation

    def start_coordinated_aggregation(self, session_id: str, device_ids: List[str], 
                                    coordination_info: Dict[str, Any] = None,
                                    transfer_method: TransferMethod = TransferMethod.WIFI_HTTP) -> bool:
        """Start coordinated aggregation across multiple devices with enhanced coordination."""
        try:
            logging.info(f"Starting coordinated aggregation for {len(device_ids)} devices")
            
            # Initialize enhanced session tracking
            self.current_session_id = session_id
            self.session_directory = Path(self.output_directory) / session_id
            self.session_directory.mkdir(parents=True, exist_ok=True)
            
            # Store coordination information
            if coordination_info:
                self._save_coordination_info(coordination_info)
            
            # Discover files from all devices in parallel
            discovery_tasks = []
            for device_id in device_ids:
                if device_id in self.device_manager.devices:
                    task = asyncio.create_task(
                        self._enhanced_device_discovery(device_id, transfer_method)
                    )
                    discovery_tasks.append((device_id, task))
            
            # Wait for all discoveries to complete
            if discovery_tasks:
                asyncio.run(self._wait_for_discoveries(discovery_tasks))
            
            # Optimize transfer order for efficiency
            self._optimize_transfer_queue()
            
            # Start parallel transfers with load balancing
            self._start_parallel_transfers()
            
            return True
            
        except Exception as e:
            logging.error(f"Failed to start coordinated aggregation: {e}")
            return False

    async def _enhanced_device_discovery(self, device_id: str, transfer_method: TransferMethod):
        """Enhanced device file discovery with better error handling."""
        try:
            device = self.device_manager.devices.get(device_id)
            if not device or device.status != DeviceStatus.CONNECTED:
                logging.warning(f"Device {device_id} not available for file discovery")
                return
            
            # Discover files with retry logic
            max_retries = 3
            for attempt in range(max_retries):
                try:
                    files = await self._discover_device_files_with_metadata(device_id, transfer_method)
                    
                    # Create transfer tasks
                    for file_info in files:
                        task = self._create_enhanced_transfer_task(device_id, file_info, transfer_method)
                        if task:
                            self.transfer_queue.append(task)
                    
                    logging.info(f"Discovered {len(files)} files from device {device_id}")
                    break
                    
                except Exception as e:
                    if attempt < max_retries - 1:
                        wait_time = (2 ** attempt) * 1.0  # Exponential backoff
                        logging.warning(f"Discovery attempt {attempt + 1} failed for {device_id}, retrying in {wait_time}s: {e}")
                        await asyncio.sleep(wait_time)
                    else:
                        logging.error(f"Failed to discover files from {device_id} after {max_retries} attempts: {e}")
                        
        except Exception as e:
            logging.error(f"Enhanced discovery failed for device {device_id}: {e}")

    async def _discover_device_files_with_metadata(self, device_id: str, transfer_method: TransferMethod) -> List[Dict[str, Any]]:
        """Discover device files with enhanced metadata collection."""
        files = []
        
        try:
            # Get basic file list
            basic_files = await self.get_device_file_list(device_id)
            
            # Enhance with metadata
            for file_path in basic_files:
                try:
                    # Determine modality from file path/extension
                    modality = self._determine_file_modality(file_path)
                    
                    # Get file metadata (size, timestamps, etc.)
                    metadata = await self._get_file_metadata(device_id, file_path, transfer_method)
                    
                    file_info = {
                        'path': file_path,
                        'modality': modality,
                        'metadata': metadata,
                        'size': metadata.get('size', 0),
                        'modified_time': metadata.get('modified_time', 0)
                    }
                    
                    files.append(file_info)
                    
                except Exception as e:
                    logging.warning(f"Failed to get metadata for {file_path}: {e}")
                    # Add file with basic info
                    files.append({
                        'path': file_path,
                        'modality': self._determine_file_modality(file_path),
                        'metadata': {},
                        'size': 0,
                        'modified_time': 0
                    })
            
        except Exception as e:
            logging.error(f"Failed to discover files with metadata from {device_id}: {e}")
        
        return files

    def _determine_file_modality(self, file_path: str) -> DataModality:
        """Determine data modality from file path."""
        file_path_lower = file_path.lower()
        
        if any(ext in file_path_lower for ext in ['.mp4', '.avi', '.mov']):
            if 'thermal' in file_path_lower:
                return DataModality.THERMAL_VIDEO
            else:
                return DataModality.RGB_VIDEO
        elif any(ext in file_path_lower for ext in ['.wav', '.mp3', '.aac']):
            return DataModality.AUDIO
        elif 'gsr' in file_path_lower or 'eda' in file_path_lower:
            return DataModality.GSR
        elif 'ppg' in file_path_lower:
            return DataModality.PPG
        elif 'accel' in file_path_lower:
            return DataModality.ACCELEROMETER
        elif 'gyro' in file_path_lower:
            return DataModality.GYROSCOPE
        elif 'temp' in file_path_lower:
            return DataModality.TEMPERATURE
        else:
            return DataModality.SYSTEM_LOGS

    async def _get_file_metadata(self, device_id: str, file_path: str, transfer_method: TransferMethod) -> Dict[str, Any]:
        """Get detailed file metadata from device."""
        metadata = {}
        
        try:
            # This would be implemented based on the transfer method
            # For now, return basic metadata structure
            metadata = {
                'size': 0,
                'modified_time': time.time(),
                'device_id': device_id,
                'original_path': file_path,
                'transfer_method': transfer_method.value
            }
            
        except Exception as e:
            logging.warning(f"Failed to get metadata for {file_path}: {e}")
        
        return metadata

    def _create_enhanced_transfer_task(self, device_id: str, file_info: Dict[str, Any], 
                                     transfer_method: TransferMethod) -> Optional[FileTransferTask]:
        """Create enhanced transfer task with better metadata."""
        try:
            local_filename = self.generate_local_filename(
                device_id, 
                Path(file_info['path']).name, 
                file_info['modality'].value
            )
            local_path = str(self.session_directory / local_filename)
            
            task = FileTransferTask(
                task_id=f"{device_id}_{int(time.time() * 1000)}_{len(self.transfer_queue)}",
                device_id=device_id,
                remote_path=file_info['path'],
                local_path=local_path,
                modality=file_info['modality'],
                transfer_method=transfer_method,
                file_size=file_info['size'],
                metadata=file_info['metadata']
            )
            
            return task
            
        except Exception as e:
            logging.error(f"Failed to create transfer task for {file_info['path']}: {e}")
            return None

    def _optimize_transfer_queue(self):
        """Optimize transfer queue for efficiency."""
        try:
            # Sort by priority: smaller files first, then by modality importance
            modality_priority = {
                DataModality.SYNC_EVENTS: 1,
                DataModality.SYSTEM_LOGS: 2,
                DataModality.GSR: 3,
                DataModality.PPG: 4,
                DataModality.ACCELEROMETER: 5,
                DataModality.GYROSCOPE: 6,
                DataModality.TEMPERATURE: 7,
                DataModality.AUDIO: 8,
                DataModality.RGB_VIDEO: 9,
                DataModality.THERMAL_VIDEO: 10
            }
            
            self.transfer_queue.sort(key=lambda task: (
                modality_priority.get(task.modality, 99),  # Priority by modality
                task.file_size or 0  # Then by file size (smaller first)
            ))
            
            logging.info(f"Optimized transfer queue with {len(self.transfer_queue)} tasks")
            
        except Exception as e:
            logging.error(f"Failed to optimize transfer queue: {e}")

    def _start_parallel_transfers(self):
        """Start parallel transfers with load balancing."""
        try:
            # Determine optimal number of parallel transfers
            max_parallel = min(len(self.transfer_queue), 4)  # Max 4 parallel transfers
            
            # Group transfers by device to avoid overwhelming single devices
            device_queues = {}
            for task in self.transfer_queue:
                if task.device_id not in device_queues:
                    device_queues[task.device_id] = []
                device_queues[task.device_id].append(task)
            
            # Start transfers with device load balancing
            active_count = 0
            device_iterators = {device_id: iter(queue) for device_id, queue in device_queues.items()}
            
            while active_count < max_parallel and device_iterators:
                # Round-robin through devices
                for device_id in list(device_iterators.keys()):
                    if active_count >= max_parallel:
                        break
                    
                    try:
                        task = next(device_iterators[device_id])
                        self.start_transfer(task)
                        active_count += 1
                    except StopIteration:
                        # No more tasks for this device
                        del device_iterators[device_id]
            
            logging.info(f"Started {active_count} parallel transfers across {len(device_queues)} devices")
            
        except Exception as e:
            logging.error(f"Failed to start parallel transfers: {e}")

    async def _wait_for_discoveries(self, discovery_tasks: List[Tuple[str, asyncio.Task]]):
        """Wait for all device discoveries to complete."""
        try:
            for device_id, task in discovery_tasks:
                try:
                    await task
                except Exception as e:
                    logging.error(f"Discovery failed for device {device_id}: {e}")
                    
        except Exception as e:
            logging.error(f"Error waiting for discoveries: {e}")

    def _save_coordination_info(self, coordination_info: Dict[str, Any]):
        """Save coordination information for the session."""
        try:
            coord_path = self.session_directory / "coordination_info.json"
            
            enhanced_info = {
                'coordination_info': coordination_info,
                'aggregation_start_time': datetime.now().isoformat(),
                'session_id': self.current_session_id
            }
            
            with open(coord_path, 'w', encoding='utf-8') as f:
                json.dump(enhanced_info, f, indent=2, default=str)
                
            logging.info(f"Saved coordination info to {coord_path}")
            
        except Exception as e:
            logging.error(f"Failed to save coordination info: {e}")

    def get_enhanced_statistics(self) -> Dict[str, Any]:
        """Get enhanced aggregation statistics with device breakdown."""
        try:
            base_stats = self.get_aggregation_statistics()
            
            # Add device-specific statistics
            device_stats = {}
            for task in list(self.completed_transfers.values()) + list(self.active_transfers.values()):
                device_id = task.device_id if hasattr(task, 'device_id') else getattr(task, 'task', {}).device_id
                
                if device_id not in device_stats:
                    device_stats[device_id] = {
                        'total_files': 0,
                        'completed_files': 0,
                        'failed_files': 0,
                        'total_bytes': 0,
                        'transferred_bytes': 0,
                        'modalities': set()
                    }
                
                stats = device_stats[device_id]
                task_obj = task if hasattr(task, 'device_id') else task.task
                
                stats['total_files'] += 1
                stats['total_bytes'] += task_obj.file_size or 0
                stats['modalities'].add(task_obj.modality.value if task_obj.modality else 'unknown')
                
                if hasattr(task_obj, 'status'):
                    if task_obj.status == TransferStatus.COMPLETED:
                        stats['completed_files'] += 1
                        stats['transferred_bytes'] += task_obj.file_size or 0
                    elif task_obj.status == TransferStatus.FAILED:
                        stats['failed_files'] += 1
            
            # Convert sets to lists for JSON serialization
            for device_id in device_stats:
                device_stats[device_id]['modalities'] = list(device_stats[device_id]['modalities'])
                device_stats[device_id]['success_rate'] = (
                    device_stats[device_id]['completed_files'] / 
                    max(1, device_stats[device_id]['total_files'])
                ) * 100
            
            enhanced_stats = {
                **base_stats,
                'device_statistics': device_stats,
                'total_devices': len(device_stats),
                'session_directory': str(self.session_directory) if hasattr(self, 'session_directory') else None
            }
            
            return enhanced_stats
            
        except Exception as e:
            logging.error(f"Failed to get enhanced statistics: {e}")
            return self.get_aggregation_statistics()

    def generate_comprehensive_report(self) -> Dict[str, Any]:
        """Generate comprehensive aggregation report with analysis."""
        try:
            stats = self.get_enhanced_statistics()
            
            # Analyze transfer performance
            performance_analysis = self._analyze_transfer_performance()
            
            # Generate recommendations
            recommendations = self._generate_transfer_recommendations(stats, performance_analysis)
            
            comprehensive_report = {
                'session_id': self.current_session_id,
                'aggregation_statistics': stats,
                'performance_analysis': performance_analysis,
                'recommendations': recommendations,
                'report_generated_at': datetime.now().isoformat()
            }
            
            # Save comprehensive report
            report_path = self.session_directory / "comprehensive_aggregation_report.json"
            with open(report_path, 'w', encoding='utf-8') as f:
                json.dump(comprehensive_report, f, indent=2, default=str)
            
            logging.info(f"Generated comprehensive report: {report_path}")
            return comprehensive_report
            
        except Exception as e:
            logging.error(f"Failed to generate comprehensive report: {e}")
            return {}

    def _analyze_transfer_performance(self) -> Dict[str, Any]:
        """Analyze transfer performance across devices and modalities."""
        analysis = {
            'average_transfer_speed_mbps': 0.0,
            'fastest_device': None,
            'slowest_device': None,
            'modality_performance': {},
            'transfer_method_performance': {},
            'error_patterns': []
        }
        
        try:
            device_speeds = {}
            modality_speeds = {}
            method_speeds = {}
            
            for task in self.completed_transfers.values():
                if task.status == TransferStatus.COMPLETED and task.transfer_rate_mbps > 0:
                    # Device performance
                    if task.device_id not in device_speeds:
                        device_speeds[task.device_id] = []
                    device_speeds[task.device_id].append(task.transfer_rate_mbps)
                    
                    # Modality performance
                    modality = task.modality.value if task.modality else 'unknown'
                    if modality not in modality_speeds:
                        modality_speeds[modality] = []
                    modality_speeds[modality].append(task.transfer_rate_mbps)
                    
                    # Transfer method performance
                    method = task.transfer_method.value if task.transfer_method else 'unknown'
                    if method not in method_speeds:
                        method_speeds[method] = []
                    method_speeds[method].append(task.transfer_rate_mbps)
            
            # Calculate averages and find extremes
            if device_speeds:
                device_averages = {device: sum(speeds)/len(speeds) for device, speeds in device_speeds.items()}
                analysis['fastest_device'] = max(device_averages, key=device_averages.get)
                analysis['slowest_device'] = min(device_averages, key=device_averages.get)
                analysis['average_transfer_speed_mbps'] = sum(device_averages.values()) / len(device_averages)
            
            # Modality performance analysis
            analysis['modality_performance'] = {
                modality: sum(speeds)/len(speeds) for modality, speeds in modality_speeds.items()
            }
            
            # Transfer method performance analysis
            analysis['transfer_method_performance'] = {
                method: sum(speeds)/len(speeds) for method, speeds in method_speeds.items()
            }
            
        except Exception as e:
            logging.error(f"Failed to analyze transfer performance: {e}")
        
        return analysis

    def _generate_transfer_recommendations(self, stats: Dict[str, Any], performance: Dict[str, Any]) -> List[str]:
        """Generate recommendations based on transfer statistics and performance."""
        recommendations = []
        
        try:
            # Overall success rate recommendations
            overall_success_rate = stats.get('success_rate', 0)
            if overall_success_rate < 90:
                recommendations.append("Consider improving network stability - success rate is below 90%")
            
            # Device-specific recommendations
            device_stats = stats.get('device_statistics', {})
            for device_id, device_stat in device_stats.items():
                if device_stat['success_rate'] < 80:
                    recommendations.append(f"Check connection stability for device {device_id} - success rate: {device_stat['success_rate']:.1f}%")
            
            # Performance recommendations
            avg_speed = performance.get('average_transfer_speed_mbps', 0)
            if avg_speed < 1.0:
                recommendations.append("Transfer speeds are low - consider using wired connections or improving WiFi signal")
            
            # Transfer method recommendations
            method_performance = performance.get('transfer_method_performance', {})
            if len(method_performance) > 1:
                best_method = max(method_performance, key=method_performance.get)
                recommendations.append(f"Consider using {best_method} for better transfer performance")
            
        except Exception as e:
            logging.error(f"Failed to generate recommendations: {e}")
        
        return recommendations

    def cleanup(self):
        """Cleanup resources."""
        self.cancel_all_transfers()
        
        # Wait for workers to finish
        for worker in list(self.active_transfers.values()):
            worker.wait(5000)  # Wait up to 5 seconds
            worker.deleteLater()
            
        self.active_transfers.clear()
        logging.info("FileAggregator cleaned up")