<div align="center">

# AriaCast for Android

**Stream any audio from your Android device to any speaker in your home.**

[![Total downloads](https://img.shields.io/github/downloads/AriaCast/AriaCast-app/total?style=for-the-badge&color=2ea44f&label=Total%20downloads)](https://github.com/AriaCast/AriaCast-app/releases)
[![Latest release downloads](https://img.shields.io/github/downloads/AriaCast/AriaCast-app/latest/total?style=for-the-badge&color=1f6feb&label=Latest%20release)](https://github.com/AriaCast/AriaCast-app/releases/latest)
[![Latest version](https://img.shields.io/github/v/release/AriaCast/AriaCast-app?style=for-the-badge&label=Version)](https://github.com/AriaCast/AriaCast-app/releases/latest)
[![Translation status](https://hosted.weblate.org/widget/ariacast/ariacast-app/svg-badge.svg)](https://hosted.weblate.org/engage/ariacast/)

<a href="https://store.colourswift.com/app/com.aria.ariacast"><img src="assets/get-it-on-safehaven.svg" alt="Get it on SafeHaven" height="70" /></a>

</div>

<div align="center">
  <img width="32%" alt="UI 1" src="https://github.com/user-attachments/assets/12b44736-e1f7-47c8-b292-c6e9ae8be728" />
  <img width="32%" alt="UI 2" src="https://github.com/user-attachments/assets/682ad335-4ffc-40b7-9f78-7894c13764dd" />
  <img width="32%" alt="UI 3" src="https://github.com/user-attachments/assets/36e14a6d-5c71-4043-9a7b-0cea7949c95d" />
</div>

---

## Overview

AriaCast captures your phone's system audio with Android's `MediaProjection` API and sends it to the speakers you already own. It speaks the protocols those speakers understand, so you don't need each vendor's app.

- **Any app, any speaker:** capture is system-wide, and receivers are found automatically.
- **Several receivers at once**, with one volume level mirrored across all of them.
- **Rich now-playing info:** title, artist, album, artwork and progress, as far as each protocol allows.
- **Control from both ends:** hardware volume keys, receiver remotes and Quick Settings.

Requires Android 12 (API 31) or newer.

## Supported protocols

| Protocol | Works with | What AriaCast does |
|---|---|---|
| **AriaCast Native** | [AriaCast servers](https://github.com/AirPlr/Ariacast-server) and AriaCompanion | Binary WebSockets (`/audio`, `/control`, `/stats`) carrying 48 kHz stereo PCM, with metadata sync and volume control. Discovery uses mDNS (`_audiocast._tcp`) and a UDP broadcast. |
| **AirPlay 1 (RAOP)** | AirPort Express, Apple TV, AirPlay speakers, shairport-sync | RTSP + RTP with lossless ALAC or L16 PCM, RSA/AES encryption, password (Digest) and on-screen-code pairing, MFi `auth-setup`, retransmission and drift correction. Sends text, artwork and progress metadata. |
| **AirPlay 2** | HomePod, Apple TV, Mac, AirPlay 2 speakers | HomeKit pairing (transient or PIN, with stored credentials), HAP-encrypted control and event channels, ChaCha20-Poly1305 audio, NTP or PTP timing, and metadata, artwork and volume sync. Falls back to AirPlay 1 for PTP-only receivers when Android can't provide the timing. |
| **Google Cast** | Chromecast, Nest speakers and displays, Cast groups | Real CastV2 (TLS, protobuf) using the Default Media Receiver. Pushes metadata and artwork, and syncs volume and play/pause both ways. |
| **DLNA / UPnP** | Smart TVs, AV receivers, WiiM and other media boxes | SSDP discovery and AVTransport/RenderingControl over SOAP. The receiver plays a live WAV stream served by the phone, with DIDL-Lite metadata. |
| **Snapcast** | Snapserver | JSON-RPC control plus a TCP audio source (48 kHz, 16-bit, stereo). The stream is assigned to your groups and the previous assignment is restored when you stop. |

Every protocol can be switched on or off under **Settings → Protocols**.

**Receiver remote control (DACP):** AirPlay receivers and their apps can send play/pause, next/previous and volume commands back to the phone.

## Features

- **Automatic discovery** over mDNS, SSDP and UDP broadcast.
- **Manual servers** by host and port, for other VLANs, WireGuard or Tailscale, where multicast discovery can't reach.
- **Quick Settings tiles** to start and stop casting from the notification shade.
- **Hardware volume keys** control the receiver's volume.
- **`ariacast://` deep links and NFC tags** to start or stop casting and to join a Wi-Fi network first. The app can write the NFC tags itself.
- **AirPlay PIN and password manager** for saved pairings and credentials.
- **Packet logger** for inspecting protocol traffic when something misbehaves.
- **JavaScript plugins** (Rhino, sandboxed) that add UI or react to casting events. See the [plugin documentation](https://github.com/AriaCast/AriaCast-android-plugins).
- **Home Assistant mode:** pick rooms backed by your Home Assistant Areas through the `ariacast_core` add-on.
- **Localized** into many languages through Weblate.

## AriaCompanion (ESP32 bridge)

Some phones, notably Samsung ones, block `MediaProjection` capture for sideloaded apps. [AriaCompanion](AriaCompanion/README.md) works around this with two ESP32 boards:

```
Phone ──Bluetooth A2DP──▶ ESP32 (BT sink) ──I2S──▶ ESP32 (Wi-Fi sender) ──WebSocket──▶ AriaCast receiver
```

The phone is never in the audio path. AriaCast only selects the target through the sender's REST API. Because the bridge speaks the native protocol, it works with AriaCast receivers only.

## Audio source compatibility

> **DRM-protected apps:** Some streaming apps (Spotify, YouTube Music and similar) block system audio capture with `FLAG_SECURE` or DRM, so AriaCast may be unable to stream them.

AriaCast works well with:

- **Local music players:** Poweramp, Musicolet, or any player for your own FLAC/MP3 library.
- **Podcasts and audiobooks:** AntennaPod and other open-source apps.
- **Personal media:** voice recordings, local files and non-DRM streaming apps.

On rooted devices, the [DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure) LSPosed/Magisk module can bypass some of these restrictions.

## Getting started

1. **Install:** get the latest APK from [Releases](https://github.com/AriaCast/AriaCast-app/releases/latest), or from [SafeHaven](https://store.colourswift.com/app/com.aria.ariacast).
2. **Grant Notification Access** so AriaCast can read track metadata.
3. **Enable the protocols** you use under **Settings → Protocols**.
4. **Select your receiver** and start casting.

## Testing

Run the unit tests with:

```sh
./gradlew testDebugUnitTest
```

The AirPlay clients are also tested end to end against real [shairport-sync](https://github.com/mikebrady/shairport-sync) receivers (AirPlay 1, and AirPlay 2 with nqptp). The tests check the audio the receiver actually decodes, not just the handshakes.

```sh
sudo scripts/e2e/start-receivers.sh      # see the script for options
AIRPLAY_E2E=1 ./gradlew testDebugUnitTest
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release notes.

## Contributing and translations

Pull requests are welcome. Help translate AriaCast on [Weblate](https://hosted.weblate.org/engage/ariacast/).

[![Translation status](https://hosted.weblate.org/widget/ariacast/ariacast-app/multi-auto.svg)](https://hosted.weblate.org/engage/ariacast/)

If you enjoy AriaCast, you can support its development:

<a href="https://buymeacoffee.com/AirPlr"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" height="40"></a>

## Related projects

- [AriaCast Server](https://github.com/AirPlr/Ariacast-server): the receiver/backend for the native protocol
- [Plugin documentation](https://github.com/AriaCast/AriaCast-android-plugins)

## License

See [LICENSE.md](LICENSE.md).
