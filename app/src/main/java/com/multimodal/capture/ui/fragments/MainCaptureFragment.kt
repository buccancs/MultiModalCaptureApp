package com.multimodal.capture.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.multimodal.capture.R
import com.multimodal.capture.ui.PreviewActivity
import com.multimodal.capture.ui.components.ThermalPreviewView
import com.multimodal.capture.ui.components.StatusIndicatorView
import com.multimodal.capture.viewmodel.MainViewModel
import timber.log.Timber

/**
 * Fragment for main capture interface with enhanced preview management
 * Based on IRCamera app capture interface patterns
 */
class MainCaptureFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    
    // Views
    private lateinit var cameraPreview: PreviewView
    private lateinit var thermalPreview: ThermalPreviewView
    private lateinit var btnTogglePreview: FloatingActionButton
    private lateinit var btnRecord: MaterialButton
    private lateinit var textPreviewMode: TextView
    private lateinit var recordingStatusContainer: View
    private lateinit var statusCamera: StatusIndicatorView
    private lateinit var statusThermal: StatusIndicatorView
    private lateinit var statusGsr: StatusIndicatorView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main_capture, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupClickListeners()
        observeViewModel()
        setupPreviews()
        
        Timber.d("[DEBUG_LOG] MainCaptureFragment initialized")
    }
    
    private fun initViews(view: View) {
        cameraPreview = view.findViewById(R.id.camera_preview)
        thermalPreview = view.findViewById(R.id.thermal_preview)
        btnTogglePreview = view.findViewById(R.id.btn_toggle_preview)
        btnRecord = view.findViewById(R.id.btn_record)
        textPreviewMode = view.findViewById(R.id.text_preview_mode)
        recordingStatusContainer = view.findViewById(R.id.recording_status_container)
        statusCamera = view.findViewById(R.id.status_camera)
        statusThermal = view.findViewById(R.id.status_thermal)
        statusGsr = view.findViewById(R.id.status_gsr)
    }
    
    private fun setupClickListeners() {
        btnTogglePreview.setOnClickListener {
            Timber.d("[DEBUG_LOG] Preview button clicked - navigating to PreviewActivity")
            val previewIntent = Intent(requireContext(), PreviewActivity::class.java)
            startActivity(previewIntent)
        }
        
        btnRecord.setOnClickListener {
            Timber.d("[DEBUG_LOG] Record button clicked")
            handleRecordButtonClick()
        }
    }
    
    private fun observeViewModel() {
        // Observe recording state
        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            updateRecordingUI(isRecording)
        }
        
        // Observe preview mode
        viewModel.previewMode.observe(viewLifecycleOwner) { previewMode ->
            updatePreviewMode(previewMode)
        }
        
        // Observe status updates
        viewModel.cameraStatus.observe(viewLifecycleOwner) { status ->
            updateCameraStatus(status)
        }
        
        viewModel.thermalStatus.observe(viewLifecycleOwner) { status ->
            updateThermalStatus(status)
        }
        
        viewModel.gsrStatus.observe(viewLifecycleOwner) { status ->
            updateGsrStatus(status)
        }
        
        // Observe errors
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error.isNotEmpty()) {
                Timber.e("[DEBUG_LOG] Error: $error")
                // TODO: Show error to user (could add Snackbar or Toast)
            }
        }
    }
    
    private fun setupPreviews() {
        // Setup camera preview
        viewModel.setupCameraPreview(cameraPreview)
        
        // Setup thermal preview
        viewModel.setThermalPreviewView(thermalPreview)
        
        Timber.d("[DEBUG_LOG] Previews setup completed")
    }
    
    private fun handleRecordButtonClick() {
        val isCurrentlyRecording = viewModel.isRecording.value ?: false
        
        if (isCurrentlyRecording) {
            // Stop recording
            viewModel.stopRecording()
        } else {
            // Start recording
            val sessionId = "session_${System.currentTimeMillis()}"
            val startTimestamp = System.currentTimeMillis()
            viewModel.startRecording(sessionId, startTimestamp)
        }
    }
    
    private fun updateRecordingUI(isRecording: Boolean) {
        if (isRecording) {
            btnRecord.text = getString(R.string.stop_recording)
            btnRecord.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.recording_active)
            recordingStatusContainer.isVisible = true
        } else {
            btnRecord.text = getString(R.string.start_recording)
            btnRecord.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent_primary)
            recordingStatusContainer.isVisible = false
        }
        
        Timber.d("[DEBUG_LOG] Recording UI updated: isRecording=$isRecording")
    }
    
    private fun updatePreviewMode(previewMode: String) {
        textPreviewMode.text = previewMode
        
        val isThermalMode = previewMode.contains("thermal", ignoreCase = true)
        
        if (isThermalMode) {
            cameraPreview.isVisible = false
            thermalPreview.isVisible = true
            btnTogglePreview.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_color))
        } else {
            cameraPreview.isVisible = true
            thermalPreview.isVisible = false
            btnTogglePreview.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent_primary))
        }
        
        Timber.d("[DEBUG_LOG] Preview mode updated: $previewMode")
    }
    
    private fun updateCameraStatus(status: String) {
        val isConnected = status.contains("ready", ignoreCase = true) || 
                         status.contains("connected", ignoreCase = true)
        
        val statusType = if (isConnected) {
            StatusIndicatorView.Status.CONNECTED
        } else {
            StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusCamera.setStatus(statusType, status, R.drawable.ic_camera)
    }
    
    private fun updateThermalStatus(status: String) {
        val isConnected = status.contains("connected", ignoreCase = true) || 
                         status.contains("ready", ignoreCase = true)
        
        val statusType = if (isConnected) {
            StatusIndicatorView.Status.CONNECTED
        } else {
            StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusThermal.setStatus(statusType, status, R.drawable.ic_thermal)
    }
    
    private fun updateGsrStatus(status: String) {
        val isConnected = status.contains("connected", ignoreCase = true) || 
                         status.contains("ready", ignoreCase = true)
        
        val statusType = if (isConnected) {
            StatusIndicatorView.Status.CONNECTED
        } else {
            StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusGsr.setStatus(statusType, status, R.drawable.ic_sensor)
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh previews when fragment becomes visible
        setupPreviews()
    }
}