#!/usr/bin/env python3
"""
Real-Time Monitoring Dashboard for Multi-Device Synchronization System
Provides web-based interface for live monitoring of system performance and device health.
"""

import asyncio
import json
import logging
import time
from typing import Dict, List, Any, Optional
from datetime import datetime
from pathlib import Path

# Web framework imports
try:
    from flask import Flask, render_template, jsonify, request, send_from_directory
    from flask_socketio import SocketIO, emit
    import plotly.graph_objs as go
    import plotly.utils
except ImportError:
    Flask = None
    SocketIO = None
    logging.warning("Flask and related packages not available. Dashboard will not function.")

from analytics.performance_analytics import PerformanceAnalytics, MetricType, AlertLevel
from core.time_sync import TimeSynchronizer
from core.device_manager import DeviceManager


class MonitoringDashboard:
    """
    Real-time monitoring dashboard with web interface.
    
    Provides live visualization of:
    - Synchronization quality metrics
    - Device health status
    - Performance alerts
    - Historical trends
    """
    
    def __init__(self, analytics: PerformanceAnalytics = None,
                 time_synchronizer: TimeSynchronizer = None,
                 device_manager: DeviceManager = None,
                 host: str = "localhost", port: int = 5000):
        """Initialize monitoring dashboard."""
        if Flask is None:
            raise ImportError("Flask and related packages required for dashboard")
        
        self.analytics = analytics or PerformanceAnalytics()
        self.time_synchronizer = time_synchronizer
        self.device_manager = device_manager
        
        # Web server configuration
        self.host = host
        self.port = port
        
        # Flask app setup
        self.app = Flask(__name__, 
                        template_folder=str(Path(__file__).parent / "templates"),
                        static_folder=str(Path(__file__).parent / "static"))
        self.app.config['SECRET_KEY'] = 'monitoring_dashboard_secret'
        
        # SocketIO for real-time updates
        self.socketio = SocketIO(self.app, cors_allowed_origins="*")
        
        # Dashboard state
        self.is_running = False
        self.update_task: Optional[asyncio.Task] = None
        self.update_interval = 2.0  # seconds
        
        # Setup routes and handlers
        self._setup_routes()
        self._setup_socketio_handlers()
        
        logging.info("MonitoringDashboard initialized")
    
    def _setup_routes(self):
        """Setup Flask routes."""
        
        @self.app.route('/')
        def index():
            """Main dashboard page."""
            return render_template('dashboard.html')
        
        @self.app.route('/api/status')
        def api_status():
            """Get current system status."""
            try:
                summary = self.analytics.get_current_performance_summary()
                return jsonify({
                    'status': 'ok',
                    'timestamp': time.time(),
                    'summary': summary
                })
            except Exception as e:
                return jsonify({
                    'status': 'error',
                    'error': str(e)
                }), 500
        
        @self.app.route('/api/devices')
        def api_devices():
            """Get device information."""
            try:
                devices = []
                if self.device_manager:
                    for device_id, device in self.device_manager.devices.items():
                        device_info = {
                            'id': device_id,
                            'name': device.device_name,
                            'status': device.status.value,
                            'last_seen': getattr(device, 'last_seen', time.time())
                        }
                        
                        # Add sync status if available
                        if self.time_synchronizer:
                            sync_status = self.time_synchronizer.get_device_status(device_id)
                            if sync_status:
                                device_info.update({
                                    'sync_state': sync_status.state.value,
                                    'sync_offset_ms': (sync_status.offset * 1000) if sync_status.offset else None,
                                    'sync_uncertainty_ms': (sync_status.uncertainty * 1000) if sync_status.uncertainty else None,
                                    'sync_quality': self.time_synchronizer.get_sync_quality(device_id)
                                })
                        
                        devices.append(device_info)
                
                return jsonify({
                    'devices': devices,
                    'count': len(devices)
                })
            except Exception as e:
                return jsonify({
                    'error': str(e)
                }), 500
        
        @self.app.route('/api/alerts')
        def api_alerts():
            """Get current alerts."""
            try:
                active_alerts = [alert.to_dict() for alert in self.analytics.active_alerts.values()]
                recent_alerts = [alert.to_dict() for alert in self.analytics.alert_history[-10:]]
                
                return jsonify({
                    'active_alerts': active_alerts,
                    'recent_alerts': recent_alerts,
                    'active_count': len(active_alerts)
                })
            except Exception as e:
                return jsonify({
                    'error': str(e)
                }), 500
        
        @self.app.route('/api/metrics/<metric_type>')
        def api_metrics(metric_type):
            """Get historical metrics data."""
            try:
                # Get time window from query parameters
                window_minutes = int(request.args.get('window', 30))
                window_seconds = window_minutes * 60
                
                # Get metrics
                try:
                    metric_enum = MetricType(metric_type)
                    metrics = self.analytics._get_recent_metrics_by_type(metric_enum, window_seconds)
                except ValueError:
                    return jsonify({'error': f'Invalid metric type: {metric_type}'}), 400
                
                # Format for plotting
                data = {
                    'timestamps': [m.timestamp for m in metrics],
                    'values': [m.value for m in metrics],
                    'device_ids': [m.device_id for m in metrics]
                }
                
                return jsonify(data)
            except Exception as e:
                return jsonify({
                    'error': str(e)
                }), 500
        
        @self.app.route('/api/report')
        def api_report():
            """Generate analytics report."""
            try:
                # Get time range from query parameters
                hours = int(request.args.get('hours', 1))
                end_time = time.time()
                start_time = end_time - (hours * 3600)
                
                report = self.analytics.generate_analytics_report(start_time, end_time)
                
                return jsonify({
                    'report_id': report.report_id,
                    'start_time': report.start_time,
                    'end_time': report.end_time,
                    'device_count': report.device_count,
                    'total_metrics': report.total_metrics,
                    'summary_stats': report.summary_stats,
                    'device_performance': report.device_performance,
                    'recommendations': report.recommendations
                })
            except Exception as e:
                return jsonify({
                    'error': str(e)
                }), 500
    
    def _setup_socketio_handlers(self):
        """Setup SocketIO event handlers."""
        
        @self.socketio.on('connect')
        def handle_connect():
            """Handle client connection."""
            logging.info(f"Dashboard client connected: {request.sid}")
            emit('status', {'message': 'Connected to monitoring dashboard'})
        
        @self.socketio.on('disconnect')
        def handle_disconnect():
            """Handle client disconnection."""
            logging.info(f"Dashboard client disconnected: {request.sid}")
        
        @self.socketio.on('request_update')
        def handle_request_update():
            """Handle client request for immediate update."""
            try:
                self._send_real_time_update()
            except Exception as e:
                emit('error', {'message': str(e)})
    
    async def start_dashboard(self):
        """Start the monitoring dashboard."""
        if self.is_running:
            logging.warning("Dashboard already running")
            return
        
        self.is_running = True
        
        # Start analytics if not already running
        if not self.analytics.is_running:
            await self.analytics.start_analytics()
        
        # Start real-time update task
        self.update_task = asyncio.create_task(self._real_time_update_loop())
        
        # Start Flask app in a separate thread
        import threading
        self.server_thread = threading.Thread(
            target=lambda: self.socketio.run(self.app, host=self.host, port=self.port, debug=False)
        )
        self.server_thread.daemon = True
        self.server_thread.start()
        
        logging.info(f"Monitoring dashboard started at http://{self.host}:{self.port}")
    
    async def stop_dashboard(self):
        """Stop the monitoring dashboard."""
        if not self.is_running:
            return
        
        self.is_running = False
        
        # Cancel update task
        if self.update_task:
            self.update_task.cancel()
            try:
                await self.update_task
            except asyncio.CancelledError:
                pass
        
        logging.info("Monitoring dashboard stopped")
    
    async def _real_time_update_loop(self):
        """Real-time update loop for dashboard."""
        while self.is_running:
            try:
                self._send_real_time_update()
                await asyncio.sleep(self.update_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in real-time update loop: {e}")
                await asyncio.sleep(1.0)
    
    def _send_real_time_update(self):
        """Send real-time update to connected clients."""
        try:
            # Get current performance summary
            summary = self.analytics.get_current_performance_summary()
            
            # Get device information
            devices = []
            if self.device_manager:
                for device_id, device in self.device_manager.devices.items():
                    device_info = {
                        'id': device_id,
                        'name': device.device_name,
                        'status': device.status.value
                    }
                    
                    # Add sync information
                    if self.time_synchronizer:
                        sync_status = self.time_synchronizer.get_device_status(device_id)
                        if sync_status:
                            device_info.update({
                                'sync_state': sync_status.state.value,
                                'sync_quality': self.time_synchronizer.get_sync_quality(device_id) or 0.0,
                                'sync_offset_ms': (abs(sync_status.offset) * 1000) if sync_status.offset else 0.0
                            })
                    
                    devices.append(device_info)
            
            # Get active alerts
            active_alerts = [alert.to_dict() for alert in self.analytics.active_alerts.values()]
            
            # Prepare update data
            update_data = {
                'timestamp': time.time(),
                'summary': summary,
                'devices': devices,
                'alerts': active_alerts,
                'alert_count': len(active_alerts)
            }
            
            # Send to all connected clients
            self.socketio.emit('real_time_update', update_data)
            
        except Exception as e:
            logging.error(f"Error sending real-time update: {e}")
    
    def create_dashboard_templates(self):
        """Create HTML templates for the dashboard."""
        templates_dir = Path(__file__).parent / "templates"
        templates_dir.mkdir(exist_ok=True)
        
        # Main dashboard template
        dashboard_html = '''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Multi-Device Sync Monitoring Dashboard</title>
    <script src="https://cdn.socket.io/4.0.0/socket.io.min.js"></script>
    <script src="https://cdn.plot.ly/plotly-latest.min.js"></script>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 0;
            padding: 20px;
            background-color: #f5f5f5;
        }
        .header {
            background-color: #2c3e50;
            color: white;
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 20px;
        }
        .dashboard-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin-bottom: 20px;
        }
        .card {
            background: white;
            border-radius: 8px;
            padding: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .metric-value {
            font-size: 2em;
            font-weight: bold;
            color: #2c3e50;
        }
        .device-list {
            list-style: none;
            padding: 0;
        }
        .device-item {
            padding: 10px;
            margin: 5px 0;
            border-radius: 4px;
            border-left: 4px solid #3498db;
        }
        .device-connected {
            background-color: #d5f4e6;
            border-left-color: #27ae60;
        }
        .device-disconnected {
            background-color: #fadbd8;
            border-left-color: #e74c3c;
        }
        .alert {
            padding: 10px;
            margin: 5px 0;
            border-radius: 4px;
        }
        .alert-critical {
            background-color: #fadbd8;
            border-left: 4px solid #e74c3c;
        }
        .alert-warning {
            background-color: #fef9e7;
            border-left: 4px solid #f39c12;
        }
        .status-indicator {
            display: inline-block;
            width: 12px;
            height: 12px;
            border-radius: 50%;
            margin-right: 8px;
        }
        .status-connected { background-color: #27ae60; }
        .status-disconnected { background-color: #e74c3c; }
        .chart-container {
            height: 400px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>Multi-Device Synchronization Monitoring</h1>
        <p>Real-time monitoring of device synchronization and system performance</p>
        <div>Status: <span id="connection-status">Connecting...</span></div>
    </div>

    <div class="dashboard-grid">
        <div class="card">
            <h3>System Overview</h3>
            <div>Active Devices: <span class="metric-value" id="device-count">0</span></div>
            <div>Active Alerts: <span class="metric-value" id="alert-count">0</span></div>
            <div>Last Update: <span id="last-update">Never</span></div>
        </div>
        
        <div class="card">
            <h3>Sync Quality</h3>
            <div>Average Quality: <span class="metric-value" id="avg-sync-quality">0.0</span></div>
            <div>Max Offset: <span id="max-sync-offset">0.0ms</span></div>
        </div>
    </div>

    <div class="dashboard-grid">
        <div class="card">
            <h3>Connected Devices</h3>
            <ul class="device-list" id="device-list">
                <li>No devices connected</li>
            </ul>
        </div>
        
        <div class="card">
            <h3>Active Alerts</h3>
            <div id="alerts-list">
                <p>No active alerts</p>
            </div>
        </div>
    </div>

    <div class="card">
        <h3>Sync Quality Trend</h3>
        <div id="sync-quality-chart" class="chart-container"></div>
    </div>

    <script>
        // Initialize Socket.IO connection
        const socket = io();
        
        // Connection status
        socket.on('connect', function() {
            document.getElementById('connection-status').textContent = 'Connected';
            document.getElementById('connection-status').style.color = '#27ae60';
        });
        
        socket.on('disconnect', function() {
            document.getElementById('connection-status').textContent = 'Disconnected';
            document.getElementById('connection-status').style.color = '#e74c3c';
        });
        
        // Real-time updates
        socket.on('real_time_update', function(data) {
            updateDashboard(data);
        });
        
        function updateDashboard(data) {
            // Update timestamp
            document.getElementById('last-update').textContent = new Date(data.timestamp * 1000).toLocaleTimeString();
            
            // Update device count
            document.getElementById('device-count').textContent = data.devices.length;
            
            // Update alert count
            document.getElementById('alert-count').textContent = data.alert_count;
            
            // Update sync quality
            if (data.summary.metrics && data.summary.metrics.sync_quality) {
                document.getElementById('avg-sync-quality').textContent = data.summary.metrics.sync_quality.average.toFixed(2);
            }
            
            if (data.summary.metrics && data.summary.metrics.sync_offset) {
                document.getElementById('max-sync-offset').textContent = data.summary.metrics.sync_offset.max.toFixed(1) + 'ms';
            }
            
            // Update device list
            updateDeviceList(data.devices);
            
            // Update alerts
            updateAlertsList(data.alerts);
            
            // Update charts
            updateSyncQualityChart();
        }
        
        function updateDeviceList(devices) {
            const deviceList = document.getElementById('device-list');
            deviceList.innerHTML = '';
            
            if (devices.length === 0) {
                deviceList.innerHTML = '<li>No devices connected</li>';
                return;
            }
            
            devices.forEach(device => {
                const li = document.createElement('li');
                li.className = 'device-item ' + (device.status === 'CONNECTED' ? 'device-connected' : 'device-disconnected');
                
                const statusClass = device.status === 'CONNECTED' ? 'status-connected' : 'status-disconnected';
                const syncQuality = device.sync_quality ? (device.sync_quality * 100).toFixed(0) + '%' : 'N/A';
                const syncOffset = device.sync_offset_ms ? device.sync_offset_ms.toFixed(1) + 'ms' : 'N/A';
                
                li.innerHTML = `
                    <span class="status-indicator ${statusClass}"></span>
                    <strong>${device.name || device.id}</strong><br>
                    <small>Quality: ${syncQuality} | Offset: ${syncOffset}</small>
                `;
                
                deviceList.appendChild(li);
            });
        }
        
        function updateAlertsList(alerts) {
            const alertsList = document.getElementById('alerts-list');
            alertsList.innerHTML = '';
            
            if (alerts.length === 0) {
                alertsList.innerHTML = '<p>No active alerts</p>';
                return;
            }
            
            alerts.forEach(alert => {
                const div = document.createElement('div');
                div.className = 'alert alert-' + alert.level;
                div.innerHTML = `
                    <strong>${alert.level.toUpperCase()}</strong><br>
                    ${alert.message}<br>
                    <small>${new Date(alert.timestamp * 1000).toLocaleString()}</small>
                `;
                alertsList.appendChild(div);
            });
        }
        
        function updateSyncQualityChart() {
            // Fetch recent sync quality data
            fetch('/api/metrics/sync_quality?window=30')
                .then(response => response.json())
                .then(data => {
                    const trace = {
                        x: data.timestamps.map(t => new Date(t * 1000)),
                        y: data.values,
                        type: 'scatter',
                        mode: 'lines+markers',
                        name: 'Sync Quality',
                        line: { color: '#3498db' }
                    };
                    
                    const layout = {
                        title: 'Sync Quality Over Time',
                        xaxis: { title: 'Time' },
                        yaxis: { title: 'Quality (0-1)', range: [0, 1] },
                        margin: { t: 40, r: 40, b: 40, l: 60 }
                    };
                    
                    Plotly.newPlot('sync-quality-chart', [trace], layout);
                })
                .catch(error => console.error('Error updating chart:', error));
        }
        
        // Initial chart load
        setTimeout(updateSyncQualityChart, 1000);
        
        // Refresh chart every 30 seconds
        setInterval(updateSyncQualityChart, 30000);
    </script>
</body>
</html>'''
        
        with open(templates_dir / "dashboard.html", "w") as f:
            f.write(dashboard_html)
        
        logging.info(f"Dashboard templates created in {templates_dir}")
    
    async def cleanup(self):
        """Cleanup dashboard resources."""
        await self.stop_dashboard()
        if self.analytics:
            await self.analytics.cleanup()
        logging.info("MonitoringDashboard cleaned up")


async def main():
    """Example usage of monitoring dashboard."""
    from utils.logger import setup_logging
    
    setup_logging(level=logging.INFO)
    
    try:
        # Create components
        device_manager = DeviceManager()
        time_synchronizer = TimeSynchronizer()
        analytics = PerformanceAnalytics(time_synchronizer, device_manager)
        
        # Create dashboard
        dashboard = MonitoringDashboard(
            analytics=analytics,
            time_synchronizer=time_synchronizer,
            device_manager=device_manager,
            host="0.0.0.0",  # Allow external connections
            port=5000
        )
        
        # Create templates
        dashboard.create_dashboard_templates()
        
        # Start dashboard
        await dashboard.start_dashboard()
        
        print("Dashboard started at http://localhost:5000")
        print("Press Ctrl+C to stop...")
        
        # Keep running
        try:
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            print("\nShutting down dashboard...")
        
    except Exception as e:
        logging.error(f"Dashboard example failed: {e}")
    
    finally:
        if 'dashboard' in locals():
            await dashboard.cleanup()


if __name__ == "__main__":
    asyncio.run(main())