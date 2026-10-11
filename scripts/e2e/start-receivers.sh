#!/usr/bin/env bash
# Starts real AirPlay receivers for the end-to-end tests in
# app/src/test/java/com/aria/ariacast/e2e (run as root on Linux):
#
#   * shairport-sync (AirPlay 1)          127.0.0.1:5101   audio -> $OUT/raop.pcm
#   * shairport-sync, password "secret"   127.0.0.1:5102   audio -> $OUT/raop-pw.pcm
#   * shairport-sync built --with-airplay-2 + nqptp, in network namespace
#     "ap2e2e" (it needs PTP ports 319/320, which the test's PTP master also
#     binds)                               10.200.0.2:7000  audio -> $OUT/ap2.pcm
#
# Metadata is sent as UDP packets to 127.0.0.1:5601/5602 and 10.200.0.1:5603.
# Then run:  AIRPLAY_E2E=1 ./gradlew testDebugUnitTest --tests '*e2e*'
#
# Env: SPS1_BIN (default: shairport-sync on PATH), SPS2_BIN and NQPTP_BIN
# (AirPlay 2 build; that part is skipped when unset).
set -euo pipefail
OUT=${OUT:-/tmp/airplay-e2e}
SPS1_BIN=${SPS1_BIN:-$(command -v shairport-sync)}
mkdir -p "$OUT"

if ! pgrep -x avahi-daemon >/dev/null; then
  mkdir -p /run/dbus
  pgrep -x dbus-daemon >/dev/null || dbus-daemon --system --fork
  avahi-daemon -D --no-drop-root
  sleep 1
fi

raop_conf() { # name port udpbase metaport [password]
  local pw=""
  [[ -n "${5:-}" ]] && pw="password = \"$5\";"
  cat <<CONF
general = {
  name = "$1";
  port = $2;
  udp_port_base = $3;
  output_backend = "stdout";
  mdns_backend = "avahi";
  $pw
};
metadata = {
  enabled = "yes";
  include_cover_art = "yes";
  socket_address = "127.0.0.1";
  socket_port = $4;
  socket_msglength = 65000;
};
CONF
}

raop_conf "E2E RAOP" 5101 6101 5601 > "$OUT/raop.conf"
raop_conf "E2E RAOP PW" 5102 6201 5602 secret > "$OUT/raop-pw.conf"
"$SPS1_BIN" -c "$OUT/raop.conf" >> "$OUT/raop.pcm" 2> "$OUT/raop.log" &
echo $! > "$OUT/raop.pid"
"$SPS1_BIN" -c "$OUT/raop-pw.conf" >> "$OUT/raop-pw.pcm" 2> "$OUT/raop-pw.log" &
echo $! > "$OUT/raop-pw.pid"

if [[ -n "${SPS2_BIN:-}" && -n "${NQPTP_BIN:-}" ]]; then
  ip netns add ap2e2e 2>/dev/null || true
  if ! ip link show veth-ap2 >/dev/null 2>&1; then
    ip link add veth-ap2 type veth peer name veth-ap2r
    ip link set veth-ap2r netns ap2e2e
    ip addr add 10.200.0.1/24 dev veth-ap2
    ip link set veth-ap2 up
    ip netns exec ap2e2e ip addr add 10.200.0.2/24 dev veth-ap2r
    ip netns exec ap2e2e ip link set veth-ap2r up
    ip netns exec ap2e2e ip link set lo up
  fi
  cat > "$OUT/ap2.conf" <<CONF
general = {
  name = "E2E AP2";
  output_backend = "stdout";
  mdns_backend = "avahi";
};
metadata = {
  enabled = "yes";
  include_cover_art = "yes";
  socket_address = "10.200.0.1";
  socket_port = 5603;
  socket_msglength = 65000;
};
CONF
  ip netns exec ap2e2e "$NQPTP_BIN" > "$OUT/nqptp.log" 2>&1 &
  echo $! > "$OUT/nqptp.pid"
  sleep 1
  ip netns exec ap2e2e "$SPS2_BIN" -c "$OUT/ap2.conf" -vv >> "$OUT/ap2.pcm" 2> "$OUT/ap2.log" &
  echo $! > "$OUT/ap2.pid"
fi
sleep 2
echo "receivers up; logs in $OUT"
