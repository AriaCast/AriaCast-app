#!/usr/bin/env python3
"""
AriaCompanion streaming daemon for Raspberry Pi Zero W/2W.

Waits for a Bluetooth A2DP source connection, captures raw PCM via the
bluealsa ALSA plugin, and streams it to any TCP client on port 7001.

Audio format: 44100 Hz, stereo, 16-bit little-endian  (AriaCast upsamples to 48 kHz)
"""
import os
import socket
import subprocess
import threading
import time
import logging
import sys

TCP_PORT = 7001
CHUNK    = 4096   # ~11 ms of audio per TCP write


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger(__name__)


# ── TCP client hub ────────────────────────────────────────────────────────────

class _Hub:
    def __init__(self):
        self._lock    = threading.Lock()
        self._clients = set()

    def add(self, sock):
        with self._lock:
            self._clients.add(sock)
        log.info("TCP client connected (%d total)", len(self._clients))

    def broadcast(self, data: bytes):
        with self._lock:
            dead = set()
            for c in self._clients:
                try:
                    c.sendall(data)
                except Exception:
                    dead.add(c)
            for c in dead:
                self._clients.discard(c)
                try:    c.close()
                except: pass
            if dead:
                log.info("Dropped %d dead client(s)", len(dead))

hub = _Hub()


def _tcp_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", TCP_PORT))
    srv.listen(8)
    log.info("TCP listening on :%d", TCP_PORT)
    while True:
        try:
            client, _ = srv.accept()
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            hub.add(client)
        except Exception as e:
            log.error("accept error: %s", e)
            time.sleep(1)


# ── Bluetooth device detection ────────────────────────────────────────────────

def _connected_a2dp_mac() -> str | None:
    """Return the MAC of the first connected A2DP source, or None."""

    # Preferred: bluealsa-cli (part of bluealsa-utils)
    try:
        out = subprocess.check_output(
            ["bluealsa-cli", "list-pcms"], stderr=subprocess.DEVNULL, timeout=3
        ).decode()
        for line in out.splitlines():
            # path: /org/bluealsa/hci0/dev_AA_BB_CC_DD_EE_FF/a2dpsnk/sink
            if "/a2dpsnk/" in line or "/a2dpsrc/" in line:
                for seg in line.split("/"):
                    if seg.startswith("dev_"):
                        return seg[4:].replace("_", ":")
    except FileNotFoundError:
        pass
    except Exception as e:
        log.debug("bluealsa-cli: %s", e)

    # Fallback: bluetoothctl (available everywhere)
    try:
        out = subprocess.check_output(
            ["bluetoothctl", "devices", "Connected"],
            stderr=subprocess.DEVNULL, timeout=3
        ).decode()
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0] == "Device":
                return parts[1]
    except Exception as e:
        log.debug("bluetoothctl: %s", e)

    return None


# ── Capture loop ──────────────────────────────────────────────────────────────

def _read_proc(proc: subprocess.Popen):
    """Read arecord stdout and broadcast to all TCP clients."""
    try:
        while True:
            data = proc.stdout.read(CHUNK)
            if not data:
                break
            hub.broadcast(data)
    except Exception as e:
        log.error("read error: %s", e)
    log.info("arecord stdout closed")


def _capture_loop():
    proc        = None
    active_mac  = None

    while True:
        mac = _connected_a2dp_mac()

        if mac:
            if mac != active_mac or proc is None or proc.poll() is not None:
                # Kill previous capture if any
                if proc is not None:
                    try:    proc.terminate(); proc.wait(timeout=2)
                    except: pass

                active_mac = mac
                device = f"bluealsa:DEV={mac},PROFILE=a2dp,SRV=org.bluealsa"
                log.info("Starting capture from %s", mac)
                try:
                    proc = subprocess.Popen(
                        [
                            "arecord",
                            "-D", device,
                            "-r", "44100",
                            "-c", "2",
                            "-f", "S16_LE",
                            "-t", "raw",
                            "--buffer-size=8192",
                        ],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL,
                    )
                    threading.Thread(target=_read_proc, args=(proc,), daemon=True).start()
                except Exception as e:
                    log.error("arecord failed: %s", e)
                    proc = None

        elif proc is not None:
            log.info("BT disconnected — stopping capture")
            try:    proc.terminate(); proc.wait(timeout=2)
            except: pass
            proc       = None
            active_mac = None

        time.sleep(2)


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    threading.Thread(target=_tcp_server, daemon=True).start()
    _capture_loop()
