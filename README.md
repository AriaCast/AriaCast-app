# AriaCast (Android Application)
![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/AirPlr/AriaCast-app/latest/total?style=for-the-badge)
![AirPlay Compatible](https://img.shields.io/badge/Supports-AirPlay_1-blue?style=for-the-badge)

<img width="30%" height="auto" alt="Screenshot_20260425_220300_AriaCast" src="https://github.com/user-attachments/assets/12b44736-e1f7-47c8-b292-c6e9ae8be728" />
<img width="30%" height="auto" alt="Screenshot_20260425_220307_AriaCast" src="https://github.com/user-attachments/assets/682ad335-4ffc-40b7-9f78-7894c13764dd" />
<img width="30%" height="auto" alt="Screenshot_20260425_220321_AriaCast" src="https://github.com/user-attachments/assets/36e14a6d-5c71-4043-9a7b-0cea7949c95d" />


**Capture and stream your Android device's internal audio to any receiver—including AirPlay 1 speakers and Smart TVs.**

AriaCast bridges the gap between Android and the rest of your home audio ecosystem. By capturing your device's internal audio output, it allows you to stream music from *any* app—Spotify, YouTube Music, Pocket Casts, and more—directly to **AirPlay 1 compatible speakers**, DLNA devices, or custom AriaCast servers.

---

### 🚀 Key Features: Now with AirPlay 1 Support
*   **Universal AirPlay 1 Streaming**: Directly broadcast your Android audio to legacy AirPlay speakers and receivers. No proprietary hardware required.
*   **System-Wide Audio Capture**: Uses the `MediaProjection` API to grab audio from any application, ensuring you never miss a beat.
*   **Real-Time Metadata**: Syncs track info, artist names, and album artwork automatically (via `NotificationListenerService`).
*   **Zero-Config Discovery**: Automatically scans your local network for available AirPlay and DLNA receivers.
*   **Quick Control**: Dedicated Notification Shade tile to start/stop casting without ever opening the app.

---

## Supported Protocols
AriaCast acts as a versatile streaming hub, focusing on maximum compatibility:

| Protocol | Status | Best For |
| :--- | :--- | :--- |
| **AirPlay 1** | ✅ **Active** | Legacy audio systems, Hifi speakers, and receivers. |
| **AriaCast (Native)** | ✅ **Active** | Low-latency, high-fidelity, and rich metadata. |
| **DLNA / UPnP** | ✅ **Active** | Smart TVs, AV receivers, and generic media players. |
| **AirPlay 2** | 🚧 Coming Soon | Multi-room streaming and PIN-protected devices. |
| **Google Cast** | 🚧 Coming Soon | Chromecast, Nest, and Android TV ecosystem. |

---

## Setup & Usage

### Prerequisites
*   Android 12 (API 31) or higher.
*   An **AirPlay 1** receiver or DLNA-compatible device on the same local network.

### Quick Start
1.  **Download**: Get the latest `AriaCast.apk` from the [Releases](https://github.com/AirPlr/AriaCast-app/releases) page.
2.  **Permissions**: Grant **Notification Access** (to sync metadata) and allow **Audio Capture** (required to stream system audio).
3.  **Stream**: Open the app, select your AirPlay or DLNA speaker from the discovery list, and tap to connect.

---

## Why AriaCast?
Unlike standard apps that only support specific streaming platforms, AriaCast captures audio at the **system level**. Whether you are listening to a niche podcast app or your favorite music player, AriaCast turns your Android device into a powerful source for your entire home audio network.

---

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/AirPlr)

### ⚠️ Advanced: Root Users
To capture DRM-protected content, you can bypass `FLAG_SECURE` restrictions on rooted devices using the [LSPosed/DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure) module.

---

## Contributing & Plugins
AriaCast features a **JavaScript-based plugin system**, allowing the community to extend functionality without recompiling the app.
*   [Documentation & Plugin Repo](https://github.com/AriaCast/AriaCast-android-plugins)
*   [Backend Server Repository](https://github.com/AirPlr/Ariacast-server)

---
### TRANSLATIONS
<a href="https://hosted.weblate.org/engage/ariacast/"><img src="https://hosted.weblate.org/widget/ariacast/ariacast-app/multi-auto.svg" alt="Stato traduzione"></a>
