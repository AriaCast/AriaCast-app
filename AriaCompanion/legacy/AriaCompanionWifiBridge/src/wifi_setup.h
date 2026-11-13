#pragma once
#include <Arduino.h>

// Prova a connettersi in STA con le credenziali salvate in NVS.
// Ritorna true se connesso entro timeoutMs.
bool wifiConnectSTA(uint32_t timeoutMs = 15000);

// Avvia l'Access Point "AriaCast-Setup" con un mini portale web (porta 80)
// per inserire SSID/password. Funzione bloccante: gestisce il portale
// finché l'utente non salva la configurazione, poi riavvia il dispositivo.
void wifiStartConfigPortal();
