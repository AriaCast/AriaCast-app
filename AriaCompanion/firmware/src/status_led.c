#include "status_led.h"

#include "board_config.h"
#include "hardware/pio.h"
#include "hardware/pwm.h"
#include "pico/stdlib.h"
#include "ws2812.pio.h"

static PIO pio = pio0;
static uint sm = 0;
static uint32_t last_update_ms;

static void put_rgb(uint8_t r, uint8_t g, uint8_t b) {
    if (pio_sm_is_tx_fifo_full(pio, sm)) return; /* never block the USB loop */
    pio_sm_put(pio, sm, ((uint32_t)g << 24) | ((uint32_t)r << 16) | ((uint32_t)b << 8));
}

void status_led_init(void) {
    gpio_set_function(ARIA_LED_PWM_PIN, GPIO_FUNC_PWM);
    uint slice = pwm_gpio_to_slice_num(ARIA_LED_PWM_PIN);
    pwm_set_wrap(slice, 255);
    pwm_set_enabled(slice, true);

    uint offset = pio_add_program(pio, &ws2812_program);
    ws2812_program_init(pio, sm, offset, ARIA_LED_WS2812_PIN, 800000, false);
    put_rgb(0, 0, 0);
}

bool status_led_present(void) { return true; }

static void set_level(uint8_t level, bool streaming) {
    pwm_set_gpio_level(ARIA_LED_PWM_PIN, (uint16_t)((uint32_t)level * level / 255));
    uint8_t v = level / 4; /* WS2812s are very bright */
    if (streaming) put_rgb(v, 0, v / 2); else put_rgb(0, 0, v);
}

void status_led_update(led_state_t state, uint16_t peak) {
    uint32_t now = to_ms_since_boot(get_absolute_time());
    if (now - last_update_ms < 20) return;
    last_update_ms = now;
    switch (state) {
    case LED_PRIVATE:      set_level((now / 1000) % 2 ? 40 : 0, false); break;
    case LED_SPEAKER_IDLE: set_level(80, false); break;
    case LED_STREAMING:    set_level((uint8_t)(30 + (uint32_t)(peak >> 7) * 225 / 255), true); break;
    case LED_OVERRUN:      set_level((now / 100) % 2 ? 255 : 0, true); break;
    }
}
