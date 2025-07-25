package com.multimodal.capture.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.multimodal.capture.R
import com.multimodal.capture.data.SessionFolder
import com.multimodal.capture.data.SessionFile
import com.multimodal.capture.data.SessionFileType
import com.multimodal.capture.databinding.ActivitySessionFolderBinding
import com.multimodal.capture.ui.adapter.SessionFileAdapter
import com.multimodal.capture.ui.adapter.SessionFolderAdapter
import com.multimodal.capture.utils.SessionFolderManager
import timber.log.Timber
import java.io.File

/**
 * SessionFolderActivity provides a view of recorded session folders and files.
 * Displays session folders with their contents and metadata.
 */
class SessionFolderActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionFolderBinding
    private lateinit var sessionFolderAdapter: SessionFolderAdapter
    private lateinit var sessionFolderManager: SessionFolderManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySessionFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupActionBar()
        setupRecyclerView()
        loadSessionFolders()
        
        Timber.d("SessionFolderActivity created")
    }

    private fun setupActionBar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Session Folders"
        }
    }

    private fun setupRecyclerView() {
        sessionFolderManager = SessionFolderManager(this)
        sessionFolderAdapter = SessionFolderAdapter { sessionFolder ->
            showSessionDetails(sessionFolder)
        }
        
        binding.recyclerViewSessions.apply {
            layoutManager = LinearLayoutManager(this@SessionFolderActivity)
            adapter = sessionFolderAdapter
        }
    }

    private fun loadSessionFolders() {
        try {
            val sessionFolders = sessionFolderManager.getSessionFolders()
            sessionFolderAdapter.updateSessions(sessionFolders)
            
            // Update empty state
            if (sessionFolders.isEmpty()) {
                binding.textViewEmpty.visibility = android.view.View.VISIBLE
                binding.recyclerViewSessions.visibility = android.view.View.GONE
            } else {
                binding.textViewEmpty.visibility = android.view.View.GONE
                binding.recyclerViewSessions.visibility = android.view.View.VISIBLE
            }
            
            Timber.d("Loaded ${sessionFolders.size} session folders")
        } catch (e: Exception) {
            Timber.e(e, "Error loading session folders")
            binding.textViewEmpty.apply {
                visibility = android.view.View.VISIBLE
                text = "Error loading session folders"
            }
            binding.recyclerViewSessions.visibility = android.view.View.GONE
        }
    }

    /**
     * Show session folder details and file information
     */
    private fun showSessionDetails(sessionFolder: SessionFolder) {
        try {
            Timber.d("Showing details for session: ${sessionFolder.name}")
            
            // Switch to file view mode
            showSessionFiles(sessionFolder)
                
        } catch (e: Exception) {
            Timber.e(e, "Error showing session details for: ${sessionFolder.name}")
            android.widget.Toast.makeText(
                this, 
                "Error loading session details", 
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * Show individual files within a session
     */
    private fun showSessionFiles(sessionFolder: SessionFolder) {
        // Update action bar title
        supportActionBar?.title = sessionFolder.name
        
        // Create and setup file adapter
        val sessionFileAdapter = SessionFileAdapter(
            onFileClick = { file -> openFile(file) },
            onFileShare = { file -> shareFile(file) },
            onFileDelete = { file -> deleteFile(file, sessionFolder) }
        )
        
        // Update RecyclerView with file adapter
        binding.recyclerViewSessions.adapter = sessionFileAdapter
        sessionFileAdapter.updateFiles(sessionFolder.files)
        
        // Update empty state
        if (sessionFolder.files.isEmpty()) {
            binding.textViewEmpty.apply {
                visibility = android.view.View.VISIBLE
                text = "No files found in this session."
            }
            binding.recyclerViewSessions.visibility = android.view.View.GONE
        } else {
            binding.textViewEmpty.visibility = android.view.View.GONE
            binding.recyclerViewSessions.visibility = android.view.View.VISIBLE
        }
        
        Timber.d("Showing ${sessionFolder.files.size} files for session: ${sessionFolder.name}")
    }
    
    /**
     * Open a file using system default application
     */
    private fun openFile(sessionFile: SessionFile) {
        try {
            val file = java.io.File(sessionFile.path)
            if (!file.exists()) {
                Toast.makeText(this, "File not found: ${sessionFile.name}", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(sessionFile))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "No app available to open ${sessionFile.type.displayName} files",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error opening file: ${sessionFile.name}")
            Toast.makeText(this, "Error opening file", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Share a file using system share dialog
     */
    private fun shareFile(sessionFile: SessionFile) {
        try {
            val file = java.io.File(sessionFile.path)
            if (!file.exists()) {
                Toast.makeText(this, "File not found: ${sessionFile.name}", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(sessionFile)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Session File: ${sessionFile.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share ${sessionFile.name}"))
            
        } catch (e: Exception) {
            Timber.e(e, "Error sharing file: ${sessionFile.name}")
            Toast.makeText(this, "Error sharing file", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Delete a file with confirmation dialog
     */
    private fun deleteFile(sessionFile: SessionFile, sessionFolder: SessionFolder) {
        AlertDialog.Builder(this)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete ${sessionFile.name}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val file = java.io.File(sessionFile.path)
                    if (file.exists() && file.delete()) {
                        Toast.makeText(this, "File deleted successfully", Toast.LENGTH_SHORT).show()
                        // Refresh the file list
                        val updatedSession = sessionFolderManager.getSessionFolder(sessionFolder.name)
                        if (updatedSession != null) {
                            showSessionFiles(updatedSession)
                        }
                    } else {
                        Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error deleting file: ${sessionFile.name}")
                    Toast.makeText(this, "Error deleting file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Get MIME type for a session file
     */
    private fun getMimeType(sessionFile: SessionFile): String {
        return when (sessionFile.type) {
            SessionFileType.AUDIO -> "audio/wav"
            SessionFileType.VIDEO -> "video/mp4"
            SessionFileType.GSR_DATA -> "text/csv"
            SessionFileType.METADATA -> "application/json"
            SessionFileType.THERMAL_YUV,
            SessionFileType.THERMAL_ARGB,
            SessionFileType.THERMAL_RAW,
            SessionFileType.THERMAL_PSEUDO -> "application/octet-stream"
            SessionFileType.UNKNOWN -> "*/*"
        }
    }

    /**
     * Open session folder in file manager
     */
    private fun openSessionFolder(sessionFolder: SessionFolder) {
        try {
            val folderFile = File(sessionFolder.path)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(
                        this@SessionFolderActivity,
                        "${packageName}.fileprovider",
                        folderFile
                    ),
                    "resource/folder"
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback: show path in toast
                android.widget.Toast.makeText(
                    this,
                    "Session folder: ${sessionFolder.path}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error opening session folder: ${sessionFolder.name}")
            android.widget.Toast.makeText(
                this,
                "Error opening folder",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("SessionFolderActivity destroyed")
    }

    companion object {
        private const val TAG = "SessionFolderActivity"
    }
}