package com.multimodal.capture.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.multimodal.capture.R
import com.multimodal.capture.BuildConfig
import timber.log.Timber

/**
 * Activity that provides a separate preview screen for the splash screen.
 * Allows users to see how the splash screen appears without going through the app launch flow.
 */
class SplashPreviewActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var btnRefreshPreview: MaterialButton
    private lateinit var btnToggleOverlay: MaterialButton
    private lateinit var viewPreviewOverlay: View
    private lateinit var cvInfo: MaterialCardView
    private lateinit var tvSplashInfo: TextView
    private lateinit var tvVersionInPreview: TextView

    private var isOverlayVisible = false
    private var isInfoVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Timber.d("[DEBUG_LOG] SplashPreviewActivity started")
        
        // Set portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // Set preview layout
        setContentView(R.layout.activity_splash_preview)
        
        // Set navigation bar color to match splash background
        window.navigationBarColor = ContextCompat.getColor(this, R.color.splash_background)
        
        // Initialize views
        initializeViews()
        
        // Setup toolbar
        setupToolbar()
        
        // Setup controls
        setupControls()
        
        // Initialize preview content
        initializePreviewContent()
    }

    /**
     * Initialize all views
     */
    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        btnRefreshPreview = findViewById(R.id.btn_refresh_preview)
        btnToggleOverlay = findViewById(R.id.btn_toggle_overlay)
        viewPreviewOverlay = findViewById(R.id.view_preview_overlay)
        cvInfo = findViewById(R.id.cv_info)
        tvSplashInfo = findViewById(R.id.tv_splash_info)
        
        // Get the version TextView from the included splash layout
        tvVersionInPreview = findViewById(R.id.tv_version)
    }

    /**
     * Setup toolbar with navigation
     */
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Splash Screen Preview"
        }
        
        // Handle navigation back
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    /**
     * Setup control buttons
     */
    private fun setupControls() {
        // Refresh preview button
        btnRefreshPreview.setOnClickListener {
            refreshPreview()
        }
        
        // Toggle overlay button
        btnToggleOverlay.setOnClickListener {
            toggleOverlay()
        }
        
        // Toggle info card on long press of preview container
        findViewById<View>(R.id.fl_preview_container).setOnLongClickListener {
            toggleInfoCard()
            true
        }
    }

    /**
     * Initialize preview content with dynamic data
     */
    private fun initializePreviewContent() {
        try {
            // Update version info in the preview (same as SplashActivity)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            val versionText = "Version $versionName (Build $versionCode)"
            tvVersionInPreview.text = versionText
            
            // Update info card content
            updateInfoCardContent()
            
            Timber.d("[DEBUG_LOG] Preview content initialized with version: $versionText")
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to initialize preview content")
            // Fallback to default version string
            tvVersionInPreview.text = getString(R.string.splash_version_info)
        }
    }

    /**
     * Update info card content with current app information
     */
    private fun updateInfoCardContent() {
        val splashDuration = if (BuildConfig.DEBUG) 1.5f else 2.5f
        val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"
        
        val infoText = """
            This preview shows how the splash screen appears when the app launches.
            
            • Duration: ${splashDuration}s in $buildType builds
            • Features: Thermal Camera, GSR Sensors, Multi-Modal Capture
            • Navigation: Automatically proceeds to MainActivity
            • Back Button: Disabled during actual splash screen
            
            Long press the preview to toggle this information card.
        """.trimIndent()
        
        tvSplashInfo.text = infoText
    }

    /**
     * Refresh the preview content
     */
    private fun refreshPreview() {
        Timber.d("[DEBUG_LOG] Refreshing splash screen preview")
        
        // Re-initialize preview content
        initializePreviewContent()
        
        // Hide overlay if visible
        if (isOverlayVisible) {
            toggleOverlay()
        }
        
        // Show brief feedback
        btnRefreshPreview.text = "Refreshed!"
        btnRefreshPreview.postDelayed({
            btnRefreshPreview.text = "Refresh"
        }, 1000)
    }

    /**
     * Toggle preview overlay
     */
    private fun toggleOverlay() {
        isOverlayVisible = !isOverlayVisible
        
        viewPreviewOverlay.visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        
        btnToggleOverlay.text = if (isOverlayVisible) "Hide Overlay" else "Show Overlay"
        
        Timber.d("[DEBUG_LOG] Preview overlay toggled: $isOverlayVisible")
    }

    /**
     * Toggle information card visibility
     */
    private fun toggleInfoCard() {
        isInfoVisible = !isInfoVisible
        
        cvInfo.visibility = if (isInfoVisible) View.VISIBLE else View.GONE
        
        Timber.d("[DEBUG_LOG] Info card toggled: $isInfoVisible")
    }

    /**
     * Handle back button press
     */
    override fun onBackPressed() {
        Timber.d("[DEBUG_LOG] SplashPreviewActivity back button pressed")
        super.onBackPressed()
        
        // Add smooth transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Handle activity pause
     */
    override fun onPause() {
        super.onPause()
        Timber.d("[DEBUG_LOG] SplashPreviewActivity paused")
    }

    /**
     * Handle activity resume
     */
    override fun onResume() {
        super.onResume()
        Timber.d("[DEBUG_LOG] SplashPreviewActivity resumed")
        
        // Refresh content when returning to preview
        initializePreviewContent()
    }

    /**
     * Handle activity destroy
     */
    override fun onDestroy() {
        super.onDestroy()
        Timber.d("[DEBUG_LOG] SplashPreviewActivity destroyed")
    }
}