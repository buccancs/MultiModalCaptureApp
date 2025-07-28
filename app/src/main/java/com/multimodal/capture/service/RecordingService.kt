package com.multimodal.capture.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LifecycleOwner
import com.multimodal.capture.MainActivity
import com.multimodal.capture.R
import com.multimodal.capture.data.managers.CameraManager
import com.multimodal.capture.data.managers.GSRSensorManager
import com.multimodal.capture.data.managers.ThermalCameraManager
import com.multimodal.capture.data.DeviceState
import com.multimodal.capture.data.DeviceStateCallback
import com.multimodal.capture.data.network.NetworkManager
import com.multimodal.capture.data.interfaces.IDataSource
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * RecordingService handles background recording operations.
 * Runs as a foreground service to ensure continuous recording even when app is backgrounded.
 * Acts as the central orchestrator for all hardware managers and device state management.
 */
class RecordingService : Service(), DeviceStateCallback {
    
    private val binder = RecordingBinder()
    private var isRecording = false
    private var currentSessionId: String = ""
    private var startTimestamp: Long = 0L

    // Hardware Managers - The service owns all managers as single source of truth
    private lateinit var cameraManager: CameraManager
    private lateinit var gsrSensorManager: GSRSensorManager
    private lateinit var thermalCameraManager: ThermalCameraManager
    private lateinit var networkManager: NetworkManager
    
    // IDataSource pattern - Unified interface for all data sources
    private val dataSources = mutableListOf<IDataSource>()
    
    // Device State LiveData - Unified state machine for all devices
    private val _cameraState = MutableLiveData<DeviceState>(DeviceState.DISCONNECTED)
    private val _thermalState = MutableLiveData<DeviceState>(DeviceState.DISCONNECTED)
    private val _gsrState = MutableLiveData<DeviceState>(DeviceState.DISCONNECTED)
    
    val cameraState: LiveData<DeviceState> = _cameraState
    val thermalState: LiveData<DeviceState> = _thermalState
    val gsrState: LiveData<DeviceState> = _gsrState
    
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
                startRecordingSession(sessionId, timestamp)
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingSession()
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
     * DeviceStateCallback implementation - Central state management
     */
    override fun onDeviceStateChanged(deviceType: String, newState: DeviceState, message: String) {
        Timber.d("Device state changed: $deviceType -> $newState ($message)")
        
        when (deviceType.lowercase()) {
            "camera" -> _cameraState.postValue(newState)
            "thermal" -> _thermalState.postValue(newState)
            "gsr" -> _gsrState.postValue(newState)
            else -> Timber.w("Unknown device type: $deviceType")
        }
    }
    
    /**
     * Initialize all sensor managers that this service will control.
     * The service now owns all hardware managers as single source of truth.
     */
    private fun initializeSensorManagers() {
        try {
            // Initialize NetworkManager first as other managers may depend on it
            networkManager = NetworkManager(this)
            
            // Initialize GSR Sensor Manager with NetworkManager
            gsrSensorManager = GSRSensorManager(this, networkManager)
            gsrSensorManager.setStatusCallback { status -> 
                // Convert status string to DeviceState and report
                val deviceState = mapStatusToDeviceState(status)
                onDeviceStateChanged("gsr", deviceState, status)
            }

            // Initialize Thermal Camera Manager with NetworkManager
            thermalCameraManager = ThermalCameraManager(this, networkManager)
            thermalCameraManager.initialize()
            thermalCameraManager.setStatusCallback { status -> 
                val deviceState = mapStatusToDeviceState(status)
                onDeviceStateChanged("thermal", deviceState, status)
            }

            // Initialize Camera Manager with NetworkManager
            // Note: CameraManager requires LifecycleOwner, will be set when bound to UI
            // cameraManager will be initialized when needed through getCameraManager()

            // Add managers to IDataSource list for unified interface
            dataSources.add(gsrSensorManager)
            dataSources.add(thermalCameraManager)
            // CameraManager will be added when initialized with LifecycleOwner

            Timber.d("All sensor managers initialized in RecordingService")
            
        } catch (e: Exception) {
            Timber.e(e, "Error initializing sensor managers")
        }
    }
    
    /**
     * Map status strings to DeviceState enum values
     */
    private fun mapStatusToDeviceState(status: String): DeviceState {
        return when {
            status.contains("disconnected", ignoreCase = true) -> DeviceState.DISCONNECTED
            status.contains("permission", ignoreCase = true) -> DeviceState.PERMISSION_REQUIRED
            status.contains("connecting", ignoreCase = true) -> DeviceState.CONNECTING
            status.contains("ready", ignoreCase = true) -> DeviceState.READY
            status.contains("connected", ignoreCase = true) -> DeviceState.READY
            status.contains("streaming", ignoreCase = true) -> DeviceState.STREAMING
            status.contains("recording", ignoreCase = true) -> DeviceState.STREAMING
            status.contains("error", ignoreCase = true) -> DeviceState.ERROR
            else -> DeviceState.DISCONNECTED
        }
    }
    
    /**
     * Start recording session - Central orchestrator for all hardware managers
     */
    fun startRecordingSession(sessionId: String, timestamp: Long): Boolean {
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
            
            // Start recording on all data sources in synchronized manner
            val outputDir = getExternalFilesDir("sessions/$sessionId")!!
            
            // Use IDataSource pattern to start recording on all data sources
            dataSources.forEach { dataSource ->
                try {
                    dataSource.startRecording(sessionId, outputDir)
                    Timber.d("Started recording for data source: ${dataSource.getDataSourceName()}")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start recording for data source: ${dataSource.getDataSourceName()}")
                }
            }

            Timber.d("Recording session started for: $sessionId")
            
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
     * Stop recording session - Central orchestrator for all hardware managers
     */
    fun stopRecordingSession(): Boolean {
        if (!isRecording) {
            Timber.w("No recording in progress")
            return false
        }
        
        return try {
            isRecording = false
            
            // Stop foreground service
            stopForeground(true)

            // Stop recording on all data sources in synchronized manner
            dataSources.forEach { dataSource ->
                try {
                    dataSource.stopRecording()
                    Timber.d("Stopped recording for data source: ${dataSource.getDataSourceName()}")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to stop recording for data source: ${dataSource.getDataSourceName()}")
                }
            }
            
            Timber.d("Recording session stopped for: $currentSessionId")
            
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
    
    /**
     * Get CameraManager instance - Initialize if needed with LifecycleOwner
     */
    fun getCameraManager(lifecycleOwner: LifecycleOwner? = null): CameraManager? {
        return if (::cameraManager.isInitialized) {
            cameraManager
        } else if (lifecycleOwner != null) {
            // Initialize CameraManager when LifecycleOwner is available
            cameraManager = CameraManager(this, lifecycleOwner, networkManager)
            cameraManager.setStatusCallback { status -> 
                val deviceState = mapStatusToDeviceState(status)
                onDeviceStateChanged("camera", deviceState, status)
            }
            
            // Add to dataSources list for unified interface
            dataSources.add(cameraManager)
            
            cameraManager
        } else {
            null
        }
    }
    
    /**
     * Get NetworkManager instance
     */
    fun getNetworkManager(): NetworkManager {
        return networkManager
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (isRecording) {
            stopRecordingSession()
        }
        
        // Clean up all data sources using IDataSource pattern
        dataSources.forEach { dataSource ->
            try {
                dataSource.cleanup()
                Timber.d("Cleaned up data source: ${dataSource.getDataSourceName()}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup data source: ${dataSource.getDataSourceName()}")
            }
        }
        dataSources.clear()
        
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