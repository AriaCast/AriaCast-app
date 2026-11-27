# Changelog

All notable changes to AriaCast are documented here. Releases prior to 1.1.8
are available as [GitHub Releases](https://github.com/AriaCast/AriaCast-app/releases).

## [2.0.0]

AriaCast 2.0 sends **and** receives: any Android phone, tablet or TV can cast to speakers,
be a speaker, or bridge between protocols. It also adds Sendspin (Music Assistant), video
casting, a new interface, and AriaCompanion 2.

### Send and receive in one app
- The AriaCast Receiver app is now part of AriaCast. Turn on **Receive on this device** and
  other devices see it as a speaker. It plays received audio locally, can **forward** it to
  other speakers (bridge mode, no screen-capture prompt), and can start at boot.
- **DLNA/UPnP renderer**: any DLNA app can play music or video on this device; videos play
  full screen on the Receive screen (e.g. on Android TV).
- **Sendspin (Music Assistant)**: Music Assistant can play on this device in sync with its
  other speakers, show what's playing, and use this device's audio as a **Live Input**.
  Pairing uses a token/QR shown in the app.
- Full-screen **Receive screen** for tablets and TVs: artwork colors, clock, "scan to cast
  here" QR code, burn-in protection.
- **Android TV**: listed in the TV launcher, works with the remote.

### Casting
- **Audio sources**: other apps (now including games and apps that don't tag their audio),
  microphone or USB line-in, AriaCompanion, or audio this device is receiving.
- **Room handoff**: add, remove or move speakers while casting, without a new screen-capture prompt.
- **Video casting** to Google Cast, DLNA and AirPlay: choose or share a video file or link,
  with subtitles and seeking.
- **Custom Google Cast receiver** (optional): artwork and song details on Chromecast TVs.

### New interface (Jetpack Compose)
- Send and Receive cards, source picker, speaker list with status dots, groups, multiroom
  delay sliders, add speaker by address.
- **Now Playing** with controls, volume, visualizer and optional synced lyrics (LRCLIB).
- **Connection doctor**: checks network, speakers, capture and data flow step by step, and
  recognizes apps that block audio capture.
- **Onboarding**, Material You colors on Android 12+, home-screen **widget**.

### Sound and timing
- **Sleep timer** on Now Playing (15 to 90 minutes). The sound fades out over the last 30
  seconds, then the cast stops. Every cast also starts with a one-second fade-in.
- **Sound per speaker**: bass, treble and level (-12 to +12 dB) and an optional loudness
  leveler, set for each speaker while casting and remembered for next time. Works with
  AriaCast, AirPlay, Cast, DLNA, Snapcast and VBAN speakers.
- **Rooms stay lined up**: each speaker's sync delay is now saved and applied the next time
  that speaker joins a cast.
- **Known speakers**: the list of speakers known to work, to work with a catch, to fail, or that
  AriaCast cannot reach is now a public file, [data/speakers.json](data/speakers.json), with
  [docs/SPEAKERS.md](docs/SPEAKERS.md) generated from it (a test fails if they drift apart). The app
  ships a copy, reads the file from GitHub once a day to stay current (Settings → Known speakers,
  where it can be switched off, updated by hand and browsed), and only takes a newer, valid list.
  The file can also carry settings for a speaker that needs something different from the usual:
  whether DLNA gets a Stop first or a Play after each track, the stream's content type, a starting
  delay, and whether titles and artwork are sent. Those five are the only things it can change, each
  within fixed limits. The connection doctor shows a note, in your language where the list has one,
  while you cast to a speaker with a documented quirk. DLNA speakers now report manufacturer and model.
- **More speaker logging**: every line logged while casting to a speaker is now attributed to it,
  and each speaker keeps its own history, so a chatty one can't push another's out. New lines:
  session start and end (with the speaker's model and why it ended); for DLNA the HTTP status and
  timing of every Stop, SetAVTransportURI, Play and SetVolume, the UPnP error when a renderer
  refuses, what the renderer does with the stream (its user-agent, how long it listened), and
  its transport state (`PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`) a moment after Play and on every
  change; for Google Cast every player state change and why it went idle; for AriaCast the
  receiver's handshake, how long a connection lasted and each reconnect, and the receiver's own
  underrun and overrun counts. DLNA speakers are asked their state every 10 seconds while you
  cast to them, which is one small request.
- **Report speaker**: long-press a speaker (or use the doctor) to share a report with who the
  speaker is, what is known about it, the cast state and its log lines, with addresses shortened.
  Works for speakers you never managed to connect to.

### Built in, previously plugins
- Music Assistant player picker, connection doctor, visualizer, add speaker by address.
- **Plugin API v2**: plugins describe their UI with `ui.render()`; the v1 view methods
  (`ui.inflate`, `ui.findView`, `ui.add`) no longer draw anything.

### AriaCompanion 2
- One RP2040 board on the USB-C port (Raspberry Pi Pico or RP2040-Zero) replaces the two
  ESP32 boards: the phone plays to it as a USB speaker and it sends the audio straight back
  to AriaCast over a private USB channel. Works with every speaker type, lossless, no
  screen-capture prompt. Only media is forwarded: calls stay on the phone, notifications
  and alarms are held back. Firmware updates from the app. The ESP32 version is in
  `AriaCompanion/legacy/`.

### Also
- **Web remote**: control AriaCast from any browser on the network (private link).
- **Automation**: `com.aria.ariacast.action.CAST`, `STOP`, `RECEIVER_START`,
  `RECEIVER_STOP` for Tasker/MacroDroid (off by default).
- **F-Droid build flavor** without the in-app update check.
- Minimum Android version lowered to 10.

## [1.1.9_hotfix]

### AirPlay 1 (RAOP) - rewritten
- Fixed: encrypted streams never worked - the AirPort RSA public key was wrong, and the AES tail block was padded instead of sent in the clear.
- Fixed: audio was announced as `L16` while ALAC frames were sent, so receivers played noise; the codec now follows the receiver's `cn` (ALAC, or real L16 PCM).
- Fixed: the `et`/`cn` TXT lists (e.g. `0,3,5`) were misread; full TXT records now reach the client.
- Fixed: metadata lacked the `mlit` container and `RTP-Info`; text, artwork and progress are now delivered according to the receiver's `md`.
- New: RTP is paced on a single monotonic clock with sync packets every second, a jitter buffer with inaudible drift correction, and retransmission of lost packets.
- New: password-protected receivers (Digest auth), Apple TV on-screen-code pairing, MFi `auth-setup`, `Apple-Challenge` verification, keep-alives with automatic reconnect, FLUSH on pause, mute, and a proper TEARDOWN.

### AirPlay 2 - rewritten
- Fixed: transient pairing omitted the transient flag, and nothing after pairing was encrypted; the RTSP control channel and the event channel now use HomeKit session encryption.
- Fixed: 48 kHz audio was sent as 44.1 kHz (slow, drifting playback); it's now resampled.
- New: PIN pairing (`/pair-pin-start`, full pair-setup) with stored credentials and pair-verify on later connections; access-control passwords.
- New: NTP timing for Apple receivers, PTP where the device allows it, and automatic fallback to AirPlay 1 for PTP-only receivers (e.g. shairport-sync).
- New: DMAP metadata, artwork and progress, `/feedback` keep-alives, retransmission, event-channel volume/command handling, stream + session TEARDOWN.

### Google Cast - new
- Real Cast protocol (CastV2 over TLS) replacing the DIAL launch attempts that didn't work on current devices: launches the Default Media Receiver, plays the live stream with metadata and artwork, mirrors device volume and play/pause, and stops cleanly.

### Shared
- Receiver remote control (DACP): play/pause/next/previous/volume from receivers' remotes and apps.
- One volume level for all receivers, mirrored when a receiver changes its own volume; mute at the bottom step.
- PIN/password prompts per receiver; saved passwords and pairings can be managed in *Manage AirPlay PINs*.

## [1.1.8]

This release brings a new streaming protocol, hardware volume key support, and a major round of AirPlay 1/2 reliability fixes, alongside a rework of the AriaCompanion suite and Home Assistant integration.

### Features & Improvements

#### New Protocols & Controls
- **Snapcast support**: added Snapcast as a new streaming protocol (#42, @daniel-freiermuth).
- **Hardware volume keys**: device volume buttons now control the receiver's volume (#43, @daniel-freiermuth).
- **Full AirPlay 1 support**: new UDP transport, ALAC encoding, timing, volume and sync handling (#38, @daniel-freiermuth).

#### AirPlay 1 & 2 Reliability
- Switched AirPlay 2 timing to the correct **PTP** protocol instead of NTP (#39, @daniel-freiermuth).
- Fixed AirPlay 2 session ID fallback, stream port parsing and transient encryption issues (#35, @daniel-freiermuth).
- AirPlay 1: connect to the advertised port and fixed socket reuse on fallback (#37, @daniel-freiermuth); capture audio natively at 44100 Hz with periodic sync re-anchoring.
- Discovery: AirPlay 1 and AirPlay 2 entries are now correctly distinguished, removing duplicate/wrong listings (#36, @daniel-freiermuth).
- Resampler: normalized the polyphase filter to unity gain, fixing volume drift (#40, @daniel-freiermuth).
- Acquire a partial wake lock while casting, preventing playback drops when the screen locks (#41, @daniel-freiermuth).
- Settings: the AirPlay 2 protocol switch is toggleable again after a short-lived regression.

#### AriaCompanion & Smart Home
- Reworked AriaCompanion around a two-board Sender/Receiver suite, now streaming directly to a real AriaCast Receiver over the native protocol, with Now Playing metadata relay and packet-logger integration (#33, AriaCast + Claude Code).
- Added an **HA Mode bridge** (`HAModeManager` + `HaEcosystemClient`) for Home Assistant ecosystem integration (#31, AriaCast + Claude Code).

#### Setup & Connectivity
- Native `ariacast://` deep links to start/stop casting and to join Wi-Fi before casting (#32/#34, AriaCast + Claude Code).
- NFC: write `ariacast://` tags natively and see discovered devices directly on the NFC write screen (#34, AriaCast + Claude Code).

#### Security
- Fixed full-app audit findings covering plugin sandboxing, network trust, crypto handling and potential leaks (#34, AriaCast + Claude Code).

#### Localization
- Updated Chinese (Simplified), Dutch, Japanese, Italian and French translations via the Weblate community.

### Credits
- **@daniel-freiermuth** — Snapcast protocol, hardware volume keys, full AirPlay 1 support, and a large batch of AirPlay 1/2 reliability fixes.
- **AriaCast / Claude Code** — AriaCompanion suite rework, HA Mode bridge, NFC & deep-link improvements, full-app security audit fixes.
- **Weblate community translators** — Chinese, Dutch, Japanese, Italian and French updates.
- **@AirPlr (Lorenzo Imbastari)** — release management, PR reviews & merges.

---
*Thanks to everyone who contributed to this release!*
