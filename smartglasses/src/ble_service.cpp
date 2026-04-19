#include "ble_service.h"

// =============================================================================
// Generic BLE Connection Service — Implementation
// =============================================================================
// This file contains the complete, self-contained BLE connection service.
// It is independent of any specific application (camera, audio, sensors, etc.)
// and can be reused across different firmware projects.
//
// Any BLE-capable device can search for and connect to the peripheral set up
// by this module — no proprietary app or SDK is required.
// =============================================================================

// ---- Standard BLE SIG UUIDs ------------------------------------------------
// Device Information Service (0x180A)
static const uint16_t DIS_SERVICE_UUID                  = 0x180A;
static const uint16_t DIS_MANUFACTURER_NAME_CHAR_UUID   = 0x2A29;
static const uint16_t DIS_MODEL_NUMBER_CHAR_UUID        = 0x2A24;
static const uint16_t DIS_FIRMWARE_REV_CHAR_UUID        = 0x2A26;
static const uint16_t DIS_HARDWARE_REV_CHAR_UUID        = 0x2A27;

// Battery Service (0x180F)
static const uint16_t BAS_SERVICE_UUID                  = 0x180F;
static const uint16_t BAS_BATTERY_LEVEL_CHAR_UUID       = 0x2A19;

// ---- Module state -----------------------------------------------------------
static BLEServer          *s_server                    = nullptr;
static BLECharacteristic  *s_batteryLevelChar          = nullptr;
static bool                s_connected                 = false;
static BLEConnectCallback     s_onConnectCb            = nullptr;
static BLEDisconnectCallback  s_onDisconnectCb         = nullptr;
static const char            *s_deviceName             = "BLE-Device";
static uint32_t            s_serviceStartMs            = 0;
static uint32_t            s_connectCount              = 0;
static uint32_t            s_disconnectCount           = 0;
static uint32_t            s_lastConnectMs             = 0;
static uint32_t            s_lastDisconnectMs          = 0;

// ---- Internal server callback -----------------------------------------------
class GenericServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *server) override {
        s_connected = true;
        s_connectCount++;
        s_lastConnectMs = millis();
        Serial.println("[BLE] Client connected.");
        Serial.printf(
            "SESSION_EVIDENCE:DEV=GLASSES_BLE;EV=BLE_CONNECT;UP=%lu;CC=%lu;DC=%lu\n",
            (unsigned long) ble_service_uptime_ms(),
            (unsigned long) s_connectCount,
            (unsigned long) s_disconnectCount
        );
        if (s_onConnectCb) {
            s_onConnectCb(server);
        }
    }

    void onDisconnect(BLEServer *server) override {
        s_connected = false;
        s_disconnectCount++;
        s_lastDisconnectMs = millis();
        Serial.println("[BLE] Client disconnected. Restarting advertising.");
        Serial.printf(
            "SESSION_EVIDENCE:DEV=GLASSES_BLE;EV=BLE_DISCONNECT;UP=%lu;CC=%lu;DC=%lu\n",
            (unsigned long) ble_service_uptime_ms(),
            (unsigned long) s_connectCount,
            (unsigned long) s_disconnectCount
        );
        // Automatically restart advertising so other devices can find us
        BLEDevice::startAdvertising();
        if (s_onDisconnectCb) {
            s_onDisconnectCb(server);
        }
    }
};

// ---- Public API implementation ----------------------------------------------

void ble_service_init(const BLEServiceConfig &config) {
    s_deviceName     = config.deviceName;
    s_onConnectCb    = config.onConnect;
    s_onDisconnectCb = config.onDisconnect;
    s_serviceStartMs = millis();
    s_connectCount = 0;
    s_disconnectCount = 0;
    s_lastConnectMs = 0;
    s_lastDisconnectMs = 0;

    Serial.println("[BLE] Initializing BLE stack...");

    // --- 1. Initialize the BLE device ----------------------------------------
    BLEDevice::init(s_deviceName);
    BLEDevice::setMTU(config.mtuSize);

    // Set transmit power
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, config.txPower);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV,     config.txPower);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_SCAN,    config.txPower);

    // --- 2. Create server and register callbacks -----------------------------
    s_server = BLEDevice::createServer();
    s_server->setCallbacks(new GenericServerCallbacks());

    // --- 3. Device Information Service (standard, read-only) -----------------
    BLEService *disService = s_server->createService(DIS_SERVICE_UUID);

    BLECharacteristic *mfrChar = disService->createCharacteristic(
        DIS_MANUFACTURER_NAME_CHAR_UUID, BLECharacteristic::PROPERTY_READ);
    mfrChar->setValue(config.manufacturerName);

    BLECharacteristic *modelChar = disService->createCharacteristic(
        DIS_MODEL_NUMBER_CHAR_UUID, BLECharacteristic::PROPERTY_READ);
    modelChar->setValue(config.modelNumber);

    BLECharacteristic *fwChar = disService->createCharacteristic(
        DIS_FIRMWARE_REV_CHAR_UUID, BLECharacteristic::PROPERTY_READ);
    fwChar->setValue(config.firmwareRevision);

    BLECharacteristic *hwChar = disService->createCharacteristic(
        DIS_HARDWARE_REV_CHAR_UUID, BLECharacteristic::PROPERTY_READ);
    hwChar->setValue(config.hardwareRevision);

    disService->start();

    // --- 4. Battery Service (standard, read + notify) ------------------------
    BLEService *basService = s_server->createService(BAS_SERVICE_UUID);

    s_batteryLevelChar = basService->createCharacteristic(
        BAS_BATTERY_LEVEL_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);

    BLE2902 *batteryCcc = new BLE2902();
    batteryCcc->setNotifications(true);
    s_batteryLevelChar->addDescriptor(batteryCcc);

    // Set initial battery level to 0 (caller should update after init)
    uint8_t initLevel = 0;
    s_batteryLevelChar->setValue(&initLevel, 1);

    basService->start();

    // --- 5. Configure advertising --------------------------------------------
    // The advertising setup uses standard BLE advertising data so that ANY
    // generic BLE scanner app (nRF Connect, LightBlue, BLE Scanner, etc.)
    // on ANY platform (Android, iOS, Windows, Linux, macOS) can discover
    // and connect to this device.
    BLEAdvertising *advertising = BLEDevice::getAdvertising();

    // Primary advertising data — includes device name & flags
    BLEAdvertisementData advData;
    advData.setName(s_deviceName);
    advData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
    advData.setAppearance(config.appearance);
    advertising->setAdvertisementData(advData);

    // Scan response — includes device name again for maximum compatibility
    BLEAdvertisementData scanResp;
    scanResp.setName(s_deviceName);
    advertising->setScanResponseData(scanResp);

    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);   // 7.5 ms minimum connection interval hint
    advertising->setMaxPreferred(0x12);   // 22.5 ms maximum connection interval hint

    BLEDevice::startAdvertising();

    Serial.println("[BLE] Initialized and advertising.");
    Serial.print("[BLE] Device name: ");
    Serial.println(s_deviceName);
}

bool ble_service_is_connected() {
    return s_connected;
}

void ble_service_start_advertising() {
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Advertising restarted.");
}

void ble_service_stop_advertising() {
    BLEDevice::getAdvertising()->stop();
    Serial.println("[BLE] Advertising stopped.");
}

BLEServer *ble_service_get_server() {
    return s_server;
}

// ---- Battery Service helpers ------------------------------------------------

void ble_service_set_battery_level(uint8_t level) {
    if (!s_batteryLevelChar) return;
    s_batteryLevelChar->setValue(&level, 1);
    if (s_connected) {
        s_batteryLevelChar->notify();
    }
}

BLECharacteristic *ble_service_get_battery_characteristic() {
    return s_batteryLevelChar;
}

uint32_t ble_service_uptime_ms() {
    if (s_serviceStartMs == 0) return 0;
    return millis() - s_serviceStartMs;
}

uint32_t ble_service_connect_count() {
    return s_connectCount;
}

uint32_t ble_service_disconnect_count() {
    return s_disconnectCount;
}

uint32_t ble_service_last_connect_ms() {
    return s_lastConnectMs;
}

uint32_t ble_service_last_disconnect_ms() {
    return s_lastDisconnectMs;
}

// ---- Custom Service Registration -------------------------------------------

BLEService *ble_service_create_service(const char *uuid) {
    if (!s_server) return nullptr;
    return s_server->createService(uuid);
}

BLEService *ble_service_create_service(BLEUUID uuid) {
    if (!s_server) return nullptr;
    return s_server->createService(uuid);
}

void ble_service_start_custom_service(BLEService *service, BLEUUID serviceUUID) {
    if (!service) return;
    service->start();

    // Add the custom service UUID to the advertising payload so scanners
    // can filter by it
    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(serviceUUID);

    // Restart advertising with the updated payload
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Custom service started and added to advertising.");
}
