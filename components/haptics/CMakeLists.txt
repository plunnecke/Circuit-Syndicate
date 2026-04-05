idf_component_register (
    SRCS "haps_logic.c"
    INCLUDE_DIRS "."
    REQUIRES 
        freertos
        driver
        esp_timer
        non_visual
)

target_link_libraries(${COMPONENT_TARGET} "-lm")