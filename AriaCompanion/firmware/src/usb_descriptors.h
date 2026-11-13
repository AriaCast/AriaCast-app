#ifndef ARIA_USB_DESCRIPTORS_H
#define ARIA_USB_DESCRIPTORS_H

#include <stdint.h>
#include "aria_companion_proto.h"

/* Entity ids inside the audio function */
#define ARIA_ENTITY_INPUT_TERMINAL   0x01
#define ARIA_ENTITY_OUTPUT_TERMINAL  0x03
#define ARIA_ENTITY_CLOCK            0x04

/* SPEAKER mode interface numbers */
#define ARIA_ITF_AUDIO_CONTROL   0
#define ARIA_ITF_AUDIO_STREAMING 1
#define ARIA_ITF_VENDOR_SPEAKER  2
/* PRIVATE mode */
#define ARIA_ITF_VENDOR_PRIVATE  0

#define ARIA_EP_AUDIO_OUT        0x01
#define ARIA_EP_VENDOR_IN_SPK    0x82
#define ARIA_EP_VENDOR_IN_PRIV   0x81

/* 48 samples per 1 ms frame, plus one sample of margin, stereo, 16-bit = 196 bytes */
#define ARIA_ISO_EP_SIZE ((ARIA_SAMPLE_RATE / 1000 + 1) * ARIA_CHANNELS * ARIA_BYTES_PER_SAMPLE)

#define ARIA_AUDIO_SPEAKER_DESC_LEN (TUD_AUDIO_DESC_IAD_LEN \
    + TUD_AUDIO_DESC_STD_AC_LEN \
    + TUD_AUDIO_DESC_CS_AC_LEN \
    + TUD_AUDIO_DESC_CLK_SRC_LEN \
    + TUD_AUDIO_DESC_INPUT_TERM_LEN \
    + TUD_AUDIO_DESC_OUTPUT_TERM_LEN \
    + TUD_AUDIO_DESC_STD_AS_INT_LEN \
    + TUD_AUDIO_DESC_STD_AS_INT_LEN \
    + TUD_AUDIO_DESC_CS_AS_INT_LEN \
    + TUD_AUDIO_DESC_TYPE_I_FORMAT_LEN \
    + TUD_AUDIO_DESC_STD_AS_ISO_EP_LEN \
    + TUD_AUDIO_DESC_CS_AS_ISO_EP_LEN)

#define ARIA_VENDOR_DESC_LEN (9 + 7)

/* Current mode (descriptor set) - owned by control.c */
extern volatile uint8_t aria_usb_mode;

#endif
