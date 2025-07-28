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
import com.multimodal.capture.ui.viewmodel.MainViewModel
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
                com.google.android.material.snackbar.Snackbar.make(
                    requireView(),
                    error,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
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
            // Pre-flight guard: Check sensor status before starting recording
            val cameraStatus = viewModel.cameraStatus.value ?: ""
            val thermalStatus = viewModel.thermalStatus.value ?: ""
            val gsrStatus = viewModel.gsrStatus.value ?: ""
            
            val isCameraReady = cameraStatus.contains("ready", ignoreCase = true) || 
                               cameraStatus.contains("connected", ignoreCase = true)
            val isThermalReady = thermalStatus.contains("ready", ignoreCase = true) || 
                                thermalStatus.contains("connected", ignoreCase = true)
            val isGsrReady = gsrStatus.contains("ready", ignoreCase = true) || 
                            gsrStatus.contains("connected", ignoreCase = true)
            
            when {
                !isCameraReady -> {
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Camera is not ready. Please ensure camera is connected and ready.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                    return
                }
                !isThermalReady -> {
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Thermal camera is not ready. Please ensure thermal camera is connected.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                    return
                }
                !isGsrReady -> {
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "GSR sensor is not ready. Please ensure GSR sensor is connected.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                    return
                }
                else -> {
                    // All sensors ready - start recording
                    val sessionId = "session_${System.currentTimeMillis()}"
                    val startTimestamp = System.currentTimeMillis()
                    viewModel.startRecording(sessionId, startTimestamp)
                }
            }
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
        val statusType = when {
            status.contains("ready", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("connected", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("streaming", ignoreCase = true) -> StatusIndicatorView.Status.STREAMING
            status.contains("error", ignoreCase = true) -> StatusIndicatorView.Status.ERROR
            status.contains("connecting", ignoreCase = true) -> StatusIndicatorView.Status.CONNECTING
            else -> StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusCamera.setStatus(statusType, status, R.drawable.ic_camera)
    }
    
    private fun updateThermalStatus(status: String) {
        val statusType = when {
            status.contains("ready", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("connected", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("streaming", ignoreCase = true) -> StatusIndicatorView.Status.STREAMING
            status.contains("error", ignoreCase = true) -> StatusIndicatorView.Status.ERROR
            status.contains("connecting", ignoreCase = true) -> StatusIndicatorView.Status.CONNECTING
            else -> StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusThermal.setStatus(statusType, status, R.drawable.ic_thermal)
    }
    
    private fun updateGsrStatus(status: String) {
        val statusType = when {
            status.contains("ready", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("connected", ignoreCase = true) -> StatusIndicatorView.Status.READY
            status.contains("streaming", ignoreCase = true) -> StatusIndicatorView.Status.STREAMING
            status.contains("error", ignoreCase = true) -> StatusIndicatorView.Status.ERROR
            status.contains("connecting", ignoreCase = true) -> StatusIndicatorView.Status.CONNECTING
            else -> StatusIndicatorView.Status.DISCONNECTED
        }
        
        statusGsr.setStatus(statusType, status, R.drawable.ic_sensor)
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh previews when fragment becomes visible
        setupPreviews()
    }
}