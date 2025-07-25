package com.multimodal.capture.utils

import android.content.Context
import com.multimodal.capture.data.SessionFolder
import com.multimodal.capture.data.SessionFile
import timber.log.Timber
import java.io.File

/**
 * SessionFolderManager handles scanning and organizing session folders
 * from the recordings directory.
 */
class SessionFolderManager(private val context: Context) {

    private val recordingsDir: File by lazy {
        File(context.getExternalFilesDir(null), "recordings").apply {
            if (!exists()) {
                mkdirs()
                Timber.d("Created recordings directory: $absolutePath")
            }
        }
    }

    /**
     * Get all session folders from the recordings directory.
     * Groups files by session ID and creates SessionFolder objects.
     */
    fun getSessionFolders(): List<SessionFolder> {
        return try {
            val sessionMap = mutableMapOf<String, MutableList<File>>()
            
            // Scan all files in recordings directory
            recordingsDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val sessionId = extractSessionId(file.name)
                    if (sessionId.isNotEmpty()) {
                        sessionMap.getOrPut(sessionId) { mutableListOf() }.add(file)
                    }
                }
            }
            
            // Convert to SessionFolder objects
            sessionMap.map { (sessionId, files) ->
                createSessionFolder(sessionId, files)
            }.sortedByDescending { it.createdDate }
            
        } catch (e: Exception) {
            Timber.e(e, "Error scanning session folders")
            emptyList()
        }
    }

    /**
     * Get a specific session folder by session ID
     */
    fun getSessionFolder(sessionId: String): SessionFolder? {
        return getSessionFolders().find { it.name == sessionId }
    }

    /**
     * Delete a session folder and all its files
     */
    fun deleteSessionFolder(sessionId: String): Boolean {
        return try {
            val sessionFiles = recordingsDir.listFiles()?.filter { file ->
                file.isFile && extractSessionId(file.name) == sessionId
            } ?: emptyList()
            
            var allDeleted = true
            sessionFiles.forEach { file ->
                if (!file.delete()) {
                    allDeleted = false
                    Timber.w("Failed to delete file: ${file.name}")
                }
            }
            
            if (allDeleted) {
                Timber.d("Successfully deleted session: $sessionId")
            }
            
            allDeleted
        } catch (e: Exception) {
            Timber.e(e, "Error deleting session folder: $sessionId")
            false
        }
    }

    /**
     * Get total size of all session folders
     */
    fun getTotalSessionsSize(): Long {
        return try {
            recordingsDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            Timber.e(e, "Error calculating total sessions size")
            0L
        }
    }

    /**
     * Get count of all session folders
     */
    fun getSessionCount(): Int {
        return getSessionFolders().size
    }

    /**
     * Extract session ID from filename
     * Expected format: {sessionId}_{type}.{extension}
     */
    private fun extractSessionId(filename: String): String {
        return try {
            // Remove extension first
            val nameWithoutExt = filename.substringBeforeLast('.')
            
            // Find the last underscore and take everything before it as session ID
            val lastUnderscoreIndex = nameWithoutExt.lastIndexOf('_')
            if (lastUnderscoreIndex > 0) {
                nameWithoutExt.substring(0, lastUnderscoreIndex)
            } else {
                ""
            }
        } catch (e: Exception) {
            Timber.w(e, "Error extracting session ID from filename: $filename")
            ""
        }
    }

    /**
     * Create SessionFolder from session ID and list of files
     */
    private fun createSessionFolder(sessionId: String, files: List<File>): SessionFolder {
        val sessionFiles = files.map { SessionFile.fromFile(it) }
        val totalSize = files.sumOf { it.length() }
        val earliestDate = files.minOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
        
        return SessionFolder(
            name = sessionId,
            path = recordingsDir.absolutePath,
            createdDate = java.util.Date(earliestDate),
            fileCount = files.size,
            totalSize = totalSize,
            files = sessionFiles
        )
    }

    /**
     * Check if recordings directory exists and is accessible
     */
    fun isRecordingsDirectoryAccessible(): Boolean {
        return recordingsDir.exists() && recordingsDir.canRead()
    }

    /**
     * Get recordings directory path
     */
    fun getRecordingsDirectoryPath(): String {
        return recordingsDir.absolutePath
    }

    companion object {
        private const val TAG = "SessionFolderManager"
    }
}