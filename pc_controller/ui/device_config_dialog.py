#!/usr/bin/env python3
"""
Device Configuration Dialog for PC Controller Application
Provides interface for configuring individual Android device settings.
"""

import logging
from typing import Dict, Any, Optional
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QGridLayout, QFormLayout,
    QPushButton, QLabel, QLineEdit, QSpinBox, QDoubleSpinBox,
    QComboBox, QCheckBox, QGroupBox, QTabWidget, QWidget,
    QMessageBox, QProgressBar, QTextEdit
)
from PyQt6.QtCore import Qt, pyqtSignal, QTimer
from PyQt6.QtGui import QFont

from core.device_manager import AndroidDevice, DeviceStatus


class DeviceConfigDialog(QDialog):
    """Dialog for configuring Android device settings."""
    
    # Signals
    config_updated = pyqtSignal(str, dict)  # device_id, config
    
    def __init__(self, device: AndroidDevice, parent=None):
        super().__init__(parent)
        self.device = device
        self.config_data = {}
        self.setup_ui()
        self.load_device_config()
        
    def setup_ui(self):
        """Setup the user interface."""
        self.setWindowTitle(f"Configure Device: {self.device.name}")
        self.setModal(True)
        self.resize(600, 500)
        
        # Main layout
        layout = QVBoxLayout(self)
        
        # Device info header
        self.create_device_info_header(layout)
        
        # Configuration tabs
        self.create_config_tabs(layout)
        
        # Button panel
        self.create_button_panel(layout)
        
    def create_device_info_header(self, parent_layout):
        """Create device information header."""
        info_group = QGroupBox("Device Information")
        info_layout = QGridLayout(info_group)
        
        # Device details
        info_layout.addWidget(QLabel("Device ID:"), 0, 0)
        info_layout.addWidget(QLabel(self.device.device_id), 0, 1)
        
        info_layout.addWidget(QLabel("Name:"), 0, 2)
        info_layout.addWidget(QLabel(self.device.name), 0, 3)
        
        info_layout.addWidget(QLabel("IP Address:"), 1, 0)
        info_layout.addWidget(QLabel(self.device.ip_address), 1, 1)
        
        info_layout.addWidget(QLabel("Status:"), 1, 2)
        status_label = QLabel(self.device.status.value)
        status_label.setStyleSheet(self.get_status_style(self.device.status))
        info_layout.addWidget(status_label, 1, 3)
        
        parent_layout.addWidget(info_group)
        
    def create_config_tabs(self, parent_layout):
        """Create configuration tabs."""
        self.tab_widget = QTabWidget()
        
        # Video Configuration Tab
        self.create_video_config_tab()
        
        # Audio Configuration Tab
        self.create_audio_config_tab()
        
        # Sensor Configuration Tab
        self.create_sensor_config_tab()
        
        # Network Configuration Tab
        self.create_network_config_tab()
        
        parent_layout.addWidget(self.tab_widget)
        
    def create_video_config_tab(self):
        """Create video configuration tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # RGB Camera Settings
        rgb_group = QGroupBox("RGB Camera")
        rgb_layout = QFormLayout(rgb_group)
        
        self.rgb_resolution = QComboBox()
        self.rgb_resolution.addItems([
            "1920x1080", "1280x720", "854x480", "640x480"
        ])
        rgb_layout.addRow("Resolution:", self.rgb_resolution)
        
        self.rgb_fps = QSpinBox()
        self.rgb_fps.setRange(15, 60)
        self.rgb_fps.setValue(30)
        rgb_layout.addRow("Frame Rate (FPS):", self.rgb_fps)
        
        self.rgb_quality = QSpinBox()
        self.rgb_quality.setRange(50, 100)
        self.rgb_quality.setValue(85)
        rgb_layout.addRow("Quality (%):", self.rgb_quality)
        
        layout.addRow(rgb_group)
        
        # Thermal Camera Settings
        thermal_group = QGroupBox("Thermal Camera")
        thermal_layout = QFormLayout(thermal_group)
        
        self.thermal_enabled = QCheckBox("Enable Thermal Recording")
        thermal_layout.addRow(self.thermal_enabled)
        
        self.thermal_fps = QSpinBox()
        self.thermal_fps.setRange(15, 30)
        self.thermal_fps.setValue(25)
        thermal_layout.addRow("Frame Rate (FPS):", self.thermal_fps)
        
        self.thermal_format = QComboBox()
        self.thermal_format.addItems(["RAW", "TIFF", "PNG"])
        thermal_layout.addRow("Output Format:", self.thermal_format)
        
        layout.addRow(thermal_group)
        
        self.tab_widget.addTab(tab, "Video")
        
    def create_audio_config_tab(self):
        """Create audio configuration tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # Audio Settings
        audio_group = QGroupBox("Audio Recording")
        audio_layout = QFormLayout(audio_group)
        
        self.audio_enabled = QCheckBox("Enable Audio Recording")
        self.audio_enabled.setChecked(True)
        audio_layout.addRow(self.audio_enabled)
        
        self.audio_sample_rate = QComboBox()
        self.audio_sample_rate.addItems([
            "44100", "48000", "96000"
        ])
        audio_layout.addRow("Sample Rate (Hz):", self.audio_sample_rate)
        
        self.audio_channels = QComboBox()
        self.audio_channels.addItems(["Mono", "Stereo"])
        self.audio_channels.setCurrentText("Stereo")
        audio_layout.addRow("Channels:", self.audio_channels)
        
        self.audio_bitrate = QSpinBox()
        self.audio_bitrate.setRange(128, 320)
        self.audio_bitrate.setValue(256)
        audio_layout.addRow("Bitrate (kbps):", self.audio_bitrate)
        
        layout.addRow(audio_group)
        
        self.tab_widget.addTab(tab, "Audio")
        
    def create_sensor_config_tab(self):
        """Create sensor configuration tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # GSR Sensor Settings
        gsr_group = QGroupBox("GSR Sensor")
        gsr_layout = QFormLayout(gsr_group)
        
        self.gsr_enabled = QCheckBox("Enable GSR Recording")
        self.gsr_enabled.setChecked(True)
        gsr_layout.addRow(self.gsr_enabled)
        
        self.gsr_sample_rate = QSpinBox()
        self.gsr_sample_rate.setRange(64, 512)
        self.gsr_sample_rate.setValue(128)
        gsr_layout.addRow("Sample Rate (Hz):", self.gsr_sample_rate)
        
        self.gsr_range = QComboBox()
        self.gsr_range.addItems(["Auto", "40μS", "10μS", "2.5μS"])
        gsr_layout.addRow("GSR Range:", self.gsr_range)
        
        layout.addRow(gsr_group)
        
        # PPG Settings
        ppg_group = QGroupBox("PPG/Heart Rate")
        ppg_layout = QFormLayout(ppg_group)
        
        self.ppg_enabled = QCheckBox("Enable PPG Recording")
        self.ppg_enabled.setChecked(True)
        ppg_layout.addRow(self.ppg_enabled)
        
        self.hr_calculation = QCheckBox("Calculate Heart Rate")
        self.hr_calculation.setChecked(True)
        ppg_layout.addRow(self.hr_calculation)
        
        layout.addRow(ppg_group)
        
        # Additional Sensors
        other_group = QGroupBox("Additional Sensors")
        other_layout = QFormLayout(other_group)
        
        self.accelerometer = QCheckBox("Accelerometer")
        other_layout.addRow(self.accelerometer)
        
        self.gyroscope = QCheckBox("Gyroscope")
        other_layout.addRow(self.gyroscope)
        
        self.magnetometer = QCheckBox("Magnetometer")
        other_layout.addRow(self.magnetometer)
        
        layout.addRow(other_group)
        
        self.tab_widget.addTab(tab, "Sensors")
        
    def create_network_config_tab(self):
        """Create network configuration tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # Connection Settings
        conn_group = QGroupBox("Connection Settings")
        conn_layout = QFormLayout(conn_group)
        
        self.heartbeat_interval = QSpinBox()
        self.heartbeat_interval.setRange(1, 60)
        self.heartbeat_interval.setValue(5)
        conn_layout.addRow("Heartbeat Interval (s):", self.heartbeat_interval)
        
        self.connection_timeout = QSpinBox()
        self.connection_timeout.setRange(5, 120)
        self.connection_timeout.setValue(30)
        conn_layout.addRow("Connection Timeout (s):", self.connection_timeout)
        
        self.retry_attempts = QSpinBox()
        self.retry_attempts.setRange(1, 10)
        self.retry_attempts.setValue(3)
        conn_layout.addRow("Retry Attempts:", self.retry_attempts)
        
        layout.addRow(conn_group)
        
        # Data Streaming
        stream_group = QGroupBox("Data Streaming")
        stream_layout = QFormLayout(stream_group)
        
        self.stream_preview = QCheckBox("Stream Preview Frames")
        self.stream_preview.setChecked(True)
        stream_layout.addRow(self.stream_preview)
        
        self.preview_fps = QSpinBox()
        self.preview_fps.setRange(1, 15)
        self.preview_fps.setValue(5)
        stream_layout.addRow("Preview FPS:", self.preview_fps)
        
        self.stream_sensor_data = QCheckBox("Stream Sensor Data")
        self.stream_sensor_data.setChecked(True)
        stream_layout.addRow(self.stream_sensor_data)
        
        layout.addRow(stream_group)
        
        self.tab_widget.addTab(tab, "Network")
        
    def create_button_panel(self, parent_layout):
        """Create button panel."""
        button_layout = QHBoxLayout()
        
        # Test Configuration Button
        self.test_btn = QPushButton("Test Configuration")
        self.test_btn.clicked.connect(self.test_configuration)
        button_layout.addWidget(self.test_btn)
        
        button_layout.addStretch()
        
        # Cancel Button
        cancel_btn = QPushButton("Cancel")
        cancel_btn.clicked.connect(self.reject)
        button_layout.addWidget(cancel_btn)
        
        # Apply Button
        apply_btn = QPushButton("Apply")
        apply_btn.clicked.connect(self.apply_configuration)
        button_layout.addWidget(apply_btn)
        
        # OK Button
        ok_btn = QPushButton("OK")
        ok_btn.setDefault(True)
        ok_btn.clicked.connect(self.accept_configuration)
        button_layout.addWidget(ok_btn)
        
        parent_layout.addLayout(button_layout)
        
    def load_device_config(self):
        """Load current device configuration."""
        # This would typically load from device or saved settings
        # For now, use default values
        pass
        
    def get_configuration(self) -> Dict[str, Any]:
        """Get current configuration from UI."""
        config = {
            'video': {
                'rgb': {
                    'resolution': self.rgb_resolution.currentText(),
                    'fps': self.rgb_fps.value(),
                    'quality': self.rgb_quality.value()
                },
                'thermal': {
                    'enabled': self.thermal_enabled.isChecked(),
                    'fps': self.thermal_fps.value(),
                    'format': self.thermal_format.currentText()
                }
            },
            'audio': {
                'enabled': self.audio_enabled.isChecked(),
                'sample_rate': int(self.audio_sample_rate.currentText()),
                'channels': 2 if self.audio_channels.currentText() == "Stereo" else 1,
                'bitrate': self.audio_bitrate.value()
            },
            'sensors': {
                'gsr': {
                    'enabled': self.gsr_enabled.isChecked(),
                    'sample_rate': self.gsr_sample_rate.value(),
                    'range': self.gsr_range.currentText()
                },
                'ppg': {
                    'enabled': self.ppg_enabled.isChecked(),
                    'calculate_hr': self.hr_calculation.isChecked()
                },
                'additional': {
                    'accelerometer': self.accelerometer.isChecked(),
                    'gyroscope': self.gyroscope.isChecked(),
                    'magnetometer': self.magnetometer.isChecked()
                }
            },
            'network': {
                'heartbeat_interval': self.heartbeat_interval.value(),
                'connection_timeout': self.connection_timeout.value(),
                'retry_attempts': self.retry_attempts.value(),
                'stream_preview': self.stream_preview.isChecked(),
                'preview_fps': self.preview_fps.value(),
                'stream_sensor_data': self.stream_sensor_data.isChecked()
            }
        }
        return config
        
    def set_configuration(self, config: Dict[str, Any]):
        """Set configuration in UI."""
        try:
            # Video settings
            if 'video' in config:
                video_config = config['video']
                if 'rgb' in video_config:
                    rgb = video_config['rgb']
                    if 'resolution' in rgb:
                        self.rgb_resolution.setCurrentText(rgb['resolution'])
                    if 'fps' in rgb:
                        self.rgb_fps.setValue(rgb['fps'])
                    if 'quality' in rgb:
                        self.rgb_quality.setValue(rgb['quality'])
                        
                if 'thermal' in video_config:
                    thermal = video_config['thermal']
                    if 'enabled' in thermal:
                        self.thermal_enabled.setChecked(thermal['enabled'])
                    if 'fps' in thermal:
                        self.thermal_fps.setValue(thermal['fps'])
                    if 'format' in thermal:
                        self.thermal_format.setCurrentText(thermal['format'])
            
            # Audio settings
            if 'audio' in config:
                audio = config['audio']
                if 'enabled' in audio:
                    self.audio_enabled.setChecked(audio['enabled'])
                if 'sample_rate' in audio:
                    self.audio_sample_rate.setCurrentText(str(audio['sample_rate']))
                if 'channels' in audio:
                    self.audio_channels.setCurrentText("Stereo" if audio['channels'] == 2 else "Mono")
                if 'bitrate' in audio:
                    self.audio_bitrate.setValue(audio['bitrate'])
            
            # Sensor settings
            if 'sensors' in config:
                sensors = config['sensors']
                if 'gsr' in sensors:
                    gsr = sensors['gsr']
                    if 'enabled' in gsr:
                        self.gsr_enabled.setChecked(gsr['enabled'])
                    if 'sample_rate' in gsr:
                        self.gsr_sample_rate.setValue(gsr['sample_rate'])
                    if 'range' in gsr:
                        self.gsr_range.setCurrentText(gsr['range'])
                        
                if 'ppg' in sensors:
                    ppg = sensors['ppg']
                    if 'enabled' in ppg:
                        self.ppg_enabled.setChecked(ppg['enabled'])
                    if 'calculate_hr' in ppg:
                        self.hr_calculation.setChecked(ppg['calculate_hr'])
                        
                if 'additional' in sensors:
                    additional = sensors['additional']
                    self.accelerometer.setChecked(additional.get('accelerometer', False))
                    self.gyroscope.setChecked(additional.get('gyroscope', False))
                    self.magnetometer.setChecked(additional.get('magnetometer', False))
            
            # Network settings
            if 'network' in config:
                network = config['network']
                if 'heartbeat_interval' in network:
                    self.heartbeat_interval.setValue(network['heartbeat_interval'])
                if 'connection_timeout' in network:
                    self.connection_timeout.setValue(network['connection_timeout'])
                if 'retry_attempts' in network:
                    self.retry_attempts.setValue(network['retry_attempts'])
                if 'stream_preview' in network:
                    self.stream_preview.setChecked(network['stream_preview'])
                if 'preview_fps' in network:
                    self.preview_fps.setValue(network['preview_fps'])
                if 'stream_sensor_data' in network:
                    self.stream_sensor_data.setChecked(network['stream_sensor_data'])
                    
        except Exception as e:
            logging.error(f"Error setting configuration: {e}")
            QMessageBox.warning(self, "Configuration Error", 
                              f"Error loading configuration: {e}")
    
    def test_configuration(self):
        """Test the current configuration."""
        config = self.get_configuration()
        
        # Disable test button during test
        self.test_btn.setEnabled(False)
        self.test_btn.setText("Testing...")
        
        # Emit signal to test configuration
        self.config_updated.emit(self.device.device_id, {
            'action': 'test',
            'config': config
        })
        
        # Re-enable button after delay
        QTimer.singleShot(3000, self.reset_test_button)
        
    def reset_test_button(self):
        """Reset test button state."""
        self.test_btn.setEnabled(True)
        self.test_btn.setText("Test Configuration")
        
    def apply_configuration(self):
        """Apply configuration without closing dialog."""
        config = self.get_configuration()
        self.config_updated.emit(self.device.device_id, {
            'action': 'apply',
            'config': config
        })
        
    def accept_configuration(self):
        """Accept and apply configuration, then close dialog."""
        config = self.get_configuration()
        self.config_updated.emit(self.device.device_id, {
            'action': 'apply',
            'config': config
        })
        self.accept()
        
    def get_status_style(self, status: DeviceStatus) -> str:
        """Get CSS style for device status."""
        if status == DeviceStatus.CONNECTED:
            return "color: #4CAF50; font-weight: bold;"
        elif status == DeviceStatus.CONNECTING:
            return "color: #FF9800; font-weight: bold;"
        elif status == DeviceStatus.RECORDING:
            return "color: #F44336; font-weight: bold;"
        else:
            return "color: #9E9E9E;"