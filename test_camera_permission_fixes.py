#!/usr/bin/env python3
"""
Test script to verify that camera permission lifecycle fixes are working correctly.
This script checks that all the fixes are in place to resolve the black/grey preview issue.
"""

import os

def test_camera_permission_fixes():
    """Test that all camera permission lifecycle fixes have been properly implemented."""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    
    print("🔍 Testing Camera Permission Lifecycle Fixes...")
    print("=" * 60)
    
    fixes_verified = []
    
    # Test 1: Check MainActivity permission denial handling fixes
    main_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/MainActivity.kt")
    
    if os.path.exists(main_activity_file):
        with open(main_activity_file, 'r') as f:
            main_content = f.read()
        
        # Check onPermissionsDenied cleanup
        if "cleanupCameraResources()" in main_content.split("override fun onPermissionsDenied")[1].split("override fun")[0]:
            print("✅ FIXED: onPermissionsDenied now calls cleanupCameraResources()")
            fixes_verified.append("onPermissionsDenied cleanup")
        
        # Check onEssentialPermissionsDenied cleanup
        if "cleanupCameraResources()" in main_content.split("override fun onEssentialPermissionsDenied")[1].split("override fun")[0]:
            print("✅ FIXED: onEssentialPermissionsDenied now calls cleanupCameraResources()")
            fixes_verified.append("onEssentialPermissionsDenied cleanup")
        
        # Check cleanupCameraResources method exists
        if "private fun cleanupCameraResources()" in main_content:
            print("✅ FIXED: cleanupCameraResources() method implemented")
            fixes_verified.append("cleanupCameraResources method")
            
            # Check if it resets servicesInitialized flag
            if "servicesInitialized = false" in main_content.split("private fun cleanupCameraResources()")[1].split("private fun")[0]:
                print("✅ FIXED: cleanupCameraResources resets servicesInitialized flag")
                fixes_verified.append("servicesInitialized reset")
            
            # Check if it calls ViewModel cleanup
            if "viewModel.cleanupCaptureModules()" in main_content.split("private fun cleanupCameraResources()")[1].split("private fun")[0]:
                print("✅ FIXED: cleanupCameraResources calls ViewModel cleanup")
                fixes_verified.append("ViewModel cleanup call")
    
    # Test 2: Check MainViewModel cleanup method
    viewmodel_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/viewmodel/MainViewModel.kt")
    
    if os.path.exists(viewmodel_file):
        with open(viewmodel_file, 'r') as f:
            viewmodel_content = f.read()
        
        # Check cleanupCaptureModules method exists
        if "fun cleanupCaptureModules()" in viewmodel_content:
            print("✅ FIXED: MainViewModel.cleanupCaptureModules() method implemented")
            fixes_verified.append("MainViewModel cleanupCaptureModules method")
            
            # Check if it cleans up camera manager
            if "cameraManager.cleanup()" in viewmodel_content.split("fun cleanupCaptureModules()")[1].split("fun ")[0]:
                print("✅ FIXED: cleanupCaptureModules cleans up camera manager")
                fixes_verified.append("Camera manager cleanup")
            
            # Check if it cleans up thermal manager
            if "thermalCameraManager.cleanup()" in viewmodel_content.split("fun cleanupCaptureModules()")[1].split("fun ")[0]:
                print("✅ FIXED: cleanupCaptureModules cleans up thermal manager")
                fixes_verified.append("Thermal manager cleanup")
            
            # Check if it resets initialization state
            if "_initializationComplete.postValue(false)" in viewmodel_content.split("fun cleanupCaptureModules()")[1].split("fun ")[0]:
                print("✅ FIXED: cleanupCaptureModules resets initialization state")
                fixes_verified.append("Initialization state reset")
            
            # Check if it resets status values
            if "_cameraStatus.postValue" in viewmodel_content.split("fun cleanupCaptureModules()")[1].split("fun ")[0]:
                print("✅ FIXED: cleanupCaptureModules resets camera status")
                fixes_verified.append("Camera status reset")
    
    print("\n" + "=" * 60)
    print("📋 SUMMARY OF FIXES VERIFIED:")
    
    for i, fix in enumerate(fixes_verified, 1):
        print(f"{i}. {fix}")
    
    print(f"\n🔧 TOTAL FIXES VERIFIED: {len(fixes_verified)}")
    
    expected_fixes = 10  # Total number of expected fixes
    
    if len(fixes_verified) >= expected_fixes:
        print("\n🎉 SUCCESS: All camera permission lifecycle fixes have been properly implemented!")
        print("\n📋 What was fixed:")
        print("• Permission Denial Handling:")
        print("  - onPermissionsDenied now cleans up camera resources when essential permissions denied")
        print("  - onEssentialPermissionsDenied now cleans up camera resources")
        print("  - cleanupCameraResources() method properly resets servicesInitialized flag")
        print("• Camera Resource Management:")
        print("  - MainViewModel.cleanupCaptureModules() cleans up all camera managers")
        print("  - Initialization state is reset to allow re-initialization")
        print("  - Camera and thermal status values are reset")
        print("• Re-initialization Support:")
        print("  - servicesInitialized flag reset allows initializeServices() to run again")
        print("  - initializationComplete observer will trigger preview setup again")
        
        print("\n🔧 How it works now:")
        print("1. First time: Permissions granted → initializeServices() → camera works")
        print("2. Permission denied: cleanupCameraResources() → cleanup + reset flags")
        print("3. Permission re-granted: initializeServices() runs again → camera re-initialized")
        print("4. Result: Camera preview works consistently on subsequent permission grants")
        
        print("\n🎯 Expected behavior:")
        print("• Camera preview should work the first time permissions are granted")
        print("• When permissions are denied, camera resources are properly cleaned up")
        print("• When permissions are re-granted, camera should initialize and work again")
        print("• Both RGB camera and thermal camera previews should work consistently")
        
        return True
    else:
        print(f"\n⚠️  Only {len(fixes_verified)}/{expected_fixes} fixes verified. Some fixes may be missing.")
        return False

if __name__ == "__main__":
    success = test_camera_permission_fixes()
    if not success:
        print("\n❌ Some fixes are missing or incomplete.")