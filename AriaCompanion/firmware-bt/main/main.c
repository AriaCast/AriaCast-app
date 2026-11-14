/*
 * AriaCompanion BT firmware (docs/ariacompanion-bt.md).
 *
 * The phone sees a Bluetooth speaker (A2DP sink, SBC). Bluedroid runs in external-codec
 * mode, so what the phone sends arrives here as SBC frames that are never decoded: they are
 * queued and written, unchanged, to the AriaCast app over an SPP (RFCOMM) channel. The app
 * drives the same two modes as the USB board: PRIVATE (A2DP kept disconnected, the phone
 * keeps its audio) and SPEAKER (the board connects A2DP to the phone). No HFP/HSP is
 * registered, so calls never route here.
 */
#include <errno.h>
#include <inttypes.h>
#include <string.h>
#include <unistd.h>

#include "driver/gpio.h"
#include "esp_a2dp_api.h"
#include "esp_bt.h"
#include "esp_bt_device.h"
#include "esp_bt_main.h"
#include "esp_gap_bt_api.h"
#include "esp_log.h"
#include "esp_spp_api.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/ringbuf.h"
#include "freertos/task.h"
#include "nvs_flash.h"

#include "aria_bt_proto.h"
#include "version.h"

#define TAG          "aria_bt"
#define LED_GPIO     GPIO_NUM_2
#define QUEUE_BYTES  (48 * 1024)   /* ~1.2 s of SBC at 328 kbit/s: rides out RFCOMM stalls */
#define TICK_MS      100
#define CONNECT_TRIES 3

/* ------------------------------------------------------------------ state */

typedef enum { EV_SPP_OPEN, EV_SPP_CLOSE, EV_CMD, EV_A2DP_CONNECTED, EV_A2DP_DISCONNECTED } ev_type_t;

typedef struct {
    ev_type_t     type;
    uint8_t       cmd;
    uint16_t      value;
    int           fd;
    esp_bd_addr_t bda;
} ev_t;

static QueueHandle_t   ctl_q;          /* everything that changes state goes through here */
static RingbufHandle_t out_rb;         /* chunks waiting to be written to the app         */

/* Owned by the control task, read elsewhere. */
static volatile int  spp_fd = -1;
static volatile bool want_speaker;
static volatile bool a2dp_connected;
static esp_bd_addr_t spp_peer;
static esp_bd_addr_t a2dp_peer;
static int64_t       last_heartbeat_us;
static uint32_t      heartbeat_timeout_ms = 3000;
static int           connect_tries;
static int64_t       retry_connect_at_us;

/* Counters (GET_STATS). */
static volatile uint32_t a2dp_bytes, spp_bytes, overruns, resets;
static volatile uint32_t pending_overrun;
static volatile int64_t  last_overrun_us = -10000000;

static uint32_t     seq;
static portMUX_TYPE seq_lock = portMUX_INITIALIZER_UNLOCKED;

static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32(uint8_t *p, uint32_t v) { put16(p, (uint16_t)v); put16(p + 2, (uint16_t)(v >> 16)); }

static uint8_t current_mode(void) { return (want_speaker && a2dp_connected) ? ARIA_MODE_SPEAKER : ARIA_MODE_PRIVATE; }

/* ------------------------------------------------------------------ outgoing chunks */

/*
 * Queues one chunk for the writer task. Audio is droppable (never blocks the Bluetooth task;
 * a full queue counts as an overrun); events wait briefly for room.
 */
static bool queue_chunk(uint8_t type, const void *prefix, size_t prefix_len, const void *data, size_t data_len, bool droppable)
{
    size_t payload = prefix_len + data_len;
    if (payload > ARIA_MAX_PAYLOAD) return false;
    size_t total = ARIA_HEADER_BYTES + payload;
    void *slot = NULL;
    if (xRingbufferSendAcquire(out_rb, &slot, total, droppable ? 0 : pdMS_TO_TICKS(50)) != pdTRUE || slot == NULL) {
        if (droppable) {
            overruns++;
            pending_overrun += (uint32_t)total;
            last_overrun_us = esp_timer_get_time();
        }
        return false;
    }
    uint8_t *p = slot;
    p[0] = ARIA_MAGIC;
    p[1] = type;
    put16(p + 2, (uint16_t)payload);
    taskENTER_CRITICAL(&seq_lock);
    put32(p + 4, seq++);
    taskEXIT_CRITICAL(&seq_lock);
    if (prefix_len) memcpy(p + ARIA_HEADER_BYTES, prefix, prefix_len);
    if (data_len) memcpy(p + ARIA_HEADER_BYTES + prefix_len, data, data_len);
    xRingbufferSendComplete(out_rb, slot);
    return true;
}

static void send_byte_chunk(uint8_t type, uint8_t v) { queue_chunk(type, &v, 1, NULL, 0, false); }

static void send_mode(void)
{
    if (spp_fd >= 0) send_byte_chunk(ARIA_CHUNK_MODE, current_mode());
}

static void writer_task(void *arg)
{
    for (;;) {
        size_t size = 0;
        uint8_t *item = xRingbufferReceive(out_rb, &size, pdMS_TO_TICKS(100));
        if (item == NULL) continue;
        int fd = spp_fd;
        size_t done = 0;
        int stalls = 0;
        while (fd >= 0 && done < size) {
            ssize_t n = write(fd, item + done, size - done);
            if (n > 0) { done += (size_t)n; stalls = 0; continue; }
            if (n < 0 && errno != EAGAIN) break;              /* link gone */
            if (++stalls > 100) break;                        /* ~0.5 s congested: give up on it */
            vTaskDelay(pdMS_TO_TICKS(5));
            fd = spp_fd;
        }
        spp_bytes += (uint32_t)done;
        vRingbufferReturnItem(out_rb, item);
    }
}

/* ------------------------------------------------------------------ incoming commands */

static void reader_task(void *arg)
{
    uint8_t buf[64];
    uint8_t cmd[ARIA_CMD_BYTES];
    size_t have = 0;
    for (;;) {
        int fd = spp_fd;
        if (fd < 0) { have = 0; vTaskDelay(pdMS_TO_TICKS(50)); continue; }
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) { vTaskDelay(pdMS_TO_TICKS(10)); continue; }
        for (ssize_t i = 0; i < n; i++) {
            if (have == 0 && buf[i] != ARIA_MAGIC) continue;   /* resync on the magic byte */
            cmd[have++] = buf[i];
            if (have == ARIA_CMD_BYTES) {
                ev_t ev = { .type = EV_CMD, .cmd = cmd[1], .value = (uint16_t)(cmd[2] | (cmd[3] << 8)) };
                xQueueSend(ctl_q, &ev, pdMS_TO_TICKS(20));
                have = 0;
            }
        }
    }
}

/* ------------------------------------------------------------------ modes */

static void go_speaker(void)
{
    if (spp_fd < 0) return;
    want_speaker = true;
    last_heartbeat_us = esp_timer_get_time();
    if (!a2dp_connected) {
        connect_tries = 1;
        retry_connect_at_us = 0;
        ESP_LOGI(TAG, "SPEAKER: connecting A2DP");
        esp_a2d_sink_connect(spp_peer);
    } else {
        send_mode();
    }
}

static void go_private(void)
{
    want_speaker = false;
    retry_connect_at_us = 0;
    if (a2dp_connected) {
        ESP_LOGI(TAG, "PRIVATE: dropping A2DP");
        esp_a2d_sink_disconnect(a2dp_peer);   /* MODE 0 goes out on the disconnect event */
    } else {
        send_mode();
    }
}

static void handle_command(uint8_t cmd, uint16_t value)
{
    switch (cmd) {
    case ARIA_CMD_GET_INFO: {
        uint8_t info[ARIA_INFO_BYTES];
        info[0] = ARIA_BT_PROTOCOL_VERSION;
        info[1] = current_mode();
        put16(info + 2, ARIA_BT_FW_VERSION_BCD);
        put16(info + 4, ARIA_CAP_LED | ARIA_CAP_BLUETOOTH);
        put16(info + 6, QUEUE_BYTES / 1024);
        queue_chunk(ARIA_CHUNK_INFO, info, sizeof(info), NULL, 0, false);
        break;
    }
    case ARIA_CMD_SET_MODE:
        if (value == ARIA_MODE_SPEAKER) go_speaker(); else go_private();
        break;
    case ARIA_CMD_HEARTBEAT:
        heartbeat_timeout_ms = (value < 1 ? 1 : value > 10 ? 10 : value) * 1000u;
        last_heartbeat_us = esp_timer_get_time();
        break;
    case ARIA_CMD_GET_STATS: {
        uint8_t st[ARIA_STATS_BYTES] = {0};
        put32(st + 0, a2dp_bytes);
        put32(st + 4, spp_bytes);
        put32(st + 8, overruns);
        put32(st + 12, resets);
        queue_chunk(ARIA_CHUNK_STATS, st, sizeof(st), NULL, 0, false);
        break;
    }
    case ARIA_CMD_RESET_STATS:
        a2dp_bytes = spp_bytes = overruns = resets = 0;
        break;
    default:
        ESP_LOGW(TAG, "unknown command 0x%02x", cmd);
    }
}

/* ------------------------------------------------------------------ status LED */

static void led_update(uint32_t tick)
{
    int on;
    if (esp_timer_get_time() - last_overrun_us < 1000000) on = tick % 2;                    /* dropping: fast blink */
    else if (current_mode() == ARIA_MODE_SPEAKER) on = 1;                                     /* speaker: solid */
    else if (spp_fd >= 0) { uint32_t t = tick % 10; on = (t == 0 || t == 2); }               /* app linked: double blink */
    else on = (tick / 10) % 2;                                                                /* idle: slow blink */
    gpio_set_level(LED_GPIO, on);
}

/* ------------------------------------------------------------------ control task */

static void control_task(void *arg)
{
    uint32_t tick = 0;
    for (;;) {
        ev_t ev;
        if (xQueueReceive(ctl_q, &ev, pdMS_TO_TICKS(TICK_MS)) == pdTRUE) {
            switch (ev.type) {
            case EV_SPP_OPEN:
                memcpy(spp_peer, ev.bda, sizeof(esp_bd_addr_t));
                spp_fd = ev.fd;
                want_speaker = false;
                ESP_LOGI(TAG, "app linked (fd %d)", ev.fd);
                if (a2dp_connected) go_private(); else send_mode();
                break;
            case EV_SPP_CLOSE:
                ESP_LOGI(TAG, "app link closed");
                spp_fd = -1;
                go_private();
                break;
            case EV_CMD:
                handle_command(ev.cmd, ev.value);
                break;
            case EV_A2DP_CONNECTED:
                memcpy(a2dp_peer, ev.bda, sizeof(esp_bd_addr_t));
                if (!want_speaker) {
                    /* The phone connected on its own (after pairing, at Bluetooth start-up):
                     * PRIVATE means the phone keeps its audio, so hang up. */
                    ESP_LOGI(TAG, "A2DP connected while PRIVATE: disconnecting");
                    esp_a2d_sink_disconnect(ev.bda);
                } else {
                    a2dp_connected = true;
                    connect_tries = 0;
                    send_mode();
                }
                break;
            case EV_A2DP_DISCONNECTED: {
                bool was = a2dp_connected;
                a2dp_connected = false;
                if (was) send_mode();
                if (want_speaker && spp_fd >= 0) {
                    if (connect_tries < CONNECT_TRIES) {
                        retry_connect_at_us = esp_timer_get_time() + 1000000;
                    } else {
                        ESP_LOGW(TAG, "A2DP would not connect: back to PRIVATE");
                        want_speaker = false;
                        send_mode();
                    }
                }
                break;
            }
            }
        }

        int64_t now = esp_timer_get_time();
        if (want_speaker && (now - last_heartbeat_us) / 1000 > heartbeat_timeout_ms) {
            ESP_LOGW(TAG, "no heartbeat: back to PRIVATE");
            go_private();
        }
        if (want_speaker && !a2dp_connected && retry_connect_at_us && now >= retry_connect_at_us) {
            retry_connect_at_us = 0;
            connect_tries++;
            esp_a2d_sink_connect(spp_peer);
        }
        if (pending_overrun && spp_fd >= 0) {
            uint8_t v[4];
            put32(v, pending_overrun);
            pending_overrun = 0;
            queue_chunk(ARIA_CHUNK_OVERRUN, v, sizeof(v), NULL, 0, false);
        }
        led_update(tick++);
    }
}

/* ------------------------------------------------------------------ Bluetooth callbacks */

static void post(ev_t ev) { xQueueSend(ctl_q, &ev, pdMS_TO_TICKS(20)); }

static uint8_t mask_to_code(uint8_t mask, const uint8_t *masks, const uint8_t *codes, int n)
{
    for (int i = 0; i < n; i++) if (mask & masks[i]) return codes[i];
    return 0;
}

static void a2d_cb(esp_a2d_cb_event_t event, esp_a2d_cb_param_t *param)
{
    switch (event) {
    case ESP_A2D_CONNECTION_STATE_EVT: {
        ev_t ev = {0};
        memcpy(ev.bda, param->conn_stat.remote_bda, sizeof(esp_bd_addr_t));
        if (param->conn_stat.state == ESP_A2D_CONNECTION_STATE_CONNECTED) { ev.type = EV_A2DP_CONNECTED; post(ev); }
        else if (param->conn_stat.state == ESP_A2D_CONNECTION_STATE_DISCONNECTED) { ev.type = EV_A2DP_DISCONNECTED; post(ev); }
        break;
    }
    case ESP_A2D_AUDIO_STATE_EVT:
        if (spp_fd >= 0) send_byte_chunk(ARIA_CHUNK_STREAM, param->audio_stat.state == ESP_A2D_AUDIO_STATE_STARTED ? 1 : 0);
        break;
    case ESP_A2D_AUDIO_CFG_EVT: {
        if (param->audio_cfg.mcc.type != ESP_A2D_MCT_SBC) break;
        const esp_a2d_cie_sbc_t *s = &param->audio_cfg.mcc.cie.sbc_info;
        static const uint8_t sf_m[] = { ESP_A2D_SBC_CIE_SF_48K, ESP_A2D_SBC_CIE_SF_44K, ESP_A2D_SBC_CIE_SF_32K, ESP_A2D_SBC_CIE_SF_16K };
        static const uint32_t sf_hz[] = { 48000, 44100, 32000, 16000 };
        uint32_t rate = 44100;
        for (int i = 0; i < 4; i++) if (s->samp_freq & sf_m[i]) { rate = sf_hz[i]; break; }
        static const uint8_t ch_m[] = { ESP_A2D_SBC_CIE_CH_MODE_JOINT_STEREO, ESP_A2D_SBC_CIE_CH_MODE_STEREO, ESP_A2D_SBC_CIE_CH_MODE_DUAL_CHANNEL, ESP_A2D_SBC_CIE_CH_MODE_MONO };
        static const uint8_t ch_c[] = { ARIA_SBC_JOINT, ARIA_SBC_STEREO, ARIA_SBC_DUAL, ARIA_SBC_MONO };
        static const uint8_t bl_m[] = { ESP_A2D_SBC_CIE_BLOCK_LEN_16, ESP_A2D_SBC_CIE_BLOCK_LEN_12, ESP_A2D_SBC_CIE_BLOCK_LEN_8, ESP_A2D_SBC_CIE_BLOCK_LEN_4 };
        static const uint8_t bl_c[] = { 16, 12, 8, 4 };
        uint8_t cfg[ARIA_SBC_CONFIG_BYTES] = {0};
        put32(cfg, rate);
        cfg[4] = mask_to_code(s->ch_mode, ch_m, ch_c, 4);
        cfg[5] = mask_to_code(s->block_len, bl_m, bl_c, 4);
        cfg[6] = (s->num_subbands & ESP_A2D_SBC_CIE_NUM_SUBBANDS_8) ? 8 : 4;
        cfg[7] = (s->alloc_mthd & ESP_A2D_SBC_CIE_ALLOC_MTHD_SRN) ? ARIA_SBC_ALLOC_SNR : ARIA_SBC_ALLOC_LOUDNESS;
        cfg[8] = s->min_bitpool;
        cfg[9] = s->max_bitpool;
        ESP_LOGI(TAG, "SBC %"PRIu32" Hz, mode %u, %u blocks, %u subbands, bitpool %u-%u", rate, cfg[4], cfg[5], cfg[6], cfg[8], cfg[9]);
        if (spp_fd >= 0) queue_chunk(ARIA_CHUNK_SBC_CONFIG, cfg, sizeof(cfg), NULL, 0, false);
        break;
    }
    default:
        break;
    }
}

/* Undecoded SBC from the phone: forward it as is. Runs on the Bluetooth task, never blocks. */
static void a2d_audio_cb(esp_a2d_conn_hdl_t conn_hdl, esp_a2d_audio_buff_t *buf)
{
    a2dp_bytes += buf->data_len;
    if (spp_fd >= 0 && want_speaker && buf->data_len > 0) {
        uint8_t prefix[ARIA_SBC_AUDIO_PREFIX] = {0};
        put32(prefix, buf->timestamp);
        put16(prefix + 4, buf->number_frame);
        queue_chunk(ARIA_CHUNK_SBC_AUDIO, prefix, sizeof(prefix), buf->data, buf->data_len, true);
    }
    esp_a2d_audio_buff_free(buf);
}

static void spp_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    switch (event) {
    case ESP_SPP_INIT_EVT:
        if (param->init.status == ESP_SPP_SUCCESS) esp_spp_vfs_register();
        else ESP_LOGE(TAG, "SPP init failed: %d", param->init.status);
        break;
    case ESP_SPP_VFS_REGISTER_EVT:
        esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_SLAVE, 0, ARIA_BT_SPP_NAME);
        break;
    case ESP_SPP_SRV_OPEN_EVT:
        if (param->srv_open.status == ESP_SPP_SUCCESS) {
            ev_t ev = { .type = EV_SPP_OPEN, .fd = param->srv_open.fd };
            memcpy(ev.bda, param->srv_open.rem_bda, sizeof(esp_bd_addr_t));
            post(ev);
        }
        break;
    case ESP_SPP_CLOSE_EVT: {
        ev_t ev = { .type = EV_SPP_CLOSE };
        post(ev);
        break;
    }
    default:
        break;
    }
}

static void gap_cb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) ESP_LOGI(TAG, "paired with %s", param->auth_cmpl.device_name);
        else ESP_LOGW(TAG, "pairing failed: %d", param->auth_cmpl.stat);
        break;
    case ESP_BT_GAP_CFM_REQ_EVT:   /* numeric comparison: no display here, accept */
        esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, true);
        break;
    case ESP_BT_GAP_PIN_REQ_EVT: { /* legacy pairing fallback */
        esp_bt_pin_code_t pin = { '0', '0', '0', '0' };
        esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin);
        break;
    }
    default:
        break;
    }
}

/* ------------------------------------------------------------------ main */

void app_main(void)
{
    esp_err_t err = nvs_flash_init();   /* bonds live in NVS */
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    gpio_reset_pin(LED_GPIO);
    gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);

    ctl_q = xQueueCreate(16, sizeof(ev_t));
    out_rb = xRingbufferCreate(QUEUE_BYTES, RINGBUF_TYPE_NOSPLIT);
    configASSERT(ctl_q && out_rb);

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT));
    esp_bluedroid_config_t bluedroid_cfg = BT_BLUEDROID_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bluedroid_init_with_cfg(&bluedroid_cfg));
    ESP_ERROR_CHECK(esp_bluedroid_enable());

    ESP_ERROR_CHECK(esp_bt_gap_register_callback(gap_cb));
    esp_bt_gap_set_device_name(ARIA_BT_DEVICE_NAME);
    esp_bt_io_cap_t iocap = ESP_BT_IO_CAP_NONE;   /* "just works" pairing */
    esp_bt_gap_set_security_param(ESP_BT_SP_IOCAP_MODE, &iocap, sizeof(iocap));

    /* Control + return channel. */
    ESP_ERROR_CHECK(esp_spp_register_callback(spp_cb));
    esp_spp_cfg_t spp_cfg = BT_SPP_DEFAULT_CONFIG();
    ESP_ERROR_CHECK(esp_spp_enhanced_init(&spp_cfg));

    /* A2DP sink, SBC only, frames handed over undecoded. No AVRCP, no HFP. */
    ESP_ERROR_CHECK(esp_a2d_register_callback(a2d_cb));
    ESP_ERROR_CHECK(esp_a2d_sink_init());
    esp_a2d_mcc_t mcc = {0};
    mcc.type = ESP_A2D_MCT_SBC;
    mcc.cie.sbc_info.samp_freq = ESP_A2D_SBC_CIE_SF_44K | ESP_A2D_SBC_CIE_SF_48K;
    mcc.cie.sbc_info.ch_mode = 0xf;
    mcc.cie.sbc_info.block_len = 0xf;
    mcc.cie.sbc_info.num_subbands = 0x3;
    mcc.cie.sbc_info.alloc_mthd = 0x3;
    mcc.cie.sbc_info.min_bitpool = 2;
    mcc.cie.sbc_info.max_bitpool = 53;   /* A2DP "high quality" ceiling */
    ESP_ERROR_CHECK(esp_a2d_sink_register_stream_endpoint(0, &mcc));
    ESP_ERROR_CHECK(esp_a2d_sink_register_audio_data_callback(a2d_audio_cb));

    esp_bt_cod_t cod = {0};
    cod.major = ESP_BT_COD_MAJOR_DEV_AV;
    cod.minor = 0x05;   /* Audio/Video: loudspeaker */
    cod.service = ESP_BT_COD_SRVC_RENDERING | ESP_BT_COD_SRVC_AUDIO;
    esp_bt_gap_set_cod(cod, ESP_BT_INIT_COD);
    esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);

    xTaskCreate(control_task, "aria_ctl", 4096, NULL, 6, NULL);
    xTaskCreate(writer_task, "aria_tx", 4096, NULL, 7, NULL);
    xTaskCreate(reader_task, "aria_rx", 3072, NULL, 5, NULL);

    const uint8_t *addr = esp_bt_dev_get_address();
    ESP_LOGI(TAG, "AriaCompanion BT %d.%d ready as %02x:%02x:%02x:%02x:%02x:%02x", ARIA_BT_FW_VERSION_MAJOR,
             ARIA_BT_FW_VERSION_MINOR, addr[0], addr[1], addr[2], addr[3], addr[4], addr[5]);
}
