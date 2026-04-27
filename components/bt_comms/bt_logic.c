#include "bt_includes.h"
#include "bt_config.h"
#include "esp_system.h"

bool hapticsOn = true;
bool sensorsOn = true;
static bool awaitingGlassesResponse = false;
static bool appOverrideActive = false;
static bool systemPowerLockedOff = false;
extern bool object_detected; // from nv_logic.c
extern volatile uint32_t nv_sample_count;       // from nv_logic.c
extern volatile uint32_t nv_object_detect_count; // from nv_logic.c
extern volatile int64_t nv_last_sample_ms;      // from nv_logic.c

void ble_send(const char *msg);

typedef enum {
    NONE,
    TURN_SENSORS_OFF,
    TURN_BOTH_OFF
} PendingAction;

static PendingAction pendingAction = NONE;

static int64_t bt_boot_time_ms = 0;
static int64_t bt_last_heartbeat_ms = 0;
static uint32_t bt_disconnect_count = 0;
static esp_reset_reason_t bt_reset_reason = ESP_RST_UNKNOWN;

// ===== BLE =====
uint16_t conn_handle;
uint16_t char_handle;

// ===== HELPERS =====
void str_to_upper(char *s) {
    for (int i = 0; s[i]; i++) s[i] = toupper(s[i]);
}

void str_to_lower(char *s) {
    for (int i = 0; s[i]; i++) s[i] = tolower(s[i]);
}

void str_trim(char *s) {
    char *end;
    while (isspace((unsigned char)*s)) s++;
    end = s + strlen(s) - 1;
    while (end > s && isspace((unsigned char)*end)) end--;
    *(end + 1) = '\0';
}

static const char *reset_reason_text(esp_reset_reason_t reason) {
    switch (reason) {
        case ESP_RST_POWERON: return "POWER_ON";
        case ESP_RST_EXT: return "EXTERNAL";
        case ESP_RST_SW: return "SOFTWARE";
        case ESP_RST_PANIC: return "PANIC";
        case ESP_RST_INT_WDT: return "INT_WDT";
        case ESP_RST_TASK_WDT: return "TASK_WDT";
        case ESP_RST_WDT: return "WDT";
        case ESP_RST_DEEPSLEEP: return "DEEP_SLEEP";
        case ESP_RST_BROWNOUT: return "BROWNOUT";
        case ESP_RST_SDIO: return "SDIO";
        default: return "UNKNOWN";
    }
}

static void emit_session_evidence(const char *event, int status_code) {
    if (conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return;
    }

    const int64_t now_ms = esp_timer_get_time() / 1000;
    const int64_t uptime_ms = now_ms - bt_boot_time_ms;

    char msg[196];
    snprintf(
        msg,
        sizeof(msg),
        "SESSION_EVIDENCE:DEV=VEST;EV=%s;UP=%lld;DC=%lu;ST=%d;AO=%d;SL=%d;NV=%lu;OD=%lu;NVS=%lld",
        event,
        (long long) uptime_ms,
        (unsigned long) bt_disconnect_count,
        status_code,
        appOverrideActive ? 1 : 0,
        systemPowerLockedOff ? 1 : 0,
        (unsigned long) nv_sample_count,
        (unsigned long) nv_object_detect_count,
        (long long) nv_last_sample_ms
    );
    ble_send(msg);
}

// ===== HARDWARE =====
void updateLEDs() {
    gpio_set_level(MOSFET1_PIN, hapticsOn);
    gpio_set_level(MOSFET2_PIN, sensorsOn);
}

static bool read_haptics_switch_state(void) {
    // BTN pins use pull-up inputs, so logical ON is active-low.
    return !gpio_get_level(BTN1_PIN);
}

static bool read_sensors_switch_state(void) {
    // BTN pins use pull-up inputs, so logical ON is active-low.
    return !gpio_get_level(BTN2_PIN);
}

static void sync_outputs_to_physical_switches(void) {
    hapticsOn = read_haptics_switch_state();
    sensorsOn = read_sensors_switch_state();
    updateLEDs();
}

// calling battery reading from non_visual
extern float battery_percentage;

// ===== BLE SEND =====
void ble_send(const char *msg) {
    if (conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(msg, strlen(msg));
        ble_gatts_notify_custom(conn_handle, char_handle, om);
    }
}

static const char *on_off_text(bool value) {
    return value ? "ON" : "OFF";
}

static void send_app_toggle_mismatch(const char *target, bool appStateOn, bool physicalStateOn) {
    if (appStateOn == physicalStateOn) {
        return;
    }

    char msg[64];
    snprintf(msg, sizeof(msg), "APP_TOGGLE_MISMATCH:%s:%s:%s",
             target, on_off_text(appStateOn), on_off_text(physicalStateOn));
    ble_send(msg);
}

static void report_app_toggle_mismatches(bool checkHaptics, bool checkSensors) {
    if (checkHaptics) {
        send_app_toggle_mismatch("HAPTICS", hapticsOn, read_haptics_switch_state());
    }

    if (checkSensors) {
        send_app_toggle_mismatch("SENSORS", sensorsOn, read_sensors_switch_state());
    }
}

// ===== LOGIC =====
void reportSystemState() {
    if (hapticsOn && sensorsOn)
        ble_send("BOTH_BUTTONS_ON");
    else if (!hapticsOn && !sensorsOn)
        ble_send("BOTH_BUTTONS_OFF");
    else if (hapticsOn && !sensorsOn)
        ble_send("HAPTICS_ON_SENSORS_OFF");
    else
        ble_send("HAPTICS_OFF_SENSORS_ON");
}

void requestGlassesConfirmation(PendingAction action) {
    pendingAction = action;
    awaitingGlassesResponse = true;
    ble_send("Are Glasses On?");
}

// ===== COMMAND PROCESSING =====
void processCommand(char *cmd) {
    bool checkHapticsMismatch = false;
    bool checkSensorsMismatch = false;

    str_trim(cmd);
    str_to_upper(cmd);

    ESP_LOGI(TAG, "CMD: %s", cmd);

    if (strcmp(cmd, "APP_CONNECTED") == 0) {
        reportSystemState();
        return;
    }
    else if (strcmp(cmd, "TURN SYSTEM ON") == 0) {
        appOverrideActive = true;
        systemPowerLockedOff = false;
    }
    else if (strcmp(cmd, "TURN SYSTEM OFF") == 0) {
        appOverrideActive = true;
        systemPowerLockedOff = true;
        hapticsOn = false;
        sensorsOn = false;
    }
    else if (systemPowerLockedOff &&
            (strcmp(cmd, "TURN HAPTICS ON") == 0 || strcmp(cmd, "TURN SENSORS ON") == 0)) {
        ble_send("SYSTEM_POWER_LOCK_ACTIVE");
        reportSystemState();
        return;
    }
    else if (strcmp(cmd, "TURN HAPTICS ON") == 0) {
        appOverrideActive = true;
        hapticsOn = true;
        checkHapticsMismatch = true;
    }
    else if (strcmp(cmd, "TURN HAPTICS OFF") == 0) {
        appOverrideActive = true;
        hapticsOn = false;
        checkHapticsMismatch = true;
    }
    else if (strcmp(cmd, "TURN SENSORS ON") == 0) {
        appOverrideActive = true;
        sensorsOn = true;
        checkSensorsMismatch = true;
    }
    else if (strcmp(cmd, "TURN SENSORS OFF") == 0) {
        appOverrideActive = true;
        requestGlassesConfirmation(TURN_SENSORS_OFF);
        return;
    }
    else if (strcmp(cmd, "TURN ALL OFF") == 0) {
        appOverrideActive = true;
        systemPowerLockedOff = true;
        requestGlassesConfirmation(TURN_BOTH_OFF);
        return;
    }
    else {
        ble_send("UNKNOWN RESPONSE");
        return;
    }

    updateLEDs();
    reportSystemState();
    report_app_toggle_mismatches(checkHapticsMismatch, checkSensorsMismatch);
}

// ===== BLE WRITE CALLBACK =====
static int ble_write_cb(uint16_t conn_handle_in,
                        uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt,
                        void *arg) {

    char buffer[100] = {0};
    int len = ctxt->om->om_len;

    memcpy(buffer, ctxt->om->om_data, len);
    buffer[len] = '\0';

    str_trim(buffer);

    if (awaitingGlassesResponse) {
        bool checkHapticsMismatch = false;
        bool checkSensorsMismatch = false;

        str_to_lower(buffer);

        if (strcmp(buffer, "glasses are on") == 0) {

            ble_send("CONFIRMED: GLASSES ON");

            if (pendingAction == TURN_SENSORS_OFF) {
                sensorsOn = false;
                ble_send("SENSORS OFF");
                checkSensorsMismatch = true;
            }
            else if (pendingAction == TURN_BOTH_OFF) {
                sensorsOn = false;
                hapticsOn = false;
                systemPowerLockedOff = true;
                ble_send("ALL OFF");
                checkHapticsMismatch = true;
                checkSensorsMismatch = true;
            }

            updateLEDs();
            reportSystemState();
            report_app_toggle_mismatches(checkHapticsMismatch, checkSensorsMismatch);

            awaitingGlassesResponse = false;
            pendingAction = NONE;
        }
        else {
            ble_send("INVALID RESPONSE");
        }
    }
    else {
        processCommand(buffer);
    }

    return 0;
}

// ===== GATT =====
static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x180A),
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = BLE_UUID16_DECLARE(0x2A57),
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY,
                .access_cb = ble_write_cb,
                .val_handle = &char_handle,
            },
            {0}
        }
    },
    {0}
};

// ===== BLE EVENTS =====
static int ble_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                conn_handle = event->connect.conn_handle;
                ESP_LOGI(TAG, "Connected (handle=%d)", conn_handle);

                char boot_msg[96];
                snprintf(
                    boot_msg,
                    sizeof(boot_msg),
                    "SESSION_EVIDENCE:DEV=VEST;EV=BOOT;RR=%s;UP=0",
                    reset_reason_text(bt_reset_reason)
                );
                ble_send(boot_msg);
                emit_session_evidence("BLE_CONNECT", event->connect.status);
            } else {
                conn_handle = BLE_HS_CONN_HANDLE_NONE;
                ESP_LOGW(TAG, "Connect failed (status=%d)", event->connect.status);
            }
            break;

        case BLE_GAP_EVENT_DISCONNECT:
            bt_disconnect_count++;
            ESP_LOGI(
                TAG,
                "Disconnected (reason=%d, total_disconnects=%lu)",
                event->disconnect.reason,
                (unsigned long) bt_disconnect_count
            );
            conn_handle = BLE_HS_CONN_HANDLE_NONE;
            awaitingGlassesResponse = false;
            pendingAction = NONE;
            appOverrideActive = false;
            systemPowerLockedOff = false;
            sync_outputs_to_physical_switches();
            break;
    }
    return 0;
}

// ===== BLE INIT =====
void ble_app_on_sync(void) {
    uint8_t own_addr_type;
    ble_hs_id_infer_auto(0, &own_addr_type);

    // Advertisement data with device name
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)"Navigation Vest";
    fields.name_len = strlen("Navigation Vest");
    fields.name_is_complete = 1;
    ble_gap_adv_set_fields(&fields);

    struct ble_gap_adv_params adv_params = {0};
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;  // undirected connectable
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;  // general discoverable

    ble_gap_adv_start(0, NULL, BLE_HS_FOREVER, &adv_params, ble_gap_event, NULL);
}

// ===== TASK =====
void ble_host_task(void *param) {
    nimble_port_run();
}

// ===== HARDWARE INITIALIZATION =====
void init_bt(void) {

    ESP_ERROR_CHECK(nvs_flash_init());

    // GPIO
    gpio_set_direction(MOSFET1_PIN, GPIO_MODE_OUTPUT);
    gpio_set_direction(MOSFET2_PIN, GPIO_MODE_OUTPUT);

    gpio_config_t switch_conf = {
    .intr_type    = GPIO_INTR_DISABLE,
    .mode         = GPIO_MODE_INPUT,
    .pin_bit_mask = (1ULL << BTN1_PIN) | (1ULL << BTN2_PIN),
    .pull_down_en = GPIO_PULLDOWN_DISABLE,
    .pull_up_en   = GPIO_PULLUP_ENABLE
    };
    gpio_config(&switch_conf);

    updateLEDs();

    // BLE
    nimble_port_init();
    ble_svc_gap_init();
    ble_svc_gap_device_name_set("Navigation Vest");
    ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svcs);
    ble_gatts_add_svcs(gatt_svcs);
    ble_hs_cfg.sync_cb = ble_app_on_sync;
    nimble_port_freertos_init(ble_host_task);
}

    // ==== BT COMMS TASK ====
void bt_comms_task(void *pvParameters) {
    // Sync initial state with physical switch positions at boot
    appOverrideActive = false;
    systemPowerLockedOff = false;
    bt_boot_time_ms = esp_timer_get_time() / 1000;
    bt_last_heartbeat_ms = bt_boot_time_ms;
    bt_reset_reason = esp_reset_reason();

    ESP_LOGI(TAG, "Phase6 session bootstrap reset_reason=%s", reset_reason_text(bt_reset_reason));

    sync_outputs_to_physical_switches();
    reportSystemState();

    while (1) {

        bool switch1 = read_haptics_switch_state();
        bool switch2 = read_sensors_switch_state();

        // System-power lock keeps outputs off while BLE stays connected.
        if (systemPowerLockedOff) {
            if (hapticsOn || sensorsOn) {
                hapticsOn = false;
                sensorsOn = false;
                updateLEDs();
                reportSystemState();
            }
        }
        // While override is active, app commands are authoritative and
        // physical switches are observed only for diagnostics.
        else if (!appOverrideActive) {
            bool changed = false;

            if (switch1 != hapticsOn) {
                hapticsOn = switch1;
                ble_send(hapticsOn ? "HAPTICS ON" : "HAPTICS OFF");
                changed = true;
            }

            if (switch2 != sensorsOn) {
                sensorsOn = switch2;
                ble_send(sensorsOn ? "SENSORS ON" : "SENSORS OFF");
                changed = true;
            }

            if (changed) {
                updateLEDs();
                reportSystemState();
            }
        }

        // Debug - remove after testing
        printf("SW1:%d SW2:%d hapticsOn:%d sensorsOn:%d override:%d sysLock:%d\n", 
            switch1,
            switch2,
            hapticsOn, 
            sensorsOn,
            appOverrideActive,
            systemPowerLockedOff);
        
        // Battery every 5s
        static int64_t last = 0;
        int64_t now = esp_timer_get_time() / 1000;

        if (now - last > 5000) {
            last = now;

            char msg[50];
            sprintf(msg, "BATTERY:%d", (int)battery_percentage);
            ble_send(msg);
        }

        if (conn_handle != BLE_HS_CONN_HANDLE_NONE && (now - bt_last_heartbeat_ms) > 60000) {
            bt_last_heartbeat_ms = now;
            emit_session_evidence("HEARTBEAT", 0);
        }

        // Glasses capture with 3s cooldown to prevent spamming
         static int64_t lastGlassesCapture = 0;
        int64_t nowGlassesCapture = esp_timer_get_time() / 1000;

        if (nowGlassesCapture - lastGlassesCapture > 3000) {
            lastGlassesCapture = nowGlassesCapture;
                if (object_detected) {
                    ble_send("GLASSES_CAPTURE");
                }
        }

        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

// ============================================================
//  MODULE ENTRY POINT
//  Initializes hardware and launches bt_comms task
// ============================================================

void boot_bt(void) {
    init_bt();
    xTaskCreate(bt_comms_task, "bt_comms_task", 4096, NULL, 5, NULL);
}
