#!/usr/bin/env python3
"""
Device Health Monitoring Widget for PC Controller Application
Provides real-time health monitoring and status display for Android devices.
"""

import logging
from typing import Dict, Any, Optional, List
from datetime import datetime, timedelta
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QProgressBar, QGroupBox, QFrame, QScrollArea,
    QPushButton, QTableWidget, QTableWidgetItem, QHeaderView
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QThread, pyqtSlot
from PyQt6.QtGui import QFont, QPalette, QColor, QPixmap, QPainter

from core.device_manager import AndroidDevice, DeviceStatus


class DeviceHealthMetrics:
    """Container for device health metrics."""
    
    def __init__(self):
        self.battery_level = 0
        self.storage_free = 0
        self.storage_total = 0
        self.memory_usage = 0
        self.cpu_usage = 0
        self.temperature = 0
        self.network_strength = 0
        self.last_heartbeat = None
        self.recording_duration = 0
        self.data_rate = 0
        self.error_count = 0
        self.sync_quality = 0
        
    def to_dict(self) -> Dict[str, Any]:
        """Convert metrics to dictionary."""
        return {
            'battery_level': self.battery_level,
            'storage_free': self.storage_free,
            'storage_total': self.storage_total,
            'memory_usage': self.memory_usage,
            'cpu_usage': self.cpu_usage,
            'temperature': self.temperature,
            'network_strength': self.network_strength,
            'last_heartbeat': self.last_heartbeat,
            'recording_duration': self.recording_duration,
            'data_rate': self.data_rate,
            'error_count': self.error_count,
            'sync_quality': self.sync_quality
        }
        
    def from_dict(self, data: Dict[str, Any]):
        """Load metrics from dictionary."""
        self.battery_level = data.get('battery_level', 0)
        self.storage_free = data.get('storage_free', 0)
        self.storage_total = data.get('storage_total', 0)
        self.memory_usage = data.get('memory_usage', 0)
        self.cpu_usage = data.get('cpu_usage', 0)
        self.temperature = data.get('temperature', 0)
        self.network_strength = data.get('network_strength', 0)
        self.last_heartbeat = data.get('last_heartbeat')
        self.recording_duration = data.get('recording_duration', 0)
        self.data_rate = data.get('data_rate', 0)
        self.error_count = data.get('error_count', 0)
        self.sync_quality = data.get('sync_quality', 0)


class DeviceHealthCard(QFrame):
    """Individual device health monitoring card."""
    
    # Signals
    configure_requested = pyqtSignal(str)  # device_id
    details_requested = pyqtSignal(str)    # device_id
    
    def __init__(self, device: AndroidDevice, parent=None):
        super().__init__(parent)
        self.device = device
        self.metrics = DeviceHealthMetrics()
        self.setup_ui()
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_display)
        self.update_timer.start(1000)  # Update every second
        
    def setup_ui(self):
        """Setup the user interface."""
        self.setFrameStyle(QFrame.Shape.Box)
        self.setStyleSheet("""
            QFrame {
                border: 2px solid #444;
                border-radius: 8px;
                background-color: #2b2b2b;
                margin: 4px;
            }
            QLabel {
                color: #ffffff;
                font-size: 11px;
            }
            QProgressBar {
                border: 1px solid #555;
                border-radius: 3px;
                text-align: center;
                font-size: 10px;
            }
            QProgressBar::chunk {
                border-radius: 2px;
            }
        """)
        
        layout = QVBoxLayout(self)
        layout.setSpacing(4)
        layout.setContentsMargins(8, 8, 8, 8)
        
        # Header with device info
        self.create_header(layout)
        
        # Status indicators
        self.create_status_section(layout)
        
        # Performance metrics
        self.create_metrics_section(layout)
        
        # Action buttons
        self.create_action_buttons(layout)
        
    def create_header(self, parent_layout):
        """Create device header section."""
        header_layout = QHBoxLayout()
        
        # Device name and status
        info_layout = QVBoxLayout()
        
        self.name_label = QLabel(self.device.name)
        self.name_label.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        info_layout.addWidget(self.name_label)
        
        self.status_label = QLabel(self.device.status.value)
        self.status_label.setStyleSheet(self.get_status_style(self.device.status))
        info_layout.addWidget(self.status_label)
        
        header_layout.addLayout(info_layout)
        header_layout.addStretch()
        
        # Connection indicator
        self.connection_indicator = QLabel("●")
        self.connection_indicator.setFont(QFont("Arial", 16))
        self.connection_indicator.setStyleSheet("color: #f44336;")  # Red by default
        header_layout.addWidget(self.connection_indicator)
        
        parent_layout.addLayout(header_layout)
        
    def create_status_section(self, parent_layout):
        """Create status indicators section."""
        status_group = QGroupBox("Status")
        status_layout = QGridLayout(status_group)
        status_layout.setSpacing(2)
        
        # Battery
        status_layout.addWidget(QLabel("Battery:"), 0, 0)
        self.battery_bar = QProgressBar()
        self.battery_bar.setMaximum(100)
        self.battery_bar.setStyleSheet("QProgressBar::chunk { background-color: #4CAF50; }")
        status_layout.addWidget(self.battery_bar, 0, 1)
        
        # Storage
        status_layout.addWidget(QLabel("Storage:"), 1, 0)
        self.storage_bar = QProgressBar()
        self.storage_bar.setMaximum(100)
        self.storage_bar.setStyleSheet("QProgressBar::chunk { background-color: #2196F3; }")
        status_layout.addWidget(self.storage_bar, 1, 1)
        
        # Network
        status_layout.addWidget(QLabel("Network:"), 2, 0)
        self.network_bar = QProgressBar()
        self.network_bar.setMaximum(100)
        self.network_bar.setStyleSheet("QProgressBar::chunk { background-color: #FF9800; }")
        status_layout.addWidget(self.network_bar, 2, 1)
        
        parent_layout.addWidget(status_group)
        
    def create_metrics_section(self, parent_layout):
        """Create performance metrics section."""
        metrics_group = QGroupBox("Performance")
        metrics_layout = QGridLayout(metrics_group)
        metrics_layout.setSpacing(2)
        
        # CPU Usage
        metrics_layout.addWidget(QLabel("CPU:"), 0, 0)
        self.cpu_label = QLabel("0%")
        metrics_layout.addWidget(self.cpu_label, 0, 1)
        
        # Memory Usage
        metrics_layout.addWidget(QLabel("Memory:"), 0, 2)
        self.memory_label = QLabel("0%")
        metrics_layout.addWidget(self.memory_label, 0, 3)
        
        # Temperature
        metrics_layout.addWidget(QLabel("Temp:"), 1, 0)
        self.temp_label = QLabel("0°C")
        metrics_layout.addWidget(self.temp_label, 1, 1)
        
        # Data Rate
        metrics_layout.addWidget(QLabel("Data:"), 1, 2)
        self.data_rate_label = QLabel("0 KB/s")
        metrics_layout.addWidget(self.data_rate_label, 1, 3)
        
        # Sync Quality
        metrics_layout.addWidget(QLabel("Sync:"), 2, 0)
        self.sync_label = QLabel("0%")
        metrics_layout.addWidget(self.sync_label, 2, 1)
        
        # Error Count
        metrics_layout.addWidget(QLabel("Errors:"), 2, 2)
        self.error_label = QLabel("0")
        metrics_layout.addWidget(self.error_label, 2, 3)
        
        parent_layout.addWidget(metrics_group)
        
    def create_action_buttons(self, parent_layout):
        """Create action buttons."""
        button_layout = QHBoxLayout()
        
        # Configure button
        config_btn = QPushButton("Config")
        config_btn.setMaximumWidth(60)
        config_btn.clicked.connect(lambda: self.configure_requested.emit(self.device.device_id))
        button_layout.addWidget(config_btn)
        
        # Details button
        details_btn = QPushButton("Details")
        details_btn.setMaximumWidth(60)
        details_btn.clicked.connect(lambda: self.details_requested.emit(self.device.device_id))
        button_layout.addWidget(details_btn)
        
        button_layout.addStretch()
        
        parent_layout.addLayout(button_layout)
        
    def update_metrics(self, metrics: DeviceHealthMetrics):
        """Update device metrics."""
        self.metrics = metrics
        self.update_display()
        
    def update_display(self):
        """Update the display with current metrics."""
        # Update status
        self.status_label.setText(self.device.status.value)
        self.status_label.setStyleSheet(self.get_status_style(self.device.status))
        
        # Update connection indicator
        if self.device.status == DeviceStatus.CONNECTED:
            self.connection_indicator.setStyleSheet("color: #4CAF50;")  # Green
        elif self.device.status == DeviceStatus.CONNECTING:
            self.connection_indicator.setStyleSheet("color: #FF9800;")  # Orange
        else:
            self.connection_indicator.setStyleSheet("color: #f44336;")  # Red
            
        # Update progress bars
        self.battery_bar.setValue(self.metrics.battery_level)
        self.battery_bar.setFormat(f"{self.metrics.battery_level}%")
        
        # Update battery color based on level
        if self.metrics.battery_level < 20:
            self.battery_bar.setStyleSheet("QProgressBar::chunk { background-color: #f44336; }")
        elif self.metrics.battery_level < 50:
            self.battery_bar.setStyleSheet("QProgressBar::chunk { background-color: #FF9800; }")
        else:
            self.battery_bar.setStyleSheet("QProgressBar::chunk { background-color: #4CAF50; }")
            
        # Storage
        if self.metrics.storage_total > 0:
            storage_used_percent = int((1 - self.metrics.storage_free / self.metrics.storage_total) * 100)
            self.storage_bar.setValue(storage_used_percent)
            self.storage_bar.setFormat(f"{storage_used_percent}% used")
        
        # Network
        self.network_bar.setValue(self.metrics.network_strength)
        self.network_bar.setFormat(f"{self.metrics.network_strength}%")
        
        # Performance metrics
        self.cpu_label.setText(f"{self.metrics.cpu_usage}%")
        self.memory_label.setText(f"{self.metrics.memory_usage}%")
        self.temp_label.setText(f"{self.metrics.temperature}°C")
        
        # Data rate
        if self.metrics.data_rate < 1024:
            self.data_rate_label.setText(f"{self.metrics.data_rate:.1f} B/s")
        elif self.metrics.data_rate < 1024 * 1024:
            self.data_rate_label.setText(f"{self.metrics.data_rate/1024:.1f} KB/s")
        else:
            self.data_rate_label.setText(f"{self.metrics.data_rate/(1024*1024):.1f} MB/s")
            
        # Sync quality
        self.sync_label.setText(f"{self.metrics.sync_quality}%")
        if self.metrics.sync_quality < 70:
            self.sync_label.setStyleSheet("color: #f44336;")
        elif self.metrics.sync_quality < 90:
            self.sync_label.setStyleSheet("color: #FF9800;")
        else:
            self.sync_label.setStyleSheet("color: #4CAF50;")
            
        # Error count
        self.error_label.setText(str(self.metrics.error_count))
        if self.metrics.error_count > 0:
            self.error_label.setStyleSheet("color: #f44336; font-weight: bold;")
        else:
            self.error_label.setStyleSheet("color: #4CAF50;")
            
    def get_status_style(self, status: DeviceStatus) -> str:
        """Get CSS style for device status."""
        if status == DeviceStatus.CONNECTED:
            return "color: #4CAF50; font-weight: bold;"
        elif status == DeviceStatus.CONNECTING:
            return "color: #FF9800; font-weight: bold;"
        elif status == DeviceStatus.RECORDING:
            return "color: #f44336; font-weight: bold;"
        else:
            return "color: #9E9E9E;"


class DeviceHealthWidget(QWidget):
    """Main device health monitoring widget."""
    
    # Signals
    configure_device = pyqtSignal(str)  # device_id
    show_device_details = pyqtSignal(str)  # device_id
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.device_cards = {}  # device_id -> DeviceHealthCard
        self.setup_ui()
        
    def setup_ui(self):
        """Setup the user interface."""
        layout = QVBoxLayout(self)
        
        # Header
        header_layout = QHBoxLayout()
        title_label = QLabel("Device Health Monitor")
        title_label.setFont(QFont("Arial", 14, QFont.Weight.Bold))
        header_layout.addWidget(title_label)
        header_layout.addStretch()
        
        # Refresh button
        refresh_btn = QPushButton("Refresh")
        refresh_btn.clicked.connect(self.refresh_all)
        header_layout.addWidget(refresh_btn)
        
        layout.addLayout(header_layout)
        
        # Scroll area for device cards
        scroll_area = QScrollArea()
        scroll_area.setWidgetResizable(True)
        scroll_area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        
        # Container widget for cards
        self.cards_container = QWidget()
        self.cards_layout = QGridLayout(self.cards_container)
        self.cards_layout.setSpacing(8)
        
        scroll_area.setWidget(self.cards_container)
        layout.addWidget(scroll_area)
        
    def add_device(self, device: AndroidDevice):
        """Add a device to the health monitor."""
        if device.device_id not in self.device_cards:
            card = DeviceHealthCard(device)
            card.configure_requested.connect(self.configure_device)
            card.details_requested.connect(self.show_device_details)
            
            self.device_cards[device.device_id] = card
            self.update_layout()
            
    def remove_device(self, device_id: str):
        """Remove a device from the health monitor."""
        if device_id in self.device_cards:
            card = self.device_cards[device_id]
            self.cards_layout.removeWidget(card)
            card.deleteLater()
            del self.device_cards[device_id]
            self.update_layout()
            
    def update_device_metrics(self, device_id: str, metrics: DeviceHealthMetrics):
        """Update metrics for a specific device."""
        if device_id in self.device_cards:
            self.device_cards[device_id].update_metrics(metrics)
            
    def update_device_status(self, device_id: str, status: DeviceStatus):
        """Update status for a specific device."""
        if device_id in self.device_cards:
            self.device_cards[device_id].device.status = status
            
    def update_layout(self):
        """Update the grid layout of device cards."""
        # Calculate optimal grid layout
        num_cards = len(self.device_cards)
        if num_cards == 0:
            return
            
        # Aim for roughly square grid, but prefer more columns
        cols = max(1, int((num_cards ** 0.5) + 0.5))
        if cols * (cols - 1) >= num_cards:
            cols -= 1
        cols = max(1, min(cols, 4))  # Limit to 4 columns max
        
        # Clear current layout
        for i in reversed(range(self.cards_layout.count())):
            self.cards_layout.itemAt(i).widget().setParent(None)
            
        # Add cards to grid
        row, col = 0, 0
        for card in self.device_cards.values():
            self.cards_layout.addWidget(card, row, col)
            col += 1
            if col >= cols:
                col = 0
                row += 1
                
    def refresh_all(self):
        """Refresh all device health information."""
        # This would typically trigger a refresh request to all devices
        logging.info("Refreshing device health information")
        
    def get_device_count(self) -> int:
        """Get the number of monitored devices."""
        return len(self.device_cards)
        
    def get_connected_device_count(self) -> int:
        """Get the number of connected devices."""
        return sum(1 for card in self.device_cards.values() 
                  if card.device.status == DeviceStatus.CONNECTED)
                  
    def get_recording_device_count(self) -> int:
        """Get the number of recording devices."""
        return sum(1 for card in self.device_cards.values() 
                  if card.device.status == DeviceStatus.RECORDING)