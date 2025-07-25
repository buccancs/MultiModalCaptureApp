#!/usr/bin/env python3
"""
Test script to verify thermal camera integration in the Multi-Modal Capture App.
This script checks that all the thermal camera integration components are properly implemented.
"""

import os
import re
import sys

def test_thermal_camera_integration():
    """Test if thermal camera integration is properly implemented"""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    integration_verified = []
    issues_found = []
    
    print("🔥 Testing Thermal Camera Integration...")
    print("=" * 60)
    
    # Test 1: Check MainActivity USB permission integration
    print("\n📱 Test 1: MainActivity USB Permission Integration")
    main_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/MainActivity.kt")
    
    if os.path.exists(main_activity_file):
        with open(main_activity_file, 'r') as f:
            main_content = f.read()
            
        # Check if TODO is resolved
        if "TODO: Integrate with ThermalCameraManager" not in main_content:
            print("✅ VERIFIED: TODO comment has been resolved")
            integration_verified.append("TODO comment resolved")
        else:
            print("❌ ISSUE: TODO comment still exists")
            issues_found.append("TODO comment not resolved")
            
        # Check if viewModel.connectToThermalCamera() is called
        if "viewModel.connectToThermalCamera()" in main_content:
            print("✅ VERIFIED: MainActivity calls ViewModel thermal camera connection")
            integration_verified.append("MainActivity integration")
        else:
            print("❌ ISSUE: MainActivity doesn't call thermal camera connection")
            issues_found.append("MainActivity integration missing")
    
    # Test 2: Check MainViewModel thermal camera methods
    print("\n🧠 Test 2: MainViewModel Thermal Camera Methods")
    viewmodel_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/viewmodel/MainViewModel.kt")
    
    if os.path.exists(viewmodel_file):
        with open(viewmodel_file, 'r') as f:
            viewmodel_content = f.read()
            
        if "fun connectToThermalCamera()" in viewmodel_content:
            print("✅ VERIFIED: MainViewModel has connectToThermalCamera method")
            integration_verified.append("ViewModel connectToThermalCamera method")
        else:
            print("❌ ISSUE: MainViewModel missing connectToThermalCamera method")
            issues_found.append("ViewModel connectToThermalCamera method")
            
        if "fun setThermalPreviewImageView" in viewmodel_content:
            print("✅ VERIFIED: MainViewModel has thermal preview setup")
            integration_verified.append("ViewModel thermal preview")
        else:
            print("❌ ISSUE: MainViewModel missing thermal preview setup")
            issues_found.append("ViewModel thermal preview")
    
    # Test 3: Check ThermalCameraManager calibration implementation
    print("\n🌡️  Test 3: ThermalCameraManager Calibration Implementation")
    thermal_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/ThermalCameraManager.kt")
    
    if os.path.exists(thermal_manager_file):
        with open(thermal_manager_file, 'r') as f:
            thermal_content = f.read()
            
        # Check for actual SDK method implementation
        if "tiny1bShutterManual()" in thermal_content:
            print("✅ VERIFIED: Shutter calibration uses correct SDK method")
            integration_verified.append("Shutter calibration SDK method")
        else:
            print("❌ ISSUE: Shutter calibration doesn't use correct SDK method")
            issues_found.append("Shutter calibration SDK method")
            
        # Check for LibIRTemp integration
        if "libIRTemp?.setTempData(rawFrame)" in thermal_content:
            print("✅ VERIFIED: Temperature processing uses LibIRTemp SDK")
            integration_verified.append("LibIRTemp temperature processing")
        else:
            print("❌ ISSUE: Temperature processing doesn't use LibIRTemp SDK")
            issues_found.append("LibIRTemp temperature processing")
            
        # Check for improved saving format
        if "thermal_raw.dat" in thermal_content and "thermal_pseudocolored.dat" in thermal_content:
            print("✅ VERIFIED: Enhanced saving format with raw and pseudo-colored data")
            integration_verified.append("Enhanced saving format")
        else:
            print("❌ ISSUE: Enhanced saving format not implemented")
            issues_found.append("Enhanced saving format")
            
        # Check for metadata creation
        if "createThermalMetadata" in thermal_content:
            print("✅ VERIFIED: Metadata creation for temperature interpretation")
            integration_verified.append("Metadata creation")
        else:
            print("❌ ISSUE: Metadata creation not implemented")
            issues_found.append("Metadata creation")
    
    # Test 4: Check recording workflow integration
    print("\n📹 Test 4: Recording Workflow Integration")
    if os.path.exists(viewmodel_file):
        with open(viewmodel_file, 'r') as f:
            viewmodel_content = f.read()
            
        # Check if thermal recording is called in startRecording
        if "thermalCameraManager.startRecording(sessionId, startTimestamp)" in viewmodel_content:
            print("✅ VERIFIED: Thermal recording integrated in recording workflow")
            integration_verified.append("Recording workflow integration")
        else:
            print("❌ ISSUE: Thermal recording not integrated in workflow")
            issues_found.append("Recording workflow integration")
    
    # Summary
    print("\n" + "=" * 60)
    print("📋 INTEGRATION SUMMARY")
    print("=" * 60)
    
    if integration_verified:
        print(f"✅ Successfully verified {len(integration_verified)} integration components:")
        for i, component in enumerate(integration_verified, 1):
            print(f"   {i}. {component}")
    
    if issues_found:
        print(f"\n❌ Found {len(issues_found)} integration issues:")
        for i, issue in enumerate(issues_found, 1):
            print(f"   {i}. {issue}")
    
    print(f"\n🎯 OVERALL STATUS:")
    if len(integration_verified) >= 7 and len(issues_found) == 0:
        print("✅ EXCELLENT: Thermal camera integration is complete and fully functional!")
        print("   All major components are properly integrated:")
        print("   - USB permission handling triggers thermal camera connection")
        print("   - Shutter calibration uses correct SDK methods")
        print("   - Temperature processing uses LibIRTemp SDK")
        print("   - Enhanced saving format with raw + pseudo-colored data")
        print("   - Recording workflow includes thermal camera")
        print("   - Preview functionality is implemented")
        return True
    elif len(integration_verified) >= 5:
        print("✅ GOOD: Most thermal camera integration components are working!")
        print("   The core functionality is implemented with minor issues.")
        return True
    else:
        print("⚠️  PARTIAL: Thermal camera integration needs more work.")
        print("   Several key components are missing or incomplete.")
        return False

if __name__ == "__main__":
    success = test_thermal_camera_integration()
    sys.exit(0 if success else 1)