#!/usr/bin/env python3
"""
Test script to verify device-specific settings capabilities in the Multi-Modal Capture App.
This script checks whether the settings UI properly reflects actual device capabilities
rather than using hardcoded options.
"""

import os
import re
import sys

def test_device_settings_capabilities():
    """Test if device settings reflect actual device capabilities"""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    issues_found = []
    
    print("🔍 Testing Device Settings Capabilities...")
    print("=" * 60)
    
    # Test 1: Check if arrays.xml contains hardcoded resolution options
    print("\n📱 Test 1: Camera Resolution Options")
    arrays_file = os.path.join(project_root, "app/src/main/res/values/arrays.xml")
    
    if os.path.exists(arrays_file):
        with open(arrays_file, 'r') as f:
            arrays_content = f.read()
            
        # Check for hardcoded resolution arrays
        if 'resolution_entries' in arrays_content and '3840x2160' in arrays_content:
            print("❌ ISSUE: Camera resolutions are hardcoded in arrays.xml")
            print("   - Found hardcoded resolutions: 4K, 1080p, 720p, etc.")
            print("   - These may not be supported by all devices")
            issues_found.append("Hardcoded camera resolutions")
        
        # Check for hardcoded camera selection
        if 'camera_entries' in arrays_content and 'Wide Angle Camera' in arrays_content:
            print("❌ ISSUE: Camera selection options are hardcoded")
            print("   - Assumes all devices have wide angle and telephoto cameras")
            issues_found.append("Hardcoded camera selection options")
            
        # Check for hardcoded thermal resolutions
        if 'thermal_resolution_entries' in arrays_content and '384x288' in arrays_content:
            print("❌ ISSUE: Thermal camera resolutions are hardcoded")
            print("   - Assumes all thermal cameras support 384x288, 256x192, etc.")
            issues_found.append("Hardcoded thermal camera resolutions")
    
    # Test 2: Check CameraManager for device capability detection
    print("\n📷 Test 2: CameraManager Device Capability Detection")
    camera_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/CameraManager.kt")
    
    if os.path.exists(camera_manager_file):
        with open(camera_manager_file, 'r') as f:
            camera_content = f.read()
            
        # Check for hardcoded resolution
        if 'setTargetResolution(Size(1920, 1080))' in camera_content:
            print("❌ ISSUE: CameraManager uses hardcoded resolution")
            print("   - Resolution is fixed at 1920x1080 regardless of device capabilities")
            issues_found.append("CameraManager hardcoded resolution")
            
        # Check for hardcoded quality
        if 'QualitySelector.from(Quality.HD)' in camera_content:
            print("❌ ISSUE: CameraManager uses hardcoded video quality")
            print("   - Quality is fixed at HD regardless of device capabilities")
            issues_found.append("CameraManager hardcoded quality")
            
        # Check if it queries available cameras
        if 'CameraManager.getAvailableCameraInfos()' not in camera_content:
            print("❌ ISSUE: CameraManager doesn't query available cameras")
            print("   - Should use Camera2 API to detect actual available cameras")
            issues_found.append("No camera enumeration")
    
    # Test 3: Check ThermalCameraManager for device capability detection
    print("\n🌡️  Test 3: ThermalCameraManager Device Capability Detection")
    thermal_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/ThermalCameraManager.kt")
    
    if os.path.exists(thermal_manager_file):
        with open(thermal_manager_file, 'r') as f:
            thermal_content = f.read()
            
        # Check for hardcoded IR type
        if 'IRCMDType.USB_IR_256_384' in thermal_content:
            print("❌ ISSUE: ThermalCameraManager uses hardcoded IR type")
            print("   - Assumes all thermal cameras are 256x384 resolution")
            issues_found.append("ThermalCameraManager hardcoded IR type")
            
        # Check for hardcoded frame defaults
        if 'frameRate: Float = 30.0f' in thermal_content:
            print("❌ ISSUE: ThermalCameraManager uses hardcoded frame rate")
            print("   - Frame rate is fixed at 30.0 FPS regardless of device capabilities")
            issues_found.append("ThermalCameraManager hardcoded frame rate")
    
    # Test 4: Check GSRSensorManager for device capability detection
    print("\n📊 Test 4: GSRSensorManager Device Capability Detection")
    gsr_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/GSRSensorManager.kt")
    
    if os.path.exists(gsr_manager_file):
        with open(gsr_manager_file, 'r') as f:
            gsr_content = f.read()
            
        # Check for hardcoded sample rate
        if 'targetSampleRate = 128.0' in gsr_content:
            print("❌ ISSUE: GSRSensorManager uses hardcoded sample rate")
            print("   - Sample rate is fixed at 128Hz regardless of device capabilities")
            issues_found.append("GSRSensorManager hardcoded sample rate")
            
        # Check if it's a stub implementation
        if 'stub implementation' in gsr_content.lower():
            print("❌ ISSUE: GSRSensorManager is still a stub implementation")
            print("   - Doesn't query actual Shimmer device capabilities")
            issues_found.append("GSRSensorManager stub implementation")
    
    # Test 5: Check SettingsActivity for dynamic population
    print("\n⚙️  Test 5: SettingsActivity Dynamic Population")
    settings_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/ui/SettingsActivity.kt")
    
    if os.path.exists(settings_activity_file):
        with open(settings_activity_file, 'r') as f:
            settings_content = f.read()
            
        # Check if it dynamically populates preferences
        if 'setPreferencesFromResource(R.xml.preferences, rootKey)' in settings_content:
            if 'populateDeviceCapabilities' not in settings_content:
                print("❌ ISSUE: SettingsActivity doesn't populate device capabilities")
                print("   - Uses static XML preferences without querying device capabilities")
                issues_found.append("No dynamic settings population")
    
    # Summary
    print("\n" + "=" * 60)
    print("📋 SUMMARY")
    print("=" * 60)
    
    if issues_found:
        print(f"❌ Found {len(issues_found)} issues with device settings capabilities:")
        for i, issue in enumerate(issues_found, 1):
            print(f"   {i}. {issue}")
        
        print("\n🔧 RECOMMENDED FIXES:")
        print("   1. Implement dynamic camera resolution detection using Camera2 API")
        print("   2. Query available cameras and populate camera selection dynamically")
        print("   3. Add Topdon SDK capability querying for thermal camera settings")
        print("   4. Implement Shimmer device capability detection for GSR settings")
        print("   5. Update SettingsActivity to populate preferences based on actual device capabilities")
        print("   6. Replace hardcoded arrays with device-specific options")
        
        return False
    else:
        print("✅ All device settings properly reflect actual device capabilities")
        return True

if __name__ == "__main__":
    success = test_device_settings_capabilities()
    sys.exit(0 if success else 1)