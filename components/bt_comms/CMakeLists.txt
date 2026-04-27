idf_component_register(
    SRCS "bt_logic.c"          
    INCLUDE_DIRS "."           
    REQUIRES
        freertos
        driver
        esp_adc
        esp_timer
        nvs_flash
        bt
)