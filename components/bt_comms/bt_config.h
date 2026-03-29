// components/bt_comms/bt_config.h
#ifndef BT_CONFIG_H
#define BT_CONFIG_H

// GPIO Pins
#define BTN1_PIN 26
#define BTN2_PIN 27
#define MOSFET1_PIN 18
#define MOSFET2_PIN 19
#define BATTERY_ADC_CHANNEL ADC1_CHANNEL_6

// BLE Configuration
#define TAG "BLE_SYS"

// State variables (if needed)
extern bool hapticsOn;
extern bool sensorsOn;
extern uint16_t conn_handle;
extern uint16_t char_handle;

#endif // BT_CONFIG_H