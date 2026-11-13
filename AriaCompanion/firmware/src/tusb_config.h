#ifndef TUSB_CONFIG_H
#define TUSB_CONFIG_H

#include "aria_companion_proto.h"

#ifndef CFG_TUSB_MCU
#error CFG_TUSB_MCU must be defined (the Pico SDK does this)
#endif

#ifndef CFG_TUSB_OS
#define CFG_TUSB_OS              OPT_OS_NONE
#endif
#define CFG_TUSB_DEBUG           0
#define CFG_TUD_ENABLED          1
#define CFG_TUD_MAX_SPEED        OPT_MODE_FULL_SPEED
#define CFG_TUSB_RHPORT0_MODE    (OPT_MODE_DEVICE | OPT_MODE_FULL_SPEED)
#define CFG_TUSB_MEM_SECTION
#define CFG_TUSB_MEM_ALIGN       __attribute__((aligned(4)))
#define CFG_TUD_ENDPOINT0_SIZE   64

#define CFG_TUD_CDC              0
#define CFG_TUD_MSC              0
#define CFG_TUD_HID              0
#define CFG_TUD_MIDI             0
#define CFG_TUD_AUDIO            1
#define CFG_TUD_VENDOR           1

/* ---- Audio: UAC2 stereo speaker, 48 kHz / 16-bit, adaptive, no feedback endpoint ---- */
#include "usb_descriptors.h"
#define CFG_TUD_AUDIO_FUNC_1_DESC_LEN               ARIA_AUDIO_SPEAKER_DESC_LEN
#define CFG_TUD_AUDIO_FUNC_1_N_AS_INT               1
#define CFG_TUD_AUDIO_FUNC_1_CTRL_BUF_SZ            64
#define CFG_TUD_AUDIO_ENABLE_EP_OUT                 1
#define CFG_TUD_AUDIO_FUNC_1_N_BYTES_PER_SAMPLE_RX  ARIA_BYTES_PER_SAMPLE
#define CFG_TUD_AUDIO_FUNC_1_N_CHANNELS_RX          ARIA_CHANNELS
#define CFG_TUD_AUDIO_FUNC_1_EP_OUT_SZ_MAX          ARIA_ISO_EP_SIZE
#define CFG_TUD_AUDIO_FUNC_1_EP_OUT_SW_BUF_SZ       (8 * ARIA_ISO_EP_SIZE)
#define CFG_TUD_AUDIO_ENABLE_FEEDBACK_EP            0

/* ---- Vendor: bulk IN only (audio and events back to the app) ---- */
#define CFG_TUD_VENDOR_EPSIZE      64
#define CFG_TUD_VENDOR_RX_BUFSIZE  0
#define CFG_TUD_VENDOR_TX_BUFSIZE  4096

#endif
