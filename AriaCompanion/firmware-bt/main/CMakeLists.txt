idf_component_register(SRCS "main.c"
                       INCLUDE_DIRS "."
                       REQUIRES bt nvs_flash esp_driver_gpio esp_timer esp_ringbuf)
