package com.multimodal.capture.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.multimodal.capture.R
import com.multimodal.capture.ui.adapters.DeviceListAdapter
import com.multimodal.capture.viewmodel.MainViewModel
import timber.log.Timber

/**
 * Fragment for managing device connections and displaying device status
 * Based on IRCamera app device management patterns
 */
class DeviceManagementFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    
    // Views
    private lateinit var clHasDevice: View
    private lateinit var clNoDevice: View
    private lateinit var recyclerDeviceList: RecyclerView
    private lateinit var btnScanDevices: MaterialButton
    private lateinit var btnConnectDevice: MaterialButton
    
    // Adapter
    private lateinit var deviceAdapter: DeviceListAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_device_management, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        
        Timber.d("[DEBUG_LOG] DeviceManagementFragment initialized")
    }
    
    private fun initViews(view: View) {
        clHasDevice = view.findViewById(R.id.cl_has_device)
        clNoDevice = view.findViewById(R.id.cl_no_device)
        recyclerDeviceList = view.findViewById(R.id.recycler_device_list)
        btnScanDevices = view.findViewById(R.id.btn_scan_devices)
        btnConnectDevice = view.findViewById(R.id.btn_connect_device)
    }
    
    private fun setupRecyclerView() {
        deviceAdapter = DeviceListAdapter(
            onDeviceClick = { device ->
                // Handle device click
                Timber.d("[DEBUG_LOG] Device clicked: ${device.name}")
                handleDeviceClick(device)
            },
            onActionClick = { device ->
                // Handle action button click (Connect/Disconnect)
                Timber.d("[DEBUG_LOG] Action clicked for device: ${device.name}, connected: ${device.isConnected}")
                handleActionClick(device)
            }
        )
        
        recyclerDeviceList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }
    }
    
    private fun setupClickListeners() {
        btnScanDevices.setOnClickListener {
            Timber.d("[DEBUG_LOG] Scan devices button clicked")
            scanForDevices()
        }
        
        btnConnectDevice.setOnClickListener {
            Timber.d("[DEBUG_LOG] Connect device button clicked")
            connectToDevices()
        }
    }
    
    private fun observeViewModel() {
        // Observe device connection states
        viewModel.thermalStatus.observe(viewLifecycleOwner) { status ->
            Timber.d("[DEBUG_LOG] Thermal status updated: $status")
            updateDeviceList()
        }
        
        viewModel.gsrStatus.observe(viewLifecycleOwner) { status ->
            Timber.d("[DEBUG_LOG] GSR status updated: $status")
            updateDeviceList()
        }
        
        // TODO: Add more device status observers as needed
    }
    
    private fun updateDeviceList() {
        val devices = mutableListOf<DeviceItem>()
        
        // Add thermal camera
        val thermalStatus = viewModel.thermalStatus.value ?: "Disconnected"
        val isThermalConnected = thermalStatus.contains("connected", ignoreCase = true) || 
                                thermalStatus.contains("ready", ignoreCase = true)
        devices.add(
            DeviceItem(
                id = "thermal_camera",
                name = "Thermal Camera TC001",
                type = DeviceType.THERMAL_CAMERA,
                isConnected = isThermalConnected,
                status = thermalStatus,
                batteryLevel = null // USB device, no battery
            )
        )
        
        // Add GSR sensor
        val gsrStatus = viewModel.gsrStatus.value ?: "Disconnected"
        val isGsrConnected = gsrStatus.contains("connected", ignoreCase = true) || 
                            gsrStatus.contains("ready", ignoreCase = true)
        devices.add(
            DeviceItem(
                id = "gsr_sensor",
                name = "Shimmer3 GSR+",
                type = DeviceType.GSR_SENSOR,
                isConnected = isGsrConnected,
                status = gsrStatus,
                batteryLevel = if (isGsrConnected) 85 else null // Example battery level for wireless device
            )
        )
        
        // Add audio device (always available)
        val cameraStatus = viewModel.cameraStatus.value ?: "Ready"
        devices.add(
            DeviceItem(
                id = "audio_device",
                name = "Audio Recorder",
                type = DeviceType.AUDIO,
                isConnected = true, // Audio is always available
                status = cameraStatus,
                batteryLevel = null // Built-in device, no battery
            )
        )
        
        // Update UI based on device availability
        val hasConnectedDevices = devices.any { it.isConnected }
        clHasDevice.isVisible = hasConnectedDevices
        clNoDevice.isVisible = !hasConnectedDevices
        
        // Update adapter
        deviceAdapter.updateDevices(devices)
        
        Timber.d("[DEBUG_LOG] Device list updated: ${devices.size} devices, $hasConnectedDevices connected")
    }
    
    private fun scanForDevices() {
        Timber.d("[DEBUG_LOG] Starting device scan...")
        
        // Scan for GSR sensors (Bluetooth devices)
        viewModel.scanForBluetoothDevices()
        
        // Connect to thermal camera
        viewModel.connectToThermalCamera()
        
        // Update device list after scan
        updateDeviceList()
    }
    
    private fun connectToDevices() {
        Timber.d("[DEBUG_LOG] Attempting to connect to devices...")
        
        // Connect to thermal camera
        viewModel.connectToThermalCamera()
        
        // The GSR connection will be handled through the scan process
        scanForDevices()
    }
    
    private fun handleDeviceClick(device: DeviceItem) {
        when (device.type) {
            DeviceType.THERMAL_CAMERA -> {
                if (!device.isConnected) {
                    viewModel.connectToThermalCamera()
                }
            }
            DeviceType.GSR_SENSOR -> {
                if (!device.isConnected) {
                    viewModel.scanForBluetoothDevices()
                }
            }
            DeviceType.AUDIO -> {
                // Audio is always available, no action needed
                Timber.d("[DEBUG_LOG] Audio device is always ready")
            }
        }
    }
    
    private fun handleActionClick(device: DeviceItem) {
        Timber.d("[DEBUG_LOG] Handling action click for: ${device.name}, connected: ${device.isConnected}")
        
        when (device.type) {
            DeviceType.THERMAL_CAMERA -> {
                if (device.isConnected) {
                    Timber.d("[DEBUG_LOG] Disconnecting thermal camera")
                    // TODO: Implement thermal camera disconnection in ViewModel
                    // For now, just log the action - disconnection methods need to be added to ViewModel
                } else {
                    Timber.d("[DEBUG_LOG] Connecting to thermal camera")
                    viewModel.connectToThermalCamera()
                }
            }
            DeviceType.GSR_SENSOR -> {
                if (device.isConnected) {
                    Timber.d("[DEBUG_LOG] Disconnecting GSR sensor")
                    // TODO: Implement GSR sensor disconnection in ViewModel
                    // For now, just log the action - disconnection methods need to be added to ViewModel
                } else {
                    Timber.d("[DEBUG_LOG] Connecting to GSR sensor")
                    viewModel.scanForBluetoothDevices()
                }
            }
            DeviceType.AUDIO -> {
                Timber.d("[DEBUG_LOG] Audio device action - always available, no action needed")
                // Audio is always available, no connect/disconnect action needed
            }
        }
        
        // Update device list to reflect new connection states
        updateDeviceList()
    }
    
    override fun onResume() {
        super.onResume()
        updateDeviceList()
    }
}

// Data classes for device management
data class DeviceItem(
    val id: String,
    val name: String,
    val type: DeviceType,
    val isConnected: Boolean,
    val status: String,
    val batteryLevel: Int? = null
)

enum class DeviceType {
    THERMAL_CAMERA,
    GSR_SENSOR,
    AUDIO
}