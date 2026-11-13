#include "api_server.h"
#include "config_store.h"
#include "aria_sender.h"
#include "metadata_relay.h"

#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>

static WebServer server(API_SERVER_PORT);

static void sendCors() {
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

static const char *stateToString(AriaSenderState s) {
    switch (s) {
        case AriaSenderState::IDLE: return "idle";
        case AriaSenderState::CONNECTING: return "connecting";
        case AriaSenderState::WAITING_READY: return "waiting_handshake";
        case AriaSenderState::STREAMING: return "streaming";
        case AriaSenderState::RECONNECTING: return "reconnecting";
    }
    return "unknown";
}

static void handleOptions() {
    sendCors();
    server.send(204);
}

static void handleStatus() {
    sendCors();
    AriaSenderStatus st = ariaSenderGetStatus();

    JsonDocument doc;
    doc["wifi"]["connected"] = WiFi.status() == WL_CONNECTED;
    doc["wifi"]["ip"] = WiFi.localIP().toString();
    doc["wifi"]["ssid"] = configStore.cfg.wifiSsid;

    doc["receiver"]["host"] = st.host;
    doc["receiver"]["port"] = st.port;
    doc["receiver"]["state"] = stateToString(st.state);

    doc["stats"]["framesSent"] = st.framesSent;
    doc["stats"]["i2sTimeouts"] = st.i2sTimeouts;

    String out;
    serializeJson(doc, out);
    server.send(200, "application/json", out);
}

static void handleSetReceiver() {
    sendCors();

    if (server.method() != HTTP_POST) {
        server.send(405, "application/json", "{\"error\":\"method not allowed\"}");
        return;
    }

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, server.arg("plain"));
    if (err) {
        server.send(400, "application/json", "{\"error\":\"invalid json\"}");
        return;
    }

    if (!doc["host"].is<const char *>() || strlen(doc["host"].as<const char *>()) == 0) {
        server.send(400, "application/json", "{\"error\":\"missing host\"}");
        return;
    }

    String host = doc["host"].as<String>();
    uint16_t port = doc["port"] | 12889;

    configStore.saveReceiver(host, port);
    ariaSenderSetTarget(host, port);
    // Il receiver è appena cambiato (o è la prima volta): dagli subito le
    // info Now Playing che avevamo in cache, senza aspettare il prossimo
    // cambio traccia dall'app.
    metadataRelayResendTo(host, port);

    JsonDocument resp;
    resp["host"] = host;
    resp["port"] = port;
    resp["ok"] = true;
    String out;
    serializeJson(resp, out);
    server.send(200, "application/json", out);
}

static void handleSetMetadata() {
    sendCors();

    if (server.method() != HTTP_POST) {
        server.send(405, "application/json", "{\"error\":\"method not allowed\"}");
        return;
    }

    // Nessun parsing: l'app manda già l'esatto corpo JSON da girare al
    // receiver, ci limitiamo a metterlo in cache e inoltrarlo.
    String body = server.arg("plain");
    metadataRelaySet(body, configStore.cfg.receiverHost, configStore.cfg.receiverPort);

    server.send(200, "application/json", "{\"ok\":true}");
}

static void handleDeleteReceiver() {
    sendCors();
    configStore.clearReceiver();
    ariaSenderSetTarget("", 0);
    server.send(200, "application/json", "{\"ok\":true}");
}

static void handleWifiReset() {
    sendCors();
    server.send(200, "application/json", "{\"ok\":true,\"message\":\"riavvio in modalita' AP\"}");
    delay(500);
    configStore.clearWifi();
    ESP.restart();
}

void apiServerBegin() {
    server.on("/api/status", HTTP_GET, handleStatus);
    server.on("/api/status", HTTP_OPTIONS, handleOptions);

    server.on("/api/receiver", HTTP_POST, handleSetReceiver);
    server.on("/api/receiver", HTTP_DELETE, handleDeleteReceiver);
    server.on("/api/receiver", HTTP_OPTIONS, handleOptions);

    server.on("/api/metadata", HTTP_POST, handleSetMetadata);
    server.on("/api/metadata", HTTP_OPTIONS, handleOptions);

    server.on("/api/wifi/reset", HTTP_POST, handleWifiReset);
    server.on("/api/wifi/reset", HTTP_OPTIONS, handleOptions);

    server.begin();
    Serial.printf("[API] REST API in ascolto sulla porta %u\n", API_SERVER_PORT);
}

void apiServerHandle() {
    server.handleClient();
}
