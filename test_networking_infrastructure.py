#!/usr/bin/env python3
"""
Test script for validating the enhanced networking infrastructure
between PC controller and Android devices.

This script tests:
1. Device discovery and connection
2. Command protocol (START, STOP, STATUS)
3. Time synchronization
4. Sync markers and events
5. Multi-device coordination
"""

import asyncio
import json
import socket
import time
import uuid
from typing import Dict, List, Optional
import logging

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class NetworkingTestSuite:
    def __init__(self):
        self.devices = {}
        self.test_results = {}
        self.session_id = str(uuid.uuid4())
        
    async def run_all_tests(self):
        """Run all networking infrastructure tests"""
        logger.info("Starting networking infrastructure test suite")
        
        tests = [
            ("Device Discovery", self.test_device_discovery),
            ("Device Connection", self.test_device_connection),
            ("Command Protocol", self.test_command_protocol),
            ("Time Synchronization", self.test_time_synchronization),
            ("Sync Markers", self.test_sync_markers),
            ("Multi-Device Coordination", self.test_multi_device_coordination),
            ("Error Handling", self.test_error_handling),
            ("Network Recovery", self.test_network_recovery)
        ]
        
        for test_name, test_func in tests:
            logger.info(f"Running test: {test_name}")
            try:
                result = await test_func()
                self.test_results[test_name] = {
                    "status": "PASSED" if result else "FAILED",
                    "timestamp": time.time()
                }
                logger.info(f"Test {test_name}: {'PASSED' if result else 'FAILED'}")
            except Exception as e:
                self.test_results[test_name] = {
                    "status": "ERROR",
                    "error": str(e),
                    "timestamp": time.time()
                }
                logger.error(f"Test {test_name} ERROR: {e}")
        
        self.print_test_summary()
        
    async def test_device_discovery(self) -> bool:
        """Test device discovery mechanism"""
        logger.info("Testing device discovery...")
        
        try:
            # Simulate UDP broadcast for device discovery
            discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            discovery_socket.settimeout(5.0)
            
            # Send discovery request
            discovery_message = json.dumps({
                "type": "DISCOVERY_REQUEST",
                "timestamp": int(time.time() * 1000),
                "requestId": str(uuid.uuid4())
            })
            
            discovery_socket.sendto(discovery_message.encode(), ('<broadcast>', 8888))
            logger.info("Sent discovery broadcast")
            
            # Listen for responses
            discovered_devices = []
            start_time = time.time()
            
            while time.time() - start_time < 5.0:
                try:
                    data, addr = discovery_socket.recvfrom(1024)
                    response = json.loads(data.decode())
                    
                    if response.get("type") == "DISCOVERY_RESPONSE":
                        device_info = {
                            "ip": addr[0],
                            "port": addr[1],
                            "device_id": response.get("deviceId"),
                            "device_name": response.get("deviceName"),
                            "capabilities": response.get("capabilities", [])
                        }
                        discovered_devices.append(device_info)
                        logger.info(f"Discovered device: {device_info['device_name']} at {addr[0]}")
                        
                except socket.timeout:
                    break
                except Exception as e:
                    logger.warning(f"Error receiving discovery response: {e}")
            
            discovery_socket.close()
            self.devices = {dev["device_id"]: dev for dev in discovered_devices}
            
            logger.info(f"Discovery completed. Found {len(discovered_devices)} devices")
            return len(discovered_devices) > 0
            
        except Exception as e:
            logger.error(f"Device discovery failed: {e}")
            return False
    
    async def test_device_connection(self) -> bool:
        """Test device connection establishment"""
        logger.info("Testing device connections...")
        
        if not self.devices:
            logger.warning("No devices discovered, skipping connection test")
            return False
        
        connected_count = 0
        
        for device_id, device_info in self.devices.items():
            try:
                # Attempt TCP connection
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(10.0)
                sock.connect((device_info["ip"], 8889))  # Default server port
                
                # Send connection request
                connection_msg = {
                    "type": "CONNECTION_REQUEST",
                    "timestamp": int(time.time() * 1000),
                    "clientId": "test_client"
                }
                
                sock.send(json.dumps(connection_msg).encode() + b'\n')
                
                # Wait for connection acknowledgment
                response = sock.recv(1024).decode().strip()
                ack = json.loads(response)
                
                if ack.get("type") == "CONNECTION_ACK":
                    logger.info(f"Connected to device: {device_info['device_name']}")
                    device_info["socket"] = sock
                    connected_count += 1
                else:
                    logger.warning(f"Connection rejected by device: {device_info['device_name']}")
                    sock.close()
                    
            except Exception as e:
                logger.error(f"Failed to connect to device {device_info['device_name']}: {e}")
        
        logger.info(f"Connected to {connected_count}/{len(self.devices)} devices")
        return connected_count > 0
    
    async def test_command_protocol(self) -> bool:
        """Test command protocol (START, STOP, STATUS)"""
        logger.info("Testing command protocol...")
        
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if not connected_devices:
            logger.warning("No connected devices, skipping command protocol test")
            return False
        
        commands_tested = 0
        successful_commands = 0
        
        for device in connected_devices:
            try:
                # Test STATUS command
                status_cmd = {
                    "type": "COMMAND",
                    "payload": {
                        "command": "CMD_STATUS",
                        "parameters": {},
                        "sessionId": self.session_id,
                        "timestamp": int(time.time() * 1000)
                    },
                    "timestamp": int(time.time() * 1000),
                    "messageId": str(uuid.uuid4()),
                    "deviceId": None,
                    "requiresAck": True
                }
                
                device["socket"].send(json.dumps(status_cmd).encode() + b'\n')
                commands_tested += 1
                
                # Wait for response
                response = device["socket"].recv(2048).decode().strip()
                ack = json.loads(response)
                
                if ack.get("type") == "COMMAND_ACK":
                    logger.info(f"STATUS command successful for device: {device['device_name']}")
                    successful_commands += 1
                
                # Test PREPARE command
                prepare_cmd = {
                    "type": "COMMAND",
                    "payload": {
                        "command": "CMD_PREPARE",
                        "parameters": {"recordingMode": "test"},
                        "sessionId": self.session_id,
                        "timestamp": int(time.time() * 1000)
                    },
                    "timestamp": int(time.time() * 1000),
                    "messageId": str(uuid.uuid4()),
                    "requiresAck": True
                }
                
                device["socket"].send(json.dumps(prepare_cmd).encode() + b'\n')
                commands_tested += 1
                
                response = device["socket"].recv(2048).decode().strip()
                ack = json.loads(response)
                
                if ack.get("type") == "COMMAND_ACK":
                    logger.info(f"PREPARE command successful for device: {device['device_name']}")
                    successful_commands += 1
                
            except Exception as e:
                logger.error(f"Command protocol test failed for device {device['device_name']}: {e}")
        
        success_rate = successful_commands / commands_tested if commands_tested > 0 else 0
        logger.info(f"Command protocol test: {successful_commands}/{commands_tested} commands successful ({success_rate:.1%})")
        
        return success_rate >= 0.8  # 80% success rate required
    
    async def test_time_synchronization(self) -> bool:
        """Test time synchronization mechanism"""
        logger.info("Testing time synchronization...")
        
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if not connected_devices:
            logger.warning("No connected devices, skipping time sync test")
            return False
        
        sync_successful = 0
        
        for device in connected_devices:
            try:
                # Send sync ping
                client_send_time = int(time.time() * 1000)
                sync_ping = {
                    "type": "SYNC_PING",
                    "payload": {
                        "pingId": str(uuid.uuid4()),
                        "clientTimestamp": client_send_time,
                        "sequenceNumber": 1
                    },
                    "timestamp": client_send_time,
                    "messageId": str(uuid.uuid4())
                }
                
                device["socket"].send(json.dumps(sync_ping).encode() + b'\n')
                
                # Wait for sync pong
                response = device["socket"].recv(2048).decode().strip()
                pong = json.loads(response)
                client_receive_time = int(time.time() * 1000)
                
                if pong.get("type") == "SYNC_PONG":
                    payload = pong.get("payload", {})
                    server_receive_time = payload.get("serverReceiveTimestamp")
                    server_send_time = payload.get("serverSendTimestamp")
                    
                    if server_receive_time and server_send_time:
                        # Calculate round-trip time and offset
                        rtt = client_receive_time - client_send_time
                        offset = ((server_receive_time - client_send_time) + (server_send_time - client_receive_time)) / 2
                        
                        logger.info(f"Sync successful for {device['device_name']}: RTT={rtt}ms, Offset={offset}ms")
                        
                        if rtt < 1000:  # RTT less than 1 second
                            sync_successful += 1
                
            except Exception as e:
                logger.error(f"Time sync test failed for device {device['device_name']}: {e}")
        
        success_rate = sync_successful / len(connected_devices)
        logger.info(f"Time synchronization test: {sync_successful}/{len(connected_devices)} devices synchronized ({success_rate:.1%})")
        
        return success_rate >= 0.8
    
    async def test_sync_markers(self) -> bool:
        """Test sync marker functionality"""
        logger.info("Testing sync markers...")
        
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if not connected_devices:
            logger.warning("No connected devices, skipping sync marker test")
            return False
        
        markers_sent = 0
        markers_acknowledged = 0
        
        for device in connected_devices:
            try:
                # Send sync marker
                marker = {
                    "type": "SYNC_MARKER",
                    "payload": {
                        "markerId": str(uuid.uuid4()),
                        "markerType": "CALIBRATION",
                        "sessionId": self.session_id,
                        "timestamp": int(time.time() * 1000),
                        "data": {
                            "testMarker": True,
                            "calibrationType": "test_calibration"
                        }
                    },
                    "timestamp": int(time.time() * 1000),
                    "messageId": str(uuid.uuid4()),
                    "requiresAck": True
                }
                
                device["socket"].send(json.dumps(marker).encode() + b'\n')
                markers_sent += 1
                
                # Wait for acknowledgment
                response = device["socket"].recv(2048).decode().strip()
                ack = json.loads(response)
                
                if ack.get("type") == "COMMAND_ACK":
                    logger.info(f"Sync marker acknowledged by device: {device['device_name']}")
                    markers_acknowledged += 1
                
            except Exception as e:
                logger.error(f"Sync marker test failed for device {device['device_name']}: {e}")
        
        success_rate = markers_acknowledged / markers_sent if markers_sent > 0 else 0
        logger.info(f"Sync marker test: {markers_acknowledged}/{markers_sent} markers acknowledged ({success_rate:.1%})")
        
        return success_rate >= 0.8
    
    async def test_multi_device_coordination(self) -> bool:
        """Test multi-device coordination"""
        logger.info("Testing multi-device coordination...")
        
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if len(connected_devices) < 2:
            logger.warning("Need at least 2 devices for coordination test, skipping")
            return True  # Pass if not enough devices
        
        # Test coordinated start
        try:
            start_time = int(time.time() * 1000) + 3000  # Start in 3 seconds
            
            for device in connected_devices:
                coord_start = {
                    "type": "SYNC_EVENT",
                    "payload": {
                        "eventType": "COORDINATED_START",
                        "sessionId": self.session_id,
                        "coordinatedStartTime": start_time,
                        "masterDeviceId": connected_devices[0]["device_id"],
                        "timestamp": int(time.time() * 1000)
                    },
                    "timestamp": int(time.time() * 1000),
                    "messageId": str(uuid.uuid4())
                }
                
                device["socket"].send(json.dumps(coord_start).encode() + b'\n')
            
            logger.info(f"Sent coordinated start command to {len(connected_devices)} devices")
            
            # Wait for confirmations
            confirmations = 0
            for device in connected_devices:
                try:
                    response = device["socket"].recv(2048).decode().strip()
                    ack = json.loads(response)
                    
                    if "COORDINATED_START" in str(ack):
                        confirmations += 1
                        logger.info(f"Coordination confirmed by device: {device['device_name']}")
                        
                except Exception as e:
                    logger.warning(f"No confirmation from device {device['device_name']}: {e}")
            
            success_rate = confirmations / len(connected_devices)
            logger.info(f"Multi-device coordination: {confirmations}/{len(connected_devices)} devices confirmed ({success_rate:.1%})")
            
            return success_rate >= 0.8
            
        except Exception as e:
            logger.error(f"Multi-device coordination test failed: {e}")
            return False
    
    async def test_error_handling(self) -> bool:
        """Test error handling and recovery"""
        logger.info("Testing error handling...")
        
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if not connected_devices:
            logger.warning("No connected devices, skipping error handling test")
            return False
        
        # Test invalid command
        device = connected_devices[0]
        try:
            invalid_cmd = {
                "type": "COMMAND",
                "payload": {
                    "command": "INVALID_COMMAND",
                    "parameters": {},
                    "sessionId": self.session_id,
                    "timestamp": int(time.time() * 1000)
                },
                "timestamp": int(time.time() * 1000),
                "messageId": str(uuid.uuid4()),
                "requiresAck": True
            }
            
            device["socket"].send(json.dumps(invalid_cmd).encode() + b'\n')
            
            # Wait for error response
            response = device["socket"].recv(2048).decode().strip()
            error_response = json.loads(response)
            
            if error_response.get("type") == "ERROR":
                logger.info("Error handling test successful - received error response for invalid command")
                return True
            else:
                logger.warning("Error handling test failed - no error response for invalid command")
                return False
                
        except Exception as e:
            logger.error(f"Error handling test failed: {e}")
            return False
    
    async def test_network_recovery(self) -> bool:
        """Test network recovery mechanisms"""
        logger.info("Testing network recovery...")
        
        # This is a simplified test - in a real scenario, we would simulate network failures
        connected_devices = [dev for dev in self.devices.values() if "socket" in dev]
        if not connected_devices:
            logger.warning("No connected devices, skipping network recovery test")
            return False
        
        # Test heartbeat mechanism
        device = connected_devices[0]
        try:
            heartbeat = {
                "type": "HEARTBEAT",
                "payload": {
                    "deviceId": "test_client",
                    "timestamp": int(time.time() * 1000)
                },
                "timestamp": int(time.time() * 1000),
                "messageId": str(uuid.uuid4())
            }
            
            device["socket"].send(json.dumps(heartbeat).encode() + b'\n')
            
            # Wait for heartbeat acknowledgment
            response = device["socket"].recv(2048).decode().strip()
            ack = json.loads(response)
            
            if ack.get("type") == "HEARTBEAT_ACK":
                logger.info("Network recovery test successful - heartbeat acknowledged")
                return True
            else:
                logger.warning("Network recovery test failed - no heartbeat acknowledgment")
                return False
                
        except Exception as e:
            logger.error(f"Network recovery test failed: {e}")
            return False
    
    def print_test_summary(self):
        """Print test results summary"""
        logger.info("\n" + "="*60)
        logger.info("NETWORKING INFRASTRUCTURE TEST SUMMARY")
        logger.info("="*60)
        
        total_tests = len(self.test_results)
        passed_tests = sum(1 for result in self.test_results.values() if result["status"] == "PASSED")
        failed_tests = sum(1 for result in self.test_results.values() if result["status"] == "FAILED")
        error_tests = sum(1 for result in self.test_results.values() if result["status"] == "ERROR")
        
        for test_name, result in self.test_results.items():
            status = result["status"]
            if status == "PASSED":
                logger.info(f"✓ {test_name}: PASSED")
            elif status == "FAILED":
                logger.info(f"✗ {test_name}: FAILED")
            else:
                logger.info(f"⚠ {test_name}: ERROR - {result.get('error', 'Unknown error')}")
        
        logger.info("-"*60)
        logger.info(f"Total Tests: {total_tests}")
        logger.info(f"Passed: {passed_tests}")
        logger.info(f"Failed: {failed_tests}")
        logger.info(f"Errors: {error_tests}")
        logger.info(f"Success Rate: {passed_tests/total_tests:.1%}")
        logger.info("="*60)
        
        # Cleanup connections
        for device in self.devices.values():
            if "socket" in device:
                try:
                    device["socket"].close()
                except:
                    pass

async def main():
    """Main test execution"""
    test_suite = NetworkingTestSuite()
    await test_suite.run_all_tests()

if __name__ == "__main__":
    asyncio.run(main())