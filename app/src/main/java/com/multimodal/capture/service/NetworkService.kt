package com.multimodal.capture.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.multimodal.capture.BuildConfig
import com.multimodal.capture.network.CommandProtocol
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * NetworkService handles background networking operations.
 * Maintains connection with PC controller even when app is backgrounded.
 * Refactored to use Kotlin Coroutines for robust background task management.
 */
class NetworkService : Service() {

    private val binder = NetworkBinder()
    private var isRunning = false

    // Coroutine scope for managing all background tasks in this service
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Networking components
    private var tcpServerSocket: ServerSocket? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private var dataStreamingSocket: DatagramSocket? = null
    private var pcAddress: InetAddress? = null
    private val connectedClients = mutableListOf<Socket>()

    // RecordingService integration
    private var recordingService: RecordingService? = null
    private var isRecordingServiceBound = false

    // WakeLock to ensure CPU stays active
    private var wakeLock: PowerManager.WakeLock? = null

    // Configuration
    private val tcpPort = 8888 // Make this configurable via SettingsManager
    private val udpDiscoveryPort = 8889
    private val udpStreamingPort = 8890

    inner class NetworkBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    /** ServiceConnection for binding to RecordingService */
    private val recordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            isRecordingServiceBound = true
            Timber.d("NetworkService connected to RecordingService")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isRecordingServiceBound = false
            recordingService = null
            Timber.w("NetworkService disconnected from RecordingService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("NetworkService created")

        // Bind to RecordingService for direct communication
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, recordingServiceConnection, Context.BIND_AUTO_CREATE)
        }

        // Acquire a wake lock to ensure CPU stays active for networking
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiModalCapture::NetworkWakelockTag")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NETWORK -> startNetworking()
            ACTION_STOP_NETWORK -> stopNetworking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startNetworking() {
        if (isRunning) {
            Timber.w("Network service already running")
            return
        }
        isRunning = true

        // Acquire WakeLock to keep CPU running
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(30 * 60 * 1000L /* 30 minutes timeout */)
            Timber.d("Network service wake lock acquired.")
        }

        // Launch server tasks within the service's coroutine scope
        serviceScope.launch { startTcpCommandServer() }
        serviceScope.launch { startUdpDiscoveryServer() }

        Timber.d("Enhanced network service started with C&C protocol")
    }

    private suspend fun startTcpCommandServer() = withContext(Dispatchers.IO) {
        try {
            tcpServerSocket = ServerSocket(tcpPort)
            Timber.i("TCP Command Server listening on port $tcpPort")
            while (currentCoroutineContext().isActive) {
                try {
                    val clientSocket = tcpServerSocket?.accept()
                    if (clientSocket != null) {
                        Timber.i("PC client connected: ${clientSocket.inetAddress.hostAddress}")
                        pcAddress = clientSocket.inetAddress
                        dataStreamingSocket = DatagramSocket()
                        connectedClients.add(clientSocket)
                        // Launch a new coroutine to handle each client
                        serviceScope.launch { handleTcpClient(clientSocket) }
                    }
                } catch (e: IOException) {
                    if (currentCoroutineContext().isActive) Timber.e(e, "Error accepting TCP client connection")
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) Timber.e(e, "TCP Command Server failed: ${e.message}")
        } finally {
            tcpServerSocket?.close()
        }
    }

    private suspend fun startUdpDiscoveryServer() = withContext(Dispatchers.IO) {
        try {
            udpDiscoverySocket = DatagramSocket(udpDiscoveryPort).apply { broadcast = true }
            Timber.i("UDP Discovery Server listening on port $udpDiscoveryPort")
            val buffer = ByteArray(1024)
            while (currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpDiscoverySocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message == "DISCOVER_MULTIMODAL_CAPTURE_APP") {
                        Timber.i("Received discovery ping from ${packet.address.hostAddress}")
                        val response = createDiscoveryResponse()
                        val responseBytes = CommandProtocol.toJson(response).toByteArray()
                        val responsePacket = DatagramPacket(responseBytes, responseBytes.size, packet.address, packet.port)
                        udpDiscoverySocket?.send(responsePacket)
                        Timber.d("Sent discovery response to ${packet.address.hostAddress}")
                    }
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive) Timber.e(e, "Error in UDP discovery server loop")
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) Timber.e(e, "UDP Discovery Server failed: ${e.message}")
        } finally {
            udpDiscoverySocket?.close()
        }
    }

    private suspend fun handleTcpClient(clientSocket: Socket) {
        try {
            val reader = clientSocket.getInputStream().bufferedReader()
            val writer = clientSocket.getOutputStream().bufferedWriter()
            while (currentCoroutineContext().isActive && !clientSocket.isClosed) {
                val message = reader.readLine() ?: break // Connection closed by client
                Timber.d("Received command: $message")
                val command = CommandProtocol.fromJson<CommandProtocol.Command>(message)
                if (command != null) {
                    val response = processCommand(command)
                    writer.write(CommandProtocol.toJson(response))
                    writer.newLine()
                    writer.flush()
                } else {
                    // Handle parse error
                    val errorResponse = CommandProtocol.Response.ErrorResponse("UNKNOWN", "Invalid command format")
                    writer.write(CommandProtocol.toJson(errorResponse))
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (e: IOException) {
            Timber.w("Client connection closed or error: ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "Error in TCP client handler")
        } finally {
            try {
                clientSocket.close()
                connectedClients.remove(clientSocket)
                if (connectedClients.isEmpty()) {
                    pcAddress = null
                    dataStreamingSocket?.close()
                    dataStreamingSocket = null
                }
                Timber.d("Client connection cleaned up")
            } catch (e: IOException) {
                Timber.w(e, "Error closing client socket")
            }
        }
    }

    private fun processCommand(command: CommandProtocol.Command): CommandProtocol.Response {
        if (!isRecordingServiceBound || recordingService == null) {
            return CommandProtocol.Response.ErrorResponse("INTERNAL_ERROR", "Recording service not available.")
        }

        return when (command) {
            is CommandProtocol.Command.StartRecording -> {
                val sessionId = command.sessionId ?: "session_${System.currentTimeMillis()}"
                val success = recordingService!!.startRecording(sessionId, System.currentTimeMillis())
                if (success) {
                    CommandProtocol.Response.Acknowledgment("START_RECORDING", "Recording started with session: $sessionId")
                } else {
                    CommandProtocol.Response.ErrorResponse("START_RECORDING", "Failed to start recording. Already recording or not ready.")
                }
            }
            is CommandProtocol.Command.StopRecording -> {
                val success = recordingService!!.stopRecording()
                if (success) {
                    CommandProtocol.Response.Acknowledgment("STOP_RECORDING", "Recording stopped successfully.")
                } else {
                    CommandProtocol.Response.ErrorResponse("STOP_RECORDING", "Failed to stop recording. Not currently recording.")
                }
            }
            is CommandProtocol.Command.GetStatus -> createStatusResponse()
            else -> CommandProtocol.Response.ErrorResponse("UNKNOWN_COMMAND", "Command not supported")
        }
    }

    fun sendDataPacket(packet: CommandProtocol.DataPacket) {
        val socket = dataStreamingSocket ?: return
        val address = pcAddress ?: return

        // Launch a fire-and-forget coroutine to send the UDP packet
        serviceScope.launch {
            try {
                val jsonData = CommandProtocol.toJson(packet)
                val buffer = jsonData.toByteArray()
                val datagramPacket = DatagramPacket(buffer, buffer.size, address, udpStreamingPort)
                socket.send(datagramPacket)
                Timber.v("Sent data packet: ${packet.type}")
            } catch (e: Exception) {
                // Avoid spamming logs for streaming errors
            }
        }
    }

    private fun createDiscoveryResponse(): CommandProtocol.DiscoveryResponse {
        val ipAddress = try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).let { wifiManager ->
                val ipInt = wifiManager.connectionInfo.ipAddress
                String.format("%d.%d.%d.%d", ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
            }
        } catch (e: Exception) { "Unknown" }

        return CommandProtocol.DiscoveryResponse(
            deviceName = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
            tcpPort = tcpPort,
            udpStreamingPort = udpStreamingPort,
            deviceCapabilities = listOf("GSR_SENSOR", "THERMAL_CAMERA", "RGB_CAMERA", "AUDIO_RECORDING"),
            ipAddress = ipAddress
        )
    }

    private fun createStatusResponse(): CommandProtocol.Response.StatusUpdate {
        // This method would query the bound RecordingService for the status of each manager
        val isRec = isRecordingServiceBound && recordingService?.isRecording() == true
        val sessionId = if (isRec) recordingService?.getCurrentSessionId() else null
        
        val deviceStatus = if (isRecordingServiceBound && recordingService != null) {
            mapOf(
                "GSR" to CommandProtocol.DeviceStatus(
                    connected = recordingService!!.getGSRManager().isConnected(),
                    status = if (recordingService!!.getGSRManager().isConnected()) "CONNECTED" else "DISCONNECTED",
                    lastDataTimestamp = null
                ),
                "Thermal" to CommandProtocol.DeviceStatus(
                    connected = recordingService!!.getThermalManager().isConnected(),
                    status = if (recordingService!!.getThermalManager().isConnected()) "CONNECTED" else "DISCONNECTED",
                    lastDataTimestamp = null
                )
            )
        } else {
            mapOf(
                "GSR" to CommandProtocol.DeviceStatus(false, "DISCONNECTED", null),
                "Thermal" to CommandProtocol.DeviceStatus(false, "DISCONNECTED", null)
            )
        }

        val systemInfo = CommandProtocol.SystemInfo(
            deviceName = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = Build.VERSION.RELEASE,
            availableStorageMb = getAvailableStorageMb(),
            batteryLevel = getBatteryLevel(),
            networkInterface = "WiFi",
            ipAddress = getLocalIpAddress()
        )
        
        return CommandProtocol.Response.StatusUpdate(isRec, sessionId, deviceStatus, systemInfo)
    }

    private fun getLocalIpAddress(): String {
        return try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).let { wifiManager ->
                val ipInt = wifiManager.connectionInfo.ipAddress
                String.format("%d.%d.%d.%d", ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getAvailableStorageMb(): Long {
        return try {
            val stat = android.os.StatFs(applicationContext.filesDir.path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            bytesAvailable / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    private fun stopNetworking() {
        if (!isRunning) return
        isRunning = false

        // Release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Timber.d("Network service wake lock released.")
        }

        // Cancel all coroutines in the scope, which will stop the servers
        serviceScope.cancel()
        Timber.d("Coroutine scope cancelled. Network servers stopping.")

        // Close sockets to unblock any waiting threads immediately
        try {
            tcpServerSocket?.close()
            udpDiscoverySocket?.close()
            dataStreamingSocket?.close()
            connectedClients.forEach { it.close() }
        } catch (e: IOException) {
            Timber.w(e, "Exception while closing network sockets.")
        }
        connectedClients.clear()

        Timber.d("Network service stopped and cleaned up")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworking()
        if (isRecordingServiceBound) {
            unbindService(recordingServiceConnection)
        }
        Timber.d("NetworkService destroyed")
    }

    companion object {
        const val ACTION_START_NETWORK = "com.multimodal.capture.START_NETWORK"
        const val ACTION_STOP_NETWORK = "com.multimodal.capture.STOP_NETWORK"
    }
}