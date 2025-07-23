"""
Main Window UI for PC Controller Application.
Provides the primary graphical interface for device management and recording control.
"""

import logging
from typing import Dict, List, Optional
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QPushButton, QLabel, QTableWidget, QTableWidgetItem, QTextEdit,
    QGroupBox, QProgressBar, QStatusBar, QMenuBar, QMenu, QMessageBox,
    QSplitter, QFrame, QScrollArea, QTabWidget
)
from PyQt6.QtCore import Qt, QTimer, pyqtSlot
from PyQt6.QtGui import QAction, QIcon, QPixmap, QFont

from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from core.recording_controller import RecordingController, RecordingState
from utils.config import Config
from ui.video_series_widget import VideoSeriesWidget
from ui.live_preview_widget import LivePreviewWidget

class DeviceStatusWidget(QWidget):
    """Widget for displaying individual device status."""
    
    def __init__(self, device: AndroidDevice):
        super().__init__()
        self.device = device
        self.setup_ui()
    
    def setup_ui(self):
        """Setup the device status UI."""
        layout = QVBoxLayout(self)
        
        # Device header
        header_layout = QHBoxLayout()
        
        self.device_name_label = QLabel(self.device.device_name)
        self.device_name_label.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        header_layout.addWidget(self.device_name_label)
        
        header_layout.addStretch()
        
        self.status_label = QLabel(self.device.status.value.title())
        self.status_label.setStyleSheet(self._get_status_style(self.device.status))
        header_layout.addWidget(self.status_label)
        
        layout.addLayout(header_layout)
        
        # Device details
        details_layout = QGridLayout()
        
        details_layout.addWidget(QLabel("Device ID:"), 0, 0)
        details_layout.addWidget(QLabel(self.device.device_id), 0, 1)
        
        details_layout.addWidget(QLabel("IP Address:"), 1, 0)
        details_layout.addWidget(QLabel(self.device.ip_address), 1, 1)
        
        details_layout.addWidget(QLabel("Capabilities:"), 2, 0)
        capabilities_text = ", ".join(self.device.capabilities)
        details_layout.addWidget(QLabel(capabilities_text), 2, 1)
        
        layout.addLayout(details_layout)
        
        # Control buttons
        button_layout = QHBoxLayout()
        
        self.connect_button = QPushButton("Connect")
        self.connect_button.clicked.connect(self._on_connect_clicked)
        button_layout.addWidget(self.connect_button)
        
        self.disconnect_button = QPushButton("Disconnect")
        self.disconnect_button.clicked.connect(self._on_disconnect_clicked)
        button_layout.addWidget(self.disconnect_button)
        
        layout.addLayout(button_layout)
        
        # Update button states
        self._update_button_states()
        
        # Set frame style
        self.setFrameStyle(QFrame.Shape.Box)
        self.setLineWidth(1)
    
    def _get_status_style(self, status: DeviceStatus) -> str:
        """Get CSS style for status label."""
        color_map = {
            DeviceStatus.CONNECTED: "color: green; font-weight: bold;",
            DeviceStatus.RECORDING: "color: red; font-weight: bold;",
            DeviceStatus.CONNECTING: "color: orange; font-weight: bold;",
            DeviceStatus.ERROR: "color: red; font-weight: bold;",
            DeviceStatus.DISCONNECTED: "color: gray;",
            DeviceStatus.DISCOVERED: "color: blue;"
        }
        return color_map.get(status, "color: black;")
    
    def _update_button_states(self):
        """Update button enabled states based on device status."""
        is_connected = self.device.status == DeviceStatus.CONNECTED
        is_recording = self.device.status == DeviceStatus.RECORDING
        
        self.connect_button.setEnabled(not is_connected and not is_recording)
        self.disconnect_button.setEnabled(is_connected or is_recording)
    
    def _on_connect_clicked(self):
        """Handle connect button click."""
        # This will be connected to the main window's device manager
        pass
    
    def _on_disconnect_clicked(self):
        """Handle disconnect button click."""
        # This will be connected to the main window's device manager
        pass
    
    def update_device(self, device: AndroidDevice):
        """Update the widget with new device information."""
        self.device = device
        self.device_name_label.setText(device.device_name)
        self.status_label.setText(device.status.value.title())
        self.status_label.setStyleSheet(self._get_status_style(device.status))
        self._update_button_states()

class RecordingControlWidget(QWidget):
    """Widget for recording control and status."""
    
    def __init__(self):
        super().__init__()
        self.setup_ui()
    
    def setup_ui(self):
        """Setup the recording control UI."""
        layout = QVBoxLayout(self)
        
        # Recording status
        status_group = QGroupBox("Recording Status")
        status_layout = QVBoxLayout(status_group)
        
        self.recording_state_label = QLabel("Idle")
        self.recording_state_label.setFont(QFont("Arial", 14, QFont.Weight.Bold))
        self.recording_state_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        status_layout.addWidget(self.recording_state_label)
        
        self.session_info_label = QLabel("No active session")
        self.session_info_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        status_layout.addWidget(self.session_info_label)
        
        layout.addWidget(status_group)
        
        # Control buttons
        control_group = QGroupBox("Recording Control")
        control_layout = QVBoxLayout(control_group)
        
        button_layout = QHBoxLayout()
        
        self.start_button = QPushButton("Start Recording")
        self.start_button.setMinimumHeight(40)
        self.start_button.setStyleSheet("QPushButton { background-color: green; color: white; font-weight: bold; }")
        button_layout.addWidget(self.start_button)
        
        self.stop_button = QPushButton("Stop Recording")
        self.stop_button.setMinimumHeight(40)
        self.stop_button.setStyleSheet("QPushButton { background-color: red; color: white; font-weight: bold; }")
        self.stop_button.setEnabled(False)
        button_layout.addWidget(self.stop_button)
        
        control_layout.addLayout(button_layout)
        
        # Pause/Resume buttons
        pause_layout = QHBoxLayout()
        
        self.pause_button = QPushButton("Pause")
        self.pause_button.setEnabled(False)
        pause_layout.addWidget(self.pause_button)
        
        self.resume_button = QPushButton("Resume")
        self.resume_button.setEnabled(False)
        pause_layout.addWidget(self.resume_button)
        
        control_layout.addLayout(pause_layout)
        
        layout.addWidget(control_group)
        
        # Recording progress
        progress_group = QGroupBox("Recording Progress")
        progress_layout = QVBoxLayout(progress_group)
        
        self.progress_bar = QProgressBar()
        self.progress_bar.setVisible(False)
        progress_layout.addWidget(self.progress_bar)
        
        self.duration_label = QLabel("Duration: 00:00:00")
        progress_layout.addWidget(self.duration_label)
        
        layout.addWidget(progress_group)
    
    def update_recording_state(self, state: RecordingState):
        """Update the recording state display."""
        state_text = state.value.title()
        self.recording_state_label.setText(state_text)
        
        # Update button states
        is_idle = state == RecordingState.IDLE
        is_recording = state == RecordingState.RECORDING
        is_preparing = state == RecordingState.PREPARING
        
        self.start_button.setEnabled(is_idle)
        self.stop_button.setEnabled(is_recording or is_preparing)
        self.pause_button.setEnabled(is_recording)
        self.resume_button.setEnabled(False)  # Will be enabled when paused
        
        # Update progress bar visibility
        self.progress_bar.setVisible(is_recording or is_preparing)
        
        # Update state label color
        color_map = {
            RecordingState.IDLE: "color: black;",
            RecordingState.PREPARING: "color: orange;",
            RecordingState.RECORDING: "color: red;",
            RecordingState.STOPPING: "color: orange;",
            RecordingState.ERROR: "color: red;"
        }
        style = color_map.get(state, "color: black;")
        self.recording_state_label.setStyleSheet(style)

class MainWindow(QMainWindow):
    """Main window for the PC Controller application."""
    
    def __init__(self, device_manager: DeviceManager, recording_controller: RecordingController, config: Config):
        super().__init__()
        
        self.device_manager = device_manager
        self.recording_controller = recording_controller
        self.config = config
        
        # UI components
        self.device_widgets: Dict[str, DeviceStatusWidget] = {}
        self.log_widget = None
        self.recording_widget = None
        self.video_series_widget = None
        self.live_preview_widget = None
        
        # Timers
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self._update_ui)
        self.update_timer.start(1000)  # Update every second
        
        self.setup_ui()
        self.connect_signals()
        
        logging.info("MainWindow initialized")
    
    def setup_ui(self):
        """Setup the main window UI."""
        self.setWindowTitle("MultiModal Capture Controller")
        self.setMinimumSize(1200, 800)
        
        # Apply window size from config
        self.resize(self.config.ui.window_width, self.config.ui.window_height)
        
        # Create menu bar
        self._create_menu_bar()
        
        # Create status bar
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.status_bar.showMessage("Ready")
        
        # Create central widget with tabs
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        
        layout = QVBoxLayout(central_widget)
        
        # Create tab widget
        self.tab_widget = QTabWidget()
        layout.addWidget(self.tab_widget)
        
        # Create tabs
        self._create_devices_tab()
        self._create_recording_tab()
        self._create_video_series_tab()
        self._create_live_preview_tab()
        self._create_logs_tab()
    
    def _create_menu_bar(self):
        """Create the application menu bar."""
        menubar = self.menuBar()
        
        # File menu
        file_menu = menubar.addMenu("File")
        
        settings_action = QAction("Settings", self)
        settings_action.triggered.connect(self._show_settings)
        file_menu.addAction(settings_action)
        
        file_menu.addSeparator()
        
        exit_action = QAction("Exit", self)
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)
        
        # View menu
        view_menu = menubar.addMenu("View")
        
        refresh_action = QAction("Refresh Devices", self)
        refresh_action.triggered.connect(self._refresh_devices)
        view_menu.addAction(refresh_action)
        
        # Help menu
        help_menu = menubar.addMenu("Help")
        
        about_action = QAction("About", self)
        about_action.triggered.connect(self._show_about)
        help_menu.addAction(about_action)
    
    def _create_devices_tab(self):
        """Create the devices management tab."""
        devices_widget = QWidget()
        layout = QVBoxLayout(devices_widget)
        
        # Header
        header_layout = QHBoxLayout()
        header_layout.addWidget(QLabel("Connected Devices"))
        header_layout.addStretch()
        
        refresh_button = QPushButton("Refresh")
        refresh_button.clicked.connect(self._refresh_devices)
        header_layout.addWidget(refresh_button)
        
        layout.addLayout(header_layout)
        
        # Devices scroll area
        scroll_area = QScrollArea()
        scroll_area.setWidgetResizable(True)
        scroll_area.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        
        self.devices_container = QWidget()
        self.devices_layout = QVBoxLayout(self.devices_container)
        self.devices_layout.addStretch()
        
        scroll_area.setWidget(self.devices_container)
        layout.addWidget(scroll_area)
        
        self.tab_widget.addTab(devices_widget, "Devices")
    
    def _create_recording_tab(self):
        """Create the recording control tab."""
        recording_widget = QWidget()
        layout = QHBoxLayout(recording_widget)
        
        # Recording control
        self.recording_widget = RecordingControlWidget()
        layout.addWidget(self.recording_widget)
        
        # Device recording status
        status_group = QGroupBox("Device Status")
        status_layout = QVBoxLayout(status_group)
        
        self.device_status_table = QTableWidget()
        self.device_status_table.setColumnCount(4)
        self.device_status_table.setHorizontalHeaderLabels(["Device", "Status", "Recording", "Files"])
        status_layout.addWidget(self.device_status_table)
        
        layout.addWidget(status_group)
        
        self.tab_widget.addTab(recording_widget, "Recording")
    
    def _create_logs_tab(self):
        """Create the logs viewing tab."""
        logs_widget = QWidget()
        layout = QVBoxLayout(logs_widget)
        
        # Log controls
        controls_layout = QHBoxLayout()
        
        clear_button = QPushButton("Clear Logs")
        clear_button.clicked.connect(self._clear_logs)
        controls_layout.addWidget(clear_button)
        
        controls_layout.addStretch()
        
        layout.addLayout(controls_layout)
        
        # Log display
        self.log_widget = QTextEdit()
        self.log_widget.setReadOnly(True)
        self.log_widget.setFont(QFont("Consolas", 9))
        layout.addWidget(self.log_widget)
        
        self.tab_widget.addTab(logs_widget, "Logs")
    
    def _create_video_series_tab(self):
        """Create the video series playback tab."""
        # Create and initialize the video series widget
        self.video_series_widget = VideoSeriesWidget(self.config)
        
        # Connect video series signals
        self.video_series_widget.video_started.connect(self._on_video_started)
        self.video_series_widget.video_finished.connect(self._on_video_finished)
        self.video_series_widget.video_switched.connect(self._on_video_switched)
        self.video_series_widget.playlist_loaded.connect(self._on_playlist_loaded)
        
        # Add tab
        self.tab_widget.addTab(self.video_series_widget, "Video Series")
    
    def _create_live_preview_tab(self):
        """Create the live video preview tab."""
        # Create and initialize the live preview widget
        self.live_preview_widget = LivePreviewWidget(self.config, self.device_manager)
        
        # Connect live preview signals
        self.live_preview_widget.preview_started.connect(self._on_preview_started)
        self.live_preview_widget.preview_stopped.connect(self._on_preview_stopped)
        
        # Add tab
        self.tab_widget.addTab(self.live_preview_widget, "Live Preview")
    
    def connect_signals(self):
        """Connect signals from managers to UI slots."""
        # Device manager signals
        self.device_manager.device_discovered.connect(self._on_device_discovered)
        self.device_manager.device_connected.connect(self._on_device_connected)
        self.device_manager.device_disconnected.connect(self._on_device_disconnected)
        self.device_manager.device_status_changed.connect(self._on_device_status_changed)
        
        # Recording controller signals
        self.recording_controller.recording_state_changed.connect(self._on_recording_state_changed)
        self.recording_controller.session_started.connect(self._on_session_started)
        self.recording_controller.session_stopped.connect(self._on_session_stopped)
        self.recording_controller.device_recording_status.connect(self._on_device_recording_status)
        self.recording_controller.recording_error.connect(self._on_recording_error)
        
        # Connect recording control buttons
        if self.recording_widget:
            self.recording_widget.start_button.clicked.connect(self._start_recording)
            self.recording_widget.stop_button.clicked.connect(self._stop_recording)
            self.recording_widget.pause_button.clicked.connect(self._pause_recording)
            self.recording_widget.resume_button.clicked.connect(self._resume_recording)
    
    @pyqtSlot(dict)
    def _on_device_discovered(self, device_data: dict):
        """Handle device discovered signal."""
        device_id = device_data.get('device_id', 'unknown')
        logging.info(f"Device discovered: {device_data.get('device_name', 'Unknown')} ({device_id})")
        self._update_devices_display()
    
    @pyqtSlot(str)
    def _on_device_connected(self, device_id: str):
        """Handle device connected signal."""
        logging.info(f"Device connected: {device_id}")
        self.status_bar.showMessage(f"Device connected: {device_id}")
        self._update_devices_display()
    
    @pyqtSlot(str)
    def _on_device_disconnected(self, device_id: str):
        """Handle device disconnected signal."""
        logging.info(f"Device disconnected: {device_id}")
        self.status_bar.showMessage(f"Device disconnected: {device_id}")
        self._update_devices_display()
    
    @pyqtSlot(str, str)
    def _on_device_status_changed(self, device_id: str, status: str):
        """Handle device status changed signal."""
        logging.debug(f"Device {device_id} status changed to: {status}")
        self._update_devices_display()
    
    @pyqtSlot(str)
    def _on_recording_state_changed(self, state: str):
        """Handle recording state changed signal."""
        recording_state = RecordingState(state)
        if self.recording_widget:
            self.recording_widget.update_recording_state(recording_state)
        
        self.status_bar.showMessage(f"Recording: {state.title()}")
        logging.info(f"Recording state changed to: {state}")
    
    @pyqtSlot(dict)
    def _on_session_started(self, session_data: dict):
        """Handle session started signal."""
        session_id = session_data.get('session_id', 'Unknown')
        if self.recording_widget:
            self.recording_widget.session_info_label.setText(f"Session: {session_id}")
        
        logging.info(f"Recording session started: {session_id}")
    
    @pyqtSlot(dict)
    def _on_session_stopped(self, session_data: dict):
        """Handle session stopped signal."""
        if self.recording_widget:
            self.recording_widget.session_info_label.setText("No active session")
        
        session_id = session_data.get('session_id', 'Unknown')
        duration = session_data.get('total_duration', 0)
        logging.info(f"Recording session stopped: {session_id} (Duration: {duration:.1f}s)")
    
    @pyqtSlot(str, bool)
    def _on_device_recording_status(self, device_id: str, is_recording: bool):
        """Handle device recording status signal."""
        status = "Recording" if is_recording else "Idle"
        logging.debug(f"Device {device_id} recording status: {status}")
        self._update_device_status_table()
    
    @pyqtSlot(str)
    def _on_recording_error(self, error_message: str):
        """Handle recording error signal."""
        logging.error(f"Recording error: {error_message}")
        QMessageBox.critical(self, "Recording Error", error_message)
    
    @pyqtSlot(str, float)
    def _on_video_started(self, video_path: str, timestamp: float):
        """Handle video started signal from video series widget."""
        logging.info(f"Video started: {video_path} at {timestamp:.3f}s")
        self.status_bar.showMessage(f"Video playing: {video_path.split('/')[-1]}")
        
        # Start video session in recording controller if recording
        if hasattr(self.recording_controller, 'is_recording') and self.recording_controller.is_recording():
            self.video_series_widget.start_session()
    
    @pyqtSlot(str, float)
    def _on_video_finished(self, video_path: str, timestamp: float):
        """Handle video finished signal from video series widget."""
        logging.info(f"Video finished: {video_path} at {timestamp:.3f}s")
        self.status_bar.showMessage("Video finished")
    
    @pyqtSlot(str, str, float)
    def _on_video_switched(self, from_video: str, to_video: str, timestamp: float):
        """Handle video switched signal from video series widget."""
        from_name = from_video.split('/')[-1] if from_video else "None"
        to_name = to_video.split('/')[-1] if to_video else "None"
        logging.info(f"Video switched: {from_name} -> {to_name} at {timestamp:.3f}s")
        self.status_bar.showMessage(f"Switched to: {to_name}")
    
    @pyqtSlot(int)
    def _on_playlist_loaded(self, video_count: int):
        """Handle playlist loaded signal from video series widget."""
        logging.info(f"Playlist loaded with {video_count} videos")
        self.status_bar.showMessage(f"Playlist loaded: {video_count} videos")
    
    @pyqtSlot(str)
    def _on_preview_started(self, device_id: str):
        """Handle preview started signal from live preview widget."""
        logging.info(f"Live preview started for device: {device_id}")
        self.status_bar.showMessage(f"Preview started: {device_id}")
    
    @pyqtSlot(str)
    def _on_preview_stopped(self, device_id: str):
        """Handle preview stopped signal from live preview widget."""
        logging.info(f"Live preview stopped for device: {device_id}")
        self.status_bar.showMessage(f"Preview stopped: {device_id}")
    
    def _update_devices_display(self):
        """Update the devices display."""
        # Clear existing widgets
        for widget in self.device_widgets.values():
            widget.setParent(None)
        self.device_widgets.clear()
        
        # Add current devices
        devices = self.device_manager.get_all_devices()
        for device in devices:
            widget = DeviceStatusWidget(device)
            
            # Connect device control buttons
            widget.connect_button.clicked.connect(
                lambda checked, d_id=device.device_id: self.device_manager.connect_device(d_id)
            )
            widget.disconnect_button.clicked.connect(
                lambda checked, d_id=device.device_id: self.device_manager.disconnect_device(d_id)
            )
            
            self.device_widgets[device.device_id] = widget
            self.devices_layout.insertWidget(self.devices_layout.count() - 1, widget)
    
    def _update_device_status_table(self):
        """Update the device status table."""
        if not hasattr(self, 'device_status_table'):
            return
        
        devices = self.device_manager.get_all_devices()
        self.device_status_table.setRowCount(len(devices))
        
        for row, device in enumerate(devices):
            self.device_status_table.setItem(row, 0, QTableWidgetItem(device.device_name))
            self.device_status_table.setItem(row, 1, QTableWidgetItem(device.status.value.title()))
            
            # Recording status
            recording_status = self.recording_controller.get_device_recording_status(device.device_id)
            is_recording = recording_status.is_recording if recording_status else False
            self.device_status_table.setItem(row, 2, QTableWidgetItem("Yes" if is_recording else "No"))
            
            # Files created
            files_count = len(recording_status.files_created) if recording_status and recording_status.files_created else 0
            self.device_status_table.setItem(row, 3, QTableWidgetItem(str(files_count)))
    
    def _update_ui(self):
        """Periodic UI update."""
        # Update recording duration
        if self.recording_widget and self.recording_controller.get_recording_state() == RecordingState.RECORDING:
            session = self.recording_controller.get_current_session()
            if session:
                import time
                duration = time.time() - session.start_time
                hours = int(duration // 3600)
                minutes = int((duration % 3600) // 60)
                seconds = int(duration % 60)
                self.recording_widget.duration_label.setText(f"Duration: {hours:02d}:{minutes:02d}:{seconds:02d}")
    
    def _start_recording(self):
        """Start recording on all connected devices."""
        self.recording_controller.start_recording()
    
    def _stop_recording(self):
        """Stop recording on all devices."""
        self.recording_controller.stop_recording()
    
    def _pause_recording(self):
        """Pause recording on all devices."""
        self.recording_controller.pause_recording()
    
    def _resume_recording(self):
        """Resume recording on all devices."""
        self.recording_controller.resume_recording()
    
    def _refresh_devices(self):
        """Refresh device discovery."""
        self.device_manager.stop_discovery()
        self.device_manager.start_discovery()
        self.status_bar.showMessage("Refreshing devices...")
    
    def _clear_logs(self):
        """Clear the log display."""
        if self.log_widget:
            self.log_widget.clear()
    
    def _show_settings(self):
        """Show settings dialog."""
        QMessageBox.information(self, "Settings", "Settings dialog not yet implemented")
    
    def _show_about(self):
        """Show about dialog."""
        QMessageBox.about(self, "About", 
                         "MultiModal Capture Controller v1.0\n\n"
                         "A cross-platform application for managing\n"
                         "synchronized multi-modal data capture\n"
                         "across multiple Android devices.")
    
    def closeEvent(self, event):
        """Handle window close event."""
        # Save window size to config
        self.config.ui.window_width = self.width()
        self.config.ui.window_height = self.height()
        self.config.save()
        
        # Stop any active recording
        if self.recording_controller.get_recording_state() == RecordingState.RECORDING:
            reply = QMessageBox.question(
                self, "Recording Active", 
                "Recording is currently active. Stop recording and exit?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
            )
            
            if reply == QMessageBox.StandardButton.Yes:
                self.recording_controller.stop_recording()
            else:
                event.ignore()
                return
        
        # Cleanup
        self.update_timer.stop()
        event.accept()