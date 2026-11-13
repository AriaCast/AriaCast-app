#include "aria_sender.h"
#include "i2s_input.h"

#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

// Dimensione frame fissata dalla spec AriaCast: 48000 Hz * 2 canali *
// 2 byte * 20 ms / 1000 = 3840 byte. Nessun header di frame.
static const size_t AUDIO_FRAME_SIZE = 3840;
static const uint32_t HANDSHAKE_TIMEOUT_MS = 3000;
static const uint32_t RECONNECT_INTERVAL_MS = 3000;

static WebSocketsClient ws;
static AriaSenderStatus status;
static SemaphoreHandle_t statusMutex;

// --- Gestione cambio target (thread-safe, richiesto dall'API REST) ---
static SemaphoreHandle_t targetMutex;
static volatile bool targetChanged = false;
static String pendingHost;
static uint16_t pendingPort = 0;

static bool wsBegun = false;
static bool handshakeReady = false;
static uint32_t connectedAtMillis = 0;

static void setStatus(AriaSenderState s) {
    xSemaphoreTake(statusMutex, portMAX_DELAY);
    status.state = s;
    xSemaphoreGive(statusMutex);
}

static void webSocketEvent(WStype_t type, uint8_t *payload, size_t length) {
    switch (type) {
        case WStype_CONNECTED:
            Serial.println("[Aria] WebSocket connesso, attendo handshake READY...");
            handshakeReady = false;
            connectedAtMillis = millis();
            setStatus(AriaSenderState::WAITING_READY);
            break;

        case WStype_DISCONNECTED:
            Serial.println("[Aria] WebSocket disconnesso.");
            handshakeReady = false;
            setStatus(AriaSenderState::RECONNECTING);
            break;

        case WStype_TEXT: {
            // Handshake del Receiver: {"status":"READY", ...}
            // oppure, come alias di compatibilità, {"type":"handshake"}
            JsonDocument doc;
            DeserializationError err = deserializeJson(doc, payload, length);
            if (err) {
                Serial.printf("[Aria] JSON handshake non valido: %s\n", err.c_str());
                break;
            }
            const char *statusField = doc["status"] | "";
            const char *typeField = doc["type"] | "";
            if (strcmp(statusField, "READY") == 0 || strcmp(typeField, "handshake") == 0) {
                Serial.println("[Aria] Handshake OK, inizio streaming.");
                handshakeReady = true;
                setStatus(AriaSenderState::STREAMING);
            }
            break;
        }

        case WStype_ERROR:
            Serial.println("[Aria] Errore WebSocket.");
            break;

        default:
            break;
    }
}

void ariaSenderSetTarget(const String &host, uint16_t port) {
    xSemaphoreTake(targetMutex, portMAX_DELAY);
    pendingHost = host;
    pendingPort = port;
    targetChanged = true;
    xSemaphoreGive(targetMutex);
}

AriaSenderStatus ariaSenderGetStatus() {
    xSemaphoreTake(statusMutex, portMAX_DELAY);
    AriaSenderStatus copy = status;
    xSemaphoreGive(statusMutex);
    return copy;
}

static void applyPendingTargetIfAny() {
    if (!targetChanged) return;

    xSemaphoreTake(targetMutex, portMAX_DELAY);
    String newHost = pendingHost;
    uint16_t newPort = pendingPort;
    targetChanged = false;
    xSemaphoreGive(targetMutex);

    if (wsBegun) {
        ws.disconnect();
        wsBegun = false;
    }
    handshakeReady = false;

    xSemaphoreTake(statusMutex, portMAX_DELAY);
    status.host = newHost;
    status.port = newPort;
    xSemaphoreGive(statusMutex);

    if (newHost.length() == 0) {
        Serial.println("[Aria] Nessun receiver selezionato: in attesa.");
        setStatus(AriaSenderState::IDLE);
        return;
    }

    Serial.printf("[Aria] Nuovo receiver: %s:%u\n", newHost.c_str(), newPort);
    ws.begin(newHost.c_str(), newPort, "/audio");
    ws.setReconnectInterval(RECONNECT_INTERVAL_MS);
    wsBegun = true;
    setStatus(AriaSenderState::CONNECTING);
}

static void ariaSenderTask(void *pv) {
    static uint8_t frameBuf[AUDIO_FRAME_SIZE];

    for (;;) {
        applyPendingTargetIfAny();

        if (wsBegun) {
            ws.loop();

            // Timeout handshake: se connessi ma nessun READY entro 3s,
            // ricicliamo la connessione (il Receiver potrebbe essere
            // incompatibile o il socket "fantasma").
            AriaSenderStatus cur = ariaSenderGetStatus();
            if (cur.state == AriaSenderState::WAITING_READY &&
                millis() - connectedAtMillis > HANDSHAKE_TIMEOUT_MS) {
                Serial.println("[Aria] Timeout handshake, riconnetto.");
                ws.disconnect();
            }
        }

        if (handshakeReady) {
            bool ok = i2sInputRead(frameBuf, AUDIO_FRAME_SIZE, 60);
            if (!ok) {
                xSemaphoreTake(statusMutex, portMAX_DELAY);
                status.i2sTimeouts++;
                xSemaphoreGive(statusMutex);
                // frameBuf è già stato azzerato da i2sInputRead: inviamo
                // comunque silenzio, per non far scadere il socket lato
                // Receiver e mantenere il pacing a 20ms.
            }

            ws.sendBIN(frameBuf, AUDIO_FRAME_SIZE);

            xSemaphoreTake(statusMutex, portMAX_DELAY);
            status.framesSent++;
            xSemaphoreGive(statusMutex);
        } else {
            // Non ancora in streaming: nessun dato da inviare, ma continuiamo
            // a servire ws.loop() per gestire handshake/riconnessione.
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
}

void ariaSenderBegin() {
    statusMutex = xSemaphoreCreateMutex();
    targetMutex = xSemaphoreCreateMutex();

    ws.onEvent(webSocketEvent);

    // Task dedicato, priorità medio-alta e pinnato al core 1 (il core 0
    // gestisce tipicamente WiFi/BT stack) per un pacing dei frame stabile.
    xTaskCreatePinnedToCore(ariaSenderTask, "aria_sender", 8192, nullptr, 10, nullptr, 1);
}
