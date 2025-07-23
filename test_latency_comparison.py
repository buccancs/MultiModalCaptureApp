#!/usr/bin/env python3
"""
WiFi vs Bluetooth Latency Comparison Test Script

This script demonstrates the latency comparison functionality between
WiFi and Bluetooth communication methods for Android devices.
"""

import asyncio
import logging
import json
import time
import sys
import os
from pathlib import Path

# Add the pc_controller directory to the Python path
sys.path.insert(0, str(Path(__file__).parent / "pc_controller"))

from analytics.latency_comparator import LatencyComparator, CommunicationMethod, LatencyTestType
from analytics.performance_analytics import PerformanceAnalytics
from core.device_manager import DeviceManager, AndroidDevice, DeviceStatus
from core.time_sync import TimeSynchronizer


class MockConfig:
    """Mock configuration for testing."""
    def __init__(self):
        self.network = MockNetworkConfig()


class MockNetworkConfig:
    """Mock network configuration."""
    def __init__(self):
        self.discovery_port = 8889
        self.connection_timeout = 5000


class LatencyComparisonDemo:
    """Demonstration of WiFi vs Bluetooth latency comparison."""
    
    def __init__(self):
        """Initialize the demo."""
        # Setup logging
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        self.logger = logging.getLogger(__name__)
        
        # Initialize components
        self.config = MockConfig()
        self.device_manager = DeviceManager(self.config)
        self.time_synchronizer = TimeSynchronizer()
        self.performance_analytics = PerformanceAnalytics(
            self.time_synchronizer, 
            self.device_manager
        )
        self.latency_comparator = LatencyComparator(
            self.device_manager,
            self.performance_analytics
        )
        
        # Connect signals
        self.latency_comparator.measurement_completed.connect(self.on_measurement_completed)
        self.latency_comparator.comparison_completed.connect(self.on_comparison_completed)
        self.latency_comparator.test_progress.connect(self.on_test_progress)
        
        self.logger.info("LatencyComparisonDemo initialized")
    
    def on_measurement_completed(self, measurement_data):
        """Handle completed measurement."""
        method = measurement_data.get('communication_method', 'unknown')
        test_type = measurement_data.get('test_type', 'unknown')
        latency = measurement_data.get('latency_ms', 0)
        success = measurement_data.get('success', False)
        
        status = "✓" if success else "✗"
        self.logger.info(f"{status} {method.upper()} {test_type}: {latency:.2f}ms")
    
    def on_comparison_completed(self, device_id, result_data):
        """Handle completed comparison."""
        self.logger.info(f"Comparison completed for device {device_id}")
        self.print_comparison_results(result_data)
    
    def on_test_progress(self, device_id, current, total):
        """Handle test progress updates."""
        progress = (current / total) * 100
        self.logger.info(f"Test progress for {device_id}: {progress:.1f}% ({current}/{total})")
    
    def create_mock_device(self, device_id: str, supports_bluetooth: bool = True) -> AndroidDevice:
        """Create a mock Android device for testing."""
        capabilities = ["wifi", "camera", "microphone"]
        if supports_bluetooth:
            capabilities.append("bluetooth")
        
        device = AndroidDevice(
            device_id=device_id,
            device_name=f"Test Device {device_id}",
            ip_address="192.168.1.100",
            server_port=8888,
            capabilities=capabilities
        )
        device.status = DeviceStatus.CONNECTED
        device.connection_time = time.time()
        
        return device
    
    async def run_latency_comparison_demo(self):
        """Run the complete latency comparison demonstration."""
        self.logger.info("Starting WiFi vs Bluetooth Latency Comparison Demo")
        self.logger.info("=" * 60)
        
        try:
            # Create mock devices
            device1 = self.create_mock_device("device_001", supports_bluetooth=True)
            device2 = self.create_mock_device("device_002", supports_bluetooth=False)
            
            # Add devices to device manager
            self.device_manager.devices[device1.device_id] = device1
            self.device_manager.devices[device2.device_id] = device2
            
            self.logger.info(f"Created mock devices:")
            self.logger.info(f"  - {device1.device_name} (WiFi + Bluetooth)")
            self.logger.info(f"  - {device2.device_name} (WiFi only)")
            
            # Start performance analytics
            await self.performance_analytics.start_analytics()
            
            # Run comparison tests
            await self.run_device_comparison(device1.device_id, "Device with Bluetooth support")
            await asyncio.sleep(2)  # Brief pause between tests
            await self.run_device_comparison(device2.device_id, "Device without Bluetooth support")
            
            # Generate comprehensive report
            await self.generate_final_report()
            
        except Exception as e:
            self.logger.error(f"Error in demo: {e}")
        finally:
            # Cleanup
            await self.cleanup()
    
    async def run_device_comparison(self, device_id: str, description: str):
        """Run latency comparison for a specific device."""
        self.logger.info(f"\nTesting {description}")
        self.logger.info("-" * 40)
        
        # Start comparison test (shorter duration for demo)
        test_duration = 0.5  # 30 seconds for demo
        success = await self.latency_comparator.start_comparison_test(
            device_id, 
            test_duration
        )
        
        if not success:
            self.logger.error(f"Failed to start comparison test for {device_id}")
            return
        
        # Wait for test completion
        await asyncio.sleep(test_duration * 60 + 5)  # Wait for test + buffer
        
        # Get results
        results = self.latency_comparator.get_comparison_results(device_id)
        if results:
            self.print_detailed_results(results)
        else:
            self.logger.warning(f"No results available for {device_id}")
    
    def print_comparison_results(self, result_data):
        """Print comparison results in a formatted way."""
        device_id = result_data.get('device_id', 'unknown')
        wifi_latency = result_data.get('wifi_avg_latency_ms', 0)
        bluetooth_latency = result_data.get('bluetooth_avg_latency_ms', 0)
        wifi_success = result_data.get('wifi_success_rate', 0)
        bluetooth_success = result_data.get('bluetooth_success_rate', 0)
        recommended = result_data.get('recommended_method', 'unknown')
        
        self.logger.info(f"\nResults for {device_id}:")
        self.logger.info(f"  WiFi Latency:      {wifi_latency:.2f}ms (Success: {wifi_success:.1f}%)")
        self.logger.info(f"  Bluetooth Latency: {bluetooth_latency:.2f}ms (Success: {bluetooth_success:.1f}%)")
        self.logger.info(f"  Recommended:       {recommended.upper()}")
    
    def print_detailed_results(self, results):
        """Print detailed results analysis."""
        self.logger.info(f"\nDetailed Analysis for {results.device_id}:")
        self.logger.info(f"  Test Duration: {results.test_duration_seconds:.1f} seconds")
        self.logger.info(f"  WiFi Measurements: {len(results.wifi_measurements)}")
        self.logger.info(f"  Bluetooth Measurements: {len(results.bluetooth_measurements)}")
        
        if results.wifi_measurements:
            wifi_latencies = [m.latency_ms for m in results.wifi_measurements if m.success]
            if wifi_latencies:
                self.logger.info(f"  WiFi Latency Range: {min(wifi_latencies):.2f} - {max(wifi_latencies):.2f}ms")
        
        if results.bluetooth_measurements:
            bt_latencies = [m.latency_ms for m in results.bluetooth_measurements if m.success]
            if bt_latencies:
                self.logger.info(f"  Bluetooth Latency Range: {min(bt_latencies):.2f} - {max(bt_latencies):.2f}ms")
        
        # Performance comparison
        if results.wifi_avg_latency > 0 and results.bluetooth_avg_latency > 0:
            if results.wifi_avg_latency < results.bluetooth_avg_latency:
                improvement = ((results.bluetooth_avg_latency - results.wifi_avg_latency) / results.bluetooth_avg_latency) * 100
                self.logger.info(f"  WiFi is {improvement:.1f}% faster than Bluetooth")
            else:
                improvement = ((results.wifi_avg_latency - results.bluetooth_avg_latency) / results.wifi_avg_latency) * 100
                self.logger.info(f"  Bluetooth is {improvement:.1f}% faster than WiFi")
    
    async def generate_final_report(self):
        """Generate and display final comprehensive report."""
        self.logger.info("\n" + "=" * 60)
        self.logger.info("COMPREHENSIVE LATENCY COMPARISON REPORT")
        self.logger.info("=" * 60)
        
        report = self.latency_comparator.generate_comparison_report()
        
        # Summary statistics
        summary = report.get('summary', {})
        self.logger.info(f"Total Devices Tested: {report.get('total_devices_tested', 0)}")
        self.logger.info(f"Average WiFi Latency: {summary.get('wifi_avg_latency_ms', 0):.2f}ms")
        self.logger.info(f"Average Bluetooth Latency: {summary.get('bluetooth_avg_latency_ms', 0):.2f}ms")
        self.logger.info(f"WiFi Success Rate: {summary.get('wifi_success_rate', 0):.1f}%")
        self.logger.info(f"Bluetooth Success Rate: {summary.get('bluetooth_success_rate', 0):.1f}%")
        
        # Recommendations
        recommendations = summary.get('recommended_method_distribution', {})
        if recommendations:
            self.logger.info("\nRecommendation Distribution:")
            for method, percentage in recommendations.items():
                self.logger.info(f"  {method.upper()}: {percentage:.1f}%")
        
        # Save report to file
        report_file = Path("latency_comparison_report.json")
        with open(report_file, 'w') as f:
            json.dump(report, f, indent=2, default=str)
        self.logger.info(f"\nDetailed report saved to: {report_file}")
        
        # Generate recommendations
        self.generate_recommendations(summary)
    
    def generate_recommendations(self, summary):
        """Generate actionable recommendations based on results."""
        self.logger.info("\nRECOMMENDations:")
        self.logger.info("-" * 20)
        
        wifi_latency = summary.get('wifi_avg_latency_ms', 0)
        bluetooth_latency = summary.get('bluetooth_avg_latency_ms', 0)
        wifi_success = summary.get('wifi_success_rate', 0)
        bluetooth_success = summary.get('bluetooth_success_rate', 0)
        
        if wifi_latency > 0 and bluetooth_latency > 0:
            if wifi_latency < bluetooth_latency and wifi_success >= bluetooth_success:
                self.logger.info("✓ Use WiFi for optimal performance")
                self.logger.info("  - Lower latency and reliable connection")
            elif bluetooth_latency < wifi_latency and bluetooth_success >= wifi_success:
                self.logger.info("✓ Use Bluetooth for optimal performance")
                self.logger.info("  - Lower latency and reliable connection")
            else:
                self.logger.info("✓ Consider hybrid approach:")
                self.logger.info("  - Use WiFi for high-throughput data")
                self.logger.info("  - Use Bluetooth for control commands")
        
        if wifi_success < 90:
            self.logger.info("⚠ WiFi connection reliability issues detected")
            self.logger.info("  - Check network stability and signal strength")
        
        if bluetooth_success < 90:
            self.logger.info("⚠ Bluetooth connection reliability issues detected")
            self.logger.info("  - Check device proximity and interference")
        
        if wifi_latency > 100:
            self.logger.info("⚠ High WiFi latency detected")
            self.logger.info("  - Consider network optimization or wired connection")
        
        if bluetooth_latency > 200:
            self.logger.info("⚠ High Bluetooth latency detected")
            self.logger.info("  - Consider using WiFi for time-critical operations")
    
    async def cleanup(self):
        """Cleanup resources."""
        self.logger.info("\nCleaning up resources...")
        
        try:
            await self.performance_analytics.stop_analytics()
            self.latency_comparator.cleanup()
            self.device_manager.cleanup()
        except Exception as e:
            self.logger.error(f"Error during cleanup: {e}")
        
        self.logger.info("Demo completed successfully!")


async def main():
    """Main entry point for the demo."""
    demo = LatencyComparisonDemo()
    await demo.run_latency_comparison_demo()


if __name__ == "__main__":
    print("WiFi vs Bluetooth Latency Comparison Demo")
    print("=========================================")
    print()
    print("This demo will:")
    print("1. Create mock Android devices with different capabilities")
    print("2. Run latency comparison tests for WiFi and Bluetooth")
    print("3. Analyze and compare the results")
    print("4. Generate recommendations for optimal communication method")
    print()
    print("Starting demo in 3 seconds...")
    time.sleep(3)
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nDemo interrupted by user")
    except Exception as e:
        print(f"Demo failed with error: {e}")
        import traceback
        traceback.print_exc()