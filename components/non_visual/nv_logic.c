// ============================================================
//  NON_VISUAL LOGIC
//  Reads 4 ultrasonic sensors via ADC, updates shared S1-S4,
//  reads battery via I2C MAX17048
// ============================================================

#include "nv_includes.h"
#include "nv_config.h"

// ============================================================
//  GLOBALS
// ============================================================

// Shared sensor values - read by haptics module
float S1 = 0.0f;
float S2 = 0.0f;
float S3 = 0.0f;
float S4 = 0.0f;

// Shared battery percentage - read by bt_comms
float battery_percentage = 0.0f;

i2c_master_dev_handle_t dev_handle;

// Controlled by bt_comms
extern bool sensorsOn;

// ============================================================
//  ADC CALIBRATION HELPER
// ============================================================

bool adc_calibration_init(adc_unit_t unit, adc_atten_t atten, adc_cali_handle_t *handle) {
    adc_cali_line_fitting_config_t cfg = {
        .unit_id  = unit,
        .atten    = atten,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    return (adc_cali_create_scheme_line_fitting(&cfg, handle) == ESP_OK);
}

// ============================================================
//  MAX17048 I2C READ HELPER
// ============================================================

esp_err_t max17048_read_reg(uint8_t reg_addr, uint16_t *reg_data) {
    uint8_t buffer[2];
    esp_err_t ret = i2c_master_transmit_receive(dev_handle, &reg_addr, 1, buffer, 2, -1);
    if (ret == ESP_OK) {
        *reg_data = (buffer[0] << 8) | buffer[1];
    }
    return ret;
}

// ============================================================
//  SENSOR TASK
//  Runs continuously, updates S1-S4 shared globals
// ============================================================

static sensor_t sensors[NUM_SENSORS];
static adc_oneshot_unit_handle_t adc_handle;

void non_visual_task(void *pvParameters) {
    while (1) {
        if (!sensorsOn) {
            // Sensors disabled by bt_comms - clear readings
            S1 = S2 = S3 = S4 = 0.0f;
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        // Trigger pulse
        gpio_set_level(TRIGGER_GPIO, 1);
        ets_delay_us(50);
        gpio_set_level(TRIGGER_GPIO, 0);

        // Read each sensor
        for (int i = 0; i < NUM_SENSORS; i++) {
            vTaskDelay(pdMS_TO_TICKS(20));

            int raw = 0, mv = 0;
            if (adc_oneshot_read(adc_handle, sensors[i].channel, &raw) == ESP_OK) {

                if (sensors[i].is_calibrated)
                    adc_cali_raw_to_voltage(sensors[i].cali_handle, raw, &mv);
                else
                    mv = (raw * 3300) / 4095;

                float current_dist_ft = (float)mv / (MV_PER_INCH * 12.0f);

                // Init buffer on first read
                if (!sensors[i].buffer_filled) {
                    for (int j = 0; j < WINDOW_SIZE; j++)
                        sensors[i].buffer[j] = current_dist_ft;
                    sensors[i].buffer_filled = true;
                }

                // Rolling average filter
                sensors[i].buffer[sensors[i].buffer_index] = current_dist_ft;
                sensors[i].buffer_index = (sensors[i].buffer_index + 1) % WINDOW_SIZE;

                float sum = 0;
                for (int j = 0; j < WINDOW_SIZE; j++)
                    sum += sensors[i].buffer[j];

                sensors[i].result_ft = roundf(sum / WINDOW_SIZE);
            }
        }

        // Update shared globals
        S1 = sensors[0].result_ft;
        S2 = sensors[1].result_ft;
        S3 = sensors[2].result_ft;
        S4 = sensors[3].result_ft;

        // Battery read
        uint16_t soc_raw = 0;
        if (max17048_read_reg(MAX17048_SOC_REG, &soc_raw) == ESP_OK)
            battery_percentage = soc_raw / 256.0f;

        printf("\rS1:%.0f | S2:%.0f | S3:%.0f | S4:%.0f | Bat:%.2f%%",
               S1, S2, S3, S4, battery_percentage);
        fflush(stdout);

        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

// ============================================================
//  MODULE BOOT FUNCTION - called from main.c
// ============================================================

void boot_nv(void) {
    // GPIO trigger
    gpio_config_t io_conf = {
        .intr_type    = GPIO_INTR_DISABLE,
        .mode         = GPIO_MODE_OUTPUT,
        .pin_bit_mask = (1ULL << TRIGGER_GPIO),
        .pull_down_en = 0,
        .pull_up_en   = 0
    };
    gpio_config(&io_conf);
    gpio_set_level(TRIGGER_GPIO, 0);

    // I2C bus
    i2c_master_bus_config_t i2c_bus_config = {
        .clk_source               = I2C_CLK_SRC_DEFAULT,
        .i2c_port                 = I2C_MASTER_NUM,
        .scl_io_num               = I2C_MASTER_SCL_IO,
        .sda_io_num               = I2C_MASTER_SDA_IO,
        .glitch_ignore_cnt        = 7,
        .flags.enable_internal_pullup = true,
    };
    i2c_master_bus_handle_t bus_handle;
    ESP_ERROR_CHECK(i2c_new_master_bus(&i2c_bus_config, &bus_handle));

    // I2C device (battery gauge)
    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = MAX17048_ADDR,
        .scl_speed_hz    = 100000,
    };
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus_handle, &dev_cfg, &dev_handle));

    // ADC unit
    adc_oneshot_unit_init_cfg_t init_config = { .unit_id = ADC_UNIT };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config, &adc_handle));

    // Sensor channels
    adc_channel_t channels[NUM_SENSORS] = {
        ADC_CHANNEL_6, ADC_CHANNEL_7, ADC_CHANNEL_4, ADC_CHANNEL_5
    };

    for (int i = 0; i < NUM_SENSORS; i++) {
        sensors[i].channel      = channels[i];
        sensors[i].buffer_index = 0;
        sensors[i].buffer_filled = false;

        for (int j = 0; j < WINDOW_SIZE; j++)
            sensors[i].buffer[j] = 0.0f;

        adc_oneshot_chan_cfg_t config = {
            .atten    = ADC_ATTEN,
            .bitwidth = ADC_BITWIDTH_DEFAULT
        };
        ESP_ERROR_CHECK(adc_oneshot_config_channel(adc_handle, sensors[i].channel, &config));
        sensors[i].is_calibrated = adc_calibration_init(ADC_UNIT, ADC_ATTEN, &sensors[i].cali_handle);
    }

    xTaskCreate(non_visual_task, "non_visual_task", 4096, NULL, 5, NULL);
}