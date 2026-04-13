// components/bt_comms/bt_config.h
#ifndef BT_CONFIG_H
#define BT_CONFIG_H

// GPIO Pins
#define BTN1_PIN 26
#define BTN2_PIN 27
#define MOSFET1_PIN 18
#define MOSFET2_PIN 19
#define BT_ADC_UNIT     ADC_UNIT_1
#define BT_ADC_CHANNEL  ADC_CHANNEL_6
#define BT_ADC_ATTEN    ADC_ATTEN_DB_12

// BLE Configuration
#define TAG "BLE_SYS"

// State variables (if needed)
extern bool hapticsOn;
extern bool sensorsOn;
extern uint16_t conn_handle;
extern uint16_t char_handle;

extern float S1, S2, S3, S4; // from nv_logic.c

void init_bt(void);
void boot_bt(void);

#endif // BT_CONFIG_H