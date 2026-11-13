# AriaCompanion 2: Implementation Spec

Status: **draft**, last updated 2026-11-13.
Goals and the reasons behind the design are in [ROADMAP-2.0.md](ROADMAP-2.0.md#ariacompanion-2-usb-loopback-on-one-rp2040). This document covers **how** to build it: USB descriptors, the private USB protocol, the firmware, and the app modules.

## 1. Overview

```
            ┌──────────────────────── RP2040 ────────────────────────┐
Android ──▶ │ UAC2 speaker (iso OUT, adaptive) ──▶ ring buffer 64 KB │
 mixer      │                                         │              │
            │ vendor interface ◀──── chunk encoder ◀──┘              │
            │   EP0 vendor requests (commands)                       │
            │   bulk IN (audio + events)  ─────────────────────────────▶ AriaCast app
            └────────────────────────────────────────────────────────┘
```

- **The board never plays, decodes or resamples audio.** It copies isochronous OUT data into a ring buffer and sends it back out on bulk IN, with headers.
- **The board has two modes:**
  - **SPEAKER:** the UAC2 speaker and the vendor interface are both present.
  - **PRIVATE:** only the vendor interface is present. Android sees no audio device, so all audio stays on the phone.
- **Safe default: the board starts in PRIVATE mode.** It becomes a speaker only when the app asks, and returns to PRIVATE if the app stops sending heartbeats. If AriaCast isn't running, the board is never a speaker, so audio can't silently disappear into it.

## 2. USB identity

| Field | Value |
|---|---|
| VID / PID | `0x2E8A` / *assigned by Raspberry Pi's PID program (to request)*. Development builds use `0x1209` / `0x0001`, the pid.codes test PID. |
| Speed | Full-speed (12 Mbit/s), USB 2.0 |
| Device class | `0xEF / 0x02 / 0x01` (composite with an Interface Association Descriptor, required for UAC2) |
| `bcdDevice` | Firmware version as BCD `MMmm` |
| Strings | Manufacturer `AriaCast`, product `AriaCompanion`, serial = the RP2040 flash unique ID (`pico_get_unique_board_id_string`) |

The same VID/PID is used in both modes. Changing modes is a full USB disconnect and reconnect (§4.3), so the host always rereads the descriptors.

## 3. Descriptors

### 3.1 SPEAKER mode (configuration 1)

| Interface | Contents |
|---|---|
| IAD | Covers interfaces 0–1, function class Audio, UAC2 |
| **0: Audio Control** (UAC2) | Clock Source (ID 1): internal, fixed frequency, 48 000 Hz, read-only. Input Terminal (ID 2): USB streaming, 2 channels, clock 1. Output Terminal (ID 3): type `0x0301` *Speaker*, source 2. **No Feature Unit** (see §6.4). |
| **1: Audio Streaming** | Alt 0: zero bandwidth. Alt 1: PCM, 2 channels, 16-bit (subslot 2). Endpoint `0x01` **isochronous OUT, adaptive**, max packet 196 bytes (49 frames × 4 bytes, one frame of margin), interval 1 ms. **No feedback endpoint.** |
| **2: Vendor** | Class `0xFF`, subclass `0xAC`, protocol `0x01` (protocol version 1). Endpoint `0x82` **bulk IN**, 64 bytes. Commands use EP0 vendor requests (§4.2), so there's no bulk OUT. |

Why adaptive with no feedback endpoint: the board has no audio clock of its own. It accepts whatever the host sends each 1 ms frame, so asynchronous feedback would be meaningless.

**Planned for protocol v2:** an Alt 2 with 24-bit samples (subslot 4), and 44.1/96 kHz from a programmable clock source. These depend on the bulk throughput test (§8, M0). At 96 kHz/24-bit the return channel needs about 768 KB/s, close to the practical full-speed bulk limit on Android.

### 3.2 PRIVATE mode (configuration 1)

Only interface 0: the vendor interface (same class, subclass, protocol and endpoint `0x81` bulk IN). Device class `0x00`. In this mode the bulk IN endpoint only carries events (§4.1).

## 4. The vendor protocol (v1)

All multi-byte fields are little-endian. The shared header `firmware/include/aria_companion_proto.h` is the single source of truth. The Kotlin constants in `CompanionProtocol.kt` mirror it, and a unit test checks that they match.

### 4.1 Bulk IN stream

The bulk IN endpoint carries a **byte stream of chunks**. One USB transfer may contain several chunks, or part of one, so the app parses it as a stream.

```
ChunkHeader (8 bytes)
  u8   magic    = 0xAC
  u8   type
  u16  length   // payload bytes, excluding the header
  u32  seq      // increments by 1 per chunk, wraps around
```

| Type | Name | Payload |
|---|---|---|
| `0x01` | `AUDIO` | `u16 sof_frame` (USB frame number of the first sample, 0–2047), `u16 reserved`, then interleaved PCM in the current format |
| `0x02` | `FORMAT` | `u32 sample_rate`, `u8 bits`, `u8 subslot_bytes`, `u8 channels`, `u8 reserved`. Sent at stream start and whenever the format changes, always before the `AUDIO` chunks it describes. |
| `0x03` | `STREAM` | `u8 state`: 0 = the host closed the stream (alt 0), 1 = streaming |
| `0x04` | `OVERRUN` | `u32 dropped_bytes`: the ring buffer overflowed because the app read too slowly |
| `0x05` | `MODE` | `u8 mode`: 0 = PRIVATE, 1 = SPEAKER. Sent once after every enumeration. |

- **Batching:** the board sends one `AUDIO` chunk per 2 ms of audio (384 bytes at 48 kHz/16-bit). A short packet ends each USB transfer.
- **Resync:** if `magic` doesn't match, the app scans forward for the next `0xAC` whose `length` is plausible (≤ 4 KB) and logs a resync.
- **Gaps:** jumps in `sof_frame` that aren't explained by `seq` mean the host sent nothing. That's silence, and the app fills it with zeros. A jump in `seq` or an `OVERRUN` chunk means data was lost.

### 4.2 EP0 vendor requests

Requests are sent to interface 2 in SPEAKER mode and interface 0 in PRIVATE mode. `bmRequestType`: `0x41` (host → device) or `0xC1` (device → host).

| `bRequest` | Name | Direction | `wValue` | Data |
|---|---|---|---|---|
| `0x01` | `GET_INFO` | IN | 0 | `u8 proto_version, u8 mode, u16 fw_version, u16 caps_bitmap, u16 ring_kib` |
| `0x02` | `SET_MODE` | OUT | 0 = PRIVATE, 1 = SPEAKER | none. The board replies, then re-enumerates about 50 ms later. |
| `0x03` | `HEARTBEAT` | OUT | timeout in seconds (1–10, default 3) | none |
| `0x04` | `GET_STATS` | IN | 0 | `u32 iso_bytes, u32 bulk_bytes, u32 overruns, u32 resets, u16 peak_l, u16 peak_r` |
| `0x05` | `REBOOT_BOOTLOADER` | OUT | 0 | none. Calls `reset_usb_boot(0, 0)`, so the board comes back as the RP2040 boot ROM device (PICOBOOT). |
| `0x06` | `RESET_STATS` | OUT | 0 | none |

`caps_bitmap` bits: 0 = 24-bit support, 1 = 44.1 kHz, 2 = 96 kHz, 3 = has a status LED. v1 firmware reports `0x0008` or `0x0000`.

### 4.3 Mode rules

| Event | Board does |
|---|---|
| Power-on or reset | Enumerates in **PRIVATE** mode |
| `SET_MODE(SPEAKER)` | Disconnects (`tud_disconnect`), waits 100 ms, swaps descriptors, reconnects (`tud_connect`), then sends a `MODE` chunk |
| `SET_MODE(PRIVATE)` | Same, in the other direction |
| No `HEARTBEAT` within the timeout while in SPEAKER mode | Switches to PRIVATE on its own. Covers the app being killed or crashing. |
| USB bus reset or suspend | Keeps its current mode. Resets the heartbeat timer. |

The firmware stores nothing in flash. All state lives only until the next power cycle.

## 5. Firmware

### 5.1 Layout

```
AriaCompanion/
├── firmware/
│   ├── CMakeLists.txt          # Pico SDK 2.x, TinyUSB from the SDK
│   ├── include/
│   │   ├── aria_companion_proto.h   # §4, shared with the app (mirrored)
│   │   └── board_config.h           # LED pin and type per board
│   └── src/
│       ├── main.c              # init, main loop, watchdog
│       ├── tusb_config.h       # CFG_TUD_AUDIO=1, CFG_TUD_VENDOR=1
│       ├── usb_descriptors.c   # both modes, chosen by the current mode
│       ├── audio.c             # UAC2 class callbacks, clock source requests
│       ├── loopback.c          # ring buffer, chunk encoder, bulk IN pump
│       ├── control.c           # vendor requests, mode switch, heartbeat
│       └── status_led.c
├── legacy/                     # 1.x ESP32 firmware, frozen
│   ├── AriaCompanionBtSink/
│   └── AriaCompanionWifiBridge/
└── README.md
```

### 5.2 Main loop

The RP2040 runs on core 0 only; core 1 is unused in v1.
```
init: clocks (120 MHz), watchdog (500 ms), LED, tusb_init(), mode = PRIVATE
loop:
  tud_task()
  loopback_pump()     // ring buffer → chunks → tud_vendor_write while there's room
  control_tick()      // heartbeat timeout, pending mode switch
  led_tick()
  watchdog_update()
```

### 5.3 Audio path
- `tud_audio_rx_done_pre_read_cb` (TinyUSB UAC2): read the endpoint's FIFO into the 64 KB ring buffer, a power of two, about 340 ms at 48 kHz/16-bit. Record the `sof_frame` of the first sample.
- When the ring buffer is full, drop the **oldest** data and count it in `overruns`. The next chunk is preceded by an `OVERRUN` chunk.
- `tud_audio_set_itf_cb` / `tud_audio_set_itf_close_EP_cb`: alt 1 → send `FORMAT` and then `STREAM(1)`; alt 0 → send `STREAM(0)`.
- Answer clock source requests (`GET_CUR`/`GET_RANGE` for the sample rate, `GET_CUR` for clock validity) as TinyUSB's `uac2_speaker_fb` example does, but with a single fixed rate.
- Peak meter: the running maximum absolute sample per channel, reset when `GET_STATS` reads it.

### 5.4 Status LED
| Pattern | Meaning |
|---|---|
| Slow blink | PRIVATE mode, no heartbeat |
| Solid | SPEAKER mode, stream closed |
| Brightness follows peak level | SPEAKER mode, streaming |
| Fast blink | Overrun in the last second |

Boards: Raspberry Pi Pico or Pico 2 (GPIO 25 LED), Waveshare RP2040-Zero (WS2812 on GPIO 16). Selected with `-DARIA_BOARD=...`.

### 5.5 Build and flash
`cmake -B build -DPICO_BOARD=pico -DARIA_BOARD=pico && cmake --build build`, which produces `aria_companion.uf2`. CI builds both boards and attaches the UF2 files to releases.

## 6. App

The app modules plug into the 2.0 source/sink pipeline (roadmap, phase 1). Package: `com.aria.ariacast.companion`.

### 6.1 Modules

| File | Role |
|---|---|
| `CompanionProtocol.kt` | Constants and data classes mirroring `aria_companion_proto.h` |
| `ChunkParser.kt` | **Pure function:** bytes → `Format`, `Pcm(seq, sofFrame, samples)`, `StreamState`, `Overrun`, `Mode` events. Resync logic. No Android dependencies, so it can be unit-tested. |
| `CompanionUsb.kt` | Finds the device (VID/PID **and** an interface with class `0xFF`, subclass `0xAC`, protocol `0x01`), requests permission, opens it, claims the vendor interface (`force = false`), sends vendor requests with `controlTransfer`, and runs a reader thread that keeps **4 × 16 KB `UsbRequest`s** queued on bulk IN. |
| `CompanionController.kt` | State machine: `Absent → Attached(PRIVATE) → SwitchingToSpeaker → Speaker → SwitchingToPrivate …`. Sends a heartbeat every second (timeout 3 s). Re-finds the device after every re-enumeration. |
| `CompanionSource.kt` | The pipeline's `AudioSource`. PCM from the parser goes into a **delay line** (100 ms), then the **gate**, then the router. Fills `sof_frame` gaps with zeros. |
| `MediaOnlyGate.kt` | Decides whether audio may pass (§6.2). The decision function is pure and unit-tested; the class wraps `AudioPlaybackCallback`. |
| `CallGuard.kt` | Watches call state (§6.3) |
| `LoopGuard.kt` | While the board is in SPEAKER mode, sends AriaCast's own `AudioTrack`s (receive mode, test tone) to the built-in speaker with `setPreferredDevice()` |
| `UsbAttachActivity.kt` | Invisible activity (`Theme.NoDisplay`) for `USB_DEVICE_ATTACHED`. It hands the device to the service and finishes. |

### 6.2 The gate

**Which players block**, by `AudioAttributes.usage`:
- **Pass:** `USAGE_MEDIA`, `USAGE_UNKNOWN` (many media apps leave the usage unset), `USAGE_GAME` (passes when the "Include games" setting is on, blocks when it's off).
- **Block:** everything else: notification, ringtone, alarm, voice communication, assistant, navigation guidance, accessibility, UI sonification.

A blocking player is **ignored** (doesn't close the gate) if any of these holds:
1. `audioManager.getStreamVolume(attrs.volumeControlStream) == 0`
2. Its usage is a notification or ringtone, and `ringerMode != RINGER_MODE_NORMAL`
3. `AudioPlaybackConfiguration.getAudioDeviceInfo()` is available (check the API level when implementing), is not null, and isn't the board

```kotlin
// MediaOnlyGate.kt: pure decision, unit-tested
fun shouldBlock(players: List<PlayerInfo>, env: AudioEnv, includeGames: Boolean): Boolean =
    players.any { p ->
        p.usage.isBlocking(includeGames) &&
        env.streamVolume(p.volumeStream) > 0 &&
        !(p.usage.isNotificationOrRing() && env.ringerMode != RINGER_MODE_NORMAL) &&
        (p.deviceId == null || p.deviceId == env.companionDeviceId)
    }
```

**Timing.** The delay line holds D = 100 ms. Audio leaving it at time *t* entered the app at *t* − D.
- **Close:** when `shouldBlock` becomes true at time T, fade out over 20 ms immediately. Because of the delay, this also covers notification audio that reached the board up to 100 ms before the callback arrived.
- **Open:** when `shouldBlock` becomes false at time T₂, stay closed until T₂ + D + 200 ms. D lets the delayed notification audio drain; the extra 200 ms covers sound tails. Then fade in over 20 ms.
- Re-check `shouldBlock` whenever the playback callback fires, the ringer mode changes (`RINGER_MODE_CHANGED_ACTION`), or a stream volume changes (`VOLUME_CHANGED_ACTION`).

### 6.3 Calls

- With `READ_PHONE_STATE` granted: listen with `TelephonyCallback.CallStateListener` (API 31+) or `PhoneStateListener` (API 29–30).
  - `RINGING` or `OFFHOOK` → `SET_MODE(PRIVATE)`
  - `IDLE` lasting 2 s → `SET_MODE(SPEAKER)`
- Without the permission: the gate still blocks ringtone and call audio, because those usages block. A one-time hint explains that granting the permission keeps calls on the phone.
- VoIP apps (`USAGE_VOICE_COMMUNICATION`) are handled by the gate. If the phone's `AudioManager.getMode()` reports `MODE_IN_COMMUNICATION` for more than 1 s, it is treated like `OFFHOOK`.

### 6.4 Volume (v1 decision)

There's no Feature Unit, so **Android scales the audio with the phone's media volume before it reaches the board.** In v1, the phone's media volume works as the master volume for the house: volume keys work normally while any media app plays, and the receivers' own volumes still apply on top. A hardware volume control (Feature Unit, with volume changes reported to the app) can come later, depending on how Android treats it (spike).

### 6.5 Manifest and permissions

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" /> <!-- optional, requested at runtime -->

<activity android:name=".companion.UsbAttachActivity"
          android:exported="true" android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
               android:resource="@xml/companion_device_filter" />
</activity>
```

- `companion_device_filter.xml` matches the release VID/PID and the development PID.
- When the user ticks "Always open AriaCast for this device," Android grants USB permission automatically on every attach, including after each mode switch.
- The service already declares the `connectedDevice` foreground service type.

### 6.6 AriaCompanion screen
- **Status:** absent / private / speaker / streaming; current format; live level meter from `GET_STATS` peaks; overrun and resync counters.
- **Gate indicator:** open, or closed with the reason, for example "closed: notification sound."
- **Settings:** include games; heartbeat timeout; "Start casting when plugged in" (default on).
- **Firmware:** installed version, with an update button in milestone M3.

### 6.7 Firmware updates in the app (M3)
1. Send `REBOOT_BOOTLOADER`.
2. The board comes back as the RP2040 boot ROM device (VID/PID `2E8A:0003`).
3. Using PICOBOOT over the USB host API: exclusive access, erase, write the UF2 blocks from the bundled firmware, then reboot.
4. Check the new `GET_INFO` version.

The firmware image is bundled in the APK as an asset and verified against a SHA-256 checksum. It's never downloaded at runtime.

## 7. Testing

| Level | What is tested |
|---|---|
| Firmware, host-side unit tests | Chunk encoder, ring buffer wraparound and overrun, mode state machine. Compiled for the host with a TinyUSB stub. |
| App unit tests | `ChunkParser` (split chunks, resync, gaps), `shouldBlock` (every usage × volume × ringer mode × device combination), gate timing with a fake clock, protocol constants matching the C header |
| Bench | Linux PC as the host: `aplay` a 1 kHz sine to the board, read bulk IN with a small Python `pyusb` script, check the result is bit-exact and has no gaps over 1 hour |
| On-device matrix | Samsung (One UI, current and previous), Pixel, one other OEM, one Android TV box. See the checklist below. |

**On-device checklist:**
- [ ] The vendor interface opens while Android's USB audio support is using the speaker interfaces
- [ ] Media goes to the board in SPEAKER mode and goes back to the phone in PRIVATE mode
- [ ] Killing the app → the board returns to PRIVATE mode within 3 s, and media is audible on the phone
- [ ] Incoming call: the ringtone and the call are on the phone; casting resumes 2 s after hang-up
- [ ] Notification at volume 0 during music: no gap in the house audio
- [ ] Notification at normal volume during music: the house hears a short fade, never the notification sound
- [ ] Alarm during music: plays on the phone, the house is gated
- [ ] Ring mode set to vibrate: notifications don't close the gate
- [ ] One hour of streaming: no overruns, no resyncs
- [ ] Unplug and replug with "always open" ticked: casting resumes with no prompts
- [ ] Samsung "Separate app sound": does it list the board? (optional route)

## 8. Milestones

| # | Milestone | Done when |
|---|---|---|
| **M0** | **Spike.** Minimal firmware (SPEAKER mode only, `AUDIO` chunks only). A debug screen plays the captured audio back on the phone's built-in speaker (pinned with `setPreferredDevice`) and shows the level and bulk throughput. | The first three checklist items pass on Samsung and Pixel, and bulk throughput is measured |
| M1 | Firmware complete: both modes, heartbeat, every chunk type and vendor request, LED, CI builds of the UF2 files | Bench test passes |
| M2 | App integration after roadmap phase 1: `CompanionSource`, gate, `CallGuard`, `LoopGuard`, attach handling | Full on-device checklist passes |
| M3 | AriaCompanion screen, in-app firmware updates, 1.x firmware moved to `legacy/`, README rewrite | Released with 2.0 |

M0 and M1 don't depend on the app rewrite and can start now.

## 9. Open questions
- Does Android ever send call audio to a speaker-only USB output while the board is in SPEAKER mode during the short window before `CallGuard` reacts? The gate covers the house in that case, but the user might miss the first ring on the phone.
- Do any OEMs refuse to let an app claim one interface of a USB device whose audio interfaces Android is already using? The M0 spike answers this.
- Is a 64 KB ring buffer enough when Android delays reads under heavy load (screen off, Doze)? Measure in M2, and keep a wake lock while streaming as 1.1.8 does.
