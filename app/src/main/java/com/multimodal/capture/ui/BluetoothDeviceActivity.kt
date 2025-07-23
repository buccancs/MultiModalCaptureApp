package com.multimodal.capture.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
        BluetoothAdapter.getDefaultAdapter()
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
    
    // Broadcast receiver for device discovery
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
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
        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bluetooth Devices"
        
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
            
            // Return device address to calling activity
            val resultIntent = Intent().apply {
                putExtra(EXTRA_DEVICE_ADDRESS, device.address)
                putExtra(EXTRA_DEVICE_NAME, device.name ?: "Unknown Device")
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            
            Timber.d("Selected device: ${device.name} (${device.address})")
            
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception connecting to device")
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
            bluetoothAdapter?.cancelDiscovery()
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
            holder.deviceStatus.text = when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "Paired"
                BluetoothDevice.BOND_BONDING -> "Pairing..."
                else -> "Not Paired"
            }
            
            holder.itemView.setOnClickListener {
                onDeviceClick(device)
            }
            
        } catch (e: SecurityException) {
            holder.deviceName.text = "Permission Required"
            holder.deviceAddress.text = device.address
            holder.deviceStatus.text = "Access Denied"
        }
    }
    
    override fun getItemCount(): Int = devices.size
}