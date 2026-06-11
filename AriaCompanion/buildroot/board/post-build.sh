#!/usr/bin/env bash
# Buildroot post-build hook — runs after the rootfs is assembled but before
# the image is created.  $TARGET_DIR and $HOST_DIR are set by Buildroot.
set -euo pipefail

EXT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${EXT_DIR}/src/aria-companion.c"
BIN="${TARGET_DIR}/usr/bin/aria-companion"

# ── Compile the C streaming daemon with the Buildroot cross-compiler ──────────
echo "==> Compiling aria-companion"

# Detect cross-compiler prefix from the host directory
CC=$(find "${HOST_DIR}/bin" -name '*-gcc' ! -name '*++*' | head -1)
if [[ -z "$CC" ]]; then
    echo "ERROR: cross-compiler not found in ${HOST_DIR}/bin" >&2
    exit 1
fi

PKG_CONFIG="${HOST_DIR}/bin/pkg-config"
ALSA_CFLAGS=$(PKG_CONFIG_SYSROOT_DIR="${TARGET_DIR}" \
              PKG_CONFIG_LIBDIR="${TARGET_DIR}/usr/lib/pkgconfig" \
              "${PKG_CONFIG}" --cflags alsa 2>/dev/null || echo "-I${TARGET_DIR}/usr/include")
ALSA_LIBS=$(PKG_CONFIG_SYSROOT_DIR="${TARGET_DIR}" \
            PKG_CONFIG_LIBDIR="${TARGET_DIR}/usr/lib/pkgconfig" \
            "${PKG_CONFIG}" --libs alsa 2>/dev/null || echo "-lasound")

"$CC" -O2 -Wall \
    ${ALSA_CFLAGS} \
    "$SRC" \
    ${ALSA_LIBS} \
    -o "$BIN"

echo "==> Compiled: $BIN"

# ── config.txt — RPi bootloader config ───────────────────────────────────────
# Detect architecture to write the right config.txt
BOARD_CONFIG="${TARGET_DIR}/../images/rpi-firmware/config.txt"
mkdir -p "$(dirname "$BOARD_CONFIG")"

if echo "$CC" | grep -q "aarch64"; then
    # Pi Zero 2W — 64-bit
    cat > "$BOARD_CONFIG" << 'EOF'
arm_64bit=1
gpu_mem=16
dtparam=audio=off
dtparam=krnbt=on
EOF
    KERNEL_IMG="kernel8.img"
else
    # Pi Zero W — 32-bit
    cat > "$BOARD_CONFIG" << 'EOF'
arm_64bit=0
gpu_mem=16
dtparam=audio=off
enable_uart=1
dtoverlay=miniuart-bt
EOF
    KERNEL_IMG="kernel.img"
fi

# ── cmdline.txt ───────────────────────────────────────────────────────────────
cat > "${TARGET_DIR}/../images/rpi-firmware/cmdline.txt" << EOF
console=serial0,115200 root=/dev/mmcblk0p2 rootfstype=ext4 rootwait quiet
EOF

# ── dropbear host keys — generated fresh for each build ───────────────────────
mkdir -p "${TARGET_DIR}/etc/dropbear"
if [[ ! -f "${TARGET_DIR}/etc/dropbear/dropbear_ed25519_host_key" ]]; then
    if command -v dropbearkey &>/dev/null; then
        dropbearkey -t ed25519 -f "${TARGET_DIR}/etc/dropbear/dropbear_ed25519_host_key" 2>/dev/null || true
        dropbearkey -t rsa    -s 2048 -f "${TARGET_DIR}/etc/dropbear/dropbear_rsa_host_key" 2>/dev/null || true
    fi
fi

echo "==> post-build done"
