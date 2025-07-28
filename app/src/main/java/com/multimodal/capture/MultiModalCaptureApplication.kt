package com.multimodal.capture

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for Multi-Modal Capture application.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class MultiModalCaptureApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("MultiModalCaptureApplication initialized with Hilt")
    }
}