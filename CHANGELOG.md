# Changelog

All notable changes to AriaCast are documented here. Releases prior to 1.1.8
are available as [GitHub Releases](https://github.com/AriaCast/AriaCast-app/releases).

## [1.1.9]

This release rewrites the AirPlay 1 and AirPlay 2 senders from the ground up, adds real Google Cast support, and includes a batch of discovery, DLNA and plugin-sandbox fixes.

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

### Fixes & Improvements
- AirPlay 1: resample per destination instead of changing the global capture rate (@daniel-freiermuth).
- AirPlay 1: all RAOP output-stream writers are now synchronized on the interleaved RTSP socket (@daniel-freiermuth).
- DLNA: send `Play` after `SetAVTransportURI` on track change (fixes WiiM playback/pause), and omit the port when the URI uses the default HTTP/HTTPS port (@daniel-freiermuth).
- Discovery: stale devices are now dropped, and Music Assistant Snapserver discovery is fixed (@daniel-freiermuth, @arthurbre).
- Cast: fixed a resource leak on early return in `startCasting`; `CancellationException` is no longer treated as an error (@daniel-freiermuth).
- AriaCompanion: disconnect `HttpURLConnection` on error when fetching receiver state (@daniel-freiermuth).
- Plugins: stricter `PluginClassShutter` whitelist (allows dynamic proxies and androidx), explicit overloads, ES6 mode and new UI helpers (@Highwall2016).

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
