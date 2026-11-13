<div align="center">
  
  <h1>AriaCast for Android</h1>
  <p><b><i>Stream any audio from your Android device to any speaker in your home, or turn it into one.</i></b></p>
  
  <p>
    <a href="https://github.com/AirPlr/AriaCast-app/releases"><img src="https://img.shields.io/github/downloads/AirPlr/AriaCast-app/latest/total?style=for-the-badge&color=2ea44f" alt="Downloads" /></a>
    &nbsp;
    <a href="https://store.colourswift.com/app/com.aria.ariacast"><img src="assets/get-it-on-safehaven.svg" alt="Get it on SafeHaven" height="70" /></a>
  </p>
</div>

<hr />

<h2>📱 Overview</h2>
<p><b>AriaCast</b> is the bridge between your Android device and your home audio. It captures your device's audio and sends it to any networked speaker, and since 2.0 it can also <b>receive</b>: a phone, tablet or Android TV becomes a speaker for AriaCast, DLNA apps and Music Assistant, or a bridge that forwards what it receives to other speakers.</p>

<div style="display: flex; justify-content: space-between;">
  <img width="32%" alt="UI 1" src="https://github.com/user-attachments/assets/12b44736-e1f7-47c8-b292-c6e9ae8be728" />
  <img width="32%" alt="UI 2" src="https://github.com/user-attachments/assets/682ad335-4ffc-40b7-9f78-7894c13764dd" />
  <img width="32%" alt="UI 3" src="https://github.com/user-attachments/assets/36e14a6d-5c71-4043-9a7b-0cea7949c95d" />
</div>

<hr />

<h2>🚀 Supported Streaming Protocols</h2>
<p>We believe in open standards. AriaCast allows you to stream to a wide array of devices:</p>

<ul>
  <li><b>AriaCast Native:</b> Our high-performance protocol using binary WebSockets for low-latency, high-fidelity audio with rich metadata sync.</li>
<li><b>AirPlay 1:</b> AirPort Express, Apple TV and any AirPlay speaker/receiver (RAOP): lossless ALAC or PCM, RSA/AES encryption, password and on-screen-code pairing, now-playing text, artwork and progress, receiver remote control and volume sync.</li>
  <li><b>AirPlay 2:</b> HomePods, Apple TVs, Macs and AirPlay 2 speakers: HomeKit pairing (transient or PIN), encrypted control and audio channels, metadata, artwork and volume sync, with automatic fallback to AirPlay 1 for receivers that need timing Android can't provide.</li>
  <li><b>Google Cast:</b> Chromecast, Nest speakers/displays and Cast groups, with now-playing metadata and two-way volume and play/pause sync.</li>
  <li><b>DLNA / UPnP:</b> Universal compatibility with Smart TVs, AV Receivers, and media boxes.</li>
  <li><b>Snapcast:</b> synchronized multi-room playback through a Snapcast server.</li>
  <li><b>Sendspin (Music Assistant):</b> this device's audio as a Music Assistant Live Input, encrypted end to end and paired with a token.</li>
</ul>

<h2>📥 Receive on this device</h2>
<ul>
  <li><b>AriaCast receiver:</b> other AriaCast devices see this one as a speaker.</li>
  <li><b>DLNA renderer:</b> any DLNA app can play music or video here; videos play full screen (great on Android TV).</li>
  <li><b>Sendspin player:</b> Music Assistant plays here in sync with its other speakers.</li>
  <li><b>Bridge:</b> forward what this device receives to AirPlay, Cast, DLNA, Snapcast or Sendspin speakers.</li>
  <li><b>Receive screen</b> for tablets and TVs, with artwork colors, a clock and a "scan to cast here" QR code.</li>
</ul>

<hr />

<h2>✨ Key Features</h2>
<div style="background-color: #f6f8fa; padding: 15px; border-radius: 8px;">
  <ul>
    <li><b>System-Wide Capture:</b> Works with <i>any (No DRM)</i> app.</li>
    <li><b>Automatic Discovery:</b> Instant detection of AriaCast, AirPlay and DLNA devices on your network.</li>
    <li><b>Rich Metadata Sync:</b> Pushes track title, artist, and album art to receivers in real-time.</li>
    <li><b>Quick Settings Tile:</b> Start casting directly from your notification shade.</li>
    <li><b>Sources:</b> other apps, the microphone or a USB line-in (turntables, mixers), AriaCompanion, or audio this device receives.</li>
    <li><b>Room handoff:</b> add, remove or move speakers while casting.</li>
    <li><b>Video casting</b> to Google Cast, DLNA and AirPlay, with subtitles.</li>
    <li><b>Now Playing</b> with visualizer and optional synced lyrics, <b>connection doctor</b>, home-screen <b>widget</b>, <b>web remote</b>.</li>
    <li><b>Music Assistant</b> player picker built in.</li>
    <li><b>Automation:</b> intents for Tasker and MacroDroid (opt-in).</li>
    <li><b>JavaScript Plugins:</b> extend the app without re-building; plugins draw their UI with <code>ui.render()</code> (Plugin API v2).</li>
  </ul>
</div>
<hr />


<h2>🎵 Audio Source Compatibility</h2>
<p>
  AriaCast captures audio at the system level via the <code>MediaProjection</code> API. 
  While this allows for broad compatibility, please note the following regarding content sources:
</p>

<div style="background-color: #fff3cd; padding: 15px; border-radius: 8px; border-left: 5px solid #ffc107; color: #856404;">
  <strong>Note on DRM-Protected Content:</strong> 
  Some major streaming services (like Spotify, YouTube Music, etc.) implement strict <code>FLAG_SECURE</code> 
  or DRM protections that prevent system-level audio capture. Consequently, AriaCast may not be able 
  to stream audio from these specific apps by default.
  <br><br>
  <strong>AriaCast shines with:</strong>
  <ul>
    <li><b>Local Music Players:</b> Perfect for Poweramp, Musicolet, or any player managing your personal FLAC/MP3 library.</li>
    <li><b>Podcasts & Audiobooks:</b> Great for AntennaPod or other open-source audio apps.</li>
    <li><b>Personal Media:</b> Your own voice recordings, local audio files, and non-DRM streaming apps.</li>
  </ul>
</div>

<p>
  <b>AriaCompanion 2</b> casts those apps too, without root: a single RP2040 board (Raspberry Pi Pico or RP2040-Zero)
  on the USB-C port acts as a USB speaker and hands the audio straight back to AriaCast. Calls stay on the phone and
  notification sounds are held back. See <a href="AriaCompanion/README.md">AriaCompanion</a>.
</p>
<p>
  <i>Advanced users can bypass some restrictions on rooted devices using the <a href="https://github.com/LSPosed/DisableFlagSecure">DisableFlagSecure</a> module with LSPosed/Magisk.</i>
</p>

<hr />

<h2>🛠️ Setup & Usage</h2>
<ol>
  <li><b>Install:</b> Download the latest <a href="https://github.com/AirPlr/AriaCast-app/releases">APK here</a>.</li>
  <li>Grant <b>Notification Access</b> (for metadata)</li>
<li><b>Select</b> your receiver, and <b>ENJOY</b>.</li>
</ol>


<hr />

<div align="center">
  <h3>🌍 Help Us Translate</h3>
  <a href="https://hosted.weblate.org/engage/ariacast/"><img src="https://hosted.weblate.org/widget/ariacast/ariacast-app/multi-auto.svg" alt="Translation Status"></a>
  <br><br>
  <a href="https://buymeacoffee.com/AirPlr"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee"></a>
</div>

<hr />

<h3>🧪 Protocol tests</h3>
<p>
  Unit tests run with <code>./gradlew testGithubDebugUnitTest</code> (AriaCompanion firmware: <code>make -C AriaCompanion/firmware/test</code>). The AirPlay clients are also tested end to end against real
  <a href="https://github.com/mikebrady/shairport-sync">shairport-sync</a> receivers (AirPlay 1, and AirPlay 2 with nqptp): start them with
  <code>sudo scripts/e2e/start-receivers.sh</code> (see the script for options), then run <code>AIRPLAY_E2E=1 ./gradlew testGithubDebugUnitTest</code>.
  The tests check the audio the receiver actually decodes, not just the handshakes.
</p>

<hr />

<h3>🔗 Related Projects</h3>
<ul>
  <li><a href="https://github.com/AirPlr/Ariacast-server">AriaCast Backend Server</a></li>
  <li><a href="https://github.com/AriaCast/AriaCast-android-plugins">Plugin Documentation</a></li>
</ul>
