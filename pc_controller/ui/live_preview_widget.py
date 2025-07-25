"""
Live Video Preview Widget for PC Controller Application.
Provides real-time preview of video streams from connected devices.
"""

import logging
import cv2
import numpy as np
from typing import Dict, List, Optional, Any
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QLabel, 
    QGroupBox, QPushButton, QComboBox, QCheckBox, QSlider,
    QFrame, QScrollArea
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QThread, pyqtSlot
from PyQt6.QtGui import QPixmap, QImage, QFont

from utils.config import Config
from core.device_manager import DeviceManager, AndroidDevice


class VideoStreamThread(QThread):
    """Thread for handling video stream processing with optimizations."""
    
    frame_ready = pyqtSignal(str, np.ndarray)  # device_id, frame
    
    def __init__(self, device_id: str, stream_url: str):
        super().__init__()
        self.device_id = device_id
        self.stream_url = stream_url
        self.running = False
        self.cap = None
        self.target_fps = 15
        self.frame_skip_count = 0
        self.max_frame_skip = 2  # Skip frames if processing is slow
        self.last_frame_time = 0
        
    def run(self):
        """Main thread loop for video capture with optimizations."""
        try:
            self.cap = cv2.VideoCapture(self.stream_url)
            if not self.cap.isOpened():
                logging.error(f"Failed to open video stream: {self.stream_url}")
                return
            
            # Set optimized capture properties
            self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 320)
            self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 240)
            self.cap.set(cv2.CAP_PROP_FPS, self.target_fps)
            self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # Reduce buffer to minimize latency
            
            self.running = True
            frame_interval = 1000 // self.target_fps  # ms between frames
            
            while self.running:
                current_time = self.elapsed()
                
                ret, frame = self.cap.read()
                if ret:
                    # Implement frame skipping for performance
                    if self.frame_skip_count > 0:
                        self.frame_skip_count -= 1
                        continue
                    
                    # Only resize if frame is not already the target size
                    if frame.shape[:2] != (240, 320):
                        frame = cv2.resize(frame, (320, 240), interpolation=cv2.INTER_LINEAR)
                    
                    self.frame_ready.emit(self.device_id, frame)
                    
                    # Adaptive frame rate control
                    processing_time = self.elapsed() - current_time
                    if processing_time > frame_interval:
                        # If processing is slow, skip next frame
                        self.frame_skip_count = min(self.max_frame_skip, 
                                                  int(processing_time / frame_interval))
                else:
                    logging.warning(f"Failed to read frame from {self.device_id}")
                    break
                
                # Dynamic sleep based on processing time
                elapsed = self.elapsed() - current_time
                sleep_time = max(1, frame_interval - elapsed)
                self.msleep(int(sleep_time))
                
        except Exception as e:
            logging.error(f"Video stream error for {self.device_id}: {e}")
        finally:
            if self.cap:
                self.cap.release()
    
    def stop(self):
        """Stop the video stream."""
        self.running = False
        self.wait()


class DevicePreviewWidget(QWidget):
    """Widget for displaying preview from a single device."""
    
    def __init__(self, device: AndroidDevice, parent=None):
        super().__init__(parent)
        self.device = device
        self.stream_thread = None
        self.setup_ui()
        
    def setup_ui(self):
        """Setup the device preview UI."""
        layout = QVBoxLayout(self)
        
        # Device info header
        header_layout = QHBoxLayout()
        
        self.device_label = QLabel(f"{self.device.name} ({self.device.device_id})")
        self.device_label.setFont(QFont("Arial", 10, QFont.Weight.Bold))
        header_layout.addWidget(self.device_label)
        
        header_layout.addStretch()
        
        # Stream type selector
        self.stream_type_combo = QComboBox()
        self.stream_type_combo.addItems(["RGB Camera", "Thermal Camera"])
        self.stream_type_combo.currentTextChanged.connect(self.on_stream_type_changed)
        header_layout.addWidget(self.stream_type_combo)
        
        # Enable/disable checkbox
        self.enable_checkbox = QCheckBox("Enable Preview")
        self.enable_checkbox.toggled.connect(self.on_enable_toggled)
        header_layout.addWidget(self.enable_checkbox)
        
        layout.addLayout(header_layout)
        
        # Video display area
        self.video_frame = QFrame()
        self.video_frame.setFrameStyle(QFrame.Shape.Box)
        self.video_frame.setMinimumSize(320, 240)
        self.video_frame.setMaximumSize(320, 240)
        self.video_frame.setStyleSheet("background-color: black;")
        
        video_layout = QVBoxLayout(self.video_frame)
        
        self.video_label = QLabel("No Preview")
        self.video_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.video_label.setStyleSheet("color: white; font-size: 12px;")
        video_layout.addWidget(self.video_label)
        
        layout.addWidget(self.video_frame)
        
        # Status info
        self.status_label = QLabel("Disconnected")
        self.status_label.setFont(QFont("Arial", 9))
        self.status_label.setStyleSheet("color: red;")
        layout.addWidget(self.status_label)
        
        # Quality controls
        quality_layout = QHBoxLayout()
        
        quality_layout.addWidget(QLabel("Quality:"))
        self.quality_slider = QSlider(Qt.Orientation.Horizontal)
        self.quality_slider.setRange(1, 5)
        self.quality_slider.setValue(2)  # Low quality by default
        self.quality_slider.valueChanged.connect(self.on_quality_changed)
        quality_layout.addWidget(self.quality_slider)
        
        self.quality_label = QLabel("Low")
        quality_layout.addWidget(self.quality_label)
        
        layout.addLayout(quality_layout)
    
    def on_stream_type_changed(self, stream_type: str):
        """Handle stream type change."""
        logging.info(f"Stream type changed to {stream_type} for device {self.device.device_id}")
        if self.enable_checkbox.isChecked():
            self.restart_stream()
    
    def on_enable_toggled(self, enabled: bool):
        """Handle enable/disable toggle."""
        if enabled:
            self.start_stream()
        else:
            self.stop_stream()
    
    def on_quality_changed(self, value: int):
        """Handle quality slider change."""
        quality_names = ["", "Very Low", "Low", "Medium", "High", "Very High"]
        self.quality_label.setText(quality_names[value])
        
        if self.stream_thread and self.stream_thread.isRunning():
            # Restart stream with new quality
            self.restart_stream()
    
    def start_stream(self):
        """Start video stream from device."""
        if self.stream_thread and self.stream_thread.isRunning():
            return
        
        # Determine stream URL based on device and stream type
        stream_type = self.stream_type_combo.currentText()
        stream_url = self.get_stream_url(stream_type)
        
        if stream_url:
            self.stream_thread = VideoStreamThread(self.device.device_id, stream_url)
            self.stream_thread.frame_ready.connect(self.on_frame_received)
            self.stream_thread.start()
            
            self.status_label.setText("Connecting...")
            self.status_label.setStyleSheet("color: orange;")
            logging.info(f"Started video stream for {self.device.device_id}")
        else:
            self.status_label.setText("Stream not available")
            self.status_label.setStyleSheet("color: red;")
    
    def stop_stream(self):
        """Stop video stream."""
        if self.stream_thread:
            self.stream_thread.stop()
            self.stream_thread = None
        
        self.video_label.setText("No Preview")
        self.status_label.setText("Disconnected")
        self.status_label.setStyleSheet("color: red;")
        logging.info(f"Stopped video stream for {self.device.device_id}")
    
    def restart_stream(self):
        """Restart video stream with current settings."""
        self.stop_stream()
        if self.enable_checkbox.isChecked():
            self.start_stream()
    
    def get_stream_url(self, stream_type: str) -> Optional[str]:
        """Get stream URL for the specified stream type."""
        # This would be implemented based on the actual device communication protocol
        # For now, return a placeholder URL
        base_url = f"http://{self.device.ip_address}:8080"
        
        if stream_type == "RGB Camera":
            return f"{base_url}/video_rgb"
        elif stream_type == "Thermal Camera":
            return f"{base_url}/video_thermal"
        
        return None
    
    @pyqtSlot(str, np.ndarray)
    def on_frame_received(self, device_id: str, frame: np.ndarray):
        """Handle received video frame."""
        if device_id != self.device.device_id:
            return
        
        try:
            # Convert frame to Qt format
            height, width, channel = frame.shape
            bytes_per_line = 3 * width
            
            # Convert BGR to RGB
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            
            # Create QImage
            qt_image = QImage(rgb_frame.data, width, height, bytes_per_line, QImage.Format.Format_RGB888)
            
            # Create QPixmap and display
            pixmap = QPixmap.fromImage(qt_image)
            self.video_label.setPixmap(pixmap.scaled(
                self.video_label.size(), 
                Qt.AspectRatioMode.KeepAspectRatio, 
                Qt.TransformationMode.SmoothTransformation
            ))
            
            # Update status
            if self.status_label.text() != "Connected":
                self.status_label.setText("Connected")
                self.status_label.setStyleSheet("color: green;")
                
        except Exception as e:
            logging.error(f"Error displaying frame for {device_id}: {e}")
    
    def update_device(self, device: AndroidDevice):
        """Update device information."""
        self.device = device
        self.device_label.setText(f"{device.name} ({device.device_id})")
        
        # Restart stream if settings changed
        if self.enable_checkbox.isChecked():
            self.restart_stream()


class LivePreviewWidget(QWidget):
    """Main widget for live video preview from multiple devices."""
    
    # Signals
    preview_started = pyqtSignal(str)  # device_id
    preview_stopped = pyqtSignal(str)  # device_id
    
    def __init__(self, config: Config, device_manager: DeviceManager, parent=None):
        super().__init__(parent)
        self.config = config
        self.device_manager = device_manager
        self.device_previews: Dict[str, DevicePreviewWidget] = {}
        
        self.setup_ui()
        self.connect_signals()
        
        logging.info("Live Preview Widget initialized")
    
    def setup_ui(self):
        """Setup the live preview UI."""
        layout = QVBoxLayout(self)
        
        # Header controls
        header_group = QGroupBox("Live Video Preview Controls")
        header_layout = QHBoxLayout(header_group)
        
        self.enable_all_button = QPushButton("Enable All")
        self.enable_all_button.clicked.connect(self.enable_all_previews)
        header_layout.addWidget(self.enable_all_button)
        
        self.disable_all_button = QPushButton("Disable All")
        self.disable_all_button.clicked.connect(self.disable_all_previews)
        header_layout.addWidget(self.disable_all_button)
        
        header_layout.addStretch()
        
        # Global quality control
        header_layout.addWidget(QLabel("Global Quality:"))
        self.global_quality_slider = QSlider(Qt.Orientation.Horizontal)
        self.global_quality_slider.setRange(1, 5)
        self.global_quality_slider.setValue(2)
        self.global_quality_slider.valueChanged.connect(self.on_global_quality_changed)
        header_layout.addWidget(self.global_quality_slider)
        
        layout.addWidget(header_group)
        
        # Scroll area for device previews
        scroll_area = QScrollArea()
        scroll_area.setWidgetResizable(True)
        scroll_area.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        scroll_area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        
        self.preview_container = QWidget()
        self.preview_layout = QGridLayout(self.preview_container)
        self.preview_layout.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)
        
        scroll_area.setWidget(self.preview_container)
        layout.addWidget(scroll_area)
        
        # Status info
        self.status_label = QLabel("No devices connected")
        self.status_label.setFont(QFont("Arial", 10))
        layout.addWidget(self.status_label)
    
    def connect_signals(self):
        """Connect device manager signals."""
        self.device_manager.device_connected.connect(self.on_device_connected)
        self.device_manager.device_disconnected.connect(self.on_device_disconnected)
    
    @pyqtSlot(str)
    def on_device_connected(self, device_id: str):
        """Handle device connected."""
        device = self.device_manager.get_device(device_id)
        if device:
            self.add_device_preview(device)
    
    @pyqtSlot(str)
    def on_device_disconnected(self, device_id: str):
        """Handle device disconnected."""
        self.remove_device_preview(device_id)
    
    def add_device_preview(self, device: AndroidDevice):
        """Add preview widget for a device."""
        if device.device_id in self.device_previews:
            return
        
        preview_widget = DevicePreviewWidget(device)
        self.device_previews[device.device_id] = preview_widget
        
        # Add to grid layout
        row = len(self.device_previews) // 3
        col = (len(self.device_previews) - 1) % 3
        self.preview_layout.addWidget(preview_widget, row, col)
        
        self.update_status()
        logging.info(f"Added preview for device: {device.device_id}")
    
    def remove_device_preview(self, device_id: str):
        """Remove preview widget for a device."""
        if device_id not in self.device_previews:
            return
        
        preview_widget = self.device_previews[device_id]
        preview_widget.stop_stream()
        preview_widget.setParent(None)
        del self.device_previews[device_id]
        
        # Reorganize grid layout
        self.reorganize_previews()
        self.update_status()
        logging.info(f"Removed preview for device: {device_id}")
    
    def reorganize_previews(self):
        """Reorganize preview widgets in grid layout."""
        # Clear layout
        for i in reversed(range(self.preview_layout.count())):
            self.preview_layout.itemAt(i).widget().setParent(None)
        
        # Re-add widgets
        for i, preview_widget in enumerate(self.device_previews.values()):
            row = i // 3
            col = i % 3
            self.preview_layout.addWidget(preview_widget, row, col)
    
    def enable_all_previews(self):
        """Enable preview for all connected devices."""
        for preview_widget in self.device_previews.values():
            preview_widget.enable_checkbox.setChecked(True)
        
        logging.info("Enabled all device previews")
    
    def disable_all_previews(self):
        """Disable preview for all connected devices."""
        for preview_widget in self.device_previews.values():
            preview_widget.enable_checkbox.setChecked(False)
        
        logging.info("Disabled all device previews")
    
    def on_global_quality_changed(self, value: int):
        """Handle global quality change."""
        for preview_widget in self.device_previews.values():
            preview_widget.quality_slider.setValue(value)
    
    def update_status(self):
        """Update status label."""
        device_count = len(self.device_previews)
        active_count = sum(1 for widget in self.device_previews.values() 
                          if widget.enable_checkbox.isChecked())
        
        if device_count == 0:
            self.status_label.setText("No devices connected")
        else:
            self.status_label.setText(f"{device_count} devices connected, {active_count} previews active")
    
    def refresh_devices(self):
        """Refresh device list and previews."""
        # Get current devices from device manager
        current_devices = {device.device_id: device for device in self.device_manager.get_all_devices()}
        
        # Remove previews for disconnected devices
        for device_id in list(self.device_previews.keys()):
            if device_id not in current_devices:
                self.remove_device_preview(device_id)
        
        # Add previews for new devices
        for device_id, device in current_devices.items():
            if device_id not in self.device_previews:
                self.add_device_preview(device)
        
        # Update existing previews
        for device_id, device in current_devices.items():
            if device_id in self.device_previews:
                self.device_previews[device_id].update_device(device)
        
        logging.info("Refreshed device previews")
    
    def cleanup(self):
        """Cleanup resources."""
        for preview_widget in self.device_previews.values():
            preview_widget.stop_stream()
        
        self.device_previews.clear()
        logging.info("Live preview widget cleaned up")