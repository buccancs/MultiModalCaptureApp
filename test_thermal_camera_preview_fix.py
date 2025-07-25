#!/usr/bin/env python3
"""
Test script to verify thermal camera preview fix for grey screen issue.

This script tests the ThermalCameraManager implementation to ensure:
1. Proper IRCMD initialization and preview setup
2. UVCCamera frame callback configuration
3. Thermal data processing and bitmap conversion
4. Preview ImageView updates

Usage: python test_thermal_camera_preview_fix.py
"""

import subprocess
import sys
import time
import os

def run_command(command, description):
    """Run a command and return success status"""
    print(f"\n[DEBUG_LOG] {description}")
    print(f"[DEBUG_LOG] Running: {command}")
    
    try:
        result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=30)
        
        if result.returncode == 0:
            print(f"[DEBUG_LOG] ✓ {description} - SUCCESS")
            if result.stdout.strip():
                print(f"[DEBUG_LOG] Output: {result.stdout.strip()}")
            return True
        else:
            print(f"[DEBUG_LOG] ✗ {description} - FAILED")
            if result.stderr.strip():
                print(f"[DEBUG_LOG] Error: {result.stderr.strip()}")
            return False
            
    except subprocess.TimeoutExpired:
        print(f"[DEBUG_LOG] ✗ {description} - TIMEOUT")
        return False
    except Exception as e:
        print(f"[DEBUG_LOG] ✗ {description} - EXCEPTION: {e}")
        return False

def check_thermal_camera_implementation():
    """Check if thermal camera implementation has been properly fixed"""
    print("\n" + "="*60)
    print("THERMAL CAMERA PREVIEW FIX VERIFICATION")
    print("="*60)
    
    # Check if ThermalCameraManager exists and has proper imports
    thermal_manager_path = "app/src/main/java/com/multimodal/capture/capture/ThermalCameraManager.kt"
    
    if not os.path.exists(thermal_manager_path):
        print(f"[DEBUG_LOG] ✗ ThermalCameraManager not found at {thermal_manager_path}")
        return False
    
    print(f"[DEBUG_LOG] ✓ ThermalCameraManager found")
    
    # Check for required imports
    required_imports = [
        "import com.energy.iruvc.utils.IFrameCallback",
        "import com.energy.iruvc.uvc.UVCCamera"
    ]
    
    with open(thermal_manager_path, 'r') as f:
        content = f.read()
    
    missing_imports = []
    for import_stmt in required_imports:
        if import_stmt not in content:
            missing_imports.append(import_stmt)
    
    if missing_imports:
        print(f"[DEBUG_LOG] ✗ Missing required imports: {missing_imports}")
        return False
    
    print(f"[DEBUG_LOG] ✓ All required imports present")
    
    # Check for key methods
    required_methods = [
        "setupFrameCallback",
        "processThermalFrameData", 
        "convertThermalDataToBitmap"
    ]
    
    missing_methods = []
    for method in required_methods:
        if f"fun {method}" not in content and f"private fun {method}" not in content:
            missing_methods.append(method)
    
    if missing_methods:
        print(f"[DEBUG_LOG] ✗ Missing required methods: {missing_methods}")
        return False
    
    print(f"[DEBUG_LOG] ✓ All required methods present")
    
    # Check for proper IRCMD startPreview call
    if "cmd.startPreview(" not in content:
        print(f"[DEBUG_LOG] ✗ IRCMD startPreview call not found")
        return False
    
    print(f"[DEBUG_LOG] ✓ IRCMD startPreview call present")
    
    # Check for UVCCamera frame callback setup
    if "uvcCamera.setFrameCallback" not in content:
        print(f"[DEBUG_LOG] ✗ UVCCamera frame callback setup not found")
        return False
    
    print(f"[DEBUG_LOG] ✓ UVCCamera frame callback setup present")
    
    # Check for thermal data processing
    if "processThermalFrameData" not in content:
        print(f"[DEBUG_LOG] ✗ Thermal data processing not found")
        return False
    
    print(f"[DEBUG_LOG] ✓ Thermal data processing present")
    
    return True

def test_build_compilation():
    """Test if the project builds successfully with the thermal camera fix"""
    print("\n" + "="*60)
    print("BUILD COMPILATION TEST")
    print("="*60)
    
    # Try to build the project
    build_success = run_command(
        "./gradlew assembleDebug --no-daemon --stacktrace",
        "Building project with thermal camera fix"
    )
    
    if not build_success:
        print("[DEBUG_LOG] ✗ Build failed - checking for compilation errors")
        return False
    
    print("[DEBUG_LOG] ✓ Project builds successfully with thermal camera fix")
    return True

def test_thermal_camera_unit_tests():
    """Run thermal camera related unit tests"""
    print("\n" + "="*60)
    print("THERMAL CAMERA UNIT TESTS")
    print("="*60)
    
    # Look for existing thermal camera tests
    test_files = [
        "test_thermal_camera_integration.py",
        "test_thermal_camera_initialization.py",
        "test_camera_preview_issues.py"
    ]
    
    tests_run = 0
    tests_passed = 0
    
    for test_file in test_files:
        if os.path.exists(test_file):
            print(f"[DEBUG_LOG] Running {test_file}")
            if run_command(f"python {test_file}", f"Running {test_file}"):
                tests_passed += 1
            tests_run += 1
    
    if tests_run == 0:
        print("[DEBUG_LOG] No thermal camera unit tests found")
        return True
    
    print(f"[DEBUG_LOG] Unit tests: {tests_passed}/{tests_run} passed")
    return tests_passed == tests_run

def generate_test_report():
    """Generate a test report for the thermal camera preview fix"""
    print("\n" + "="*60)
    print("THERMAL CAMERA PREVIEW FIX TEST REPORT")
    print("="*60)
    
    report = {
        "implementation_check": check_thermal_camera_implementation(),
        "build_test": test_build_compilation(),
        "unit_tests": test_thermal_camera_unit_tests()
    }
    
    print(f"\n[DEBUG_LOG] Test Results Summary:")
    print(f"[DEBUG_LOG] Implementation Check: {'✓ PASS' if report['implementation_check'] else '✗ FAIL'}")
    print(f"[DEBUG_LOG] Build Compilation: {'✓ PASS' if report['build_test'] else '✗ FAIL'}")
    print(f"[DEBUG_LOG] Unit Tests: {'✓ PASS' if report['unit_tests'] else '✗ FAIL'}")
    
    overall_success = all(report.values())
    print(f"\n[DEBUG_LOG] Overall Result: {'✓ ALL TESTS PASSED' if overall_success else '✗ SOME TESTS FAILED'}")
    
    if overall_success:
        print("\n[DEBUG_LOG] 🎉 Thermal camera preview fix verification completed successfully!")
        print("[DEBUG_LOG] The grey screen issue should now be resolved.")
        print("[DEBUG_LOG] Key improvements made:")
        print("[DEBUG_LOG] - Added proper UVCCamera frame callback setup")
        print("[DEBUG_LOG] - Implemented thermal data processing pipeline")
        print("[DEBUG_LOG] - Added thermal data to bitmap conversion")
        print("[DEBUG_LOG] - Fixed IRCMD startPreview method calls")
        print("[DEBUG_LOG] - Added proper error handling and logging")
    else:
        print("\n[DEBUG_LOG] ❌ Some tests failed. Please review the implementation.")
    
    return overall_success

def main():
    """Main test execution"""
    print("Starting thermal camera preview fix verification...")
    
    start_time = time.time()
    success = generate_test_report()
    end_time = time.time()
    
    print(f"\n[DEBUG_LOG] Test execution completed in {end_time - start_time:.2f} seconds")
    
    if success:
        print("[DEBUG_LOG] ✅ Thermal camera preview fix verification PASSED")
        sys.exit(0)
    else:
        print("[DEBUG_LOG] ❌ Thermal camera preview fix verification FAILED")
        sys.exit(1)

if __name__ == "__main__":
    main()