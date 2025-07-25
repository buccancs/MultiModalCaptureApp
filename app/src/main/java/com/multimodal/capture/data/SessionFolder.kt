package com.multimodal.capture.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class representing a session folder with its metadata
 */
data class SessionFolder(
    val name: String,
    val path: String,
    val createdDate: Date,
    val fileCount: Int,
    val totalSize: Long,
    val files: List<SessionFile>
) {
    
    /**
     * Get formatted date string
     */
    fun getFormattedDate(): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return formatter.format(createdDate)
    }
    
    /**
     * Get formatted size string
     */
    fun getFormattedSize(): String {
        return when {
            totalSize < 1024 -> "${totalSize} B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            totalSize < 1024 * 1024 * 1024 -> String.format("%.1f MB", totalSize / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", totalSize / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * Get file count string
     */
    fun getFileCountString(): String {
        return if (fileCount == 1) "1 file" else "$fileCount files"
    }
    
    companion object {
        /**
         * Create SessionFolder from directory
         */
        fun fromDirectory(directory: File): SessionFolder? {
            if (!directory.exists() || !directory.isDirectory) return null
            
            val files = directory.listFiles()?.mapNotNull { file ->
                if (file.isFile) SessionFile.fromFile(file) else null
            } ?: emptyList()
            
            val totalSize = files.sumOf { it.size }
            
            return SessionFolder(
                name = directory.name,
                path = directory.absolutePath,
                createdDate = Date(directory.lastModified()),
                fileCount = files.size,
                totalSize = totalSize,
                files = files
            )
        }
    }
}

/**
 * Data class representing a file within a session folder
 */
data class SessionFile(
    val name: String,
    val path: String,
    val size: Long,
    val type: SessionFileType,
    val lastModified: Date
) {
    
    /**
     * Get formatted size string
     */
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "${size} B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    companion object {
        /**
         * Create SessionFile from File
         */
        fun fromFile(file: File): SessionFile {
            val type = SessionFileType.fromFileName(file.name)
            return SessionFile(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                type = type,
                lastModified = Date(file.lastModified())
            )
        }
    }
}

/**
 * Enum representing different types of session files
 */
enum class SessionFileType(val extension: String, val displayName: String) {
    AUDIO("wav", "Audio"),
    VIDEO("mp4", "Video"),
    GSR_DATA("csv", "GSR Data"),
    THERMAL_YUV("dat", "Thermal YUV"),
    THERMAL_ARGB("dat", "Thermal ARGB"),
    THERMAL_RAW("dat", "Thermal Raw"),
    THERMAL_PSEUDO("dat", "Thermal Pseudo"),
    METADATA("json", "Metadata"),
    UNKNOWN("", "Unknown");
    
    companion object {
        fun fromFileName(fileName: String): SessionFileType {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when {
                fileName.contains("audio") && extension == "wav" -> AUDIO
                fileName.contains("rgb_video") && extension == "mp4" -> VIDEO
                fileName.contains("gsr_data") && extension == "csv" -> GSR_DATA
                fileName.contains("thermal_yuv") && extension == "dat" -> THERMAL_YUV
                fileName.contains("thermal_argb") && extension == "dat" -> THERMAL_ARGB
                fileName.contains("thermal_raw") && extension == "dat" -> THERMAL_RAW
                fileName.contains("thermal_pseudocolored") && extension == "dat" -> THERMAL_PSEUDO
                extension == "json" -> METADATA
                else -> UNKNOWN
            }
        }
    }
}