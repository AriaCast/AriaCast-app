# AriaCast (Android Application)

![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/AirPlr/AriaCast-app/latest/total?style=for-the-badge)


# [Discord Server](https://discord.gg/JpAkwGTG)


**Capture and stream your Android device's internal audio to any receiver on your local network.**

AriaCast is a powerful Android application that allows you to broadcast audio from *any* application—such as Spotify, YouTube Music, or Pocket Casts—to a designated custom server. This effectively turns any computer or compatible device on your network into a high-fidelity networked speaker.


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

### Start Casting

You can start streaming in two ways:
1.  **App Interface**: Open AriaCast, wait for it to discover servers, select your target, and tap **"Start Casting"**.
2.  **Quick Settings**: Add the **AriaCast** tile to your Quick Settings panel for one-tap access to start/stop streaming.

## Plugin System

AriaCast features a powerful JavaScript-based plugin system that allows extending the app's UI and functionality without recompiling. This is used to provide native controls for specific servers (like Music Assistant) directly within the AriaCast interface.

### Plugin Storage & Structure
By default, plugins are stored in the app's internal data directory. However, since many Android devices restrict access to `Android/data`, **you can select a custom plugin folder** in the application settings. This allows you to easily manage plugin files using any file explorer.

Each plugin consists of two files:
- `plugin_name.json`: Metadata (ID, name, version, author).
- `plugin_name.js`: The JavaScript logic executed via the Rhino engine.

### JavaScript API Reference
Plugins have access to several global objects to interact with the Android system and AriaCast service:

#### `ui` (UI Operations)
- `ui.run(function)`: Runs code on the Android UI thread.
- `ui.add(view)`: Adds an Android View to the plugin container.
- `ui.clear()`: Removes all views from the plugin container.
- `ui.inflate(layoutName)`: Inflates a native XML layout (e.g., `item_server`).
- `ui.findView(parent, idName)`: Finds a view within an inflated layout by its ID string.

#### `events` (Lifecycle & State)
- `events.onServiceConnected(callback)`: Triggered when connected to an AriaCast server. Provides the service object.
- `events.onStateChanged(callback)`: Triggered on casting state changes (`CONNECTING`, `CASTING`, `IDLE`).
- `events.onConfigRequested(callback)`: Triggered when the user taps the "Settings" icon for the plugin.

#### `http` & `ws` (Networking)
- `http.fetch(url, headers)`: Synchronous GET request.
- `http.post(url, body, headers)`: Synchronous POST request.
- `ws.request(url, command, args, token)`: Specialized WebSocket RPC helper for real-time communication.

#### `storage` (Persistence)
- `storage.get(key)`: Retrieves a string value.
- `storage.set(key, value)`: Persists a string value.

#### `bg` (Concurrency)
- `bg.run(function)`: Executes a function in a background thread to avoid blocking the UI.

### Example: Music Assistant Control
The built-in Music Assistant plugin uses these APIs to:
1. Detect if the connected server is a Music Assistant instance.
2. Fetch the list of available players via WebSocket.
3. Render a native list of players.
4. Allow the user to "target" a specific player for the current stream.

## Backend Server

This document describes the Android client. The server-side application, responsible for receiving the audio stream and metadata, acts as the "speaker" and must be running on your local network.

*   **Server Repository**: [Link to AriaCast Server](https://github.com/AirPlr/Ariacast-server)
*   **Music Assistant Plugin Repository**: [Link to Ariacast MusicAssistant Plugin](https://github.com/AirPlr/AriaCast-Receiver-MusicAssistant)
