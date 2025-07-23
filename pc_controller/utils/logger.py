"""
Logging utilities for the PC Controller Application.
Provides centralized logging configuration and management.
"""

import logging
import sys
from pathlib import Path
from datetime import datetime
from typing import Optional

def setup_logging(
    log_level: str = "INFO",
    log_file: Optional[str] = None,
    console_output: bool = True,
    file_output: bool = True
):
    """
    Setup logging configuration for the PC Controller application.
    
    Args:
        log_level: Logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        log_file: Path to log file (auto-generated if None)
        console_output: Enable console logging
        file_output: Enable file logging
    """
    
    # Convert string level to logging constant
    numeric_level = getattr(logging, log_level.upper(), logging.INFO)
    
    # Create logs directory
    log_dir = Path.home() / ".multimodal_capture" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    
    # Generate log file name if not provided
    if log_file is None:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        log_file = log_dir / f"pc_controller_{timestamp}.log"
    else:
        log_file = Path(log_file)
    
    # Create formatters
    detailed_formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(filename)s:%(lineno)d - %(message)s'
    )
    
    simple_formatter = logging.Formatter(
        '%(asctime)s - %(levelname)s - %(message)s'
    )
    
    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(numeric_level)
    
    # Clear existing handlers
    root_logger.handlers.clear()
    
    # Add console handler
    if console_output:
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(numeric_level)
        console_handler.setFormatter(simple_formatter)
        root_logger.addHandler(console_handler)
    
    # Add file handler
    if file_output:
        try:
            file_handler = logging.FileHandler(log_file, mode='a', encoding='utf-8')
            file_handler.setLevel(numeric_level)
            file_handler.setFormatter(detailed_formatter)
            root_logger.addHandler(file_handler)
            
            logging.info(f"Logging initialized - File: {log_file}")
            
        except Exception as e:
            print(f"Failed to setup file logging: {e}")
    
    # Set specific logger levels
    logging.getLogger('PyQt6').setLevel(logging.WARNING)
    logging.getLogger('urllib3').setLevel(logging.WARNING)
    
    logging.info(f"Logging setup complete - Level: {log_level}")

def get_logger(name: str) -> logging.Logger:
    """
    Get a logger instance with the specified name.
    
    Args:
        name: Logger name (typically __name__)
        
    Returns:
        Logger instance
    """
    return logging.getLogger(name)

class LogCapture:
    """Context manager for capturing log messages during testing."""
    
    def __init__(self, logger_name: str = None, level: int = logging.INFO):
        self.logger_name = logger_name
        self.level = level
        self.handler = None
        self.records = []
    
    def __enter__(self):
        # Create memory handler
        self.handler = logging.Handler()
        self.handler.setLevel(self.level)
        self.handler.emit = self._capture_record
        
        # Add to logger
        logger = logging.getLogger(self.logger_name)
        logger.addHandler(self.handler)
        
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.handler:
            logger = logging.getLogger(self.logger_name)
            logger.removeHandler(self.handler)
    
    def _capture_record(self, record):
        """Capture log record."""
        self.records.append(record)
    
    def get_messages(self) -> list:
        """Get captured log messages."""
        return [record.getMessage() for record in self.records]
    
    def has_message(self, message: str) -> bool:
        """Check if a specific message was logged."""
        return any(message in record.getMessage() for record in self.records)

def configure_qt_logging():
    """Configure Qt-specific logging settings."""
    import os
    
    # Suppress Qt debug messages in production
    os.environ['QT_LOGGING_RULES'] = '*.debug=false'
    
    # Configure Qt message handling
    try:
        from PyQt6.QtCore import qInstallMessageHandler, QtMsgType
        
        def qt_message_handler(mode, context, message):
            """Custom Qt message handler."""
            if mode == QtMsgType.QtDebugMsg:
                logging.debug(f"Qt Debug: {message}")
            elif mode == QtMsgType.QtWarningMsg:
                logging.warning(f"Qt Warning: {message}")
            elif mode == QtMsgType.QtCriticalMsg:
                logging.error(f"Qt Critical: {message}")
            elif mode == QtMsgType.QtFatalMsg:
                logging.critical(f"Qt Fatal: {message}")
        
        qInstallMessageHandler(qt_message_handler)
        
    except ImportError:
        # PyQt6 not available, skip Qt logging configuration
        pass

def log_system_info():
    """Log system information for debugging."""
    import platform
    import sys
    
    logging.info("=== System Information ===")
    logging.info(f"Platform: {platform.platform()}")
    logging.info(f"Python Version: {sys.version}")
    logging.info(f"Architecture: {platform.architecture()}")
    logging.info(f"Processor: {platform.processor()}")
    logging.info(f"Machine: {platform.machine()}")
    
    try:
        from PyQt6.QtCore import QT_VERSION_STR, PYQT_VERSION_STR
        logging.info(f"Qt Version: {QT_VERSION_STR}")
        logging.info(f"PyQt Version: {PYQT_VERSION_STR}")
    except ImportError:
        logging.info("PyQt6 not available")
    
    logging.info("=== End System Information ===")

def setup_exception_logging():
    """Setup global exception logging."""
    
    def handle_exception(exc_type, exc_value, exc_traceback):
        """Handle uncaught exceptions."""
        if issubclass(exc_type, KeyboardInterrupt):
            # Allow KeyboardInterrupt to be handled normally
            sys.__excepthook__(exc_type, exc_value, exc_traceback)
            return
        
        logging.critical(
            "Uncaught exception",
            exc_info=(exc_type, exc_value, exc_traceback)
        )
    
    sys.excepthook = handle_exception

# Convenience functions for common logging patterns
def log_function_entry(func_name: str, **kwargs):
    """Log function entry with parameters."""
    params = ", ".join(f"{k}={v}" for k, v in kwargs.items())
    logging.debug(f"Entering {func_name}({params})")

def log_function_exit(func_name: str, result=None):
    """Log function exit with result."""
    if result is not None:
        logging.debug(f"Exiting {func_name} -> {result}")
    else:
        logging.debug(f"Exiting {func_name}")

def log_performance(operation: str, duration: float):
    """Log performance metrics."""
    logging.info(f"Performance: {operation} took {duration:.3f}s")

def log_memory_usage():
    """Log current memory usage."""
    try:
        import psutil
        import os
        
        process = psutil.Process(os.getpid())
        memory_info = process.memory_info()
        
        logging.info(f"Memory Usage: RSS={memory_info.rss / 1024 / 1024:.1f}MB, "
                    f"VMS={memory_info.vms / 1024 / 1024:.1f}MB")
        
    except ImportError:
        logging.debug("psutil not available for memory monitoring")
    except Exception as e:
        logging.debug(f"Failed to get memory usage: {e}")

# Initialize logging when module is imported
if __name__ != "__main__":
    # Only setup basic logging when imported as module
    # Full setup will be done by main application
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )