# AriaCompanion BT: Implementation Spec

The wireless variant of AriaCompanion 2. Same job as the USB board (cast apps whose audio
can't be captured), but the phone reaches it over Bluetooth instead of a cable. The USB
firmware (`AriaCompanion/firmware/`, RP2040) is unchanged; this document covers the second
firmware (`AriaCompanion/firmware-bt/`, ESP32) and what the app adds for it.

## 1. Overview

```
Phone  ──A2DP (SBC)──▶  ESP32-WROOM  ──RFCOMM/SPP (same SBC frames)──▶  AriaCast app ──▶ speakers
```

- The board is an ordinary **Bluetooth speaker (A2DP sink)** to Android. Whatever the phone
  plays to it is not decoded on the board: Bluedroid's external-codec mode hands over the
  SBC frames exactly as received, and the board forwards them to the app over an **SPP**
  (RFCOMM) channel. No second compression, no quality loss beyond Bluetooth itself, about
  200-350 kbit/s each way, which Classic Bluetooth carries comfortably.
- The app decodes SBC (BlueZ libsbc, native), resamples to 48 kHz and feeds the same
  pipeline as the USB board: media-only gate, call guard, cast to any protocol.
- **No HFP/HSP profile.** Android never routes calls to a device without a hands-free
  profile, so calls stay on the phone by construction. On top of that the app sends the
  board to PRIVATE during a call (§4.3), so the ringtone stays on the phone as well.
- Why not a Bluetooth microphone as the return path: the mic is HFP/SCO (16 kHz mono), and
  Android suspends A2DP while SCO is open, so music and loopback can't run together.

Hardware: any **classic ESP32** (ESP32-WROOM-32 / WROVER). ESP32-S2/S3/C3/C6 have no
Classic Bluetooth and cannot be an A2DP sink. Firmware: **ESP-IDF 5.5 or newer** (the
external-codec A2DP API, `esp_a2d_sink_register_audio_data_callback`, first shipped there).

## 2. Bluetooth identity

| Item | Value |
|---|---|
| Device name | `AriaCompanion BT` (the app matches the `AriaCompanion` prefix) |
| Class of Device | Major *Audio/Video*, minor *Loudspeaker*, services *Rendering* + *Audio* |
| Profiles | A2DP sink (SBC only), SPP server. **No** HFP/HSP, no AVRCP. |
| SPP | Server channel named `AriaCompanion`, standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB` |
| Pairing | Secure Simple Pairing, IO capability *NoInputNoOutput* ("Just works"). Bonds persist in NVS. |

## 3. The SPP protocol (v1)

One RFCOMM channel carries both directions. All multi-byte fields are little-endian.
`firmware-bt/main/aria_bt_proto.h` is the source of truth; `CompanionProtocol.kt` mirrors it.

### 3.1 Board → app: chunks

Same framing as the USB bulk stream (`docs/ariacompanion-2.md` §4.1): an 8-byte header
`u8 magic=0xAC, u8 type, u16 length, u32 seq` followed by `length` payload bytes. The app's
`ChunkParser` handles both transports.

| Type | Name | Payload |
|---|---|---|
| `0x03` | `STREAM` | `u8 state`: 1 when A2DP starts streaming, 0 when it suspends/stops |
| `0x04` | `OVERRUN` | `u32 dropped_bytes`: the board's queue to the app overflowed |
| `0x05` | `MODE` | `u8 mode`: 0 PRIVATE, 1 SPEAKER. Sent when the SPP link opens and after every change. |
| `0x06` | `SBC_CONFIG` | `u32 sample_rate`, `u8 channel_mode` (0 mono, 1 dual, 2 stereo, 3 joint), `u8 block_len`, `u8 subbands`, `u8 alloc` (0 loudness, 1 SNR), `u8 min_bitpool`, `u8 max_bitpool`, `u16 reserved`. Sent when A2DP configures the codec. |
| `0x07` | `SBC_AUDIO` | `u32 timestamp` (A2DP RTP timestamp of the first frame), `u16 frames`, `u16 reserved`, then the SBC frames back to back |
| `0x08` | `INFO` | reply to `GET_INFO`: same 8 bytes as the USB `GET_INFO` (`u8 proto, u8 mode, u16 fw, u16 caps, u16 queue_kib`) |
| `0x09` | `STATS` | reply to `GET_STATS`: same 20 bytes as USB (`u32 a2dp_bytes, u32 spp_bytes, u32 overruns, u32 resets, u16 peak_l=0, u16 peak_r=0`) |

### 3.2 App → board: commands

Fixed 4 bytes: `u8 magic=0xAC, u8 command, u16 value`. Command codes reuse the USB vendor
request numbers.

| Command | Value | Effect |
|---|---|---|
| `0x01 GET_INFO` | 0 | board replies with an `INFO` chunk |
| `0x02 SET_MODE` | 0 PRIVATE / 1 SPEAKER | see §4 |
| `0x03 HEARTBEAT` | timeout s (1-10) | keeps SPEAKER alive |
| `0x04 GET_STATS` | 0 | board replies with a `STATS` chunk |
| `0x06 RESET_STATS` | 0 | zeroes the counters |

## 4. Modes

The same two modes as the USB board, so `CompanionHub.reconcile()` drives both.

### 4.1 PRIVATE (default)

The phone keeps its audio. The board is connectable for SPP and pairing, but **A2DP is not
connected**: if the phone connects A2DP on its own (it tries after pairing and on Bluetooth
start-up), the board disconnects it straight away.

### 4.2 SPEAKER

Entered on `SET_MODE(1)` from the connected app: the board **initiates** the A2DP
connection to the phone at the other end of the SPP link. Android makes a newly connected
A2DP device the active media output, so media now plays to the board and comes back over SPP.

### 4.3 Rules

| Event | Board does |
|---|---|
| Power-on | PRIVATE |
| `SET_MODE(SPEAKER)` | connects A2DP to the SPP peer, sends `MODE 1` once connected |
| `SET_MODE(PRIVATE)` | disconnects A2DP, sends `MODE 0` |
| No `HEARTBEAT` within the timeout in SPEAKER | PRIVATE on its own (app killed or crashed) |
| SPP link closes | PRIVATE |
| A call starts (app side, `CallGuard`) | app sends `SET_MODE(PRIVATE)`; the board drops A2DP so ringtone and call stay on the phone; `SET_MODE(SPEAKER)` again when it ends |

## 5. App

- `CompanionLink`: transport-neutral interface (info, stats, mode, heartbeat, event stream).
  `CompanionUsb` (RP2040) and `CompanionBt` (RFCOMM socket) implement it.
- `CompanionHub` owns one link at a time: USB when a board is plugged in, otherwise the
  Bluetooth board chosen on the AriaCompanion screen (bonded devices named `AriaCompanion*`).
  It keeps the RFCOMM link open while the app runs and retries with back-off.
- `CompanionSource` decodes `SBC_AUDIO` with libsbc (`app/src/main/cpp/sbc`, JNI
  `SbcDecoder`), resamples 44.1 → 48 kHz when needed, and continues as for USB PCM.
- Permissions: `BLUETOOTH_CONNECT` (runtime, Android 12+). Pairing happens in Android's
  Bluetooth settings; the app links there.
- Firmware updates of the ESP32 are not done by the app: flash it from a computer (§6).

## 6. Firmware

`AriaCompanion/firmware-bt/` is an ESP-IDF 5.5 project.

```sh
. $IDF_PATH/export.sh
idf.py -C AriaCompanion/firmware-bt set-target esp32
idf.py -C AriaCompanion/firmware-bt build flash monitor
```

Status LED (GPIO 2 on most WROOM dev boards): slow blink PRIVATE without app, double blink
PRIVATE with the app connected, solid SPEAKER, fast blink while frames are being dropped.
