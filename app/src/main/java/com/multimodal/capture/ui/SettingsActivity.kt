package com.multimodal.capture.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.multimodal.capture.R

/**
 * SettingsActivity provides configuration options for the multi-modal capture system.
 * Allows users to customize recording parameters, quality settings, and app behavior.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Setup toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        // Load settings fragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}