#include "app.h"
#include "ble_service.h"

#include <BLE2902.h>

#include "config.h" // Use config.h for all configurations
#include "esp_camera.h"
#include "esp_heap_caps.h"
#include "esp_sleep.h"
#include "esp_system.h"
#include <string>
#include <stdarg.h>

// Battery state
float batteryVoltage = 0.0f;
int batteryPercentage = 0;
unsigned long lastBatteryCheck = 0;

// Device power state
bool deviceActive = true;
device_state_t deviceState = DEVICE_BOOTING;

// Button and LED state
volatile bool buttonPressed = false;
unsigned long buttonPressTime = 0;
led_status_t ledMode = LED_BOOT_SEQUENCE;

// Gentle power optimization
unsigned long lastActivity = 0;
bool powerSaveMode = false;

// Light sleep toggle (kept for battery experiments)
bool lightSleepEnabled = true;

// ---------------------------------------------------------------------------------
// BLE - Camera-specific service (connection handled by ble_service module)
// ---------------------------------------------------------------------------------

// Camera Service UUIDs
static BLEUUID serviceUUID(CAMERA_SERVICE_UUID);
static BLEUUID photoDataUUID(PHOTO_DATA_UUID);
static BLEUUID photoControlUUID(PHOTO_CONTROL_UUID);

// Camera-specific characteristics
BLECharacteristic *photoDataCharacteristic;
BLECharacteristic *photoControlCharacteristic;

// Runtime capture state
bool connected = false;
bool isCapturingPhotos = false;
int captureInterval = 0; // capture interval in ms
unsigned long lastCaptureTime = 0;
bool passiveBurstFallbackEnabled = false;
bool captureRuntimeEnabled = true;
unsigned long lastBurstTriggerTime = 0;

enum capture_transfer_intent_t : uint8_t {
    TRANSFER_INTENT_IDLE = 0,
    TRANSFER_INTENT_SINGLE = 1,
    TRANSFER_INTENT_BURST = 2
};
capture_transfer_intent_t captureTransferIntent = TRANSFER_INTENT_IDLE;

// session timing
static unsigned long sessionStartMs = 0;
static unsigned long lastSessionHeartbeatMs = 0;
static const unsigned long SESSION_HEARTBEAT_INTERVAL_MS = 60000;

size_t sent_photo_bytes = 0;
size_t sent_photo_frames = 0;
bool photoDataUploading = false;

// Burst capture state
bool burstMode = false;
uint8_t burstTotalFrames = 0;
uint8_t burstCurrentFrame = 0;       // Logical frame index being transferred
uint8_t burstCapturedFrames = 0;     // Total frames captured in burst
camera_fb_t *burstBuffers[BURST_MAX_COUNT] = {nullptr};
size_t burstSizes[BURST_MAX_COUNT] = {0};
uint8_t *burstJpegData[BURST_MAX_COUNT] = {nullptr};
uint32_t burstCaptureTimesMs[BURST_MAX_COUNT] = {0};
uint8_t burstUploadOrder[BURST_MAX_COUNT] = {0};
uint16_t burstInterCaptureDelayMs = BURST_INTER_CAPTURE_DELAY;

static const uint8_t BURST_CADENCE_HINT_CMD = 0x05;
static const uint8_t BURST_CADENCE_HINT_VERSION = 0x01;
static const uint8_t BURST_CADENCE_ACK_MARKER_LO = 0xFD;
static const uint8_t BURST_CADENCE_ACK_MARKER_HI = 0xFF;
static const uint8_t BURST_CADENCE_ACK_APPLIED = 0x00;
static const uint8_t BURST_CADENCE_ACK_CLAMPED = 0x01;
static const uint8_t BURST_CADENCE_ACK_REJECTED = 0x02;
static const uint8_t TRANSFER_ABORT_MARKER_LO = 0xFC;
static const uint8_t TRANSFER_ABORT_MARKER_HI = 0xFF;
static const uint8_t TRANSFER_ABORT_REASON_UNKNOWN = 0x00;
static const uint8_t TRANSFER_ABORT_REASON_CMD_SINGLE = 0x01;
static const uint8_t TRANSFER_ABORT_REASON_CMD_BURST = 0x02;
static const uint8_t TRANSFER_ABORT_REASON_CAPTURE_DISABLED = 0x03;
static const uint8_t TRANSFER_ABORT_REASON_BUTTON_PRIORITY = 0x04;
static const uint8_t TRANSFER_ABORT_REASON_RUNTIME_GUARD = 0x05;

//
// Camera Frame
//
camera_fb_t *fb = nullptr;
image_orientation_t current_photo_orientation = ORIENTATION_0_DEGREES;

// Forward declarations
void handlePhotoControl(int8_t controlValue);
void handlePhotoControlPayload(const uint8_t *payload, size_t payloadLen);
void readBatteryLevel();
void updateBatteryService();
void IRAM_ATTR buttonISR();
void handleButton();
void updateLED();
void blinkLED(int count, int delayMs);
void enterPowerSave();
void exitPowerSave();
void shutdownDevice();
void enableLightSleep();
bool take_photo();
bool take_burst(uint8_t count);
void freeBurstBuffers();
void buildBurstUploadOrder();
uint16_t minimumBurstDelayForMotionWindow(uint8_t frameCount);
uint16_t clampBurstInterCaptureDelay(uint16_t requestedDelayMs, uint8_t frameCount);
void sendBurstCadenceAck(uint8_t version, uint8_t ackCode, uint16_t appliedDelayMs);
void sendTransferAbortMarker(uint8_t reasonCode, const char *source);
void abortActiveCaptureTransfer(uint8_t reasonCode, const char *source);
void setCaptureRuntimeEnabled(bool enabled, const char *source);

static const char *resetReasonText(esp_reset_reason_t reason)
{
    switch (reason) {
    case ESP_RST_POWERON:
        return "POWER_ON";
    case ESP_RST_EXT:
        return "EXTERNAL";
    case ESP_RST_SW:
        return "SOFTWARE";
    case ESP_RST_PANIC:
        return "PANIC";
    case ESP_RST_INT_WDT:
        return "INT_WDT";
    case ESP_RST_TASK_WDT:
        return "TASK_WDT";
    case ESP_RST_WDT:
        return "WDT";
    case ESP_RST_DEEPSLEEP:
        return "DEEP_SLEEP";
    case ESP_RST_BROWNOUT:
        return "BROWNOUT";
    case ESP_RST_SDIO:
        return "SDIO";
    default:
        return "UNKNOWN";
    }
}

static void emitSessionEvidence(const char *event, const char *detailsFmt = nullptr, ...)
{
    char details[180] = {0};
    if (detailsFmt != nullptr) {
        va_list args;
        va_start(args, detailsFmt);
        vsnprintf(details, sizeof(details), detailsFmt, args);
        va_end(args);
    }

    unsigned long uptimeMs = 0;
    if (sessionStartMs > 0) {
        uptimeMs = millis() - sessionStartMs;
    }

    if (details[0] != '\0') {
        Serial.printf(
            "SESSION_EVIDENCE:DEV=GLASSES;EV=%s;UP=%lu;%s\n",
            event,
            uptimeMs,
            details
        );
    } else {
        Serial.printf(
            "SESSION_EVIDENCE:DEV=GLASSES;EV=%s;UP=%lu\n",
            event,
            uptimeMs
        );
    }
}

// 
// Button ISR
// 
void IRAM_ATTR buttonISR()
{
    buttonPressed = true;
}

// 
// LED Functions
// 
void updateLED()
{
    unsigned long now = millis();
    static unsigned long bootStartTime = 0;
    static unsigned long powerOffStartTime = 0;

    switch (ledMode) {
    case LED_BOOT_SEQUENCE:
        if (bootStartTime == 0)
            bootStartTime = now;

        // 5 quick blinks over 1.5 seconds total (inverted logic: HIGH=OFF, LOW=ON)
        if (now - bootStartTime < 1500) {
            int blinkPhase = ((now - bootStartTime) / 150) % 2;
            digitalWrite(STATUS_LED_PIN, !blinkPhase);
        } else {
            digitalWrite(STATUS_LED_PIN, HIGH); // OFF
            ledMode = LED_NORMAL_OPERATION;
            bootStartTime = 0;
        }
        break;

    case LED_POWER_OFF_SEQUENCE:
        if (powerOffStartTime == 0)
            powerOffStartTime = now;

        // 2 quick blinks over 800ms total (inverted logic: HIGH=OFF, LOW=ON)
        if (now - powerOffStartTime < 800) {
            int blinkPhase = ((now - powerOffStartTime) / 200) % 2;
            digitalWrite(STATUS_LED_PIN, !blinkPhase);
        } else {
            digitalWrite(STATUS_LED_PIN, HIGH); // OFF
            delay(100);
            shutdownDevice();
        }
        break;

    case LED_NORMAL_OPERATION:
    default:
        if (connected) {
            // Connected - LED solid ON
            digitalWrite(STATUS_LED_PIN, LOW);
        } else {
            // Disconnected - LED slow blink (1 sec on, 1 sec off)
            int blinkPhase = (now / 1000) % 2;
            digitalWrite(STATUS_LED_PIN, blinkPhase ? HIGH : LOW);
        }
        break;
    }
}

void blinkLED(int count, int delayMs)
{
    for (int i = 0; i < count; i++) {
        digitalWrite(STATUS_LED_PIN, HIGH);
        delay(delayMs);
        digitalWrite(STATUS_LED_PIN, LOW);
        delay(delayMs);
    }
}

// 
// Button Handling
// 
void handleButton()
{
    unsigned long now = millis();
    static unsigned long lastButtonTime = 0;
    static bool buttonDown = false;
    static bool shutdownTriggered = false;

    const bool processTransition = buttonPressed;
    const bool currentButtonState = !digitalRead(POWER_BUTTON_PIN); // Active low (pressed = true)

    // Transition lane (ISR-gated): debounce + press/release edges + short-press path.
    if (processTransition) {
        // Button press debouncing
        if (now - lastButtonTime >= BUTTON_DEBOUNCE_MS) {
            if (currentButtonState && !buttonDown) {
                // Button just pressed
                buttonPressTime = now;
                buttonDown = true;
                shutdownTriggered = false;
                lastButtonTime = now;

            } else if (!currentButtonState && buttonDown) {
                // Button released
                buttonDown = false;
                unsigned long pressDuration = now - buttonPressTime;
                lastButtonTime = now;

                // Turn off LED in case it was on from hold
                digitalWrite(STATUS_LED_PIN, HIGH); // LED OFF

                if (pressDuration < POWER_OFF_PRESS_MS && pressDuration >= 10) {
                    // Short press - take a priority burst
                    lastActivity = now;
                    if (powerSaveMode) {
                        exitPowerSave();
                    }

                    if (!captureRuntimeEnabled) {
                        Serial.println("Button pressed: Capture runtime disabled, ignoring burst request.");
                        emitSessionEvidence(
                            "BUTTON_BURST",
                            "BLE=%d;UPLOAD=%d;CAP_EN=0",
                            connected ? 1 : 0,
                            photoDataUploading ? 1 : 0
                        );
                    } else if (!connected) {
                        Serial.println("Button pressed: Not connected, cannot capture burst.");
                        emitSessionEvidence("BUTTON_BURST", "BLE=0;UPLOAD=%d", photoDataUploading ? 1 : 0);
                    } else {
                        const bool hadInFlightCapture = photoDataUploading || burstMode || isCapturingPhotos;
                        if (hadInFlightCapture) {
                            emitSessionEvidence(
                                "BUTTON_BURST_OVERRIDE",
                                "UPLOAD=%d;BURST=%d;INTERVAL=%d",
                                photoDataUploading ? 1 : 0,
                                burstMode ? 1 : 0,
                                isCapturingPhotos ? 1 : 0
                            );
                            abortActiveCaptureTransfer(
                                TRANSFER_ABORT_REASON_BUTTON_PRIORITY,
                                "BUTTON_PRIORITY_BURST"
                            );
                            isCapturingPhotos = false;
                            captureInterval = 0;
                            passiveBurstFallbackEnabled = false;
                            Serial.println("Button burst priority override: flushed active capture state.");
                        }

                        Serial.printf(
                            "Button press: Capturing priority burst (%u frames @ %ums).\n",
                            (unsigned int) BURST_DEFAULT_COUNT,
                            (unsigned int) burstInterCaptureDelayMs
                        );
                        emitSessionEvidence(
                            "BUTTON_BURST",
                            "BLE=1;PRIORITY=1;FRAMES=%u;DELAY_MS=%u",
                            (unsigned int) BURST_DEFAULT_COUNT,
                            (unsigned int) burstInterCaptureDelayMs
                        );

                        if (take_burst(BURST_DEFAULT_COUNT)) {
                            Serial.println("Button burst capture successful. Starting upload.");
                            captureTransferIntent = TRANSFER_INTENT_BURST;
                            emitSessionEvidence("STREAM_INTENT", "MODE=BURST;SRC=BUTTON");
                            photoDataUploading = true;
                            sent_photo_bytes = 0;
                            sent_photo_frames = 0;
                            lastCaptureTime = now;
                            lastBurstTriggerTime = now;
                        } else {
                            Serial.println("Button burst capture failed.");
                            captureTransferIntent = TRANSFER_INTENT_IDLE;
                            emitSessionEvidence("BUTTON_BURST_FAILED", "BLE=1;FRAMES=%u", (unsigned int) BURST_DEFAULT_COUNT);
                        }
                    }
                }
            }
        }

        buttonPressed = false;
    }

    // Hold-monitor lane: continue hold-duration checks while button remains down.
    if (currentButtonState && buttonDown) {
        unsigned long holdDuration = now - buttonPressTime;

        if (holdDuration >= POWER_OFF_PRESS_MS && !shutdownTriggered) {
            // Hold threshold reached: start the power-off LED sequence then deep sleep.
            shutdownTriggered = true;
            ledMode = LED_POWER_OFF_SEQUENCE;
            Serial.printf(
                "Button hold reached %lums (threshold=%dms) - shutting down.\n",
                holdDuration,
                POWER_OFF_PRESS_MS
            );
        }
    }
}

// 
// Power Management
// 
void enterPowerSave()
{
    if (!powerSaveMode) {
        setCpuFrequencyMhz(MIN_CPU_FREQ_MHZ); // 40MHz for idle
        powerSaveMode = true;
    }
}

void exitPowerSave()
{
    if (powerSaveMode) {
        setCpuFrequencyMhz(NORMAL_CPU_FREQ_MHZ); // Back to 80MHz
        powerSaveMode = false;
    }
}

void shutdownDevice()
{
    static bool waitingForReleaseLogged = false;
    const bool buttonStillPressed = !digitalRead(POWER_BUTTON_PIN); // Active low

    // Stop any capture loop
    isCapturingPhotos = false;

    // Keep LED OFF while waiting to enter deep sleep.
    digitalWrite(STATUS_LED_PIN, HIGH);

    // Prevent immediate wake by waiting for button release before arming wake.
    if (buttonStillPressed) {
        if (!waitingForReleaseLogged) {
            Serial.println("Shutdown pending: waiting for button release before deep sleep.");
            emitSessionEvidence("DEEP_SLEEP_WAIT_RELEASE", "REASON=button");
            waitingForReleaseLogged = true;
        }
        return;
    }
    waitingForReleaseLogged = false;

    Serial.println("Shutting down device.");
    emitSessionEvidence("DEEP_SLEEP_ENTER", "REASON=button");

    // Disconnect BLE
    if (connected) {
        Serial.println("Disconnecting BLE.");
    }

    // Enter deep sleep
    esp_sleep_enable_ext0_wakeup(static_cast<gpio_num_t>(POWER_BUTTON_PIN), 0); // Wake on button press
    Serial.println("Entering deep sleep.");
    delay(100);
    esp_deep_sleep_start();
}

//
// BLE Connection Callbacks (invoked by the generic ble_service module)
//
static void onBLEConnect(BLEServer *server)
{
    connected = true;
    lastActivity = millis(); // Register activity - prevents sleep
    Serial.println("App: BLE client connected.");
    emitSessionEvidence(
        "BLE_CONNECT",
        "CC=%lu;DC=%lu",
        (unsigned long) ble_service_connect_count(),
        (unsigned long) ble_service_disconnect_count()
    );
    lastBurstTriggerTime = millis();
    emitSessionEvidence(
        "PASSIVE_CADENCE",
        "CAP=%d;INT_MS=%d;BURST_DELAY_MS=%u;FALLBACK_EN=%d;FALLBACK_INT_MS=%u",
        isCapturingPhotos ? 1 : 0,
        captureInterval,
        (unsigned int) burstInterCaptureDelayMs,
        passiveBurstFallbackEnabled ? 1 : 0,
        (unsigned int) PASSIVE_BURST_FALLBACK_INTERVAL_MS
    );
    // Send current battery level on connect
    updateBatteryService();
}

static void onBLEDisconnect(BLEServer *server)
{
    connected = false;
    Serial.println("App: BLE client disconnected.");
    emitSessionEvidence(
        "BLE_DISCONNECT",
        "CC=%lu;DC=%lu;LAST_CONN_MS=%lu",
        (unsigned long) ble_service_connect_count(),
        (unsigned long) ble_service_disconnect_count(),
        (unsigned long) ble_service_last_connect_ms()
    );
};

class PhotoControlCallback : public BLECharacteristicCallbacks
{
    void onWrite(BLECharacteristic *characteristic) override
    {
        const std::string payload = characteristic->getValue();
        if (payload.empty()) return;

        const uint8_t *data = reinterpret_cast<const uint8_t *>(payload.data());
        const size_t payloadLen = payload.size();
        const int8_t received = static_cast<int8_t>(data[0]);

        Serial.printf(
            "PhotoControl received: cmd=%d bytes=%u\n",
            received,
            static_cast<unsigned int>(payloadLen)
        );

        lastActivity = millis(); // Register activity - prevents sleep
        handlePhotoControlPayload(data, payloadLen);
    }
};

// 
// Battery Functions
// 
void readBatteryLevel()
{
    // Take multiple ADC readings for stability
    int adcSum = 0;
    for (int i = 0; i < 10; i++) {
        int value = analogRead(BATTERY_ADC_PIN);
        adcSum += value;
        delay(10);
    }
    int adcValue = adcSum / 10;

    // ESP32-S3 ADC: 12-bit (0-4095), reference voltage ~3.3V
    float adcVoltage = (adcValue / 4095.0f) * 3.3f;

    // Apply voltage divider ratio to get actual battery voltage
    batteryVoltage = adcVoltage * VOLTAGE_DIVIDER_RATIO;

    // Clamp voltage to reasonable range
    if (batteryVoltage > 5.0f)
        batteryVoltage = 5.0f;
    if (batteryVoltage < 2.5f)
        batteryVoltage = 2.5f;

    // Load-compensated battery calculation (accounts for voltage sag under load)
    float loadCompensatedMax = BATTERY_MAX_VOLTAGE;
    float loadCompensatedMin = BATTERY_MIN_VOLTAGE;

    // More accurate percentage calculation for load conditions
    if (batteryVoltage >= loadCompensatedMax) {
        batteryPercentage = 100;
    } else if (batteryVoltage <= loadCompensatedMin) {
        batteryPercentage = 0;
    } else {
        float range = loadCompensatedMax - loadCompensatedMin;
        batteryPercentage = (int) (((batteryVoltage - loadCompensatedMin) / range) * 100.0f);
    }

    // Smooth percentage changes to avoid jumpy readings
    static int lastBatteryPercentage = batteryPercentage;
    if (abs(batteryPercentage - lastBatteryPercentage) > 5) {
        batteryPercentage = lastBatteryPercentage + (batteryPercentage > lastBatteryPercentage ? 2 : -2);
    }
    lastBatteryPercentage = batteryPercentage;

    // Clamp percentage
    if (batteryPercentage > 100)
        batteryPercentage = 100;
    if (batteryPercentage < 0)
        batteryPercentage = 0;

    // Battery status with load info
    Serial.print("Battery: ");
    Serial.print(batteryVoltage);
    Serial.print("V (");
    Serial.print(batteryPercentage);
    Serial.print("%) [Load-compensated: ");
    Serial.print(loadCompensatedMin);
    Serial.print("V-");
    Serial.print(loadCompensatedMax);
    Serial.println("V]");
}

void updateBatteryService()
{
    // Delegate to the generic BLE service module
    ble_service_set_battery_level((uint8_t) batteryPercentage);
}

// 
// configure_ble()
// Uses the generic ble_service module for connection, advertising, Device
// Information Service, and Battery Service.  Only the camera-specific custom
// service is set up here.
// 
void configure_ble()
{
    // --- 1. Initialize the generic BLE connection service --------------------
    BLEServiceConfig bleCfg = {};
    bleCfg.deviceName        = BLE_DEVICE_NAME;
    bleCfg.manufacturerName  = MANUFACTURER_NAME;
    bleCfg.modelNumber       = BLE_DEVICE_NAME;
    bleCfg.firmwareRevision  = FIRMWARE_VERSION_STRING;
    bleCfg.hardwareRevision  = HARDWARE_REVISION;
    bleCfg.advMinInterval    = BLE_ADV_MIN_INTERVAL;
    bleCfg.advMaxInterval    = BLE_ADV_MAX_INTERVAL;
    bleCfg.appearance        = 0x0000;  // Generic (avoids "app needed" prompt on Android)
    bleCfg.mtuSize           = BLE_MTU_SIZE;
    bleCfg.txPower           = BLE_TX_POWER;
    bleCfg.onConnect         = onBLEConnect;
    bleCfg.onDisconnect      = onBLEDisconnect;

    ble_service_init(bleCfg);

    // --- 2. Set initial battery level ----------------------------------------
    readBatteryLevel();
    ble_service_set_battery_level((uint8_t) batteryPercentage);

    // --- 3. Register camera-specific custom service --------------------------
    BLEService *cameraService = ble_service_create_service(serviceUUID);

    // Photo Data characteristic (read + notify)
    photoDataCharacteristic = cameraService->createCharacteristic(
        photoDataUUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    BLE2902 *ccc = new BLE2902();
    ccc->setNotifications(true);
    photoDataCharacteristic->addDescriptor(ccc);

    // Photo Control characteristic (write)
    photoControlCharacteristic = cameraService->createCharacteristic(
        photoControlUUID, BLECharacteristic::PROPERTY_WRITE);
    photoControlCharacteristic->setCallbacks(new PhotoControlCallback());
    uint8_t controlValue = 0;
    photoControlCharacteristic->setValue(&controlValue, 1);

    // Start the camera service and advertise its UUID
    ble_service_start_custom_service(cameraService, serviceUUID);

    Serial.println("Camera BLE service registered.");
}

//
// Camera capture helpers
//
bool take_photo()
{
    // Release any prior frame buffer first
    if (fb) {
        Serial.println("Releasing previous camera buffer.");
        esp_camera_fb_return(fb);
        fb = nullptr;
    }

    // Flush stale FIFO frames (fb_count is 3)
    for (int flush = 0; flush < 3; flush++) {
        camera_fb_t* stale = esp_camera_fb_get();
        if (stale) {
            Serial.printf("Flushed stale frame %d (%d bytes)\n", flush, stale->len);
            esp_camera_fb_return(stale);
        }
    }

    Serial.println("Capturing new photo.");
    fb = esp_camera_fb_get();
    if (!fb) {
        Serial.println("Failed to get camera frame buffer!");
        return false;
    }
    Serial.print("Photo captured: ");
    Serial.print(fb->len);
    Serial.println(" bytes.");

    // Validate JPEG header (must start with FFD8FF)
    if (fb->len < 4 || fb->buf[0] != 0xFF || fb->buf[1] != 0xD8 || fb->buf[2] != 0xFF) {
        Serial.print("ERROR: Invalid JPEG header. First 4 bytes: ");
        Serial.print(fb->buf[0], HEX);
        Serial.print(" ");
        Serial.print(fb->buf[1], HEX);
        Serial.print(" ");
        Serial.print(fb->buf[2], HEX);
        Serial.print(" ");
        Serial.println(fb->buf[3], HEX);
        esp_camera_fb_return(fb);
        fb = nullptr;
        return false;
    }
    Serial.println("JPEG header valid (FFD8FF).");

    // Fix orientation for the captured photo
    current_photo_orientation = FIXED_IMAGE_ORIENTATION;
    Serial.println("Photo oriented 180 degrees.");

    lastActivity = millis(); // Register activity
    return true;
}

// 
// Burst Capture Functions
// 
uint16_t minimumBurstDelayForMotionWindow(uint8_t frameCount)
{
    if (frameCount <= 1) {
        return BURST_INTER_CAPTURE_DELAY_MIN;
    }

    const uint32_t intervals = static_cast<uint32_t>(frameCount) - 1U;
    const uint32_t requiredDelayMs =
        (static_cast<uint32_t>(BURST_TARGET_SPAN_MS) + intervals - 1U) / intervals;

    uint16_t clampedDelayMs = static_cast<uint16_t>(requiredDelayMs);
    if (clampedDelayMs < BURST_INTER_CAPTURE_DELAY_MIN) {
        clampedDelayMs = BURST_INTER_CAPTURE_DELAY_MIN;
    }
    if (clampedDelayMs > BURST_INTER_CAPTURE_DELAY_MAX) {
        clampedDelayMs = BURST_INTER_CAPTURE_DELAY_MAX;
    }

    return clampedDelayMs;
}

uint16_t clampBurstInterCaptureDelay(uint16_t requestedDelayMs, uint8_t frameCount)
{
    uint16_t clampedDelayMs = requestedDelayMs;

    if (clampedDelayMs < BURST_INTER_CAPTURE_DELAY_MIN) {
        clampedDelayMs = BURST_INTER_CAPTURE_DELAY_MIN;
    }
    if (clampedDelayMs > BURST_INTER_CAPTURE_DELAY_MAX) {
        clampedDelayMs = BURST_INTER_CAPTURE_DELAY_MAX;
    }

    const uint16_t motionMinimumDelayMs = minimumBurstDelayForMotionWindow(frameCount);
    if (clampedDelayMs < motionMinimumDelayMs) {
        clampedDelayMs = motionMinimumDelayMs;
    }

    return clampedDelayMs;
}

void sendBurstCadenceAck(uint8_t version, uint8_t ackCode, uint16_t appliedDelayMs)
{
    if (!connected || photoDataCharacteristic == nullptr) return;

    uint8_t ackPayload[6] = {
        BURST_CADENCE_ACK_MARKER_LO,
        BURST_CADENCE_ACK_MARKER_HI,
        version,
        ackCode,
        static_cast<uint8_t>(appliedDelayMs & 0xFF),
        static_cast<uint8_t>((appliedDelayMs >> 8) & 0xFF)
    };

    photoDataCharacteristic->setValue(ackPayload, sizeof(ackPayload));
    photoDataCharacteristic->notify();
    delay(BLE_PHOTO_TRANSFER_DELAY);
}

void freeBurstBuffers()
{
    for (int i = 0; i < BURST_MAX_COUNT; i++) {
        if (burstBuffers[i] != nullptr) {
            if (burstJpegData[i] != nullptr) {
                free(burstJpegData[i]);
                burstJpegData[i] = nullptr;
            }
            esp_camera_fb_return(burstBuffers[i]);
            burstBuffers[i] = nullptr;
        }
        burstSizes[i] = 0;
        burstCaptureTimesMs[i] = 0;
        burstUploadOrder[i] = static_cast<uint8_t>(i);
    }
    burstCapturedFrames = 0;
    burstCurrentFrame = 0;
    burstTotalFrames = 0;
    burstMode = false;
}

void buildBurstUploadOrder()
{
    for (uint8_t i = 0; i < burstCapturedFrames; i++) {
        burstUploadOrder[i] = i;
    }

    // Stable insertion sort by capture timestamp so upload order is FIFO.
    for (uint8_t i = 1; i < burstCapturedFrames; i++) {
        const uint8_t key = burstUploadOrder[i];
        const uint32_t keyTs = burstCaptureTimesMs[key];
        int j = static_cast<int>(i) - 1;
        while (j >= 0 && burstCaptureTimesMs[burstUploadOrder[j]] > keyTs) {
            burstUploadOrder[j + 1] = burstUploadOrder[j];
            j--;
        }
        burstUploadOrder[j + 1] = key;
    }

    Serial.print("Burst upload order (logical->slot@ts): ");
    for (uint8_t i = 0; i < burstCapturedFrames; i++) {
        const uint8_t slot = burstUploadOrder[i];
        Serial.printf("%u->%u@%u ",
            static_cast<unsigned int>(i),
            static_cast<unsigned int>(slot),
            static_cast<unsigned int>(burstCaptureTimesMs[slot])
        );
    }
    Serial.println();
}

static uint8_t clampBurstCountForMemory(uint8_t requestedCount)
{
    // Keep a reserve for BLE/task allocations and only use a fixed fraction
    // of current free PSRAM for burst frame buffers.
    const size_t freePsram = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
    if (freePsram == 0) {
        Serial.println("Burst: free PSRAM unknown, skipping memory clamp.");
        return requestedCount;
    }

    size_t usablePsram = 0;
    if (freePsram > BURST_MEMORY_RESERVE_BYTES) {
        usablePsram = freePsram - BURST_MEMORY_RESERVE_BYTES;
    }

    const size_t budgetBytes = (usablePsram * BURST_MEMORY_BUDGET_PERCENT) / 100;
    const size_t frameEstimate = BURST_FRAME_ESTIMATE_BYTES > 0 ? BURST_FRAME_ESTIMATE_BYTES : 1;
    uint8_t maxByMemory = static_cast<uint8_t>(budgetBytes / frameEstimate);

    if (maxByMemory < BURST_MIN_COUNT) maxByMemory = BURST_MIN_COUNT;
    if (maxByMemory > BURST_MAX_COUNT) maxByMemory = BURST_MAX_COUNT;

    uint8_t clamped = requestedCount;
    if (clamped > maxByMemory) {
        clamped = maxByMemory;
    }

    Serial.printf(
        "Burst guard: free=%uB reserve=%uB budget=%uB est/frame=%uB maxFrames=%u\n",
        (unsigned int) freePsram,
        (unsigned int) BURST_MEMORY_RESERVE_BYTES,
        (unsigned int) budgetBytes,
        (unsigned int) frameEstimate,
        (unsigned int) maxByMemory
    );

    return clamped;
}

bool take_burst(uint8_t count)
{
    if (count < BURST_MIN_COUNT) count = BURST_MIN_COUNT;
    if (count > BURST_MAX_COUNT) count = BURST_MAX_COUNT;

    const uint8_t requestedCount = count;
    count = clampBurstCountForMemory(count);

    if (count < requestedCount) {
        Serial.printf(
            "Burst request clamped by memory guard: requested=%u, using=%u\n",
            (unsigned int) requestedCount,
            (unsigned int) count
        );
    }

    const uint16_t appliedInterCaptureDelayMs =
        clampBurstInterCaptureDelay(burstInterCaptureDelayMs, count);

    if (appliedInterCaptureDelayMs != burstInterCaptureDelayMs) {
        Serial.printf(
            "Burst delay adjusted for motion span: requested=%u applied=%u frames=%u target_span_ms=%u\n",
            (unsigned int) burstInterCaptureDelayMs,
            (unsigned int) appliedInterCaptureDelayMs,
            (unsigned int) count,
            (unsigned int) BURST_TARGET_SPAN_MS
        );
    }

    Serial.printf(
        "Burst capture: %u frames, %ums inter-frame delay\n",
        (unsigned int) count,
        (unsigned int) appliedInterCaptureDelayMs
    );

    freeBurstBuffers();

    if (fb) {
        esp_camera_fb_return(fb);
        fb = nullptr;
    }

    burstTotalFrames = count;
    burstMode = true;
    const uint32_t burstCaptureStartMs = static_cast<uint32_t>(millis());

    // Drain all stale frames from the FIFO queue (fb_count = 3)
    for (int flush = 0; flush < 3; flush++) {
        camera_fb_t* stale = esp_camera_fb_get();
        if (stale) {
            Serial.printf("Burst: flushed stale frame %d (%d bytes)\n", flush, stale->len);
            esp_camera_fb_return(stale);
        }
    }

    for (int i = 0; i < count; i++) {
        camera_fb_t *frame = nullptr;

        // Delay between frames, then enforce timestamps so each accepted
        // frame is newer than the previous one.
        if (i > 0) {
            delay(appliedInterCaptureDelayMs);
        }

        int discarded = 0;
        const uint32_t previousCaptureMs = (i > 0) ? burstCaptureTimesMs[i - 1] : 0U;
        const uint32_t minAcceptedMs =
            (i > 0 && previousCaptureMs > 0U) ? (previousCaptureMs + 1U) : burstCaptureStartMs;

        while (true) {
            camera_fb_t *candidate = esp_camera_fb_get();
            if (!candidate) {
                Serial.printf("Burst: Failed to get frame during temporal gate for frame %d\n", i);
                freeBurstBuffers();
                return false;
            }

            uint32_t frameMs = static_cast<uint32_t>(candidate->timestamp.tv_sec * 1000ULL
                               + candidate->timestamp.tv_usec / 1000ULL);
            if (frameMs == 0U) {
                frameMs = static_cast<uint32_t>(millis());
            }

            if (minAcceptedMs == 0U || frameMs >= minAcceptedMs) {
                frame = candidate;
                break;
            }

            esp_camera_fb_return(candidate);
            discarded++;
            if (discarded > 10) {
                // Safety valve: take next frame anyway, but log that the gate failed.
                Serial.printf(
                    "Burst: timestamp gate safety limit reached for frame %d (minAcceptedMs=%u)\n",
                    i,
                    static_cast<unsigned int>(minAcceptedMs)
                );
                frame = esp_camera_fb_get();
                break;
            }
        }

        if (discarded > 0) {
            Serial.printf(
                "Burst: discarded %d stale frame(s) via timestamp gate for frame %d\n",
                discarded,
                i
            );
        }

        if (!frame) {
            Serial.printf("Burst: Failed to capture frame %d\n", i);
            freeBurstBuffers();
            return false;
        }

        // Validate JPEG header
        if (frame->len < 4 || frame->buf[0] != 0xFF || frame->buf[1] != 0xD8 || frame->buf[2] != 0xFF) {
            Serial.printf("Burst: Invalid JPEG on frame %d\n", i);
            esp_camera_fb_return(frame);
            freeBurstBuffers();
            return false;
        }

        uint32_t captureMs = (uint32_t) ((frame->timestamp.tv_sec * 1000ULL) + (frame->timestamp.tv_usec / 1000ULL));
        if (captureMs == 0) {
            captureMs = (uint32_t) millis();
        }

        burstJpegData[i] = (uint8_t *)ps_malloc(frame->len);
        if (!burstJpegData[i]) {
            Serial.printf("Burst: Failed to allocate PSRAM for frame %d (%d bytes)\n", i, frame->len);
            esp_camera_fb_return(frame);
            freeBurstBuffers();
            return false;
        }
        memcpy(burstJpegData[i], frame->buf, frame->len);
        burstSizes[i] = frame->len;
        burstCaptureTimesMs[i] = captureMs;
        burstBuffers[i] = frame;

        esp_camera_fb_return(frame);
        burstBuffers[i] = nullptr; 

        Serial.printf("Burst frame %d: %d bytes captured\n", i, burstSizes[i]);
        burstCapturedFrames++;
    }

    buildBurstUploadOrder();
    Serial.printf("Burst capture complete: %d frames captured\n", burstCapturedFrames);
    burstCurrentFrame = 0;

    current_photo_orientation = FIXED_IMAGE_ORIENTATION;
    lastActivity = millis();
    return true;
}

void sendTransferAbortMarker(uint8_t reasonCode, const char *source)
{
    if (!connected || photoDataCharacteristic == nullptr) {
        return;
    }

    uint8_t marker[3] = {
        TRANSFER_ABORT_MARKER_LO,
        TRANSFER_ABORT_MARKER_HI,
        reasonCode
    };
    photoDataCharacteristic->setValue(marker, sizeof(marker));
    photoDataCharacteristic->notify();
    delay(BLE_PHOTO_TRANSFER_DELAY);

    emitSessionEvidence(
        "TRANSFER_ABORT_MARKER_SENT",
        "REASON=%u;SRC=%s",
        static_cast<unsigned int>(reasonCode),
        source != nullptr ? source : "UNKNOWN"
    );
}

void abortActiveCaptureTransfer(uint8_t reasonCode, const char *source)
{
    const char *origin = source != nullptr ? source : "UNKNOWN";
    const bool hadTransferState =
        photoDataUploading || fb != nullptr || burstMode ||
        sent_photo_bytes > 0 || sent_photo_frames > 0 || burstCapturedFrames > 0;

    if (hadTransferState) {
        sendTransferAbortMarker(reasonCode, origin);
        emitSessionEvidence(
            "TRANSFER_ABORT",
            "REASON=%u;SRC=%s;UPLOAD=%d;BURST=%d;BYTES=%u;CHUNKS=%u",
            static_cast<unsigned int>(reasonCode),
            origin,
            photoDataUploading ? 1 : 0,
            burstMode ? 1 : 0,
            static_cast<unsigned int>(sent_photo_bytes),
            static_cast<unsigned int>(sent_photo_frames)
        );
    }

    photoDataUploading = false;
    sent_photo_bytes = 0;
    sent_photo_frames = 0;
    captureTransferIntent = TRANSFER_INTENT_IDLE;

    if (fb) {
        esp_camera_fb_return(fb);
        fb = nullptr;
    }

    freeBurstBuffers();
}

void setCaptureRuntimeEnabled(bool enabled, const char *source)
{
    const char *origin = source != nullptr ? source : "UNKNOWN";

    if (captureRuntimeEnabled == enabled) {
        emitSessionEvidence(
            "CAPTURE_RUNTIME",
            "STATE=%s;SRC=%s;UNCHANGED=1",
            enabled ? "ENABLED" : "DISABLED",
            origin
        );
        return;
    }

    captureRuntimeEnabled = enabled;

    if (!enabled) {
        isCapturingPhotos = false;
        captureInterval = 0;
        passiveBurstFallbackEnabled = false;
        captureTransferIntent = TRANSFER_INTENT_IDLE;
        abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_CAPTURE_DISABLED, "CAPTURE_RUNTIME_DISABLED");
    } else {
        // Keep fallback OFF until the app explicitly arms interval capture.
        passiveBurstFallbackEnabled = false;
        // Preserve in-flight intent (especially button burst) when re-enabled.
        if (!photoDataUploading && !burstMode && fb == nullptr) {
            captureTransferIntent = TRANSFER_INTENT_IDLE;
        }
        lastBurstTriggerTime = millis();
    }

    emitSessionEvidence(
        "CAPTURE_RUNTIME",
        "STATE=%s;SRC=%s",
        enabled ? "ENABLED" : "DISABLED",
        origin
    );

    Serial.printf(
        "Capture runtime %s (source=%s)\n",
        enabled ? "ENABLED" : "DISABLED",
        origin
    );
}

// Photo control map
// 0x00 => disable capture runtime (capture/upload/fallback off)
// 0x01 => single photo
// 0x02 => interval capture
// 0x03 => enable capture runtime
// 0x04 => burst capture
// 0x05 => burst cadence hint payload [cmd, version, delay_lo, delay_hi, health]
void handlePhotoControlPayload(const uint8_t *payload, size_t payloadLen)
{
    if (payload == nullptr || payloadLen == 0) return;

    const uint8_t command = payload[0];
    if (command == BURST_CADENCE_HINT_CMD) {
        if (payloadLen < 5) {
            Serial.println("Cadence hint rejected: payload too short.");
            sendBurstCadenceAck(BURST_CADENCE_HINT_VERSION, BURST_CADENCE_ACK_REJECTED, burstInterCaptureDelayMs);
            return;
        }

        const uint8_t version = payload[1];
        const uint16_t requestedDelayMs =
            static_cast<uint16_t>(payload[2]) |
            static_cast<uint16_t>(payload[3] << 8);
        const uint8_t healthCode = payload[4];

        if (version != BURST_CADENCE_HINT_VERSION) {
            Serial.printf(
                "Cadence hint rejected: version=%u expected=%u\n",
                static_cast<unsigned int>(version),
                static_cast<unsigned int>(BURST_CADENCE_HINT_VERSION)
            );
            sendBurstCadenceAck(version, BURST_CADENCE_ACK_REJECTED, burstInterCaptureDelayMs);
            return;
        }

        const uint16_t appliedDelayMs =
            clampBurstInterCaptureDelay(requestedDelayMs, BURST_DEFAULT_COUNT);
        const uint8_t ackCode =
            (appliedDelayMs == requestedDelayMs) ? BURST_CADENCE_ACK_APPLIED : BURST_CADENCE_ACK_CLAMPED;

        burstInterCaptureDelayMs = appliedDelayMs;
        emitSessionEvidence(
            "CADENCE_HINT",
            "REQ=%u;APPLIED=%u;ACK=%u;HEALTH=%u",
            static_cast<unsigned int>(requestedDelayMs),
            static_cast<unsigned int>(appliedDelayMs),
            static_cast<unsigned int>(ackCode),
            static_cast<unsigned int>(healthCode)
        );
        Serial.printf(
            "Cadence hint applied: requested=%u applied=%u health=%u ack=%u\n",
            static_cast<unsigned int>(requestedDelayMs),
            static_cast<unsigned int>(appliedDelayMs),
            static_cast<unsigned int>(healthCode),
            static_cast<unsigned int>(ackCode)
        );
        sendBurstCadenceAck(version, ackCode, appliedDelayMs);
        return;
    }

    handlePhotoControl(static_cast<int8_t>(command));
}

static bool isBurstTransferInProgress()
{
    return burstMode || (photoDataUploading && captureTransferIntent == TRANSFER_INTENT_BURST);
}

void handlePhotoControl(int8_t controlValue)
{
    switch (controlValue) {
    case 0x00: // disable capture runtime
        Serial.println("Command: Disable capture runtime.");
        emitSessionEvidence("CMD_CAPTURE_DISABLE", "SRC=BLE");
        captureTransferIntent = TRANSFER_INTENT_IDLE;
        setCaptureRuntimeEnabled(false, "BLE_CMD");
        break;

    case 0x03: // enable capture runtime
        Serial.println("Command: Enable capture runtime.");
        emitSessionEvidence("CMD_CAPTURE_ENABLE", "SRC=BLE");
        setCaptureRuntimeEnabled(true, "BLE_CMD");
        break;
        
    case 0x01: // single photo
    case -1:   
        if (!captureRuntimeEnabled) {
            Serial.println("Command rejected: capture runtime disabled (single photo).");
            emitSessionEvidence("CMD_REJECTED", "CMD=0x01;REASON=CAPTURE_DISABLED");
            break;
        }
        if (isBurstTransferInProgress()) {
            Serial.println("Command: Single photo deferred while burst transfer is active.");
            emitSessionEvidence(
                "CMD_SINGLE_DEFERRED",
                "REASON=BURST_ACTIVE;UPLOAD=%d;BURST=%d;INTENT=%u",
                photoDataUploading ? 1 : 0,
                burstMode ? 1 : 0,
                static_cast<unsigned int>(captureTransferIntent)
            );
            break;
        }
        Serial.println("Command: Take single photo.");
        emitSessionEvidence("CMD_SINGLE_PHOTO", "SRC=BLE");
        // Cancel in-flight upload so the new single capture gets priority
        if (photoDataUploading || burstMode || fb != nullptr) {
            abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_CMD_SINGLE, "CMD_SINGLE_PHOTO");
            Serial.println("Cancelled previous upload for new capture.");
        }
        captureTransferIntent = TRANSFER_INTENT_SINGLE;
        emitSessionEvidence("STREAM_INTENT", "MODE=SINGLE;SRC=CMD_SINGLE");
        isCapturingPhotos = true;
        captureInterval = 0;
        passiveBurstFallbackEnabled = false;
        break;
        
    case 0x02: // start interval capture
        if (!captureRuntimeEnabled) {
            Serial.println("Command rejected: capture runtime disabled (interval capture).");
            emitSessionEvidence("CMD_REJECTED", "CMD=0x02;REASON=CAPTURE_DISABLED");
            break;
        }
        if (isBurstTransferInProgress()) {
            Serial.println("Command: Interval capture deferred while burst transfer is active.");
            emitSessionEvidence(
                "CMD_INTERVAL_DEFERRED",
                "CMD=0x02;REASON=BURST_ACTIVE;UPLOAD=%d;BURST=%d;INTENT=%u",
                photoDataUploading ? 1 : 0,
                burstMode ? 1 : 0,
                static_cast<unsigned int>(captureTransferIntent)
            );
            break;
        }
        Serial.println("Command: Start interval capture.");
        captureInterval = PHOTO_CAPTURE_INTERVAL_MS;
        passiveBurstFallbackEnabled = true;
        captureTransferIntent = TRANSFER_INTENT_SINGLE;
        emitSessionEvidence("STREAM_INTENT", "MODE=SINGLE;SRC=CMD_INTERVAL");
        Serial.print("Capture interval: ");
        Serial.print(captureInterval / 1000);
        Serial.println(" seconds");
        emitSessionEvidence("CMD_INTERVAL_START", "INT_MS=%d", captureInterval);
        isCapturingPhotos = true;
        lastCaptureTime = millis() - captureInterval;
        break;

    case 0x04: // burst capture
        if (!captureRuntimeEnabled) {
            Serial.println("Command rejected: capture runtime disabled (burst capture).");
            emitSessionEvidence("CMD_REJECTED", "CMD=0x04;REASON=CAPTURE_DISABLED");
            break;
        }
        Serial.printf(
            "Command: Burst capture (%u frames @ %ums).\n",
            (unsigned int) BURST_DEFAULT_COUNT,
            (unsigned int) burstInterCaptureDelayMs
        );
        emitSessionEvidence(
            "CMD_BURST",
            "FRAMES=%u;DELAY_MS=%u",
            (unsigned int) BURST_DEFAULT_COUNT,
            (unsigned int) burstInterCaptureDelayMs
        );
        // Cancel in-flight upload so burst can start immediately
        if (photoDataUploading || burstMode || fb != nullptr) {
            abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_CMD_BURST, "CMD_BURST");
            Serial.println("Cancelled previous upload for burst.");
        }
        passiveBurstFallbackEnabled = false;
        captureTransferIntent = TRANSFER_INTENT_BURST;
        emitSessionEvidence("STREAM_INTENT", "MODE=BURST;SRC=CMD_BURST");
        if (!photoDataUploading && !burstMode) {
            if (take_burst(BURST_DEFAULT_COUNT)) {
                Serial.println("Burst capture successful. Starting upload.");
                photoDataUploading = true;
                sent_photo_bytes = 0;
                sent_photo_frames = 0;
                lastCaptureTime = millis();
                lastBurstTriggerTime = lastCaptureTime;
            } else {
                captureTransferIntent = TRANSFER_INTENT_IDLE;
            }
        } else {
            Serial.println("Burst: Upload already in progress.");
        }
        break;
        
    default:
        if (controlValue >= 5 && controlValue <= 127) {
            if (!captureRuntimeEnabled) {
                Serial.printf(
                    "Legacy interval command rejected while capture runtime disabled (cmd=%d).\n",
                    controlValue
                );
                emitSessionEvidence(
                    "CMD_REJECTED",
                    "CMD=0x%02X;REASON=CAPTURE_DISABLED",
                    static_cast<unsigned int>(controlValue & 0xFF)
                );
                break;
            }
            if (isBurstTransferInProgress()) {
                Serial.printf(
                    "Legacy interval command deferred while burst transfer is active (cmd=%d).\n",
                    controlValue
                );
                emitSessionEvidence(
                    "CMD_INTERVAL_DEFERRED",
                    "CMD=0x%02X;REASON=BURST_ACTIVE;UPLOAD=%d;BURST=%d;INTENT=%u",
                    static_cast<unsigned int>(controlValue & 0xFF),
                    photoDataUploading ? 1 : 0,
                    burstMode ? 1 : 0,
                    static_cast<unsigned int>(captureTransferIntent)
                );
                break;
            }
            Serial.print("Command: Start interval capture");
            Serial.print(controlValue);
            Serial.println(")");
            captureInterval = PHOTO_CAPTURE_INTERVAL_MS;
            passiveBurstFallbackEnabled = true;
            captureTransferIntent = TRANSFER_INTENT_SINGLE;
            emitSessionEvidence("STREAM_INTENT", "MODE=SINGLE;SRC=CMD_INTERVAL_LEGACY");
            isCapturingPhotos = true;
            lastCaptureTime = millis() - captureInterval;
            emitSessionEvidence(
                "CMD_INTERVAL_START",
                "INT_MS=%d;SRC=legacy_value_%d",
                captureInterval,
                controlValue
            );
        } else {
            Serial.print("Unknown command: ");
            Serial.println(controlValue);
        }
        break;
    }
}

//
// configure_camera()
//
void configure_camera()
{
    Serial.println("Initializing camera.");
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sscb_sda = SIOD_GPIO_NUM;
    config.pin_sscb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = CAMERA_XCLK_FREQ;

    // Pull camera tuning from config.h
    config.frame_size = CAMERA_FRAME_SIZE;
    config.pixel_format = PIXFORMAT_JPEG;
    config.fb_count = 3;
    config.jpeg_quality = CAMERA_JPEG_QUALITY;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("Camera init failed with error 0x%x\n", err);
    } else {
        Serial.println("Camera initialized successfully.");
        
        // Flip image to match physical mounting
        sensor_t *s = esp_camera_sensor_get();
        if (s != NULL) {
            s->set_vflip(s, 1);   
            s->set_hmirror(s, 1); 
            Serial.println("Camera sensor flipped 180 degrees");
        }
    }
}

//
// Setup + main loop
//

// Scratch buffer for BLE photo chunks
static uint8_t *s_compressed_frame_2 = nullptr;

void setup_app()
{
    Serial.begin(115200);
    Serial.println("Setup started.");

    sessionStartMs = millis();
    lastSessionHeartbeatMs = sessionStartMs;

    const esp_reset_reason_t reason = esp_reset_reason();
    emitSessionEvidence(
        "BOOT",
        "RR=%s;FW=%s;HW=%s",
        resetReasonText(reason),
        FIRMWARE_VERSION_STRING,
        HARDWARE_REVISION
    );

    // GPIO init
    pinMode(POWER_BUTTON_PIN, INPUT_PULLUP);
    pinMode(STATUS_LED_PIN, OUTPUT);

    // LED is inverted: HIGH=off, LOW=on
    digitalWrite(STATUS_LED_PIN, HIGH);

    // Button interrupt setup
    attachInterrupt(digitalPinToInterrupt(POWER_BUTTON_PIN), buttonISR, CHANGE);

    // Kick off boot LED sequence
    ledMode = LED_BOOT_SEQUENCE;

    // Baseline CPU clock from config
    setCpuFrequencyMhz(NORMAL_CPU_FREQ_MHZ);
    lastActivity = millis();

    configure_ble();
    configure_camera();

    // Allocate BLE transfer buffer (chunk + header room)
    s_compressed_frame_2 = (uint8_t *) ps_calloc(BLE_CHUNK_SIZE + 3, sizeof(uint8_t));
    if (!s_compressed_frame_2) {
        Serial.println("Failed to allocate chunk buffer!");
    } else {
        Serial.printf("Chunk buffer allocated: %d bytes\n", BLE_CHUNK_SIZE + 3);
    }

    // Start in idle capture state
    isCapturingPhotos = false;
    captureInterval = 0;
    passiveBurstFallbackEnabled = false;
    burstInterCaptureDelayMs = BURST_INTER_CAPTURE_DELAY;
    lastCaptureTime = millis();
    lastBurstTriggerTime = lastCaptureTime;
    Serial.println("Waiting for capture commands from app.");
    emitSessionEvidence(
        "PASSIVE_CADENCE",
        "CAP=%d;INT_MS=%d;BURST_DELAY_MS=%u;FALLBACK_EN=%d;FALLBACK_INT_MS=%u",
        isCapturingPhotos ? 1 : 0,
        captureInterval,
        (unsigned int) burstInterCaptureDelayMs,
        passiveBurstFallbackEnabled ? 1 : 0,
        (unsigned int) PASSIVE_BURST_FALLBACK_INTERVAL_MS
    );

    // Initial battery reading setup
    analogReadResolution(12);                            // keep ADC at 12-bit
    analogSetPinAttenuation(BATTERY_ADC_PIN, ADC_11db); // full-ish 3.3V ADC range

    readBatteryLevel();
    deviceState = DEVICE_ACTIVE;

    Serial.println("Setup complete.");
    Serial.println("Light sleep optimization enabled for extended battery life.");
}

void loop_app()
{
    unsigned long now = millis();

    if (now - lastSessionHeartbeatMs >= SESSION_HEARTBEAT_INTERVAL_MS) {
        lastSessionHeartbeatMs = now;
        emitSessionEvidence(
            "HEARTBEAT",
            "BLE=%d;CC=%lu;DC=%lu;BAT=%d;UPLOAD=%d;BURST=%d;CAP=%d;CAP_INT=%d;BDELAY=%u;FALLBACK_EN=%d;FALLBACK_INT_MS=%u;HEAP=%u",
            connected ? 1 : 0,
            (unsigned long) ble_service_connect_count(),
            (unsigned long) ble_service_disconnect_count(),
            batteryPercentage,
            photoDataUploading ? 1 : 0,
            burstMode ? 1 : 0,
            isCapturingPhotos ? 1 : 0,
            captureInterval,
            (unsigned int) burstInterCaptureDelayMs,
            passiveBurstFallbackEnabled ? 1 : 0,
            (unsigned int) PASSIVE_BURST_FALLBACK_INTERVAL_MS,
            (unsigned int) ESP.getFreeHeap()
        );
    }

    // Button handling
    handleButton();

    // LED update
    updateLED();

    // Power-save entry/exit checks
    if (!connected && !photoDataUploading && (now - lastActivity > IDLE_THRESHOLD_MS)) {
        enterPowerSave();
    } else if (connected || photoDataUploading) {
        if (powerSaveMode)
            exitPowerSave();
        lastActivity = now;
    }

    // Periodic battery task
    if (now - lastBatteryCheck >= BATTERY_TASK_INTERVAL_MS) {
        readBatteryLevel();
        updateBatteryService();
        lastBatteryCheck = now;
    }

    // Force one battery push after each BLE connect/reconnect.
    static bool firstBatteryUpdateAfterConnect = true;
    if (!connected) {
        firstBatteryUpdateAfterConnect = true;
    }
    if (connected && firstBatteryUpdateAfterConnect) {
        readBatteryLevel();
        updateBatteryService();
        firstBatteryUpdateAfterConnect = false;
    }

    if (!captureRuntimeEnabled && photoDataUploading) {
        Serial.println("Capture runtime disabled: aborting active upload.");
        abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_RUNTIME_GUARD, "LOOP_RUNTIME_GUARD");
    }

    // Decide if it's time to capture
    if (captureRuntimeEnabled && isCapturingPhotos && !photoDataUploading && connected) {
        if ((captureInterval == 0) || (now - lastCaptureTime >= (unsigned long) captureInterval)) {
            if (captureInterval == 0) {
                // interval=0 means one-shot
                isCapturingPhotos = false;
            }
            captureTransferIntent = TRANSFER_INTENT_SINGLE;
            Serial.println("Interval reached. Capturing photo.");
            if (take_photo()) {
                Serial.println("Photo capture successful. Starting upload.");
                photoDataUploading = true;
                sent_photo_bytes = 0;
                sent_photo_frames = 0;
                lastCaptureTime = now;
            }
        }
    }

    // If no recent catalyst burst happened, use periodic fallback burst.
    if (captureRuntimeEnabled && connected && passiveBurstFallbackEnabled && !photoDataUploading && !burstMode) {
        if (now - lastBurstTriggerTime >= PASSIVE_BURST_FALLBACK_INTERVAL_MS) {
            Serial.printf(
                "Passive fallback: no catalyst burst in %ums, capturing burst.\n",
                (unsigned int) PASSIVE_BURST_FALLBACK_INTERVAL_MS
            );
            emitSessionEvidence(
                "PASSIVE_BURST_FALLBACK",
                "INT_MS=%u;FRAMES=%u;BURST_DELAY_MS=%u",
                (unsigned int) PASSIVE_BURST_FALLBACK_INTERVAL_MS,
                (unsigned int) BURST_DEFAULT_COUNT,
                (unsigned int) burstInterCaptureDelayMs
            );
            captureTransferIntent = TRANSFER_INTENT_BURST;
            emitSessionEvidence("STREAM_INTENT", "MODE=BURST;SRC=PASSIVE_FALLBACK");
            if (take_burst(BURST_DEFAULT_COUNT)) {
                Serial.println("Passive fallback burst successful. Starting upload.");
                photoDataUploading = true;
                sent_photo_bytes = 0;
                sent_photo_frames = 0;
                lastCaptureTime = now;
                lastBurstTriggerTime = now;
            } else {
                Serial.println("Passive fallback burst failed.");
                captureTransferIntent = TRANSFER_INTENT_IDLE;
                // Avoid tight retry loops if camera fails temporarily.
                lastBurstTriggerTime = now;
            }
        }
    }

    // Upload path: stream JPEG chunks over BLE
    if (photoDataUploading && burstMode) {
        if (captureTransferIntent != TRANSFER_INTENT_BURST) {
            Serial.printf(
                "Stream guard: blocked burst upload while intent=%u\n",
                static_cast<unsigned int>(captureTransferIntent)
            );
            emitSessionEvidence(
                "STREAM_GUARD",
                "BLOCK=BURST_UPLOAD;INTENT=%u;BURST=%d;UPLOAD=%d",
                static_cast<unsigned int>(captureTransferIntent),
                burstMode ? 1 : 0,
                photoDataUploading ? 1 : 0
            );
            abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_CMD_SINGLE, "STREAM_GUARD_BURST_IN_SINGLE");
            return;
        }

        uint8_t logicalSeq = burstCurrentFrame;
        if (logicalSeq < burstCapturedFrames) {
            uint8_t bIdx = burstUploadOrder[logicalSeq];
            if (bIdx >= burstCapturedFrames || burstJpegData[bIdx] == nullptr) {
                Serial.printf(
                    "Burst upload order fallback: logical=%u mappedSlot=%u\n",
                    static_cast<unsigned int>(logicalSeq),
                    static_cast<unsigned int>(bIdx)
                );
                bIdx = logicalSeq;
            }

            if (bIdx >= burstCapturedFrames || burstJpegData[bIdx] == nullptr) {
                Serial.printf("Burst upload missing frame data for logical=%u slot=%u\n",
                    static_cast<unsigned int>(logicalSeq),
                    static_cast<unsigned int>(bIdx)
                );
                photoDataUploading = false;
                captureTransferIntent = TRANSFER_INTENT_IDLE;
                freeBurstBuffers();
                return;
            }

            size_t remaining = burstSizes[bIdx] - sent_photo_bytes;
            if (remaining > 0) {
                size_t bytes_to_copy;
                if (sent_photo_frames == 0) {
                    // First burst chunk header:
                    // [frame_index(2), burst_seq(1), orientation(1), capture_time_ms_le(4)]
                    s_compressed_frame_2[0] = 0; 
                    s_compressed_frame_2[1] = 0; 
                    s_compressed_frame_2[2] = logicalSeq;
                    s_compressed_frame_2[3] = (uint8_t) current_photo_orientation;
                    const uint32_t captureMs = burstCaptureTimesMs[bIdx];
                    s_compressed_frame_2[4] = (uint8_t) (captureMs & 0xFF);
                    s_compressed_frame_2[5] = (uint8_t) ((captureMs >> 8) & 0xFF);
                    s_compressed_frame_2[6] = (uint8_t) ((captureMs >> 16) & 0xFF);
                    s_compressed_frame_2[7] = (uint8_t) ((captureMs >> 24) & 0xFF);
                    bytes_to_copy = (remaining > (BLE_CHUNK_SIZE - 8)) ? (BLE_CHUNK_SIZE - 8) : remaining;
                    memcpy(&s_compressed_frame_2[8], &burstJpegData[bIdx][sent_photo_bytes], bytes_to_copy);
                    photoDataCharacteristic->setValue(s_compressed_frame_2, bytes_to_copy + 8);
                } else {
                    s_compressed_frame_2[0] = (uint8_t) (sent_photo_frames & 0xFF);
                    s_compressed_frame_2[1] = (uint8_t) ((sent_photo_frames >> 8) & 0xFF);
                    s_compressed_frame_2[2] = logicalSeq;
                    bytes_to_copy = (remaining > (BLE_CHUNK_SIZE - 1)) ? (BLE_CHUNK_SIZE - 1) : remaining;
                    memcpy(&s_compressed_frame_2[3], &burstJpegData[bIdx][sent_photo_bytes], bytes_to_copy);
                    photoDataCharacteristic->setValue(s_compressed_frame_2, bytes_to_copy + 3);
                }
                photoDataCharacteristic->notify();
                delay(BLE_PHOTO_TRANSFER_DELAY);

                sent_photo_bytes += bytes_to_copy;
                sent_photo_frames++;

                Serial.printf("Burst logical[%d] slot[%d] chunk %d (%d bytes), %d remaining\n", 
                    logicalSeq, bIdx, (int)sent_photo_frames, (int)bytes_to_copy, (int)(remaining - bytes_to_copy));

                lastActivity = now;
            } else {
                
                s_compressed_frame_2[0] = 0xFF;
                s_compressed_frame_2[1] = 0xFF;
                s_compressed_frame_2[2] = logicalSeq;
                photoDataCharacteristic->setValue(s_compressed_frame_2, 3);
                photoDataCharacteristic->notify();
                delay(BLE_PHOTO_TRANSFER_DELAY);
                Serial.printf("Burst logical frame %d (slot %d) upload complete.\n", logicalSeq, bIdx);

                
                burstCurrentFrame++;
                sent_photo_bytes = 0;
                sent_photo_frames = 0;

                if (burstCurrentFrame >= burstCapturedFrames) {
                    delay(BLE_PHOTO_TRANSFER_DELAY); 
                    s_compressed_frame_2[0] = 0xFE;
                    s_compressed_frame_2[1] = 0xFF;
                    photoDataCharacteristic->setValue(s_compressed_frame_2, 2);
                    photoDataCharacteristic->notify();
                    delay(BLE_PHOTO_TRANSFER_DELAY);
                    Serial.println("Burst transfer complete (0xFFFE sent).");

                    photoDataUploading = false;
                    captureTransferIntent = TRANSFER_INTENT_IDLE;
                    freeBurstBuffers();
                }
            }
        }
    } else if (photoDataUploading && fb) {
        if (captureTransferIntent == TRANSFER_INTENT_BURST) {
            Serial.println("Stream guard: blocked single upload while burst intent is active.");
            emitSessionEvidence(
                "STREAM_GUARD",
                "BLOCK=SINGLE_UPLOAD;INTENT=%u;BURST=%d;UPLOAD=%d",
                static_cast<unsigned int>(captureTransferIntent),
                burstMode ? 1 : 0,
                photoDataUploading ? 1 : 0
            );
            abortActiveCaptureTransfer(TRANSFER_ABORT_REASON_CMD_BURST, "STREAM_GUARD_SINGLE_IN_BURST");
            return;
        }

        size_t remaining = fb->len - sent_photo_bytes;
        if (remaining > 0) {
            size_t bytes_to_copy;
            if (sent_photo_frames == 0) {
                // First chunk includes orientation metadata
                s_compressed_frame_2[0] = 0; // Frame index low byte
                s_compressed_frame_2[1] = 0; // Frame index high byte
                s_compressed_frame_2[2] = (uint8_t) current_photo_orientation;
                bytes_to_copy = (remaining > (BLE_CHUNK_SIZE - 1)) ? (BLE_CHUNK_SIZE - 1) : remaining;
                memcpy(&s_compressed_frame_2[3], &fb->buf[sent_photo_bytes], bytes_to_copy);
                photoDataCharacteristic->setValue(s_compressed_frame_2, bytes_to_copy + 3);
                
                // Debug print of first chunk payload prefix
                Serial.print("First chunk header: idx=");
                Serial.print(s_compressed_frame_2[0]);
                Serial.print(",");
                Serial.print(s_compressed_frame_2[1]);
                Serial.print(" orient=");
                Serial.print(s_compressed_frame_2[2]);
                Serial.print(" JPEG start: ");
                Serial.print(s_compressed_frame_2[3], HEX);
                Serial.print(" ");
                Serial.print(s_compressed_frame_2[4], HEX);
                Serial.print(" ");
                Serial.print(s_compressed_frame_2[5], HEX);
                Serial.print(" ");
                Serial.println(s_compressed_frame_2[6], HEX);
            } else {
                s_compressed_frame_2[0] = (uint8_t) (sent_photo_frames & 0xFF);
                s_compressed_frame_2[1] = (uint8_t) ((sent_photo_frames >> 8) & 0xFF);
                bytes_to_copy = (remaining > BLE_CHUNK_SIZE) ? BLE_CHUNK_SIZE : remaining;
                memcpy(&s_compressed_frame_2[2], &fb->buf[sent_photo_bytes], bytes_to_copy);
                photoDataCharacteristic->setValue(s_compressed_frame_2, bytes_to_copy + 2);
            }
            photoDataCharacteristic->notify();
            delay(BLE_PHOTO_TRANSFER_DELAY);  

            sent_photo_bytes += bytes_to_copy;
            sent_photo_frames++;

            Serial.print("Uploading chunk ");
            Serial.print(sent_photo_frames);
            Serial.print(" (");
            Serial.print(bytes_to_copy);
            Serial.print(" bytes), ");
            Serial.print(remaining - bytes_to_copy);
            Serial.println(" bytes remaining.");

            lastActivity = now; // upload counts as activity
        } else {
            s_compressed_frame_2[0] = 0xFF;
            s_compressed_frame_2[1] = 0xFF;
            photoDataCharacteristic->setValue(s_compressed_frame_2, 2);
            photoDataCharacteristic->notify();
            delay(BLE_PHOTO_TRANSFER_DELAY);  
            Serial.println("Photo upload complete.");

            photoDataUploading = false;
            captureTransferIntent = TRANSFER_INTENT_IDLE;
            esp_camera_fb_return(fb);
            fb = nullptr;
            Serial.println("Camera frame buffer freed.");
        }
    }

    // Light sleep currently disabled to ensure BLE connectivity maintained.
    // DO NOT RE-ENABLE WITHOUT TESTING BLE CONNECTIVITY IMPACT THOROUGHLY.
    // if (!photoDataUploading && lightSleepEnabled && connected) {
    //     esp_sleep_enable_timer_wakeup(LIGHT_SLEEP_DURATION_US);
    //     esp_light_sleep_start();
    // }

    
    if (photoDataUploading) {
        delay(20); 
    } else if (powerSaveMode) {
        delay(50); 
    } else {
        delay(50); 
    }
}
