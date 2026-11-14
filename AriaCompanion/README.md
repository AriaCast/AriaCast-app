# AriaCompanion 2

A single RP2040 board on the phone's USB-C port that lets AriaCast cast apps whose audio
can't be captured (apps that opt out of capture, and sideloaded apps on Samsung phones).

The phone sees an ordinary **USB speaker**. Whatever it plays there, the board sends straight
back to the AriaCast app over a private USB channel that no other app can see, and AriaCast
streams it to any speaker: AirPlay, Google Cast, DLNA, Snapcast, Sendspin or AriaCast.
No Bluetooth, no Wi-Fi setup, no screen-capture prompt, lossless 48 kHz/16-bit.

Only media audio reaches the house: calls stay on the phone, and notification sounds,
alarms and ringtones are held back. See [docs/ariacompanion-2.md](../docs/ariacompanion-2.md)
for the full design.

## What you need

- Any RP2040 board: Raspberry Pi Pico, or Waveshare RP2040-Zero (USB-C). One firmware image runs on both.
- A USB-C OTG cable or adapter.

## Install

1. Hold **BOOTSEL** while plugging the board into a computer; it appears as a drive called `RPI-RP2`.
2. Copy `aria_companion.uf2` onto it (from a release, or from `app/src/main/assets/companion/`).
3. Plug the board into the phone. Android asks which app should open it: choose AriaCast and tick **Always**.

After that, updates happen from **Settings → AriaCompanion → Update firmware** in the app.

## Status LED

| Pattern | Meaning |
|---|---|
| Slow blink | Idle: the phone keeps its own audio |
| Solid | Ready: the phone sends media to the board |
| Brightness follows the music | Casting |
| Fast blink | Audio was dropped in the last second |

## Building

```sh
export PICO_SDK_PATH=/path/to/pico-sdk   # 2.1.x, with lib/tinyusb
cmake -S firmware -B build -G Ninja      # add -DPICO_BOARD=pico2 for the RP2350
ninja -C build                           # -> build/aria_companion.uf2
make -C firmware/test                    # host-side unit tests
```

Copy the new `aria_companion.uf2` to `app/src/main/assets/companion/`, regenerate
`aria_companion.uf2.sha256` with `sha256sum`, and bump `version.txt`.

## Wireless: AriaCompanion BT (ESP32)

A second firmware, in [firmware-bt/](firmware-bt/), turns a single **classic ESP32**
(ESP32-WROOM-32 / WROVER; S2/S3/C3/C6 lack Classic Bluetooth) into the same thing without the
cable. The phone sees an ordinary **Bluetooth speaker**; the board forwards the SBC audio it
receives, untouched, back to AriaCast over a Bluetooth serial (SPP) channel. It registers no
hands-free profile, so calls never go to it, and the app sends it back to idle during a call.
See [docs/ariacompanion-bt.md](../docs/ariacompanion-bt.md).

1. Build and flash from a computer (ESP-IDF 5.5 or newer):
   ```sh
   . $IDF_PATH/export.sh
   idf.py -C AriaCompanion/firmware-bt set-target esp32
   idf.py -C AriaCompanion/firmware-bt build flash monitor
   ```
   CI also publishes the binaries as the `aria_companion_bt` artifact.
2. On the phone, pair **AriaCompanion BT** in Android's Bluetooth settings. Android will try to
   use it as a speaker straight away; the board hangs up until AriaCast asks for it.
3. In AriaCast, **Settings → AriaCompanion → Bluetooth board**, allow Bluetooth access and pick
   the board. The **AriaCompanion** source then appears on the Send screen.

The LED on GPIO 2: slow blink idle, double blink linked to the app, solid while it is the
phone's speaker, fast blink when frames are dropped.

## Version 1 (two ESP32 boards)

The Bluetooth + Wi-Fi bridge from AriaCast 1.x is in [legacy/](legacy/). It no longer receives fixes.
