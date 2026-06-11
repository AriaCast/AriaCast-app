#!/usr/bin/env bash
# One-command build + serve for AriaCompanion firmware images.
#
# Usage:
#   ./docker-run.sh              # build both Pi Zero W and Pi Zero 2W
#   ./docker-run.sh pizero2w     # build only Pi Zero 2W (faster)
#   ./docker-run.sh pizerow      # build only Pi Zero W
#
# The first run downloads Buildroot packages (~1.5 GB) and builds everything
# from source — expect 60–90 minutes.  Subsequent runs reuse the download
# cache and finish in ~5 minutes.
#
# When done, open http://localhost:8040 to download the .img files.
set -euo pipefail

TARGET="${1:-both}"
IMAGE_TAG="ariacomp-builder"
# Named volume persists the Buildroot package download cache across runs
DL_VOLUME="ariacomp-dl-cache"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Sanity checks ─────────────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    echo "ERROR: docker not found. Install Docker and try again." >&2
    exit 1
fi

case "$TARGET" in
    pizerow|pizero2w|both) ;;
    *)
        echo "Usage: $0 [pizerow|pizero2w|both]" >&2
        exit 1
        ;;
esac

# ── Build the Docker image ────────────────────────────────────────────────────
echo "▶ Building Docker image '${IMAGE_TAG}'…"
echo "  (This step installs host tools and pre-downloads Buildroot ~50 MB."
echo "   Cached after first run.)"
echo ""

docker build \
    --tag "${IMAGE_TAG}" \
    --file "${SCRIPT_DIR}/Dockerfile" \
    "${SCRIPT_DIR}"

echo ""
echo "▶ Running build for target: ${TARGET}"
echo "  Download cache volume: ${DL_VOLUME}"
echo "  Build output served on: http://localhost:8040"
echo ""
echo "  ┌─ First run  ──────────────────────────────────────┐"
echo "  │  Downloads ~1.5 GB of packages + cross-compiler   │"
echo "  │  Compiles Linux kernel + rootfs from source        │"
echo "  │  Expected time: 60–90 min                          │"
echo "  ├─ Subsequent runs ─────────────────────────────────┤"
echo "  │  Cache reused from '${DL_VOLUME}' volume          │"
echo "  │  Expected time: ~5 min                             │"
echo "  └────────────────────────────────────────────────────┘"
echo ""

# ── Run the builder container ─────────────────────────────────────────────────
docker run \
    --rm \
    --name ariacomp-build \
    -p 8040:8040 \
    -v "${DL_VOLUME}:/build/dl-cache" \
    "${IMAGE_TAG}" \
    "${TARGET}"
