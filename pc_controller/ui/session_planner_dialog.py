#!/usr/bin/env python3
"""
Session Planner Dialog for PC Controller Application
Provides interface for planning and configuring multi-device recording sessions.
"""

import logging
from typing import Dict, Any, Optional, List
from datetime import datetime, timedelta
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QGridLayout, QFormLayout,
    QPushButton, QLabel, QLineEdit, QSpinBox, QDoubleSpinBox,
    QComboBox, QCheckBox, QGroupBox, QTabWidget, QWidget,
    QMessageBox, QProgressBar, QTextEdit, QTableWidget,
    QTableWidgetItem, QHeaderView, QDateTimeEdit, QSlider,
    QListWidget, QListWidgetItem, QSplitter, QFrame
)
from PyQt6.QtCore import Qt, pyqtSignal, QTimer, QDateTime
from PyQt6.QtGui import QFont, QIcon

from core.device_manager import AndroidDevice, DeviceStatus
from core.recording_controller import SessionInfo


class SessionConfiguration:
    """Container for session configuration data."""
    
    def __init__(self):
        self.session_name = ""
        self.session_id = ""
        self.description = ""
        self.participant_id = ""
        self.experiment_type = ""
        self.duration_minutes = 10
        self.start_time = None
        self.auto_start = False
        self.selected_devices = []
        self.recording_settings = {}
        self.sync_settings = {}
        self.output_settings = {}
        
    def to_dict(self) -> Dict[str, Any]:
        """Convert configuration to dictionary."""
        return {
            'session_name': self.session_name,
            'session_id': self.session_id,
            'description': self.description,
            'participant_id': self.participant_id,
            'experiment_type': self.experiment_type,
            'duration_minutes': self.duration_minutes,
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'auto_start': self.auto_start,
            'selected_devices': self.selected_devices,
            'recording_settings': self.recording_settings,
            'sync_settings': self.sync_settings,
            'output_settings': self.output_settings
        }
        
    def from_dict(self, data: Dict[str, Any]):
        """Load configuration from dictionary."""
        self.session_name = data.get('session_name', '')
        self.session_id = data.get('session_id', '')
        self.description = data.get('description', '')
        self.participant_id = data.get('participant_id', '')
        self.experiment_type = data.get('experiment_type', '')
        self.duration_minutes = data.get('duration_minutes', 10)
        
        start_time_str = data.get('start_time')
        if start_time_str:
            self.start_time = datetime.fromisoformat(start_time_str)
        else:
            self.start_time = None
            
        self.auto_start = data.get('auto_start', False)
        self.selected_devices = data.get('selected_devices', [])
        self.recording_settings = data.get('recording_settings', {})
        self.sync_settings = data.get('sync_settings', {})
        self.output_settings = data.get('output_settings', {})


class DeviceSelectionWidget(QWidget):
    """Widget for selecting and configuring devices for the session."""
    
    def __init__(self, available_devices: List[AndroidDevice], parent=None):
        super().__init__(parent)
        self.available_devices = available_devices
        self.selected_devices = []
        self.setup_ui()
        
    def setup_ui(self):
        """Setup the user interface."""
        layout = QHBoxLayout(self)
        
        # Available devices list
        available_group = QGroupBox("Available Devices")
        available_layout = QVBoxLayout(available_group)
        
        self.available_list = QListWidget()
        self.populate_available_devices()
        available_layout.addWidget(self.available_list)
        
        layout.addWidget(available_group)
        
        # Control buttons
        button_layout = QVBoxLayout()
        button_layout.addStretch()
        
        self.add_btn = QPushButton("Add →")
        self.add_btn.clicked.connect(self.add_device)
        button_layout.addWidget(self.add_btn)
        
        self.remove_btn = QPushButton("← Remove")
        self.remove_btn.clicked.connect(self.remove_device)
        button_layout.addWidget(self.remove_btn)
        
        self.add_all_btn = QPushButton("Add All →")
        self.add_all_btn.clicked.connect(self.add_all_devices)
        button_layout.addWidget(self.add_all_btn)
        
        self.remove_all_btn = QPushButton("← Remove All")
        self.remove_all_btn.clicked.connect(self.remove_all_devices)
        button_layout.addWidget(self.remove_all_btn)
        
        button_layout.addStretch()
        layout.addLayout(button_layout)
        
        # Selected devices list
        selected_group = QGroupBox("Selected Devices")
        selected_layout = QVBoxLayout(selected_group)
        
        self.selected_list = QListWidget()
        selected_layout.addWidget(self.selected_list)
        
        layout.addWidget(selected_group)
        
    def populate_available_devices(self):
        """Populate the available devices list."""
        self.available_list.clear()
        for device in self.available_devices:
            if device.device_id not in self.selected_devices:
                item = QListWidgetItem(f"{device.name} ({device.ip_address})")
                item.setData(Qt.ItemDataRole.UserRole, device.device_id)
                
                # Set icon based on device status
                if device.status == DeviceStatus.CONNECTED:
                    item.setIcon(QIcon(":/icons/device_connected.png"))
                elif device.status == DeviceStatus.CONNECTING:
                    item.setIcon(QIcon(":/icons/device_connecting.png"))
                else:
                    item.setIcon(QIcon(":/icons/device_disconnected.png"))
                    
                self.available_list.addItem(item)
                
    def populate_selected_devices(self):
        """Populate the selected devices list."""
        self.selected_list.clear()
        for device_id in self.selected_devices:
            device = next((d for d in self.available_devices if d.device_id == device_id), None)
            if device:
                item = QListWidgetItem(f"{device.name} ({device.ip_address})")
                item.setData(Qt.ItemDataRole.UserRole, device.device_id)
                self.selected_list.addItem(item)
                
    def add_device(self):
        """Add selected device to the session."""
        current_item = self.available_list.currentItem()
        if current_item:
            device_id = current_item.data(Qt.ItemDataRole.UserRole)
            if device_id not in self.selected_devices:
                self.selected_devices.append(device_id)
                self.populate_available_devices()
                self.populate_selected_devices()
                
    def remove_device(self):
        """Remove selected device from the session."""
        current_item = self.selected_list.currentItem()
        if current_item:
            device_id = current_item.data(Qt.ItemDataRole.UserRole)
            if device_id in self.selected_devices:
                self.selected_devices.remove(device_id)
                self.populate_available_devices()
                self.populate_selected_devices()
                
    def add_all_devices(self):
        """Add all available devices to the session."""
        for device in self.available_devices:
            if device.device_id not in self.selected_devices:
                self.selected_devices.append(device.device_id)
        self.populate_available_devices()
        self.populate_selected_devices()
        
    def remove_all_devices(self):
        """Remove all devices from the session."""
        self.selected_devices.clear()
        self.populate_available_devices()
        self.populate_selected_devices()
        
    def get_selected_devices(self) -> List[str]:
        """Get list of selected device IDs."""
        return self.selected_devices.copy()
        
    def set_selected_devices(self, device_ids: List[str]):
        """Set the selected devices."""
        self.selected_devices = device_ids.copy()
        self.populate_available_devices()
        self.populate_selected_devices()


class SessionPlannerDialog(QDialog):
    """Dialog for planning and configuring recording sessions."""
    
    # Signals
    session_configured = pyqtSignal(dict)  # session_config
    
    def __init__(self, available_devices: List[AndroidDevice], parent=None):
        super().__init__(parent)
        self.available_devices = available_devices
        self.config = SessionConfiguration()
        self.setup_ui()
        self.load_default_config()
        
    def setup_ui(self):
        """Setup the user interface."""
        self.setWindowTitle("Session Planner")
        self.setModal(True)
        self.resize(800, 600)
        
        # Main layout
        layout = QVBoxLayout(self)
        
        # Configuration tabs
        self.create_config_tabs(layout)
        
        # Button panel
        self.create_button_panel(layout)
        
    def create_config_tabs(self, parent_layout):
        """Create configuration tabs."""
        self.tab_widget = QTabWidget()
        
        # Session Info Tab
        self.create_session_info_tab()
        
        # Device Selection Tab
        self.create_device_selection_tab()
        
        # Recording Settings Tab
        self.create_recording_settings_tab()
        
        # Synchronization Tab
        self.create_sync_settings_tab()
        
        # Output Settings Tab
        self.create_output_settings_tab()
        
        parent_layout.addWidget(self.tab_widget)
        
    def create_session_info_tab(self):
        """Create session information tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # Basic session info
        basic_group = QGroupBox("Session Information")
        basic_layout = QFormLayout(basic_group)
        
        self.session_name_edit = QLineEdit()
        self.session_name_edit.setPlaceholderText("Enter session name")
        basic_layout.addRow("Session Name:", self.session_name_edit)
        
        self.session_id_edit = QLineEdit()
        self.session_id_edit.setPlaceholderText("Auto-generated if empty")
        basic_layout.addRow("Session ID:", self.session_id_edit)
        
        self.participant_id_edit = QLineEdit()
        self.participant_id_edit.setPlaceholderText("Enter participant ID")
        basic_layout.addRow("Participant ID:", self.participant_id_edit)
        
        self.experiment_type_combo = QComboBox()
        self.experiment_type_combo.addItems([
            "General Recording", "Stress Response", "Emotion Recognition",
            "Cognitive Load", "Social Interaction", "Custom"
        ])
        basic_layout.addRow("Experiment Type:", self.experiment_type_combo)
        
        self.description_edit = QTextEdit()
        self.description_edit.setMaximumHeight(80)
        self.description_edit.setPlaceholderText("Enter session description")
        basic_layout.addRow("Description:", self.description_edit)
        
        layout.addRow(basic_group)
        
        # Timing settings
        timing_group = QGroupBox("Timing Settings")
        timing_layout = QFormLayout(timing_group)
        
        self.duration_spin = QSpinBox()
        self.duration_spin.setRange(1, 180)
        self.duration_spin.setValue(10)
        self.duration_spin.setSuffix(" minutes")
        timing_layout.addRow("Duration:", self.duration_spin)
        
        self.start_time_edit = QDateTimeEdit()
        self.start_time_edit.setDateTime(QDateTime.currentDateTime())
        self.start_time_edit.setCalendarPopup(True)
        timing_layout.addRow("Start Time:", self.start_time_edit)
        
        self.auto_start_check = QCheckBox("Auto-start at scheduled time")
        timing_layout.addRow(self.auto_start_check)
        
        layout.addRow(timing_group)
        
        self.tab_widget.addTab(tab, "Session Info")
        
    def create_device_selection_tab(self):
        """Create device selection tab."""
        self.device_selection_widget = DeviceSelectionWidget(self.available_devices)
        self.tab_widget.addTab(self.device_selection_widget, "Device Selection")
        
    def create_recording_settings_tab(self):
        """Create recording settings tab."""
        tab = QWidget()
        layout = QVBoxLayout(tab)
        
        # Global recording settings
        global_group = QGroupBox("Global Recording Settings")
        global_layout = QFormLayout(global_group)
        
        self.video_quality_combo = QComboBox()
        self.video_quality_combo.addItems(["High", "Medium", "Low"])
        global_layout.addRow("Video Quality:", self.video_quality_combo)
        
        self.audio_enabled_check = QCheckBox("Enable Audio Recording")
        self.audio_enabled_check.setChecked(True)
        global_layout.addRow(self.audio_enabled_check)
        
        self.thermal_enabled_check = QCheckBox("Enable Thermal Recording")
        self.thermal_enabled_check.setChecked(True)
        global_layout.addRow(self.thermal_enabled_check)
        
        self.gsr_enabled_check = QCheckBox("Enable GSR Recording")
        self.gsr_enabled_check.setChecked(True)
        global_layout.addRow(self.gsr_enabled_check)
        
        layout.addWidget(global_group)
        
        # Advanced settings
        advanced_group = QGroupBox("Advanced Settings")
        advanced_layout = QFormLayout(advanced_group)
        
        self.buffer_size_spin = QSpinBox()
        self.buffer_size_spin.setRange(1, 100)
        self.buffer_size_spin.setValue(10)
        self.buffer_size_spin.setSuffix(" MB")
        advanced_layout.addRow("Buffer Size:", self.buffer_size_spin)
        
        self.compression_combo = QComboBox()
        self.compression_combo.addItems(["None", "Low", "Medium", "High"])
        self.compression_combo.setCurrentText("Medium")
        advanced_layout.addRow("Compression:", self.compression_combo)
        
        self.error_recovery_check = QCheckBox("Enable Error Recovery")
        self.error_recovery_check.setChecked(True)
        advanced_layout.addRow(self.error_recovery_check)
        
        layout.addWidget(advanced_group)
        layout.addStretch()
        
        self.tab_widget.addTab(tab, "Recording")
        
    def create_sync_settings_tab(self):
        """Create synchronization settings tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # Time synchronization
        sync_group = QGroupBox("Time Synchronization")
        sync_layout = QFormLayout(sync_group)
        
        self.sync_enabled_check = QCheckBox("Enable Time Synchronization")
        self.sync_enabled_check.setChecked(True)
        sync_layout.addRow(self.sync_enabled_check)
        
        self.sync_interval_spin = QSpinBox()
        self.sync_interval_spin.setRange(1, 60)
        self.sync_interval_spin.setValue(10)
        self.sync_interval_spin.setSuffix(" seconds")
        sync_layout.addRow("Sync Interval:", self.sync_interval_spin)
        
        self.sync_tolerance_spin = QSpinBox()
        self.sync_tolerance_spin.setRange(1, 1000)
        self.sync_tolerance_spin.setValue(50)
        self.sync_tolerance_spin.setSuffix(" ms")
        sync_layout.addRow("Sync Tolerance:", self.sync_tolerance_spin)
        
        layout.addRow(sync_group)
        
        # Sync markers
        markers_group = QGroupBox("Synchronization Markers")
        markers_layout = QFormLayout(markers_group)
        
        self.start_marker_check = QCheckBox("Add start marker")
        self.start_marker_check.setChecked(True)
        markers_layout.addRow(self.start_marker_check)
        
        self.end_marker_check = QCheckBox("Add end marker")
        self.end_marker_check.setChecked(True)
        markers_layout.addRow(self.end_marker_check)
        
        self.interval_markers_check = QCheckBox("Add interval markers")
        markers_layout.addRow(self.interval_markers_check)
        
        self.marker_interval_spin = QSpinBox()
        self.marker_interval_spin.setRange(10, 300)
        self.marker_interval_spin.setValue(60)
        self.marker_interval_spin.setSuffix(" seconds")
        markers_layout.addRow("Marker Interval:", self.marker_interval_spin)
        
        layout.addRow(markers_group)
        
        self.tab_widget.addTab(tab, "Synchronization")
        
    def create_output_settings_tab(self):
        """Create output settings tab."""
        tab = QWidget()
        layout = QFormLayout(tab)
        
        # Output location
        output_group = QGroupBox("Output Settings")
        output_layout = QFormLayout(output_group)
        
        self.output_dir_edit = QLineEdit()
        self.output_dir_edit.setPlaceholderText("Default output directory")
        output_layout.addRow("Output Directory:", self.output_dir_edit)
        
        self.naming_pattern_edit = QLineEdit()
        self.naming_pattern_edit.setText("{session_id}_{device_name}_{modality}")
        output_layout.addRow("Naming Pattern:", self.naming_pattern_edit)
        
        layout.addRow(output_group)
        
        # Export settings
        export_group = QGroupBox("Export Settings")
        export_layout = QFormLayout(export_group)
        
        self.generate_manifest_check = QCheckBox("Generate session manifest")
        self.generate_manifest_check.setChecked(True)
        export_layout.addRow(self.generate_manifest_check)
        
        self.auto_export_check = QCheckBox("Auto-export after recording")
        export_layout.addRow(self.auto_export_check)
        
        self.export_format_combo = QComboBox()
        self.export_format_combo.addItems(["Native", "MATLAB", "HDF5", "CSV"])
        export_layout.addRow("Export Format:", self.export_format_combo)
        
        layout.addRow(export_group)
        
        self.tab_widget.addTab(tab, "Output")
        
    def create_button_panel(self, parent_layout):
        """Create button panel."""
        button_layout = QHBoxLayout()
        
        # Validate button
        validate_btn = QPushButton("Validate Configuration")
        validate_btn.clicked.connect(self.validate_configuration)
        button_layout.addWidget(validate_btn)
        
        button_layout.addStretch()
        
        # Cancel button
        cancel_btn = QPushButton("Cancel")
        cancel_btn.clicked.connect(self.reject)
        button_layout.addWidget(cancel_btn)
        
        # Save Template button
        save_template_btn = QPushButton("Save Template")
        save_template_btn.clicked.connect(self.save_template)
        button_layout.addWidget(save_template_btn)
        
        # OK button
        ok_btn = QPushButton("Create Session")
        ok_btn.setDefault(True)
        ok_btn.clicked.connect(self.accept_configuration)
        button_layout.addWidget(ok_btn)
        
        parent_layout.addLayout(button_layout)
        
    def load_default_config(self):
        """Load default configuration values."""
        # Generate default session ID
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.session_id_edit.setText(f"Session_{timestamp}")
        
    def get_configuration(self) -> SessionConfiguration:
        """Get current configuration from UI."""
        config = SessionConfiguration()
        
        # Session info
        config.session_name = self.session_name_edit.text()
        config.session_id = self.session_id_edit.text()
        config.description = self.description_edit.toPlainText()
        config.participant_id = self.participant_id_edit.text()
        config.experiment_type = self.experiment_type_combo.currentText()
        config.duration_minutes = self.duration_spin.value()
        config.start_time = self.start_time_edit.dateTime().toPython()
        config.auto_start = self.auto_start_check.isChecked()
        
        # Device selection
        config.selected_devices = self.device_selection_widget.get_selected_devices()
        
        # Recording settings
        config.recording_settings = {
            'video_quality': self.video_quality_combo.currentText(),
            'audio_enabled': self.audio_enabled_check.isChecked(),
            'thermal_enabled': self.thermal_enabled_check.isChecked(),
            'gsr_enabled': self.gsr_enabled_check.isChecked(),
            'buffer_size_mb': self.buffer_size_spin.value(),
            'compression': self.compression_combo.currentText(),
            'error_recovery': self.error_recovery_check.isChecked()
        }
        
        # Sync settings
        config.sync_settings = {
            'enabled': self.sync_enabled_check.isChecked(),
            'interval_seconds': self.sync_interval_spin.value(),
            'tolerance_ms': self.sync_tolerance_spin.value(),
            'start_marker': self.start_marker_check.isChecked(),
            'end_marker': self.end_marker_check.isChecked(),
            'interval_markers': self.interval_markers_check.isChecked(),
            'marker_interval_seconds': self.marker_interval_spin.value()
        }
        
        # Output settings
        config.output_settings = {
            'output_directory': self.output_dir_edit.text(),
            'naming_pattern': self.naming_pattern_edit.text(),
            'generate_manifest': self.generate_manifest_check.isChecked(),
            'auto_export': self.auto_export_check.isChecked(),
            'export_format': self.export_format_combo.currentText()
        }
        
        return config
        
    def set_configuration(self, config: SessionConfiguration):
        """Set configuration in UI."""
        # Session info
        self.session_name_edit.setText(config.session_name)
        self.session_id_edit.setText(config.session_id)
        self.description_edit.setPlainText(config.description)
        self.participant_id_edit.setText(config.participant_id)
        self.experiment_type_combo.setCurrentText(config.experiment_type)
        self.duration_spin.setValue(config.duration_minutes)
        
        if config.start_time:
            self.start_time_edit.setDateTime(QDateTime.fromSecsSinceEpoch(int(config.start_time.timestamp())))
            
        self.auto_start_check.setChecked(config.auto_start)
        
        # Device selection
        self.device_selection_widget.set_selected_devices(config.selected_devices)
        
        # Recording settings
        if config.recording_settings:
            settings = config.recording_settings
            self.video_quality_combo.setCurrentText(settings.get('video_quality', 'High'))
            self.audio_enabled_check.setChecked(settings.get('audio_enabled', True))
            self.thermal_enabled_check.setChecked(settings.get('thermal_enabled', True))
            self.gsr_enabled_check.setChecked(settings.get('gsr_enabled', True))
            self.buffer_size_spin.setValue(settings.get('buffer_size_mb', 10))
            self.compression_combo.setCurrentText(settings.get('compression', 'Medium'))
            self.error_recovery_check.setChecked(settings.get('error_recovery', True))
            
        # Sync settings
        if config.sync_settings:
            settings = config.sync_settings
            self.sync_enabled_check.setChecked(settings.get('enabled', True))
            self.sync_interval_spin.setValue(settings.get('interval_seconds', 10))
            self.sync_tolerance_spin.setValue(settings.get('tolerance_ms', 50))
            self.start_marker_check.setChecked(settings.get('start_marker', True))
            self.end_marker_check.setChecked(settings.get('end_marker', True))
            self.interval_markers_check.setChecked(settings.get('interval_markers', False))
            self.marker_interval_spin.setValue(settings.get('marker_interval_seconds', 60))
            
        # Output settings
        if config.output_settings:
            settings = config.output_settings
            self.output_dir_edit.setText(settings.get('output_directory', ''))
            self.naming_pattern_edit.setText(settings.get('naming_pattern', '{session_id}_{device_name}_{modality}'))
            self.generate_manifest_check.setChecked(settings.get('generate_manifest', True))
            self.auto_export_check.setChecked(settings.get('auto_export', False))
            self.export_format_combo.setCurrentText(settings.get('export_format', 'Native'))
            
    def validate_configuration(self):
        """Validate the current configuration."""
        config = self.get_configuration()
        errors = []
        
        # Check required fields
        if not config.session_name:
            errors.append("Session name is required")
            
        if not config.session_id:
            errors.append("Session ID is required")
            
        if not config.selected_devices:
            errors.append("At least one device must be selected")
            
        # Check device connectivity
        connected_devices = 0
        for device_id in config.selected_devices:
            device = next((d for d in self.available_devices if d.device_id == device_id), None)
            if device and device.status == DeviceStatus.CONNECTED:
                connected_devices += 1
                
        if connected_devices == 0:
            errors.append("No selected devices are currently connected")
            
        # Show validation results
        if errors:
            QMessageBox.warning(self, "Configuration Validation", 
                              "Configuration errors:\n\n" + "\n".join(f"• {error}" for error in errors))
        else:
            QMessageBox.information(self, "Configuration Validation", 
                                  "Configuration is valid and ready for use.")
                                  
    def save_template(self):
        """Save current configuration as a template."""
        # This would typically save to a file or database
        QMessageBox.information(self, "Save Template", 
                              "Template saving functionality would be implemented here.")
                              
    def accept_configuration(self):
        """Accept and emit the configuration."""
        config = self.get_configuration()
        self.session_configured.emit(config.to_dict())
        self.accept()