package com.multimodal.capture.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.multimodal.capture.R
import com.multimodal.capture.data.GSRDataPoint
import timber.log.Timber
import kotlin.math.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Enhanced Shimmer sensor data visualization view with real-time GSR display
 * Based on thermal camera enhancements with advanced interaction capabilities
 */
class ShimmerDataView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ShimmerDataView"
        
        // Display modes
        const val DISPLAY_MODE_REALTIME = 0
        const val DISPLAY_MODE_HISTORY = 1
        const val DISPLAY_MODE_STATISTICS = 2
        
        // Time ranges
        const val TIME_RANGE_30_SECONDS = 30000L
        const val TIME_RANGE_1_MINUTE = 60000L
        const val TIME_RANGE_5_MINUTES = 300000L
        const val TIME_RANGE_10_MINUTES = 600000L
        
        // Graph types
        const val GRAPH_TYPE_LINE = 0
        const val GRAPH_TYPE_BAR = 1
        const val GRAPH_TYPE_AREA = 2
        
        // Color schemes
        const val COLOR_SCHEME_DEFAULT = 0
        const val COLOR_SCHEME_MEDICAL = 1
        const val COLOR_SCHEME_HIGH_CONTRAST = 2
    }

    // Data storage
    private val gsrDataQueue = ConcurrentLinkedQueue<GSRDataPoint>()
    private val heartRateDataQueue = ConcurrentLinkedQueue<Pair<Long, Int>>()
    private val prrDataQueue = ConcurrentLinkedQueue<Pair<Long, Double>>()
    
    // Display properties
    private var displayMode = DISPLAY_MODE_REALTIME
    private var timeRange = TIME_RANGE_1_MINUTE
    private var graphType = GRAPH_TYPE_LINE
    private var colorScheme = COLOR_SCHEME_DEFAULT
    
    // Graph dimensions and layout
    private var graphRect = RectF()
    private var legendRect = RectF()
    private var statsRect = RectF()
    private var margin = 40f
    private var legendHeight = 120f
    private var statsHeight = 80f
    
    // Data ranges
    private var gsrMin = 0.0
    private var gsrMax = 100.0
    private var heartRateMin = 40
    private var heartRateMax = 200
    private var prrMin = 0.0
    private var prrMax = 100.0
    
    // Current values
    private var currentGSR = 0.0
    private var currentHeartRate = 0
    private var currentPRR = 0.0
    private var lastUpdateTime = 0L
    
    // Statistics
    private var gsrAverage = 0.0
    private var gsrStdDev = 0.0
    private var heartRateAverage = 0
    private var dataPointCount = 0
    
    // Paint objects for different elements
    private val gsrPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    
    private val heartRatePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    private val prrPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = 24f
        color = Color.WHITE
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
        alpha = 128
    }
    
    // Touch handling
    private var isInteracting = false
    private var touchX = 0f
    private var touchY = 0f
    private var selectedDataPoint: GSRDataPoint? = null
    
    // Callbacks
    var dataUpdateCallback: ((GSRDataPoint) -> Unit)? = null
    var statisticsCallback: ((Double, Double, Int, Double) -> Unit)? = null
    var interactionCallback: ((GSRDataPoint?) -> Unit)? = null
    
    // Surface drawing
    private var surfaceCanvas: Canvas? = null
    private var backgroundBitmap: Bitmap? = null

    init {
        holder.addCallback(this)
        setupColorScheme()
        Timber.d("[DEBUG_LOG] ShimmerDataView initialized")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Timber.d("[DEBUG_LOG] ShimmerDataView surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Timber.d("[DEBUG_LOG] ShimmerDataView surface changed: ${width}x${height}")
        backgroundBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        calculateLayout(width, height)
        redrawGraph()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Timber.d("[DEBUG_LOG] ShimmerDataView surface destroyed")
        backgroundBitmap?.recycle()
        backgroundBitmap = null
    }

    private fun calculateLayout(width: Int, height: Int) {
        // Calculate graph area
        graphRect.set(
            margin,
            margin,
            width - margin,
            height - margin - legendHeight - statsHeight
        )
        
        // Calculate legend area
        legendRect.set(
            margin,
            graphRect.bottom + 10f,
            width - margin,
            graphRect.bottom + 10f + legendHeight
        )
        
        // Calculate statistics area
        statsRect.set(
            margin,
            legendRect.bottom + 10f,
            width - margin,
            height - margin
        )
    }

    private fun setupColorScheme() {
        when (colorScheme) {
            COLOR_SCHEME_DEFAULT -> {
                gsrPaint.color = Color.GREEN
                heartRatePaint.color = Color.RED
                prrPaint.color = Color.BLUE
                textPaint.color = Color.WHITE
            }
            COLOR_SCHEME_MEDICAL -> {
                gsrPaint.color = Color.rgb(0, 150, 255) // Medical blue
                heartRatePaint.color = Color.rgb(255, 100, 100) // Medical red
                prrPaint.color = Color.rgb(255, 200, 0) // Medical yellow
                textPaint.color = Color.WHITE
            }
            COLOR_SCHEME_HIGH_CONTRAST -> {
                gsrPaint.color = Color.YELLOW
                heartRatePaint.color = Color.MAGENTA
                prrPaint.color = Color.CYAN
                textPaint.color = Color.WHITE
            }
        }
    }

    /**
     * Add new GSR data point
     */
    fun addGSRData(gsrValue: Double, heartRate: Int, prr: Double, timestamp: Long = System.currentTimeMillis(), sessionId: String = "current") {
        val shimmerTimestamp = timestamp / 1_000_000 // Convert to shimmer time scale
        val ppgValue = heartRate * 10.0 // Approximate PPG from heart rate
        val dataPoint = GSRDataPoint(
            timestamp = timestamp * 1_000_000, // Convert to nanoseconds
            shimmerTimestamp = shimmerTimestamp,
            gsrValue = gsrValue,
            ppgValue = ppgValue,
            packetReceptionRate = prr,
            sessionId = sessionId
        )
        
        // Add to queues
        gsrDataQueue.offer(dataPoint)
        heartRateDataQueue.offer(Pair(timestamp, heartRate))
        prrDataQueue.offer(Pair(timestamp, prr))
        
        // Update current values
        currentGSR = gsrValue
        currentHeartRate = heartRate
        currentPRR = prr
        lastUpdateTime = timestamp
        
        // Clean old data based on time range
        cleanOldData()
        
        // Update statistics
        updateStatistics()
        
        // Redraw graph
        redrawGraph()
        
        // Notify callbacks
        dataUpdateCallback?.invoke(dataPoint)
        statisticsCallback?.invoke(gsrAverage, gsrStdDev, heartRateAverage, currentPRR)
        
        Timber.v("[DEBUG_LOG] Added GSR data: GSR=$gsrValue, HR=$heartRate, PRR=$prr")
    }

    private fun cleanOldData() {
        val cutoffTime = System.currentTimeMillis() - timeRange
        
        // Clean GSR data
        while (gsrDataQueue.isNotEmpty() && gsrDataQueue.peek()?.timestamp ?: 0L < cutoffTime) {
            gsrDataQueue.poll()
        }
        
        // Clean heart rate data
        while (heartRateDataQueue.isNotEmpty() && heartRateDataQueue.peek()?.first ?: 0L < cutoffTime) {
            heartRateDataQueue.poll()
        }
        
        // Clean PRR data
        while (prrDataQueue.isNotEmpty() && prrDataQueue.peek()?.first ?: 0L < cutoffTime) {
            prrDataQueue.poll()
        }
    }

    private fun updateStatistics() {
        val gsrValues = gsrDataQueue.map { it.gsrValue }
        val heartRateValues = heartRateDataQueue.map { it.second }
        
        if (gsrValues.isNotEmpty()) {
            gsrAverage = gsrValues.average()
            val variance = gsrValues.map { (it - gsrAverage).pow(2) }.average()
            gsrStdDev = sqrt(variance)
            
            gsrMin = gsrValues.minOrNull() ?: 0.0
            gsrMax = gsrValues.maxOrNull() ?: 100.0
        }
        
        if (heartRateValues.isNotEmpty()) {
            heartRateAverage = heartRateValues.average().toInt()
            heartRateMin = heartRateValues.minOrNull() ?: 40
            heartRateMax = heartRateValues.maxOrNull() ?: 200
        }
        
        dataPointCount = gsrDataQueue.size
    }

    private fun redrawGraph() {
        val bitmap = backgroundBitmap ?: return
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), backgroundPaint)
        
        // Draw grid
        drawGrid(canvas)
        
        // Draw data based on display mode
        when (displayMode) {
            DISPLAY_MODE_REALTIME -> drawRealtimeData(canvas)
            DISPLAY_MODE_HISTORY -> drawHistoryData(canvas)
            DISPLAY_MODE_STATISTICS -> drawStatistics(canvas)
        }
        
        // Draw legend
        drawLegend(canvas)
        
        // Draw current values
        drawCurrentValues(canvas)
        
        // Draw interaction overlay
        if (isInteracting) {
            drawInteractionOverlay(canvas)
        }
        
        // Draw to surface
        val surfaceHolder = holder
        if (surfaceHolder.surface.isValid) {
            val surfaceCanvas = surfaceHolder.lockCanvas()
            surfaceCanvas?.let { sc ->
                sc.drawBitmap(bitmap, 0f, 0f, null)
                surfaceHolder.unlockCanvasAndPost(sc)
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // Vertical grid lines
        val verticalLines = 10
        for (i in 0..verticalLines) {
            val x = graphRect.left + (graphRect.width() / verticalLines) * i
            canvas.drawLine(x, graphRect.top, x, graphRect.bottom, gridPaint)
        }
        
        // Horizontal grid lines
        val horizontalLines = 8
        for (i in 0..horizontalLines) {
            val y = graphRect.top + (graphRect.height() / horizontalLines) * i
            canvas.drawLine(graphRect.left, y, graphRect.right, y, gridPaint)
        }
        
        // Draw axes labels
        textPaint.textSize = 20f
        canvas.drawText("Time", graphRect.centerX() - 20f, graphRect.bottom + 30f, textPaint)
        
        canvas.save()
        canvas.rotate(-90f, graphRect.left - 30f, graphRect.centerY())
        canvas.drawText("GSR Value", graphRect.left - 30f, graphRect.centerY(), textPaint)
        canvas.restore()
    }

    private fun drawRealtimeData(canvas: Canvas) {
        when (graphType) {
            GRAPH_TYPE_LINE -> drawLineGraph(canvas)
            GRAPH_TYPE_BAR -> drawBarGraph(canvas)
            GRAPH_TYPE_AREA -> drawAreaGraph(canvas)
        }
    }

    private fun drawLineGraph(canvas: Canvas) {
        val gsrData = gsrDataQueue.toList()
        if (gsrData.size < 2) return
        
        val path = Path()
        var isFirst = true
        
        for (dataPoint in gsrData) {
            val x = mapTimeToX(dataPoint.timestamp)
            val y = mapGSRToY(dataPoint.gsrValue)
            
            if (isFirst) {
                path.moveTo(x, y)
                isFirst = false
            } else {
                path.lineTo(x, y)
            }
        }
        
        canvas.drawPath(path, gsrPaint)
        
        // Draw heart rate overlay
        drawHeartRateOverlay(canvas)
    }

    private fun drawBarGraph(canvas: Canvas) {
        val gsrData = gsrDataQueue.toList()
        if (gsrData.isEmpty()) return
        
        val barWidth = graphRect.width() / maxOf(gsrData.size, 1)
        
        for ((index, dataPoint) in gsrData.withIndex()) {
            val x = graphRect.left + index * barWidth
            val y = mapGSRToY(dataPoint.gsrValue)
            
            canvas.drawRect(x, y, x + barWidth - 2f, graphRect.bottom, gsrPaint)
        }
    }

    private fun drawAreaGraph(canvas: Canvas) {
        val gsrData = gsrDataQueue.toList()
        if (gsrData.size < 2) return
        
        val path = Path()
        path.moveTo(mapTimeToX(gsrData.first().timestamp), graphRect.bottom)
        
        for (dataPoint in gsrData) {
            val x = mapTimeToX(dataPoint.timestamp)
            val y = mapGSRToY(dataPoint.gsrValue)
            path.lineTo(x, y)
        }
        
        path.lineTo(mapTimeToX(gsrData.last().timestamp), graphRect.bottom)
        path.close()
        
        val areaPaint = Paint(gsrPaint).apply {
            style = Paint.Style.FILL
            alpha = 100
        }
        
        canvas.drawPath(path, areaPaint)
        canvas.drawPath(path, gsrPaint)
    }

    private fun drawHeartRateOverlay(canvas: Canvas) {
        val heartRateData = heartRateDataQueue.toList()
        if (heartRateData.size < 2) return
        
        val path = Path()
        var isFirst = true
        
        for ((timestamp, heartRate) in heartRateData) {
            val x = mapTimeToX(timestamp)
            val y = mapHeartRateToY(heartRate)
            
            if (isFirst) {
                path.moveTo(x, y)
                isFirst = false
            } else {
                path.lineTo(x, y)
            }
        }
        
        canvas.drawPath(path, heartRatePaint)
    }

    private fun drawHistoryData(canvas: Canvas) {
        // TODO: Implement historical data view with scrolling capability
        drawRealtimeData(canvas)
        
        // Add history indicator
        textPaint.textSize = 16f
        canvas.drawText("History Mode", graphRect.left, graphRect.top - 10f, textPaint)
    }

    private fun drawStatistics(canvas: Canvas) {
        textPaint.textSize = 24f
        val startY = graphRect.top + 50f
        val lineHeight = 40f
        
        canvas.drawText("GSR Statistics", graphRect.left, startY, textPaint)
        canvas.drawText("Average: ${String.format("%.2f", gsrAverage)}", graphRect.left, startY + lineHeight, textPaint)
        canvas.drawText("Std Dev: ${String.format("%.2f", gsrStdDev)}", graphRect.left, startY + lineHeight * 2, textPaint)
        canvas.drawText("Min: ${String.format("%.2f", gsrMin)}", graphRect.left, startY + lineHeight * 3, textPaint)
        canvas.drawText("Max: ${String.format("%.2f", gsrMax)}", graphRect.left, startY + lineHeight * 4, textPaint)
        
        canvas.drawText("Heart Rate Statistics", graphRect.left, startY + lineHeight * 6, textPaint)
        canvas.drawText("Average: $heartRateAverage BPM", graphRect.left, startY + lineHeight * 7, textPaint)
        canvas.drawText("Range: $heartRateMin - $heartRateMax BPM", graphRect.left, startY + lineHeight * 8, textPaint)
        
        canvas.drawText("Data Points: $dataPointCount", graphRect.left, startY + lineHeight * 10, textPaint)
    }

    private fun drawLegend(canvas: Canvas) {
        textPaint.textSize = 18f
        val legendY = legendRect.top + 30f
        
        // GSR legend
        canvas.drawLine(legendRect.left, legendY, legendRect.left + 30f, legendY, gsrPaint)
        canvas.drawText("GSR", legendRect.left + 40f, legendY + 5f, textPaint)
        
        // Heart Rate legend
        canvas.drawLine(legendRect.left, legendY + 30f, legendRect.left + 30f, legendY + 30f, heartRatePaint)
        canvas.drawText("Heart Rate", legendRect.left + 40f, legendY + 35f, textPaint)
        
        // PRR legend
        canvas.drawLine(legendRect.left, legendY + 60f, legendRect.left + 30f, legendY + 60f, prrPaint)
        canvas.drawText("PRR", legendRect.left + 40f, legendY + 65f, textPaint)
    }

    private fun drawCurrentValues(canvas: Canvas) {
        textPaint.textSize = 20f
        val valuesY = statsRect.top + 30f
        
        canvas.drawText("Current Values:", statsRect.left, valuesY, textPaint)
        canvas.drawText("GSR: ${String.format("%.2f", currentGSR)}", statsRect.left, valuesY + 25f, textPaint)
        canvas.drawText("HR: $currentHeartRate BPM", statsRect.left + 150f, valuesY + 25f, textPaint)
        canvas.drawText("PRR: ${String.format("%.1f", currentPRR)}%", statsRect.left + 300f, valuesY + 25f, textPaint)
    }

    private fun drawInteractionOverlay(canvas: Canvas) {
        if (selectedDataPoint != null) {
            val dataPoint = selectedDataPoint!!
            val x = mapTimeToX(dataPoint.timestamp)
            val y = mapGSRToY(dataPoint.gsrValue)
            
            // Draw selection circle
            val selectionPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(x, y, 10f, selectionPaint)
            
            // Draw data tooltip
            val estimatedHeartRate = (dataPoint.ppgValue / 10.0).toInt() // Reverse the approximation
            val tooltipPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                alpha = 200
            }
            
            val tooltipRect = RectF(x + 15f, y - 60f, x + 200f, y - 10f)
            canvas.drawRect(tooltipRect, tooltipPaint)
            
            textPaint.textSize = 14f
            canvas.drawText("GSR: ${String.format("%.2f", dataPoint.gsrValue)}", x + 20f, y - 45f, textPaint)
            canvas.drawText("HR: $estimatedHeartRate", x + 20f, y - 30f, textPaint)
            canvas.drawText("PRR: ${String.format("%.1f", dataPoint.packetReceptionRate)}%", x + 20f, y - 15f, textPaint)
        }
    }

    private fun mapTimeToX(timestamp: Long): Float {
        val currentTime = System.currentTimeMillis()
        val timeRange = this.timeRange
        val relativeTime = (timestamp - (currentTime - timeRange)).toFloat()
        return graphRect.left + (relativeTime / timeRange) * graphRect.width()
    }

    private fun mapGSRToY(gsrValue: Double): Float {
        val range = gsrMax - gsrMin
        val normalizedValue = ((gsrValue - gsrMin) / range).coerceIn(0.0, 1.0)
        return graphRect.bottom - (normalizedValue * graphRect.height()).toFloat()
    }

    private fun mapHeartRateToY(heartRate: Int): Float {
        val range = heartRateMax - heartRateMin
        val normalizedValue = ((heartRate - heartRateMin).toDouble() / range).coerceIn(0.0, 1.0)
        return graphRect.bottom - (normalizedValue * graphRect.height()).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                isInteracting = true
                
                // Find nearest data point
                selectedDataPoint = findNearestDataPoint(touchX, touchY)
                interactionCallback?.invoke(selectedDataPoint)
                
                redrawGraph()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                
                selectedDataPoint = findNearestDataPoint(touchX, touchY)
                interactionCallback?.invoke(selectedDataPoint)
                
                redrawGraph()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isInteracting = false
                selectedDataPoint = null
                redrawGraph()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestDataPoint(x: Float, y: Float): GSRDataPoint? {
        if (!graphRect.contains(x, y)) return null
        
        var nearestPoint: GSRDataPoint? = null
        var minDistance = Float.MAX_VALUE
        
        for (dataPoint in gsrDataQueue) {
            val pointX = mapTimeToX(dataPoint.timestamp)
            val pointY = mapGSRToY(dataPoint.gsrValue)
            
            val distance = sqrt((x - pointX).pow(2) + (y - pointY).pow(2))
            if (distance < minDistance && distance < 50f) {
                minDistance = distance
                nearestPoint = dataPoint
            }
        }
        
        return nearestPoint
    }

    /**
     * Set display mode
     */
    fun setDisplayMode(mode: Int) {
        displayMode = mode
        redrawGraph()
        Timber.d("[DEBUG_LOG] Display mode set to: $mode")
    }

    /**
     * Set time range for data display
     */
    fun setTimeRange(range: Long) {
        timeRange = range
        cleanOldData()
        redrawGraph()
        Timber.d("[DEBUG_LOG] Time range set to: ${range}ms")
    }

    /**
     * Set graph type
     */
    fun setGraphType(type: Int) {
        graphType = type
        redrawGraph()
        Timber.d("[DEBUG_LOG] Graph type set to: $type")
    }

    /**
     * Set color scheme
     */
    fun setColorScheme(scheme: Int) {
        colorScheme = scheme
        setupColorScheme()
        redrawGraph()
        Timber.d("[DEBUG_LOG] Color scheme set to: $scheme")
    }

    /**
     * Clear all data
     */
    fun clearData() {
        gsrDataQueue.clear()
        heartRateDataQueue.clear()
        prrDataQueue.clear()
        
        currentGSR = 0.0
        currentHeartRate = 0
        currentPRR = 0.0
        
        updateStatistics()
        redrawGraph()
        
        Timber.d("[DEBUG_LOG] All data cleared")
    }

    /**
     * Get current statistics
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "gsrAverage" to gsrAverage,
            "gsrStdDev" to gsrStdDev,
            "gsrMin" to gsrMin,
            "gsrMax" to gsrMax,
            "heartRateAverage" to heartRateAverage,
            "heartRateMin" to heartRateMin,
            "heartRateMax" to heartRateMax,
            "dataPointCount" to dataPointCount,
            "currentGSR" to currentGSR,
            "currentHeartRate" to currentHeartRate,
            "currentPRR" to currentPRR
        )
    }

    /**
     * Export data as list
     */
    fun exportData(): List<GSRDataPoint> {
        return gsrDataQueue.toList()
    }
}