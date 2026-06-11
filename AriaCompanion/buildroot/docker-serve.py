#!/usr/bin/env python3
"""
Minimal HTTP file server for built AriaCompanion images.
Serves a download page on port 8040 with file sizes and correct
Content-Disposition headers so browsers save with the right filename.

Usage (called by docker-entrypoint.sh):
    python3 docker-serve.py /build/serve
"""
import http.server
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

PORT = 8040

BOARD_LABELS = {
    "ariacompanion-pizero2w.img": ("Pi Zero 2W", "ARMv8 · 64-bit · Cortex-A53", "#1a73e8"),
    "ariacompanion-pizerow.img":  ("Pi Zero W",  "ARMv6 · 32-bit · ARM1176",    "#34a853"),
}

_CSS = """
  * { box-sizing: border-box; margin: 0; padding: 0 }
  body { font-family: system-ui, sans-serif; background: #f0f2f5;
         min-height: 100vh; display: flex; align-items: flex-start;
         justify-content: center; padding: 40px 16px }
  .card { background: white; border-radius: 12px; padding: 32px;
          max-width: 560px; width: 100%;
          box-shadow: 0 1px 3px rgba(0,0,0,.12) }
  h1 { font-size: 22px; color: #202124; margin-bottom: 4px }
  .sub { color: #5f6368; font-size: 14px; margin-bottom: 28px }
  .item { border: 1px solid #e0e0e0; border-radius: 8px;
          padding: 18px 20px; margin-bottom: 14px;
          display: flex; align-items: center; gap: 16px }
  .dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0 }
  .info { flex: 1 }
  .name { font-weight: 600; color: #202124 }
  .desc { font-size: 13px; color: #5f6368; margin-top: 2px }
  .size { font-size: 13px; color: #5f6368; margin-top: 2px }
  a.btn { display: inline-block; padding: 8px 18px; border-radius: 6px;
          background: #1a73e8; color: white; text-decoration: none;
          font-size: 14px; font-weight: 500; white-space: nowrap }
  a.btn:hover { background: #1557b0 }
  .none { color: #c5221f; font-size: 15px; padding: 12px 0 }
  .footer { font-size: 12px; color: #9aa0a6; margin-top: 24px; text-align: center }
  hr { border: none; border-top: 1px solid #e0e0e0; margin: 20px 0 }
  .flash { background:#e6f4ea; border-radius:8px; padding:14px 16px;
           font-size:13px; color:#137333; margin-top:20px }
  code { background:#f1f3f4; padding:2px 6px; border-radius:4px;
         font-family:monospace; font-size:12px }
"""

def fmt_size(path: Path) -> str:
    b = path.stat().st_size
    for unit in ("B", "KB", "MB", "GB"):
        if b < 1024:
            return f"{b:.0f} {unit}"
        b /= 1024
    return f"{b:.1f} GB"

def make_index(serve_dir: Path) -> str:
    images = sorted(serve_dir.glob("*.img"))
    built_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    items_html = ""
    if not images:
        items_html = '<p class="none">No images found.</p>'
    else:
        for img in images:
            label, desc, color = BOARD_LABELS.get(
                img.name, (img.stem, "", "#757575")
            )
            size = fmt_size(img)
            items_html += f"""
            <div class="item">
              <div class="dot" style="background:{color}"></div>
              <div class="info">
                <div class="name">{label}</div>
                <div class="desc">{desc}</div>
                <div class="size">{img.name} &nbsp;·&nbsp; {size}</div>
              </div>
              <a class="btn" href="/{img.name}" download="{img.name}">Download</a>
            </div>"""

    return f"""<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AriaCompanion — Built Images</title>
<style>{_CSS}</style>
</head><body>
<div class="card">
  <h1>AriaCompanion</h1>
  <p class="sub">Built {built_at}</p>
  {items_html}
  <hr>
  <div class="flash">
    Flash with:
    <br><br>
    <code>sudo dd if=ariacompanion-pizero2w.img of=/dev/sdX bs=4M status=progress</code>
    <br><br>
    Or open the file in <strong>Raspberry Pi Imager</strong> → Use custom image.
  </div>
  <p class="footer">AriaCompanion — Bluetooth A2DP → TCP bridge for AriaCast</p>
</div>
</body></html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    serve_dir: Path

    server_version = "AriaCompanion/1"

    def log_message(self, fmt, *args):
        print(f"  {self.address_string()}  {fmt % args}", flush=True)

    def do_GET(self):
        path = self.path.split("?")[0].lstrip("/")

        # Index
        if path == "" or path == "index.html":
            body = make_index(self.serve_dir).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", len(body))
            self.end_headers()
            self.wfile.write(body)
            return

        # File download
        target = self.serve_dir / path
        if target.suffix == ".img" and target.is_file():
            size = target.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Disposition", f'attachment; filename="{target.name}"')
            self.send_header("Content-Length", size)
            self.end_headers()
            with open(target, "rb") as f:
                while chunk := f.read(256 * 1024):
                    self.wfile.write(chunk)
            return

        self.send_error(404)


if __name__ == "__main__":
    serve_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    Handler.serve_dir = serve_dir

    with http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler) as srv:
        print(f"Serving {serve_dir}  →  http://localhost:{PORT}", flush=True)
        srv.serve_forever()
