package com.multimodal.capture.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.multimodal.capture.R
import com.multimodal.capture.export.DataExportManager
import com.multimodal.capture.ui.GSRGraphActivity
import com.multimodal.capture.ui.SessionFolderActivity
import com.multimodal.capture.ui.SettingsActivity
import com.multimodal.capture.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Fragment for settings and export functionality
 * Based on IRCamera app settings and export patterns
 */
class SettingsExportFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    
    // Views
    private lateinit var switchGsrDemo: SwitchCompat
    private lateinit var btnOpenSettings: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var btnExportJson: MaterialButton
    private lateinit var btnExportAnalysis: MaterialButton
    private lateinit var btnViewSessions: MaterialButton
    private lateinit var btnGsrGraph: MaterialButton
    
    // Export manager
    private lateinit var dataExportManager: DataExportManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_export, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        initializeExportManager()
        setupClickListeners()
        loadSettings()
        
        Timber.d("[DEBUG_LOG] SettingsExportFragment initialized")
    }
    
    private fun initViews(view: View) {
        switchGsrDemo = view.findViewById(R.id.switch_gsr_demo)
        btnOpenSettings = view.findViewById(R.id.btn_open_settings)
        btnExportCsv = view.findViewById(R.id.btn_export_csv)
        btnExportJson = view.findViewById(R.id.btn_export_json)
        btnExportAnalysis = view.findViewById(R.id.btn_export_analysis)
        btnViewSessions = view.findViewById(R.id.btn_view_sessions)
        btnGsrGraph = view.findViewById(R.id.btn_gsr_graph)
    }
    
    private fun initializeExportManager() {
        dataExportManager = DataExportManager(requireContext())
    }
    
    private fun setupClickListeners() {
        // GSR Demo Mode Toggle
        switchGsrDemo.setOnCheckedChangeListener { _, isChecked ->
            Timber.d("[DEBUG_LOG] GSR demo mode toggled: $isChecked")
            saveGsrDemoMode(isChecked)
        }
        
        // Settings Button
        btnOpenSettings.setOnClickListener {
            Timber.d("[DEBUG_LOG] Open settings button clicked")
            openSettingsActivity()
        }
        
        // Export Buttons
        btnExportCsv.setOnClickListener {
            Timber.d("[DEBUG_LOG] Export CSV button clicked")
            exportData("csv")
        }
        
        btnExportJson.setOnClickListener {
            Timber.d("[DEBUG_LOG] Export JSON button clicked")
            exportData("json")
        }
        
        btnExportAnalysis.setOnClickListener {
            Timber.d("[DEBUG_LOG] Export analysis button clicked")
            exportData("analysis")
        }
        
        // Session Management Buttons
        btnViewSessions.setOnClickListener {
            Timber.d("[DEBUG_LOG] View sessions button clicked")
            openSessionFolderActivity()
        }
        
        btnGsrGraph.setOnClickListener {
            Timber.d("[DEBUG_LOG] GSR graph button clicked")
            openGsrGraphActivity()
        }
    }
    
    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isDemoMode = prefs.getBoolean("gsr_demo_mode", true)
        switchGsrDemo.isChecked = isDemoMode
        
        Timber.d("[DEBUG_LOG] Settings loaded: GSR demo mode = $isDemoMode")
    }
    
    private fun saveGsrDemoMode(isEnabled: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putBoolean("gsr_demo_mode", isEnabled).apply()
        
        Timber.d("[DEBUG_LOG] GSR demo mode saved: $isEnabled")
    }
    
    private fun openSettingsActivity() {
        try {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to open settings activity")
        }
    }
    
    private fun openSessionFolderActivity() {
        try {
            val intent = Intent(requireContext(), SessionFolderActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to open session folder activity")
        }
    }
    
    private fun openGsrGraphActivity() {
        try {
            val intent = Intent(requireContext(), GSRGraphActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Failed to open GSR graph activity")
        }
    }
    
    private fun exportData(format: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Timber.d("[DEBUG_LOG] Starting data export: format=$format")
                
                // TODO: Implement proper export functionality with DataExportManager
                // For now, create placeholder functionality
                val result = when (format) {
                    "csv" -> {
                        Timber.d("[DEBUG_LOG] CSV export placeholder")
                        // TODO: Call dataExportManager.exportToCSV() with proper parameters
                        true
                    }
                    "json" -> {
                        Timber.d("[DEBUG_LOG] JSON export placeholder")
                        // TODO: Call dataExportManager.exportToJSON() with proper parameters
                        true
                    }
                    "analysis" -> {
                        Timber.d("[DEBUG_LOG] Analysis export placeholder")
                        // TODO: Implement analysis report export
                        true
                    }
                    else -> {
                        Timber.w("[DEBUG_LOG] Unknown export format: $format")
                        false
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (result) {
                        Timber.d("[DEBUG_LOG] Export successful: $format")
                        showExportSuccess(format)
                    } else {
                        Timber.e("[DEBUG_LOG] Export failed: $format")
                        showExportError(format)
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "[DEBUG_LOG] Export error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showExportError(format)
                }
            }
        }
    }
    
    private fun showExportSuccess(format: String) {
        // TODO: Show success message to user (could use Snackbar or Toast)
        Timber.d("[DEBUG_LOG] Export completed successfully: $format")
    }
    
    private fun showExportError(format: String) {
        // TODO: Show error message to user (could use Snackbar or Toast)
        Timber.e("[DEBUG_LOG] Export failed: $format")
    }
    
    override fun onResume() {
        super.onResume()
        // Reload settings when fragment becomes visible
        loadSettings()
    }
}