package com.multimodal.capture.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multimodal.capture.R
import com.multimodal.capture.databinding.ActivityBluetoothDeviceBinding
import timber.log.Timber

/**
 * BluetoothDeviceActivity handles Bluetooth device scanning and pairing for GSR sensors.
 * Provides a user interface for discovering and connecting to Shimmer devices.
 */
class BluetoothDeviceActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityBluetoothDeviceBinding
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startDeviceDiscovery()
        } else {
            showPermissionError()
        }
    }
    
    // Bluetooth enable request launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndStartScan()
        } else {
            Toast.makeText(this, "Bluetooth is required for GSR sensor connection", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    // Broadcast receiver for device discovery and pairing
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { addDiscoveredDevice(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnScan.text = "Scanning..."
                    binding.btnScan.isEnabled = false
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnScan.text = getString(R.string.bluetooth_scan)
                    binding.btnScan.isEnabled = true
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    
                    device?.let { handleBondStateChange(it, bondState, previousBondState) }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityBluetoothDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupRecyclerView()
        registerDiscoveryReceiver()
        
        // Check if Bluetooth is supported
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        // Check if Bluetooth is enabled
        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            checkPermissionsAndStartScan()
        }
    }
    
    private fun setupUI() {
        // Setup toolbar without setSupportActionBar to avoid conflict with window decor
        binding.toolbar.title = "Bluetooth Devices"
        
        // Use built-in back arrow from Material Design
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Setup scan button
        binding.btnScan.setOnClickListener {
            checkPermissionsAndStartScan()
        }
        
        // Setup paired devices button
        binding.btnShowPaired.setOnClickListener {
            showPairedDevices()
        }
    }
    
    private fun setupRecyclerView() {
        deviceAdapter = BluetoothDeviceAdapter(discoveredDevices) { device ->
            connectToDevice(device)
        }
        
        binding.recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(this@BluetoothDeviceActivity)
            adapter = deviceAdapter
        }
    }
    
    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(discoveryReceiver, filter)
    }
    
    private fun checkPermissionsAndStartScan() {
        val requiredPermissions = mutableListOf<String>()
        
        // Check Bluetooth permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
            != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            startDeviceDiscovery()
        }
    }
    
    private fun startDeviceDiscovery() {
        try {
            // Clear previous results
            discoveredDevices.clear()
            deviceAdapter.notifyDataSetChanged()
            
            // Cancel any ongoing discovery
            bluetoothAdapter?.cancelDiscovery()
            
            // Start discovery
            val started = bluetoothAdapter?.startDiscovery() ?: false
            if (!started) {
                Toast.makeText(this, "Failed to start device discovery", Toast.LENGTH_SHORT).show()
            }
            
            Timber.d("Bluetooth device discovery started")
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during device discovery")
            Toast.makeText(this, "Permission denied for Bluetooth scanning", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPairedDevices() {
        try {
            val pairedDevices = bluetoothAdapter?.bondedDevices
            discoveredDevices.clear()
            
            pairedDevices?.forEach { device ->
                discoveredDevices.add(device)
            }
            
            deviceAdapter.notifyDataSetChanged()
            
            if (discoveredDevices.isEmpty()) {
                Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception accessing paired devices")
            Toast.makeText(this, "Permission denied for accessing paired devices", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun addDiscoveredDevice(device: BluetoothDevice) {
        try {
            // Check if device is already in the list
            if (discoveredDevices.none { it.address == device.address }) {
                // Filter for potential Shimmer devices
                val deviceName = device.name
                if (deviceName != null && (
                    deviceName.contains("Shimmer", ignoreCase = true) ||
                    deviceName.contains("GSR", ignoreCase = true) ||
                    device.address.startsWith("00:06:66") // Shimmer MAC prefix
                )) {
                    discoveredDevices.add(device)
                    deviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
                    
                    Timber.d("Added Shimmer device: ${deviceName} (${device.address})")
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception accessing device info")
        }
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            // Stop discovery to save resources
            bluetoothAdapter?.cancelDiscovery()
            
            // Check if device is already paired
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // Device is already paired, proceed with connection
                Timber.d("Device already paired: ${device.name} (${device.address})")
                proceedWithConnection(device)
            } else {
                // Device not paired, initiate pairing process
                Timber.d("Initiating pairing with device: ${device.name} (${device.address})")
                
                // Show pairing status
                Toast.makeText(this, "Initiating pairing with ${device.name ?: "device"}...", Toast.LENGTH_SHORT).show()
                
                // Start pairing process - this will show the Android system pairing dialog
                val pairingResult = device.createBond()
                
                if (pairingResult) {
                    Timber.d("Pairing initiated successfully for ${device.name}")
                    // The pairing process will continue in the background
                    // The system will show the pairing dialog if needed
                    // We'll handle the result in the BroadcastReceiver
                } else {
                    Timber.w("Failed to initiate pairing for ${device.name}")
                    Toast.makeText(this, "Failed to start pairing process", Toast.LENGTH_SHORT).show()
                }
            }
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception connecting to device")
            Toast.makeText(this, "Permission denied for device connection", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during device connection")
            Toast.makeText(this, "Error connecting to device: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Handle Bluetooth device bond state changes
     */
    private fun handleBondStateChange(device: BluetoothDevice, bondState: Int, previousBondState: Int) {
        try {
            val deviceName = device.name ?: "Unknown Device"
            
            when (bondState) {
                BluetoothDevice.BOND_BONDING -> {
                    Timber.d("Pairing in progress with $deviceName")
                    Toast.makeText(this, "Pairing with $deviceName...", Toast.LENGTH_SHORT).show()
                    // Update UI to show pairing in progress
                    deviceAdapter.notifyDataSetChanged()
                }
                BluetoothDevice.BOND_BONDED -> {
                    Timber.d("Successfully paired with $deviceName")
                    Toast.makeText(this, "Successfully paired with $deviceName", Toast.LENGTH_SHORT).show()
                    // Update UI to show paired status
                    deviceAdapter.notifyDataSetChanged()
                    // Proceed with connection after successful pairing
                    proceedWithConnection(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    if (previousBondState == BluetoothDevice.BOND_BONDING) {
                        Timber.w("Pairing failed with $deviceName")
                        Toast.makeText(this, "Pairing failed with $deviceName", Toast.LENGTH_SHORT).show()
                    } else {
                        Timber.d("Device $deviceName unpaired")
                    }
                    // Update UI to show unpaired status
                    deviceAdapter.notifyDataSetChanged()
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception handling bond state change")
        }
    }
    
    /**
     * Proceed with connection after successful pairing
     */
    private fun proceedWithConnection(device: BluetoothDevice) {
        try {
            // Return device address to calling activity
            val resultIntent = Intent().apply {
                putExtra(EXTRA_DEVICE_ADDRESS, device.address)
                putExtra(EXTRA_DEVICE_NAME, device.name ?: "Unknown Device")
            }
            setResult(RESULT_OK, resultIntent)
            
            Toast.makeText(this, "Connected to ${device.name ?: "device"}", Toast.LENGTH_SHORT).show()
            finish()
            
            Timber.d("Successfully connected to device: ${device.name} (${device.address})")
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during connection finalization")
            Toast.makeText(this, "Permission denied for device connection", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPermissionError() {
        Toast.makeText(
            this,
            "Bluetooth permissions are required to scan for GSR sensors",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            // Cancel discovery with proper permission handling
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Timber.w(e, "Permission denied when canceling discovery")
            }
            
            unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error during cleanup")
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}

/**
 * RecyclerView adapter for Bluetooth devices
 */
class BluetoothDeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder>() {
    
    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: android.widget.TextView = view.findViewById(R.id.tv_device_name)
        val deviceAddress: android.widget.TextView = view.findViewById(R.id.tv_device_address)
        val deviceStatus: android.widget.TextView = view.findViewById(R.id.tv_device_status)
        val connectButton: android.widget.TextView = view.findViewById(R.id.tv_connect)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        try {
            holder.deviceName.text = device.name ?: "Unknown Device"
            holder.deviceAddress.text = device.address
            
            // Update status and connect button based on pairing state
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    holder.deviceStatus.text = "Paired"
                    holder.connectButton.text = "Connect"
                    holder.connectButton.isEnabled = true
                }
                BluetoothDevice.BOND_BONDING -> {
                    holder.deviceStatus.text = "Pairing..."
                    holder.connectButton.text = "Pairing..."
                    holder.connectButton.isEnabled = false
                }
                else -> {
                    holder.deviceStatus.text = "Not Paired"
                    holder.connectButton.text = "Pair & Connect"
                    holder.connectButton.isEnabled = true
                }
            }
            
            // Set click listener on the connect button
            holder.connectButton.setOnClickListener {
                onDeviceClick(device)
            }
            
            // Also keep the item click for backward compatibility
            holder.itemView.setOnClickListener {
                onDeviceClick(device)
            }
            
        } catch (e: SecurityException) {
            holder.deviceName.text = "Permission Required"
            holder.deviceAddress.text = device.address
            holder.deviceStatus.text = "Access Denied"
            holder.connectButton.text = "No Permission"
            holder.connectButton.isEnabled = false
        }
    }
    
    override fun getItemCount(): Int = devices.size
}