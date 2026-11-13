cmake_minimum_required(VERSION 3.13)

# One image runs on the Raspberry Pi Pico and the Waveshare RP2040-Zero (see board_config.h).
# For the RP2350 (Pico 2), configure with -DPICO_BOARD=pico2.
if(NOT DEFINED PICO_BOARD)
  set(PICO_BOARD pico)
endif()

include(pico_sdk_import.cmake)
project(aria_companion C CXX ASM)
set(CMAKE_C_STANDARD 11)
pico_sdk_init()

add_executable(aria_companion
  src/main.c
  src/usb_descriptors.c
  src/loopback.c
  src/status_led.c
)
pico_generate_pio_header(aria_companion ${CMAKE_CURRENT_LIST_DIR}/src/ws2812.pio)
target_compile_options(aria_companion PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_include_directories(aria_companion PRIVATE src include)
target_link_libraries(aria_companion PRIVATE
  pico_stdlib pico_unique_id pico_bootrom
  tinyusb_device
  hardware_pio hardware_pwm hardware_watchdog)
pico_enable_stdio_usb(aria_companion 0)
pico_enable_stdio_uart(aria_companion 0)
pico_add_extra_outputs(aria_companion)
