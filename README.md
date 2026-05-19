<div align="center">
  
  <h1>AriaCast for Android</h1>
  <p><b><i>Stream any audio from your Android device to any speaker in your home.</i></b></p>
  
  <p>
    <a href="https://github.com/AirPlr/AriaCast-app/releases"><img src="https://img.shields.io/github/downloads/AirPlr/AriaCast-app/latest/total?style=for-the-badge&color=2ea44f" alt="Downloads" /></a>
  </p>
</div>

<hr />

<h2>📱 Overview</h2>
<p><b>AriaCast</b> is the ultimate bridge between your Android device and your home audio ecosystem. Instead of relying on proprietary apps, AriaCast captures your system's audio output and routes it to any networked receiver.</p>

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
<li><b>AirPlay 1:</b> Seamless streaming to legacy Apple devices and Hi-Fi speakers.</li>
  <li><b>DLNA / UPnP:</b> Universal compatibility with Smart TVs, AV Receivers, and media boxes.</li>
  <li><i>Coming Soon: AirPlay 2 (Multi-room) & Google Cast.</i></li>
</ul>

<hr />

<h2>✨ Key Features</h2>
<div style="background-color: #f6f8fa; padding: 15px; border-radius: 8px;">
  <ul>
    <li><b>System-Wide Capture:</b> Works with <i>any (No DRM)</i> app.</li>
    <li><b>Automatic Discovery:</b> Instant detection of AriaCast, AirPlay and DLNA devices on your network.</li>
    <li><b>Rich Metadata Sync:</b> Pushes track title, artist, and album art to receivers in real-time.</li>
    <li><b>Quick Settings Tile:</b> Start casting directly from your notification shade.</li>
    <li><b>JavaScript Plugins:</b> Extend app functionality or add custom server controls without re-building.</li>
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
  <i>Advanced users can bypass some restrictions on rooted devices using the <a href="https://github.com/LSPosed/DisableFlagSecure">DisableFlagSecure</a> module with LSPosed/Magisk.</i>
</p>

<hr />

<h2>🛠️ Setup & Usage</h2>
<ol>
  <li><b>Install:</b> Download the latest <a href="https://github.com/AirPlr/AriaCast-app/releases">APK here</a>.</li>
  <li>Grant <b>Notification Access</b> (for metadata) select your receiver, and enjoy.</li>
</ol>


<hr />

<div align="center">
  <h3>🌍 Help Us Translate</h3>
  <a href="https://hosted.weblate.org/engage/ariacast/"><img src="https://hosted.weblate.org/widget/ariacast/ariacast-app/multi-auto.svg" alt="Translation Status"></a>
  <br><br>
  <a href="https://buymeacoffee.com/AirPlr"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee"></a>
</div>

<hr />

<h3>🔗 Related Projects</h3>
<ul>
  <li><a href="https://github.com/AirPlr/Ariacast-server">AriaCast Backend Server</a></li>
  <li><a href="https://github.com/AriaCast/AriaCast-android-plugins">Plugin Documentation</a></li>
</ul>
