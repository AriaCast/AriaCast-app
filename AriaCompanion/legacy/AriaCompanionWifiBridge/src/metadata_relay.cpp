#include "metadata_relay.h"
#include <HTTPClient.h>

static String lastMetadataJson;

static void forwardToReceiver(const String &json, const String &host, uint16_t port) {
    if (host.length() == 0 || json.length() == 0) return;

    HTTPClient http;
    String url = "http://" + host + ":" + String(port) + "/metadata";
    if (!http.begin(url)) {
        Serial.println("[Metadata] http.begin() fallito, inoltro saltato.");
        return;
    }
    http.setTimeout(3000);
    http.addHeader("Content-Type", "application/json");

    int code = http.POST(json);
    if (code < 0) {
        Serial.printf("[Metadata] Inoltro fallito: %s\n", http.errorToString(code).c_str());
    } else {
        Serial.printf("[Metadata] Inoltrata al receiver %s (HTTP %d).\n", url.c_str(), code);
    }
    http.end();
}

void metadataRelaySet(const String &json, const String &receiverHost, uint16_t receiverPort) {
    lastMetadataJson = json;
    forwardToReceiver(json, receiverHost, receiverPort);
}

void metadataRelayResendTo(const String &receiverHost, uint16_t receiverPort) {
    forwardToReceiver(lastMetadataJson, receiverHost, receiverPort);
}
