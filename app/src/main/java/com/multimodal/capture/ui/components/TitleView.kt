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
 * Custom TitleView component for standardized headers across activities
 * Based on IRCamera app TitleView patterns with configurable actions
 */
class TitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // Views
    private val leftIcon: ImageView
    private val titleText: TextView
    private val rightIcon: ImageView
    private val rightIcon2: ImageView

    // Click listeners
    var onLeftIconClickListener: (() -> Unit)? = null
    var onRightIconClickListener: (() -> Unit)? = null
    var onRightIcon2ClickListener: (() -> Unit)? = null

    init {
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.component_title_view, this, true)

        // Initialize views
        leftIcon = findViewById(R.id.icon_left)
        titleText = findViewById(R.id.text_title)
        rightIcon = findViewById(R.id.icon_right)
        rightIcon2 = findViewById(R.id.icon_right2)

        // Set up click listeners
        setupClickListeners()

        // Apply custom attributes if provided
        attrs?.let { applyAttributes(it) }
    }

    private fun setupClickListeners() {
        leftIcon.setOnClickListener {
            onLeftIconClickListener?.invoke()
        }

        rightIcon.setOnClickListener {
            onRightIconClickListener?.invoke()
        }

        rightIcon2.setOnClickListener {
            onRightIcon2ClickListener?.invoke()
        }
    }

    private fun applyAttributes(attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TitleView)
        
        try {
            // Title text
            val title = typedArray.getString(R.styleable.TitleView_titleText)
            if (!title.isNullOrEmpty()) {
                setTitle(title)
            }

            // Left icon
            val leftDrawable = typedArray.getResourceId(R.styleable.TitleView_leftDrawable, 0)
            if (leftDrawable != 0) {
                setLeftIcon(leftDrawable)
            }

            // Right icon
            val rightDrawable = typedArray.getResourceId(R.styleable.TitleView_rightDrawable, 0)
            if (rightDrawable != 0) {
                setRightIcon(rightDrawable)
            }

            // Right icon 2
            val rightDrawable2 = typedArray.getResourceId(R.styleable.TitleView_rightDrawable2, 0)
            if (rightDrawable2 != 0) {
                setRightIcon2(rightDrawable2)
            }

            // Show/hide left icon
            val showLeftIcon = typedArray.getBoolean(R.styleable.TitleView_showLeftIcon, true)
            setLeftIconVisible(showLeftIcon)

        } finally {
            typedArray.recycle()
        }
    }

    /**
     * Set the title text
     */
    fun setTitle(title: String) {
        titleText.text = title
    }

    /**
     * Set the left icon drawable
     */
    fun setLeftIcon(drawableRes: Int) {
        leftIcon.setImageResource(drawableRes)
        leftIcon.isVisible = true
    }

    /**
     * Set the right icon drawable
     */
    fun setRightIcon(drawableRes: Int) {
        rightIcon.setImageResource(drawableRes)
        rightIcon.isVisible = true
    }

    /**
     * Set the second right icon drawable
     */
    fun setRightIcon2(drawableRes: Int) {
        rightIcon2.setImageResource(drawableRes)
        rightIcon2.isVisible = true
    }

    /**
     * Show/hide the left icon
     */
    fun setLeftIconVisible(visible: Boolean) {
        leftIcon.isVisible = visible
    }

    /**
     * Show/hide the right icon
     */
    fun setRightIconVisible(visible: Boolean) {
        rightIcon.isVisible = visible
    }

    /**
     * Show/hide the second right icon
     */
    fun setRightIcon2Visible(visible: Boolean) {
        rightIcon2.isVisible = visible
    }

    /**
     * Set the title text color
     */
    fun setTitleTextColor(colorRes: Int) {
        titleText.setTextColor(ContextCompat.getColor(context, colorRes))
    }

    /**
     * Set icon tint color
     */
    fun setIconTint(colorRes: Int) {
        val color = ContextCompat.getColor(context, colorRes)
        leftIcon.setColorFilter(color)
        rightIcon.setColorFilter(color)
        rightIcon2.setColorFilter(color)
    }

    /**
     * Enable/disable the title view
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        leftIcon.isEnabled = enabled
        rightIcon.isEnabled = enabled
        rightIcon2.isEnabled = enabled
        titleText.isEnabled = enabled
        
        val alpha = if (enabled) 1.0f else 0.5f
        leftIcon.alpha = alpha
        rightIcon.alpha = alpha
        rightIcon2.alpha = alpha
        titleText.alpha = alpha
    }
}