#!/usr/bin/env python3
"""
Debug script for thermal camera connection issues.
This script helps analyze the thermal camera connection process and identify runtime issues.
"""

import subprocess
import time
import sys
import os

def run_adb_command(command):
    """Run an ADB command and return the output"""
    try:
        result = subprocess.run(command, shell=True, capture_output=True, text=True)
        return result.stdout, result.stderr, result.returncode
    except Exception as e:
        print(f"Error running command: {e}")
        return "", str(e), 1

def check_device_connection():
    """Check if Android device is connected"""
    print("🔍 Checking device connection...")
    stdout, stderr, returncode = run_adb_command("adb devices")
    
    if returncode != 0:
        print("❌ ADB not found or not working")
        return False
    
    lines = stdout.strip().split('\n')[1:]  # Skip header
    connected_devices = [line for line in lines if 'device' in line and 'offline' not in line]
    
    if not connected_devices:
        print("❌ No Android device connected")
        print("Please connect your Android device and enable USB debugging")
        return False
    
    print(f"✅ Device connected: {connected_devices[0]}")
    return True

def install_debug_apk():
    """Install the debug APK"""
    print("📱 Installing debug APK...")
    apk_path = "app/build/outputs/apk/debug/app-debug.apk"
    
    if not os.path.exists(apk_path):
        print(f"❌ APK not found at {apk_path}")
        print("Please build the app first: ./gradlew assembleDebug")
        return False
    
    stdout, stderr, returncode = run_adb_command(f"adb install -r {apk_path}")
    
    if returncode != 0:
        print(f"❌ Failed to install APK: {stderr}")
        return False
    
    print("✅ APK installed successfully")
    return True

def clear_logs():
    """Clear existing logcat logs"""
    print("🧹 Clearing existing logs...")
    run_adb_command("adb logcat -c")

def start_app():
    """Start the MultiModal Capture app"""
    print("🚀 Starting MultiModal Capture app...")
    stdout, stderr, returncode = run_adb_command(
        "adb shell am start -n com.multimodal.capture.debug/com.multimodal.capture.MainActivity"
    )
    
    if returncode != 0:
        print(f"❌ Failed to start app: {stderr}")
        return False
    
    print("✅ App started successfully")
    return True

def monitor_thermal_logs():
    """Monitor logs for thermal camera debug information"""
    print("📊 Monitoring thermal camera logs...")
    print("=" * 80)
    print("🔥 THERMAL CAMERA DEBUG LOGS")
    print("=" * 80)
    print("Looking for [DEBUG_LOG] entries related to thermal camera...")
    print("Press Ctrl+C to stop monitoring")
    print("-" * 80)
    
    try:
        # Monitor logs with filter for our debug messages
        process = subprocess.Popen(
            ["adb", "logcat", "-s", "ThermalCameraManager", "MainActivity", "MainViewModel"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            universal_newlines=True
        )
        
        for line in iter(process.stdout.readline, ''):
            if line.strip():
                # Highlight debug logs
                if "[DEBUG_LOG]" in line:
                    print(f"🔍 {line.strip()}")
                elif "ERROR" in line.upper() or "EXCEPTION" in line.upper():
                    print(f"❌ {line.strip()}")
                elif "WARN" in line.upper():
                    print(f"⚠️  {line.strip()}")
                else:
                    print(f"ℹ️  {line.strip()}")
                    
    except KeyboardInterrupt:
        print("\n" + "=" * 80)
        print("🛑 Log monitoring stopped")
        process.terminate()

def analyze_usb_devices():
    """Check USB devices connected to Android device"""
    print("🔌 Analyzing USB devices...")
    
    # Check if thermal camera is detected by the system
    stdout, stderr, returncode = run_adb_command("adb shell lsusb")
    
    if returncode == 0 and stdout.strip():
        print("📋 USB devices detected:")
        for line in stdout.strip().split('\n'):
            if line.strip():
                print(f"   {line.strip()}")
                # Check for Topdon vendor IDs
                if "1f3a" in line.lower() or "3538" in line.lower():
                    print("   🔥 ↑ Potential Topdon thermal camera detected!")
    else:
        print("ℹ️  lsusb not available or no USB devices found")
    
    # Check USB permissions
    print("\n🔐 Checking USB permissions...")
    stdout, stderr, returncode = run_adb_command(
        "adb shell dumpsys usb | grep -A 10 -B 5 'mDeviceList'"
    )
    
    if returncode == 0 and stdout.strip():
        print("📋 USB device permissions:")
        print(stdout.strip())

def main():
    """Main debug function"""
    print("🔥 THERMAL CAMERA DEBUG TOOL")
    print("=" * 50)
    
    # Check if we're in the right directory
    if not os.path.exists("app/build.gradle"):
        print("❌ Please run this script from the project root directory")
        sys.exit(1)
    
    # Step 1: Check device connection
    if not check_device_connection():
        sys.exit(1)
    
    # Step 2: Install debug APK
    if not install_debug_apk():
        sys.exit(1)
    
    # Step 3: Analyze USB devices
    analyze_usb_devices()
    
    # Step 4: Clear logs and start app
    clear_logs()
    time.sleep(1)
    
    if not start_app():
        sys.exit(1)
    
    # Step 5: Wait a moment for app to initialize
    print("⏳ Waiting for app to initialize...")
    time.sleep(3)
    
    # Step 6: Monitor logs
    monitor_thermal_logs()

if __name__ == "__main__":
    main()