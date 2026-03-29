#ifndef NV_CONFIG.H
#define NV_CONFIG.H

#define NUM_SENSORS 4 
#define WINDOW_SIZE 5

#define ADC_UNIT ADC_UNIT_1 
#define ADC_ATTEN ADC_ATTEN_DB_12 
#define MV_PER_INCH 6.445f

#define TRIGGER_GPIO 5

//=============================================================
//  I2C CONFIGURATION
//=============================================================

#define I2C_MASTER_SCL_IO 22 
#define I2C_MASTER_SDA_IO 21 
#define I2C_MASTER_NUM 0 
#define MAX17048_ADDR 0x36 
#define MAX17048_SOC_REG 0x04 

//=============================================================
//  SENSOR STRUCTURE AND GLOBALS
//=============================================================

#define Sensor1 sensors[0].result_ft 
#define Sensor2 sensors[1].result_ft 
#define Sensor3 sensors[2].result_ft 
#define Sensor4 sensors[3].result_ft 

typedef struct { 
    adc_channel_t channel; 
    adc_cali_handle_t cali_handle; 
    bool is_calibrated; 

    float buffer[WINDOW_SIZE]; 
    int buffer_index; 
    bool buffer_filled; 

    float result_ft;} 
    sensor_t; 

i2c_master_dev_handle_t dev_handle; 

//=============================================================
//  ADC calibration helper
//==============================================================

static bool adc_calibration_init(adc_unit_t unit, adc_atten_t atten, adc_cali_handle_t *handle) 
{ 
    adc_cali_line_fitting_config_t cfg = 
    { 
        .unit_id = unit, 
        .atten = atten, 
        .bitwidth = ADC_BITWIDTH_DEFAULT, 
    }; 

    return (adc_cali_create_scheme_line_fitting(&cfg, handle) == ESP_OK); 
} 

//=============================================================
//  MAX17048 I2C Read Helper
//=============================================================

esp_err_t max17048_read_reg(uint8_t reg_addr, uint16_t *reg_data) { 
    uint8_t buffer[2]; 
    esp_err_t ret = i2c_master_transmit_receive(dev_handle, &reg_addr, 1, buffer, 2, -1); 

    if (ret == ESP_OK) { 
        *reg_data = (buffer[0] << 8) | buffer[1]; } 
    return ret; } 

#endif // NV_CONFIG.H