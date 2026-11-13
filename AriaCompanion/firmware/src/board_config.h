#ifndef ARIA_BOARD_CONFIG_H
#define ARIA_BOARD_CONFIG_H
/*
 * One firmware image for every supported board: the status LED is driven on both
 *  - GPIO 25, plain LED via PWM (Raspberry Pi Pico / Pico 2), and
 *  - GPIO 16, WS2812 RGB LED via PIO (Waveshare RP2040-Zero).
 * Driving the pin a board doesn't use is harmless (nothing is connected to it).
 */
#define ARIA_LED_PWM_PIN    25
#define ARIA_LED_WS2812_PIN 16
#endif
