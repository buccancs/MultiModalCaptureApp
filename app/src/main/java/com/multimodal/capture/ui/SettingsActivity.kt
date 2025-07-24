package com.multimodal.capture.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButton
import com.multimodal.capture.R
import com.multimodal.capture.utils.SettingsManager

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
        
        // Setup JSON export button
        setupJsonExportButton()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    /**
     * Setup the JSON export button functionality
     */
    private fun setupJsonExportButton() {
        val jsonButton = findViewById<MaterialButton>(R.id.btn_show_json)
        jsonButton.setOnClickListener {
            showSettingsJson()
        }
    }
    
    /**
     * Display the current settings as JSON in a dialog
     */
    private fun showSettingsJson() {
        val settingsManager = SettingsManager.getInstance(this)
        val jsonString = settingsManager.exportConfiguration()
        
        // Format JSON for better readability
        val formattedJson = try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonElement = com.google.gson.JsonParser.parseString(jsonString)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            jsonString // Fallback to original if formatting fails
        }
        
        AlertDialog.Builder(this)
            .setTitle("Settings Parameters JSON")
            .setMessage(formattedJson)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Settings JSON", formattedJson)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "JSON copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }
            .create()
            .apply {
                // Make dialog scrollable for long JSON content
                setOnShowListener {
                    val messageView = findViewById<android.widget.TextView>(android.R.id.message)
                    messageView?.apply {
                        textSize = 12f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setHorizontallyScrolling(true)
                        movementMethod = android.text.method.ScrollingMovementMethod()
                    }
                }
            }
            .show()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}