idf_component_register(
    SRCS
        "main.c"
    INCLUDE_DIRS
        "."
    REQUIRES
        driver
        esp_timer
)

target_link_libraries(${COMPONENT_TARGET} "-lm")