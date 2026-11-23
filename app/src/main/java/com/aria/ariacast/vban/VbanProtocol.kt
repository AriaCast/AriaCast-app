package com.aria.ariacast.vban

/**
 * VBAN wire format, from VB-Audio's "VBAN Protocol Specifications" (revision 13).
 *
 * Every packet is one UDP datagram: a 28-byte little-endian header followed by data.
 * AUDIO packets carry interleaved PCM; SERVICE packets with the PING0 function are how
 * VBAN devices identify themselves on the network.
 */
object VbanProtocol {
    const val PLATFORM = "VBAN"
    const val DEFAULT_PORT = 6980
    /** The usual name of a receiver's first incoming stream, so this works without setup. */
    const val DEFAULT_STREAM_NAME = "Stream1"
    /** [com.aria.ariacast.ExtraFields] key holding a speaker's stream name. */
    const val EXTRA_STREAM = "stream"

    const val HEADER_SIZE = 28
    /** The spec's data limit: 1500-byte MTU minus 64 bytes for IP/UDP headers and VLAN tags. */
    const val MAX_DATA_SIZE = 1436
    const val MAX_SAMPLES = 256
    const val STREAM_NAME_SIZE = 16

    const val PROTOCOL_AUDIO = 0x00
    const val PROTOCOL_SERVICE = 0x60
    const val PROTOCOL_MASK = 0xE0
    const val DATATYPE_INT16 = 0x01
    const val CODEC_PCM = 0x00

    const val SERVICE_IDENTIFICATION = 0
    const val FNCT_PING0 = 0
    const val FNCT_REPLY = 0x80

    const val PING0_SIZE = 676
    const val PING_TYPE_RECEPTOR = 0x01
    const val PING_TYPE_TRANSMITTER = 0x02
    const val PING_FEATURE_AUDIO = 0x01

    private val MAGIC = byteArrayOf('V'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'N'.code.toByte())

    /** VBAN_SRList: the header stores the index into this table. */
    private val SAMPLE_RATES = intArrayOf(
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600,
    )

    /** Index of [rate] in the VBAN sample-rate table, or -1 if VBAN can't carry it. */
    fun sampleRateIndex(rate: Int): Int = SAMPLE_RATES.indexOf(rate)

    /** Up to 16 printable ASCII characters; receivers match a stream on its name and sender IP. */
    fun sanitizeStreamName(name: String?): String =
        name.orEmpty().filter { it in ' '..'~' }.trim().take(STREAM_NAME_SIZE).ifEmpty { DEFAULT_STREAM_NAME }

    /** Writes a 28-byte header at the start of [buf]. The byte fields hold value-minus-one where the spec says so. */
    fun writeHeader(buf: ByteArray, formatSr: Int, formatNbs: Int, formatNbc: Int, formatBit: Int, streamName: String, frame: Int) {
        MAGIC.copyInto(buf)
        buf[4] = formatSr.toByte()
        buf[5] = formatNbs.toByte()
        buf[6] = formatNbc.toByte()
        buf[7] = formatBit.toByte()
        buf.fill(0, 8, 8 + STREAM_NAME_SIZE)
        streamName.take(STREAM_NAME_SIZE).forEachIndexed { i, c -> buf[8 + i] = c.code.toByte() }
        writeFrameCounter(buf, frame)
    }

    /** nuFrame: a growing counter receivers use to spot lost, doubled or reordered packets. */
    fun writeFrameCounter(buf: ByteArray, frame: Int) = putInt(buf, 24, frame)

    fun isVban(data: ByteArray, length: Int): Boolean =
        length >= HEADER_SIZE && (0 until 4).all { data[it] == MAGIC[it] }

    /**
     * A PING0 request identifying us as a transmitter. Receivers answer with the same
     * frame counter, the reply bit set in the function byte, and their own PING0 data.
     */
    fun pingRequest(frame: Int, deviceName: String, applicationName: String, hostName: String): ByteArray {
        val packet = ByteArray(HEADER_SIZE + PING0_SIZE)
        writeHeader(packet, PROTOCOL_SERVICE, FNCT_PING0, SERVICE_IDENTIFICATION, 0, "VBAN PING", frame)
        val d = HEADER_SIZE
        putInt(packet, d + 0, PING_TYPE_TRANSMITTER)  // bitType
        putInt(packet, d + 4, PING_FEATURE_AUDIO)     // bitfeature
        putInt(packet, d + 12, 48000)                 // PreferedRate
        putInt(packet, d + 16, 48000)                 // MinRate
        putInt(packet, d + 20, 48000)                 // MaxRate
        putAscii(packet, d + 164, 64, deviceName)
        putAscii(packet, d + 292, 64, applicationName)
        putAscii(packet, d + 356, 64, hostName)
        return packet
    }

    data class PingReply(
        val frame: Int,
        val type: Int,
        val features: Int,
        val deviceName: String,
        val manufacturer: String,
        val application: String,
        val hostName: String,
        val userName: String,
    ) {
        /** Something that plays audio streams, as opposed to a pure transmitter. */
        val receivesAudio: Boolean get() = features and PING_FEATURE_AUDIO != 0 && type != PING_TYPE_TRANSMITTER

        val displayName: String get() =
            listOf(userName, hostName, deviceName, application).firstOrNull { it.isNotBlank() } ?: "VBAN"
    }

    /** Parses a PING0 reply, or returns null for anything else (audio, our own requests, short packets). */
    fun parsePingReply(data: ByteArray, length: Int): PingReply? {
        if (!isVban(data, length) || length < HEADER_SIZE + PING0_SIZE) return null
        if (data[4].toInt() and PROTOCOL_MASK != PROTOCOL_SERVICE) return null
        if (data[6].toInt() and 0xFF != SERVICE_IDENTIFICATION) return null
        if (data[5].toInt() and 0xFF != (FNCT_PING0 or FNCT_REPLY)) return null
        val d = HEADER_SIZE
        return PingReply(
            frame = getInt(data, 24),
            type = getInt(data, d + 0),
            features = getInt(data, d + 4),
            deviceName = getString(data, d + 164, 64),
            manufacturer = getString(data, d + 228, 64),
            application = getString(data, d + 292, 64),
            hostName = getString(data, d + 356, 64),
            userName = getString(data, d + 420, 128),
        )
    }

    private fun putInt(buf: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) buf[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun getInt(buf: ByteArray, at: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((buf[at + i].toInt() and 0xFF) shl (8 * i)) }

    private fun putAscii(buf: ByteArray, at: Int, size: Int, value: String) {
        value.filter { it in ' '..'~' }.take(size - 1).forEachIndexed { i, c -> buf[at + i] = c.code.toByte() }
    }

    /** A zero-terminated field; UserName/UserComment are UTF-8, the rest ASCII (a subset). */
    private fun getString(buf: ByteArray, at: Int, size: Int): String {
        var end = at
        while (end < at + size && buf[end] != 0.toByte()) end++
        return String(buf, at, end - at, Charsets.UTF_8).trim()
    }
}
