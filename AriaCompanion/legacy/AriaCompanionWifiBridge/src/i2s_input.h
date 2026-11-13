#pragma once
// Ingresso I2S in modalità SLAVE: il clock (BCK/WS) arriva dalla
// Scheda A (AriaCast BT Bridge), qui ci limitiamo a leggere i
// campioni. Formato fisso e non negoziabile: PCM S16LE, 48000 Hz,
// stereo — esattamente quanto richiesto da AriaCast (audio.md).

#include <Arduino.h>

// Pin di ingresso su questa scheda (ESP32-S3). Modifica se la tua
// piedinatura è diversa. Devono essere collegati alle uscite I2S
// della Scheda A (BCK->BCK, WS->WS, DATA della A -> DATA_IN qui).
#define I2S_IN_PIN_BCK 4
#define I2S_IN_PIN_WS 5
#define I2S_IN_PIN_DATA 6

// Inizializza il periferico I2S in modalità slave RX.
// Ritorna true se l'inizializzazione ha successo.
bool i2sInputBegin();

// Legge esattamente 'len' byte (bloccante, con timeout interno).
// Ritorna true se la lettura è completa entro il timeout; in caso
// di timeout (tipicamente perché la Scheda A non sta fornendo
// clock, es. spenta o nessuna sorgente BT connessa) restituisce
// false e 'dst' viene riempito di silenzio (zeri).
bool i2sInputRead(uint8_t *dst, size_t len, uint32_t timeoutMs = 200);
