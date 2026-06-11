# AriaCompanion

A hardware bridge that lets AriaCast cast audio from apps blocked by Samsung's
**AudioHardening** system (Metrolist, YouTube Music ReVanced, and others).

## Why this exists

Samsung injects a flag (`FLAG_NO_MEDIA_PROJECTION`) into the audio tracks of
unofficial or sideloaded apps. This flag silently blocks Android's
`MediaProjection` API — the one AriaCast uses to capture system audio.
Official Play Store apps are exempt. There is no software workaround at the
AriaCast level.

AriaCompanion solves this by moving audio capture off the phone entirely:

```
Phone Bluetooth → AriaCompanion (Pi/ESP32) → TCP → AriaCast → Cast destination
```

Your phone plays audio to AriaCompanion as a Bluetooth A2DP sink. AriaCompanion
streams the raw PCM back to AriaCast over your local network. AriaCast then
forwards it to any cast target (AirPlay, RAOP, AriaCast Receiver, etc.) exactly
as it would with captured system audio.

---

## Hardware

| Device | SoC | Arch | Notes |
|--------|-----|------|-------|
| **Raspberry Pi Zero 2W** *(recommended)* | BCM2710 / Cortex-A53 | ARMv8 64-bit | Handles BT+WiFi coexistence well; quad-core headroom |
| Raspberry Pi Zero W | BCM2835 / ARM1176 | ARMv6 32-bit | Works; single-core, tighter margins |
| ESP32 | Xtensa LX6 | — | Firmware in `AriaCompanion.ino`; BT+WiFi radio contention can cause instability |

You also need a **microSD card** (2 GB minimum; 8 GB recommended for writes headroom).

---

## Building the firmware image

### Prerequisites

Install these once on your build machine (Debian / Ubuntu):

```bash
sudo apt-get install -y \
    build-essential git wget cpio rsync bc unzip file \
    libssl-dev libelf-dev python3
```

The build script downloads Buildroot automatically. First build takes **1–2 hours**
and ~5 GB of disk space (toolchain + kernel + packages). Subsequent incremental
builds are much faster.

### Build

```bash
cd AriaCompanion/buildroot

# Raspberry Pi Zero 2W (ARMv8, 64-bit) — recommended
./build.sh pizero2w

# Raspberry Pi Zero W (ARMv6, 32-bit)
./build.sh pizerow

# Build both
./build.sh both
```

Output image: `output-<target>/images/sdcard.img` (~320 MB)

### Flash to SD card

```bash
# Replace /dev/sdX with your SD card device (check with lsblk first!)
sudo dd if=output-pizero2w/images/sdcard.img of=/dev/sdX bs=4M status=progress
sync
```

Or use [Raspberry Pi Imager](https://www.raspberrypi.com/software/) →
*Use custom image* and select the `.img` file.

---

## First-time setup

### 1. Boot the Pi

Insert the SD card and power the Pi. On first boot it has no WiFi credentials,
so it automatically starts in **setup mode**:

- Creates a WiFi access point named **`AriaCompanion-XXXX`** (last 4 hex chars
  of the MAC address)
- IP address: `192.168.4.1`

### 2. Connect your phone to the AP

Go to your phone's WiFi settings and join **`AriaCompanion-XXXX`**.
There is no password.

Your phone will detect a captive portal and open a browser automatically
(Android shows a notification; iOS redirects Safari). If it doesn't open
automatically, navigate to **`http://192.168.4.1`** manually.

### 3. Enter your home WiFi credentials

Fill in your home network SSID and password, then tap **Connect & Reboot**.

The Pi saves the credentials and reboots. After ~30 seconds it will be on
your home network.

### 4. Pair Bluetooth

On your phone, open **Bluetooth settings** and scan for new devices.
Select **AriaCompanion**. No PIN is required — the device auto-accepts all
pairing requests.

> **Tip:** You only need to pair once. After that the Pi auto-reconnects
> whenever it's powered on and your phone is in range.

### 5. Enable in AriaCast

1. Open AriaCast → **Settings** → **AriaCompanion**
2. Tap **Scan** — the device appears automatically via mDNS
   (or enter `192.168.4.1` manually if mDNS doesn't work on your router)
3. Enable **Use as Audio Source**

From now on, when you tap Cast in AriaCast, it will:
1. Pull audio from AriaCompanion over TCP instead of capturing system audio
2. Forward it to your cast target as usual

---

## Daily use

1. Power on the Pi (or leave it always on)
2. Set your phone's **audio output to AriaCompanion** via Bluetooth
3. Play audio in any app — even ones blocked by AudioHardening
4. Tap **Cast** in AriaCast as normal

The Bluetooth volume on your phone controls the level. Keep it at 100% for
best quality; adjust volume on the cast destination instead.

---

## OTA updates

You can update the `aria-companion` streaming binary without rebuilding the
whole image or SSH-ing into the device.

### Build the updated binary

```bash
cd AriaCompanion/buildroot

# Rebuild (uses cached toolchain — much faster than first build)
./build.sh pizero2w   # or pizerow

# The updated binary is at:
#   output-pizero2w/target/usr/bin/aria-companion
```

### Upload via the web interface

1. Make sure AriaCompanion is in **normal mode** (connected to home WiFi)
2. Open **`http://ariacompanion.local`** in a browser on the same network
   (or use the device's IP address)
3. Under **OTA update**, choose the `aria-companion` binary and tap
   **Upload & Restart**

The server verifies the file is a valid ELF binary, replaces the binary
atomically, and sends SIGTERM to the running daemon. The init system
restarts it within a second. No reboot needed.

---

## Management web interface

Available at **`http://ariacompanion.local`** (or `http://<device-ip>`):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Status page (BT powered, A2DP streaming) + OTA upload form |
| `/status` | GET | JSON status: `{"mode","bt","streaming"}` |
| `/update` | POST | Upload new `aria-companion` binary (multipart/form-data, field `bin`) |
| `/reset` | POST | Clear WiFi credentials, reboot to setup mode |

In setup mode, every URL except `/wifi` and `/status` redirects to the
captive portal config page.

---

## Resetting WiFi

To switch to a different network:

**Via the web interface:** open `http://ariacompanion.local` → scroll down →
tap **Reset WiFi credentials** → confirm. The Pi reboots into setup mode and
the `AriaCompanion-XXXX` AP appears again.

**Via SSH (if you have access):**
```bash
ssh root@ariacompanion.local   # password: aria
touch /etc/aria-setup-needed
reboot
```

---

## SSH access

Dropbear SSH is included for debugging.

```bash
ssh root@ariacompanion.local
# password: aria
```

Useful commands:
```bash
# Check streaming daemon logs
cat /var/log/aria-companion.log

# Check management server logs
cat /var/log/aria-mgr.log

# Check WiFi connection
ip addr show wlan0
wpa_cli status

# Check Bluetooth status
bluetoothctl show
bluealsa-cli list-pcms

# Restart everything
/etc/init.d/S42bt-agent restart
/etc/init.d/S50aria-companion restart
```

---

## Audio format

AriaCompanion streams raw PCM with no header:

| Parameter | Value |
|-----------|-------|
| Sample rate | 44 100 Hz |
| Channels | 2 (stereo) |
| Bit depth | 16-bit signed, little-endian |
| Encoding | None (raw PCM) |
| Port | TCP 7001 |

AriaCast upsamples from 44 100 → 48 000 Hz internally using the polyphase FIR
resampler before forwarding to the cast destination. No configuration needed.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Raspberry Pi Zero W / 2W                                   │
│                                                             │
│  bluetoothd (BlueZ)  ←─── A2DP ──── Phone Bluetooth        │
│       │                                                     │
│  bluealsa (-p a2dp-sink)                                    │
│       │  (ALSA PCM device: bluealsa:DEV=XX:XX:...,PROFILE=a2dp)
│       │                                                     │
│  aria-companion  ──────────── TCP :7001 ──────► AriaCast   │
│                                                             │
│  aria-mgr (HTTP :80)  ── setup: captive portal             │
│                        ── normal: status + OTA upload       │
│                                                             │
│  avahi-daemon  ─── mDNS: _ariacompanion._tcp:7001          │
└─────────────────────────────────────────────────────────────┘
```

### Boot sequence

| Init script | Service | Purpose |
|-------------|---------|---------|
| S30network | hostapd + dnsmasq *or* wpa_supplicant + dhcpcd | AP setup mode or home WiFi |
| S35aria-mgr | aria-mgr.py | HTTP management server |
| S40bluetooth | bluetoothd | Bluetooth stack |
| S41bluealsa | bluealsa | A2DP sink ALSA device |
| S42bt-agent | bt-agent.py | Auto-accept BT pairing |
| S50aria-companion | aria-companion | PCM capture → TCP stream |

---

## Troubleshooting

**The AP doesn't appear after first boot**
- Wait 30–60 seconds; the BCM43438 WiFi chip takes time to initialise
- Check that the SD card was flashed correctly (the boot partition must be FAT32)

**`AriaCompanion` doesn't appear in Bluetooth scan**
- Give it 10–15 seconds after connecting to the AP or home WiFi
- SSH in and run `bluetoothctl show` — `Discoverable: yes` should appear
- Try `killall bt-agent.py && python3 /opt/aria-companion/bt-agent.py &`

**AriaCast can't find the device (mDNS fails)**
- Some routers block mDNS between clients; use the IP address directly
- Find the IP: check your router's DHCP list for hostname `AriaCompanion`,
  or SSH in and run `ip addr show wlan0`

**Audio is choppy or cuts out**
- Keep Bluetooth volume at 100% on the phone; adjust elsewhere
- Make sure the Pi has good WiFi signal; move it closer to your router
- SSH in and check `cat /var/log/aria-companion.log` for ALSA errors

**OTA upload fails with "Not a valid ELF binary"**
- Make sure you're uploading the binary for the right architecture:
  `aria-companion` from `output-pizero2w/` for Pi Zero 2W (aarch64),
  `output-pizerow/` for Pi Zero W (armv6)
