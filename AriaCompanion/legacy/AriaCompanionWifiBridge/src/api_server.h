#pragma once
// API REST minimale, su una porta separata da quella AriaCast (12889),
// pensata per essere pilotata dalla tua app (che gestisce discovery e
// selezione del receiver). Questo firmware NON fa discovery propria:
// riceve semplicemente l'host/porta scelti dall'app.
//
// Endpoint:
//   GET  /api/status              -> stato corrente (JSON)
//   POST /api/receiver            -> body {"host":"...", "port":12889}
//   DELETE /api/receiver          -> deseleziona il receiver (stop streaming)
//   POST /api/metadata            -> body: lo stesso JSON che l'app manderebbe
//                                     a POST /metadata sul receiver; viene
//                                     inoltrato così com'è al receiver attuale
//   POST /api/wifi/reset          -> cancella le credenziali WiFi e riavvia
//                                     in modalità AP di configurazione

#include <Arduino.h>

// Porta di ascolto della REST API (diversa da 12889, usata da AriaCast).
static const uint16_t API_SERVER_PORT = 8081;

// Avvia il webserver dell'API. Va chiamata dopo che il WiFi è connesso.
void apiServerBegin();

// Da chiamare frequentemente nel loop() principale.
void apiServerHandle();
