package com.aria.ariacast.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The firmware header is the source of truth; the Kotlin mirror must match it. */
class CompanionProtocolTest {
    private val header: Map<String, Long> by lazy {
        val f = listOf("../AriaCompanion/firmware/include/aria_companion_proto.h", "AriaCompanion/firmware/include/aria_companion_proto.h")
            .map(::File).first { it.exists() }
        Regex("""#define\s+(ARIA_\w+)\s+(0x[0-9A-Fa-f]+|\d+)\b""").findAll(f.readText())
            .associate { it.groupValues[1] to java.lang.Long.decode(it.groupValues[2]) }
    }

    private fun h(name: String) = header[name] ?: error("$name missing from aria_companion_proto.h")

    @Test fun constantsMatchFirmwareHeader() {
        val p = CompanionProtocol
        val pairs = mapOf(
            "ARIA_PROTOCOL_VERSION" to p.PROTOCOL_VERSION, "ARIA_IFACE_CLASS" to p.IFACE_CLASS,
            "ARIA_IFACE_SUBCLASS" to p.IFACE_SUBCLASS, "ARIA_IFACE_PROTOCOL" to p.IFACE_PROTOCOL,
            "ARIA_VID" to p.VID_DEV, "ARIA_PID" to p.PID_DEV,
            "ARIA_MAGIC" to p.MAGIC, "ARIA_HEADER_BYTES" to p.HEADER_BYTES, "ARIA_MAX_PAYLOAD" to p.MAX_PAYLOAD,
            "ARIA_CHUNK_AUDIO" to p.CHUNK_AUDIO, "ARIA_CHUNK_FORMAT" to p.CHUNK_FORMAT, "ARIA_CHUNK_STREAM" to p.CHUNK_STREAM,
            "ARIA_CHUNK_OVERRUN" to p.CHUNK_OVERRUN, "ARIA_CHUNK_MODE" to p.CHUNK_MODE,
            "ARIA_AUDIO_PREFIX_BYTES" to p.AUDIO_PREFIX_BYTES,
            "ARIA_REQTYPE_OUT" to p.REQTYPE_OUT, "ARIA_REQTYPE_IN" to p.REQTYPE_IN,
            "ARIA_REQ_GET_INFO" to p.REQ_GET_INFO, "ARIA_REQ_SET_MODE" to p.REQ_SET_MODE,
            "ARIA_REQ_HEARTBEAT" to p.REQ_HEARTBEAT, "ARIA_REQ_GET_STATS" to p.REQ_GET_STATS,
            "ARIA_REQ_REBOOT_BOOTLOADER" to p.REQ_REBOOT_BOOTLOADER, "ARIA_REQ_RESET_STATS" to p.REQ_RESET_STATS,
            "ARIA_INFO_BYTES" to p.INFO_BYTES, "ARIA_STATS_BYTES" to p.STATS_BYTES,
            "ARIA_MODE_PRIVATE" to p.MODE_PRIVATE, "ARIA_MODE_SPEAKER" to p.MODE_SPEAKER,
            "ARIA_SOF_MODULO" to p.SOF_MODULO,
        )
        pairs.forEach { (name, value) -> assertEquals(name, h(name), value.toLong()) }
    }

    @Test fun infoAndStatsParse() {
        val info = CompanionInfo.parse(byteArrayOf(1, 1, 0x00, 0x02, 0x08, 0, 64, 0))!!
        assertEquals(1, info.protocolVersion)
        assertEquals(CompanionProtocol.MODE_SPEAKER, info.mode)
        assertEquals("2.0", info.firmwareVersionString)
        assertEquals(CompanionProtocol.CAP_LED, info.capabilities)
        assertEquals(64, info.ringKilobytes)

        val s = ByteArray(20).also { it[0] = 0x10; it[8] = 3; it[16] = 0xFF.toByte(); it[17] = 0x7F }
        val stats = CompanionStats.parse(s)!!
        assertEquals(16L, stats.isoBytes)
        assertEquals(3L, stats.overruns)
        assertEquals(32767, stats.peakLeft)
        assertTrue(CompanionStats.parse(ByteArray(3)) == null)
    }
}
