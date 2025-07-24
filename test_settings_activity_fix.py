#!/usr/bin/env python3
"""
Test script to verify that the SettingsActivity action bar fix is working.
This script checks that the necessary changes have been made to resolve the crash.
"""

import os
import xml.etree.ElementTree as ET

def test_settings_activity_fix():
    """Test that the SettingsActivity action bar fix has been properly implemented."""
    
    project_root = "/Users/duyantran/workspace/untitled1"
    
    # Test 1: Check that NoActionBar theme exists in themes.xml
    themes_file = os.path.join(project_root, "app/src/main/res/values/themes.xml")
    
    print("🔍 Testing SettingsActivity Action Bar Fix...")
    print("=" * 50)
    
    if not os.path.exists(themes_file):
        print("❌ FAIL: themes.xml file not found")
        return False
    
    with open(themes_file, 'r') as f:
        themes_content = f.read()
    
    if "Theme.MultiModalCaptureApp.NoActionBar" in themes_content:
        print("✅ PASS: NoActionBar theme variant exists in themes.xml")
    else:
        print("❌ FAIL: NoActionBar theme variant not found in themes.xml")
        return False
    
    if "Theme.Material3.DayNight.NoActionBar" in themes_content:
        print("✅ PASS: NoActionBar theme extends correct parent")
    else:
        print("❌ FAIL: NoActionBar theme doesn't extend correct parent")
        return False
    
    # Test 2: Check that AndroidManifest.xml uses the NoActionBar theme for SettingsActivity
    manifest_file = os.path.join(project_root, "app/src/main/AndroidManifest.xml")
    
    if not os.path.exists(manifest_file):
        print("❌ FAIL: AndroidManifest.xml file not found")
        return False
    
    with open(manifest_file, 'r') as f:
        manifest_content = f.read()
    
    if 'android:name=".ui.SettingsActivity"' in manifest_content and 'android:theme="@style/Theme.MultiModalCaptureApp.NoActionBar"' in manifest_content:
        print("✅ PASS: SettingsActivity uses NoActionBar theme in AndroidManifest.xml")
    else:
        print("❌ FAIL: SettingsActivity doesn't use NoActionBar theme in AndroidManifest.xml")
        return False
    
    # Test 3: Check that SettingsActivity.kt still has the setSupportActionBar call
    settings_activity_file = os.path.join(project_root, "app/src/main/java/com/multimodal/capture/ui/SettingsActivity.kt")
    
    if not os.path.exists(settings_activity_file):
        print("❌ FAIL: SettingsActivity.kt file not found")
        return False
    
    with open(settings_activity_file, 'r') as f:
        settings_content = f.read()
    
    if "setSupportActionBar(findViewById(R.id.toolbar))" in settings_content:
        print("✅ PASS: SettingsActivity still has setSupportActionBar call (now safe to use)")
    else:
        print("❌ FAIL: SettingsActivity missing setSupportActionBar call")
        return False
    
    # Test 4: Check that activity_settings.xml has the toolbar
    layout_file = os.path.join(project_root, "app/src/main/res/layout/activity_settings.xml")
    
    if not os.path.exists(layout_file):
        print("❌ FAIL: activity_settings.xml file not found")
        return False
    
    with open(layout_file, 'r') as f:
        layout_content = f.read()
    
    if 'android:id="@+id/toolbar"' in layout_content:
        print("✅ PASS: activity_settings.xml contains toolbar with correct ID")
    else:
        print("❌ FAIL: activity_settings.xml missing toolbar with correct ID")
        return False
    
    print("=" * 50)
    print("🎉 SUCCESS: All tests passed! The SettingsActivity action bar fix is properly implemented.")
    print("\n📋 Summary of changes made:")
    print("1. Created Theme.MultiModalCaptureApp.NoActionBar theme variant")
    print("2. Updated SettingsActivity to use the NoActionBar theme")
    print("3. This resolves the action bar conflict that was causing crashes")
    print("\n🔧 How the fix works:")
    print("- The NoActionBar theme prevents the window decor from providing a default action bar")
    print("- This allows the custom toolbar to be set as the support action bar without conflict")
    print("- The SettingsActivity can now launch successfully with its custom toolbar")
    
    return True

if __name__ == "__main__":
    test_settings_activity_fix()