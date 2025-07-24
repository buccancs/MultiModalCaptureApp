#!/usr/bin/env python3
"""
Test script to analyze camera permission lifecycle issues.
This script identifies the root cause of camera preview working first time but failing on subsequent permission grants.
"""

import os

def test_camera_permission_lifecycle():
    """Test and document camera permission lifecycle issues."""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    
    print("🔍 Testing Camera Permission Lifecycle Issues...")
    print("=" * 60)
    
    issues_found = []
    
    # Test 1: Check MainActivity permission denial handling
    main_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/MainActivity.kt")
    
    if os.path.exists(main_activity_file):
        with open(main_activity_file, 'r') as f:
            main_content = f.read()
        
        # Check onPermissionsDenied implementation
        if "override fun onPermissionsDenied" in main_content:
            print("✅ FOUND: onPermissionsDenied method exists")
            
            # Check if it cleans up resources
            if "cleanup()" not in main_content.split("override fun onPermissionsDenied")[1].split("override fun")[0]:
                print("❌ CRITICAL ISSUE: onPermissionsDenied doesn't clean up camera resources")
                print("   Problem: Camera resources remain in inconsistent state when permissions denied")
                print("   Impact: Subsequent permission grants fail to reinitialize camera properly")
                issues_found.append("No camera cleanup on permission denial")
            
            # Check if it resets servicesInitialized flag
            if "servicesInitialized = false" not in main_content.split("override fun onPermissionsDenied")[1].split("override fun")[0]:
                print("❌ CRITICAL ISSUE: onPermissionsDenied doesn't reset servicesInitialized flag")
                print("   Problem: initializeServices() guard clause prevents re-initialization")
                print("   Impact: Camera modules won't be re-initialized when permissions re-granted")
                issues_found.append("servicesInitialized flag not reset on permission denial")
    
    # Test 2: Check initializeServices guard clause
    if "if (servicesInitialized)" in main_content:
        print("✅ FOUND: initializeServices has guard clause")
        print("   Note: This prevents re-initialization when servicesInitialized=true")
        
        if "return" in main_content.split("if (servicesInitialized)")[1].split("}")[0]:
            print("❌ ISSUE: Guard clause returns early, preventing re-initialization")
            issues_found.append("initializeServices guard clause prevents re-initialization")
    
    # Test 3: Check MainViewModel cleanup timing
    viewmodel_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/viewmodel/MainViewModel.kt")
    
    if os.path.exists(viewmodel_file):
        with open(viewmodel_file, 'r') as f:
            viewmodel_content = f.read()
        
        if "override fun onCleared()" in viewmodel_content:
            print("✅ FOUND: MainViewModel has onCleared() cleanup")
            print("   Note: This only runs when ViewModel is destroyed (Activity destroyed)")
            print("   Problem: No cleanup during permission denial within same Activity session")
            issues_found.append("MainViewModel cleanup only on Activity destruction")
    
    # Test 4: Check camera preview setup dependencies
    if "initializationComplete.observe" in main_content:
        print("✅ FOUND: Camera preview setup depends on initializationComplete observer")
        print("   Note: If initialization doesn't complete, preview setup won't happen")
        
        if "_initializationComplete.postValue(false)" in viewmodel_content:
            print("❌ ISSUE: Initialization can fail but no recovery mechanism")
            issues_found.append("No recovery mechanism for failed initialization")
    
    print("\n" + "=" * 60)
    print("📋 SUMMARY OF LIFECYCLE ISSUES:")
    
    for i, issue in enumerate(issues_found, 1):
        print(f"{i}. {issue}")
    
    print(f"\n🔧 TOTAL ISSUES: {len(issues_found)}")
    
    print("\n🎯 ROOT CAUSE ANALYSIS:")
    print("1. First time: Permissions granted → initializeServices() → camera works")
    print("2. Permission denied: No cleanup, servicesInitialized remains true")
    print("3. Permission re-granted: initializeServices() guard clause returns early")
    print("4. Result: Camera resources in inconsistent state → black/grey preview")
    
    print("\n💡 REQUIRED FIXES:")
    print("1. Add camera resource cleanup in onPermissionsDenied()")
    print("2. Reset servicesInitialized flag when essential permissions denied")
    print("3. Add proper re-initialization mechanism for permission state changes")
    print("4. Ensure camera preview can recover from failed initialization")
    
    return len(issues_found) == 0

if __name__ == "__main__":
    success = test_camera_permission_lifecycle()
    if success:
        print("\n🎉 No lifecycle issues found!")
    else:
        print("\n⚠️  Camera permission lifecycle issues found that need fixing.")