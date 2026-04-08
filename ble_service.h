#ifndef BLE_SERVICE_H
#define BLE_SERVICE_H

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// =============================================================================
// Generic BLE Connection Service
// =============================================================================
// This module provides a generic, device-agnostic Bluetooth Low Energy (BLE)
// connection service. Any BLE-capable device (phone, tablet, laptop, other
// microcontrollers, etc.) can search for, discover, and connect to the smart
// glasses through this service.
//
// Features:
//   - Configurable device name and appearance
//   - Standard Device Information Service (DIS) for manufacturer, model,
//     firmware revision, and hardware revision
//   - Standard Battery Service with notification support
//   - Generic advertising with scan response (visible to all BLE scanners)
//   - Connection/disconnection event callbacks for application logic
//   - Custom service registration API for application-specific services
//   - MTU negotiation for efficient data transfer
//   - Power-optimized advertising intervals
// =============================================================================

// ---- Connection event callback types ----------------------------------------
// Applications register these to get notified of BLE connection lifecycle events.
typedef void (*BLEConnectCallback)(BLEServer *server);
typedef void (*BLEDisconnectCallback)(BLEServer *server);

// ---- BLE Service Configuration ----------------------------------------------
// Pass this struct to ble_service_init() to configure the BLE peripheral.
// All string fields must remain valid for the lifetime of the BLE service.
struct BLEServiceConfig {
    // Device identity
    const char *deviceName;          // Advertised device name (e.g. "SmartGlasses")
    const char *manufacturerName;    // Device Information Service: manufacturer
    const char *modelNumber;         // Device Information Service: model number
    const char *firmwareRevision;    // Device Information Service: firmware version
    const char *hardwareRevision;    // Device Information Service: hardware revision

    // Advertising parameters
    uint16_t    advMinInterval;      // Minimum advertising interval (in 0.625 ms units)
    uint16_t    advMaxInterval;      // Maximum advertising interval (in 0.625 ms units)
    uint16_t    appearance;          // BLE appearance value (e.g. 0x03C1 = Eyewear)

    // Connection parameters
    uint16_t    mtuSize;             // Requested MTU size (e.g. 517)

    // TX power
    esp_power_level_t txPower;       // Transmit power level

    // Application callbacks (may be nullptr)
    BLEConnectCallback    onConnect;
    BLEDisconnectCallback onDisconnect;
};

// ---- Public API -------------------------------------------------------------

/// Initialize the BLE stack, create the server, Device Information Service,
/// Battery Service, and start advertising. Call once during setup().
void ble_service_init(const BLEServiceConfig &config);

/// Returns true when a BLE central is connected.
bool ble_service_is_connected();

/// Restart advertising (e.g. after an intentional disconnect).
void ble_service_start_advertising();

/// Stop advertising.
void ble_service_stop_advertising();

/// Get the underlying BLE server (for creating custom services).
BLEServer *ble_service_get_server();

// ---- Battery Service helpers ------------------------------------------------

/// Update the battery level (0-100) and notify connected clients.
void ble_service_set_battery_level(uint8_t level);

/// Get the Battery Level characteristic (for direct manipulation).
BLECharacteristic *ble_service_get_battery_characteristic();

// ---- Custom Service Registration -------------------------------------------

/// Create and start a custom BLE service on the server.
/// Returns the BLEService pointer so the caller can add characteristics before
/// calling ble_service_start_custom_service().
BLEService *ble_service_create_service(const char *uuid);
BLEService *ble_service_create_service(BLEUUID uuid);

/// After adding characteristics to a custom service, call this to start it
/// and add its UUID to the advertising payload.
void ble_service_start_custom_service(BLEService *service, BLEUUID serviceUUID);

#endif // BLE_SERVICE_H
