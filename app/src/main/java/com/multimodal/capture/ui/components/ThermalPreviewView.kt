package com.multimodal.capture.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.multimodal.capture.R
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import kotlin.math.*

/**
 * Enhanced thermal preview view with temperature measurement capabilities
 * Based on IRCamera TemperatureView implementation
 */
class ThermalPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ThermalPreviewView"
        
        // Temperature region modes
        const val REGION_MODE_CLEAN = 0
        const val REGION_MODE_POINT = 1
        const val REGION_MODE_LINE = 2
        const val REGION_MODE_RECTANGLE = 3
        const val REGION_MODE_CENTER = 4
        
        // Pseudocolor types
        const val WHITE_HOT_MODE = 0
        const val BLACK_HOT_MODE = 1
        const val RAINBOW_MODE = 2
        const val IRON_MODE = 3
        const val LAVA_MODE = 4
    }

    // Temperature measurement properties
    var temperatureRegionMode = REGION_MODE_CLEAN
        set(value) {
            field = value
            invalidateTemperatureOverlay()
        }
    
    var canTouch = true
    var textSize = 24f
    var lineColor = Color.WHITE
    
    // Temperature data
    private var thermalData: ByteArray? = null
    private var imageWidth = 256
    private var imageHeight = 192
    private var maxTemp = 0f
    private var minTemp = 0f
    private var centerTemp = 0f
    
    // Drawing properties
    private val paint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 3f
        textSize = this@ThermalPreviewView.textSize
        color = lineColor
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = this@ThermalPreviewView.textSize
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    // Touch handling
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false
    
    // Pseudocolor palette
    private var pseudocolorPalette: IntArray? = null
    private var currentPseudocolorMode = WHITE_HOT_MODE
    
    // Temperature listener
    var temperatureListener: ((max: Float, min: Float, center: Float) -> Unit)? = null
    
    // Surface drawing
    private var surfaceCanvas: Canvas? = null
    private var backgroundBitmap: Bitmap? = null

    init {
        holder.addCallback(this)
        loadDefaultPseudocolor()
        Timber.d("[DEBUG_LOG] ThermalPreviewView initialized")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Timber.d("[DEBUG_LOG] ThermalPreviewView surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Timber.d("[DEBUG_LOG] ThermalPreviewView surface changed: ${width}x${height}")
        backgroundBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Timber.d("[DEBUG_LOG] ThermalPreviewView surface destroyed")
        backgroundBitmap?.recycle()
        backgroundBitmap = null
    }

    /**
     * Update thermal preview with new frame data
     */
    fun updateThermalFrame(frameData: ByteArray, width: Int = 256, height: Int = 192) {
        thermalData = frameData
        imageWidth = width
        imageHeight = height
        
        // Process thermal data and update display
        processThermalData()
        drawThermalFrame()
    }

    /**
     * Set pseudocolor mode for thermal display
     */
    fun setPseudocolorMode(mode: Int) {
        currentPseudocolorMode = mode
        loadPseudocolorPalette(mode)
        // Redraw with new palette
        thermalData?.let { drawThermalFrame() }
    }

    /**
     * Load pseudocolor palette from assets
     */
    private fun loadPseudocolorPalette(mode: Int) {
        try {
            val assetName = when (mode) {
                WHITE_HOT_MODE -> "pseudocolor/White_Hot.bin"
                BLACK_HOT_MODE -> "pseudocolor/Black_Hot.bin"
                RAINBOW_MODE -> "pseudocolor/Rainbow.bin"
                IRON_MODE -> "pseudocolor/Iron.bin"
                LAVA_MODE -> "pseudocolor/Lava.bin"
                else -> "pseudocolor/White_Hot.bin"
            }
            
            val inputStream: InputStream = context.assets.open(assetName)
            val paletteData = ByteArray(inputStream.available())
            inputStream.read(paletteData)
            inputStream.close()
            
            // Convert palette data to color array
            pseudocolorPalette = convertPaletteToColors(paletteData)
            
            Timber.d("[DEBUG_LOG] Loaded pseudocolor palette: $assetName")
        } catch (e: IOException) {
            Timber.w(e, "[DEBUG_LOG] Failed to load pseudocolor palette, using default")
            loadDefaultPseudocolor()
        }
    }

    /**
     * Load default white hot pseudocolor
     */
    private fun loadDefaultPseudocolor() {
        // Create simple white hot palette
        pseudocolorPalette = IntArray(256) { i ->
            val intensity = i
            Color.rgb(intensity, intensity, intensity)
        }
    }

    /**
     * Convert palette binary data to color array
     */
    private fun convertPaletteToColors(paletteData: ByteArray): IntArray {
        val colors = IntArray(256)
        
        // Assuming RGB palette format (3 bytes per color)
        for (i in 0 until 256) {
            val baseIndex = i * 3
            if (baseIndex + 2 < paletteData.size) {
                val r = paletteData[baseIndex].toInt() and 0xFF
                val g = paletteData[baseIndex + 1].toInt() and 0xFF
                val b = paletteData[baseIndex + 2].toInt() and 0xFF
                colors[i] = Color.rgb(r, g, b)
            } else {
                colors[i] = Color.WHITE
            }
        }
        
        return colors
    }

    /**
     * Process thermal data to extract temperature information
     */
    private fun processThermalData() {
        val data = thermalData ?: return
        val palette = pseudocolorPalette ?: return
        
        if (data.size < imageWidth * imageHeight * 2) {
            Timber.w("[DEBUG_LOG] Insufficient thermal data for processing")
            return
        }
        
        var max = Float.MIN_VALUE
        var min = Float.MAX_VALUE
        var sum = 0f
        var count = 0
        
        // Process 16-bit thermal values
        for (i in 0 until imageWidth * imageHeight) {
            val byteIndex = i * 2
            if (byteIndex + 1 < data.size) {
                val thermalValue = ((data[byteIndex].toInt() and 0xFF) or 
                                  ((data[byteIndex + 1].toInt() and 0xFF) shl 8))
                
                // Convert to temperature (simplified conversion)
                val temperature = convertRawToTemperature(thermalValue)
                
                max = maxOf(max, temperature)
                min = minOf(min, temperature)
                sum += temperature
                count++
            }
        }
        
        maxTemp = max
        minTemp = min
        centerTemp = if (count > 0) sum / count else 0f
        
        // Calculate center point temperature
        val centerIndex = (imageHeight / 2) * imageWidth + (imageWidth / 2)
        if (centerIndex * 2 + 1 < data.size) {
            val centerRaw = ((data[centerIndex * 2].toInt() and 0xFF) or 
                           ((data[centerIndex * 2 + 1].toInt() and 0xFF) shl 8))
            centerTemp = convertRawToTemperature(centerRaw)
        }
        
        // Notify temperature listener
        temperatureListener?.invoke(maxTemp, minTemp, centerTemp)
    }

    /**
     * Convert raw thermal value to temperature in Celsius
     */
    private fun convertRawToTemperature(rawValue: Int): Float {
        // TODO: Implement proper temperature conversion based on camera calibration
        // This is a simplified conversion for demonstration
        return (rawValue.toFloat() / 65535.0f) * 100.0f - 20.0f
    }

    /**
     * Draw thermal frame with pseudocolor
     */
    private fun drawThermalFrame() {
        val data = thermalData ?: return
        val palette = pseudocolorPalette ?: return
        val bitmap = backgroundBitmap ?: return
        
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            
            // Create thermal bitmap with pseudocolor
            val thermalBitmap = createThermalBitmap(data, palette)
            if (thermalBitmap != null) {
                // Scale thermal bitmap to fit surface
                val scaledBitmap = Bitmap.createScaledBitmap(
                    thermalBitmap, 
                    bitmap.width, 
                    bitmap.height, 
                    false
                )
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                scaledBitmap.recycle()
                thermalBitmap.recycle()
            }
            
            // Draw temperature overlay
            drawTemperatureOverlay(canvas)
            
            // Draw to surface
            val surfaceHolder = holder
            if (surfaceHolder.surface.isValid) {
                val surfaceCanvas = surfaceHolder.lockCanvas()
                surfaceCanvas?.let { sc ->
                    sc.drawBitmap(bitmap, 0f, 0f, null)
                    surfaceHolder.unlockCanvasAndPost(sc)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "[DEBUG_LOG] Error drawing thermal frame")
        }
    }

    /**
     * Create thermal bitmap with pseudocolor palette
     */
    private fun createThermalBitmap(data: ByteArray, palette: IntArray): Bitmap? {
        if (data.size < imageWidth * imageHeight * 2) return null
        
        val pixels = IntArray(imageWidth * imageHeight)
        
        for (i in 0 until imageWidth * imageHeight) {
            val byteIndex = i * 2
            if (byteIndex + 1 < data.size) {
                val thermalValue = ((data[byteIndex].toInt() and 0xFF) or 
                                  ((data[byteIndex + 1].toInt() and 0xFF) shl 8))
                
                // Map to palette index (0-255)
                val paletteIndex = ((thermalValue.toFloat() / 65535.0f) * 255.0f).toInt().coerceIn(0, 255)
                pixels[i] = palette[paletteIndex]
            }
        }
        
        return Bitmap.createBitmap(pixels, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
    }

    /**
     * Draw temperature measurement overlay
     */
    private fun drawTemperatureOverlay(canvas: Canvas) {
        when (temperatureRegionMode) {
            REGION_MODE_POINT -> drawPointTemperature(canvas)
            REGION_MODE_LINE -> drawLineTemperature(canvas)
            REGION_MODE_RECTANGLE -> drawRectangleTemperature(canvas)
            REGION_MODE_CENTER -> drawCenterTemperature(canvas)
        }
    }

    private fun drawPointTemperature(canvas: Canvas) {
        if (startX > 0 && startY > 0) {
            canvas.drawCircle(startX, startY, 10f, paint)
            canvas.drawText("${centerTemp.toInt()}°C", startX + 15, startY - 15, textPaint)
        }
    }

    private fun drawLineTemperature(canvas: Canvas) {
        if (startX > 0 && startY > 0 && endX > 0 && endY > 0) {
            canvas.drawLine(startX, startY, endX, endY, paint)
            val midX = (startX + endX) / 2
            val midY = (startY + endY) / 2
            canvas.drawText("Max: ${maxTemp.toInt()}°C", midX, midY - 20, textPaint)
            canvas.drawText("Min: ${minTemp.toInt()}°C", midX, midY + 20, textPaint)
        }
    }

    private fun drawRectangleTemperature(canvas: Canvas) {
        if (startX > 0 && startY > 0 && endX > 0 && endY > 0) {
            canvas.drawRect(startX, startY, endX, endY, paint)
            canvas.drawText("Max: ${maxTemp.toInt()}°C", startX, startY - 30, textPaint)
            canvas.drawText("Min: ${minTemp.toInt()}°C", startX, startY - 10, textPaint)
        }
    }

    private fun drawCenterTemperature(canvas: Canvas) {
        val centerX = canvas.width / 2f
        val centerY = canvas.height / 2f
        canvas.drawCircle(centerX, centerY, 15f, paint)
        canvas.drawText("${centerTemp.toInt()}°C", centerX + 20, centerY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canTouch) return false
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isDrawing = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    endX = event.x
                    endY = event.y
                    invalidateTemperatureOverlay()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x
                endY = event.y
                isDrawing = false
                invalidateTemperatureOverlay()
                return true
            }
        }
        
        return super.onTouchEvent(event)
    }

    private fun invalidateTemperatureOverlay() {
        thermalData?.let { drawThermalFrame() }
    }

    /**
     * Clear temperature measurements
     */
    fun clearTemperatureMeasurements() {
        temperatureRegionMode = REGION_MODE_CLEAN
        startX = 0f
        startY = 0f
        endX = 0f
        endY = 0f
        invalidateTemperatureOverlay()
    }

    /**
     * Set image size for thermal data
     */
    fun setImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        Timber.d("[DEBUG_LOG] Thermal image size set to: ${width}x${height}")
    }
}