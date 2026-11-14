package com.aria.ariacast.companion

/**
 * AriaCompanion 2 vendor protocol, version 1.
 *
 * Mirrors `AriaCompanion/firmware/include/aria_companion_proto.h`, which is the source of
 * truth; CompanionProtocolTest checks that the two agree. See docs/ariacompanion-2.md §4.
 */
object CompanionProtocol {
    const val PROTOCOL_VERSION = 1

    // The vendor interface is identified by class/subclass/protocol, not by VID/PID, so the
    // development PID and the final Raspberry Pi PID both work without an app update.
    const val IFACE_CLASS = 0xFF
    const val IFACE_SUBCLASS = 0xAC
    const val IFACE_PROTOCOL = 0x01

    const val VID_DEV = 0x1209
    const val PID_DEV = 0x0001
    /** Raspberry Pi boot ROM (BOOTSEL / PICOBOOT), used for firmware updates. */
    const val VID_RPI = 0x2E8A
    const val PID_RP2040_BOOTROM = 0x0003

    // Bulk IN chunk header
    const val MAGIC = 0xAC
    const val HEADER_BYTES = 8
    /** Largest payload the parser accepts before treating the header as garbage. */
    const val MAX_PAYLOAD = 4096

    const val CHUNK_AUDIO = 0x01
    const val CHUNK_FORMAT = 0x02
    const val CHUNK_STREAM = 0x03
    const val CHUNK_OVERRUN = 0x04
    const val CHUNK_MODE = 0x05

    /** Bytes of the AUDIO payload before the PCM: u16 sof_frame + u16 reserved. */
    const val AUDIO_PREFIX_BYTES = 4

    // EP0 vendor requests (bmRequestType 0x41 host->device, 0xC1 device->host, recipient = interface)
    const val REQTYPE_OUT = 0x41
    const val REQTYPE_IN = 0xC1
    const val REQ_GET_INFO = 0x01
    const val REQ_SET_MODE = 0x02
    const val REQ_HEARTBEAT = 0x03
    const val REQ_GET_STATS = 0x04
    const val REQ_REBOOT_BOOTLOADER = 0x05
    const val REQ_RESET_STATS = 0x06

    const val INFO_BYTES = 8
    const val STATS_BYTES = 20

    const val MODE_PRIVATE = 0
    const val MODE_SPEAKER = 1

    const val CAP_24BIT = 1 shl 0
    const val CAP_44K1 = 1 shl 1
    const val CAP_96K = 1 shl 2
    const val CAP_LED = 1 shl 3

    /** USB frames (SOF) are 1 ms apart and the counter is 11 bits wide. */
    const val SOF_MODULO = 2048

    // ---- Bluetooth board (ESP32), docs/ariacompanion-bt.md; mirrors firmware-bt/main/aria_bt_proto.h

    const val BT_PROTOCOL_VERSION = 1
    /** The app offers bonded devices whose name starts with this. */
    const val BT_NAME_PREFIX = "AriaCompanion"
    /** Standard SPP service UUID; the board's server is registered under it. */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    const val CHUNK_SBC_CONFIG = 0x06
    const val CHUNK_SBC_AUDIO = 0x07
    const val CHUNK_INFO = 0x08
    const val CHUNK_STATS = 0x09
    const val SBC_CONFIG_BYTES = 12
    const val SBC_AUDIO_PREFIX = 8

    /** App -> board command: u8 magic, u8 command, u16 value. Codes are the USB request numbers. */
    const val CMD_BYTES = 4
    const val CMD_GET_INFO = REQ_GET_INFO
    const val CMD_SET_MODE = REQ_SET_MODE
    const val CMD_HEARTBEAT = REQ_HEARTBEAT
    const val CMD_GET_STATS = REQ_GET_STATS
    const val CMD_RESET_STATS = REQ_RESET_STATS

    const val CAP_BLUETOOTH = 1 shl 4
}

data class CompanionInfo(
    val protocolVersion: Int,
    val mode: Int,
    val firmwareVersion: Int,
    val capabilities: Int,
    /** Ring buffer size in KiB (64 KiB does not fit a u16 byte count). */
    val ringKilobytes: Int,
) {
    val firmwareVersionString: String
        get() = "${(firmwareVersion shr 8) and 0xFF}.${firmwareVersion and 0xFF}"

    companion object {
        fun parse(b: ByteArray): CompanionInfo? {
            if (b.size < CompanionProtocol.INFO_BYTES) return null
            return CompanionInfo(
                protocolVersion = b.u8(0),
                mode = b.u8(1),
                firmwareVersion = b.u16(2),
                capabilities = b.u16(4),
                ringKilobytes = b.u16(6),
            )
        }
    }
}

data class CompanionStats(
    val isoBytes: Long,
    val bulkBytes: Long,
    val overruns: Long,
    val resets: Long,
    val peakLeft: Int,
    val peakRight: Int,
) {
    companion object {
        fun parse(b: ByteArray): CompanionStats? {
            if (b.size < CompanionProtocol.STATS_BYTES) return null
            return CompanionStats(b.u32(0), b.u32(4), b.u32(8), b.u32(12), b.u16(16), b.u16(18))
        }
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
