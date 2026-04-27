idf_component_register(
    SRCS "nv_logic.c"
    INCLUDE_DIRS "."
    REQUIRES
        freertos
        driver
        esp_adc
        esp_timer
)