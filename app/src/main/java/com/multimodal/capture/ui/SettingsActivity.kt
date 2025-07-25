package com.multimodal.capture.ui

import android.content.Intent
import android.os.Bundle
import android.util.Size
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.camera.video.Quality
import com.google.android.material.button.MaterialButton
import com.multimodal.capture.R
import com.multimodal.capture.utils.SettingsManager
import com.multimodal.capture.utils.DeviceCapabilityDetector
import timber.log.Timber

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
        
        // Setup buttons
        setupJsonExportButton()
        setupSplashPreviewButton()
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
     * Setup the splash preview button functionality
     */
    private fun setupSplashPreviewButton() {
        val splashPreviewButton = findViewById<MaterialButton>(R.id.btn_preview_splash)
        splashPreviewButton.setOnClickListener {
            launchSplashPreview()
        }
    }
    
    /**
     * Launch the splash screen preview activity
     */
    private fun launchSplashPreview() {
        try {
            Timber.d("[DEBUG_LOG] Launching splash screen preview from settings")
            
            val intent = Intent(this, SplashPreviewActivity::class.java)
            startActivity(intent)
            
            // Add smooth transition animation
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to launch splash preview: ${e.message}")
            android.widget.Toast.makeText(this, "Failed to open splash preview", android.widget.Toast.LENGTH_SHORT).show()
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
        
        private lateinit var deviceCapabilityDetector: DeviceCapabilityDetector
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // Initialize device capability detector
            deviceCapabilityDetector = DeviceCapabilityDetector(requireContext())
            
            // Populate device-specific options
            populateDeviceCapabilities()
        }
        
        /**
         * Populate preferences with actual device capabilities instead of hardcoded arrays
         */
        private fun populateDeviceCapabilities() {
            try {
                populateCameraOptions()
                populateThermalCameraOptions()
                populateGSRSensorOptions()
                
                Timber.d("Device capabilities populated successfully")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to populate device capabilities")
            }
        }
        
        /**
         * Populate camera-related preferences with actual device capabilities
         */
        private fun populateCameraOptions() {
            // Populate camera selection options
            val cameraSelectionPref = findPreference<ListPreference>("camera_id")
            if (cameraSelectionPref != null) {
                val availableCameras = deviceCapabilityDetector.getAvailableCameras()
                if (availableCameras.isNotEmpty()) {
                    val entries = availableCameras.map { it.second }.toTypedArray()
                    val entryValues = availableCameras.map { it.first }.toTypedArray()
                    
                    cameraSelectionPref.entries = entries
                    cameraSelectionPref.entryValues = entryValues
                    cameraSelectionPref.summary = "Choose from ${availableCameras.size} available cameras"
                    
                    Timber.d("Populated ${availableCameras.size} camera options")
                }
            }
            
            // Populate resolution options for default camera
            val resolutionPref = findPreference<ListPreference>("video_resolution")
            if (resolutionPref != null) {
                val defaultCameraId = "0" // Use main camera for resolution detection
                val availableResolutions = deviceCapabilityDetector.getAvailableResolutions(defaultCameraId)
                
                if (availableResolutions.isNotEmpty()) {
                    val entries = availableResolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                    val entryValues = availableResolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                    
                    resolutionPref.entries = entries
                    resolutionPref.entryValues = entryValues
                    resolutionPref.summary = "Choose from ${availableResolutions.size} supported resolutions"
                    
                    Timber.d("Populated ${availableResolutions.size} resolution options")
                }
            }
            
            // Populate frame rate options for default camera
            val fpsPref = findPreference<ListPreference>("video_fps")
            if (fpsPref != null) {
                val defaultCameraId = "0"
                val availableFrameRates = deviceCapabilityDetector.getAvailableFrameRates(defaultCameraId)
                
                if (availableFrameRates.isNotEmpty()) {
                    val entries = availableFrameRates.map { "$it FPS" }.toTypedArray()
                    val entryValues = availableFrameRates.map { it.toString() }.toTypedArray()
                    
                    fpsPref.entries = entries
                    fpsPref.entryValues = entryValues
                    fpsPref.summary = "Choose from ${availableFrameRates.size} supported frame rates"
                    
                    Timber.d("Populated ${availableFrameRates.size} frame rate options")
                }
            }
            
            // Populate video quality options for default camera
            val qualityPref = findPreference<ListPreference>("video_quality")
            if (qualityPref != null) {
                val defaultCameraId = "0"
                val availableQualities = deviceCapabilityDetector.getAvailableQualities(defaultCameraId)
                
                if (availableQualities.isNotEmpty()) {
                    val entries = availableQualities.map { qualityToDisplayName(it) }.toTypedArray()
                    val entryValues = availableQualities.map { qualityToValue(it) }.toTypedArray()
                    
                    qualityPref.entries = entries
                    qualityPref.entryValues = entryValues
                    qualityPref.summary = "Choose from ${availableQualities.size} supported qualities"
                    
                    Timber.d("Populated ${availableQualities.size} quality options")
                }
            }
        }
        
        /**
         * Populate thermal camera preferences with actual device capabilities
         */
        private fun populateThermalCameraOptions() {
            val thermalCapabilities = deviceCapabilityDetector.detectThermalCameraCapabilities()
            
            if (thermalCapabilities.isNotEmpty()) {
                val thermalCapability = thermalCapabilities.first() // Use first detected thermal camera
                
                // Populate thermal resolution options
                val thermalResolutionPref = findPreference<ListPreference>("thermal_resolution")
                if (thermalResolutionPref != null) {
                    val resolutions = thermalCapability.supportedResolutions
                    val entries = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                    val entryValues = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                    
                    thermalResolutionPref.entries = entries
                    thermalResolutionPref.entryValues = entryValues
                    thermalResolutionPref.summary = "Choose from ${resolutions.size} supported thermal resolutions"
                    
                    Timber.d("Populated ${resolutions.size} thermal resolution options")
                }
                
                // Populate thermal frame rate options
                val thermalFpsPref = findPreference<ListPreference>("thermal_fps")
                if (thermalFpsPref != null) {
                    val frameRates = thermalCapability.supportedFrameRates
                    val entries = frameRates.map { "$it FPS" }.toTypedArray()
                    val entryValues = frameRates.map { it.toString() }.toTypedArray()
                    
                    thermalFpsPref.entries = entries
                    thermalFpsPref.entryValues = entryValues
                    thermalFpsPref.summary = "Choose from ${frameRates.size} supported thermal frame rates"
                    
                    Timber.d("Populated ${frameRates.size} thermal frame rate options")
                }
                
                // Populate thermal palette options
                val thermalPalettePref = findPreference<ListPreference>("thermal_palette")
                if (thermalPalettePref != null) {
                    val palettes = thermalCapability.supportedPalettes
                    val entries = palettes.toTypedArray()
                    val entryValues = palettes.map { it.lowercase() }.toTypedArray()
                    
                    thermalPalettePref.entries = entries
                    thermalPalettePref.entryValues = entryValues
                    thermalPalettePref.summary = "Choose from ${palettes.size} available color palettes"
                    
                    Timber.d("Populated ${palettes.size} thermal palette options")
                }
            } else {
                Timber.d("No thermal cameras detected - using default options")
            }
        }
        
        /**
         * Populate GSR sensor preferences with actual device capabilities
         */
        private fun populateGSRSensorOptions() {
            val gsrCapabilities = deviceCapabilityDetector.detectGSRSensorCapabilities()
            
            if (gsrCapabilities.isNotEmpty()) {
                val gsrCapability = gsrCapabilities.first() // Use first detected GSR sensor
                
                // Populate GSR sample rate options
                val gsrSampleRatePref = findPreference<ListPreference>("gsr_sample_rate")
                if (gsrSampleRatePref != null) {
                    val sampleRates = gsrCapability.supportedSampleRates
                    val entries = sampleRates.map { "$it Hz" }.toTypedArray()
                    val entryValues = sampleRates.map { it.toString() }.toTypedArray()
                    
                    gsrSampleRatePref.entries = entries
                    gsrSampleRatePref.entryValues = entryValues
                    gsrSampleRatePref.summary = "Choose from ${sampleRates.size} supported sample rates"
                    
                    Timber.d("Populated ${sampleRates.size} GSR sample rate options")
                }
            } else {
                Timber.d("No GSR sensors detected - using default options")
            }
        }
        
        /**
         * Convert Quality enum to display name
         */
        private fun qualityToDisplayName(quality: Quality): String {
            return when (quality) {
                Quality.UHD -> "Ultra HD (4K)"
                Quality.FHD -> "Full HD (1080p)"
                Quality.HD -> "HD (720p)"
                Quality.SD -> "Standard (480p)"
                else -> quality.toString()
            }
        }
        
        /**
         * Convert Quality enum to value string
         */
        private fun qualityToValue(quality: Quality): String {
            return when (quality) {
                Quality.UHD -> "UHD"
                Quality.FHD -> "FHD"
                Quality.HD -> "HD"
                Quality.SD -> "SD"
                else -> quality.toString()
            }
        }
    }
}