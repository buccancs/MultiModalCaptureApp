#!/usr/bin/env python3
"""
Test script to validate thermal camera performance optimizations
Tests object pooling, frame skipping, and buffered I/O improvements
"""

import subprocess
import time
import re
import sys
import json
from pathlib import Path
from typing import Dict, List, Any

class ThermalPerformanceValidator:
    def __init__(self):
        self.test_results = {}
        self.performance_metrics = {}
        
    def run_adb_command(self, command: str, timeout: int = 30) -> tuple:
        """Run ADB command and return output"""
        try:
            result = subprocess.run(
                f"adb {command}",
                shell=True,
                capture_output=True,
                text=True,
                timeout=timeout
            )
            return result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired:
            return "", "Command timed out", 1

    def check_device_connected(self) -> bool:
        """Check if Android device is connected via ADB"""
        stdout, stderr, returncode = self.run_adb_command("devices")
        if returncode != 0:
            print("❌ ADB not available or no device connected")
            return False
        
        devices = [line for line in stdout.split('\n') if '\tdevice' in line]
        if not devices:
            print("❌ No Android device connected")
            return False
        
        print(f"✅ Android device connected: {devices[0].split()[0]}")
        return True

    def install_and_start_app(self) -> bool:
        """Install and start the app"""
        print("📱 Installing and starting app...")
        
        # Install APK
        apk_path = "app/build/outputs/apk/debug/app-debug.apk"
        if not Path(apk_path).exists():
            print(f"❌ APK not found at {apk_path}")
            return False
        
        stdout, stderr, returncode = self.run_adb_command(f"install -r {apk_path}")
        if returncode != 0:
            print(f"❌ Failed to install APK: {stderr}")
            return False
        
        # Start app
        stdout, stderr, returncode = self.run_adb_command(
            "shell am start -n com.multimodal.capture.debug/com.multimodal.capture.MainActivity"
        )
        if returncode != 0:
            print(f"❌ Failed to start app: {stderr}")
            return False
        
        print("✅ App installed and started successfully")
        time.sleep(5)  # Wait for app to initialize
        return True

    def clear_logs_and_start_monitoring(self):
        """Clear logcat and start monitoring"""
        self.run_adb_command("logcat -c")
        print("🧹 Cleared logcat buffer")

    def capture_performance_logs(self, duration: int = 30) -> str:
        """Capture performance-related logs"""
        print(f"📋 Capturing performance logs for {duration} seconds...")
        
        # Start logcat process with thermal-specific filters
        process = subprocess.Popen([
            "adb", "logcat", "-v", "time", 
            "ThermalCameraManager:V", "MainActivity:V", "*:S"
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        
        time.sleep(duration)
        process.terminate()
        
        stdout, stderr = process.communicate(timeout=5)
        return stdout

    def analyze_object_pooling_performance(self, logs: str) -> Dict[str, Any]:
        """Analyze object pooling effectiveness"""
        print("🔍 Analyzing object pooling performance...")
        
        results = {
            'buffer_pool_initialized': False,
            'argb_pool_size': 0,
            'rotation_pool_size': 0,
            'pool_usage_detected': False,
            'memory_optimization_active': False
        }
        
        # Check for buffer pool initialization
        if re.search(r'Initialized buffer pools.*ARGB=(\d+).*Rotation=(\d+)', logs):
            results['buffer_pool_initialized'] = True
            match = re.search(r'ARGB=(\d+).*Rotation=(\d+)', logs)
            if match:
                results['argb_pool_size'] = int(match.group(1))
                results['rotation_pool_size'] = int(match.group(2))
        
        # Check for pool usage patterns
        if re.search(r'getArgbBuffer|returnArgbBuffer|getRotationBuffer|returnRotationBuffer', logs):
            results['pool_usage_detected'] = True
        
        # Check for memory optimization indicators
        if re.search(r'Memory optimization|buffer pool|reusable.*buffer', logs, re.IGNORECASE):
            results['memory_optimization_active'] = True
        
        return results

    def analyze_frame_skipping_performance(self, logs: str) -> Dict[str, Any]:
        """Analyze frame skipping effectiveness"""
        print("🔍 Analyzing frame skipping performance...")
        
        results = {
            'frame_skipping_active': False,
            'dropped_frame_count': 0,
            'performance_monitoring_active': False,
            'avg_processing_time': 0,
            'max_processing_time': 0
        }
        
        # Check for frame skipping
        dropped_frames = re.findall(r'Skipped frame due to high load.*dropped:\s*(\d+)', logs)
        if dropped_frames:
            results['frame_skipping_active'] = True
            results['dropped_frame_count'] = max([int(x) for x in dropped_frames])
        
        # Check for performance monitoring
        perf_logs = re.findall(r'Thermal frame processing: avg=(\d+)ms, max=(\d+)ms', logs)
        if perf_logs:
            results['performance_monitoring_active'] = True
            # Get the latest performance metrics
            latest_perf = perf_logs[-1]
            results['avg_processing_time'] = int(latest_perf[0])
            results['max_processing_time'] = int(latest_perf[1])
        
        return results

    def analyze_buffered_io_performance(self, logs: str) -> Dict[str, Any]:
        """Analyze buffered I/O effectiveness"""
        print("🔍 Analyzing buffered I/O performance...")
        
        results = {
            'buffered_streams_initialized': False,
            'yuv_stream_active': False,
            'argb_stream_active': False,
            'streams_closed_properly': False,
            'io_optimization_active': False
        }
        
        # Check for buffered stream initialization
        if re.search(r'Initialized.*buffered stream', logs):
            results['buffered_streams_initialized'] = True
        
        if re.search(r'YUV buffered stream', logs):
            results['yuv_stream_active'] = True
        
        if re.search(r'ARGB buffered stream', logs):
            results['argb_stream_active'] = True
        
        # Check for proper stream closure
        if re.search(r'buffered stream closed successfully', logs):
            results['streams_closed_properly'] = True
        
        # Check for I/O optimization indicators
        if re.search(r'BufferedOutputStream|buffered.*stream|I/O.*optimization', logs, re.IGNORECASE):
            results['io_optimization_active'] = True
        
        return results

    def simulate_thermal_camera_usage(self) -> bool:
        """Simulate thermal camera usage to trigger optimizations"""
        print("🔥 Simulating thermal camera usage...")
        
        try:
            # Try to trigger thermal camera operations
            commands = [
                "shell input tap 500 800",  # Tap on thermal camera area
                "shell input tap 400 600",  # Try different positions
                "shell input tap 600 700",
            ]
            
            for cmd in commands:
                self.run_adb_command(cmd)
                time.sleep(2)
            
            print("✅ Thermal camera simulation completed")
            return True
            
        except Exception as e:
            print(f"⚠️ Thermal camera simulation had issues: {e}")
            return False

    def generate_performance_report(self, 
                                  pooling_results: Dict[str, Any],
                                  skipping_results: Dict[str, Any],
                                  io_results: Dict[str, Any]) -> Dict[str, Any]:
        """Generate comprehensive performance report"""
        
        report = {
            'timestamp': time.strftime('%Y-%m-%d %H:%M:%S'),
            'test_summary': {
                'object_pooling': 'PASS' if pooling_results['buffer_pool_initialized'] else 'FAIL',
                'frame_skipping': 'PASS' if skipping_results['performance_monitoring_active'] else 'FAIL',
                'buffered_io': 'PASS' if io_results['buffered_streams_initialized'] else 'FAIL'
            },
            'detailed_results': {
                'object_pooling': pooling_results,
                'frame_skipping': skipping_results,
                'buffered_io': io_results
            },
            'performance_score': 0,
            'recommendations': []
        }
        
        # Calculate performance score
        score = 0
        if pooling_results['buffer_pool_initialized']:
            score += 30
        if pooling_results['pool_usage_detected']:
            score += 20
        if skipping_results['performance_monitoring_active']:
            score += 25
        if skipping_results['frame_skipping_active']:
            score += 15
        if io_results['buffered_streams_initialized']:
            score += 10
        
        report['performance_score'] = score
        
        # Generate recommendations
        if not pooling_results['buffer_pool_initialized']:
            report['recommendations'].append("Enable object pooling for ByteArray allocations")
        
        if not skipping_results['performance_monitoring_active']:
            report['recommendations'].append("Implement performance monitoring for frame processing")
        
        if not io_results['buffered_streams_initialized']:
            report['recommendations'].append("Use buffered streams for file I/O operations")
        
        if score >= 80:
            report['recommendations'].append("Performance optimizations are working well!")
        elif score >= 60:
            report['recommendations'].append("Good performance, minor optimizations needed")
        else:
            report['recommendations'].append("Significant performance improvements needed")
        
        return report

    def run_comprehensive_test(self) -> bool:
        """Run comprehensive thermal camera performance test"""
        print("🧪 Starting Thermal Camera Performance Optimization Test")
        print("=" * 60)
        
        # Check device connection
        if not self.check_device_connected():
            return False
        
        # Install and start app
        if not self.install_and_start_app():
            return False
        
        # Clear logs and start monitoring
        self.clear_logs_and_start_monitoring()
        
        # Simulate thermal camera usage
        self.simulate_thermal_camera_usage()
        
        # Capture performance logs
        logs = self.capture_performance_logs(duration=20)
        
        # Analyze different aspects of performance
        pooling_results = self.analyze_object_pooling_performance(logs)
        skipping_results = self.analyze_frame_skipping_performance(logs)
        io_results = self.analyze_buffered_io_performance(logs)
        
        # Generate comprehensive report
        report = self.generate_performance_report(pooling_results, skipping_results, io_results)
        
        # Display results
        self.display_results(report)
        
        # Save report
        self.save_report(report)
        
        return report['performance_score'] >= 60

    def display_results(self, report: Dict[str, Any]):
        """Display test results"""
        print("\n📊 THERMAL CAMERA PERFORMANCE TEST RESULTS")
        print("=" * 60)
        
        print(f"🎯 Overall Performance Score: {report['performance_score']}/100")
        print()
        
        print("📋 Test Summary:")
        for test_name, result in report['test_summary'].items():
            status = "✅" if result == "PASS" else "❌"
            print(f"  {status} {test_name.replace('_', ' ').title()}: {result}")
        
        print("\n🔍 Detailed Results:")
        
        # Object Pooling Results
        pooling = report['detailed_results']['object_pooling']
        print(f"  Object Pooling:")
        print(f"    - Buffer pools initialized: {'✅' if pooling['buffer_pool_initialized'] else '❌'}")
        print(f"    - ARGB pool size: {pooling['argb_pool_size']}")
        print(f"    - Rotation pool size: {pooling['rotation_pool_size']}")
        print(f"    - Pool usage detected: {'✅' if pooling['pool_usage_detected'] else '❌'}")
        
        # Frame Skipping Results
        skipping = report['detailed_results']['frame_skipping']
        print(f"  Frame Skipping:")
        print(f"    - Performance monitoring: {'✅' if skipping['performance_monitoring_active'] else '❌'}")
        print(f"    - Frame skipping active: {'✅' if skipping['frame_skipping_active'] else '❌'}")
        print(f"    - Dropped frames: {skipping['dropped_frame_count']}")
        print(f"    - Avg processing time: {skipping['avg_processing_time']}ms")
        
        # Buffered I/O Results
        io = report['detailed_results']['buffered_io']
        print(f"  Buffered I/O:")
        print(f"    - Buffered streams initialized: {'✅' if io['buffered_streams_initialized'] else '❌'}")
        print(f"    - YUV stream active: {'✅' if io['yuv_stream_active'] else '❌'}")
        print(f"    - ARGB stream active: {'✅' if io['argb_stream_active'] else '❌'}")
        print(f"    - Streams closed properly: {'✅' if io['streams_closed_properly'] else '❌'}")
        
        print("\n💡 Recommendations:")
        for rec in report['recommendations']:
            print(f"  • {rec}")

    def save_report(self, report: Dict[str, Any]):
        """Save test report to file"""
        report_file = Path("thermal_performance_test_report.json")
        with open(report_file, 'w') as f:
            json.dump(report, f, indent=2)
        
        print(f"\n📄 Test report saved to: {report_file}")

def main():
    """Main test execution"""
    validator = ThermalPerformanceValidator()
    
    try:
        success = validator.run_comprehensive_test()
        
        if success:
            print("\n🎉 THERMAL CAMERA PERFORMANCE OPTIMIZATIONS VALIDATED!")
            return 0
        else:
            print("\n❌ PERFORMANCE OPTIMIZATIONS NEED IMPROVEMENT!")
            return 1
            
    except KeyboardInterrupt:
        print("\n⚠️ Test interrupted by user")
        return 1
    except Exception as e:
        print(f"\n💥 Test failed with error: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())