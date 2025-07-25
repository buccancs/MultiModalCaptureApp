package com.multimodal.capture.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.multimodal.capture.R
import com.multimodal.capture.ui.components.StatusIndicatorView
import com.multimodal.capture.ui.fragments.DeviceItem
import com.multimodal.capture.ui.fragments.DeviceType

/**
 * Adapter for displaying device list in RecyclerView
 * Based on IRCamera app device management patterns
 */
class DeviceListAdapter(
    private val onDeviceClick: (DeviceItem) -> Unit,
    private val onActionClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private var devices = listOf<DeviceItem>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    fun updateDevices(newDevices: List<DeviceItem>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconDeviceType: ImageView = itemView.findViewById(R.id.icon_device_type)
        private val textDeviceName: TextView = itemView.findViewById(R.id.text_device_name)
        private val statusDevice: StatusIndicatorView = itemView.findViewById(R.id.status_device)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)
        private val batteryContainer: View = itemView.findViewById(R.id.battery_container)
        private val iconBattery: ImageView = itemView.findViewById(R.id.icon_battery)
        private val textBatteryLevel: TextView = itemView.findViewById(R.id.text_battery_level)

        fun bind(device: DeviceItem) {
            // Set device name
            textDeviceName.text = device.name

            // Set device type icon
            val iconRes = when (device.type) {
                DeviceType.THERMAL_CAMERA -> R.drawable.ic_camera
                DeviceType.GSR_SENSOR -> R.drawable.ic_devices
                DeviceType.AUDIO -> R.drawable.ic_mic
            }
            iconDeviceType.setImageResource(iconRes)

            // Set device status using StatusIndicatorView
            val statusType = if (device.isConnected) {
                StatusIndicatorView.Status.CONNECTED
            } else {
                StatusIndicatorView.Status.DISCONNECTED
            }
            
            statusDevice.setStatus(statusType, device.status, iconRes)

            // Set action button text and styling
            if (device.isConnected) {
                actionButton.text = "Disconnect"
                actionButton.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.warning_color)
            } else {
                actionButton.text = "Connect"
                actionButton.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.accent_primary)
            }

            // Set action button click listener
            actionButton.setOnClickListener {
                onActionClick(device)
            }


            // Set device type icon tint
            val iconTint = if (device.isConnected) {
                ContextCompat.getColor(itemView.context, R.color.accent_primary)
            } else {
                ContextCompat.getColor(itemView.context, R.color.text_tertiary)
            }
            iconDeviceType.setColorFilter(iconTint)

            // Handle battery level for wireless devices
            if (device.batteryLevel != null) {
                batteryContainer.isVisible = true
                textBatteryLevel.text = "${device.batteryLevel}%"
                
                // Set battery icon color based on level
                val batteryColor = when {
                    device.batteryLevel > 50 -> ContextCompat.getColor(itemView.context, R.color.battery_high)
                    device.batteryLevel > 20 -> ContextCompat.getColor(itemView.context, R.color.battery_medium)
                    else -> ContextCompat.getColor(itemView.context, R.color.battery_low)
                }
                iconBattery.setColorFilter(batteryColor)
                textBatteryLevel.setTextColor(batteryColor)
            } else {
                batteryContainer.isVisible = false
            }

            // Set click listener
            itemView.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}