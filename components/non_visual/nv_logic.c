void app_main(void) {

    // GPIO trigger setup
    gpio_config_t io_conf = {
        .intr_type = GPIO_INTR_DISABLE,
        .mode = GPIO_MODE_OUTPUT,
        .pin_bit_mask = (1ULL << TRIGGER_GPIO),
        .pull_down_en = 0,
        .pull_up_en = 0
    };
    gpio_config(&io_conf);
    gpio_set_level(TRIGGER_GPIO, 0);

    // I2C setup
    i2c_master_bus_config_t i2c_bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = I2C_MASTER_NUM,
        .scl_io_num = I2C_MASTER_SCL_IO,
        .sda_io_num = I2C_MASTER_SDA_IO,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };

    i2c_master_bus_handle_t bus_handle;
    ESP_ERROR_CHECK(i2c_new_master_bus(&i2c_bus_config, &bus_handle));

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = MAX17048_ADDR,
        .scl_speed_hz = 100000,
    };
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus_handle, &dev_cfg, &dev_handle));

    // ADC setup
    adc_oneshot_unit_handle_t adc_handle;
    adc_oneshot_unit_init_cfg_t init_config = { .unit_id = ADC_UNIT };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config, &adc_handle));

    // Sensor configuration
    sensor_t sensors[NUM_SENSORS] = {
        { .channel = ADC_CHANNEL_6 },
        { .channel = ADC_CHANNEL_7 },
        { .channel = ADC_CHANNEL_4 },
        { .channel = ADC_CHANNEL_5 }
    };

    for (int i = 0; i < NUM_SENSORS; i++) {
        adc_oneshot_chan_cfg_t config = {
            .atten = ADC_ATTEN,
            .bitwidth = ADC_BITWIDTH_DEFAULT
        };
        ESP_ERROR_CHECK(adc_oneshot_config_channel(adc_handle, sensors[i].channel, &config));

        sensors[i].is_calibrated = adc_calibration_init(ADC_UNIT, ADC_ATTEN, &sensors[i].cali_handle);
        sensors[i].buffer_index = 0;
        sensors[i].buffer_filled = false;

        for (int j = 0; j < WINDOW_SIZE; j++)
            sensors[i].buffer[j] = 0.0f;
    }

    // MAIN LOOP
    while (1) {

        // Trigger pulse
        gpio_set_level(TRIGGER_GPIO, 1);
        ets_delay_us(50);
        gpio_set_level(TRIGGER_GPIO, 0);

        // Read sensors
        for (int i = 0; i < NUM_SENSORS; i++) {
            vTaskDelay(pdMS_TO_TICKS(20));

            int raw = 0, mv = 0;
            if (adc_oneshot_read(adc_handle, sensors[i].channel, &raw) == ESP_OK) {

                if (sensors[i].is_calibrated)
                    adc_cali_raw_to_voltage(sensors[i].cali_handle, raw, &mv);
                else
                    mv = (raw * 3300) / 4095;

                float current_dist_ft = (float)mv / (MV_PER_INCH * 12.0f);

                // Filtering
                if (!sensors[i].buffer_filled) {
                    for (int j = 0; j < WINDOW_SIZE; j++)
                        sensors[i].buffer[j] = current_dist_ft;
                    sensors[i].buffer_filled = true;
                }

                sensors[i].buffer[sensors[i].buffer_index] = current_dist_ft;
                sensors[i].buffer_index = (sensors[i].buffer_index + 1) % WINDOW_SIZE;

                float sum = 0;
                for (int j = 0; j < WINDOW_SIZE; j++)
                    sum += sensors[i].buffer[j];

                sensors[i].result_ft = roundf(sum / WINDOW_SIZE);
            }
        }

        // Battery read
        uint16_t soc_raw = 0;
        float battery_percentage = 0.0;
        if (max17048_read_reg(MAX17048_SOC_REG, &soc_raw) == ESP_OK)
            battery_percentage = soc_raw / 256.0f;

        // Output
        printf("\rS1:%.0f | S2:%.0f | S3:%.0f | S4:%.0f | Bat:%.2f%%",
               Sensor1, Sensor2, Sensor3, Sensor4, battery_percentage);
        fflush(stdout);

        vTaskDelay(pdMS_TO_TICKS(100));
    }
}
