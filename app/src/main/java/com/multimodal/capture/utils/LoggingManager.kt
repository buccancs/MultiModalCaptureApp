package com.multimodal.capture.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.multimodal.capture.BuildConfig
import timber.log.Timber
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Comprehensive logging manager for production-ready logging with file rotation,
 * structured logging, and crash reporting integration.
 */
class LoggingManager private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: LoggingManager? = null
        
        fun getInstance(): LoggingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoggingManager().also { INSTANCE = it }
            }
        }
        
        private const val LOG_FILE_PREFIX = "multimodal_capture"
        private const val LOG_FILE_EXTENSION = ".log"
        private const val MAX_LOG_FILES = 5
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val LOG_CLEANUP_INTERVAL_HOURS = 24L
    }
    
    private var context: Context? = null
    private var logDirectory: File? = null
    private var currentLogFile: File? = null
    private var fileWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var crashlytics: FirebaseCrashlytics? = null
    
    /**
     * Initialize Firebase Crashlytics with validation for dummy configurations
     */
    private fun initializeFirebaseCrashlytics() {
        try {
            // Check if we're using a dummy/placeholder API key
            if (isDummyFirebaseConfiguration()) {
                Timber.w("Dummy Firebase configuration detected - skipping Crashlytics initialization")
                return
            }
            
            // Initialize Firebase App manually if not already initialized
            try {
                com.google.firebase.FirebaseApp.getInstance()
            } catch (e: IllegalStateException) {
                // Firebase not initialized, try to initialize it
                try {
                    com.google.firebase.FirebaseApp.initializeApp(context!!)
                } catch (initException: Exception) {
                    Timber.w(initException, "Failed to initialize Firebase App - likely due to invalid configuration")
                    return
                }
            }
            
            // Initialize Crashlytics
            crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics?.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            Timber.d("Firebase Crashlytics initialized successfully")
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize Firebase Crashlytics - continuing without crash reporting")
            crashlytics = null
        }
    }
    
    /**
     * Check if the current Firebase configuration uses dummy/placeholder values
     */
    private fun isDummyFirebaseConfiguration(): Boolean {
        return try {
            val context = this.context ?: return true
            val firebaseOptions = loadFirebaseOptions(context) ?: return true
            val apiKey = firebaseOptions.apiKey
            
            val isDummy = isApiKeyDummy(apiKey)
            logDummyKeyDetection(isDummy, apiKey)
            
            isDummy
            
        } catch (e: Exception) {
            Timber.d("Could not validate Firebase configuration, assuming dummy: ${e.message}")
            true
        }
    }
    
    /**
     * Load Firebase options from resources
     */
    private fun loadFirebaseOptions(context: Context): com.google.firebase.FirebaseOptions? {
        return try {
            com.google.firebase.FirebaseOptions.fromResource(context)
        } catch (e: Exception) {
            Timber.d("Could not load Firebase options: ${e.message}")
            null
        }
    }
    
    /**
     * Check if API key matches dummy/placeholder patterns
     */
    private fun isApiKeyDummy(apiKey: String?): Boolean {
        if (apiKey == null) return true
        
        return isDummyByContent(apiKey) || isDummyByFormat(apiKey)
    }
    
    /**
     * Check if API key contains dummy content patterns
     */
    private fun isDummyByContent(apiKey: String): Boolean {
        val dummyPatterns = listOf("Dummy", "Development", "Placeholder", "Test")
        return dummyPatterns.any { pattern -> 
            apiKey.contains(pattern, ignoreCase = true) 
        } || apiKey == "AIzaSyDummyKeyForDevelopmentPurposes123456789"
    }
    
    /**
     * Check if API key has suspicious format indicating dummy key
     */
    private fun isDummyByFormat(apiKey: String): Boolean {
        return apiKey.startsWith("AIzaSy") && apiKey.length < 30
    }
    
    /**
     * Log dummy key detection result
     */
    private fun logDummyKeyDetection(isDummy: Boolean, apiKey: String?) {
        if (isDummy) {
            Timber.d("Detected dummy Firebase API key: ${apiKey?.take(20)}...")
        }
    }
    
    /**
     * Initialize the logging manager with application context
     */
    fun initialize(context: Context, enableFileLogging: Boolean = true) {
        this.context = context.applicationContext
        
        // Initialize Firebase Crashlytics with validation for dummy configurations
        initializeFirebaseCrashlytics()
        
        // Plant debug tree for development
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // Plant production tree for release builds
        Timber.plant(ProductionTree())
        
        if (enableFileLogging) {
            setupFileLogging()
            scheduleLogCleanup()
        }
        
        Timber.i("LoggingManager initialized - Debug: ${BuildConfig.DEBUG}, FileLogging: $enableFileLogging")
    }
    
    /**
     * Setup file logging with rotation
     */
    private fun setupFileLogging() {
        try {
            context?.let { ctx ->
                logDirectory = File(ctx.getExternalFilesDir(null), "logs").apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }
                
                createNewLogFile()
                Timber.plant(FileLoggingTree())
                Timber.d("File logging initialized at: ${logDirectory?.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup file logging")
        }
    }
    
    /**
     * Create a new log file with timestamp
     */
    private fun createNewLogFile() {
        try {
            closeCurrentLogFile()
            
            val timestamp = fileDateFormat.format(Date())
            currentLogFile = File(logDirectory, "${LOG_FILE_PREFIX}_${timestamp}${LOG_FILE_EXTENSION}")
            fileWriter = FileWriter(currentLogFile, true)
            
            // Write session header
            val sessionInfo = buildString {
                appendLine("=== NEW LOGGING SESSION ===")
                appendLine("Timestamp: ${dateFormat.format(Date())}")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Debug Build: ${BuildConfig.DEBUG}")
                appendLine("===============================")
            }
            
            fileWriter?.write(sessionInfo)
            fileWriter?.flush()
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to create new log file")
        }
    }
    
    /**
     * Close current log file
     */
    private fun closeCurrentLogFile() {
        try {
            fileWriter?.close()
            fileWriter = null
        } catch (e: IOException) {
            Timber.e(e, "Error closing log file")
        }
    }
    
    /**
     * Check if log rotation is needed
     */
    private fun checkLogRotation() {
        currentLogFile?.let { file ->
            if (file.length() > MAX_LOG_FILE_SIZE) {
                Timber.i("Log file size exceeded, rotating logs")
                createNewLogFile()
            }
        }
    }
    
    /**
     * Schedule periodic log cleanup
     */
    private fun scheduleLogCleanup() {
        executor.scheduleAtFixedRate({
            cleanupOldLogs()
        }, LOG_CLEANUP_INTERVAL_HOURS, LOG_CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS)
    }
    
    /**
     * Clean up old log files
     */
    private fun cleanupOldLogs() {
        try {
            logDirectory?.let { dir ->
                val logFiles = dir.listFiles { _, name ->
                    name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION)
                }?.sortedByDescending { it.lastModified() }
                
                logFiles?.let { files ->
                    if (files.size > MAX_LOG_FILES) {
                        val filesToDelete = files.drop(MAX_LOG_FILES)
                        filesToDelete.forEach { file ->
                            if (file.delete()) {
                                Timber.d("Deleted old log file: ${file.name}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during log cleanup")
        }
    }
    
    /**
     * Get current log files for debugging or export
     */
    fun getLogFiles(): List<File> {
        return logDirectory?.listFiles { _, name ->
            name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION)
        }?.toList() ?: emptyList()
    }
    
    /**
     * Export logs to external storage for debugging
     */
    fun exportLogs(callback: (Boolean, String?) -> Unit) {
        executor.execute {
            try {
                val logFiles = getLogFiles()
                if (logFiles.isEmpty()) {
                    callback(false, "No log files found")
                    return@execute
                }
                
                val exportDir = File(context?.getExternalFilesDir(null), "exported_logs")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                
                val exportFile = File(exportDir, "logs_export_${fileDateFormat.format(Date())}.txt")
                exportFile.writeText(buildString {
                    logFiles.sortedBy { it.lastModified() }.forEach { file ->
                        appendLine("=== ${file.name} ===")
                        appendLine(file.readText())
                        appendLine()
                    }
                })
                
                callback(true, exportFile.absolutePath)
                Timber.i("Logs exported to: ${exportFile.absolutePath}")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to export logs")
                callback(false, e.message)
            }
        }
    }
    
    /**
     * Report a non-fatal exception to Crashlytics
     */
    fun reportException(throwable: Throwable, message: String? = null) {
        try {
            crashlytics?.let { crashlytics ->
                message?.let { crashlytics.log(it) }
                crashlytics.recordException(throwable)
                Timber.w(throwable, "Exception reported to Crashlytics: $message")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to report exception to Crashlytics")
        }
    }
    
    /**
     * Set user identifier for crash reporting
     */
    fun setUserId(userId: String) {
        try {
            crashlytics?.setUserId(userId)
            Timber.d("User ID set for crash reporting: $userId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set user ID for crash reporting")
        }
    }
    
    /**
     * Add custom key-value data to crash reports
     */
    fun setCustomKey(key: String, value: String) {
        try {
            crashlytics?.setCustomKey(key, value)
            Timber.d("Custom key set for crash reporting: $key = $value")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set custom key for crash reporting")
        }
    }
    
    /**
     * Add custom key-value data to crash reports (boolean)
     */
    fun setCustomKey(key: String, value: Boolean) {
        try {
            crashlytics?.setCustomKey(key, value)
            Timber.d("Custom key set for crash reporting: $key = $value")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set custom key for crash reporting")
        }
    }
    
    /**
     * Add custom key-value data to crash reports (int)
     */
    fun setCustomKey(key: String, value: Int) {
        try {
            crashlytics?.setCustomKey(key, value)
            Timber.d("Custom key set for crash reporting: $key = $value")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set custom key for crash reporting")
        }
    }
    
    /**
     * Log a message to Crashlytics
     */
    fun logToCrashlytics(message: String) {
        try {
            crashlytics?.log(message)
            Timber.d("Message logged to Crashlytics: $message")
        } catch (e: Exception) {
            Timber.e(e, "Failed to log message to Crashlytics")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        closeCurrentLogFile()
        executor.shutdown()
        Timber.d("LoggingManager cleaned up")
    }
    
    /**
     * Production logging tree that filters sensitive information and reports crashes
     */
    private inner class ProductionTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= Log.INFO) {
                // Filter sensitive information in production
                val filteredMessage = filterSensitiveInfo(message)
                Log.println(priority, tag, filteredMessage)
                
                // Report errors and warnings to Crashlytics
                if (priority >= Log.WARN) {
                    try {
                        crashlytics?.let { crashlytics ->
                            crashlytics.log("$tag: $filteredMessage")
                            t?.let { throwable ->
                                if (priority >= Log.ERROR) {
                                    crashlytics.recordException(throwable)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Avoid infinite recursion by using Android Log directly
                        Log.e("LoggingManager", "Failed to report to Crashlytics", e)
                    }
                }
            }
        }
        
        private fun filterSensitiveInfo(message: String): String {
            return message
                .replace(Regex("password[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "password=***")
                .replace(Regex("token[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "token=***")
                .replace(Regex("key[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "key=***")
        }
    }
    
    /**
     * File logging tree for persistent logging
     */
    private inner class FileLoggingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Check if executor is shut down to prevent RejectedExecutionException
            if (executor.isShutdown) {
                // Fall back to Android Log if executor is shut down
                Log.println(priority, tag ?: "LoggingManager", message)
                return
            }
            
            executor.execute {
                try {
                    checkLogRotation()
                    
                    val priorityString = when (priority) {
                        Log.VERBOSE -> "V"
                        Log.DEBUG -> "D"
                        Log.INFO -> "I"
                        Log.WARN -> "W"
                        Log.ERROR -> "E"
                        Log.ASSERT -> "A"
                        else -> "U"
                    }
                    
                    val logEntry = buildString {
                        append("${dateFormat.format(Date())} ")
                        append("$priorityString/${tag ?: "Unknown"}: ")
                        append(message)
                        
                        t?.let { throwable ->
                            append("\n")
                            append(Log.getStackTraceString(throwable))
                        }
                        append("\n")
                    }
                    
                    fileWriter?.write(logEntry)
                    fileWriter?.flush()
                    
                } catch (e: Exception) {
                    // Avoid infinite recursion by using Android Log directly
                    Log.e("LoggingManager", "Error writing to log file", e)
                }
            }
        }
    }
}

/**
 * Extension functions for structured logging
 */
object StructuredLogging {
    
    /**
     * Log performance metrics
     */
    fun logPerformance(operation: String, durationMs: Long, additionalData: Map<String, Any> = emptyMap()) {
        val data = mutableMapOf<String, Any>(
            "operation" to operation,
            "duration_ms" to durationMs,
            "timestamp" to System.currentTimeMillis()
        )
        data.putAll(additionalData)
        
        Timber.i("PERFORMANCE: ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    }
    
    /**
     * Log user actions
     */
    fun logUserAction(action: String, screen: String, additionalData: Map<String, Any> = emptyMap()) {
        val data = mutableMapOf<String, Any>(
            "action" to action,
            "screen" to screen,
            "timestamp" to System.currentTimeMillis()
        )
        data.putAll(additionalData)
        
        Timber.i("USER_ACTION: ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    }
    
    /**
     * Log system events
     */
    fun logSystemEvent(event: String, category: String, additionalData: Map<String, Any> = emptyMap()) {
        val data = mutableMapOf<String, Any>(
            "event" to event,
            "category" to category,
            "timestamp" to System.currentTimeMillis()
        )
        data.putAll(additionalData)
        
        Timber.i("SYSTEM_EVENT: ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    }
    
    /**
     * Log network operations
     */
    fun logNetworkOperation(operation: String, url: String, statusCode: Int, durationMs: Long) {
        Timber.i("NETWORK: operation=$operation, url=$url, status=$statusCode, duration_ms=$durationMs")
    }
    
    /**
     * Log data operations
     */
    fun logDataOperation(operation: String, dataType: String, recordCount: Int, success: Boolean) {
        Timber.i("DATA_OP: operation=$operation, type=$dataType, records=$recordCount, success=$success")
    }
}