#include "i2s_input.h"
#include <driver/i2s_std.h>
#include <esp_check.h>

static i2s_chan_handle_t rxHandle = nullptr;

bool i2sInputBegin() {
    i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_SLAVE);
    // Buffer DMA generosi: assorbono il jitter del task che consuma i dati
    // (invio WebSocket) senza causare underrun sul lato I2S.
    chanCfg.dma_desc_num = 8;
    chanCfg.dma_frame_num = 480; // 480 frame stereo = 1920 byte per blocco DMA

    esp_err_t err = i2s_new_channel(&chanCfg, nullptr, &rxHandle);
    if (err != ESP_OK) {
        Serial.printf("[I2S] Errore i2s_new_channel: %d\n", err);
        return false;
    }

    i2s_std_config_t stdCfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(48000),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
            I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = (gpio_num_t)I2S_IN_PIN_BCK,
            .ws = (gpio_num_t)I2S_IN_PIN_WS,
            .dout = I2S_GPIO_UNUSED,
            .din = (gpio_num_t)I2S_IN_PIN_DATA,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv = false,
            },
        },
    };

    err = i2s_channel_init_std_mode(rxHandle, &stdCfg);
    if (err != ESP_OK) {
        Serial.printf("[I2S] Errore i2s_channel_init_std_mode: %d\n", err);
        return false;
    }

    err = i2s_channel_enable(rxHandle);
    if (err != ESP_OK) {
        Serial.printf("[I2S] Errore i2s_channel_enable: %d\n", err);
        return false;
    }

    Serial.printf("[I2S] Ingresso slave attivo. BCK=%d WS=%d DATA=%d (48kHz stereo 16bit)\n",
                  I2S_IN_PIN_BCK, I2S_IN_PIN_WS, I2S_IN_PIN_DATA);
    return true;
}

bool i2sInputRead(uint8_t *dst, size_t len, uint32_t timeoutMs) {
    if (!rxHandle) {
        memset(dst, 0, len);
        return false;
    }

    size_t bytesRead = 0;
    esp_err_t err = i2s_channel_read(rxHandle, dst, len, &bytesRead, pdMS_TO_TICKS(timeoutMs));

    if (err != ESP_OK || bytesRead != len) {
        // Timeout: probabilmente la Scheda A non sta fornendo clock
        // (spenta, o nessuna sorgente Bluetooth connessa). Riempiamo
        // di silenzio per non bloccare il chiamante.
        memset(dst, 0, len);
        return false;
    }

    return true;
}
