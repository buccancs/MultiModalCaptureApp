package com.multimodal.capture.ui

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.multimodal.capture.R
import com.multimodal.capture.data.GSRDataPoint
import com.multimodal.capture.databinding.ActivityGsrGraphBinding
import com.multimodal.capture.utils.SessionFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * GSRGraphActivity displays GSR sensor data in a time-series graph format.
 * 
 * Features:
 * - Real-time GSR data visualization
 * - Time-based X-axis with proper formatting
 * - Zoom and pan capabilities
 * - Session data loading and display
 * - Export functionality for graph data
 */
class GSRGraphActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityGsrGraphBinding
    private lateinit var sessionFolderManager: SessionFolderManager
    private var gsrDataPoints = mutableListOf<GSRDataPoint>()
    private var sessionId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityGsrGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "GSR Data Graph"
        }
        
        sessionFolderManager = SessionFolderManager(this)
        
        // Get session ID from intent if provided
        sessionId = intent.getStringExtra("session_id")
        
        setupChart()
        setupUI()
        loadGSRData()
        
        Timber.d("GSRGraphActivity created")
    }
    
    /**
     * Set up the line chart with proper configuration
     */
    private fun setupChart() {
        binding.chartGsr.apply {
            // Chart description
            description = Description().apply {
                text = "GSR Values Over Time"
                textSize = 12f
                textColor = Color.GRAY
            }
            
            // Enable touch gestures
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            
            // X-axis configuration
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 1f
                textColor = Color.BLACK
                valueFormatter = TimeAxisValueFormatter()
            }
            
            // Y-axis configuration
            axisLeft.apply {
                setDrawGridLines(true)
                textColor = Color.BLACK
                axisMinimum = 0f
            }
            
            axisRight.isEnabled = false
            
            // Legend
            legend.apply {
                isEnabled = true
                textColor = Color.BLACK
            }
            
            // Animation
            animateX(1000)
        }
    }
    
    /**
     * Set up UI components and event listeners
     */
    private fun setupUI() {
        binding.apply {
            // Refresh button
            buttonRefresh.setOnClickListener {
                loadGSRData()
            }
            
            // Export button
            buttonExport.setOnClickListener {
                exportGSRData()
            }
            
            // Time range selector
            spinnerTimeRange.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    updateChartTimeRange(position)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
        }
    }
    
    /**
     * Load GSR data from storage or current session
     */
    private fun loadGSRData() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = android.view.View.VISIBLE
                binding.textStatus.text = "Loading GSR data..."
                
                val dataPoints = withContext(Dispatchers.IO) {
                    if (sessionId != null) {
                        loadSessionGSRData(sessionId!!)
                    } else {
                        loadLatestGSRData()
                    }
                }
                
                gsrDataPoints.clear()
                gsrDataPoints.addAll(dataPoints)
                
                updateChart()
                updateStatistics()
                
                binding.textStatus.text = "Loaded ${gsrDataPoints.size} data points"
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to load GSR data")
                binding.textStatus.text = "Error loading GSR data: ${e.message}"
                Toast.makeText(this@GSRGraphActivity, "Failed to load GSR data", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }
    
    /**
     * Load GSR data for a specific session
     */
    private suspend fun loadSessionGSRData(sessionId: String): List<GSRDataPoint> {
        val sessionFolders = sessionFolderManager.getSessionFolders()
        val targetSession = sessionFolders.find { it.name.contains(sessionId) }
        
        if (targetSession != null) {
            val gsrFile = targetSession.files.find { it.name.contains("gsr_data") && it.name.endsWith(".csv") }
            if (gsrFile != null) {
                return parseGSRDataFromFile(File(gsrFile.path))
            }
        }
        
        return emptyList()
    }
    
    /**
     * Load the most recent GSR data
     */
    private suspend fun loadLatestGSRData(): List<GSRDataPoint> {
        val sessionFolders = sessionFolderManager.getSessionFolders()
        if (sessionFolders.isEmpty()) return emptyList()
        
        // Get the most recent session
        val latestSession = sessionFolders.maxByOrNull { it.createdDate }
        if (latestSession != null) {
            val gsrFile = latestSession.files.find { it.name.contains("gsr_data") && it.name.endsWith(".csv") }
            if (gsrFile != null) {
                return parseGSRDataFromFile(File(gsrFile.path))
            }
        }
        
        return emptyList()
    }
    
    /**
     * Parse GSR data from CSV file
     */
    private fun parseGSRDataFromFile(file: File): List<GSRDataPoint> {
        val dataPoints = mutableListOf<GSRDataPoint>()
        
        try {
            file.bufferedReader().use { reader ->
                reader.readLine() // Skip header
                reader.forEachLine { line ->
                    val gsrDataPoint = GSRDataPoint.fromCsvString(line)
                    if (gsrDataPoint != null && gsrDataPoint.isValidReading()) {
                        dataPoints.add(gsrDataPoint)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing GSR data from file: ${file.path}")
        }
        
        return dataPoints
    }
    
    /**
     * Update the chart with current GSR data
     */
    private fun updateChart() {
        if (gsrDataPoints.isEmpty()) {
            binding.chartGsr.clear()
            return
        }
        
        val entries = gsrDataPoints.mapIndexed { index, dataPoint ->
            Entry(index.toFloat(), dataPoint.gsrValue.toFloat())
        }
        
        val dataSet = LineDataSet(entries, "GSR (μS)").apply {
            color = Color.BLUE
            setCircleColor(Color.BLUE)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 9f
            setDrawFilled(true)
            fillAlpha = 50
            fillColor = Color.BLUE
        }
        
        val lineData = LineData(dataSet)
        binding.chartGsr.data = lineData
        binding.chartGsr.invalidate()
    }
    
    /**
     * Update statistics display
     */
    private fun updateStatistics() {
        if (gsrDataPoints.isEmpty()) {
            binding.textStatistics.text = "No data available"
            return
        }
        
        val gsrValues = gsrDataPoints.map { it.gsrValue }
        val minGSR = gsrValues.minOrNull() ?: 0.0
        val maxGSR = gsrValues.maxOrNull() ?: 0.0
        val avgGSR = gsrValues.average()
        val duration = if (gsrDataPoints.size > 1) {
            (gsrDataPoints.last().timestamp - gsrDataPoints.first().timestamp) / 1_000_000_000.0
        } else 0.0
        
        binding.textStatistics.text = """
            Data Points: ${gsrDataPoints.size}
            Duration: ${String.format("%.1f", duration)} seconds
            Min GSR: ${String.format("%.2f", minGSR)} μS
            Max GSR: ${String.format("%.2f", maxGSR)} μS
            Avg GSR: ${String.format("%.2f", avgGSR)} μS
        """.trimIndent()
    }
    
    /**
     * Update chart based on selected time range
     */
    private fun updateChartTimeRange(rangeIndex: Int) {
        if (gsrDataPoints.isEmpty()) return
        
        val currentTime = System.nanoTime()
        val filteredData = when (rangeIndex) {
            0 -> gsrDataPoints // All data
            1 -> { // Last hour
                val oneHourAgo = currentTime - (60 * 60 * 1_000_000_000L)
                gsrDataPoints.filter { it.timestamp >= oneHourAgo }
            }
            2 -> { // Last 10 minutes
                val tenMinutesAgo = currentTime - (10 * 60 * 1_000_000_000L)
                gsrDataPoints.filter { it.timestamp >= tenMinutesAgo }
            }
            3 -> { // Last minute
                val oneMinuteAgo = currentTime - (60 * 1_000_000_000L)
                gsrDataPoints.filter { it.timestamp >= oneMinuteAgo }
            }
            else -> gsrDataPoints
        }
        
        // Update chart with filtered data
        updateChartWithData(filteredData)
        
        // Update statistics for filtered data
        updateStatisticsWithData(filteredData)
        
        Timber.d("Updated chart with time range filter: $rangeIndex, showing ${filteredData.size} data points")
    }
    
    /**
     * Update chart with specific data set
     */
    private fun updateChartWithData(dataPoints: List<GSRDataPoint>) {
        if (dataPoints.isEmpty()) {
            binding.chartGsr.clear()
            binding.chartGsr.invalidate()
            return
        }
        
        val entries = dataPoints.mapIndexed { index, dataPoint ->
            Entry(index.toFloat(), dataPoint.gsrValue.toFloat())
        }
        
        val dataSet = LineDataSet(entries, "GSR (μS)").apply {
            color = Color.BLUE
            setCircleColor(Color.BLUE)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 9f
            setDrawFilled(true)
            fillAlpha = 50
            fillColor = Color.BLUE
        }
        
        val lineData = LineData(dataSet)
        binding.chartGsr.data = lineData
        binding.chartGsr.invalidate()
    }
    
    /**
     * Update statistics with specific data set
     */
    private fun updateStatisticsWithData(dataPoints: List<GSRDataPoint>) {
        if (dataPoints.isEmpty()) {
            binding.textStatistics.text = "No data available for selected time range"
            return
        }
        
        val gsrValues = dataPoints.map { it.gsrValue }
        val minGSR = gsrValues.minOrNull() ?: 0.0
        val maxGSR = gsrValues.maxOrNull() ?: 0.0
        val avgGSR = gsrValues.average()
        val duration = if (dataPoints.size > 1) {
            (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1_000_000_000.0
        } else 0.0
        
        binding.textStatistics.text = """
            Data Points: ${dataPoints.size}
            Duration: ${String.format("%.1f", duration)} seconds
            Min GSR: ${String.format("%.2f", minGSR)} μS
            Max GSR: ${String.format("%.2f", maxGSR)} μS
            Avg GSR: ${String.format("%.2f", avgGSR)} μS
        """.trimIndent()
    }
    
    /**
     * Export GSR data to file
     */
    private fun exportGSRData() {
        lifecycleScope.launch {
            try {
                val exportFile = withContext(Dispatchers.IO) {
                    val exportDir = File(getExternalFilesDir(null), "exports")
                    exportDir.mkdirs()
                    
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "gsr_export_$timestamp.csv"
                    val file = File(exportDir, fileName)
                    
                    file.bufferedWriter().use { writer ->
                        writer.write(GSRDataPoint.getCsvHeader())
                        writer.newLine()
                        gsrDataPoints.forEach { dataPoint ->
                            writer.write(dataPoint.toCsvString())
                            writer.newLine()
                        }
                    }
                    
                    file
                }
                
                Toast.makeText(this@GSRGraphActivity, "Data exported to ${exportFile.name}", Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to export GSR data")
                Toast.makeText(this@GSRGraphActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Timber.d("GSRGraphActivity destroyed")
    }
    
    /**
     * Custom value formatter for time-based X-axis
     */
    private inner class TimeAxisValueFormatter : ValueFormatter() {
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        override fun getFormattedValue(value: Float): String {
            val index = value.toInt()
            return if (index >= 0 && index < gsrDataPoints.size) {
                val timestamp = gsrDataPoints[index].timestamp / 1_000_000 // Convert to milliseconds
                dateFormat.format(Date(timestamp))
            } else {
                ""
            }
        }
    }
    
    companion object {
        private const val TAG = "GSRGraphActivity"
    }
}