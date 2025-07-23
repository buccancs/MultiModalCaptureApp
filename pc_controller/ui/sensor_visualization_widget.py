#!/usr/bin/env python3
"""
Sensor Visualization Widget for PC Controller Application
Provides real-time plotting and monitoring of sensor data from Android devices.
"""

import logging
import numpy as np
from typing import Dict, Any, Optional, List, Tuple
from datetime import datetime, timedelta
from collections import deque
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QPushButton, QComboBox, QCheckBox, QGroupBox,
    QSplitter, QTabWidget, QSlider, QSpinBox, QDoubleSpinBox
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QThread, pyqtSlot
from PyQt6.QtGui import QFont, QColor, QPalette

# Import plotting library (would need to be installed)
try:
    import pyqtgraph as pg
    from pyqtgraph import PlotWidget, mkPen, mkBrush
    PLOTTING_AVAILABLE = True
except ImportError:
    PLOTTING_AVAILABLE = False
    logging.warning("PyQtGraph not available - sensor visualization will be limited")

from core.device_manager import AndroidDevice, DeviceStatus


class SensorDataBuffer:
    """Buffer for storing and managing sensor data."""
    
    def __init__(self, max_size: int = 1000):
        self.max_size = max_size
        self.timestamps = deque(maxlen=max_size)
        self.values = deque(maxlen=max_size)
        self.device_id = ""
        self.sensor_type = ""
        
    def add_data_point(self, timestamp: float, value: float):
        """Add a new data point to the buffer."""
        self.timestamps.append(timestamp)
        self.values.append(value)
        
    def get_data(self, time_window: Optional[float] = None) -> Tuple[List[float], List[float]]:
        """Get data within the specified time window."""
        if not self.timestamps:
            return [], []
            
        if time_window is None:
            return list(self.timestamps), list(self.values)
            
        # Filter data within time window
        current_time = self.timestamps[-1] if self.timestamps else 0
        cutoff_time = current_time - time_window
        
        filtered_timestamps = []
        filtered_values = []
        
        for ts, val in zip(self.timestamps, self.values):
            if ts >= cutoff_time:
                filtered_timestamps.append(ts)
                filtered_values.append(val)
                
        return filtered_timestamps, filtered_values
        
    def clear(self):
        """Clear all data from the buffer."""
        self.timestamps.clear()
        self.values.clear()
        
    def get_latest_value(self) -> Optional[float]:
        """Get the most recent value."""
        return self.values[-1] if self.values else None
        
    def get_statistics(self) -> Dict[str, float]:
        """Get basic statistics for the current data."""
        if not self.values:
            return {'mean': 0, 'std': 0, 'min': 0, 'max': 0, 'count': 0}
            
        values_array = np.array(list(self.values))
        return {
            'mean': float(np.mean(values_array)),
            'std': float(np.std(values_array)),
            'min': float(np.min(values_array)),
            'max': float(np.max(values_array)),
            'count': len(values_array)
        }


class SensorPlotWidget(QWidget):
    """Widget for plotting individual sensor data."""
    
    def __init__(self, sensor_type: str, device_id: str, parent=None):
        super().__init__(parent)
        self.sensor_type = sensor_type
        self.device_id = device_id
        self.data_buffer = SensorDataBuffer()
        self.data_buffer.device_id = device_id
        self.data_buffer.sensor_type = sensor_type
        
        self.setup_ui()
        self.setup_plot()
        
        # Update timer
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_plot)
        self.update_timer.start(100)  # Update every 100ms
        
    def setup_ui(self):
        """Setup the user interface."""
        layout = QVBoxLayout(self)
        
        # Header with sensor info and controls
        header_layout = QHBoxLayout()
        
        # Sensor info
        info_label = QLabel(f"{self.sensor_type} - {self.device_id}")
        info_label.setFont(QFont("Arial", 10, QFont.Weight.Bold))
        header_layout.addWidget(info_label)
        
        header_layout.addStretch()
        
        # Controls
        self.auto_scale_check = QCheckBox("Auto Scale")
        self.auto_scale_check.setChecked(True)
        header_layout.addWidget(self.auto_scale_check)
        
        self.freeze_check = QCheckBox("Freeze")
        header_layout.addWidget(self.freeze_check)
        
        clear_btn = QPushButton("Clear")
        clear_btn.clicked.connect(self.clear_data)
        header_layout.addWidget(clear_btn)
        
        layout.addLayout(header_layout)
        
        # Plot widget
        if PLOTTING_AVAILABLE:
            self.plot_widget = PlotWidget()
            self.plot_widget.setLabel('left', self.get_y_label())
            self.plot_widget.setLabel('bottom', 'Time (s)')
            self.plot_widget.showGrid(x=True, y=True)
            layout.addWidget(self.plot_widget)
        else:
            # Fallback for when PyQtGraph is not available
            fallback_label = QLabel("Plotting library not available")
            fallback_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
            fallback_label.setStyleSheet("color: gray; font-style: italic;")
            layout.addWidget(fallback_label)
            
        # Statistics panel
        self.create_statistics_panel(layout)
        
    def create_statistics_panel(self, parent_layout):
        """Create statistics display panel."""
        stats_group = QGroupBox("Statistics")
        stats_layout = QHBoxLayout(stats_group)
        
        self.mean_label = QLabel("Mean: --")
        stats_layout.addWidget(self.mean_label)
        
        self.std_label = QLabel("Std: --")
        stats_layout.addWidget(self.std_label)
        
        self.min_label = QLabel("Min: --")
        stats_layout.addWidget(self.min_label)
        
        self.max_label = QLabel("Max: --")
        stats_layout.addWidget(self.max_label)
        
        self.count_label = QLabel("Count: --")
        stats_layout.addWidget(self.count_label)
        
        parent_layout.addWidget(stats_group)
        
    def setup_plot(self):
        """Setup the plot configuration."""
        if not PLOTTING_AVAILABLE:
            return
            
        # Configure plot appearance
        self.plot_widget.setBackground('w')  # White background
        
        # Create plot curve
        pen_color = self.get_plot_color()
        self.plot_curve = self.plot_widget.plot(
            pen=mkPen(color=pen_color, width=2),
            name=f"{self.sensor_type}"
        )
        
        # Set initial range
        y_range = self.get_y_range()
        if y_range:
            self.plot_widget.setYRange(y_range[0], y_range[1])
            
    def get_plot_color(self) -> str:
        """Get the plot color for this sensor type."""
        color_map = {
            'GSR': '#2E8B57',      # Sea Green
            'PPG': '#DC143C',      # Crimson
            'Heart Rate': '#FF6347', # Tomato
            'Temperature': '#FF8C00', # Dark Orange
            'Accelerometer': '#4169E1', # Royal Blue
            'Gyroscope': '#9932CC',    # Dark Orchid
            'Magnetometer': '#FF1493'  # Deep Pink
        }
        return color_map.get(self.sensor_type, '#000000')
        
    def get_y_label(self) -> str:
        """Get the Y-axis label for this sensor type."""
        label_map = {
            'GSR': 'Conductance (μS)',
            'PPG': 'Amplitude',
            'Heart Rate': 'BPM',
            'Temperature': 'Temperature (°C)',
            'Accelerometer': 'Acceleration (m/s²)',
            'Gyroscope': 'Angular Velocity (rad/s)',
            'Magnetometer': 'Magnetic Field (μT)'
        }
        return label_map.get(self.sensor_type, 'Value')
        
    def get_y_range(self) -> Optional[Tuple[float, float]]:
        """Get the default Y-axis range for this sensor type."""
        range_map = {
            'GSR': (0, 50),
            'PPG': (-1000, 1000),
            'Heart Rate': (50, 150),
            'Temperature': (20, 40),
            'Accelerometer': (-20, 20),
            'Gyroscope': (-10, 10),
            'Magnetometer': (-100, 100)
        }
        return range_map.get(self.sensor_type)
        
    def add_data_point(self, timestamp: float, value: float):
        """Add a new data point."""
        self.data_buffer.add_data_point(timestamp, value)
        
    def update_plot(self):
        """Update the plot with current data."""
        if not PLOTTING_AVAILABLE or self.freeze_check.isChecked():
            return
            
        # Get data for plotting (last 30 seconds)
        timestamps, values = self.data_buffer.get_data(time_window=30.0)
        
        if not timestamps:
            return
            
        # Convert timestamps to relative time (seconds from start)
        if timestamps:
            start_time = timestamps[0]
            relative_times = [(ts - start_time) for ts in timestamps]
            
            # Update plot
            self.plot_curve.setData(relative_times, values)
            
            # Auto-scale if enabled
            if self.auto_scale_check.isChecked():
                self.plot_widget.enableAutoRange()
                
        # Update statistics
        self.update_statistics()
        
    def update_statistics(self):
        """Update the statistics display."""
        stats = self.data_buffer.get_statistics()
        
        self.mean_label.setText(f"Mean: {stats['mean']:.2f}")
        self.std_label.setText(f"Std: {stats['std']:.2f}")
        self.min_label.setText(f"Min: {stats['min']:.2f}")
        self.max_label.setText(f"Max: {stats['max']:.2f}")
        self.count_label.setText(f"Count: {stats['count']}")
        
    def clear_data(self):
        """Clear all data from the plot."""
        self.data_buffer.clear()
        if PLOTTING_AVAILABLE:
            self.plot_curve.setData([], [])
        self.update_statistics()
        
    def set_time_window(self, seconds: float):
        """Set the time window for data display."""
        # This could be implemented to change the visible time range
        pass


class DeviceSensorPanel(QWidget):
    """Panel showing all sensors for a single device."""
    
    def __init__(self, device: AndroidDevice, parent=None):
        super().__init__(parent)
        self.device = device
        self.sensor_plots = {}  # sensor_type -> SensorPlotWidget
        self.setup_ui()
        
    def setup_ui(self):
        """Setup the user interface."""
        layout = QVBoxLayout(self)
        
        # Device header
        header_layout = QHBoxLayout()
        
        device_label = QLabel(f"Device: {self.device.name}")
        device_label.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        header_layout.addWidget(device_label)
        
        header_layout.addStretch()
        
        # Device status indicator
        self.status_label = QLabel(self.device.status.value)
        self.status_label.setStyleSheet(self.get_status_style(self.device.status))
        header_layout.addWidget(self.status_label)
        
        layout.addLayout(header_layout)
        
        # Sensor plots in tabs
        self.tab_widget = QTabWidget()
        layout.addWidget(self.tab_widget)
        
        # Initialize common sensor plots
        self.add_sensor_plot('GSR')
        self.add_sensor_plot('PPG')
        self.add_sensor_plot('Heart Rate')
        
    def add_sensor_plot(self, sensor_type: str):
        """Add a sensor plot tab."""
        if sensor_type not in self.sensor_plots:
            plot_widget = SensorPlotWidget(sensor_type, self.device.device_id)
            self.sensor_plots[sensor_type] = plot_widget
            self.tab_widget.addTab(plot_widget, sensor_type)
            
    def remove_sensor_plot(self, sensor_type: str):
        """Remove a sensor plot tab."""
        if sensor_type in self.sensor_plots:
            widget = self.sensor_plots[sensor_type]
            index = self.tab_widget.indexOf(widget)
            if index >= 0:
                self.tab_widget.removeTab(index)
            del self.sensor_plots[sensor_type]
            
    def update_sensor_data(self, sensor_type: str, timestamp: float, value: float):
        """Update sensor data for a specific sensor type."""
        if sensor_type in self.sensor_plots:
            self.sensor_plots[sensor_type].add_data_point(timestamp, value)
        else:
            # Auto-add new sensor types
            self.add_sensor_plot(sensor_type)
            if sensor_type in self.sensor_plots:
                self.sensor_plots[sensor_type].add_data_point(timestamp, value)
                
    def update_device_status(self, status: DeviceStatus):
        """Update device status display."""
        self.device.status = status
        self.status_label.setText(status.value)
        self.status_label.setStyleSheet(self.get_status_style(status))
        
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
            
    def clear_all_data(self):
        """Clear data from all sensor plots."""
        for plot_widget in self.sensor_plots.values():
            plot_widget.clear_data()


class SensorVisualizationWidget(QWidget):
    """Main sensor visualization widget."""
    
    # Signals
    sensor_data_received = pyqtSignal(str, str, float, float)  # device_id, sensor_type, timestamp, value
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.device_panels = {}  # device_id -> DeviceSensorPanel
        self.setup_ui()
        
        # Connect to sensor data signal
        self.sensor_data_received.connect(self.handle_sensor_data)
        
    def setup_ui(self):
        """Setup the user interface."""
        layout = QVBoxLayout(self)
        
        # Header with controls
        self.create_header(layout)
        
        # Main content area
        if PLOTTING_AVAILABLE:
            # Scrollable area for device panels
            self.tab_widget = QTabWidget()
            self.tab_widget.setTabPosition(QTabWidget.TabPosition.North)
            layout.addWidget(self.tab_widget)
        else:
            # Fallback message
            fallback_label = QLabel(
                "Sensor visualization requires PyQtGraph library.\n"
                "Install with: pip install pyqtgraph"
            )
            fallback_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
            fallback_label.setStyleSheet("color: gray; font-size: 14px;")
            layout.addWidget(fallback_label)
            
    def create_header(self, parent_layout):
        """Create header with controls."""
        header_layout = QHBoxLayout()
        
        # Title
        title_label = QLabel("Live Sensor Data")
        title_label.setFont(QFont("Arial", 14, QFont.Weight.Bold))
        header_layout.addWidget(title_label)
        
        header_layout.addStretch()
        
        # Global controls
        if PLOTTING_AVAILABLE:
            # Time window control
            header_layout.addWidget(QLabel("Time Window:"))
            self.time_window_combo = QComboBox()
            self.time_window_combo.addItems(["10s", "30s", "1m", "5m", "10m"])
            self.time_window_combo.setCurrentText("30s")
            header_layout.addWidget(self.time_window_combo)
            
            # Clear all button
            clear_all_btn = QPushButton("Clear All")
            clear_all_btn.clicked.connect(self.clear_all_data)
            header_layout.addWidget(clear_all_btn)
            
            # Export button
            export_btn = QPushButton("Export Data")
            export_btn.clicked.connect(self.export_data)
            header_layout.addWidget(export_btn)
        
        parent_layout.addLayout(header_layout)
        
    def add_device(self, device: AndroidDevice):
        """Add a device to the visualization."""
        if not PLOTTING_AVAILABLE:
            return
            
        if device.device_id not in self.device_panels:
            panel = DeviceSensorPanel(device)
            self.device_panels[device.device_id] = panel
            self.tab_widget.addTab(panel, device.name)
            
    def remove_device(self, device_id: str):
        """Remove a device from the visualization."""
        if device_id in self.device_panels:
            panel = self.device_panels[device_id]
            index = self.tab_widget.indexOf(panel)
            if index >= 0:
                self.tab_widget.removeTab(index)
            del self.device_panels[device_id]
            
    @pyqtSlot(str, str, float, float)
    def handle_sensor_data(self, device_id: str, sensor_type: str, timestamp: float, value: float):
        """Handle incoming sensor data."""
        if device_id in self.device_panels:
            self.device_panels[device_id].update_sensor_data(sensor_type, timestamp, value)
            
    def update_device_status(self, device_id: str, status: DeviceStatus):
        """Update device status."""
        if device_id in self.device_panels:
            self.device_panels[device_id].update_device_status(status)
            
    def clear_all_data(self):
        """Clear data from all device panels."""
        for panel in self.device_panels.values():
            panel.clear_all_data()
            
    def export_data(self):
        """Export sensor data to file."""
        # This would implement data export functionality
        logging.info("Data export functionality would be implemented here")
        
    def get_device_count(self) -> int:
        """Get the number of devices being visualized."""
        return len(self.device_panels)
        
    def simulate_data(self, device_id: str):
        """Simulate sensor data for testing (development only)."""
        if not PLOTTING_AVAILABLE:
            return
            
        import time
        import math
        
        current_time = time.time()
        
        # Simulate GSR data
        gsr_value = 10 + 5 * math.sin(current_time * 0.1) + np.random.normal(0, 0.5)
        self.sensor_data_received.emit(device_id, 'GSR', current_time, gsr_value)
        
        # Simulate PPG data
        ppg_value = 100 * math.sin(current_time * 2) + np.random.normal(0, 10)
        self.sensor_data_received.emit(device_id, 'PPG', current_time, ppg_value)
        
        # Simulate heart rate
        hr_value = 75 + 10 * math.sin(current_time * 0.05) + np.random.normal(0, 2)
        self.sensor_data_received.emit(device_id, 'Heart Rate', current_time, max(50, min(150, hr_value)))