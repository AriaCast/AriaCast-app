/**
 * AriaCompanion ESP32 Firmware
 *
 * Acts as a Bluetooth A2DP sink and streams the received PCM audio over a
 * plain TCP socket so AriaCast can capture it without being blocked by
 * Samsung AudioHardening or DRM restrictions.
 *
 * Audio format sent over TCP: 44100 Hz, stereo, 16-bit little-endian PCM.
 * AriaCast upsamples this to 48000 Hz internally before forwarding.
 *
 * Required library: ESP32-A2DP by Phil Schatzmann
 * Install via Arduino Library Manager: "ESP32-A2DP"
 *
 * Board: ESP32 (any variant with Bluetooth Classic, e.g. ESP32-WROOM-32,
 *         ESP32-S3 with PSRAM recommended for larger buffer headroom)
 *
 * First-time setup:
 *   1. Flash this sketch. The ESP32 starts as Wi-Fi AP "AriaCompanion-XXXX".
 *   2. Connect your phone to that network.
 *   3. In the AriaCast app → Settings → AriaCompanion → First-Time Setup,
 *      enter your home Wi-Fi credentials and tap Configure.
 *   4. The ESP32 restarts, joins your home Wi-Fi, and is ready.
 *
 * After setup:
 *   - Pair your phone's Bluetooth to "AriaCompanion" and set it as audio output.
 *   - In AriaCast → Settings → AriaCompanion, enable "Use as Audio Source".
 *   - Start casting normally — AriaCast pulls audio from the ESP32 over TCP.
 */

#include "BluetoothA2DPSink.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <Preferences.h>

// ── Configuration ────────────────────────────────────────────────────────────
#define TCP_PORT        7001
#define RING_BUF_BYTES  (48 * 1024)   // ~278 ms at 44100 Hz stereo 16-bit
#define BT_DEVICE_NAME  "AriaCompanion"

// ── Globals ───────────────────────────────────────────────────────────────────
BluetoothA2DPSink a2dp;
Preferences       prefs;
WebServer         http(80);
WiFiServer        tcpServer(TCP_PORT);
WiFiClient        tcpClient;

// Ring buffer — written by the A2DP callback (BT task), read by loop() (WiFi task).
static uint8_t          ring[RING_BUF_BYTES];
static volatile int32_t ringWrite  = 0;
static volatile int32_t ringRead   = 0;
static volatile int32_t ringAvail  = 0;
static SemaphoreHandle_t ringMutex = nullptr;

bool isSetupMode = false;

// ── Ring-buffer helpers ───────────────────────────────────────────────────────
static void ringPush(const uint8_t* data, uint32_t len) {
    xSemaphoreTake(ringMutex, portMAX_DELAY);
    for (uint32_t i = 0; i < len; i++) {
        ring[ringWrite] = data[i];
        ringWrite = (ringWrite + 1) % RING_BUF_BYTES;
        if (ringAvail < RING_BUF_BYTES) {
            ringAvail++;
        } else {
            // Buffer full: silently drop oldest byte
            ringRead = (ringRead + 1) % RING_BUF_BYTES;
        }
    }
    xSemaphoreGive(ringMutex);
}

static int32_t ringPop(uint8_t* buf, int32_t maxLen) {
    xSemaphoreTake(ringMutex, portMAX_DELAY);
    int32_t n = min((int32_t)maxLen, (int32_t)ringAvail);
    for (int32_t i = 0; i < n; i++) {
        buf[i] = ring[ringRead];
        ringRead = (ringRead + 1) % RING_BUF_BYTES;
    }
    ringAvail -= n;
    xSemaphoreGive(ringMutex);
    return n;
}

// ── A2DP data callback ────────────────────────────────────────────────────────
// Called by the Bluetooth stack task with decoded SBC PCM.
// Format: 44100 Hz, stereo, int16 little-endian (left then right per frame).
void onA2DPData(const uint8_t* data, uint32_t length) {
    ringPush(data, length);
}

// ── TCP streaming ─────────────────────────────────────────────────────────────
static uint8_t txBuf[1024];

void handleTcp() {
    // Accept a new client if none is connected
    if (!tcpClient || !tcpClient.connected()) {
        tcpClient = tcpServer.available();
        if (!tcpClient) return;
        Serial.println("TCP client connected: " + tcpClient.remoteIP().toString());
    }

    int32_t avail = ringPop(txBuf, sizeof(txBuf));
    if (avail > 0) {
        if (tcpClient.write(txBuf, avail) == 0) {
            Serial.println("TCP client disconnected");
            tcpClient.stop();
        }
    }
}

// ── HTTP handlers (setup + status) ───────────────────────────────────────────
void setupHttpHandlers() {
    // POST /wifi  body: ssid=...&pass=...
    http.on("/wifi", HTTP_POST, []() {
        String ssid = http.arg("ssid");
        String pass = http.arg("pass");
        if (ssid.isEmpty()) {
            http.send(400, "text/plain", "missing ssid");
            return;
        }
        prefs.putString("ssid", ssid);
        prefs.putString("pass", pass);
        http.send(200, "text/plain", "OK");
        delay(300);
        ESP.restart();
    });

    // POST /reset  — clear saved credentials and return to setup mode
    http.on("/reset", HTTP_POST, []() {
        prefs.remove("ssid");
        prefs.remove("pass");
        http.send(200, "text/plain", "OK");
        delay(300);
        ESP.restart();
    });

    // GET /status  — used by AriaCast to poll state
    http.on("/status", HTTP_GET, []() {
        String mode    = isSetupMode ? "setup" : "normal";
        String btConn  = a2dp.is_connected() ? "true" : "false";
        String tcpConn = (tcpClient && tcpClient.connected()) ? "true" : "false";
        http.send(200, "application/json",
            "{\"mode\":\"" + mode + "\",\"bt_connected\":" + btConn +
            ",\"streaming\":" + tcpConn + "}");
    });

    http.begin();
}

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);

    ringMutex = xSemaphoreCreateMutex();

    prefs.begin("ariacomp", false);
    String ssid = prefs.getString("ssid", "");
    String pass = prefs.getString("pass", "");

    if (ssid.isEmpty()) {
        // ── Setup mode: become a Wi-Fi AP ────────────────────────────────────
        isSetupMode = true;
        // Last 4 hex chars of MAC address as suffix
        String mac = WiFi.macAddress();  // "XX:XX:XX:XX:XX:XX"
        mac.replace(":", "");
        String apSsid = String("AriaCompanion-") + mac.substring(mac.length() - 4);

        WiFi.softAP(apSsid.c_str(), "ariacast");
        Serial.println("AP mode — SSID: " + apSsid + "  IP: " + WiFi.softAPIP().toString());

        setupHttpHandlers();
        return;
    }

    // ── Normal mode: connect to home Wi-Fi ───────────────────────────────────
    Serial.print("Connecting to " + ssid);
    WiFi.begin(ssid.c_str(), pass.c_str());
    for (int i = 0; i < 30 && WiFi.status() != WL_CONNECTED; i++) {
        delay(500);
        Serial.print(".");
    }
    Serial.println();

    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("Wi-Fi failed — returning to setup mode");
        prefs.remove("ssid");
        prefs.remove("pass");
        ESP.restart();
    }

    Serial.println("Wi-Fi connected: " + WiFi.localIP().toString());

    // mDNS — AriaCast discovers the device via _ariacompanion._tcp
    if (MDNS.begin("ariacompanion")) {
        MDNS.addService("ariacompanion", "tcp", TCP_PORT);
        MDNS.addServiceTxt("ariacompanion", "tcp", "sample_rate", "44100");
        MDNS.addServiceTxt("ariacompanion", "tcp", "channels",    "2");
        MDNS.addServiceTxt("ariacompanion", "tcp", "bits",        "16");
        Serial.println("mDNS started");
    }

    setupHttpHandlers();
    tcpServer.begin();
    Serial.println("TCP server listening on port " + String(TCP_PORT));

    // A2DP sink — connects to the phone's Bluetooth audio output
    a2dp.set_stream_reader(onA2DPData, false);
    a2dp.start(BT_DEVICE_NAME);
    Serial.println("Bluetooth A2DP sink started as \"" BT_DEVICE_NAME "\"");
}

// ── Loop ──────────────────────────────────────────────────────────────────────
void loop() {
    http.handleClient();
    if (!isSetupMode) {
        handleTcp();
    }
    delay(1);
}
