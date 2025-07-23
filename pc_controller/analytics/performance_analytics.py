#!/usr/bin/env python3
"""
Real-Time Performance Analytics System
Provides comprehensive analytics for multi-device synchronization and system performance.
"""

import time
import logging
import statistics
import asyncio
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from collections import deque, defaultdict
from enum import Enum
import json

from core.time_sync import TimeSynchronizer, DeviceSyncStatus, SyncQuality
from core.device_manager import DeviceManager, DeviceStatus
from data.session_manifest import SessionManifest


class MetricType(Enum):
    """Types of performance metrics."""
    SYNC_QUALITY = "sync_quality"
    SYNC_OFFSET = "sync_offset"
    SYNC_UNCERTAINTY = "sync_uncertainty"
    NETWORK_LATENCY = "network_latency"
    DEVICE_HEALTH = "device_health"
    SESSION_QUALITY = "session_quality"
    THROUGHPUT = "throughput"
    ERROR_RATE = "error_rate"


class AlertLevel(Enum):
    """Alert severity levels."""
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


@dataclass
class PerformanceMetric:
    """A single performance metric measurement."""
    metric_type: MetricType
    device_id: Optional[str]
    value: float
    timestamp: float
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            'metric_type': self.metric_type.value,
            'device_id': self.device_id,
            'value': self.value,
            'timestamp': self.timestamp,
            'metadata': self.metadata
        }


@dataclass
class PerformanceAlert:
    """A performance alert."""
    alert_id: str
    level: AlertLevel
    message: str
    metric_type: MetricType
    device_id: Optional[str]
    value: float
    threshold: float
    timestamp: float
    resolved: bool = False
    resolved_timestamp: Optional[float] = None
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            'alert_id': self.alert_id,
            'level': self.level.value,
            'message': self.message,
            'metric_type': self.metric_type.value,
            'device_id': self.device_id,
            'value': self.value,
            'threshold': self.threshold,
            'timestamp': self.timestamp,
            'resolved': self.resolved,
            'resolved_timestamp': self.resolved_timestamp
        }


@dataclass
class AnalyticsReport:
    """Comprehensive analytics report."""
    report_id: str
    start_time: float
    end_time: float
    device_count: int
    total_metrics: int
    summary_stats: Dict[str, Any] = field(default_factory=dict)
    device_performance: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    alerts: List[PerformanceAlert] = field(default_factory=list)
    recommendations: List[str] = field(default_factory=list)


class PerformanceAnalytics:
    """
    Real-time performance analytics system.
    
    Collects, analyzes, and reports on system performance metrics
    including synchronization quality, device health, and network performance.
    """
    
    def __init__(self, time_synchronizer: TimeSynchronizer = None,
                 device_manager: DeviceManager = None):
        """Initialize performance analytics."""
        self.time_synchronizer = time_synchronizer
        self.device_manager = device_manager
        
        # Metric storage (using deque for efficient sliding window)
        self.metrics_history: Dict[str, deque] = defaultdict(lambda: deque(maxlen=1000))
        self.device_metrics: Dict[str, Dict[str, deque]] = defaultdict(
            lambda: defaultdict(lambda: deque(maxlen=500))
        )
        
        # Alert management
        self.active_alerts: Dict[str, PerformanceAlert] = {}
        self.alert_history: List[PerformanceAlert] = []
        self.alert_thresholds: Dict[MetricType, Dict[str, float]] = {
            MetricType.SYNC_QUALITY: {'warning': 0.7, 'error': 0.5, 'critical': 0.3},
            MetricType.SYNC_OFFSET: {'warning': 25.0, 'error': 50.0, 'critical': 100.0},  # ms
            MetricType.SYNC_UNCERTAINTY: {'warning': 20.0, 'error': 50.0, 'critical': 100.0},  # ms
            MetricType.NETWORK_LATENCY: {'warning': 100.0, 'error': 200.0, 'critical': 500.0},  # ms
            MetricType.ERROR_RATE: {'warning': 0.1, 'error': 0.2, 'critical': 0.5}  # ratio
        }
        
        # Analytics configuration
        self.collection_interval = 5.0  # seconds
        self.analysis_window = 300.0  # 5 minutes
        self.max_alert_history = 1000
        
        # Background tasks
        self.collection_task: Optional[asyncio.Task] = None
        self.analysis_task: Optional[asyncio.Task] = None
        self.is_running = False
        
        logging.info("PerformanceAnalytics initialized")
    
    async def start_analytics(self):
        """Start real-time analytics collection."""
        if self.is_running:
            logging.warning("Analytics already running")
            return
        
        self.is_running = True
        
        # Start background tasks
        self.collection_task = asyncio.create_task(self._metrics_collection_loop())
        self.analysis_task = asyncio.create_task(self._analysis_loop())
        
        logging.info("Performance analytics started")
    
    async def stop_analytics(self):
        """Stop analytics collection."""
        if not self.is_running:
            return
        
        self.is_running = False
        
        # Cancel background tasks
        if self.collection_task:
            self.collection_task.cancel()
            try:
                await self.collection_task
            except asyncio.CancelledError:
                pass
        
        if self.analysis_task:
            self.analysis_task.cancel()
            try:
                await self.analysis_task
            except asyncio.CancelledError:
                pass
        
        logging.info("Performance analytics stopped")
    
    async def _metrics_collection_loop(self):
        """Main metrics collection loop."""
        while self.is_running:
            try:
                await self._collect_metrics()
                await asyncio.sleep(self.collection_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in metrics collection: {e}")
                await asyncio.sleep(1.0)
    
    async def _analysis_loop(self):
        """Main analysis loop."""
        while self.is_running:
            try:
                await self._analyze_metrics()
                await asyncio.sleep(30.0)  # Analyze every 30 seconds
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Error in metrics analysis: {e}")
                await asyncio.sleep(5.0)
    
    async def _collect_metrics(self):
        """Collect current performance metrics."""
        current_time = time.time()
        
        # Collect synchronization metrics
        if self.time_synchronizer:
            await self._collect_sync_metrics(current_time)
        
        # Collect device health metrics
        if self.device_manager:
            await self._collect_device_metrics(current_time)
        
        # Collect system-wide metrics
        await self._collect_system_metrics(current_time)
    
    async def _collect_sync_metrics(self, timestamp: float):
        """Collect synchronization-related metrics."""
        try:
            all_device_status = self.time_synchronizer.get_all_device_status()
            
            for device_id, status in all_device_status.items():
                # Sync quality metric
                quality = self.time_synchronizer.get_sync_quality(device_id)
                if quality is not None:
                    metric = PerformanceMetric(
                        metric_type=MetricType.SYNC_QUALITY,
                        device_id=device_id,
                        value=quality,
                        timestamp=timestamp
                    )
                    self._store_metric(metric)
                
                # Sync offset metric
                if status.offset is not None:
                    offset_ms = abs(status.offset) * 1000  # Convert to ms
                    metric = PerformanceMetric(
                        metric_type=MetricType.SYNC_OFFSET,
                        device_id=device_id,
                        value=offset_ms,
                        timestamp=timestamp
                    )
                    self._store_metric(metric)
                
                # Sync uncertainty metric
                if status.uncertainty is not None:
                    uncertainty_ms = status.uncertainty * 1000  # Convert to ms
                    metric = PerformanceMetric(
                        metric_type=MetricType.SYNC_UNCERTAINTY,
                        device_id=device_id,
                        value=uncertainty_ms,
                        timestamp=timestamp
                    )
                    self._store_metric(metric)
                
        except Exception as e:
            logging.error(f"Error collecting sync metrics: {e}")
    
    async def _collect_device_metrics(self, timestamp: float):
        """Collect device health metrics."""
        try:
            for device_id, device in self.device_manager.devices.items():
                # Device health score (simplified)
                health_score = 1.0 if device.status == DeviceStatus.CONNECTED else 0.0
                
                metric = PerformanceMetric(
                    metric_type=MetricType.DEVICE_HEALTH,
                    device_id=device_id,
                    value=health_score,
                    timestamp=timestamp,
                    metadata={'status': device.status.value}
                )
                self._store_metric(metric)
                
        except Exception as e:
            logging.error(f"Error collecting device metrics: {e}")
    
    async def _collect_system_metrics(self, timestamp: float):
        """Collect system-wide metrics."""
        try:
            # Calculate overall system throughput (simplified)
            recent_metrics = self._get_recent_metrics(60.0)  # Last minute
            throughput = len(recent_metrics) / 60.0  # Metrics per second
            
            metric = PerformanceMetric(
                metric_type=MetricType.THROUGHPUT,
                device_id=None,
                value=throughput,
                timestamp=timestamp
            )
            self._store_metric(metric)
            
        except Exception as e:
            logging.error(f"Error collecting system metrics: {e}")
    
    def _store_metric(self, metric: PerformanceMetric):
        """Store a metric in the appropriate collections."""
        # Store in global metrics history
        key = f"{metric.metric_type.value}_{metric.device_id or 'system'}"
        self.metrics_history[key].append(metric)
        
        # Store in device-specific metrics if applicable
        if metric.device_id:
            self.device_metrics[metric.device_id][metric.metric_type.value].append(metric)
    
    async def _analyze_metrics(self):
        """Analyze collected metrics and generate alerts."""
        try:
            # Check for threshold violations
            await self._check_alert_thresholds()
            
            # Analyze trends
            await self._analyze_trends()
            
            # Update alert status
            await self._update_alert_status()
            
        except Exception as e:
            logging.error(f"Error in metrics analysis: {e}")
    
    async def _check_alert_thresholds(self):
        """Check metrics against alert thresholds."""
        current_time = time.time()
        
        for metric_type, thresholds in self.alert_thresholds.items():
            # Get recent metrics for this type
            recent_metrics = self._get_recent_metrics_by_type(metric_type, 60.0)
            
            # Group by device
            device_metrics = defaultdict(list)
            for metric in recent_metrics:
                device_metrics[metric.device_id or 'system'].append(metric)
            
            # Check each device
            for device_id, metrics in device_metrics.items():
                if not metrics:
                    continue
                
                # Calculate average value
                avg_value = statistics.mean([m.value for m in metrics])
                
                # Check thresholds
                alert_level = self._determine_alert_level(metric_type, avg_value, thresholds)
                
                if alert_level:
                    await self._create_alert(
                        metric_type, device_id, avg_value, 
                        thresholds[alert_level.value], alert_level, current_time
                    )
    
    def _determine_alert_level(self, metric_type: MetricType, value: float, 
                              thresholds: Dict[str, float]) -> Optional[AlertLevel]:
        """Determine alert level based on metric value and thresholds."""
        if metric_type in [MetricType.SYNC_QUALITY, MetricType.DEVICE_HEALTH]:
            # Lower values are worse for these metrics
            if value <= thresholds['critical']:
                return AlertLevel.CRITICAL
            elif value <= thresholds['error']:
                return AlertLevel.ERROR
            elif value <= thresholds['warning']:
                return AlertLevel.WARNING
        else:
            # Higher values are worse for these metrics
            if value >= thresholds['critical']:
                return AlertLevel.CRITICAL
            elif value >= thresholds['error']:
                return AlertLevel.ERROR
            elif value >= thresholds['warning']:
                return AlertLevel.WARNING
        
        return None
    
    async def _create_alert(self, metric_type: MetricType, device_id: str, 
                          value: float, threshold: float, level: AlertLevel, timestamp: float):
        """Create a new performance alert."""
        alert_id = f"{metric_type.value}_{device_id}_{level.value}_{int(timestamp)}"
        
        # Check if similar alert already exists
        existing_key = f"{metric_type.value}_{device_id}_{level.value}"
        if any(existing_key in alert.alert_id for alert in self.active_alerts.values()):
            return  # Don't create duplicate alerts
        
        # Create alert message
        device_str = f"device {device_id}" if device_id != 'system' else "system"
        message = f"{metric_type.value.replace('_', ' ').title()} {level.value} for {device_str}: {value:.2f} (threshold: {threshold:.2f})"
        
        alert = PerformanceAlert(
            alert_id=alert_id,
            level=level,
            message=message,
            metric_type=metric_type,
            device_id=device_id if device_id != 'system' else None,
            value=value,
            threshold=threshold,
            timestamp=timestamp
        )
        
        self.active_alerts[alert_id] = alert
        logging.warning(f"Performance alert created: {message}")
    
    async def _analyze_trends(self):
        """Analyze metric trends for predictive insights."""
        # This is a simplified trend analysis
        # In production, this could include more sophisticated analysis
        try:
            for metric_type in MetricType:
                recent_metrics = self._get_recent_metrics_by_type(metric_type, self.analysis_window)
                
                if len(recent_metrics) < 10:  # Need minimum data points
                    continue
                
                # Simple trend detection
                values = [m.value for m in recent_metrics[-10:]]  # Last 10 values
                if len(values) >= 5:
                    # Check if trend is consistently worsening
                    trend_slope = self._calculate_trend_slope(values)
                    
                    if abs(trend_slope) > 0.1:  # Significant trend
                        trend_direction = "increasing" if trend_slope > 0 else "decreasing"
                        logging.info(f"Trend detected for {metric_type.value}: {trend_direction} (slope: {trend_slope:.3f})")
                        
        except Exception as e:
            logging.error(f"Error in trend analysis: {e}")
    
    def _calculate_trend_slope(self, values: List[float]) -> float:
        """Calculate simple linear trend slope."""
        if len(values) < 2:
            return 0.0
        
        n = len(values)
        x = list(range(n))
        
        # Simple linear regression slope
        x_mean = statistics.mean(x)
        y_mean = statistics.mean(values)
        
        numerator = sum((x[i] - x_mean) * (values[i] - y_mean) for i in range(n))
        denominator = sum((x[i] - x_mean) ** 2 for i in range(n))
        
        return numerator / denominator if denominator != 0 else 0.0
    
    async def _update_alert_status(self):
        """Update status of active alerts."""
        current_time = time.time()
        resolved_alerts = []
        
        for alert_id, alert in self.active_alerts.items():
            # Check if alert condition has been resolved
            recent_metrics = self._get_recent_metrics_by_type(alert.metric_type, 60.0)
            device_metrics = [m for m in recent_metrics if m.device_id == alert.device_id]
            
            if device_metrics:
                avg_value = statistics.mean([m.value for m in device_metrics])
                
                # Check if value is back within acceptable range
                if self._determine_alert_level(alert.metric_type, avg_value, 
                                             self.alert_thresholds[alert.metric_type]) is None:
                    alert.resolved = True
                    alert.resolved_timestamp = current_time
                    resolved_alerts.append(alert_id)
                    logging.info(f"Alert resolved: {alert.message}")
        
        # Move resolved alerts to history
        for alert_id in resolved_alerts:
            alert = self.active_alerts.pop(alert_id)
            self.alert_history.append(alert)
            
            # Limit alert history size
            if len(self.alert_history) > self.max_alert_history:
                self.alert_history = self.alert_history[-self.max_alert_history:]
    
    def _get_recent_metrics(self, window_seconds: float) -> List[PerformanceMetric]:
        """Get all metrics within the specified time window."""
        cutoff_time = time.time() - window_seconds
        recent_metrics = []
        
        for metrics_deque in self.metrics_history.values():
            for metric in metrics_deque:
                if metric.timestamp >= cutoff_time:
                    recent_metrics.append(metric)
        
        return recent_metrics
    
    def _get_recent_metrics_by_type(self, metric_type: MetricType, 
                                   window_seconds: float) -> List[PerformanceMetric]:
        """Get recent metrics of a specific type."""
        cutoff_time = time.time() - window_seconds
        recent_metrics = []
        
        for key, metrics_deque in self.metrics_history.items():
            if key.startswith(metric_type.value):
                for metric in metrics_deque:
                    if metric.timestamp >= cutoff_time:
                        recent_metrics.append(metric)
        
        return sorted(recent_metrics, key=lambda m: m.timestamp)
    
    def get_current_performance_summary(self) -> Dict[str, Any]:
        """Get current performance summary."""
        current_time = time.time()
        recent_window = 300.0  # 5 minutes
        
        summary = {
            'timestamp': current_time,
            'active_alerts': len(self.active_alerts),
            'total_devices': len(self.device_manager.devices) if self.device_manager else 0,
            'metrics': {}
        }
        
        # Calculate summary statistics for each metric type
        for metric_type in MetricType:
            recent_metrics = self._get_recent_metrics_by_type(metric_type, recent_window)
            
            if recent_metrics:
                values = [m.value for m in recent_metrics]
                summary['metrics'][metric_type.value] = {
                    'current': values[-1] if values else 0.0,
                    'average': statistics.mean(values),
                    'min': min(values),
                    'max': max(values),
                    'count': len(values)
                }
        
        return summary
    
    def generate_analytics_report(self, start_time: float, end_time: float) -> AnalyticsReport:
        """Generate comprehensive analytics report for time period."""
        report_id = f"analytics_{int(start_time)}_{int(end_time)}"
        
        # Get metrics for the time period
        period_metrics = []
        for metrics_deque in self.metrics_history.values():
            for metric in metrics_deque:
                if start_time <= metric.timestamp <= end_time:
                    period_metrics.append(metric)
        
        # Calculate summary statistics
        summary_stats = self._calculate_summary_statistics(period_metrics)
        
        # Analyze device performance
        device_performance = self._analyze_device_performance(period_metrics)
        
        # Get alerts from the period
        period_alerts = [alert for alert in self.alert_history 
                        if start_time <= alert.timestamp <= end_time]
        period_alerts.extend([alert for alert in self.active_alerts.values() 
                            if start_time <= alert.timestamp <= end_time])
        
        # Generate recommendations
        recommendations = self._generate_recommendations(summary_stats, device_performance, period_alerts)
        
        return AnalyticsReport(
            report_id=report_id,
            start_time=start_time,
            end_time=end_time,
            device_count=len(device_performance),
            total_metrics=len(period_metrics),
            summary_stats=summary_stats,
            device_performance=device_performance,
            alerts=period_alerts,
            recommendations=recommendations
        )
    
    def _calculate_summary_statistics(self, metrics: List[PerformanceMetric]) -> Dict[str, Any]:
        """Calculate summary statistics for metrics."""
        stats = {}
        
        # Group metrics by type
        by_type = defaultdict(list)
        for metric in metrics:
            by_type[metric.metric_type.value].append(metric.value)
        
        # Calculate statistics for each type
        for metric_type, values in by_type.items():
            if values:
                stats[metric_type] = {
                    'count': len(values),
                    'mean': statistics.mean(values),
                    'median': statistics.median(values),
                    'std_dev': statistics.stdev(values) if len(values) > 1 else 0.0,
                    'min': min(values),
                    'max': max(values)
                }
        
        return stats
    
    def _analyze_device_performance(self, metrics: List[PerformanceMetric]) -> Dict[str, Dict[str, Any]]:
        """Analyze performance by device."""
        device_performance = {}
        
        # Group metrics by device
        by_device = defaultdict(lambda: defaultdict(list))
        for metric in metrics:
            if metric.device_id:
                by_device[metric.device_id][metric.metric_type.value].append(metric.value)
        
        # Calculate device-specific statistics
        for device_id, device_metrics in by_device.items():
            device_stats = {}
            
            for metric_type, values in device_metrics.items():
                if values:
                    device_stats[metric_type] = {
                        'mean': statistics.mean(values),
                        'min': min(values),
                        'max': max(values),
                        'count': len(values)
                    }
            
            device_performance[device_id] = device_stats
        
        return device_performance
    
    def _generate_recommendations(self, summary_stats: Dict[str, Any], 
                                device_performance: Dict[str, Dict[str, Any]], 
                                alerts: List[PerformanceAlert]) -> List[str]:
        """Generate performance recommendations."""
        recommendations = []
        
        # Check sync quality
        if 'sync_quality' in summary_stats:
            avg_quality = summary_stats['sync_quality']['mean']
            if avg_quality < 0.7:
                recommendations.append("Overall sync quality is below optimal. Consider network optimization.")
        
        # Check sync offsets
        if 'sync_offset' in summary_stats:
            avg_offset = summary_stats['sync_offset']['mean']
            if avg_offset > 50.0:
                recommendations.append(f"High sync offsets detected (avg: {avg_offset:.1f}ms). Review time sync configuration.")
        
        # Device-specific recommendations
        for device_id, performance in device_performance.items():
            if 'sync_quality' in performance and performance['sync_quality']['mean'] < 0.6:
                recommendations.append(f"Device {device_id} has poor sync quality. Check device connectivity.")
        
        # Alert-based recommendations
        critical_alerts = [a for a in alerts if a.level == AlertLevel.CRITICAL]
        if critical_alerts:
            recommendations.append(f"Address {len(critical_alerts)} critical alerts immediately.")
        
        if not recommendations:
            recommendations.append("System performance is within acceptable parameters.")
        
        return recommendations
    
    def save_analytics_report(self, report: AnalyticsReport, output_path: str):
        """Save analytics report to file."""
        report_data = {
            'report_id': report.report_id,
            'start_time': report.start_time,
            'end_time': report.end_time,
            'device_count': report.device_count,
            'total_metrics': report.total_metrics,
            'summary_stats': report.summary_stats,
            'device_performance': report.device_performance,
            'alerts': [alert.to_dict() for alert in report.alerts],
            'recommendations': report.recommendations,
            'generated_at': datetime.now().isoformat()
        }
        
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(report_data, f, indent=2, default=str)
        
        logging.info(f"Analytics report saved to {output_path}")
    
    async def cleanup(self):
        """Cleanup analytics resources."""
        await self.stop_analytics()
        self.metrics_history.clear()
        self.device_metrics.clear()
        self.active_alerts.clear()
        logging.info("PerformanceAnalytics cleaned up")


async def main():
    """Example usage of performance analytics."""
    from utils.logger import setup_logging
    
    setup_logging(level=logging.INFO)
    
    # Create analytics system
    analytics = PerformanceAnalytics()
    
    try:
        # Start analytics
        await analytics.start_analytics()
        
        # Let it run for a bit
        await asyncio.sleep(30)
        
        # Get current summary
        summary = analytics.get_current_performance_summary()
        print(f"Current performance summary: {json.dumps(summary, indent=2)}")
        
        # Generate report
        end_time = time.time()
        start_time = end_time - 300  # Last 5 minutes
        report = analytics.generate_analytics_report(start_time, end_time)
        
        # Save report
        analytics.save_analytics_report(report, "performance_report.json")
        print(f"Generated analytics report with {report.total_metrics} metrics")
        
    except Exception as e:
        logging.error(f"Analytics example failed: {e}")
    
    finally:
        await analytics.cleanup()


if __name__ == "__main__":
    asyncio.run(main())