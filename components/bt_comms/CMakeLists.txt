idf_component_register(
    SRCS "bt_logic.c"          
    INCLUDE_DIRS "."           
    REQUIRES
        freertos
        nimble
        driver
        esp_adc
        esp_timer
        esp_log
        nvs_flash
        bt
)