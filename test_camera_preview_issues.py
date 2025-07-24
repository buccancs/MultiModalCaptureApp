#!/usr/bin/env python3
"""
Test script to identify and document camera preview issues.
This script analyzes the codebase to identify why camera and thermal previews are not working.
"""

import os
import re

def test_camera_preview_issues():
    """Test and document camera preview issues."""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    
    print("🔍 Testing Camera Preview Issues...")
    print("=" * 60)
    
    issues_found = []
    
    # Test 1: Check CameraManager LifecycleOwner issue
    camera_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/CameraManager.kt")
    
    if os.path.exists(camera_manager_file):
        with open(camera_manager_file, 'r') as f:
            camera_content = f.read()
        
        if "context as androidx.lifecycle.LifecycleOwner" in camera_content:
            print("❌ CRITICAL ISSUE: CameraManager tries to cast Context to LifecycleOwner")
            print("   Location: CameraManager.setupCamera() method")
            print("   Problem: Application context cannot be cast to LifecycleOwner")
            print("   Impact: Camera preview setup will fail with ClassCastException")
            issues_found.append("CameraManager LifecycleOwner casting issue")
        
        if "bindToLifecycle(" in camera_content:
            print("✅ FOUND: CameraX bindToLifecycle() usage")
            print("   Note: This requires proper LifecycleOwner, not Application context")
    
    # Test 2: Check initialization order issue
    main_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/MainActivity.kt")
    
    if os.path.exists(main_activity_file):
        with open(main_activity_file, 'r') as f:
            main_content = f.read()
        
        # Check if setupCameraPreview is called before initializeCaptureModules
        setup_preview_match = re.search(r'setupCameraPreview\(\)', main_content)
        init_modules_match = re.search(r'initializeCaptureModules\(\)', main_content)
        
        if setup_preview_match and init_modules_match:
            if setup_preview_match.start() < init_modules_match.start():
                print("❌ TIMING ISSUE: setupCameraPreview() called before initializeCaptureModules()")
                print("   Problem: CameraManager not initialized when preview setup is attempted")
                print("   Impact: Preview setup will be skipped silently")
                issues_found.append("Camera preview initialization timing issue")
    
    # Test 3: Check MainViewModel preview setup
    viewmodel_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/viewmodel/MainViewModel.kt")
    
    if os.path.exists(viewmodel_file):
        with open(viewmodel_file, 'r') as f:
            viewmodel_content = f.read()
        
        if "if (::cameraManager.isInitialized)" in viewmodel_content:
            print("✅ FOUND: MainViewModel checks if cameraManager is initialized")
            print("   Note: This check prevents crashes but doesn't fix the timing issue")
        
        if "getPreviewSurface()?.setSurfaceProvider" in viewmodel_content:
            print("✅ FOUND: Preview surface provider setup in MainViewModel")
    
    # Test 4: Check thermal camera preview issues
    thermal_manager_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/capture/ThermalCameraManager.kt")
    
    if os.path.exists(thermal_manager_file):
        with open(thermal_manager_file, 'r') as f:
            thermal_content = f.read()
        
        if "startThermalPreview()" in thermal_content:
            print("✅ FOUND: ThermalCameraManager has startThermalPreview() method")
        
        if "getPreviewSurface" not in thermal_content and "setPreviewImageView" not in thermal_content:
            print("❌ MISSING: ThermalCameraManager has no UI integration methods")
            print("   Problem: No way to connect thermal frames to ImageView")
            print("   Impact: Thermal preview frames are processed but never displayed")
            issues_found.append("Thermal camera UI integration missing")
        
        if "processIncomingFrame" in thermal_content:
            print("✅ FOUND: Thermal frame processing exists")
            print("   Note: Frames are processed but not connected to UI")
    
    # Test 5: Check layout preview views
    layout_file = os.path.join(project_root, "app/src/main/res/layout/activity_main.xml")
    
    if os.path.exists(layout_file):
        with open(layout_file, 'r') as f:
            layout_content = f.read()
        
        if 'android:id="@+id/camera_preview"' in layout_content:
            print("✅ FOUND: RGB camera PreviewView in layout")
        
        if 'android:id="@+id/thermal_preview"' in layout_content:
            print("✅ FOUND: Thermal camera ImageView in layout")
            if "<ImageView" in layout_content and "thermal_preview" in layout_content:
                print("   Note: Thermal preview is just a placeholder ImageView")
    
    print("\n" + "=" * 60)
    print("📋 SUMMARY OF ISSUES FOUND:")
    
    for i, issue in enumerate(issues_found, 1):
        print(f"{i}. {issue}")
    
    print(f"\n🔧 TOTAL ISSUES: {len(issues_found)}")
    
    if len(issues_found) > 0:
        print("\n💡 RECOMMENDED FIXES:")
        print("1. Fix CameraManager to accept LifecycleOwner instead of casting Context")
        print("2. Fix initialization order: call setupCameraPreview() after initializeCaptureModules()")
        print("3. Add thermal camera UI integration methods")
        print("4. Connect thermal frame processing to ImageView updates")
        print("5. Ensure proper error handling for preview setup failures")
    
    print("\n🎯 ROOT CAUSES:")
    print("• Camera preview: LifecycleOwner casting issue + initialization timing")
    print("• Thermal preview: Complete lack of UI integration")
    print("• Both: No proper error handling or user feedback for preview failures")
    
    return len(issues_found) == 0

if __name__ == "__main__":
    success = test_camera_preview_issues()
    if success:
        print("\n🎉 No issues found!")
    else:
        print("\n⚠️  Issues found that need to be fixed.")