/**
 * Shimmer PC Bridge
 * Java bridge class for interfacing Python PC controller with Shimmer SDK
 * Based on ShimmerCaptureIntelligent reference implementation
 * 
 * This class provides a command-line interface for the Python ShimmerPCManager
 * to communicate with the Java Shimmer SDK through subprocess calls.
 */

import com.shimmerresearch.driver.BasicProcessWithCallBack;
import com.shimmerresearch.driver.CallbackObject;
import com.shimmerresearch.driver.ShimmerMsg;
import com.shimmerresearch.driver.Configuration;
import com.shimmerresearch.driver.FormatCluster;
import com.shimmerresearch.driver.ObjectCluster;
import com.shimmerresearch.pcDriver.ShimmerPC;
import com.shimmerresearch.tools.bluetooth.BasicShimmerBluetoothManagerPc;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ShimmerPCBridge extends BasicProcessWithCallBack {
    
    // Shimmer SDK components
    private static BasicShimmerBluetoothManagerPc bluetoothManager;
    private ShimmerPC shimmerDevice;
    private boolean isConfigured = false;
    private boolean isRecording = false;
    
    // Device configuration
    private String deviceId;
    private String connectionType;
    private String comPort;
    private String bluetoothAddress;
    private double samplingRate = 128.0;
    
    // Data processing
    private BlockingQueue<String> commandQueue;
    private Thread commandProcessor;
    private boolean running = true;
    
    // Output streams for Python communication
    private PrintWriter stdout;
    private PrintWriter stderr;
    
    public ShimmerPCBridge() {
        // Initialize Bluetooth manager
        bluetoothManager = new BasicShimmerBluetoothManagerPc();
        
        // Initialize command queue
        commandQueue = new LinkedBlockingQueue<>();
        
        // Initialize output streams
        stdout = new PrintWriter(System.out, true);
        stderr = new PrintWriter(System.err, true);
        
        // Start command processor thread
        commandProcessor = new Thread(this::processCommands);
        commandProcessor.setDaemon(true);
        commandProcessor.start();
        
        sendStatus("initialized", "Shimmer PC Bridge initialized");
    }
    
    public static void main(String[] args) {
        try {
            ShimmerPCBridge bridge = new ShimmerPCBridge();
            bridge.parseArguments(args);
            bridge.run();
        } catch (Exception e) {
            System.err.println("ERROR: Failed to start Shimmer PC Bridge: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--device-id":
                    if (i + 1 < args.length) {
                        deviceId = args[++i];
                    }
                    break;
                case "--connection-type":
                    if (i + 1 < args.length) {
                        connectionType = args[++i];
                    }
                    break;
                case "--com-port":
                    if (i + 1 < args.length) {
                        comPort = args[++i];
                    }
                    break;
                case "--bt-address":
                    if (i + 1 < args.length) {
                        bluetoothAddress = args[++i];
                    }
                    break;
                case "--sampling-rate":
                    if (i + 1 < args.length) {
                        try {
                            samplingRate = Double.parseDouble(args[++i]);
                        } catch (NumberFormatException e) {
                            sendError("Invalid sampling rate: " + args[i]);
                        }
                    }
                    break;
            }
        }
        
        // Validate required parameters
        if (deviceId == null) {
            sendError("Device ID is required");
            System.exit(1);
        }
        
        if (connectionType == null) {
            sendError("Connection type is required");
            System.exit(1);
        }
        
        if ("serial".equals(connectionType) && comPort == null) {
            sendError("COM port is required for serial connection");
            System.exit(1);
        }
        
        if ("bluetooth".equals(connectionType) && bluetoothAddress == null) {
            sendError("Bluetooth address is required for bluetooth connection");
            System.exit(1);
        }
    }
    
    private void run() {
        try {
            // Connect to device
            connectToDevice();
            
            // Process stdin commands
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            String line;
            
            while (running && (line = stdin.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    commandQueue.offer(line);
                }
            }
            
        } catch (IOException e) {
            sendError("IO error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    private void connectToDevice() {
        try {
            sendStatus("connecting", "Connecting to Shimmer device");
            
            if ("serial".equals(connectionType)) {
                // Connect via serial/USB
                bluetoothManager.connectShimmerThroughCommPort(comPort);
            } else if ("bluetooth".equals(connectionType)) {
                // Connect via Bluetooth
                // TODO: Implement Bluetooth connection
                sendError("Bluetooth connection not yet implemented");
                return;
            }
            
            // Set callback for receiving data
            setWaitForData(bluetoothManager.callBackObject);
            
            sendStatus("connected", "Connected to Shimmer device");
            
        } catch (Exception e) {
            sendError("Failed to connect to device: " + e.getMessage());
        }
    }
    
    private void processCommands() {
        while (running) {
            try {
                String command = commandQueue.take();
                handleCommand(command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                sendError("Error processing command: " + e.getMessage());
            }
        }
    }
    
    private void handleCommand(String command) {
        switch (command.toUpperCase()) {
            case "START_RECORDING":
                startRecording();
                break;
            case "STOP_RECORDING":
                stopRecording();
                break;
            case "DISCONNECT":
                disconnect();
                break;
            case "STATUS":
                sendDeviceStatus();
                break;
            default:
                sendError("Unknown command: " + command);
        }
    }
    
    private void startRecording() {
        try {
            if (shimmerDevice != null && !isRecording) {
                shimmerDevice.startStreaming();
                isRecording = true;
                sendStatus("recording", "Recording started");
            } else {
                sendError("Cannot start recording - device not ready or already recording");
            }
        } catch (Exception e) {
            sendError("Failed to start recording: " + e.getMessage());
        }
    }
    
    private void stopRecording() {
        try {
            if (shimmerDevice != null && isRecording) {
                shimmerDevice.stopStreaming();
                isRecording = false;
                sendStatus("connected", "Recording stopped");
            } else {
                sendError("Cannot stop recording - not currently recording");
            }
        } catch (Exception e) {
            sendError("Failed to stop recording: " + e.getMessage());
        }
    }
    
    private void disconnect() {
        try {
            if (shimmerDevice != null) {
                bluetoothManager.disconnectShimmer(shimmerDevice);
                shimmerDevice = null;
                sendStatus("disconnected", "Device disconnected");
            }
            running = false;
        } catch (Exception e) {
            sendError("Failed to disconnect: " + e.getMessage());
        }
    }
    
    private void sendDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("device_id", deviceId);
        status.put("connected", shimmerDevice != null);
        status.put("recording", isRecording);
        status.put("sampling_rate", samplingRate);
        
        if (shimmerDevice != null) {
            // Add device-specific status information
            status.put("firmware_version", shimmerDevice.getFirmwareVersionFullName());
            status.put("battery_level", shimmerDevice.getBattVoltage());
            // TODO: Add more device status information as available
        }
        
        sendStatusUpdate(status);
    }
    
    @Override
    protected void processMsgFromCallback(ShimmerMsg shimmerMsg) {
        int messageType = shimmerMsg.mIdentifier;
        Object messageObject = shimmerMsg.mB;
        
        switch (messageType) {
            case ShimmerPC.MSG_IDENTIFIER_STATE_CHANGE:
                handleStateChange((CallbackObject) messageObject);
                break;
                
            case ShimmerPC.MSG_IDENTIFIER_NOTIFICATION_MESSAGE:
                handleNotificationMessage((CallbackObject) messageObject);
                break;
                
            case ShimmerPC.MSG_IDENTIFIER_DATA_PACKET:
                handleDataPacket(shimmerMsg);
                break;
                
            case ShimmerPC.MSG_IDENTIFIER_PACKET_RECEPTION_RATE_OVERALL:
                handlePacketReceptionRate(shimmerMsg);
                break;
                
            default:
                // Ignore unknown message types
                break;
        }
    }
    
    private void handleStateChange(CallbackObject callbackObject) {
        if (callbackObject.mState != null) {
            switch (callbackObject.mState) {
                case CONNECTING:
                    sendStatus("connecting", "Connecting to device");
                    break;
                    
                case CONNECTED:
                    sendStatus("connected", "Device connected");
                    shimmerDevice = (ShimmerPC) bluetoothManager.getShimmerDeviceBtConnected(comPort);
                    
                    // Configure device on first connection
                    if (!isConfigured && shimmerDevice != null) {
                        configureDevice();
                        isConfigured = true;
                    }
                    break;
                    
                case DISCONNECTED:
                case CONNECTION_LOST:
                    sendStatus("disconnected", "Device disconnected");
                    shimmerDevice = null;
                    isConfigured = false;
                    isRecording = false;
                    break;
                    
                default:
                    break;
            }
        }
    }
    
    private void handleNotificationMessage(CallbackObject callbackObject) {
        int notification = callbackObject.mIndicator;
        
        switch (notification) {
            case ShimmerPC.NOTIFICATION_SHIMMER_FULLY_INITIALIZED:
                sendStatus("initialized", "Device fully initialized");
                // Auto-start streaming if configured
                if (shimmerDevice != null) {
                    startRecording();
                }
                break;
                
            case ShimmerPC.NOTIFICATION_SHIMMER_START_STREAMING:
                sendStatus("streaming", "Device started streaming");
                isRecording = true;
                break;
                
            case ShimmerPC.NOTIFICATION_SHIMMER_STOP_STREAMING:
                sendStatus("connected", "Device stopped streaming");
                isRecording = false;
                break;
                
            default:
                break;
        }
    }
    
    private void handleDataPacket(ShimmerMsg shimmerMsg) {
        try {
            if (shimmerDevice != null && isRecording) {
                ObjectCluster objectCluster = (ObjectCluster) shimmerMsg.mB;
                
                // Extract GSR data
                Map<String, Object> data = new HashMap<>();
                data.put("timestamp", System.currentTimeMillis() / 1000.0);
                data.put("device_id", deviceId);
                
                // Extract GSR resistance (kΩ)
                FormatCluster gsrResistanceCluster = objectCluster.getFormatCluster("GSR", "CAL");
                if (gsrResistanceCluster != null) {
                    data.put("gsr_value", gsrResistanceCluster.mData);
                    // Convert resistance to conductance (μS = 1000000 / kΩ)
                    data.put("gsr_conductance", 1000000.0 / gsrResistanceCluster.mData);
                }
                
                // Extract PPG data if available
                FormatCluster ppgCluster = objectCluster.getFormatCluster("PPG", "CAL");
                if (ppgCluster != null) {
                    data.put("ppg_value", ppgCluster.mData);
                }
                
                // Extract heart rate if available
                FormatCluster hrCluster = objectCluster.getFormatCluster("Heart Rate", "CAL");
                if (hrCluster != null) {
                    data.put("heart_rate", hrCluster.mData);
                }
                
                // Send data to Python
                sendData(data);
            }
        } catch (Exception e) {
            sendError("Error processing data packet: " + e.getMessage());
        }
    }
    
    private void handlePacketReceptionRate(ShimmerMsg shimmerMsg) {
        try {
            double receptionRate = (Double) shimmerMsg.mB;
            Map<String, Object> status = new HashMap<>();
            status.put("packet_reception_rate", receptionRate);
            sendStatusUpdate(status);
        } catch (Exception e) {
            sendError("Error processing packet reception rate: " + e.getMessage());
        }
    }
    
    private void configureDevice() {
        try {
            if (shimmerDevice != null) {
                // Set sampling rate
                shimmerDevice.writeShimmerAndSensorsSamplingRate(samplingRate);
                
                // Enable GSR sensor
                shimmerDevice.writeEnabledSensors(Configuration.Shimmer3.SensorBitmap.GSR);
                
                // Configure GSR range (if needed)
                // shimmerDevice.writeGSRRange(Configuration.Shimmer3.GSR_RANGE.GSR_RANGE_AUTO);
                
                sendStatus("configured", "Device configured successfully");
            }
        } catch (Exception e) {
            sendError("Failed to configure device: " + e.getMessage());
        }
    }
    
    private void sendData(Map<String, Object> data) {
        try {
            String json = mapToJson(data);
            stdout.println("DATA:" + json);
            stdout.flush();
        } catch (Exception e) {
            sendError("Failed to send data: " + e.getMessage());
        }
    }
    
    private void sendStatus(String status, String message) {
        Map<String, Object> statusData = new HashMap<>();
        statusData.put("status", status);
        statusData.put("message", message);
        statusData.put("timestamp", System.currentTimeMillis() / 1000.0);
        sendStatusUpdate(statusData);
    }
    
    private void sendStatusUpdate(Map<String, Object> status) {
        try {
            String json = mapToJson(status);
            stdout.println("STATUS:" + json);
            stdout.flush();
        } catch (Exception e) {
            stderr.println("Failed to send status: " + e.getMessage());
        }
    }
    
    private void sendError(String errorMessage) {
        stdout.println("ERROR:" + errorMessage);
        stdout.flush();
        stderr.println("ERROR: " + errorMessage);
    }
    
    private String mapToJson(Map<String, Object> map) {
        // Simple JSON serialization (could use a proper JSON library)
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            
            json.append("\"").append(entry.getKey()).append("\":");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else if (value instanceof Number) {
                json.append(value);
            } else if (value instanceof Boolean) {
                json.append(value);
            } else if (value == null) {
                json.append("null");
            } else {
                json.append("\"").append(value.toString()).append("\"");
            }
        }
        
        json.append("}");
        return json.toString();
    }
    
    private void cleanup() {
        try {
            running = false;
            
            if (shimmerDevice != null) {
                if (isRecording) {
                    shimmerDevice.stopStreaming();
                }
                bluetoothManager.disconnectShimmer(shimmerDevice);
            }
            
            if (commandProcessor != null && commandProcessor.isAlive()) {
                commandProcessor.interrupt();
            }
            
            sendStatus("shutdown", "Shimmer PC Bridge shutting down");
            
        } catch (Exception e) {
            sendError("Error during cleanup: " + e.getMessage());
        }
    }
}