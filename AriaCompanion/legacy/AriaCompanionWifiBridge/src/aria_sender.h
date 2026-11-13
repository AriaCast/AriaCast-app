#pragma once
// Implementa il ruolo "Sender" del protocollo AriaCast verso /audio:
// https://github.com/AriaCast/AriaCast-Protocol-Spec/blob/main/spec/transport.md
// https://github.com/AriaCast/AriaCast-Protocol-Spec/blob/main/spec/audio.md
//
// - Connessione WebSocket a ws://<host>:<port>/audio
// - Attesa handshake JSON dal Receiver: {"status":"READY",...}
//   (accettato anche {"type":"handshake"} come alias, per compatibilità)
// - Timeout handshake: 3 secondi, poi riconnessione
// - Streaming di frame binari da esattamente 3840 byte (20ms di
//   PCM S16LE stereo a 48kHz), un frame ogni 20ms
// - Riconnessione automatica con backoff se la connessione cade

#include <Arduino.h>

enum class AriaSenderState {
    IDLE,           // nessun receiver configurato
    CONNECTING,     // handshake WebSocket in corso
    WAITING_READY,  // in attesa del frame JSON "READY"
    STREAMING,      // invio frame audio attivo
    RECONNECTING    // connessione caduta, ritento a breve
};

struct AriaSenderStatus {
    AriaSenderState state = AriaSenderState::IDLE;
    String host;
    uint16_t port = 0;
    uint32_t framesSent = 0;
    uint32_t i2sTimeouts = 0; // quante volte abbiamo dovuto inviare silenzio
};

// Avvia il task FreeRTOS che gestisce connessione WS + streaming.
// Va chiamata una sola volta, dopo che il WiFi è connesso.
void ariaSenderBegin();

// Imposta (o cambia) il receiver di destinazione. Se host è vuoto,
// interrompe qualsiasi streaming attivo e torna in stato IDLE.
// Chiamata dall'API REST quando l'app seleziona un receiver.
void ariaSenderSetTarget(const String &host, uint16_t port);

// Stato corrente, per l'endpoint /api/status.
AriaSenderStatus ariaSenderGetStatus();
