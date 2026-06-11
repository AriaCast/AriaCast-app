#!/usr/bin/env bash
# Buildroot post-image hook — assembles the final SD card .img using genimage.
# $BINARIES_DIR, $HOST_DIR, $BR2_EXTERNAL_ARIACOMP_PATH are set by Buildroot.
set -euo pipefail

EXT_DIR="${BR2_EXTERNAL_ARIACOMP_PATH}"
GENIMAGE="${HOST_DIR}/bin/genimage"
GENIMAGE_TMP="${BINARIES_DIR}/genimage.tmp"

# Select genimage config based on architecture
if file "${BINARIES_DIR}/Image" &>/dev/null || \
   ls "${BINARIES_DIR}/rpi-firmware/"*64* &>/dev/null 2>&1 || \
   ls "${BINARIES_DIR}"/kernel8.img &>/dev/null 2>&1; then
    BOARD="pizero2w"
    GENIMAGE_CFG="${EXT_DIR}/board/genimage-pizero2w.cfg"
else
    BOARD="pizerow"
    GENIMAGE_CFG="${EXT_DIR}/board/genimage-pizerow.cfg"
fi

echo "==> Creating SD image for ${BOARD} using ${GENIMAGE_CFG}"

rm -rf "${GENIMAGE_TMP}"
"${GENIMAGE}" \
    --rootpath "${TARGET_DIR}" \
    --tmppath  "${GENIMAGE_TMP}" \
    --inputpath "${BINARIES_DIR}" \
    --outputpath "${BINARIES_DIR}" \
    --config "${GENIMAGE_CFG}"

echo "==> SD image: ${BINARIES_DIR}/sdcard.img"
echo "    Size: $(du -sh "${BINARIES_DIR}/sdcard.img" | cut -f1)"
