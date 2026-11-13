#ifndef ARIA_STATUS_LED_H
#define ARIA_STATUS_LED_H
#include <stdbool.h>
#include <stdint.h>

typedef enum { LED_PRIVATE, LED_SPEAKER_IDLE, LED_STREAMING, LED_OVERRUN } led_state_t;

void status_led_init(void);
bool status_led_present(void);
/* Slow blink: private. Solid: speaker, stream closed. Level: streaming. Fast blink: overrun. */
void status_led_update(led_state_t state, uint16_t peak);
#endif
