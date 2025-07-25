package com.multimodal.capture.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.multimodal.capture.MainActivity
import com.multimodal.capture.R
import com.multimodal.capture.BuildConfig
import timber.log.Timber

/**
 * Splash screen activity that displays app branding and initializes the application.
 * Shows for 2-3 seconds before navigating to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var splashRunnable: Runnable? = null
    private var isTimerActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Timber.d("[DEBUG_LOG] SplashActivity started")
        
        // Set portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // Set splash screen layout
        setContentView(R.layout.activity_splash)
        
        // Set navigation bar color to match splash background
        window.navigationBarColor = ContextCompat.getColor(this, R.color.splash_background)
        
        // Initialize version info dynamically
        initializeVersionInfo()
        
        // Start splash timer
        startSplashTimer()
    }

    /**
     * Initialize version information dynamically
     */
    private fun initializeVersionInfo() {
        try {
            val versionTextView = findViewById<TextView>(R.id.tv_version)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            val versionText = "Version $versionName (Build $versionCode)"
            versionTextView.text = versionText
            
            Timber.d("[DEBUG_LOG] Version info set: $versionText")
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to get version info")
            // Fallback to default version string
            findViewById<TextView>(R.id.tv_version).text = getString(R.string.splash_version_info)
        }
    }

    /**
     * Start the splash screen timer
     */
    private fun startSplashTimer() {
        // Show splash for 2.5 seconds in release, 1.5 seconds in debug
        val splashDuration = if (BuildConfig.DEBUG) 1500L else 2500L
        
        splashRunnable = Runnable {
            isTimerActive = false
            navigateToMainActivity()
        }
        
        isTimerActive = true
        splashHandler.postDelayed(splashRunnable!!, splashDuration)
        
        Timber.d("[DEBUG_LOG] Splash timer started for ${splashDuration}ms")
    }

    /**
     * Navigate to MainActivity and finish splash screen
     */
    private fun navigateToMainActivity() {
        try {
            Timber.d("[DEBUG_LOG] Navigating to MainActivity")
            
            val intent = Intent(this, MainActivity::class.java)
            
            // Add flags to prevent going back to splash screen
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            
            startActivity(intent)
            
            // Add smooth transition animation
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            
            finish()
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to navigate to MainActivity")
            // Fallback: finish splash screen anyway
            finish()
        }
    }

    /**
     * Disable back button during splash screen
     */
    override fun onBackPressed() {
        // Do nothing - prevent user from going back during splash
        Timber.d("[DEBUG_LOG] Back button pressed during splash - ignoring")
        super.onBackPressed()
    }

    /**
     * Clean up resources when activity is destroyed
     */
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel any pending splash timer
        splashRunnable?.let { runnable ->
            splashHandler.removeCallbacks(runnable)
            splashRunnable = null
        }
        
        Timber.d("[DEBUG_LOG] SplashActivity destroyed")
    }

    /**
     * Handle activity pause - cancel timer if user leaves app
     */
    override fun onPause() {
        super.onPause()
        
        // If user leaves app during splash, cancel timer
        if (!isFinishing && isTimerActive) {
            splashRunnable?.let { runnable ->
                splashHandler.removeCallbacks(runnable)
                isTimerActive = false
            }
            Timber.d("[DEBUG_LOG] SplashActivity paused - timer cancelled")
        }
    }

    /**
     * Handle activity resume - restart timer if needed
     */
    override fun onResume() {
        super.onResume()
        
        // If returning to splash screen and timer is not active, restart timer
        if (splashRunnable != null && !isTimerActive) {
            startSplashTimer()
            Timber.d("[DEBUG_LOG] SplashActivity resumed - timer restarted")
        }
    }
}