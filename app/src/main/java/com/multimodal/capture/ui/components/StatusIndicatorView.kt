package com.multimodal.capture.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.multimodal.capture.R
import com.multimodal.capture.databinding.ComponentStatusIndicatorBinding

class StatusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: ComponentStatusIndicatorBinding

    enum class Status {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        DISABLED
    }

    init {
        binding = ComponentStatusIndicatorBinding.inflate(LayoutInflater.from(context), this, true)
    }

    fun setStatus(status: Status, text: String, iconResId: Int) {
        binding.statusText.text = text
        binding.statusIcon.setImageResource(iconResId)

        val colorRes = when (status) {
            Status.CONNECTED -> R.color.device_connected
            Status.DISCONNECTED -> R.color.device_disconnected
            Status.CONNECTING -> R.color.device_connecting
            Status.DISABLED -> R.color.text_disabled
        }
        val color = ContextCompat.getColor(context, colorRes)

        binding.statusIndicatorDot.background.setTint(color)
        binding.statusText.setTextColor(color)
        binding.statusIcon.setColorFilter(color)
    }
}