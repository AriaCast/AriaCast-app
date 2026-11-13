// ============================================================
// AriaCast WiFi Sender — Scheda B (ESP32-S3, solo WiFi)
// ============================================================
// 1. Al primo avvio (o dopo un reset WiFi) apre un Access Point
//    "AriaCast-Setup" con un mini portale web per inserire le
//    credenziali della rete WiFi di casa.
// 2. Una volta in rete, legge audio PCM 48kHz stereo da I2S
//    (come slave, clock fornito dalla Scheda A "AriaCast BT
//    Bridge") e lo trasmette come Sender AriaCast via WebSocket.
// 3. Espone una REST API su porta 8081 (separata dai 12889 di
//    AriaCast) che la tua app usa per impostare quale receiver
//    AriaCast è attualmente selezionato — la logica di discovery
//    e selezione resta lato app, questa scheda si limita a
//    connettersi dove le viene detto.
// 4. Annuncia se stessa via mDNS come "_ariacompanion._tcp" sulla
//    porta della REST API, così l'app la trova con lo stesso scan
//    NSD già usato in precedenza (AriaCompanionActivity), senza
//    bisogno di inserire l'IP a mano.
//
// Nessun Bluetooth viene mai avviato su questa scheda.

#include <Arduino.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include "config_store.h"
#include "wifi_setup.h"
#include "i2s_input.h"
#include "aria_sender.h"
#include "api_server.h"

ConfigStore configStore;

void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("=== AriaCast WiFi Sender (Scheda B) ===");
    Serial.println("Solo WiFi: nessun Bluetooth verrà avviato su questa scheda.");

    configStore.begin();

    // --- WiFi: se non ci sono credenziali salvate, o se la
    // connessione fallisce, entriamo in modalità di configurazione
    // (bloccante finché l'utente non salva e il device non riavvia).
    bool connected = wifiConnectSTA(15000);
    if (!connected) {
        Serial.println("[WiFi] Nessuna connessione disponibile: avvio portale di setup.");
        wifiStartConfigPortal(); // non ritorna mai (riavvia da lì)
    }

    // --- Ingresso I2S (slave, 48kHz fisso) ---
    if (!i2sInputBegin()) {
        Serial.println("[I2S] ERRORE inizializzazione I2S. Riavvio tra 5s...");
        delay(5000);
        ESP.restart();
    }

    // --- Sender AriaCast (task dedicato) ---
    ariaSenderBegin();

    // Se un receiver era già stato selezionato in precedenza (salvato
    // in NVS dall'ultima chiamata dell'app), riprendiamo subito lo
    // streaming verso di lui senza attendere una nuova chiamata API.
    if (configStore.cfg.receiverHost.length() > 0) {
        Serial.printf("[Main] Riprendo receiver salvato: %s:%u\n",
                      configStore.cfg.receiverHost.c_str(),
                      configStore.cfg.receiverPort);
        ariaSenderSetTarget(configStore.cfg.receiverHost, configStore.cfg.receiverPort);
    }

    // --- REST API di controllo, per l'app ---
    apiServerBegin();

    // --- mDNS: stesso nome/servizio del vecchio firmware, così
    // AriaCompanionActivity la trova via NSD senza modifiche al tipo
    // di servizio cercato. Non bloccante se fallisce (resta comunque
    // raggiungibile via IP manuale).
    if (MDNS.begin("ariacompanion")) {
        MDNS.addService("ariacompanion", "tcp", API_SERVER_PORT);
        Serial.println("[mDNS] Servizio _ariacompanion._tcp annunciato (porta REST API).");
    } else {
        Serial.println("[mDNS] Avvio mDNS fallito (non bloccante, resta l'IP manuale).");
    }

    Serial.println("[Main] Setup completato.");
}

void loop() {
    apiServerHandle();

    // Watchdog minimale: se il WiFi cade, tenta una riconnessione.
    // (Lo streaming si mette comunque in pausa/silenzio finché la
    // connessione WS non torna disponibile: vedi aria_sender.cpp)
    static uint32_t lastWifiCheck = 0;
    if (millis() - lastWifiCheck > 5000) {
        lastWifiCheck = millis();
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("[WiFi] Connessione persa, tento riconnessione...");
            WiFi.reconnect();
        }
    }

    delay(5);
}
