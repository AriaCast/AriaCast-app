#pragma once
// Configurazione persistente in NVS (flash), tramite Preferences.
// Namespace "wifi" per le credenziali, "aria" per il receiver
// AriaCast selezionato dall'app.

#include <Arduino.h>
#include <Preferences.h>

struct AppConfig {
    String wifiSsid;
    String wifiPass;

    String receiverHost;           // IP/hostname del receiver AriaCast selezionato
    uint16_t receiverPort = 12889; // porta streaming di default da spec
};

class ConfigStore {
public:
    AppConfig cfg;

    void begin() {
        loadWifi();
        loadAria();
    }

    void loadWifi() {
        Preferences p;
        p.begin("wifi", true);
        cfg.wifiSsid = p.getString("ssid", "");
        cfg.wifiPass = p.getString("pass", "");
        p.end();
    }

    void saveWifi(const String &ssid, const String &pass) {
        Preferences p;
        p.begin("wifi", false);
        p.putString("ssid", ssid);
        p.putString("pass", pass);
        p.end();
        cfg.wifiSsid = ssid;
        cfg.wifiPass = pass;
    }

    void clearWifi() {
        Preferences p;
        p.begin("wifi", false);
        p.clear();
        p.end();
        cfg.wifiSsid = "";
        cfg.wifiPass = "";
    }

    void loadAria() {
        Preferences p;
        p.begin("aria", true);
        cfg.receiverHost = p.getString("host", "");
        cfg.receiverPort = p.getUShort("port", 12889);
        p.end();
    }

    void saveReceiver(const String &host, uint16_t port) {
        Preferences p;
        p.begin("aria", false);
        p.putString("host", host);
        p.putUShort("port", port);
        p.end();
        cfg.receiverHost = host;
        cfg.receiverPort = port;
    }

    void clearReceiver() {
        Preferences p;
        p.begin("aria", false);
        p.remove("host");
        p.remove("port");
        p.end();
        cfg.receiverHost = "";
        cfg.receiverPort = 12889;
    }
};

extern ConfigStore configStore;
