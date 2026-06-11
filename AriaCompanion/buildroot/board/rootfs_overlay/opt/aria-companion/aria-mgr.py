#!/usr/bin/env python3
"""
AriaCompanion management HTTP server — port 80.

Setup mode  (wlan0 in AP, /run/aria-mode == "setup"):
  GET  /*          →  302 to /             (captive-portal probe redirect)
  GET  /           →  WiFi config form
  POST /wifi       →  save credentials, reboot
  GET  /status     →  JSON {"mode":"setup"}

Normal mode (/run/aria-mode == "normal"):
  GET  /           →  status + OTA upload form
  GET  /status     →  JSON status
  POST /update     →  multipart binary upload → replace /usr/bin/aria-companion
  POST /reset      →  clear WiFi creds, reboot to setup mode
"""
import http.server
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

# ── Constants ─────────────────────────────────────────────────────────────────
PORT           = 80
MODE_FILE      = Path("/run/aria-mode")
WPA_CONF       = Path("/etc/wpa_supplicant/wpa_supplicant.conf")
SETUP_MARKER   = Path("/etc/aria-setup-needed")
ARIA_BIN       = Path("/usr/bin/aria-companion")
ARIA_PIDFILE   = Path("/var/run/aria-companion.pid")
MAX_UPLOAD     = 8 * 1024 * 1024   # 8 MB — plenty for an ARM ELF

# ── HTML pages ─────────────────────────────────────────────────────────────────
_CSS = """
  body{font-family:system-ui,sans-serif;max-width:440px;margin:40px auto;padding:0 18px}
  h1{color:#1a73e8;margin-bottom:4px}sub{color:#888;font-size:13px}
  .card{background:#f8f9fa;border-radius:10px;padding:18px;margin:14px 0}
  .ok{background:#e6f4ea;color:#137333}.err{background:#fce8e6;color:#c5221f}
  input{width:100%;padding:10px;margin:7px 0;box-sizing:border-box;
        border:1px solid #ccc;border-radius:6px;font-size:16px}
  button{width:100%;padding:12px;border:none;border-radius:6px;
         font-size:16px;cursor:pointer;color:#fff;background:#1a73e8}
  .red{background:#c5221f;margin-top:10px}
  hr{border:none;border-top:1px solid #e0e0e0;margin:22px 0}
"""

SETUP_HTML = f"""<!DOCTYPE html><html><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AriaCompanion Setup</title><style>{_CSS}</style></head><body>
<h1>AriaCompanion</h1><sub>First-time setup</sub>
<div class="card">
  <p>Connect this device to your home Wi-Fi so AriaCast can reach it.</p>
  <form method="POST" action="/wifi">
    <input type="text"     name="ssid" placeholder="Wi-Fi name (SSID)" required autocomplete="off">
    <input type="password" name="pass" placeholder="Password (leave blank if open)" autocomplete="off">
    <button type="submit">Connect &amp; Reboot</button>
  </form>
</div>
<p style="color:#666;font-size:14px">After rebooting, pair your phone's Bluetooth to
<strong>AriaCompanion</strong> and enable it in AriaCast → Settings → AriaCompanion.</p>
</body></html>"""

SAVED_HTML = """<!DOCTYPE html><html><head>
<meta charset="utf-8"><title>AriaCompanion</title>
<style>body{font-family:system-ui,sans-serif;max-width:440px;margin:60px auto;
text-align:center;padding:0 18px}.ok{color:#137333}</style></head><body>
<h1 class="ok">&#10003; Saved — rebooting</h1>
<p>The device will join your Wi-Fi and be discoverable in the AriaCast app.</p>
</body></html>"""

UPDATE_OK_HTML = """<!DOCTYPE html><html><head>
<meta charset="utf-8"><meta http-equiv="refresh" content="4;url=/">
<title>AriaCompanion</title>
<style>body{font-family:system-ui,sans-serif;max-width:440px;margin:60px auto;
text-align:center;padding:0 18px}.ok{color:#137333}</style></head><body>
<h1 class="ok">&#10003; Updated</h1><p>Binary replaced. Restarting daemon…</p>
<p style="color:#888;font-size:14px">Redirecting in 4 s.</p>
</body></html>"""


def status_html(bt: bool, streaming: bool) -> str:
    def chip(ok, label):
        cls = "ok" if ok else "err"
        sym = "&#9679;" if ok else "&#9675;"
        return f'<div class="card {cls}">{sym} {label}</div>'

    return f"""<!DOCTYPE html><html><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AriaCompanion</title><style>{_CSS}</style></head><body>
<h1>AriaCompanion</h1><sub>normal mode</sub>
{chip(bt, "Bluetooth adapter powered")}
{chip(streaming, "A2DP device streaming")}
<hr>
<div class="card">
  <strong>OTA update</strong>
  <p style="font-size:14px;color:#555">Build <code>aria-companion</code> from source,
  then upload the binary here. The daemon restarts automatically.</p>
  <form method="POST" action="/update" enctype="multipart/form-data">
    <input type="file" name="bin" accept="application/octet-stream" required>
    <button type="submit">Upload &amp; Restart</button>
  </form>
</div>
<hr>
<form method="POST" action="/reset">
  <button class="red" type="submit"
    onclick="return confirm('Reset Wi-Fi and return to setup mode?')">
    Reset Wi-Fi credentials
  </button>
</form>
</body></html>"""


# ── Helpers ────────────────────────────────────────────────────────────────────

def get_mode() -> str:
    try:
        return MODE_FILE.read_text().strip()
    except Exception:
        return "normal"


def bt_powered() -> bool:
    try:
        out = subprocess.check_output(
            ["bluetoothctl", "show"], stderr=subprocess.DEVNULL, timeout=3
        ).decode()
        return "Powered: yes" in out
    except Exception:
        return False


def a2dp_streaming() -> bool:
    try:
        out = subprocess.check_output(
            ["bluealsa-cli", "list-pcms"], stderr=subprocess.DEVNULL, timeout=3
        ).decode()
        return any("/a2dpsnk/" in l or "/a2dpsrc/" in l for l in out.splitlines())
    except Exception:
        return False


def parse_multipart(body: bytes, boundary: str) -> dict:
    """Minimal multipart/form-data parser — returns {name: bytes}."""
    sep  = b"--" + boundary.encode()
    parts, result = body.split(sep), {}
    for part in parts[1:]:
        if part.startswith(b"--"):
            break
        try:
            hdr_end = part.index(b"\r\n\r\n")
        except ValueError:
            continue
        headers_raw = part[2:hdr_end]
        payload     = part[hdr_end + 4:]
        if payload.endswith(b"\r\n"):
            payload = payload[:-2]
        name = None
        for line in headers_raw.split(b"\r\n"):
            if b"content-disposition" in line.lower():
                for seg in line.split(b";"):
                    seg = seg.strip()
                    if seg.lower().startswith(b"name="):
                        name = seg[5:].strip(b'"').decode()
        if name:
            result[name] = payload
    return result


def restart_aria_companion():
    try:
        pid = int(ARIA_PIDFILE.read_text().strip())
        os.kill(pid, signal.SIGTERM)
    except Exception:
        subprocess.run(["killall", "aria-companion"], check=False)


def reboot_system():
    threading.Thread(target=lambda: (time.sleep(1), os.system("reboot")),
                     daemon=True).start()


def save_wpa_credentials(ssid: str, password: str):
    country = "IT"
    auth    = "NONE" if not password else "WPA-PSK"
    psk     = f'\n    psk="{password}"' if password else ""

    WPA_CONF.write_text(
        f"ctrl_interface=/var/run/wpa_supplicant\n"
        f"ctrl_interface_group=0\n"
        f"update_config=1\n"
        f"country={country}\n\n"
        f"network={{\n"
        f'    ssid="{ssid}"\n'
        f"    key_mgmt={auth}{psk}\n"
        f"    priority=1\n"
        f"}}\n"
    )
    SETUP_MARKER.unlink(missing_ok=True)


# ── HTTP handler ───────────────────────────────────────────────────────────────

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "AriaCompanion/1"
    error_content_type = "text/html"

    def log_message(self, fmt, *args):
        print(f"HTTP {self.address_string()} {fmt % args}", flush=True)

    # ── helpers ──────────────────────────────────────────────────────────────

    def send_html(self, html: str, code: int = 200):
        body = html.encode()
        self.send_response(code)
        self.send_header("Content-Type",   "text/html; charset=utf-8")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, data: dict, code: int = 200):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type",   "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def redirect(self, url: str):
        self.send_response(302)
        self.send_header("Location", url)
        self.end_headers()

    def read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", 0))
        if length > MAX_UPLOAD:
            raise ValueError(f"body too large: {length}")
        return self.rfile.read(length)

    def parse_form(self, body: bytes) -> dict:
        from urllib.parse import parse_qs, unquote_plus
        raw = {k: v[0] for k, v in parse_qs(body.decode()).items()}
        return {k: unquote_plus(v) for k, v in raw.items()}

    # ── GET ───────────────────────────────────────────────────────────────────

    def do_GET(self):
        mode = get_mode()
        path = self.path.split("?")[0]

        if path == "/status":
            if mode == "setup":
                self.send_json({"mode": "setup"})
            else:
                self.send_json({
                    "mode":      "normal",
                    "bt":        bt_powered(),
                    "streaming": a2dp_streaming(),
                })
            return

        if mode == "setup":
            if path == "/":
                self.send_html(SETUP_HTML)
            else:
                # Captive portal: redirect everything (Android / iOS probes)
                self.redirect("/")
        else:
            if path == "/":
                self.send_html(status_html(bt_powered(), a2dp_streaming()))
            else:
                self.send_error(404)

    # ── POST ──────────────────────────────────────────────────────────────────

    def do_POST(self):
        mode = get_mode()
        path = self.path.split("?")[0]

        # /wifi — save credentials (setup mode only)
        if path == "/wifi":
            try:
                body  = self.read_body()
                form  = self.parse_form(body)
                ssid  = form.get("ssid", "").strip()
                passwd = form.get("pass", "")
                if not ssid:
                    self.send_html("<h1>SSID required</h1>", 400)
                    return
                save_wpa_credentials(ssid, passwd)
                self.send_html(SAVED_HTML)
                reboot_system()
            except Exception as e:
                self.send_html(f"<h1>Error: {e}</h1>", 500)
            return

        # /update — OTA binary upload (normal mode only)
        if path == "/update":
            if mode != "normal":
                self.send_html("<h1>Only available in normal mode</h1>", 403)
                return
            try:
                ct       = self.headers.get("Content-Type", "")
                body     = self.read_body()
                boundary = None
                for seg in ct.split(";"):
                    seg = seg.strip()
                    if seg.startswith("boundary="):
                        boundary = seg[9:].strip('"')
                if not boundary:
                    self.send_html("<h1>Bad Content-Type</h1>", 400)
                    return

                fields = parse_multipart(body, boundary)
                data   = fields.get("bin")
                if not data or len(data) < 4:
                    self.send_html("<h1>No binary received</h1>", 400)
                    return
                if data[:4] != b"\x7fELF":
                    self.send_html("<h1>Not a valid ELF binary</h1>", 400)
                    return

                tmp = Path("/tmp/aria-companion.new")
                tmp.write_bytes(data)
                os.chmod(tmp, 0o755)
                shutil.move(str(tmp), str(ARIA_BIN))

                self.send_html(UPDATE_OK_HTML)
                # Restart in background so response is sent first
                threading.Thread(target=lambda: (time.sleep(0.5),
                                                 restart_aria_companion()),
                                 daemon=True).start()
            except Exception as e:
                self.send_html(f"<h1>Update failed: {e}</h1>", 500)
            return

        # /reset — clear credentials, reboot to setup mode
        if path == "/reset":
            try:
                SETUP_MARKER.touch()
                WPA_CONF.write_text(
                    "ctrl_interface=/var/run/wpa_supplicant\n"
                    "ctrl_interface_group=0\n"
                    "update_config=1\n\n"
                    "# no network configured\n"
                )
                self.send_html(SAVED_HTML)
                reboot_system()
            except Exception as e:
                self.send_html(f"<h1>Reset failed: {e}</h1>", 500)
            return

        self.send_error(404)


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    if os.geteuid() != 0:
        print("aria-mgr requires root", file=sys.stderr)
        sys.exit(1)

    srv = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"aria-mgr: HTTP on :{PORT}  mode={get_mode()}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
