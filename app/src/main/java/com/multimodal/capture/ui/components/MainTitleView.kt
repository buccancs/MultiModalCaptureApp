package com.multimodal.capture.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.multimodal.capture.R

/**
 * Enhanced MainTitleView component with dual-tab support and multiple action buttons
 * Based on IRCamera app MainTitleView patterns with Temperature/Observe modes
 */
class MainTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // Views
    private val leftActionIcon: ImageView
    private val dualTabContainer: ConstraintLayout
    private val tabTemperature: TextView
    private val tabObserve: TextView
    private val singleTitleText: TextView
    private val rightActionIcon2: ImageView
    private val rightActionIcon: ImageView

    // Click listeners
    var onLeftActionClickListener: (() -> Unit)? = null
    var onRightActionClickListener: (() -> Unit)? = null
    var onRightAction2ClickListener: (() -> Unit)? = null
    var onTemperatureTabClickListener: (() -> Unit)? = null
    var onObserveTabClickListener: (() -> Unit)? = null

    // Tab state
    private var isTemperatureTabSelected = true

    init {
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.component_main_title_view, this, true)

        // Initialize views
        leftActionIcon = findViewById(R.id.icon_left_action)
        dualTabContainer = findViewById(R.id.dual_tab_container)
        tabTemperature = findViewById(R.id.tab_temperature)
        tabObserve = findViewById(R.id.tab_observe)
        singleTitleText = findViewById(R.id.text_single_title)
        rightActionIcon2 = findViewById(R.id.icon_right_action2)
        rightActionIcon = findViewById(R.id.icon_right_action)

        // Set up click listeners
        setupClickListeners()

        // Apply custom attributes if provided
        attrs?.let { applyAttributes(it) }
    }

    private fun setupClickListeners() {
        leftActionIcon.setOnClickListener {
            onLeftActionClickListener?.invoke()
        }

        rightActionIcon.setOnClickListener {
            onRightActionClickListener?.invoke()
        }

        rightActionIcon2.setOnClickListener {
            onRightAction2ClickListener?.invoke()
        }

        tabTemperature.setOnClickListener {
            selectTemperatureTab()
            onTemperatureTabClickListener?.invoke()
        }

        tabObserve.setOnClickListener {
            selectObserveTab()
            onObserveTabClickListener?.invoke()
        }
    }

    private fun applyAttributes(attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.MainTitleView)
        
        try {
            // Primary title
            val primaryTitle = typedArray.getString(R.styleable.MainTitleView_primaryTitle)
            if (!primaryTitle.isNullOrEmpty()) {
                setSingleTitle(primaryTitle)
            }

            // Secondary title (for observe tab)
            val secondaryTitle = typedArray.getString(R.styleable.MainTitleView_secondaryTitle)
            if (!secondaryTitle.isNullOrEmpty()) {
                setObserveTabText(secondaryTitle)
            }

            // Show dual tabs
            val showDualTabs = typedArray.getBoolean(R.styleable.MainTitleView_showDualTabs, false)
            setDualTabsVisible(showDualTabs)

            // Left action drawable
            val leftActionDrawable = typedArray.getResourceId(R.styleable.MainTitleView_leftActionDrawable, 0)
            if (leftActionDrawable != 0) {
                setLeftActionIcon(leftActionDrawable)
            }

            // Right action drawable
            val rightActionDrawable = typedArray.getResourceId(R.styleable.MainTitleView_rightActionDrawable, 0)
            if (rightActionDrawable != 0) {
                setRightActionIcon(rightActionDrawable)
            }

            // Right action 2 drawable
            val rightAction2Drawable = typedArray.getResourceId(R.styleable.MainTitleView_rightAction2Drawable, 0)
            if (rightAction2Drawable != 0) {
                setRightAction2Icon(rightAction2Drawable)
            }

        } finally {
            typedArray.recycle()
        }
    }

    /**
     * Set single title text (when dual tabs are not shown)
     */
    fun setSingleTitle(title: String) {
        singleTitleText.text = title
    }

    /**
     * Set temperature tab text
     */
    fun setTemperatureTabText(text: String) {
        tabTemperature.text = text
    }

    /**
     * Set observe tab text
     */
    fun setObserveTabText(text: String) {
        tabObserve.text = text
    }

    /**
     * Show/hide dual tabs
     */
    fun setDualTabsVisible(visible: Boolean) {
        dualTabContainer.isVisible = visible
        singleTitleText.isVisible = !visible
    }

    /**
     * Select temperature tab
     */
    fun selectTemperatureTab() {
        if (!isTemperatureTabSelected) {
            isTemperatureTabSelected = true
            updateTabSelection()
        }
    }

    /**
     * Select observe tab
     */
    fun selectObserveTab() {
        if (isTemperatureTabSelected) {
            isTemperatureTabSelected = false
            updateTabSelection()
        }
    }

    /**
     * Update tab selection visual state
     */
    private fun updateTabSelection() {
        if (isTemperatureTabSelected) {
            // Temperature tab selected
            tabTemperature.setBackgroundResource(R.drawable.bg_tab_selected)
            tabTemperature.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            tabTemperature.setTypeface(null, android.graphics.Typeface.BOLD)
            
            // Observe tab unselected
            tabObserve.setBackgroundResource(R.drawable.bg_tab_unselected)
            tabObserve.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            tabObserve.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            // Observe tab selected
            tabObserve.setBackgroundResource(R.drawable.bg_tab_selected)
            tabObserve.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            tabObserve.setTypeface(null, android.graphics.Typeface.BOLD)
            
            // Temperature tab unselected
            tabTemperature.setBackgroundResource(R.drawable.bg_tab_unselected)
            tabTemperature.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            tabTemperature.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    /**
     * Set left action icon
     */
    fun setLeftActionIcon(drawableRes: Int) {
        leftActionIcon.setImageResource(drawableRes)
        leftActionIcon.isVisible = true
    }

    /**
     * Set right action icon
     */
    fun setRightActionIcon(drawableRes: Int) {
        rightActionIcon.setImageResource(drawableRes)
        rightActionIcon.isVisible = true
    }

    /**
     * Set right action 2 icon
     */
    fun setRightAction2Icon(drawableRes: Int) {
        rightActionIcon2.setImageResource(drawableRes)
        rightActionIcon2.isVisible = true
    }

    /**
     * Show/hide left action icon
     */
    fun setLeftActionVisible(visible: Boolean) {
        leftActionIcon.isVisible = visible
    }

    /**
     * Show/hide right action icon
     */
    fun setRightActionVisible(visible: Boolean) {
        rightActionIcon.isVisible = visible
    }

    /**
     * Show/hide right action 2 icon
     */
    fun setRightAction2Visible(visible: Boolean) {
        rightActionIcon2.isVisible = visible
    }

    /**
     * Set icon tint color
     */
    fun setIconTint(colorRes: Int) {
        val color = ContextCompat.getColor(context, colorRes)
        leftActionIcon.setColorFilter(color)
        rightActionIcon.setColorFilter(color)
        rightActionIcon2.setColorFilter(color)
    }

    /**
     * Get currently selected tab
     */
    fun isTemperatureTabSelected(): Boolean = isTemperatureTabSelected

    /**
     * Enable/disable the main title view
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        leftActionIcon.isEnabled = enabled
        rightActionIcon.isEnabled = enabled
        rightActionIcon2.isEnabled = enabled
        tabTemperature.isEnabled = enabled
        tabObserve.isEnabled = enabled
        singleTitleText.isEnabled = enabled
        
        val alpha = if (enabled) 1.0f else 0.5f
        leftActionIcon.alpha = alpha
        rightActionIcon.alpha = alpha
        rightActionIcon2.alpha = alpha
        tabTemperature.alpha = alpha
        tabObserve.alpha = alpha
        singleTitleText.alpha = alpha
    }
}