/*
 * USB descriptors for both modes (docs/ariacompanion-2.md §3).
 * SPEAKER: UAC2 speaker (interfaces 0-1) + vendor bulk IN (interface 2).
 * PRIVATE: vendor bulk IN only (interface 0) - Android sees no audio device.
 */
#include "tusb.h"
#include "pico/unique_id.h"
#include "usb_descriptors.h"
#include "version.h"

volatile uint8_t aria_usb_mode = ARIA_MODE_PRIVATE;

enum { STR_LANGID = 0, STR_MANUFACTURER, STR_PRODUCT, STR_SERIAL, STR_AUDIO, STR_VENDOR };

static tusb_desc_device_t const desc_device_speaker = {
    .bLength = sizeof(tusb_desc_device_t),
    .bDescriptorType = TUSB_DESC_DEVICE,
    .bcdUSB = 0x0200,
    .bDeviceClass = TUSB_CLASS_MISC,
    .bDeviceSubClass = MISC_SUBCLASS_COMMON,
    .bDeviceProtocol = MISC_PROTOCOL_IAD,
    .bMaxPacketSize0 = CFG_TUD_ENDPOINT0_SIZE,
    .idVendor = ARIA_VID,
    .idProduct = ARIA_PID,
    .bcdDevice = ARIA_FW_VERSION_BCD,
    .iManufacturer = STR_MANUFACTURER,
    .iProduct = STR_PRODUCT,
    .iSerialNumber = STR_SERIAL,
    .bNumConfigurations = 1,
};

static tusb_desc_device_t const desc_device_private = {
    .bLength = sizeof(tusb_desc_device_t),
    .bDescriptorType = TUSB_DESC_DEVICE,
    .bcdUSB = 0x0200,
    .bDeviceClass = 0x00,
    .bDeviceSubClass = 0x00,
    .bDeviceProtocol = 0x00,
    .bMaxPacketSize0 = CFG_TUD_ENDPOINT0_SIZE,
    .idVendor = ARIA_VID,
    .idProduct = ARIA_PID,
    .bcdDevice = ARIA_FW_VERSION_BCD,
    .iManufacturer = STR_MANUFACTURER,
    .iProduct = STR_PRODUCT,
    .iSerialNumber = STR_SERIAL,
    .bNumConfigurations = 1,
};

uint8_t const *tud_descriptor_device_cb(void) {
    return (uint8_t const *)(aria_usb_mode == ARIA_MODE_SPEAKER ? &desc_device_speaker : &desc_device_private);
}

#define ARIA_VENDOR_DESCRIPTOR(_itf, _stridx, _epin) \
    9, TUSB_DESC_INTERFACE, _itf, 0, 1, ARIA_IFACE_CLASS, ARIA_IFACE_SUBCLASS, ARIA_IFACE_PROTOCOL, _stridx, \
    7, TUSB_DESC_ENDPOINT, _epin, TUSB_XFER_BULK, U16_TO_U8S_LE(CFG_TUD_VENDOR_EPSIZE), 0

#define CONFIG_SPEAKER_LEN (TUD_CONFIG_DESC_LEN + ARIA_AUDIO_SPEAKER_DESC_LEN + ARIA_VENDOR_DESC_LEN)
#define CONFIG_PRIVATE_LEN (TUD_CONFIG_DESC_LEN + ARIA_VENDOR_DESC_LEN)

static uint8_t const desc_config_speaker[] = {
    TUD_CONFIG_DESCRIPTOR(1, 3, 0, CONFIG_SPEAKER_LEN, 0x80, 100),

    /* Interface Association: interfaces 0-1 form the audio function */
    TUD_AUDIO_DESC_IAD(ARIA_ITF_AUDIO_CONTROL, 0x02, 0x00),
    TUD_AUDIO_DESC_STD_AC(ARIA_ITF_AUDIO_CONTROL, 0x00, STR_AUDIO),
    TUD_AUDIO_DESC_CS_AC(0x0200, AUDIO_FUNC_DESKTOP_SPEAKER,
                         TUD_AUDIO_DESC_CLK_SRC_LEN + TUD_AUDIO_DESC_INPUT_TERM_LEN + TUD_AUDIO_DESC_OUTPUT_TERM_LEN,
                         AUDIO_CS_AS_INTERFACE_CTRL_LATENCY_POS),
    /* Internal fixed clock, 48 kHz, frequency read-only */
    TUD_AUDIO_DESC_CLK_SRC(ARIA_ENTITY_CLOCK, AUDIO_CLOCK_SOURCE_ATT_INT_FIX_CLK,
                           (AUDIO_CTRL_R << AUDIO_CLOCK_SOURCE_CTRL_CLK_FRQ_POS), ARIA_ENTITY_INPUT_TERMINAL, 0x00),
    TUD_AUDIO_DESC_INPUT_TERM(ARIA_ENTITY_INPUT_TERMINAL, AUDIO_TERM_TYPE_USB_STREAMING, 0x00, ARIA_ENTITY_CLOCK,
                              ARIA_CHANNELS, AUDIO_CHANNEL_CONFIG_NON_PREDEFINED, 0x00, 0x0000, 0x00),
    /* Output terminal: "Speaker" (0x0301). No feature unit: Android scales volume itself. */
    TUD_AUDIO_DESC_OUTPUT_TERM(ARIA_ENTITY_OUTPUT_TERMINAL, AUDIO_TERM_TYPE_OUT_GENERIC_SPEAKER,
                               ARIA_ENTITY_INPUT_TERMINAL, ARIA_ENTITY_INPUT_TERMINAL, ARIA_ENTITY_CLOCK, 0x0000, 0x00),
    /* Streaming interface, alt 0: zero bandwidth */
    TUD_AUDIO_DESC_STD_AS_INT(ARIA_ITF_AUDIO_STREAMING, 0x00, 0x00, 0x00),
    /* alt 1: PCM 16-bit stereo */
    TUD_AUDIO_DESC_STD_AS_INT(ARIA_ITF_AUDIO_STREAMING, 0x01, 0x01, 0x00),
    TUD_AUDIO_DESC_CS_AS_INT(ARIA_ENTITY_INPUT_TERMINAL, AUDIO_CTRL_NONE, AUDIO_FORMAT_TYPE_I, AUDIO_DATA_FORMAT_TYPE_I_PCM,
                             ARIA_CHANNELS, AUDIO_CHANNEL_CONFIG_NON_PREDEFINED, 0x00),
    TUD_AUDIO_DESC_TYPE_I_FORMAT(ARIA_BYTES_PER_SAMPLE, 16),
    /* Isochronous OUT, adaptive: the board has no clock of its own, it takes what arrives */
    TUD_AUDIO_DESC_STD_AS_ISO_EP(ARIA_EP_AUDIO_OUT,
                                 (uint8_t)(TUSB_XFER_ISOCHRONOUS | TUSB_ISO_EP_ATT_ADAPTIVE | TUSB_ISO_EP_ATT_DATA),
                                 ARIA_ISO_EP_SIZE, 0x01),
    TUD_AUDIO_DESC_CS_AS_ISO_EP(AUDIO_CS_AS_ISO_DATA_EP_ATT_NON_MAX_PACKETS_OK, AUDIO_CTRL_NONE,
                                AUDIO_CS_AS_ISO_DATA_EP_LOCK_DELAY_UNIT_UNDEFINED, 0x0000),

    ARIA_VENDOR_DESCRIPTOR(ARIA_ITF_VENDOR_SPEAKER, STR_VENDOR, ARIA_EP_VENDOR_IN_SPK),
};

static uint8_t const desc_config_private[] = {
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, CONFIG_PRIVATE_LEN, 0x80, 100),
    ARIA_VENDOR_DESCRIPTOR(ARIA_ITF_VENDOR_PRIVATE, STR_VENDOR, ARIA_EP_VENDOR_IN_PRIV),
};

_Static_assert(sizeof(desc_config_speaker) == CONFIG_SPEAKER_LEN, "speaker config length");
_Static_assert(sizeof(desc_config_private) == CONFIG_PRIVATE_LEN, "private config length");

uint8_t const *tud_descriptor_configuration_cb(uint8_t index) {
    (void)index;
    return aria_usb_mode == ARIA_MODE_SPEAKER ? desc_config_speaker : desc_config_private;
}

static char const *string_desc[] = {
    [STR_MANUFACTURER] = "AriaCast",
    [STR_PRODUCT] = "AriaCompanion",
    [STR_AUDIO] = "AriaCompanion",
    [STR_VENDOR] = "AriaCompanion loopback",
};

static uint16_t desc_str[33];

uint16_t const *tud_descriptor_string_cb(uint8_t index, uint16_t langid) {
    (void)langid;
    uint8_t count;
    if (index == STR_LANGID) {
        desc_str[1] = 0x0409;
        count = 1;
    } else {
        char serial[2 * PICO_UNIQUE_BOARD_ID_SIZE_BYTES + 1];
        char const *str;
        if (index == STR_SERIAL) {
            pico_get_unique_board_id_string(serial, sizeof(serial));
            str = serial;
        } else if (index < sizeof(string_desc) / sizeof(string_desc[0]) && string_desc[index]) {
            str = string_desc[index];
        } else {
            return NULL;
        }
        count = 0;
        while (str[count] && count < 32) { desc_str[1 + count] = (uint8_t)str[count]; count++; }
    }
    desc_str[0] = (uint16_t)((TUSB_DESC_STRING << 8) | (2 * count + 2));
    return desc_str;
}
