package com.multimodal.capture.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.multimodal.capture.MainActivity
import com.multimodal.capture.R
import com.multimodal.capture.capture.CameraManager
import com.multimodal.capture.capture.GSRSensorManager
import com.multimodal.capture.capture.ThermalCameraManager
import timber.log.Timber

/**
 * RecordingService handles background recording operations.
 * Runs as a foreground service to ensure continuous recording even when app is backgrounded.
 */
class RecordingService : Service() {
    
    private val binder = RecordingBinder()
    private var isRecording = false
    private var currentSessionId: String = ""
    private var startTimestamp: Long = 0L

    // Sensor Managers - The service now owns the managers
    private lateinit var gsrSensorManager: GSRSensorManager
    private lateinit var thermalCameraManager: ThermalCameraManager
    // private lateinit var cameraManager: CameraManager // Add if needed
    
    // Notification
    private val notificationId = 1001
    private val channelId = "recording_channel"
    
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("RecordingService created")
        createNotificationChannel()
        initializeSensorManagers()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: "Unknown"
                val timestamp = intent.getLongExtra(EXTRA_START_TIMESTAMP, SystemClock.elapsedRealtimeNanos())
                startRecording(sessionId, timestamp)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }
            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }
        }
        
        return START_STICKY // Restart service if killed
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    /**
     * Initialize all sensor managers that this service will control.
     */
    private fun initializeSensorManagers() {
        gsrSensorManager = GSRSensorManager(this)
        gsrSensorManager.setStatusCallback { status -> Timber.i("GSR Status: $status") }

        thermalCameraManager = ThermalCameraManager(this)
        thermalCameraManager.initialize()
        thermalCameraManager.setStatusCallback { status -> Timber.i("Thermal Status: $status") }

        Timber.d("Sensor managers initialized in RecordingService")
    }
    
    /**
     * Start recording session
     */
    fun startRecording(sessionId: String, timestamp: Long): Boolean {
        if (isRecording) {
            Timber.w("Recording already in progress")
            return false
        }
        
        return try {
            currentSessionId = sessionId
            startTimestamp = timestamp
            isRecording = true
            
            // Start foreground service with notification
            val notification = createRecordingNotification(sessionId)
            startForeground(notificationId, notification)
            
            // Start recording on all relevant managers
            val outputDir = getExternalFilesDir("sessions/$sessionId")!!
            gsrSensorManager.startRecording(sessionId, timestamp)
            thermalCameraManager.startRecording(sessionId, outputDir)

            Timber.d("Recording service started for session: $sessionId")
            
            // Broadcast recording started
            val broadcastIntent = Intent(BROADCAST_RECORDING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RECORDING, true)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            sendBroadcast(broadcastIntent)
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording service")
            isRecording = false
            stopSelf()
            false
        }
    }
    
    /**
     * Stop recording session
     */
    fun stopRecording(): Boolean {
        if (!isRecording) {
            Timber.w("No recording in progress")
            return false
        }
        
        return try {
            isRecording = false
            
            // Stop foreground service
            stopForeground(true)

            // Stop recording on all managers
            gsrSensorManager.stopRecording()
            thermalCameraManager.stopRecording()
            
            Timber.d("Recording service stopped for session: $currentSessionId")
            
            // Broadcast recording stopped
            val broadcastIntent = Intent(BROADCAST_RECORDING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RECORDING, false)
                putExtra(EXTRA_SESSION_ID, currentSessionId)
            }
            sendBroadcast(broadcastIntent)
            
            // Reset session info
            currentSessionId = ""
            startTimestamp = 0L
            
            // Stop service
            stopSelf()
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording service")
            false
        }
    }
    
    /**
     * Pause recording (if supported)
     */
    private fun pauseRecording() {
        if (!isRecording) {
            Timber.w("No recording in progress to pause")
            return
        }
        
        try {
            // Update notification to show paused state
            val notification = createPausedNotification(currentSessionId)
            NotificationManagerCompat.from(this).notify(notificationId, notification)
            
            Timber.d("Recording paused for session: $currentSessionId")
            
            // Broadcast recording paused
            val broadcastIntent = Intent(BROADCAST_RECORDING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RECORDING, false)
                putExtra(EXTRA_IS_PAUSED, true)
                putExtra(EXTRA_SESSION_ID, currentSessionId)
            }
            sendBroadcast(broadcastIntent)
            
        } catch (e: Exception) {
            Timber.e(e, "Error pausing recording")
        }
    }
    
    /**
     * Resume recording (if supported)
     */
    private fun resumeRecording() {
        try {
            // Update notification to show recording state
            val notification = createRecordingNotification(currentSessionId)
            NotificationManagerCompat.from(this).notify(notificationId, notification)
            
            Timber.d("Recording resumed for session: $currentSessionId")
            
            // Broadcast recording resumed
            val broadcastIntent = Intent(BROADCAST_RECORDING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RECORDING, true)
                putExtra(EXTRA_IS_PAUSED, false)
                putExtra(EXTRA_SESSION_ID, currentSessionId)
            }
            sendBroadcast(broadcastIntent)
            
        } catch (e: Exception) {
            Timber.e(e, "Error resuming recording")
        }
    }
    
    /**
     * Create notification channel for recording notifications
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_recording_desc)
                setShowBadge(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create recording notification
     */
    private fun createRecordingNotification(sessionId: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop recording action
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_camera,
                "Stop",
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * Create paused notification
     */
    private fun createPausedNotification(sessionId: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Resume recording action
        val resumeIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_RESUME_RECORDING
        }
        val resumePendingIntent = PendingIntent.getService(
            this, 2, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop recording action
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Paused")
            .setContentText("Session: $sessionId")
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_camera,
                "Resume",
                resumePendingIntent
            )
            .addAction(
                R.drawable.ic_camera,
                "Stop",
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * Get current recording state
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String = currentSessionId
    
    /**
     * Get recording start timestamp
     */
    fun getStartTimestamp(): Long = startTimestamp
    
    /**
     * Get recording duration in milliseconds
     */
    fun getRecordingDuration(): Long {
        return if (isRecording && startTimestamp > 0) {
            (SystemClock.elapsedRealtimeNanos() - startTimestamp) / 1_000_000
        } else {
            0L
        }
    }
    
    /**
     * Provide access to the GSR manager for the UI to bind to.
     * @return The instance of GSRSensorManager.
     */
    fun getGSRManager(): GSRSensorManager {
        return gsrSensorManager
    }

    /**
     * Provide access to the Thermal manager for the UI to bind to.
     * @return The instance of ThermalCameraManager.
     */
    fun getThermalManager(): ThermalCameraManager {
        return thermalCameraManager
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (isRecording) {
            stopRecording()
        }
        
        // Clean up sensor managers
        gsrSensorManager.cleanup()
        thermalCameraManager.cleanup()
        Timber.d("RecordingService destroyed")
    }
    
    companion object {
        // Actions
        const val ACTION_START_RECORDING = "com.multimodal.capture.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.multimodal.capture.STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.multimodal.capture.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.multimodal.capture.RESUME_RECORDING"
        
        // Extras
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_START_TIMESTAMP = "start_timestamp"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_PAUSED = "is_paused"
        
        // Broadcasts
        const val BROADCAST_RECORDING_STATE_CHANGED = "com.multimodal.capture.RECORDING_STATE_CHANGED"
    }
}