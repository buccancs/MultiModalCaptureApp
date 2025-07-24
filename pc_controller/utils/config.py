"""
Configuration management for the PC Controller Application.
Handles application settings, device parameters, and user preferences.
"""

import json
import logging
from pathlib import Path
from typing import Dict, Any, Optional
from dataclasses import dataclass, asdict
from PyQt6.QtCore import QSettings

@dataclass
class NetworkConfig:
    """Network configuration settings."""
    discovery_port: int = 8888
    server_port: int = 8888
    connection_timeout: int = 5000
    max_devices: int = 10
    enable_discovery: bool = True

@dataclass
class RecordingConfig:
    """Recording configuration settings."""
    default_session_prefix: str = "Session"
    auto_start_recording: bool = False
    sync_tolerance_ms: int = 50
    max_recording_duration: int = 3600  # seconds
    output_directory: str = "recordings"

@dataclass
class UIConfig:
    """User interface configuration settings."""
    window_width: int = 1200
    window_height: int = 800
    theme: str = "light"
    show_debug_info: bool = False
    auto_save_layout: bool = True

@dataclass
class DeviceConfig:
    """Device-specific configuration settings."""
    gsr_sample_rate: int = 128
    thermal_frame_rate: int = 30
    video_quality: str = "HD"
    audio_sample_rate: int = 44100
    enable_simulation: bool = False

@dataclass
class VideoSeriesConfig:
    """Video series playback configuration settings."""
    video_folder: str = "videos"
    playlist_file: str = "playlist.json"
    auto_advance: bool = True
    loop_playlist: bool = False
    default_video_duration: int = 30  # seconds
    transition_delay: float = 0.5  # seconds between videos
    enable_controls: bool = True
    fullscreen_mode: bool = False

class Config:
    """Main configuration manager for the PC Controller."""
    
    def __init__(self, config_file: Optional[str] = None):
        self.config_file = config_file or self._get_default_config_path()
        self.settings = QSettings("MultiModalCapture", "PCController")
        
        # Initialize configuration sections
        self.network = NetworkConfig()
        self.recording = RecordingConfig()
        self.ui = UIConfig()
        self.device = DeviceConfig()
        self.video_series = VideoSeriesConfig()
        
        # Load configuration
        self.load()
        
        logging.info(f"Configuration loaded from: {self.config_file}")
    
    def _get_default_config_path(self) -> str:
        """Get the default configuration file path."""
        config_dir = Path.home() / ".multimodal_capture"
        config_dir.mkdir(exist_ok=True)
        return str(config_dir / "pc_controller_config.json")
    
    def load(self):
        """Load configuration from file and Qt settings."""
        try:
            # Load from JSON file if it exists
            config_path = Path(self.config_file)
            if config_path.exists():
                with open(config_path, 'r') as f:
                    data = json.load(f)
                    self._update_from_dict(data)
            
            # Load UI-specific settings from Qt settings
            self._load_qt_settings()
            
        except Exception as e:
            logging.warning(f"Failed to load configuration: {e}")
            logging.info("Using default configuration")
    
    def save(self):
        """Save configuration to file and Qt settings."""
        try:
            # Save to JSON file
            config_data = {
                'network': asdict(self.network),
                'recording': asdict(self.recording),
                'ui': asdict(self.ui),
                'device': asdict(self.device),
                'video_series': asdict(self.video_series)
            }
            
            config_path = Path(self.config_file)
            config_path.parent.mkdir(exist_ok=True)
            
            with open(config_path, 'w') as f:
                json.dump(config_data, f, indent=2)
            
            # Save UI settings to Qt settings
            self._save_qt_settings()
            
            logging.info("Configuration saved successfully")
            
        except Exception as e:
            logging.error(f"Failed to save configuration: {e}")
    
    def _update_from_dict(self, data: Dict[str, Any]):
        """Update configuration from dictionary."""
        if 'network' in data:
            self.network = NetworkConfig(**data['network'])
        if 'recording' in data:
            self.recording = RecordingConfig(**data['recording'])
        if 'ui' in data:
            self.ui = UIConfig(**data['ui'])
        if 'device' in data:
            self.device = DeviceConfig(**data['device'])
        if 'video_series' in data:
            self.video_series = VideoSeriesConfig(**data['video_series'])
    
    def _load_qt_settings(self):
        """Load UI settings from Qt settings."""
        self.settings.beginGroup("UI")
        self.ui.window_width = self.settings.value("window_width", self.ui.window_width, type=int)
        self.ui.window_height = self.settings.value("window_height", self.ui.window_height, type=int)
        self.ui.theme = self.settings.value("theme", self.ui.theme, type=str)
        self.ui.show_debug_info = self.settings.value("show_debug_info", self.ui.show_debug_info, type=bool)
        self.settings.endGroup()
    
    def _save_qt_settings(self):
        """Save UI settings to Qt settings."""
        self.settings.beginGroup("UI")
        self.settings.setValue("window_width", self.ui.window_width)
        self.settings.setValue("window_height", self.ui.window_height)
        self.settings.setValue("theme", self.ui.theme)
        self.settings.setValue("show_debug_info", self.ui.show_debug_info)
        self.settings.endGroup()
        self.settings.sync()
    
    def get_output_directory(self) -> Path:
        """Get the output directory for recordings."""
        output_dir = Path(self.recording.output_directory)
        if not output_dir.is_absolute():
            output_dir = Path.home() / "MultiModalCapture" / output_dir
        
        output_dir.mkdir(parents=True, exist_ok=True)
        return output_dir
    
    def get_session_id(self) -> str:
        """Generate a new session ID."""
        from datetime import datetime
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        return f"{self.recording.default_session_prefix}_{timestamp}"
    
    def validate(self) -> bool:
        """Validate configuration settings."""
        try:
            # Validate network settings
            if not (1024 <= self.network.discovery_port <= 65535):
                logging.error("Invalid discovery port")
                return False
            
            if not (1024 <= self.network.server_port <= 65535):
                logging.error("Invalid server port")
                return False
            
            # Validate recording settings
            if self.recording.max_recording_duration <= 0:
                logging.error("Invalid max recording duration")
                return False
            
            # Validate device settings
            if self.device.gsr_sample_rate not in [16, 32, 64, 128, 256]:
                logging.error("Invalid GSR sample rate")
                return False
            
            return True
            
        except Exception as e:
            logging.error(f"Configuration validation failed: {e}")
            return False
    
    def reset_to_defaults(self):
        """Reset configuration to default values."""
        self.network = NetworkConfig()
        self.recording = RecordingConfig()
        self.ui = UIConfig()
        self.device = DeviceConfig()
        self.video_series = VideoSeriesConfig()
        
        logging.info("Configuration reset to defaults")
    
    def __str__(self) -> str:
        """String representation of configuration."""
        return f"Config(network={self.network}, recording={self.recording}, ui={self.ui}, device={self.device})"