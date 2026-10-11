package com.aria.ariacast.airplay

/**
 * PCM payload encoders for AirPlay RTP audio (44.1 kHz, 16-bit, stereo).
 *
 * Input is always interleaved little-endian 16-bit stereo PCM, as produced by
 * AudioRecord and the resampler.
 */
object AirPlayAudio {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4
    const val FRAMES_PER_PACKET = 352
    const val PACKET_PCM_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME

    /** The ALAC "magic cookie" parameters advertised in SDP / used by every receiver. */
    const val ALAC_FMTP = "352 0 16 40 10 14 2 255 0 0 44100"

    /**
     * Wraps PCM in an *uncompressed* ALAC frame. Every ALAC decoder accepts the
     * "escape" (verbatim) frame, so this is valid AppleLossless without a real
     * encoder. Bitstream: element tag ID_CPE (3 bits = 1, a stereo channel pair),
     * element instance (4), 12 unused bits, partial-frame flag (1, set when the
     * frame is shorter than the 352 announced in fmtp, followed by a 32-bit frame
     * count), bytes-shifted (2), escape flag (1 = uncompressed), then big-endian
     * L/R samples and finally the ID_END tag (3 bits = 7).
     */
    fun alacUncompressed(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        require(length % BYTES_PER_FRAME == 0) { "PCM length must be a whole number of stereo frames" }
        val frames = length / BYTES_PER_FRAME
        val partial = frames != FRAMES_PER_PACKET
        val bits = 23 + (if (partial) 32 else 0) + frames * 32 + 3
        val writer = BitWriter((bits + 7) / 8)
        writer.write(1, 3)      // ID_CPE
        writer.write(0, 4)      // element instance tag
        writer.write(0, 12)     // unused
        writer.write(if (partial) 1 else 0, 1)
        writer.write(0, 2)      // bytes shifted
        writer.write(1, 1)      // escape: samples follow uncompressed
        if (partial) writer.write(frames, 32)
        var i = offset
        val end = offset + length
        while (i < end) {
            // little-endian in, big-endian out
            writer.write(pcm[i + 1].toInt() and 0xFF, 8)
            writer.write(pcm[i].toInt() and 0xFF, 8)
            writer.write(pcm[i + 3].toInt() and 0xFF, 8)
            writer.write(pcm[i + 2].toInt() and 0xFF, 8)
            i += BYTES_PER_FRAME
        }
        writer.write(7, 3)      // ID_END
        return writer.toByteArray()
    }

    /** L16 (RFC 3551) payload: the same samples, just byte-swapped to network order. */
    fun l16(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            out[i] = pcm[offset + i + 1]
            out[i + 1] = pcm[offset + i]
            i += 2
        }
        return out
    }

    private class BitWriter(size: Int) {
        private val buf = ByteArray(size)
        private var bitPos = 0

        fun write(value: Int, count: Int) {
            for (b in count - 1 downTo 0) {
                if ((value ushr b) and 1 != 0) {
                    buf[bitPos ushr 3] = (buf[bitPos ushr 3].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
            }
        }

        fun toByteArray(): ByteArray = buf
    }
}

/**
 * AirPlay volume is sent in dB: -30.0 (quietest audible) .. 0.0 (max), with
 * -144.0 meaning mute. iTunes maps its 0-100 slider linearly onto -30..0.
 */
object AirPlayVolume {
    const val MUTE_DB = -144.0
    const val MIN_DB = -30.0
    const val MAX_DB = 0.0

    fun percentToDb(percent: Int): Double = when {
        percent <= 0 -> MUTE_DB
        percent >= 100 -> MAX_DB
        else -> MIN_DB + (MAX_DB - MIN_DB) * percent / 100.0
    }

    fun dbToPercent(db: Double): Int = when {
        db <= MIN_DB -> 0
        db >= MAX_DB -> 100
        else -> Math.round((db - MIN_DB) / (MAX_DB - MIN_DB) * 100.0).toInt()
    }

    fun format(db: Double): String = String.format(java.util.Locale.US, "%.6f", db)
}

/** Parsers for AirPlay mDNS TXT record values. */
object AirPlayTxt {
    /** "0,1,3" style lists (et, cn, md, ss...). */
    fun intList(value: String?): Set<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

    /** "0x5A7FFFF7,0x1E" (low 32 bits, high 32 bits) or a single hex/decimal value. */
    fun features(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val parts = value.split(',')
        val low = parseNumber(parts[0])
        val high = if (parts.size > 1) parseNumber(parts[1]) else 0L
        return (low and 0xFFFFFFFFL) or (high shl 32)
    }

    /** sf / flags: hex ("0x4") or decimal. */
    fun flags(value: String?): Long = if (value.isNullOrBlank()) 0L else parseNumber(value)

    private fun parseNumber(s: String): Long {
        val t = s.trim()
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16) ?: 0L
        else t.toLongOrNull() ?: 0L
    }

    fun hasFeature(features: Long, bit: Int): Boolean = (features ushr bit) and 1L == 1L

    // Feature bits we act on (see openairplay's feature table).
    const val FEATURE_AUDIO = 9
    const val FEATURE_METADATA_TEXT = 17
    const val FEATURE_METADATA_ARTWORK = 15
    const val FEATURE_METADATA_PROGRESS = 16
    const val FEATURE_RSA_AUTH = 23
    const val FEATURE_MFI_AUTH = 26
    const val FEATURE_LEGACY_PAIRING = 27
    const val FEATURE_UNIFIED_MEDIA_CONTROL = 38
    const val FEATURE_BUFFERED_AUDIO = 40
    const val FEATURE_PTP = 41
    const val FEATURE_HK_PAIRING = 46
    const val FEATURE_TRANSIENT_PAIRING = 48

    // Status flag bits (sf / flags / statusFlags).
    const val STATUS_PIN_REQUIRED = 0x8L
    const val STATUS_PASSWORD_REQUIRED = 0x80L
    const val STATUS_PAIRING_REQUIRED = 0x200L
}
