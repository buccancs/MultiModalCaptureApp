package com.multimodal.capture.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.multimodal.capture.R

/**
 * Custom bottom navigation view with percentage-based layout and dark theme styling
 * Based on IRCamera app navigation patterns
 */
class CustomBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // Tab containers
    private val tabDevices: ConstraintLayout
    private val tabCapture: ConstraintLayout
    private val tabSessions: ConstraintLayout

    // Tab icons
    private val iconDevices: ImageView
    private val iconCapture: ImageView
    private val iconSessions: ImageView

    // Tab texts
    private val textDevices: TextView
    private val textCapture: TextView
    private val textSessions: TextView

    // Colors for tab states
    private val colorSelected = ContextCompat.getColor(context, android.R.color.holo_blue_bright) // #3182ce equivalent
    private val colorUnselected = ContextCompat.getColor(context, android.R.color.darker_gray) // #a0aec0 equivalent

    // Current selected tab
    private var currentSelectedTab = 1 // Default to main capture (center tab)

    // Tab selection listener
    var onTabSelectedListener: ((Int) -> Unit)? = null

    init {
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.component_custom_bottom_nav, this, true)

        // Initialize views
        tabDevices = findViewById(R.id.tab_devices)
        tabCapture = findViewById(R.id.tab_capture)
        tabSessions = findViewById(R.id.tab_sessions)

        iconDevices = findViewById(R.id.icon_devices)
        iconCapture = findViewById(R.id.icon_capture)
        iconSessions = findViewById(R.id.icon_sessions)

        textDevices = findViewById(R.id.text_devices)
        textCapture = findViewById(R.id.text_capture)
        textSessions = findViewById(R.id.text_sessions)

        // Set up click listeners
        setupClickListeners()

        // Set initial selected state
        updateTabSelection(currentSelectedTab)
    }

    private fun setupClickListeners() {
        tabDevices.setOnClickListener {
            selectTab(0)
        }

        tabCapture.setOnClickListener {
            selectTab(1)
        }

        tabSessions.setOnClickListener {
            selectTab(2)
        }
    }

    /**
     * Select a tab by index
     * @param tabIndex 0 = Devices, 1 = Capture, 2 = Sessions
     */
    fun selectTab(tabIndex: Int) {
        if (tabIndex != currentSelectedTab) {
            currentSelectedTab = tabIndex
            updateTabSelection(tabIndex)
            onTabSelectedListener?.invoke(tabIndex)
        }
    }

    /**
     * Update the visual state of tabs based on selection
     */
    private fun updateTabSelection(selectedIndex: Int) {
        // Reset all tabs to unselected state
        setTabState(iconDevices, textDevices, false)
        setTabState(iconCapture, textCapture, false)
        setTabState(iconSessions, textSessions, false)

        // Set selected tab state
        when (selectedIndex) {
            0 -> setTabState(iconDevices, textDevices, true)
            1 -> setTabState(iconCapture, textCapture, true)
            2 -> setTabState(iconSessions, textSessions, true)
        }
    }

    /**
     * Set the visual state of a tab (selected/unselected)
     */
    private fun setTabState(icon: ImageView, text: TextView, isSelected: Boolean) {
        val color = if (isSelected) colorSelected else colorUnselected
        icon.setColorFilter(color)
        text.setTextColor(color)
    }

    /**
     * Get the currently selected tab index
     */
    fun getCurrentSelectedTab(): Int = currentSelectedTab

    /**
     * Programmatically set the selected tab without triggering the listener
     */
    fun setSelectedTabSilently(tabIndex: Int) {
        currentSelectedTab = tabIndex
        updateTabSelection(tabIndex)
    }
}