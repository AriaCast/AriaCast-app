# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Three overlapping groups, all first-class (confirmed):

- **Home-audio enthusiasts** with a mixed fleet of speakers (AirPlay, Google Cast, DLNA, Snapcast, Music Assistant) who want them to play together.
- **Everyday listeners** who just want the phone's music on a speaker and do not know or care what a protocol is.
- **Self-hosters and tinkerers** (Home Assistant, Music Assistant, Tasker, JavaScript plugins) who want full control and diagnostic tools.

## Product Purpose

AriaCast sends any audio the device plays (other apps, microphone/USB line-in, the AriaCompanion USB board, or audio it is itself receiving) to networked speakers, and since 2.0 can also turn the device into a speaker (AriaCast receiver, DLNA renderer, Sendspin player) or a bridge that forwards what it receives. Success is audio coming out of the right speakers with one or two taps, and staying there.

## Positioning

One app that speaks every home-audio protocol in both directions (send and receive, plus bridging), with system-wide capture, a USB loopback board for apps that block capture, and an open plugin API. No neighbouring app covers AirPlay 1/2, Cast, DLNA, Snapcast and Sendspin from a single Android device.

## Operating Context

- **The primary task depends on the device** (confirmed): on a phone it is picking speakers and starting/controlling a cast; on a tablet or Android TV it is often running as a receiver (full-screen Receive view with clock, artwork, QR code), driven by remote/D-pad.
- Used in short bursts while music is playing elsewhere in the home; the phone is a remote, not a destination.
- Entry points outside the app: Quick Settings tiles, home-screen widget, notification, NFC tags, automation intents, web remote.

## Capabilities and Constraints

- Jetpack Compose UI (main flows) plus legacy View/XML activities (Settings, Protocols, AirPlay PINs, NFC writer, Packet log, Plugins) that the redesign brings into the same world.
- Must work on phones, tablets and Android TV (`AppController.isTv`).
- Strings are localized (at least English and Italian); existing string resources are reused where meaning is unchanged.
- The accent-color setting is being retired; color comes from Material You (dynamic color from the wallpaper on Android 12+, static fallback below).
- Terminology: Send / Receive, speakers, groups, sources, AriaCompanion, Connection doctor, Now Playing.

## Brand Commitments

- Name: AriaCast; companion hardware: AriaCompanion.
- Color is governed by the user's wallpaper (Material You) — the app does not impose a fixed brand palette (confirmed).
- **Standing visual preference: the category standard, executed at full craft** (chosen 2026-11-14 over bespoke directions). Material 3 conventions are embraced without irony or smuggled quirks; the quality bar is Google Home (devices, rooms, media control) and Apple Music (now-playing, artwork, mini-player).

## Evidence on Hand

- README and CHANGELOG describe the full feature set; no testimonials, ratings, or usage numbers are on hand and none may be invented.

## Product Principles

1. **One tap to sound.** The most common action on each device class is reachable from the first screen without menus.
2. **Power without clutter.** Advanced features (multiroom sync, groups, bridge, Sendspin, plugins, diagnostics) stay discoverable, but never crowd the everyday path.
3. **A tool, not a toy** (confirmed): no decoration, novelty motion, or consumer-app gimmicks that slow the task down.
4. **State is always legible.** What is playing, where, and whether something is wrong must be readable at a glance.
5. **Every device is both ends.** Sending and receiving are peers in the information architecture, weighted by device class.

## Accessibility & Inclusion

Follow Material 3 / Android requirements: 48dp touch targets, sp type that honours font scale, full D-pad/remote focus on TV, dark theme as a first-class scheme. No product-specific standard beyond that was established.
