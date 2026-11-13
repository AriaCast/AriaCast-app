package com.aria.ariacast.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * In-app AriaCompanion firmware update (docs/ariacompanion-2.md §6.7).
 *
 * 1. Ask the running firmware to reboot into the RP2040 boot ROM (REBOOT_BOOTLOADER).
 * 2. The board reappears as 2E8A:0003 with a PICOBOOT vendor interface.
 * 3. Erase and write flash from the UF2 bundled in the APK, verified against its SHA-256.
 * 4. Reboot into the new firmware.
 */
object FirmwareUpdater {
    private const val ASSET_UF2 = "companion/aria_companion.uf2"
    private const val ASSET_SHA = "companion/aria_companion.uf2.sha256"
    private const val ASSET_VERSION = "companion/version.txt"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    fun bundledVersion(context: Context): String? =
        runCatching { context.assets.open(ASSET_VERSION).bufferedReader().use { it.readText().trim() } }.getOrNull()

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                val image = loadBundledImage(app) ?: run { _progress.value = "No firmware is bundled in this build"; return@launch }
                val usb = app.getSystemService(UsbManager::class.java)
                var boot = findBootloader(usb)
                if (boot == null) {
                    _progress.value = "Restarting AriaCompanion into update mode…"
                    if (!CompanionHub.get(app).rebootToBootloader()) { _progress.value = "AriaCompanion did not respond"; return@launch }
                    repeat(40) { if (boot == null) { delay(250); boot = findBootloader(usb) } }
                }
                val device = boot ?: run { _progress.value = "Update mode not detected - unplug the board, hold BOOTSEL and plug it in again"; return@launch }
                if (!usb.hasPermission(device)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    usb.requestPermission(device, PendingIntent.getBroadcast(app, 1, Intent(CompanionHub.ACTION_USB_PERMISSION).setPackage(app.packageName), flags))
                    _progress.value = "Allow USB access to finish the update"
                    repeat(120) { if (!usb.hasPermission(device)) delay(250) }
                    if (!usb.hasPermission(device)) { _progress.value = "USB access was not granted"; return@launch }
                }
                flash(usb, device, image)
            } catch (e: Exception) {
                _progress.value = "Update failed: ${e.message}"
            }
        }
    }

    private fun findBootloader(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull(CompanionUsb::isBootloader)

    private fun loadBundledImage(context: Context): Uf2Image? {
        val bytes = runCatching { context.assets.open(ASSET_UF2).use { it.readBytes() } }.getOrNull() ?: return null
        val expected = runCatching { context.assets.open(ASSET_SHA).bufferedReader().use { it.readText().trim().substringBefore(' ') } }.getOrNull()
        if (expected != null) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actual.equals(expected, ignoreCase = true)) { "bundled firmware checksum mismatch" }
        }
        return Uf2Image.parse(bytes)
    }

    private fun flash(usb: UsbManager, device: UsbDevice, image: Uf2Image) {
        val iface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC } ?: error("PICOBOOT interface not found")
        val eps = (0 until iface.endpointCount).map(iface::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val outEp = eps.first { it.direction == UsbConstants.USB_DIR_OUT }
        val inEp = eps.first { it.direction == UsbConstants.USB_DIR_IN }
        val conn = usb.openDevice(device) ?: error("cannot open the boot ROM")
        try {
            check(conn.claimInterface(iface, true)) { "cannot claim PICOBOOT" }
            // INTERFACE_RESET clears any half-finished command.
            conn.controlTransfer(0x41, 0x41, 0, iface.id, null, 0, 1000)
            val pb = Picoboot(conn, outEp, inEp)
            pb.command(Picoboot.EXCLUSIVE_ACCESS, byteArrayOf(1), 1)
            pb.command(Picoboot.EXIT_XIP, ByteArray(0), 0)
            val ranges = image.contiguousRanges()
            val total = ranges.sumOf { it.second.size }
            var done = 0
            for ((addr, data) in ranges) {
                val eraseStart = addr and 0xFFF.inv()
                val eraseEnd = (addr + data.size + 0xFFF) and 0xFFF.inv()
                _progress.value = "Erasing…"
                pb.addrSize(Picoboot.FLASH_ERASE, eraseStart, eraseEnd - eraseStart)
                var off = 0
                while (off < data.size) {
                    val n = minOf(4096, data.size - off)
                    pb.write(addr + off, data.copyOfRange(off, off + n))
                    off += n
                    done += n
                    _progress.value = "Writing ${done * 100 / total}%"
                }
            }
            _progress.value = "Restarting…"
            pb.reboot()
            _progress.value = "Firmware updated"
        } finally {
            runCatching { conn.releaseInterface(iface) }
            conn.close()
        }
    }

    /** Minimal PICOBOOT client (RP2040 datasheet §2.8.5). */
    private class Picoboot(val conn: UsbDeviceConnection, val outEp: UsbEndpoint, val inEp: UsbEndpoint) {
        private var token = 1

        fun command(id: Int, args: ByteArray, argSize: Int, transferLength: Int = 0, data: ByteArray? = null) {
            val cmd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putInt(token++).put(id.toByte()).put(argSize.toByte()).putShort(0).putInt(transferLength)
            cmd.put(args.copyOf(16))
            check(conn.bulkTransfer(outEp, cmd.array(), 32, 3000) == 32) { "PICOBOOT command 0x%02x not sent".format(id) }
            if (data != null) {
                var off = 0
                while (off < data.size) {
                    val n = conn.bulkTransfer(outEp, data, off, data.size - off, 5000)
                    check(n > 0) { "PICOBOOT data phase failed" }
                    off += n
                }
            }
            // ACK: zero-length packet in the direction opposite to the data phase (IN here).
            val ack = ByteArray(1)
            check(conn.bulkTransfer(inEp, ack, 0, 10_000) >= 0) { "PICOBOOT command 0x%02x not acknowledged".format(id) }
        }

        fun addrSize(id: Int, addr: Int, size: Int) {
            val a = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).putInt(size).array()
            command(id, a, 8)
        }

        fun write(addr: Int, data: ByteArray) {
            val a = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).putInt(data.size).array()
            command(WRITE, a, 8, data.size, data)
        }

        fun reboot() {
            val a = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0x20042000).putInt(500).array()
            runCatching { command(REBOOT, a, 12) } // the device may vanish before the ACK
        }

        companion object {
            const val MAGIC = 0x431fd10b
            const val EXCLUSIVE_ACCESS = 0x01
            const val REBOOT = 0x02
            const val FLASH_ERASE = 0x03
            const val WRITE = 0x05
            const val EXIT_XIP = 0x06
        }
    }
}

/** A UF2 firmware image reduced to flash pages (https://github.com/microsoft/uf2). */
class Uf2Image private constructor(private val pages: Map<Int, ByteArray>) {
    val pageCount: Int get() = pages.size

    /** Runs of adjacent 256-byte pages, as (address, bytes). */
    fun contiguousRanges(): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var start = -1
        var buf = java.io.ByteArrayOutputStream()
        var next = -1
        for ((addr, data) in pages.toSortedMap()) {
            if (addr != next) {
                if (start >= 0) result += start to buf.toByteArray()
                start = addr
                buf = java.io.ByteArrayOutputStream()
            }
            buf.write(data)
            next = addr + data.size
        }
        if (start >= 0) result += start to buf.toByteArray()
        return result
    }

    companion object {
        const val RP2040_FAMILY = 0xe48bff56.toInt()
        private const val FLASH_START = 0x10000000
        private const val FLASH_END = 0x11000000

        fun parse(bytes: ByteArray): Uf2Image {
            require(bytes.size % 512 == 0 && bytes.isNotEmpty()) { "not a UF2 file" }
            val pages = sortedMapOf<Int, ByteArray>()
            for (blockStart in bytes.indices step 512) {
                val b = ByteBuffer.wrap(bytes, blockStart, 512).order(ByteOrder.LITTLE_ENDIAN)
                val m0 = b.int; val m1 = b.int; val flags = b.int; val addr = b.int
                val size = b.int; b.int /* blockNo */; b.int /* numBlocks */; val family = b.int
                require(m0 == 0x0A324655 && m1 == 0x9E5D5157.toInt()) { "bad UF2 block magic" }
                if (flags and 0x1 != 0) continue // "not main flash"
                if (flags and 0x2000 != 0) require(family == RP2040_FAMILY) { "firmware is not for the RP2040" }
                require(size == 256 && addr % 256 == 0 && addr in FLASH_START until FLASH_END) { "unexpected UF2 block at 0x%08x".format(addr) }
                pages[addr] = bytes.copyOfRange(blockStart + 32, blockStart + 32 + size)
            }
            require(pages.isNotEmpty()) { "UF2 contains no flash data" }
            return Uf2Image(pages)
        }
    }
}
