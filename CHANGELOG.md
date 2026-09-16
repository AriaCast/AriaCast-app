# Changelog

All notable changes to AriaCast are documented here. Releases prior to 1.1.8
are available as [GitHub Releases](https://github.com/AriaCast/AriaCast-app/releases).

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
