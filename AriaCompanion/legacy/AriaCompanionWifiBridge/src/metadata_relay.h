#pragma once
// Inoltra i metadati "Now Playing" dall'app al receiver AriaCast attuale.
//
// Non facciamo alcun parsing: l'app ci manda già l'esatto corpo JSON che
// AriaCast userebbe per un cast diretto (vedi AudioCastService.performMetadataUpdate,
// {"data": {...TrackMetadata...}}), quindi ci limitiamo a metterlo in cache
// e a inoltrarlo così com'è a POST http://<receiverHost>:<receiverPort>/metadata.

#include <Arduino.h>

// Mette in cache `json` come ultimo metadato noto e lo inoltra subito al
// receiver a receiverHost:receiverPort, se già impostato (host non vuoto).
void metadataRelaySet(const String &json, const String &receiverHost, uint16_t receiverPort);

// Ri-invia l'ultimo metadato in cache (se presente) al receiver indicato.
// Da chiamare subito dopo aver impostato/cambiato il receiver, così ha
// subito le info Now Playing senza aspettare il prossimo cambio traccia.
void metadataRelayResendTo(const String &receiverHost, uint16_t receiverPort);
