# AriaCast 2.0 Roadmap

Status: **draft**, last updated 2026-11-13.
Nothing here is committed until it lands on the `v2` branch. Items marked **spike** need a short feasibility test before we promise them.

## Status (2026-11-13, branch `v2`)

Everything below builds (both flavors, debug and release with R8) and passes the unit tests,
but **nothing has run on a real device or emulator yet**. See "Not verified" at the end of
this section before releasing.

| Area | Status | Notes |
|---|---|---|
| Audio sources (apps incl. games/untagged, mic/line-in, AriaCompanion, received audio) | Done | `pipeline/` |
| Receiver merged (AriaCast protocol), bridge mode, Receive screen, start at boot | Done | `receiver/` |
| DLNA/UPnP renderer, audio and video | Done | No GENA events sent; controllers poll |
| Sendspin client: player@v1, source@v1, metadata@v1 | Done | Pairing PSK method only (no CPace code pairing); PCM only |
| Video casting to Cast, DLNA, AirPlay | Done | AirPlay `/play` fails on Apple TVs that require pairing |
| Compose UI, onboarding, Now Playing, lyrics, connection doctor, widget, TV launcher | Done | |
| Built-in Music Assistant picker, doctor, visualizer, add by address | Done | |
| Plugin API v2 (`ui.render`, `ui.onEvent`) | Done | v1 view methods log a warning |
| Room handoff (add/remove/move speakers while casting) | Done | |
| Automation intents, web remote (React + Vite), custom Cast receiver page | Done | Custom receiver needs the Google Cast Console registration |
| AriaCompanion 2: firmware, app side, in-app firmware update | Done | Firmware built with Pico SDK 2.1.1; host tests pass |
| F-Droid flavor, CI, minSdk 29, version 2.0.0 | Done | |
| Service split into sinks | Partial | Sources extracted, one job per destination; protocol code still in `AudioCastService` |
| One notification for both roles | Partial | Sending and receiving are two services, two notifications |
| Settings, Plugins, Protocols, Packet log, NFC, AirPlay PINs screens | Partial | Still XML; the accent color picker only affects these |
| Plugin catalog with signature checks | Not done | The existing GitHub sync with re-consent on change remains |
| AirPlay receiver (UxPlay via NDK) | Not done | Large native integration |
| Cast mirroring (libcast), AirPlay mirroring | Not done | |
| Sleep timer with fades, per-speaker EQ/loudness, saved per-speaker delay | Done | `MasterGain`, `SpeakerDsp`. Sendspin speakers aren't shaped (the server pulls the stream) |
| Per-app routing | Not done | Spike |
| Quirk profiles | Partial | `DeviceQuirks`: notes in the doctor for WiiM (DLNA) and shairport-sync (AirPlay 2); more entries come from "Copy report" |
| Home Assistant `media_player`, auto-cast on speaker appearance | Not done | |
| Ktor 3 | Not done | Client and server on 2.3.8 |
| Retire the standalone receiver repo | Not done | Needs a final release there pointing to AriaCast |

**Not verified (do before releasing):**
- AriaCompanion 2 on hardware: the M0 checklist in [ariacompanion-2.md](ariacompanion-2.md), Samsung first.
- Sendspin against a real Music Assistant 2.10+ (pairing token entry, Live Input, synchronized playback).
- DLNA renderer with common controllers (BubbleUPnP, Kodi, Windows "Cast to device").
- Compose screens on a phone, a tablet and Android TV; the widget; the release build at runtime.

## What 2.0 is

AriaCast 1.x is a sender: it captures your phone's audio and casts it to speakers.
AriaCast 2.0 becomes **one app that sends, receives, or does both**, on phones, tablets and Android TV, and adds **video**.

The headline features:

1. **Send and receive in one app.** The [AriaCast Receiver for Android](https://github.com/AriaCast/AriaCast-Receiver-for-Android) merges into this app. Any device can be a speaker, a sender, or a bridge between the two.
2. **Bridge mode.** Audio a device receives (for example, AirPlay from an iPhone) can be forwarded to any other speakers (Chromecast, Sendspin, DLNA). An Android TV box becomes an AirPlay-to-everything hub.
3. **Sendspin**, Music Assistant's protocol, in both directions.
4. **Video casting** to Cast, AirPlay and DLNA, with screen mirroring as an experimental option.
5. **A new interface** in Jetpack Compose with Material 3 Expressive, including an Android TV layout.
6. **Plugins built into the app,** plus a new plugin API.
7. **AriaCompanion 2:** a single RP2040 board on the phone's USB port that sends back the audio of apps that block capture, so they can be cast to any speaker.

## Why these features: what users are asking for

Demand for the app itself is modest (52 stars, 11 issues, all closed), so we also looked at the Music Assistant community, where most AriaCast users come from.

| Signal | Where | What it tells us |
|---|---|---|
| "Phone audio as source": **98 upvotes** | [Music Assistant #384](https://github.com/orgs/music-assistant/discussions/384) | The largest demand we found. Music Assistant 2.10 answered it with a built-in [Sendspin Source](https://www.music-assistant.io/plugins/sendspin-source/) plugin. If we don't support Sendspin, an Android Sendspin source app could replace our separate Music Assistant receiver plugin. |
| "No audio" reports | [#4](https://github.com/AriaCast/AriaCast-app/issues/4) (19 comments), [#7](https://github.com/AriaCast/AriaCast-app/issues/7), Music Assistant #384 | Users can't tell whether DRM, their setup or AriaCast is the cause. |
| One-tap casting | [#32](https://github.com/AriaCast/AriaCast-app/issues/32) (deep links, done), NFC (done), [Discussion #11](https://github.com/AriaCast/AriaCast-app/discussions/11) (widget, not done) | People want to cast without opening the app. Competitor AirMusic advertises Tasker/MacroDroid support and auto-start. |
| Receiver quirks | [#48](https://github.com/AriaCast/AriaCast-app/issues/48) (Sonos AirPlay), [#50](https://github.com/AriaCast/AriaCast-app/issues/50) (WiiM DLNA) | Each brand behaves differently; we need a cheaper way to diagnose them. |
| F-Droid | [#10](https://github.com/AriaCast/AriaCast-app/issues/10) | Requested; our inclusion request is still pending. |
| Turntable and line-in casting | Music Assistant #384 | The existing app for this (Vinyl Cast) is described as buggy. |

## Architecture

### One pipeline: sources, router, sinks

Today, [AudioCastService.kt](../app/src/main/java/com/aria/ariacast/AudioCastService.kt) (about 2,250 lines) contains every protocol. 2.0 splits it into a pipeline:

```
 Sources                         Router                      Sinks
 ─────────                       ──────                      ─────
 System audio (MediaProjection) ┐                       ┌──▶ This device's speaker
 Mic / USB line-in              ├──▶ pick source ─▶ ────┼──▶ AirPlay · Cast · DLNA
 Received: AriaCast/AirPlay/    ┘   (+ EQ, volume)      └──▶ Sendspin · Snapcast · AriaCast
           DLNA/Sendspin
```

- **Sources** produce audio: screen-capture audio, the microphone or a USB input, or a stream the device is receiving.
- **Sinks** play it: the local speaker or any remote protocol. Each protocol implements one shared `Sink` interface.
- **The router** connects a source to one or more sinks. It applies per-sink volume, delay offset and EQ.

Sending, receiving, bridging and multi-room are all just different router setups, so new protocols fit in without touching the rest.

### One service

- A single foreground service. Its service types (`mediaProjection`, `mediaPlayback`, `connectedDevice`) are set at runtime to match the active roles. The `mediaProjection` type is only claimed after the user agrees to screen capture.
- One shared JmDNS instance both **advertises** the receiver and **discovers** speakers. The device hides its own receiver from its speaker list, using a device ID in the mDNS record.
- **Loop prevention:** the local speaker's audio is marked as not capturable (`AudioAttributes.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`). A device that receives and sends at once never captures its own output.
- The Ktor server moves from the Netty engine to the lighter CIO engine. Ktor goes from 2.3.8 to 3.x, using the same version for client and server.
- **No port conflicts.** The receiver uses UDP 12888 (discovery) and TCP 12889 (streaming). The sender's discovery only opens temporary sockets.

## Features

### Sending

- **Sendspin source:** stream into Music Assistant's "Live Inputs." The source role only works on paired connections, and Music Assistant won't switch to a playing source automatically until the spec adds authentication and encryption.
- **Sendspin player:** cast to Sendspin speakers (ESPHome devices, Home Assistant Voice PE, Apollo CAST-1).
- **Mixed multi-room groups:** groups (already in [CastGroup.kt](../app/src/main/java/com/aria/ariacast/CastGroup.kt)) can mix protocols, with per-speaker volume and delay, and can be saved as **scenes** (for example, "Evening = kitchen + living room").
- **Room handoff:** move what's playing to another room without stopping.
- **Wider capture:** today only `USAGE_MEDIA` audio is captured ([AudioCastService.kt:676](../app/src/main/java/com/aria/ariacast/AudioCastService.kt#L676)). Add `USAGE_GAME` and `USAGE_UNKNOWN`, so games and untagged apps can be cast.
- **Mic and line-in mode:** intercom-style announcements, or a turntable through a USB audio interface.
- **Per-app routing (spike):** choose which apps are captured, possibly sending different apps to different rooms. Android supports capture filtered by app, but we haven't tested whether several captures can run at once.
- **Sound tools:** per-speaker EQ, loudness leveling, and a delay offset for keeping speakers in sync with video.
- **Sleep timer with fade-out,** and a fade-in when casting starts.

### Receiving

| Protocol | Plan | Notes |
|---|---|---|
| AriaCast | 2.0 | Port of the existing receiver |
| AirPlay audio | 2.0 | UxPlay core (GPLv3) through JNI, as in [android-airplay-server](https://github.com/jqssun/android-airplay-server) |
| AirPlay video and mirroring | 2.0 on TV, if the AirPlay audio work goes well | Same UxPlay core |
| DLNA/UPnP renderer (audio and video) | 2.0 | Plays through Media3/ExoPlayer |
| Sendspin player, Snapcast client | 2.0 | Open specs |
| Google Cast | **Not possible** | Receivers need Google's device certification |

### Video

- **Media casting (2.0):** share a video file or link to AriaCast, and the TV plays it directly.
  - **Cast:** the Default Media Receiver plays MP4 and HLS, with subtitles, seeking and a queue. [CastClient.kt](../app/src/main/java/com/aria/ariacast/cast/CastClient.kt) already speaks this protocol.
  - **AirPlay:** Apple TV accepts a video link over the `/play` endpoint after pairing, reusing our AirPlay 2 pairing code. Third-party "AirPlay TVs" (LG, Samsung) are stricter about the video format and need testing.
  - **DLNA:** the same `SetAVTransportURI` we already send for audio, with a video type.
  - **New code:** a small HTTP server on the phone that serves local files with seek support, and a player screen with scrubbing and subtitles.
- **Cast screen mirroring (experimental, spike):** Google's open-source [Open Screen library](https://github.com/chromium/openscreen/blob/main/cast/README.md) includes a reference sender. We would capture the screen into a virtual display, encode with the hardware `MediaCodec`, and build libcast with the Android NDK. We haven't confirmed that consumer Chromecasts accept mirroring from third-party senders.
- **AirPlay screen mirroring (research spike, 2.x at the earliest):** we found no open-source AirPlay mirroring *sender*. Mirroring depends on Apple's FairPlay handshake on the sender side, and it's unclear whether HomeKit pairing avoids it. If it works, it would set AriaCast apart; it may not be possible.
- **DRM applies to video too:** Netflix, Disney+ and similar apps will mirror as a black screen.

### AriaCompanion 2: USB loopback on one RP2040

**Problem.** Some apps' audio can't be captured through `MediaProjection`. Some apps opt out of capture. On Samsung phones, sideloaded apps also get a flag (`FLAG_NO_MEDIA_PROJECTION`) that blocks it; see [AriaCompanion/README.md](../AriaCompanion/README.md).

**How it works today (1.x).** Two ESP32 boards: the phone plays to one over Bluetooth, which passes the audio over I2S to the second, which sends it over Wi-Fi. The phone is not in the audio path, so this has drawbacks:
- It only works with AriaCast receivers.
- Bluetooth compression makes it lossy, and the audio is resampled.
- It needs Bluetooth pairing, Wi-Fi setup on the board, and two boards wired together.

**New design.** One RP2040 board in the phone's USB-C port acts as a **USB speaker**. Android sends media audio to it, as it would to any USB DAC. The firmware **sends that audio straight back** to the AriaCast app over a private USB channel, and the app streams it like any other source.

```
App audio ─▶ Android mixer ─▶ USB speaker (RP2040, UAC2 isochronous OUT)
                                    │ ring buffer, no processing
AriaCast app ◀── vendor bulk IN ◀───┘   (only the app can read this)
     │
     └─▶ router ─▶ any sink: AirPlay · Cast · DLNA · Sendspin · multi-room
```

- **The return channel is a private vendor USB interface, not a USB microphone.** If it were a microphone, Android would offer it to every app, including phone calls and voice assistants. Instead, the app reads it through Android's USB host API (`UsbManager` and bulk transfers). Other apps can't see it, and the app doesn't need the `RECORD_AUDIO` permission for it.
- **No screen-capture prompt.** The audio arrives over USB, so `MediaProjection` isn't involved. A `USB_DEVICE_ATTACHED` filter means plugging the board in can open AriaCast and start casting with no taps, which covers the one-tap requests (#32, Discussion #11).
- **No clock drift inside the board.** Audio enters and leaves on the same USB connection, timed by the same 1 ms USB frames. The board only forwards bytes; there's no DAC, no resampling and no clock recovery.
- **Lossless.** 48 kHz/16-bit stereo by default. The RP2040's full-speed USB (12 Mbit/s) also has room for 24-bit/96 kHz (576 bytes/ms each way).
- **Works on Android TV boxes with a USB port:** cast TV apps' audio to the house.
- **New pipeline source:** "USB loopback (AriaCompanion)." It feeds the same router as screen-capture audio, so groups, scenes, EQ and bridge mode all work with it.
- **Loop prevention:** while the board is attached, AriaCast's own playback (receive mode) is pinned to the phone's built-in speaker with `AudioTrack.setPreferredDevice()`. Otherwise it would go out over USB and come back in.

**Implementation spec:** [ariacompanion-2.md](ariacompanion-2.md) covers descriptors, the private USB protocol, firmware layout, app modules, tests and milestones. The board starts with only its private channel. It becomes a speaker only while the app is running and sending a heartbeat, so audio can never disappear into it when AriaCast isn't listening.

**Firmware.** Pico SDK with TinyUSB, starting from TinyUSB's UAC2 speaker and headset examples. The board presents two interfaces: UAC2 speaker (interfaces 0–1) and a vendor interface with bulk IN (interface 2). Each bulk packet carries a sequence number and a sample count, so the app can detect dropped audio. The same firmware also runs on the RP2350 (Pico 2), which has the same full-speed USB.

**Hardware.** Any RP2040 board with USB-C (for example an RP2040-Zero), plus a USB-C OTG cable or adapter. No wiring and no Bluetooth.

**Media only (decided).** Only multimedia audio may reach the house speakers. Calls, ringtones, alarms, notifications, navigation and voice-assistant audio stay on the phone or are silenced; they are never cast.

Android mixes every sound headed for a USB output into a single stream before it reaches the board. Neither the firmware nor the app can pull that mix apart, and normal apps can't change Android's routing rules (that needs the system-only `MODIFY_AUDIO_ROUTING` permission). So "media only" is enforced in three layers:

1. **A gate in the app.** `AudioManager.getActivePlaybackConfigurations()` and `AudioPlaybackCallback` (API 26, no permission needed) report every active player and its usage.
   - The USB loopback source forwards audio only while every active player is `USAGE_MEDIA` (or `USAGE_GAME`, if the user allows games).
   - When anything else starts (notification, alarm, ringtone, assistant, navigation), the forwarded audio fades to silence, and fades back when it ends.
   - A short delay buffer (about 100 ms) in the USB source lets the gate close *before* that sound reaches the speakers. The house never hears the start of a notification.
   - **Only audible sounds close the gate.** A non-media player is ignored if:
     - its stream volume is 0. Look up the stream from the player's usage with `AudioAttributes.getVolumeControlStream()`, then read `AudioManager.getStreamVolume()`.
     - it's a notification or ringtone sound while the ringer mode is silent or vibrate.
     - where Android reports the player's output device, that device isn't the board.

     Standard notifications at volume 0, in silent mode or in Do Not Disturb never start a player at all, because the system checks the stream volume before playing the sound. The volume check catches apps that play notification-type sounds themselves.
   - The trade-off: music also goes quiet for the length of a notification sound.
2. **Calls never touch the board.** When the phone starts ringing, before the call is answered, the app tells the board to re-enumerate as a private-channel-only device. With no USB speaker present, Android puts the ringtone and the call on the phone. After the call, the board comes back as a speaker.
   - The private channel exists in both modes, so the app never loses contact with the board.
   - If the user ticked "always open with AriaCast," no new USB permission prompt appears.
   - Detecting ringing needs the `READ_PHONE_STATE` permission. Without it, the gate in layer 1 still mutes the ringtone and call audio, but they may play on the board instead of the phone.
3. **Where non-media sounds end up.** Android's usual rules play ringtones and alarms on the phone speaker *and* the USB output, so the user still hears them on the phone; the gate silences only the USB copy. Notifications during media playback usually go only to the USB output, so they're silent while AriaCompanion is casting. Those rules vary by OEM and Android version, which is why the spike tests them.

**Optional, to test:**
- **Samsung's "Separate app sound"** sends chosen apps to a chosen output device. If it lists USB devices, Samsung users can send only their music apps to the board and keep everything else on the phone. Samsung's documentation only mentions Bluetooth and wired headsets.
- **Advanced mode through Shizuku,** which grants apps ADB-level permissions: Android's routing call `setPreferredDeviceForStrategy` could send only the media strategy to the board, with no gating needed. Unverified, because it depends on whether the ADB shell has `MODIFY_AUDIO_ROUTING`.

**App.** The AriaCompanion screen ([AriaCompanionActivity.kt](../app/src/main/java/com/aria/ariacast/AriaCompanionActivity.kt), which sets up the Wi-Fi bridge today) becomes a status page: detected or not, firmware version, signal level, dropped packets. Later: **in-app firmware updates.** The app reboots the board into its bootloader with a vendor command and flashes it over PICOBOOT, the protocol `picotool` uses, through the same USB host API.

**To test before committing (spike):**
- Android claims the UAC2 interfaces for itself. The app must be able to open the vendor interface on the same device at the same time. Test on several Android versions and OEMs, Samsung first.
- The media-only rules below behave as described on real phones (Samsung, Pixel and one other OEM).
- Volume: in v1, the phone's media volume is the master volume for the house (no USB hardware volume control). Check whether a hardware volume control on the board is worth adding later.
- Power: the phone's only USB-C port is taken, so it can't charge during long sessions. A USB-C hub with PD passthrough works; a custom board with passthrough is a later option.
- USB IDs: request a product ID from Raspberry Pi's [free PID program](https://github.com/raspberrypi/usb-pid) for RP2040 projects, so the app's device filter matches only AriaCompanion.

**Positioning.** This routes audio to a USB speaker, exactly as a USB DAC does, and the audio goes only to the user's own speakers. Describe it as "cast any app," not as removing DRM. Don't add recording or saving features to this path.

**Retiring the 1.x design:** the two-ESP32 firmware (`AriaCompanionBtSink/`, `AriaCompanionWifiBridge/`) moves to `AriaCompanion/legacy/` with a note, and stops getting fixes once 2.0 ships.

### Built-in features (replacing plugins)

| Plugin in [AriaCast-android-plugins](https://github.com/AriaCast/AriaCast-android-plugins) | Becomes |
|---|---|
| `example_MA` (Music Assistant control) | Native Music Assistant integration: sign in once, pick a Music Assistant player as the target, control the queue |
| `health_check` | **Connection doctor:** a step-by-step check (network, capture, receiver, audio flowing) that also detects when an app blocks capture, by comparing a playing media session with silent captured audio |
| `visualizer` | Visualizer styles on the Now Playing screen |
| `manual_server` | Built in, with QR-code pairing for cross-VLAN setups |

### Plugin API v2 (breaking change)

- **Plugins describe their UI as data instead of inflating XML layouts.** A plugin returns a small UI tree (text, button, slider, list), and Compose draws it. The current `ui.inflate()` and `ui.findView()` depend on the XML layouts that 2.0 removes, so existing plugins must be updated.
- **An in-app plugin catalog** that installs from the plugins repo, with signature checks.
- **New hooks:** metadata, audio levels and receiver events.
- The sandbox rules in [ADR 0001](adr/0001-plugin-sandbox-and-class-shutter.md) still apply. The class whitelist shrinks, because plugins no longer touch Android views.

### Automation and integrations

- **Home-screen widget** (Glance), with the status dots agreed in Discussion #11: green = connected, red = available but disconnected, none = offline.
- **Intent API for Tasker and MacroDroid.** "Auto-cast when speaker X appears." "Start receiver when the device boots."
- **Home Assistant:** extend HA mode so AriaCast appears as a `media_player` entity.
- **Web remote:** the phone serves a small React + Vite control page, so any browser on the same network can switch rooms, change volume and see what's playing.
- **AriaCast on the TV screen:** a custom Cast receiver app. When we cast audio to a Chromecast TV, it shows artwork, the visualizer and lyrics instead of Google's plain default screen, and it also serves as a better video player. It needs a one-time $5 Cast developer registration and a hosted page (GitHub Pages works).
- **Synced lyrics** from [LRCLIB](https://lrclib.net) on the Now Playing screen and the TV.

## Interface

The main interface moves from XML Views to **Jetpack Compose with Material 3 Expressive**. We rejected a React/Vite interface for the app itself: it would run inside a WebView, adding startup time and memory while a foreground audio service is running, and every service call would cross a JavaScript↔Kotlin bridge. Widgets, Quick Settings tiles and notifications must be native either way. React + Vite is used only for the web remote.

### Home screen
- Two cards: **"Send from this device"** and **"Receive on this device,"** each with its own switch. "Both" means both switches are on. A **"Forward what I receive to…"** option turns on bridge mode.
- Speakers appear as cards grouped by room, each with a live status dot, a per-speaker volume slider, and a small protocol badge.

### Now Playing
- Large artwork, with the background colors taken from the album art (carried over from the receiver app), and the visualizer behind the controls.

### Receive screen
- Full-screen Now Playing while receiving.
- When idle: the device name, a clock, and a **"Scan to cast here" QR code** that opens an `ariacast://` link on another phone.
- Content shifts slightly over time to prevent burn-in on tablets and TVs left running.

### Setup and behavior by device
- An **onboarding wizard:** permissions and notification access, finding speakers, a test tone, then "you're casting."
- **The app opens on the most likely mode:** Send on phones, Receive on tablets and TVs.
- **Android TV:** the same APK, with a layout built on Compose for TV. It shows in the TV launcher with a banner, works with the remote's D-pad, and doesn't require a touchscreen. Receive is the main screen; sending the TV's own app audio is a secondary option.
- **A compact landscape layout** for Echo Show-type devices, which the receiver app was designed for.
- **One notification for both roles,** for example "Receiving from iPhone · Forwarding to Kitchen."
- Predictive back gestures and per-app language selection (for the Weblate translations).

## Quality and distribution

- **Per-device quirk profiles,** and a "report my device" export from the Packet Log.
- **An F-Droid build flavor** without the built-in self-updater ([UpdateManager.kt](../app/src/main/java/com/aria/ariacast/UpdateManager.kt)).
- **Retiring the standalone receiver app:**
  1. The receiver relicenses under GPLv3 to match this repo. It currently has no license file.
  2. Because its package is `com.example.ariacastreceiver`, its users can't upgrade in place. A final receiver release will point them to AriaCast.
  3. Then we archive the receiver repo.

## Phases

| # | Phase | Contents | Visible to users? |
|---|---|---|---|
| 1 | Foundation | Split the service into sources and sinks, port the AriaCast receiver as a source, single foreground service, Ktor 3 with CIO, Plugin API v2 | No |
| 2 | New interface | Compose redesign, Send/Receive home screen, Receive screen, Android TV layout, onboarding, built-in plugins, connection doctor | **Yes, this is "2.0"** |
| 3 | Headline features | Sendspin (both roles), mixed multi-room scenes, AirPlay and DLNA receivers, bridge mode, video media casting | Yes |
| 4 | Convenience | Custom Cast receiver page for TV, lyrics, widget, intent API, auto-cast, web remote, mic and line-in | Yes |
| 5 | Experimental | Cast mirroring, room handoff, Home Assistant entity | Yes, behind toggles |
| 6 | Can slip to 2.x | AirPlay mirroring spike, sound tools, per-app routing, sending from Android TV | — |

The receiver merge happens during phases 1 and 2, not before, so the receiver's XML screens are never ported and then rewritten.

**AriaCompanion 2 runs alongside the phases above:**
- **Firmware and the USB spike:** at any time; nothing else depends on them.
- **"USB loopback" source in the app:** phase 3, once the source/sink pipeline exists.
- **New AriaCompanion screen:** phase 2, with the redesign.
- **In-app firmware updates:** phase 5.

## Open decisions

| Decision | Recommendation | Why |
|---|---|---|
| Minimum Android version | **Android 10 (API 29)**, up from today's 12 for the sender | Old tablets and Echo Show-type devices are the best receivers. Audio capture needs API 29, and the sender doesn't appear to need anything from Android 12; features like dynamic colors can be limited to newer devices. Needs a build check. |
| Interface toolkit | **Jetpack Compose** (see Interface) | The TV layout and Plugin API v2 depend on it. |
| AirPlay receiver | **UxPlay core through the NDK** | Proven on Android, and its license (GPLv3) matches ours. It adds C code to the build. |
| AriaCompanion return channel | **Vendor bulk interface**, not a USB microphone | Only AriaCast can read it, calls and voice assistants never pick it up, and it needs no `RECORD_AUDIO`. It depends on the USB spike passing. |
| AriaCompanion firmware stack | **Pico SDK + TinyUSB (C)** | TinyUSB ships UAC2 examples, and PICOBOOT flashing is well documented. Rust (embassy-rp) has less mature USB audio support. |

## Sources

- Music Assistant: [#384 Phone audio as source](https://github.com/orgs/music-assistant/discussions/384), [#4804 Android "Cast" provider](https://github.com/orgs/music-assistant/discussions/4804), [#4518 Cast to Music Assistant?](https://github.com/orgs/music-assistant/discussions/4518), [Sendspin Source plugin](https://www.music-assistant.io/plugins/sendspin-source/)
- Sendspin: [spec](https://github.com/Sendspin/spec), [ESPHome component](https://esphome.io/components/sendspin/), [Matter Alpha: Sendspin 1.0](https://www.matteralpha.com/industry-news/sendspin-1-0-airplay-chromecast-sonos), [CNX Software: DIY Sendspin receiver](https://www.cnx-software.com/2026/04/21/diy-sendspin-audio-receiver-supports-multi-room-audio-synchronization-integrates-with-home-assistant/)
- Cast mirroring: [Open Screen libcast](https://github.com/chromium/openscreen/blob/main/cast/README.md), [libcast architecture](https://chromium.googlesource.com/openscreen/+/HEAD/cast/docs/architecture.md), [unofficial H.264 sender fork](https://github.com/gianlucamazza/openscreen)
- AirPlay receivers: [android-airplay-server (UxPlay)](https://github.com/jqssun/android-airplay-server), [PhairPlay](https://github.com/aliozancil/PhairPlay), [airplay-tv](https://github.com/besliky/airplay-tv)
- AirPlay video: [pyatv `/play` issue](https://lightrun.com/answers/postlund-pyatv-airplay-play_url-play-404-response), [Connect SDK AirPlayService](https://connectsdk.readthedocs.io/en/latest/apis-and/and-airplayservice.html), [HLS over AirPlay on LG/Samsung](https://community.bitmovin.com/t/ios-airplay-lg-samsung-tv-playing-m3u8-videos-via-airplay-is-not-supported-and-returns-error-code-2101-12927/3123), [WWDC19: HLS for AirPlay 2](https://developer.apple.com/videos/play/wwdc19/507/)
- AriaCompanion 2: [TinyUSB](https://github.com/hathach/tinyusb) (see `examples/device/uac2_headset`), [Raspberry Pi USB PID program](https://github.com/raspberrypi/usb-pid), [Android USB host API](https://developer.android.com/develop/connectivity/usb/host), [Samsung: Separate app sound](https://www.samsung.com/uk/support/mobile-devices/useful-audio-settings-on-the-galaxy-phone-including-separate-app-sound/)
- Competitor: [AirMusic on AlternativeTo](https://www.alternativeto.net/software/airmusic/about/)
