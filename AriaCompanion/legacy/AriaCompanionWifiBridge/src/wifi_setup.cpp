#include "wifi_setup.h"
#include "config_store.h"

#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>

static WebServer apServer(80);
static DNSServer dnsServer;
static const byte DNS_PORT = 53;
static const char *AP_SSID = "AriaCast-Setup";

static const char PAGE_FORM[] PROGMEM = R"HTML(
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AriaCast Setup</title>
<style>
body{font-family:sans-serif;background:#111;color:#eee;padding:24px;max-width:420px;margin:auto}
input{width:100%;padding:10px;margin:8px 0;border-radius:6px;border:1px solid #444;
      background:#1b1b1b;color:#eee;box-sizing:border-box}
button{width:100%;padding:12px;border:0;border-radius:6px;background:#4f8cff;
       color:#fff;font-size:16px;margin-top:12px}
h2{color:#fff} label{font-size:14px;color:#aaa}
</style></head><body>
<h2>Configura AriaCast WiFi Sender</h2>
<form method="POST" action="/save">
  <label>SSID rete WiFi</label>
  <input name="ssid" required>
  <label>Password</label>
  <input name="pass" type="password">
  <button type="submit">Salva e riavvia</button>
</form>
<p style="color:#666;font-size:12px">Il receiver AriaCast si seleziona poi dall'app,
non da qui.</p>
</body></html>
)HTML";

static void handleRoot() {
    apServer.send_P(200, "text/html", PAGE_FORM);
}

static void handleSave() {
    String ssid = apServer.arg("ssid");
    String pass = apServer.arg("pass");

    if (ssid.length() == 0) {
        apServer.send(400, "text/plain", "SSID mancante");
        return;
    }

    configStore.saveWifi(ssid, pass);

    apServer.send(200, "text/html",
        "<html><body style='font-family:sans-serif;background:#111;color:#eee;"
        "padding:24px;text-align:center'>"
        "<h2>Configurazione salvata.</h2><p>Riavvio in corso...</p>"
        "</body></html>");

    delay(1500);
    ESP.restart();
}

static void handleNotFound() {
    apServer.sendHeader("Location", "/", true);
    apServer.send(302, "text/plain", "");
}

bool wifiConnectSTA(uint32_t timeoutMs) {
    if (configStore.cfg.wifiSsid.length() == 0) {
        return false;
    }

    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false); // riduce latenza/jitter, importante per lo streaming
    WiFi.begin(configStore.cfg.wifiSsid.c_str(), configStore.cfg.wifiPass.c_str());

    Serial.printf("[WiFi] Connessione a '%s'...\n", configStore.cfg.wifiSsid.c_str());

    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < timeoutMs) {
        delay(250);
        Serial.print(".");
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[WiFi] Connesso. IP: %s\n", WiFi.localIP().toString().c_str());
        return true;
    }

    Serial.println("[WiFi] Connessione fallita (timeout).");
    return false;
}

void wifiStartConfigPortal() {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID);
    IPAddress apIP = WiFi.softAPIP();
    dnsServer.start(DNS_PORT, "*", apIP);

    apServer.on("/", HTTP_GET, handleRoot);
    apServer.on("/save", HTTP_POST, handleSave);
    apServer.onNotFound(handleNotFound);
    apServer.begin();

    Serial.println("========================================");
    Serial.printf("[WiFi] Access Point di configurazione attivo\n");
    Serial.printf("[WiFi] SSID: %s (nessuna password)\n", AP_SSID);
    Serial.printf("[WiFi] Vai su http://%s per configurare\n", apIP.toString().c_str());
    Serial.println("========================================");

    for (;;) {
        dnsServer.processNextRequest();
        apServer.handleClient();
        delay(2);
    }
}
