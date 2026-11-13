/*
 * AriaCompanion 2 firmware (docs/ariacompanion-2.md §5).
 *
 * The phone sees a USB speaker; whatever it plays arrives on the isochronous OUT endpoint,
 * goes into a ring buffer and is sent straight back on the vendor bulk IN endpoint, which
 * only the AriaCast app opens. The board starts in PRIVATE mode (no speaker) and becomes a
 * speaker only while the app asks for it and keeps sending heartbeats.
 */
#include <string.h>

#include "pico/bootrom.h"
#include "pico/stdlib.h"
#include "hardware/structs/usb.h"
#include "hardware/watchdog.h"
#include "tusb.h"

#include "aria_companion_proto.h"
#include "loopback.h"
#include "status_led.h"
#include "usb_descriptors.h"
#include "version.h"

static aria_loop_t loop;

/* Control state */
static volatile int8_t   pending_mode = -1;      /* mode to switch to, or -1 */
static absolute_time_t   switch_at;
static volatile uint32_t heartbeat_timeout_ms = 3000;
static absolute_time_t   last_heartbeat;
static volatile bool     heartbeat_seen = false;
static volatile bool     reboot_to_bootloader = false;
static volatile bool     stream_active = false;
static volatile bool     stream_changed = false;
static bool              mode_announced = false;
static uint32_t          resets = 0;
static absolute_time_t   last_overrun_at;

static uint8_t tx[ARIA_HEADER_BYTES + ARIA_AUDIO_PREFIX_BYTES + ARIA_CHUNK_PCM_BYTES];

static uint16_t current_sof(void) { return (uint16_t)(usb_hw->sof_rd & 0x7FF); }

static uint8_t vendor_itf(void) {
    return aria_usb_mode == ARIA_MODE_SPEAKER ? ARIA_ITF_VENDOR_SPEAKER : ARIA_ITF_VENDOR_PRIVATE;
}

/* ---------------------------------------------------------------- audio path */

static void audio_pull(void) {
    if (aria_usb_mode != ARIA_MODE_SPEAKER) return;
    static uint8_t buf[CFG_TUD_AUDIO_FUNC_1_EP_OUT_SW_BUF_SZ];
    uint16_t n = tud_audio_available();
    if (n == 0) return;
    n = tud_audio_read(buf, n > sizeof(buf) ? sizeof(buf) : n);
    aria_loop_push(&loop, buf, n, current_sof());
    if (loop.pending_overrun) last_overrun_at = get_absolute_time();
}

static bool vendor_send(size_t n) {
    if (n == 0) return false;
    tud_vendor_write(tx, (uint32_t)n);
    return true;
}

static void vendor_pump(void) {
    if (!tud_vendor_mounted()) return;
    bool sent = false;
    /* Events first, so FORMAT always precedes the audio it describes. */
    if (!mode_announced && tud_vendor_write_available() >= ARIA_HEADER_BYTES + 1) {
        sent |= vendor_send(aria_write_mode_chunk(&loop, tx, sizeof(tx), aria_usb_mode));
        mode_announced = true;
    }
    if (stream_changed && tud_vendor_write_available() >= 2 * ARIA_HEADER_BYTES + 9) {
        stream_changed = false;
        if (stream_active) sent |= vendor_send(aria_write_format_chunk(&loop, tx, sizeof(tx)));
        sent |= vendor_send(aria_write_stream_chunk(&loop, tx, sizeof(tx), stream_active));
    }
    if (loop.pending_overrun && tud_vendor_write_available() >= ARIA_HEADER_BYTES + 4) {
        sent |= vendor_send(aria_write_overrun_chunk(&loop, tx, sizeof(tx)));
    }
    while (tud_vendor_write_available() >= sizeof(tx)) {
        size_t n = aria_write_audio_chunk(&loop, tx, sizeof(tx), !stream_active);
        if (!vendor_send(n)) break;
        sent = true;
    }
    if (sent) tud_vendor_write_flush();
}

/* ---------------------------------------------------------------- mode switching */

static void apply_pending_mode(void) {
    if (pending_mode < 0 || absolute_time_diff_us(get_absolute_time(), switch_at) > 0) return;
    uint8_t mode = (uint8_t)pending_mode;
    pending_mode = -1;
    if (mode == aria_usb_mode) return;
    /* Re-enumerate: drop off the bus, swap descriptor sets, come back. */
    tud_disconnect();
    sleep_ms(100);
    aria_usb_mode = mode;
    aria_loop_stream_start(&loop);
    stream_active = false;
    stream_changed = false;
    mode_announced = false;
    heartbeat_seen = false;
    last_heartbeat = get_absolute_time();
    tud_connect();
}

static void request_mode(uint8_t mode, uint32_t delay_ms) {
    pending_mode = (int8_t)mode;
    switch_at = make_timeout_time_ms(delay_ms);
}

static void heartbeat_check(void) {
    if (aria_usb_mode != ARIA_MODE_SPEAKER || pending_mode >= 0) return;
    int64_t since = absolute_time_diff_us(last_heartbeat, get_absolute_time()) / 1000;
    /* The app died or let go: give the phone its audio back. */
    if (since > (int64_t)heartbeat_timeout_ms) request_mode(ARIA_MODE_PRIVATE, 0);
}

/* ---------------------------------------------------------------- vendor requests */

static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32(uint8_t *p, uint32_t v) { put16(p, (uint16_t)v); put16(p + 2, (uint16_t)(v >> 16)); }

bool tud_vendor_control_xfer_cb(uint8_t rhport, uint8_t stage, tusb_control_request_t const *request) {
    if (request->bmRequestType_bit.type != TUSB_REQ_TYPE_VENDOR) return false;
    if (request->bmRequestType_bit.recipient != TUSB_REQ_RCPT_INTERFACE) return false;
    if (tu_u16_low(request->wIndex) != vendor_itf()) return false;
    if (stage != CONTROL_STAGE_SETUP) return true;

    static uint8_t reply[ARIA_STATS_BYTES];
    switch (request->bRequest) {
    case ARIA_REQ_GET_INFO:
        reply[0] = ARIA_PROTOCOL_VERSION;
        reply[1] = aria_usb_mode;
        put16(reply + 2, ARIA_FW_VERSION_BCD);
        put16(reply + 4, status_led_present() ? ARIA_CAP_LED : 0);
        put16(reply + 6, (uint16_t)(ARIA_RING_BYTES / 1024));
        return tud_control_xfer(rhport, request, reply, ARIA_INFO_BYTES);
    case ARIA_REQ_GET_STATS:
        put32(reply + 0, loop.iso_bytes);
        put32(reply + 4, loop.bulk_bytes);
        put32(reply + 8, loop.overruns);
        put32(reply + 12, resets);
        put16(reply + 16, loop.peak_l);
        put16(reply + 18, loop.peak_r);
        loop.peak_l = loop.peak_r = 0; /* peaks are per poll */
        return tud_control_xfer(rhport, request, reply, ARIA_STATS_BYTES);
    case ARIA_REQ_SET_MODE:
        if (request->wValue > ARIA_MODE_SPEAKER) return false;
        /* Reply first; re-enumerate a moment later. */
        request_mode((uint8_t)request->wValue, 50);
        last_heartbeat = get_absolute_time();
        return tud_control_status(rhport, request);
    case ARIA_REQ_HEARTBEAT: {
        uint16_t s = request->wValue;
        if (s < 1) s = 1;
        if (s > 10) s = 10;
        heartbeat_timeout_ms = s * 1000u;
        last_heartbeat = get_absolute_time();
        heartbeat_seen = true;
        return tud_control_status(rhport, request);
    }
    case ARIA_REQ_REBOOT_BOOTLOADER:
        reboot_to_bootloader = true;
        return tud_control_status(rhport, request);
    case ARIA_REQ_RESET_STATS:
        loop.iso_bytes = loop.bulk_bytes = loop.overruns = 0;
        resets = 0;
        return tud_control_status(rhport, request);
    default:
        return false;
    }
}

/* ---------------------------------------------------------------- UAC2 callbacks */

bool tud_audio_get_req_entity_cb(uint8_t rhport, tusb_control_request_t const *p_request) {
    audio_control_request_t const *request = (audio_control_request_t const *)p_request;
    if (request->bEntityID != ARIA_ENTITY_CLOCK) return false;
    if (request->bControlSelector == AUDIO_CS_CTRL_SAM_FREQ) {
        if (request->bRequest == AUDIO_CS_REQ_CUR) {
            audio_control_cur_4_t cur = { .bCur = (int32_t)tu_htole32(ARIA_SAMPLE_RATE) };
            return tud_audio_buffer_and_schedule_control_xfer(rhport, p_request, &cur, sizeof(cur));
        }
        if (request->bRequest == AUDIO_CS_REQ_RANGE) {
            audio_control_range_4_n_t(1) range = { .wNumSubRanges = tu_htole16(1) };
            range.subrange[0].bMin = ARIA_SAMPLE_RATE;
            range.subrange[0].bMax = ARIA_SAMPLE_RATE;
            range.subrange[0].bRes = 0;
            return tud_audio_buffer_and_schedule_control_xfer(rhport, p_request, &range, sizeof(range));
        }
    } else if (request->bControlSelector == AUDIO_CS_CTRL_CLK_VALID && request->bRequest == AUDIO_CS_REQ_CUR) {
        audio_control_cur_1_t valid = { .bCur = 1 };
        return tud_audio_buffer_and_schedule_control_xfer(rhport, p_request, &valid, sizeof(valid));
    }
    return false;
}

bool tud_audio_set_req_entity_cb(uint8_t rhport, tusb_control_request_t const *p_request, uint8_t *buf) {
    (void)rhport;
    audio_control_request_t const *request = (audio_control_request_t const *)p_request;
    /* The clock is fixed at 48 kHz; accept a SET_CUR of that rate, refuse anything else. */
    if (request->bEntityID == ARIA_ENTITY_CLOCK && request->bControlSelector == AUDIO_CS_CTRL_SAM_FREQ &&
        request->bRequest == AUDIO_CS_REQ_CUR && request->wLength == sizeof(audio_control_cur_4_t)) {
        return ((audio_control_cur_4_t const *)buf)->bCur == ARIA_SAMPLE_RATE;
    }
    return false;
}

bool tud_audio_set_itf_cb(uint8_t rhport, tusb_control_request_t const *p_request) {
    (void)rhport;
    uint8_t itf = tu_u16_low(tu_le16toh(p_request->wIndex));
    uint8_t alt = tu_u16_low(tu_le16toh(p_request->wValue));
    if (itf == ARIA_ITF_AUDIO_STREAMING && alt != 0) {
        aria_loop_stream_start(&loop);
        stream_active = true;
        stream_changed = true;
    }
    return true;
}

bool tud_audio_set_itf_close_EP_cb(uint8_t rhport, tusb_control_request_t const *p_request) {
    (void)rhport;
    uint8_t itf = tu_u16_low(tu_le16toh(p_request->wIndex));
    uint8_t alt = tu_u16_low(tu_le16toh(p_request->wValue));
    if (itf == ARIA_ITF_AUDIO_STREAMING && alt == 0) {
        stream_active = false;
        stream_changed = true;
    }
    return true;
}

void tud_mount_cb(void) { mode_announced = false; }
void tud_umount_cb(void) { stream_active = false; }
void tud_suspend_cb(bool remote_wakeup_en) { (void)remote_wakeup_en; }
void tud_resume_cb(void) { last_heartbeat = get_absolute_time(); }

/* ---------------------------------------------------------------- main */

static led_state_t led_state(void) {
    if (absolute_time_diff_us(last_overrun_at, get_absolute_time()) < 1000000) return LED_OVERRUN;
    if (aria_usb_mode != ARIA_MODE_SPEAKER) return LED_PRIVATE;
    return stream_active ? LED_STREAMING : LED_SPEAKER_IDLE;
}

int main(void) {
    if (watchdog_caused_reboot()) resets++;
    aria_loop_init(&loop);
    status_led_init();
    last_heartbeat = get_absolute_time();
    last_overrun_at = nil_time;

    tusb_init();
    watchdog_enable(500, true);

    while (true) {
        tud_task();
        audio_pull();
        vendor_pump();
        heartbeat_check();
        apply_pending_mode();
        status_led_update(led_state(), (uint16_t)(loop.peak_l > loop.peak_r ? loop.peak_l : loop.peak_r));
        if (reboot_to_bootloader) {
            sleep_ms(50); /* let the status stage finish */
            reset_usb_boot(0, 0);
        }
        watchdog_update();
    }
}
