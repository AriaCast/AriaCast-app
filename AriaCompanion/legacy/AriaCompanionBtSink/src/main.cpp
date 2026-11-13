// ============================================================
// AriaCast BT Bridge — Scheda A (ESP32 WROOM, solo Bluetooth)
// ============================================================
// Riceve musica via A2DP dal telefono/PC sorgente e la mette in
// uscita su I2S fisico, sempre a 48000 Hz stereo 16-bit,
// qualunque sia il sample rate negoziato dalla sorgente
// (tipicamente 44100 o 48000 Hz): il ricampionamento avviene
// qui, tramite AudioTools::ResampleStream.
//
// La Scheda B (ESP32-S3, "AriaCast WiFi Sender") legge questo
// I2S come SLAVE a 48 kHz fisso e non deve quindi fare alcun
// ricampionamento: il rate è già garantito costante da qui.
//
// Nessun WiFi viene mai avviato su questa scheda.
//
// --- Collegamento fisico verso la Scheda B (I2S) ---
//   GPIO 26 -> BCK  (bit clock)     -- questa scheda è MASTER,
//   GPIO 25 -> WS   (word select)      genera lei entrambi i clock
//   GPIO 22 -> DATA (verso la Scheda B, che lo legge in RX)
//   GND     -> GND  (comune tra le due schede, OBBLIGATORIO)
//
// Modifica i pin qui sotto se la tua piedinatura è diversa.

#include <Arduino.h>
#include "AudioTools.h"
#include "BluetoothA2DPSink.h"

// ---- Configurazione ----
static const char *BT_DEVICE_NAME = "AriaCast-BT";

// Pin I2S verso la Scheda B (questa scheda è MASTER del bus)
static const int PIN_I2S_BCK = 26;
static const int PIN_I2S_WS = 25;
static const int PIN_I2S_DATA = 22;

// Rate di uscita fisso richiesto dal protocollo AriaCast
static const uint32_t OUTPUT_SAMPLE_RATE = 48000;

// Rate assunto come default prima che una sorgente si connetta
// (la stragrande maggioranza dei telefoni negozia 44100 Hz;
// verrà corretto automaticamente non appena la sorgente si
// connette, vedi updateResampleRateIfNeeded()).
static const uint32_t ASSUMED_SOURCE_RATE = 44100;

AudioInfo fromInfo(ASSUMED_SOURCE_RATE, 2, 16);
AudioInfo toInfo(OUTPUT_SAMPLE_RATE, 2, 16);

I2SStream i2sOut;
ResampleStream resampler(i2sOut);
BluetoothA2DPSink a2dpSink(resampler);

static uint32_t lastKnownSourceRate = 0;

// Il sample rate reale negoziato con la sorgente BT si conosce solo
// DOPO la connessione (handshake AVDTP). Lo controlliamo periodicamente
// e, se cambia rispetto a quanto assunto, aggiorniamo il resampler al
// volo — copre sia il caso "sorgente diversa da quella assunta" sia il
// caso "l'utente disconnette un telefono e ne connette un altro con
// impostazioni diverse" (es. laptop Linux a 48kHz vs telefono a 44.1kHz).
static void updateResampleRateIfNeeded() {
    uint32_t rate = a2dpSink.sample_rate();
    if (rate == 0 || rate == lastKnownSourceRate) return;

    lastKnownSourceRate = rate;
    Serial.printf("[A2DP] Sample rate sorgente: %u Hz -> ricampiono a %u Hz\n",
                  rate, OUTPUT_SAMPLE_RATE);

    float stepSize = (float)rate / (float)OUTPUT_SAMPLE_RATE;
    resampler.setStepSize(stepSize);
}

static void avrcConnectionStateCallback(bool connected) {
    Serial.printf("[BT] Stato connessione: %s\n", connected ? "CONNESSO" : "disconnesso");
    if (!connected) {
        // Alla disconnessione torniamo al rate assunto di default,
        // pronto per la prossima sorgente.
        lastKnownSourceRate = 0;
    }
}

void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("=== AriaCast BT Bridge (Scheda A) ===");
    Serial.println("Solo Bluetooth: nessun WiFi verrà avviato su questa scheda.");

    // --- Uscita I2S, fissa a 48 kHz, questa scheda genera il clock ---
    auto i2sCfg = i2sOut.defaultConfig(TX_MODE);
    i2sCfg.copyFrom(toInfo);
    i2sCfg.pin_bck = PIN_I2S_BCK;
    i2sCfg.pin_ws = PIN_I2S_WS;
    i2sCfg.pin_data = PIN_I2S_DATA;
    i2sCfg.is_master = true;
    i2sOut.begin(i2sCfg);

    // --- Resampler: rate iniziale assunto, verrà corretto a runtime ---
    resampler.begin(fromInfo, toInfo.sample_rate);

    // --- Sink A2DP ---
    a2dpSink.set_avrc_connection_state_callback(avrcConnectionStateCallback);
    a2dpSink.set_auto_reconnect(true);
    a2dpSink.start(BT_DEVICE_NAME);

    Serial.printf("[BT] In attesa di pairing come \"%s\"...\n", BT_DEVICE_NAME);
}

void loop() {
    updateResampleRateIfNeeded();
    delay(500);
}
