#!/usr/bin/env python3
"""
Multi-Modal Capture System - PC Controller Application
Main entry point for the cross-platform desktop interface.
"""

import sys
import asyncio
import logging
from pathlib import Path
from PyQt6.QtWidgets import QApplication, QMainWindow
from PyQt6.QtCore import QTimer
from PyQt6.QtGui import QIcon

from ui.main_window import MainWindow
from core.device_manager import DeviceManager
from core.recording_controller import RecordingController
from utils.config import Config
from utils.logger import setup_logging

class PCControllerApp:
    """Main application class for the PC Controller."""
    
    def __init__(self):
        self.app = None
        self.main_window = None
        self.device_manager = None
        self.recording_controller = None
        self.config = None
        
    def initialize(self):
        """Initialize the application components."""
        try:
            # Setup logging
            setup_logging()
            logging.info("Starting PC Controller Application")
            
            # Load configuration
            self.config = Config()
            
            # Create Qt application
            self.app = QApplication(sys.argv)
            self.app.setApplicationName("MultiModal Capture Controller")
            self.app.setApplicationVersion("1.0.0")
            self.app.setOrganizationName("MultiModal Research")
            
            # Set application icon
            icon_path = Path(__file__).parent / "resources" / "icons" / "app_icon.png"
            if icon_path.exists():
                self.app.setWindowIcon(QIcon(str(icon_path)))
            
            # Initialize core components
            self.device_manager = DeviceManager(self.config)
            self.recording_controller = RecordingController(self.config)
            
            # Create main window
            self.main_window = MainWindow(
                device_manager=self.device_manager,
                recording_controller=self.recording_controller,
                config=self.config
            )
            
            # Setup async event loop integration
            self.setup_async_integration()
            
            logging.info("PC Controller Application initialized successfully")
            return True
            
        except Exception as e:
            logging.error(f"Failed to initialize application: {e}")
            return False
    
    def setup_async_integration(self):
        """Setup integration between Qt event loop and asyncio."""
        # Create asyncio event loop but don't try to integrate it with Qt
        # The async components will run in their own threads
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        logging.info("Async event loop created")
    
    def run(self):
        """Run the application."""
        if not self.initialize():
            return 1
        
        try:
            # Show main window
            self.main_window.show()
            
            # Start device discovery
            self.device_manager.start_discovery()
            
            # Run Qt event loop
            return self.app.exec()
            
        except KeyboardInterrupt:
            logging.info("Application interrupted by user")
            return 0
        except Exception as e:
            logging.error(f"Application error: {e}")
            return 1
        finally:
            self.cleanup()
    
    def cleanup(self):
        """Cleanup application resources."""
        try:
            logging.info("Cleaning up application resources")
            
            if self.device_manager:
                self.device_manager.cleanup()
            
            if self.recording_controller:
                self.recording_controller.cleanup()
            
            if hasattr(self, 'loop') and self.loop:
                self.loop.close()
                
        except Exception as e:
            logging.error(f"Error during cleanup: {e}")

def main():
    """Main entry point."""
    app = PCControllerApp()
    return app.run()

if __name__ == "__main__":
    sys.exit(main())