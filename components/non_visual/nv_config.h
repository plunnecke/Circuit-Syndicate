// ============================================================
//  [SETUP] CONFIGURATION
// ============================================================

#ifndef NV_CONFIG_H
#define NV_CONFIG_H

#include "nv_includes.h"

// Sensor count and filtering
#define NUM_SENSORS     4
#define WINDOW_SIZE     5

// ADC
#define ADC_UNIT        ADC_UNIT_1
#define ADC_ATTEN       ADC_ATTEN_DB_12
#define MV_PER_INCH     6.445f

// GPIO
#define TRIGGER_GPIO    5

// I2C
#define I2C_MASTER_SCL_IO   22
#define I2C_MASTER_SDA_IO   21
#define I2C_MASTER_NUM      0
#define MAX17048_ADDR       0x36
#define MAX17048_SOC_REG    0x04

// Sensor result accessors
#define Sensor1 sensors[0].result_ft
#define Sensor2 sensors[1].result_ft
#define Sensor3 sensors[2].result_ft
#define Sensor4 sensors[3].result_ft

// Sensor structure
typedef struct {
    adc_channel_t       channel;
    adc_cali_handle_t   cali_handle;
    bool                is_calibrated;
    float               buffer[WINDOW_SIZE];
    int                 buffer_index;
    bool                buffer_filled;
    float               result_ft;
} sensor_t;

// Shared sensor globals (written by non_visual, read by haptics)
extern float S1, S2, S3, S4;

// Shared battery percentage (written by non_visual, read by bt_comms)
extern float battery_percentage;

// I2C device handle (used in nv_logic.c)
extern i2c_master_dev_handle_t dev_handle;

// Function declarations
bool adc_calibration_init(adc_unit_t unit, adc_atten_t atten, adc_cali_handle_t *handle);
esp_err_t max17048_read_reg(uint8_t reg_addr, uint16_t *reg_data);
void boot_nv(void);

#endif // NV_CONFIG_H