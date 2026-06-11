#!/usr/bin/env bash
# AriaCompanion — Raspberry Pi Zero W / Zero 2W setup
#
# Flash Raspberry Pi OS Lite (32-bit for Zero W, 64-bit for Zero 2W).
# Enable SSH and set up WiFi in the Imager, then:
#   scp setup.sh pi@<ip>:~/ && ssh pi@<ip> sudo bash setup.sh
#
# After the script finishes, the Pi reboots.
# Pair your phone's BT to "AriaCompanion" and go.
set -euo pipefail

DAEMON_DIR=/opt/aria-companion
SERVICE_USER=pi

# ── Root check ────────────────────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo bash setup.sh" >&2
    exit 1
fi

echo "=== AriaCompanion setup ==="

# ── 1. System tweaks for a 2 GB card ─────────────────────────────────────────
echo "--- Tuning system for minimal footprint"

# GPU memory: headless needs only 16 MB
CONFIG=/boot/firmware/config.txt          # Bookworm path
[[ -f /boot/config.txt ]] && CONFIG=/boot/config.txt   # Bullseye fallback

grep -qF "gpu_mem=16" "$CONFIG" || echo "gpu_mem=16" >> "$CONFIG"

# Disable swap (saves writes + ~100 MB of SD)
systemctl disable --now dphys-swapfile 2>/dev/null || true
dphys-swapfile swapoff 2>/dev/null || true

# ── 2. Packages ───────────────────────────────────────────────────────────────
echo "--- Installing packages"
apt-get update -qq
apt-get install -y --no-install-recommends \
    bluez \
    bluez-alsa-utils \
    alsa-utils \
    avahi-daemon \
    python3 \
    python3-dbus \
    python3-gi
apt-get clean
rm -rf /var/lib/apt/lists/*

# ── 3. Bluetooth device identity ─────────────────────────────────────────────
echo "--- Configuring Bluetooth"

# Friendly name shown during pairing
hostnamectl set-hostname AriaCompanion

cat > /etc/bluetooth/main.conf << 'EOF'
[General]
Name = AriaCompanion
# Audio Rendering / Loudspeaker device class
Class = 0x240408
DiscoverableTimeout = 0
PairableTimeout = 0

[Policy]
AutoEnable = true
ReconnectAttempts = 7
ReconnectIntervals = 1,2,4,8,16,32,64
EOF

# ── 4. bluealsa — A2DP sink only ─────────────────────────────────────────────
# Override the default bluealsa service to run only the a2dp-sink profile.
mkdir -p /etc/systemd/system/bluealsa.service.d
cat > /etc/systemd/system/bluealsa.service.d/override.conf << 'EOF'
[Service]
ExecStart=
ExecStart=/usr/bin/bluealsa -p a2dp-sink
EOF

# ── 5. avahi mDNS service record ─────────────────────────────────────────────
# AriaCast discovers the device via _ariacompanion._tcp
cat > /etc/avahi/services/ariacompanion.service << 'EOF'
<?xml version="1.0" standalone="no"?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name>AriaCompanion</name>
  <service>
    <type>_ariacompanion._tcp</type>
    <port>7001</port>
    <txt-record>sample_rate=44100</txt-record>
    <txt-record>channels=2</txt-record>
    <txt-record>bits=16</txt-record>
  </service>
</service-group>
EOF

# ── 6. Install daemon scripts ─────────────────────────────────────────────────
echo "--- Installing AriaCompanion daemons"

mkdir -p "$DAEMON_DIR"

# Copy companion files — handle both repo checkout and standalone script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$SCRIPT_DIR/aria-companion.py" ]]; then
    cp "$SCRIPT_DIR/aria-companion.py" "$DAEMON_DIR/"
    cp "$SCRIPT_DIR/bt-agent.py"       "$DAEMON_DIR/"
else
    # Embedded fallback: download from the committed path
    echo "WARN: companion scripts not found next to setup.sh; writing embedded copies"

    cat > "$DAEMON_DIR/bt-agent.py" << 'PYEOF'
#!/usr/bin/env python3
import sys, dbus, dbus.service, dbus.mainloop.glib
from gi.repository import GLib
AGENT_PATH = "/aria/agent"
class Agent(dbus.service.Object):
    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid): pass
    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
    def RequestPinCode(self, device): return "0000"
    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device): return dbus.UInt32(0)
    @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered): pass
    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode): pass
    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey): pass
    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device): pass
    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self): pass
dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SystemBus()
agent = Agent(bus, AGENT_PATH)
mgr = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")
mgr.RegisterAgent(AGENT_PATH, "NoInputNoOutput")
mgr.RequestDefaultAgent(AGENT_PATH)
props = dbus.Interface(bus.get_object("org.bluez", "/org/bluez/hci0"), "org.freedesktop.DBus.Properties")
for k, v in [("Discoverable", dbus.Boolean(True)), ("Pairable", dbus.Boolean(True)),
             ("DiscoverableTimeout", dbus.UInt32(0)), ("PairableTimeout", dbus.UInt32(0))]:
    try: props.Set("org.bluez.Adapter1", k, v)
    except: pass
print("BT agent running")
GLib.MainLoop().run()
PYEOF

    cat > "$DAEMON_DIR/aria-companion.py" << 'PYEOF'
#!/usr/bin/env python3
import socket, subprocess, threading, time, logging, sys
TCP_PORT = 7001
CHUNK = 4096
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", handlers=[logging.StreamHandler(sys.stdout)])
log = logging.getLogger(__name__)
class _Hub:
    def __init__(self):
        self._lock = threading.Lock(); self._clients = set()
    def add(self, s):
        with self._lock: self._clients.add(s)
    def broadcast(self, data):
        with self._lock:
            dead = set()
            for c in self._clients:
                try: c.sendall(data)
                except: dead.add(c)
            for c in dead:
                self._clients.discard(c)
                try: c.close()
                except: pass
hub = _Hub()
def _tcp():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", TCP_PORT)); s.listen(8); log.info("TCP :%d", TCP_PORT)
    while True:
        try:
            c, _ = s.accept(); c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1); hub.add(c)
        except Exception as e: log.error(e); time.sleep(1)
def _mac():
    try:
        o = subprocess.check_output(["bluealsa-cli","list-pcms"],stderr=subprocess.DEVNULL,timeout=3).decode()
        for l in o.splitlines():
            if "/a2dpsnk/" in l or "/a2dpsrc/" in l:
                for s in l.split("/"):
                    if s.startswith("dev_"): return s[4:].replace("_",":")
    except: pass
    try:
        o = subprocess.check_output(["bluetoothctl","devices","Connected"],stderr=subprocess.DEVNULL,timeout=3).decode()
        for l in o.splitlines():
            p = l.split()
            if len(p)>=2 and p[0]=="Device": return p[1]
    except: pass
    return None
def _main():
    proc = active = None
    while True:
        mac = _mac()
        if mac and (proc is None or proc.poll() is not None):
            if proc: proc.terminate()
            active = mac
            dev = f"bluealsa:DEV={mac},PROFILE=a2dp,SRV=org.bluealsa"
            log.info("Capturing %s", mac)
            try:
                proc = subprocess.Popen(["arecord","-D",dev,"-r","44100","-c","2","-f","S16_LE","-t","raw","--buffer-size=8192"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                def rd(p):
                    while True:
                        d = p.stdout.read(CHUNK)
                        if not d: break
                        hub.broadcast(d)
                threading.Thread(target=rd,args=(proc,),daemon=True).start()
            except Exception as e: log.error(e); proc = None
        elif not mac and proc:
            proc.terminate(); proc = active = None
        time.sleep(2)
threading.Thread(target=_tcp, daemon=True).start()
_main()
PYEOF
fi

chmod +x "$DAEMON_DIR/aria-companion.py" "$DAEMON_DIR/bt-agent.py"

# ── 7. systemd services ───────────────────────────────────────────────────────
cat > /etc/systemd/system/aria-bt-agent.service << EOF
[Unit]
Description=AriaCompanion Bluetooth pairing agent
After=bluetooth.service
Requires=bluetooth.service

[Service]
ExecStart=/usr/bin/python3 $DAEMON_DIR/bt-agent.py
Restart=on-failure
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/aria-companion.service << EOF
[Unit]
Description=AriaCompanion PCM streaming daemon
After=bluealsa.service aria-bt-agent.service
Requires=bluealsa.service

[Service]
ExecStart=/usr/bin/python3 $DAEMON_DIR/aria-companion.py
Restart=always
RestartSec=3
User=$SERVICE_USER
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# ── 8. Enable everything ──────────────────────────────────────────────────────
echo "--- Enabling services"
systemctl daemon-reload
systemctl enable bluetooth bluealsa avahi-daemon aria-bt-agent aria-companion

# ── 9. Done ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Setup complete ==="
echo "Rebooting in 5 s…  (Ctrl-C to cancel)"
sleep 5
reboot
