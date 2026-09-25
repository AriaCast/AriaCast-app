# AriaCast Context

AriaCast is an Android application that captures device system audio and streams it to LAN/cross-VLAN receivers via protocols including AirPlay 1/2, Snapcast, DLNA, and AriaCompanion.

## Language

### Plugin System

**Plugin**:
A modular add-on consisting of a `.json` manifest and a `.js` JavaScript script that extends AriaCast's UI or observes casting events.
_Avoid_: Extension, addon, scriptlet

**Plugin Sandbox**:
The two-tier security environment combining `PluginClassShutter` (class visibility whitelist/blacklist) and `PluginWrapFactory` (reflection entry-point shielding) that executes untrusted JavaScript safely in Mozilla Rhino.
_Avoid_: VM, isolate, container

**Plugin Capability Helper**:
A host-provided facade object injected into the plugin's execution scope (`ui`, `discovery`, `storage`, `events`, `ws`, `http`, `console`).
_Avoid_: Native bridge, raw binding

### Discovery & Networking

**Manual Server**:
A streaming destination entered manually with a host and port (typically for cross-VLAN, WireGuard, or Tailscale routes where mDNS/SSDP cannot cross subnet boundaries).
_Avoid_: Static target, custom IP

**Discovery Manager**:
The central subsystem responsible for discovering receivers via network broadcast (mDNS, SSDP, UDP) and managing manually-registered servers.
_Avoid_: Device scanner, server registry
