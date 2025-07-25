#!/usr/bin/env python3

"""
Test script to verify session file viewing functionality implementation.
Tests the enhanced SessionFolderActivity with file browsing capabilities.
"""

import os
import sys
import subprocess
import re
from pathlib import Path

def test_session_file_viewing_implementation():
    """Test the session file viewing functionality implementation"""
    
    print("🔍 Testing Session File Viewing Implementation")
    print("=" * 60)
    
    project_root = Path(__file__).parent
    issues_found = []
    
    # Test 1: Verify SessionFileAdapter implementation
    print("\n📱 Test 1: SessionFileAdapter Implementation")
    adapter_file = project_root / "app/src/main/java/com/multimodal/capture/ui/adapter/SessionFileAdapter.kt"
    
    if adapter_file.exists():
        content = adapter_file.read_text()
        
        # Check for essential components
        required_components = [
            "class SessionFileAdapter",
            "onFileClick: (SessionFile) -> Unit",
            "onFileShare: (SessionFile) -> Unit", 
            "onFileDelete: (SessionFile) -> Unit",
            "updateFiles(newFiles: List<SessionFile>)",
            "SessionFileDiffCallback"
        ]
        
        missing_components = []
        for component in required_components:
            if component not in content:
                missing_components.append(component)
        
        if missing_components:
            print(f"❌ ISSUE: Missing components in SessionFileAdapter: {missing_components}")
            issues_found.append("SessionFileAdapter missing components")
        else:
            print("✅ SessionFileAdapter properly implemented with all required components")
    else:
        print("❌ ISSUE: SessionFileAdapter.kt not found")
        issues_found.append("SessionFileAdapter missing")
    
    # Test 2: Verify file type icons
    print("\n🎨 Test 2: File Type Icons")
    drawable_dir = project_root / "app/src/main/res/drawable"
    
    required_icons = [
        "ic_audio_file.xml",
        "ic_video_file.xml", 
        "ic_data_file.xml",
        "ic_thermal_file.xml",
        "ic_json_file.xml",
        "ic_unknown_file.xml",
        "ic_share.xml",
        "ic_delete.xml"
    ]
    
    missing_icons = []
    for icon in required_icons:
        icon_path = drawable_dir / icon
        if not icon_path.exists():
            missing_icons.append(icon)
    
    if missing_icons:
        print(f"❌ ISSUE: Missing drawable icons: {missing_icons}")
        issues_found.append("Missing file type icons")
    else:
        print("✅ All required file type icons are present")
    
    # Test 3: Verify SessionFolderActivity enhancements
    print("\n🏗️ Test 3: SessionFolderActivity File Viewing")
    activity_file = project_root / "app/src/main/java/com/multimodal/capture/ui/SessionFolderActivity.kt"
    
    if activity_file.exists():
        content = activity_file.read_text()
        
        # Check for file viewing functionality
        required_methods = [
            "showSessionFiles(sessionFolder: SessionFolder)",
            "openFile(sessionFile: SessionFile)",
            "shareFile(sessionFile: SessionFile)", 
            "deleteFile(sessionFile: SessionFile",
            "getMimeType(sessionFile: SessionFile)"
        ]
        
        missing_methods = []
        for method in required_methods:
            if method not in content:
                missing_methods.append(method)
        
        if missing_methods:
            print(f"❌ ISSUE: Missing methods in SessionFolderActivity: {missing_methods}")
            issues_found.append("SessionFolderActivity missing file viewing methods")
        else:
            print("✅ SessionFolderActivity properly enhanced with file viewing capabilities")
            
        # Check for proper imports
        required_imports = [
            "import com.multimodal.capture.data.SessionFile",
            "import com.multimodal.capture.data.SessionFileType",
            "import com.multimodal.capture.ui.adapter.SessionFileAdapter"
        ]
        
        missing_imports = []
        for import_stmt in required_imports:
            if import_stmt not in content:
                missing_imports.append(import_stmt)
        
        if missing_imports:
            print(f"❌ ISSUE: Missing imports in SessionFolderActivity: {missing_imports}")
            issues_found.append("SessionFolderActivity missing imports")
        else:
            print("✅ All required imports are present in SessionFolderActivity")
    else:
        print("❌ ISSUE: SessionFolderActivity.kt not found")
        issues_found.append("SessionFolderActivity missing")
    
    # Test 4: Verify item layout for files
    print("\n📋 Test 4: Session File Item Layout")
    layout_file = project_root / "app/src/main/res/layout/item_session_file.xml"
    
    if layout_file.exists():
        content = layout_file.read_text()
        
        # Check for essential UI components
        required_elements = [
            'android:id="@+id/iv_file_icon"',
            'android:id="@+id/tv_file_name"',
            'android:id="@+id/tv_file_type"',
            'android:id="@+id/tv_file_size"',
            'android:id="@+id/btn_share"',
            'android:id="@+id/btn_delete"'
        ]
        
        missing_elements = []
        for element in required_elements:
            if element not in content:
                missing_elements.append(element)
        
        if missing_elements:
            print(f"❌ ISSUE: Missing UI elements in item_session_file.xml: {missing_elements}")
            issues_found.append("Session file item layout incomplete")
        else:
            print("✅ Session file item layout properly implemented with all required elements")
    else:
        print("❌ ISSUE: item_session_file.xml not found")
        issues_found.append("Session file item layout missing")
    
    # Test 5: Verify FileProvider configuration
    print("\n🔒 Test 5: FileProvider Configuration")
    manifest_file = project_root / "app/src/main/AndroidManifest.xml"
    file_paths_file = project_root / "app/src/main/res/xml/file_paths.xml"
    
    if manifest_file.exists():
        manifest_content = manifest_file.read_text()
        if 'androidx.core.content.FileProvider' in manifest_content and 'fileprovider' in manifest_content:
            print("✅ FileProvider properly configured in AndroidManifest.xml")
        else:
            print("❌ ISSUE: FileProvider not properly configured in AndroidManifest.xml")
            issues_found.append("FileProvider configuration missing")
    
    if file_paths_file.exists():
        print("✅ file_paths.xml configuration file exists")
    else:
        print("❌ ISSUE: file_paths.xml configuration file missing")
        issues_found.append("FileProvider paths configuration missing")
    
    # Test 6: Build verification
    print("\n🔨 Test 6: Build Verification")
    try:
        result = subprocess.run(
            ["./gradlew", "assembleDebug", "--quiet"],
            cwd=project_root,
            capture_output=True,
            text=True,
            timeout=120
        )
        
        if result.returncode == 0:
            print("✅ Project builds successfully with file viewing functionality")
        else:
            print(f"❌ ISSUE: Build failed - {result.stderr}")
            issues_found.append("Build failure")
    except subprocess.TimeoutExpired:
        print("❌ ISSUE: Build timed out")
        issues_found.append("Build timeout")
    except Exception as e:
        print(f"❌ ISSUE: Build error - {e}")
        issues_found.append("Build error")
    
    # Summary
    print("\n" + "=" * 60)
    print("📊 SESSION FILE VIEWING TEST SUMMARY")
    print("=" * 60)
    
    if not issues_found:
        print("🎉 SUCCESS: All session file viewing functionality tests passed!")
        print("✅ SessionFileAdapter properly implemented")
        print("✅ File type icons created")
        print("✅ SessionFolderActivity enhanced with file viewing")
        print("✅ File item layout implemented")
        print("✅ FileProvider security configured")
        print("✅ Project builds successfully")
        return True
    else:
        print(f"❌ ISSUES FOUND: {len(issues_found)} problems detected")
        for i, issue in enumerate(issues_found, 1):
            print(f"   {i}. {issue}")
        return False

if __name__ == "__main__":
    success = test_session_file_viewing_implementation()
    sys.exit(0 if success else 1)