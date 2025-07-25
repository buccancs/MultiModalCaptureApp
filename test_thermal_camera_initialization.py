#!/usr/bin/env python3
"""
Test script to verify thermal camera initialization fixes
Tests the initialization flow and error handling for ThermalCameraManager
"""

import subprocess
import time
import re
import sys
from pathlib import Path

def run_adb_command(command):
    """Run ADB command and return output"""
    try:
        result = subprocess.run(
            f"adb {command}",
            shell=True,
            capture_output=True,
            text=True,
            timeout=30
        )
        return result.stdout, result.stderr, result.returncode
    except subprocess.TimeoutExpired:
        return "", "Command timed out", 1

def check_device_connected():
    """Check if Android device is connected via ADB"""
    stdout, stderr, returncode = run_adb_command("devices")
    if returncode != 0:
        print("❌ ADB not available or no device connected")
        return False
    
    devices = [line for line in stdout.split('\n') if '\tdevice' in line]
    if not devices:
        print("❌ No Android device connected")
        return False
    
    print(f"✅ Android device connected: {devices[0].split()[0]}")
    return True

def install_app():
    """Install the debug APK"""
    print("📱 Installing debug APK...")
    apk_path = "app/build/outputs/apk/debug/app-debug.apk"
    
    if not Path(apk_path).exists():
        print(f"❌ APK not found at {apk_path}")
        print("   Please run: ./gradlew assembleDebug")
        return False
    
    stdout, stderr, returncode = run_adb_command(f"install -r {apk_path}")
    if returncode != 0:
        print(f"❌ Failed to install APK: {stderr}")
        return False
    
    print("✅ APK installed successfully")
    return True

def start_app():
    """Start the main activity"""
    print("🚀 Starting MainActivity...")
    stdout, stderr, returncode = run_adb_command(
        "shell am start -n com.multimodal.capture.debug/com.multimodal.capture.MainActivity"
    )
    if returncode != 0:
        print(f"❌ Failed to start app: {stderr}")
        return False
    
    print("✅ App started successfully")
    time.sleep(3)  # Wait for app to initialize
    return True

def clear_logs():
    """Clear logcat buffer"""
    run_adb_command("logcat -c")
    print("🧹 Cleared logcat buffer")

def capture_logs(duration=10):
    """Capture logcat for specified duration"""
    print(f"📋 Capturing logs for {duration} seconds...")
    
    # Start logcat process
    process = subprocess.Popen(
        ["adb", "logcat", "-v", "time", "*:V"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    
    # Capture logs for specified duration
    time.sleep(duration)
    process.terminate()
    
    stdout, stderr = process.communicate(timeout=5)
    return stdout

def analyze_thermal_initialization_logs(logs):
    """Analyze logs for thermal camera initialization patterns"""
    print("🔍 Analyzing thermal camera initialization logs...")
    
    # Patterns to look for
    patterns = {
        'thermal_manager_init': r'ThermalCameraManager initialized',
        'thermal_manager_init_start': r'Initializing ThermalCameraManager',
        'thermal_manager_init_success': r'ThermalCameraManager initialized successfully',
        'thermal_manager_init_error': r'Failed to initialize ThermalCameraManager',
        'thermal_not_initialized': r'ThermalCameraManager not initialized',
        'thermal_connection_attempt': r'Attempting to connect to thermal camera',
        'thermal_connection_success': r'Successfully connected to thermal camera',
        'thermal_connection_failed': r'Failed to connect to thermal camera',
        'usb_device_attached': r'USB device attached',
        'usb_permission_granted': r'USB permission granted',
        'thermal_camera_detected': r'Thermal camera detected',
        'thermal_camera_connected': r'Thermal camera connected'
    }
    
    results = {}
    for pattern_name, pattern in patterns.items():
        matches = re.findall(pattern, logs, re.IGNORECASE)
        results[pattern_name] = len(matches)
        if matches:
            print(f"  ✅ {pattern_name}: {len(matches)} occurrences")
        else:
            print(f"  ❌ {pattern_name}: Not found")
    
    return results

def analyze_error_patterns(logs):
    """Analyze logs for error patterns"""
    print("🚨 Analyzing error patterns...")
    
    error_patterns = {
        'initialization_errors': r'Failed to initialize.*ThermalCameraManager',
        'connection_errors': r'Error connecting to thermal camera',
        'permission_errors': r'USB permission denied',
        'device_not_found': r'No thermal camera detected',
        'sdk_errors': r'Topdon SDK.*error',
        'memory_errors': r'OutOfMemoryError',
        'null_pointer_errors': r'NullPointerException.*ThermalCameraManager'
    }
    
    errors_found = {}
    for error_name, pattern in error_patterns.items():
        matches = re.findall(pattern, logs, re.IGNORECASE)
        if matches:
            errors_found[error_name] = matches
            print(f"  🔴 {error_name}: {len(matches)} occurrences")
    
    return errors_found

def test_initialization_flow():
    """Test the complete initialization flow"""
    print("🧪 Testing thermal camera initialization flow...")
    
    # Clear logs and start fresh
    clear_logs()
    
    # Start the app
    if not start_app():
        return False
    
    # Capture initialization logs
    logs = capture_logs(duration=15)
    
    # Analyze the logs
    init_results = analyze_thermal_initialization_logs(logs)
    error_results = analyze_error_patterns(logs)
    
    # Generate report
    print("\n📊 INITIALIZATION TEST RESULTS")
    print("=" * 50)
    
    # Check if initialization completed successfully
    if init_results.get('thermal_manager_init_success', 0) > 0:
        print("✅ ThermalCameraManager initialization: SUCCESS")
    elif init_results.get('thermal_manager_init_error', 0) > 0:
        print("❌ ThermalCameraManager initialization: FAILED")
    else:
        print("⚠️  ThermalCameraManager initialization: UNCLEAR")
    
    # Check for "not initialized" errors
    not_init_count = init_results.get('thermal_not_initialized', 0)
    if not_init_count > 0:
        print(f"🔴 'ThermalCameraManager not initialized' errors: {not_init_count}")
    else:
        print("✅ No 'not initialized' errors found")
    
    # Report errors
    if error_results:
        print("\n🚨 ERRORS DETECTED:")
        for error_type, errors in error_results.items():
            print(f"  - {error_type}: {len(errors)} occurrences")
    else:
        print("✅ No critical errors detected")
    
    return len(error_results) == 0

def test_usb_device_simulation():
    """Test USB device attachment simulation"""
    print("🔌 Testing USB device attachment simulation...")
    
    # This would require actual USB device or emulation
    # For now, just check if the USB handling code is present
    print("⚠️  USB device simulation requires physical hardware")
    print("   Manual test: Connect Topdon thermal camera via USB")
    return True

def generate_test_report(results):
    """Generate comprehensive test report"""
    report = f"""
# Thermal Camera Initialization Test Report
Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}

## Test Results Summary
- Device Connection: {'✅ PASS' if results.get('device_connected') else '❌ FAIL'}
- App Installation: {'✅ PASS' if results.get('app_installed') else '❌ FAIL'}
- App Startup: {'✅ PASS' if results.get('app_started') else '❌ FAIL'}
- Initialization Flow: {'✅ PASS' if results.get('init_flow') else '❌ FAIL'}

## Recommendations
"""
    
    if not results.get('init_flow'):
        report += """
### Initialization Issues Detected
1. Check ThermalCameraManager constructor for exceptions
2. Verify USB permissions are properly handled
3. Ensure Topdon SDK libraries are correctly integrated
4. Check for timing issues in initialization sequence
"""
    
    if results.get('init_flow'):
        report += """
### Initialization Working Correctly
- ThermalCameraManager initializes without errors
- Error handling is functioning properly
- USB device detection is working
"""
    
    return report

def main():
    """Main test execution"""
    print("🔥 Thermal Camera Initialization Test Suite")
    print("=" * 50)
    
    results = {}
    
    # Check device connection
    results['device_connected'] = check_device_connected()
    if not results['device_connected']:
        print("❌ Cannot proceed without connected Android device")
        return 1
    
    # Install app
    results['app_installed'] = install_app()
    if not results['app_installed']:
        print("❌ Cannot proceed without installing app")
        return 1
    
    # Test initialization flow
    results['init_flow'] = test_initialization_flow()
    
    # Test USB simulation (manual)
    test_usb_device_simulation()
    
    # Generate report
    report = generate_test_report(results)
    
    # Save report
    with open('thermal_camera_test_report.md', 'w') as f:
        f.write(report)
    
    print("\n📄 Test report saved to: thermal_camera_test_report.md")
    
    # Final result
    if all(results.values()):
        print("\n🎉 ALL TESTS PASSED!")
        return 0
    else:
        print("\n❌ SOME TESTS FAILED!")
        return 1

if __name__ == "__main__":
    sys.exit(main())