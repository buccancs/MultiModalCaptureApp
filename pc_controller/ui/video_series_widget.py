"""
Video Series Playback Widget for PC Controller Application.
Provides video playback functionality with playlist management and timestamping.
"""

import json
import logging
import time
from pathlib import Path
from typing import List, Dict, Optional, Any
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QLabel, 
    QSlider, QListWidget, QListWidgetItem, QGroupBox, QFileDialog,
    QMessageBox, QProgressBar, QSpinBox, QCheckBox, QTextEdit
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QUrl, QThread, pyqtSlot
from PyQt6.QtGui import QFont, QPixmap
from PyQt6.QtMultimedia import QMediaPlayer, QAudioOutput
from PyQt6.QtMultimediaWidgets import QVideoWidget

from utils.config import Config, VideoSeriesConfig


class VideoSeriesWidget(QWidget):
    """Widget for video series playback with playlist management."""
    
    # Signals
    video_started = pyqtSignal(str, float)  # video_path, timestamp
    video_finished = pyqtSignal(str, float)  # video_path, timestamp
    video_switched = pyqtSignal(str, str, float)  # from_video, to_video, timestamp
    playlist_loaded = pyqtSignal(int)  # number of videos
    
    def __init__(self, config: Config, parent=None):
        super().__init__(parent)
        self.config = config
        self.video_config = config.video_series
        
        # Media player components
        self.media_player = QMediaPlayer()
        self.audio_output = QAudioOutput()
        self.video_widget = QVideoWidget()
        
        # Playlist management
        self.playlist: List[Dict[str, Any]] = []
        self.current_video_index = 0
        self.session_start_time = 0.0
        
        # Timers
        self.position_timer = QTimer()
        self.auto_advance_timer = QTimer()
        
        # Setup UI and connections
        self.setup_ui()
        self.setup_media_player()
        self.connect_signals()
        
        # Load initial playlist if configured
        self.load_default_playlist()
        
        logging.info("Video Series Widget initialized")
    
    def setup_ui(self):
        """Setup the user interface."""
        layout = QVBoxLayout(self)
        
        # Video display area
        video_group = QGroupBox("Video Playback")
        video_layout = QVBoxLayout(video_group)
        
        # Video widget
        self.video_widget.setMinimumSize(640, 480)
        video_layout.addWidget(self.video_widget)
        
        # Video info display
        self.video_info_label = QLabel("No video loaded")
        self.video_info_label.setFont(QFont("Arial", 10))
        video_layout.addWidget(self.video_info_label)
        
        # Progress bar
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        video_layout.addWidget(self.progress_bar)
        
        # Position slider
        self.position_slider = QSlider(Qt.Orientation.Horizontal)
        self.position_slider.setRange(0, 0)
        video_layout.addWidget(self.position_slider)
        
        layout.addWidget(video_group)
        
        # Control panel
        control_group = QGroupBox("Playback Controls")
        control_layout = QVBoxLayout(control_group)
        
        # Main controls
        main_controls = QHBoxLayout()
        
        self.play_button = QPushButton("Play")
        self.pause_button = QPushButton("Pause")
        self.stop_button = QPushButton("Stop")
        self.previous_button = QPushButton("Previous")
        self.next_button = QPushButton("Next")
        
        main_controls.addWidget(self.play_button)
        main_controls.addWidget(self.pause_button)
        main_controls.addWidget(self.stop_button)
        main_controls.addWidget(self.previous_button)
        main_controls.addWidget(self.next_button)
        main_controls.addStretch()
        
        control_layout.addLayout(main_controls)
        
        # Auto-advance controls
        auto_controls = QHBoxLayout()
        
        self.auto_advance_checkbox = QCheckBox("Auto Advance")
        self.auto_advance_checkbox.setChecked(self.video_config.auto_advance)
        
        auto_controls.addWidget(QLabel("Duration (s):"))
        self.duration_spinbox = QSpinBox()
        self.duration_spinbox.setRange(1, 3600)
        self.duration_spinbox.setValue(self.video_config.default_video_duration)
        auto_controls.addWidget(self.duration_spinbox)
        
        auto_controls.addWidget(self.auto_advance_checkbox)
        
        self.loop_checkbox = QCheckBox("Loop Playlist")
        self.loop_checkbox.setChecked(self.video_config.loop_playlist)
        auto_controls.addWidget(self.loop_checkbox)
        
        auto_controls.addStretch()
        
        control_layout.addLayout(auto_controls)
        
        layout.addWidget(control_group)
        
        # Playlist panel
        playlist_group = QGroupBox("Playlist")
        playlist_layout = QVBoxLayout(playlist_group)
        
        # Playlist controls
        playlist_controls = QHBoxLayout()
        
        self.load_playlist_button = QPushButton("Load Playlist")
        self.save_playlist_button = QPushButton("Save Playlist")
        self.add_videos_button = QPushButton("Add Videos")
        self.clear_playlist_button = QPushButton("Clear")
        
        playlist_controls.addWidget(self.load_playlist_button)
        playlist_controls.addWidget(self.save_playlist_button)
        playlist_controls.addWidget(self.add_videos_button)
        playlist_controls.addWidget(self.clear_playlist_button)
        playlist_controls.addStretch()
        
        playlist_layout.addLayout(playlist_controls)
        
        # Playlist display
        self.playlist_widget = QListWidget()
        self.playlist_widget.setMaximumHeight(150)
        playlist_layout.addWidget(self.playlist_widget)
        
        layout.addWidget(playlist_group)
        
        # Timestamp log
        log_group = QGroupBox("Event Log")
        log_layout = QVBoxLayout(log_group)
        
        self.timestamp_log = QTextEdit()
        self.timestamp_log.setMaximumHeight(100)
        self.timestamp_log.setReadOnly(True)
        log_layout.addWidget(self.timestamp_log)
        
        layout.addWidget(log_group)
    
    def setup_media_player(self):
        """Setup media player components."""
        self.media_player.setAudioOutput(self.audio_output)
        self.media_player.setVideoOutput(self.video_widget)
        
        # Set initial volume
        self.audio_output.setVolume(0.7)
    
    def connect_signals(self):
        """Connect UI signals to handlers."""
        # Media player signals
        self.media_player.positionChanged.connect(self.update_position)
        self.media_player.durationChanged.connect(self.update_duration)
        self.media_player.mediaStatusChanged.connect(self.handle_media_status)
        self.media_player.playbackStateChanged.connect(self.handle_playback_state)
        
        # Control buttons
        self.play_button.clicked.connect(self.play_video)
        self.pause_button.clicked.connect(self.pause_video)
        self.stop_button.clicked.connect(self.stop_video)
        self.previous_button.clicked.connect(self.previous_video)
        self.next_button.clicked.connect(self.next_video)
        
        # Playlist controls
        self.load_playlist_button.clicked.connect(self.load_playlist_dialog)
        self.save_playlist_button.clicked.connect(self.save_playlist_dialog)
        self.add_videos_button.clicked.connect(self.add_videos_dialog)
        self.clear_playlist_button.clicked.connect(self.clear_playlist)
        
        # Playlist selection
        self.playlist_widget.itemDoubleClicked.connect(self.play_selected_video)
        
        # Position slider
        self.position_slider.sliderMoved.connect(self.set_position)
        
        # Auto-advance timer
        self.auto_advance_timer.timeout.connect(self.auto_advance_video)
        
        # Position update timer
        self.position_timer.timeout.connect(self.update_ui)
        self.position_timer.start(100)  # Update every 100ms
    
    def load_default_playlist(self):
        """Load the default playlist if configured."""
        try:
            video_folder = Path(self.video_config.video_folder)
            playlist_file = video_folder / self.video_config.playlist_file
            
            if playlist_file.exists():
                self.load_playlist(str(playlist_file))
            else:
                # Create default playlist from video folder
                self.scan_video_folder(str(video_folder))
                
        except Exception as e:
            logging.warning(f"Failed to load default playlist: {e}")
    
    def load_playlist(self, playlist_path: str):
        """Load playlist from JSON file."""
        try:
            with open(playlist_path, 'r') as f:
                data = json.load(f)
            
            self.playlist = data.get('videos', [])
            self.update_playlist_display()
            
            self.log_event(f"Playlist loaded: {len(self.playlist)} videos")
            self.playlist_loaded.emit(len(self.playlist))
            
        except Exception as e:
            logging.error(f"Failed to load playlist: {e}")
            QMessageBox.warning(self, "Error", f"Failed to load playlist: {e}")
    
    def save_playlist(self, playlist_path: str):
        """Save current playlist to JSON file."""
        try:
            data = {
                'videos': self.playlist,
                'created': time.time(),
                'config': {
                    'auto_advance': self.auto_advance_checkbox.isChecked(),
                    'loop_playlist': self.loop_checkbox.isChecked(),
                    'default_duration': self.duration_spinbox.value()
                }
            }
            
            with open(playlist_path, 'w') as f:
                json.dump(data, f, indent=2)
            
            self.log_event(f"Playlist saved: {playlist_path}")
            
        except Exception as e:
            logging.error(f"Failed to save playlist: {e}")
            QMessageBox.warning(self, "Error", f"Failed to save playlist: {e}")
    
    def scan_video_folder(self, folder_path: str):
        """Scan folder for video files and create playlist."""
        try:
            folder = Path(folder_path)
            if not folder.exists():
                return
            
            video_extensions = ['.mp4', '.avi', '.mov', '.mkv', '.wmv', '.flv']
            video_files = []
            
            for ext in video_extensions:
                video_files.extend(folder.glob(f'*{ext}'))
                video_files.extend(folder.glob(f'*{ext.upper()}'))
            
            self.playlist = []
            for video_file in sorted(video_files):
                self.playlist.append({
                    'path': str(video_file),
                    'name': video_file.stem,
                    'duration': self.video_config.default_video_duration
                })
            
            self.update_playlist_display()
            self.log_event(f"Scanned folder: {len(self.playlist)} videos found")
            
        except Exception as e:
            logging.error(f"Failed to scan video folder: {e}")
    
    def update_playlist_display(self):
        """Update the playlist widget display."""
        self.playlist_widget.clear()
        
        for i, video in enumerate(self.playlist):
            item_text = f"{i+1}. {video['name']} ({video.get('duration', 30)}s)"
            item = QListWidgetItem(item_text)
            item.setData(Qt.ItemDataRole.UserRole, i)
            self.playlist_widget.addItem(item)
        
        # Highlight current video
        if 0 <= self.current_video_index < len(self.playlist):
            self.playlist_widget.setCurrentRow(self.current_video_index)
    
    def play_video(self):
        """Start playing current video."""
        if not self.playlist:
            QMessageBox.information(self, "Info", "No videos in playlist")
            return
        
        if 0 <= self.current_video_index < len(self.playlist):
            video = self.playlist[self.current_video_index]
            video_path = video['path']
            
            if Path(video_path).exists():
                self.media_player.setSource(QUrl.fromLocalFile(video_path))
                self.media_player.play()
                
                # Start auto-advance timer if enabled
                if self.auto_advance_checkbox.isChecked():
                    duration = video.get('duration', self.duration_spinbox.value())
                    self.auto_advance_timer.start(duration * 1000)  # Convert to milliseconds
                
                # Log event
                timestamp = time.time() - self.session_start_time
                self.log_event(f"Video started: {video['name']}")
                self.video_started.emit(video_path, timestamp)
                
                # Update UI
                self.video_info_label.setText(f"Playing: {video['name']}")
                self.update_playlist_display()
                
            else:
                QMessageBox.warning(self, "Error", f"Video file not found: {video_path}")
    
    def pause_video(self):
        """Pause video playback."""
        self.media_player.pause()
        self.auto_advance_timer.stop()
    
    def stop_video(self):
        """Stop video playback."""
        self.media_player.stop()
        self.auto_advance_timer.stop()
        
        if self.playlist and 0 <= self.current_video_index < len(self.playlist):
            video = self.playlist[self.current_video_index]
            timestamp = time.time() - self.session_start_time
            self.log_event(f"Video stopped: {video['name']}")
            self.video_finished.emit(video['path'], timestamp)
    
    def previous_video(self):
        """Play previous video in playlist."""
        if not self.playlist:
            return
        
        old_index = self.current_video_index
        self.current_video_index = (self.current_video_index - 1) % len(self.playlist)
        
        if old_index != self.current_video_index:
            self.switch_video(old_index, self.current_video_index)
    
    def next_video(self):
        """Play next video in playlist."""
        if not self.playlist:
            return
        
        old_index = self.current_video_index
        
        if self.current_video_index < len(self.playlist) - 1:
            self.current_video_index += 1
        elif self.loop_checkbox.isChecked():
            self.current_video_index = 0
        else:
            # End of playlist
            self.stop_video()
            return
        
        if old_index != self.current_video_index:
            self.switch_video(old_index, self.current_video_index)
    
    def switch_video(self, from_index: int, to_index: int):
        """Switch from one video to another."""
        timestamp = time.time() - self.session_start_time
        
        from_video = self.playlist[from_index]['path'] if 0 <= from_index < len(self.playlist) else ""
        to_video = self.playlist[to_index]['path'] if 0 <= to_index < len(self.playlist) else ""
        
        self.log_event(f"Video switched: {self.playlist[from_index]['name']} -> {self.playlist[to_index]['name']}")
        self.video_switched.emit(from_video, to_video, timestamp)
        
        self.play_video()
    
    def auto_advance_video(self):
        """Automatically advance to next video."""
        self.auto_advance_timer.stop()
        self.next_video()
    
    def play_selected_video(self, item: QListWidgetItem):
        """Play video selected from playlist."""
        index = item.data(Qt.ItemDataRole.UserRole)
        if index is not None:
            old_index = self.current_video_index
            self.current_video_index = index
            
            if old_index != self.current_video_index:
                self.switch_video(old_index, self.current_video_index)
            else:
                self.play_video()
    
    def set_position(self, position: int):
        """Set video position from slider."""
        self.media_player.setPosition(position)
    
    def update_position(self, position: int):
        """Update position slider from media player."""
        self.position_slider.setValue(position)
        
        # Update progress bar
        duration = self.media_player.duration()
        if duration > 0:
            progress = int((position / duration) * 100)
            self.progress_bar.setValue(progress)
    
    def update_duration(self, duration: int):
        """Update slider range when duration changes."""
        self.position_slider.setRange(0, duration)
    
    def update_ui(self):
        """Update UI elements periodically."""
        # Update time display if needed
        pass
    
    def handle_media_status(self, status):
        """Handle media player status changes."""
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            # Video finished naturally
            if self.playlist and 0 <= self.current_video_index < len(self.playlist):
                video = self.playlist[self.current_video_index]
                timestamp = time.time() - self.session_start_time
                self.log_event(f"Video finished: {video['name']}")
                self.video_finished.emit(video['path'], timestamp)
            
            # Auto-advance if enabled
            if self.auto_advance_checkbox.isChecked():
                self.next_video()
    
    def handle_playback_state(self, state):
        """Handle playback state changes."""
        if state == QMediaPlayer.PlaybackState.PlayingState:
            self.play_button.setText("Playing...")
        elif state == QMediaPlayer.PlaybackState.PausedState:
            self.play_button.setText("Resume")
        else:
            self.play_button.setText("Play")
    
    def load_playlist_dialog(self):
        """Show dialog to load playlist file."""
        file_path, _ = QFileDialog.getOpenFileName(
            self, "Load Playlist", "", "JSON Files (*.json);;All Files (*)"
        )
        
        if file_path:
            self.load_playlist(file_path)
    
    def save_playlist_dialog(self):
        """Show dialog to save playlist file."""
        file_path, _ = QFileDialog.getSaveFileName(
            self, "Save Playlist", "playlist.json", "JSON Files (*.json);;All Files (*)"
        )
        
        if file_path:
            self.save_playlist(file_path)
    
    def add_videos_dialog(self):
        """Show dialog to add video files to playlist."""
        file_paths, _ = QFileDialog.getOpenFileNames(
            self, "Add Videos", "", 
            "Video Files (*.mp4 *.avi *.mov *.mkv *.wmv *.flv);;All Files (*)"
        )
        
        for file_path in file_paths:
            video_path = Path(file_path)
            self.playlist.append({
                'path': str(video_path),
                'name': video_path.stem,
                'duration': self.duration_spinbox.value()
            })
        
        if file_paths:
            self.update_playlist_display()
            self.log_event(f"Added {len(file_paths)} videos to playlist")
    
    def clear_playlist(self):
        """Clear the current playlist."""
        self.playlist.clear()
        self.current_video_index = 0
        self.update_playlist_display()
        self.stop_video()
        self.log_event("Playlist cleared")
    
    def start_session(self):
        """Start a new video session."""
        self.session_start_time = time.time()
        self.timestamp_log.clear()
        self.log_event("Video session started")
    
    def stop_session(self):
        """Stop the current video session."""
        self.stop_video()
        timestamp = time.time() - self.session_start_time
        self.log_event(f"Video session stopped (duration: {timestamp:.1f}s)")
    
    def log_event(self, message: str):
        """Log an event with timestamp."""
        timestamp = time.time() - self.session_start_time if self.session_start_time > 0 else 0
        log_entry = f"[{timestamp:8.3f}s] {message}"
        self.timestamp_log.append(log_entry)
        logging.info(f"VideoSeries: {log_entry}")
    
    def get_session_log(self) -> str:
        """Get the complete session log."""
        return self.timestamp_log.toPlainText()