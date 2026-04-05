#include "bt_includes.h"
#include "bt_config.h"

bool hapticsOn = true;
bool sensorsOn = true;
static bool awaitingGlassesResponse = false;

typedef enum {
    NONE,
    TURN_SENSORS_OFF,
    TURN_BOTH_OFF
} PendingAction;

static PendingAction pendingAction = NONE;

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

// ===== HARDWARE =====
void updateLEDs() {
    gpio_set_level(MOSFET1_PIN, hapticsOn);
    gpio_set_level(MOSFET2_PIN, sensorsOn);
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
    str_trim(cmd);
    str_to_upper(cmd);

    ESP_LOGI(TAG, "CMD: %s", cmd);

    if (strcmp(cmd, "TURN HAPTICS ON") == 0) {
        hapticsOn = true;
    }
    else if (strcmp(cmd, "TURN HAPTICS OFF") == 0) {
        hapticsOn = false;
    }
    else if (strcmp(cmd, "TURN SENSORS ON") == 0) {
        sensorsOn = true;
    }
    else if (strcmp(cmd, "TURN SENSORS OFF") == 0) {
        requestGlassesConfirmation(TURN_SENSORS_OFF);
        return;
    }
    else if (strcmp(cmd, "TURN ALL OFF") == 0) {
        requestGlassesConfirmation(TURN_BOTH_OFF);
        return;
    }

    updateLEDs();
    reportSystemState();
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
        str_to_lower(buffer);

        if (strcmp(buffer, "glasses are on") == 0) {

            ble_send("CONFIRMED: GLASSES ON");

            if (pendingAction == TURN_SENSORS_OFF) {
                sensorsOn = false;
                ble_send("SENSORS OFF");
            }
            else if (pendingAction == TURN_BOTH_OFF) {
                sensorsOn = false;
                hapticsOn = false;
                ble_send("ALL OFF");
            }

            updateLEDs();
            reportSystemState();

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
            conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "Connected");
            break;

        case BLE_GAP_EVENT_DISCONNECT:
            ESP_LOGI(TAG, "Disconnected");
            conn_handle = BLE_HS_CONN_HANDLE_NONE;
            break;
    }
    return 0;
}

// ===== BLE INIT =====
void ble_app_on_sync(void) {
    ble_hs_id_infer_auto(0, NULL);

    struct ble_gap_adv_params adv_params = {0};

    ble_gap_adv_start(0, NULL, BLE_HS_FOREVER,
                      &adv_params, ble_gap_event, NULL);
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

    gpio_set_direction(BTN1_PIN, GPIO_MODE_INPUT);
    gpio_set_direction(BTN2_PIN, GPIO_MODE_INPUT);

    updateLEDs();

    // BLE
    nimble_port_init();
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svcs);
    ble_gatts_add_svcs(gatt_svcs);
    ble_hs_cfg.sync_cb = ble_app_on_sync;
    nimble_port_freertos_init(ble_host_task);
}

    // ==== BT COMMS TASK ====
void bt_comms_task(void *pvParameters) {
    while (1) {

        // Button 1
        if (gpio_get_level(BTN1_PIN) == 0) {
            hapticsOn = !hapticsOn;
            ble_send(hapticsOn ? "HAPTICS ON" : "HAPTICS OFF");
            updateLEDs();
            reportSystemState();
            vTaskDelay(pdMS_TO_TICKS(300));
        }

        // Button 2
        if (gpio_get_level(BTN2_PIN) == 0) {
            if (sensorsOn) {
                requestGlassesConfirmation(TURN_SENSORS_OFF);
            } else {
                sensorsOn = true;
                ble_send("SENSORS ON");
                updateLEDs();
                reportSystemState();
            }
            vTaskDelay(pdMS_TO_TICKS(300));
        }

        // Battery every 5s
        static int64_t last = 0;
        int64_t now = esp_timer_get_time() / 1000;

        if (now - last > 5000) {
            last = now;

            char msg[50];
            sprintf(msg, "BATTERY:%d", (int)battery_percentage);
            ble_send(msg);
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
