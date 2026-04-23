# AriaCast (Android Application)

![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/AirPlr/AriaCast-app/latest/total?style=for-the-badge)


**Capture and stream your Android device's internal audio to any receiver on your local network.**

AriaCast is a powerful Android application that allows you to broadcast audio from *any* application—such as Spotify, YouTube Music, or Pocket Casts—to a designated custom server. This effectively turns any computer or compatible device on your network into a high-fidelity networked speaker.


[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/AirPlr)

## Features
<p float="left">
<img width="45%" alt="AriaCast Android App Screenshot" src="https://github.com/user-attachments/assets/425c09ff-312e-469f-aa5f-5be7e26330dc" />
&nbsp;&nbsp;&nbsp;&nbsp;
<img width="45%" alt="AriaCast Quick toggle Screenshot" src="https://github.com/user-attachments/assets/e30ae4bc-a950-44bf-942d-5ef667d6f878" />




*   **System-Wide Audio Streaming**: Captures the internal audio output of your Android device using the `MediaProjection` API and streams it in real-time with low latency.
*   **Real-Time Metadata**: Automatically detects currently playing media using the `NotificationListenerService`. Syncs rich metadata (Title, Artist, Album, Artwork) to the receiver instantly.
*   **Auto-Discovery**: Automatically finds available AriaCast servers running on your local network.
*   **Remote Control**: Adjust the receiver's volume directly from the app.
*   **Quick Settings Tile**: Start and stop casting instantly from your notification shade without opening the app.
*   **Efficient Networking**: Uses binary WebSockets for audio and control commands to ensure minimal overhead.

## How It Works

AriaCast uses a robust foreground service to manage the capture and streaming lifecycle:

1.  **Audio Capture**: When casting starts, the app leverages the `MediaProjection` API to capture the device's internal audio bus.
2.  **Streaming**: Raw audio data is encoded and transmitted over a persistent WebSocket connection to the `/audio` endpoint of the selected server.
3.  **Metadata Sync**: A background `NotificationListenerService` monitors media notifications. When a track changes, metadata is extracted and pushed to the server via the `/metadata` endpoint.
4.  **Control Channel**: Volume commands are sent over a dedicated `/control` WebSocket, enabling real-time adjustments without interrupting the audio stream.

## Setup & Usage

### Prerequisites

*   An Android device running **Android 12 (API 31)** or higher.
*   The companion **AriaCast Server** running on a computer, or MusicAssistant with the plugin installed, on the same local network.

### Installation & Permissions

1.  **Install the App**: Build from source or install the `AriaCast.apk` on your device.
2.  **Grant Permissions**:
    *   **Notification Access**: Required to read media metadata (Artist/Title) from playing apps. You will be prompted to enable this on first launch.
    *   **Audio Capture**: Android explicitly asks for permission to "start recording or casting" when you first initiate a stream. This is required for internal audio capture.

---

### ⚠️ Root Users Only

This application cannot capture DRM-protected content by default due to Android's system-level restrictions. If you have a **rooted device**, you can attempt to bypass these limitations using the following module:

* **Tool:** [LSPosed/DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure)
* **Requirement:** Magisk + LSPosed Framework

> **Disclaimer:** Use this at your own risk. Bypassing `FLAG_SECURE` may violate the Terms of Service of some applications and could lead to account suspension or stability issues.

---

### Start Casting

 Open AriaCast, wait for it to discover servers, select your target, and tap to Start Casting.

## Plugin System

AriaCast features a powerful JavaScript-based plugin system that allows extending the app's UI and functionality without recompiling. This is used to provide native controls for specific servers (like Music Assistant) directly within the AriaCast interface.

See [the PLUGINS repository](https://github.com/AriaCast/AriaCast-android-plugins) for documentation

## Backend Server

This document describes the Android client. The server-side application, responsible for receiving the audio stream and metadata, acts as the "speaker" and must be running on your local network.

*   **Server Repository**: [Link to AriaCast Server](https://github.com/AirPlr/Ariacast-server)
*   **Music Assistant Plugin Repository**: [Link to Ariacast MusicAssistant Plugin](https://github.com/AirPlr/AriaCast-Receiver-MusicAssistant)
