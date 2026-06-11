#!/usr/bin/env bash
# AriaCompanion Linux build script
#
# Builds a minimal Linux image for Raspberry Pi Zero W (ARMv6, 32-bit) or
# Pi Zero 2W (ARMv8, 64-bit) that receives Bluetooth A2DP audio and streams
# raw PCM over TCP port 7001 to the AriaCast Android app.
#
# Usage:
#   ./build.sh pizerow      # Pi Zero W  (ARMv6 32-bit)
#   ./build.sh pizero2w     # Pi Zero 2W (ARMv8 64-bit)
#   ./build.sh both         # build both (sequentially)
#
# Prerequisites (Debian/Ubuntu):
#   sudo apt-get install -y build-essential git wget cpio rsync bc \
#        python3 unzip file libssl-dev libelf-dev
#
# Output: output-<target>/images/sdcard.img  (~120 MB, flash with dd or Etcher)
#
# WiFi credentials:
#   Edit board/rootfs_overlay/etc/wpa_supplicant/wpa_supplicant.conf BEFORE building,
#   or edit the file on the SD boot partition after flashing.
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
BUILDROOT_VERSION="2024.02.6"
BUILDROOT_URL="https://buildroot.org/downloads/buildroot-${BUILDROOT_VERSION}.tar.gz"
BUILDROOT_DIR="$(pwd)/buildroot-${BUILDROOT_VERSION}"

EXT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this directory

TARGETS=()
case "${1:-both}" in
    pizerow)   TARGETS=(pizerow)   ;;
    pizero2w)  TARGETS=(pizero2w)  ;;
    both)      TARGETS=(pizerow pizero2w) ;;
    *)
        echo "Usage: $0 [pizerow|pizero2w|both]" >&2
        exit 1
        ;;
esac

# ── Download Buildroot ─────────────────────────────────────────────────────────
if [[ ! -d "$BUILDROOT_DIR" ]]; then
    echo "==> Downloading Buildroot ${BUILDROOT_VERSION}…"
    wget -q --show-progress -O "/tmp/buildroot.tar.gz" "$BUILDROOT_URL"
    echo "==> Extracting…"
    tar -xf /tmp/buildroot.tar.gz -C "$(dirname "$BUILDROOT_DIR")"
    rm /tmp/buildroot.tar.gz
fi

# ── Build each target ─────────────────────────────────────────────────────────
for TARGET in "${TARGETS[@]}"; do
    OUT_DIR="${EXT_DIR}/output-${TARGET}"
    DEFCONFIG="${EXT_DIR}/configs/${TARGET}_defconfig"

    echo ""
    echo "════════════════════════════════════════════════════"
    echo "  Building: ${TARGET}  →  ${OUT_DIR}/images/sdcard.img"
    echo "════════════════════════════════════════════════════"

    mkdir -p "$OUT_DIR"

    # Load defconfig (BR2_EXTERNAL allows our configs/ and board/ to be found)
    make -C "$BUILDROOT_DIR" \
        BR2_EXTERNAL="$EXT_DIR" \
        O="$OUT_DIR" \
        "${TARGET}_defconfig"

    # Build (uses all available cores)
    make -C "$BUILDROOT_DIR" \
        BR2_EXTERNAL="$EXT_DIR" \
        O="$OUT_DIR" \
        -j"$(nproc)"

    echo ""
    echo "==> Done: ${OUT_DIR}/images/sdcard.img"
    echo "    Flash with: sudo dd if=${OUT_DIR}/images/sdcard.img of=/dev/sdX bs=4M status=progress"
done
