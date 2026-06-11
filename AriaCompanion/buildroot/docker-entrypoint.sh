#!/usr/bin/env bash
# Docker entrypoint — builds the requested target(s) then serves the images.
set -euo pipefail

TARGET="${1:-both}"
ARIACOMP_DIR="/build/ariacomp"
SERVE_DIR="/build/serve"
DL_CACHE="/build/dl-cache"
BR_VERSION="2024.02.6"

# ── Point build.sh at the pre-baked Buildroot source ─────────────────────────
# The Dockerfile extracted it to /opt/buildroot-<version>.
# build.sh honours $BUILDROOT_DIR to skip downloading.
export BUILDROOT_DIR="/opt/buildroot-${BR_VERSION}"

# ── Route Buildroot package downloads to the cache volume ────────────────────
# BR2_DL_DIR tells Buildroot where to store downloaded tarballs.
# Mounting a named Docker volume here makes rebuilds much faster.
export BR2_DL_DIR="${DL_CACHE}"

# ── Build ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════╗"
echo "║  AriaCompanion Buildroot image builder   ║"
echo "║  target: ${TARGET}                       "
echo "╚══════════════════════════════════════════╝"
echo ""

build_one() {
    local t="$1"
    echo "▶ Building: ${t}"
    "${ARIACOMP_DIR}/build.sh" "${t}"

    local img="${ARIACOMP_DIR}/output-${t}/images/sdcard.img"
    if [[ -f "$img" ]]; then
        local dest="${SERVE_DIR}/ariacompanion-${t}.img"
        cp "$img" "$dest"
        printf "✓ %s  (%s)\n" "$(basename "$dest")" "$(du -sh "$dest" | cut -f1)"
    else
        echo "✗ Image not found for target ${t}" >&2
        exit 1
    fi
}

case "$TARGET" in
    pizerow)  build_one pizerow  ;;
    pizero2w) build_one pizero2w ;;
    both)     build_one pizerow; build_one pizero2w ;;
    *)
        echo "Unknown target: ${TARGET}  (use pizerow | pizero2w | both)" >&2
        exit 1
        ;;
esac

# ── Serve ─────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════"
echo "  Build complete.  Serving on :8040"
echo "  Open http://localhost:8040 to download."
echo "══════════════════════════════════════════"
echo ""

exec python3 "${ARIACOMP_DIR}/docker-serve.py" "${SERVE_DIR}"
