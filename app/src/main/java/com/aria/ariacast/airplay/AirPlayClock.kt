package com.aria.ariacast.airplay

/**
 * The single clock every AirPlay timing source in the app is derived from:
 * NTP timing replies, RTP sync packets, the PTP master and the RTP pacer.
 *
 * It's the monotonic System.nanoTime() shifted once onto the Unix epoch, so it
 * reads like wall-clock time (which receivers expect for NTP) but never jumps
 * when the phone's wall clock is adjusted mid-stream - a jump would make every
 * receiver think the stream suddenly drifted by that amount.
 */
object AirPlayClock {
    private val epochOffsetNanos = System.currentTimeMillis() * 1_000_000L - System.nanoTime()

    /** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
    const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

    /** Nanoseconds since the Unix epoch, monotonic. */
    fun nowNanos(): Long = System.nanoTime() + epochOffsetNanos

    /** 64-bit NTP timestamp (32.32 fixed point seconds since 1900) for [unixNanos]. */
    fun toNtp(unixNanos: Long): Long {
        val seconds = unixNanos / 1_000_000_000L + NTP_EPOCH_OFFSET_SECONDS
        val nanos = unixNanos % 1_000_000_000L
        val fraction = (nanos shl 32) / 1_000_000_000L
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    fun ntpNow(): Long = toNtp(nowNanos())

    /** Inverse of [toNtp]. */
    fun fromNtp(ntp: Long): Long {
        val seconds = (ntp ushr 32) - NTP_EPOCH_OFFSET_SECONDS
        val fraction = ntp and 0xFFFFFFFFL
        return seconds * 1_000_000_000L + ((fraction * 1_000_000_000L) ushr 32)
    }
}

/** Big-endian helpers shared by the RTP/PTP packet builders. */
internal object Bytes {
    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte(); buf[off + 1] = v.toByte()
    }

    fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte(); buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte(); buf[off + 3] = v.toByte()
    }

    fun putU64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun u32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)

    fun u64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        val clean = s.trim()
        require(clean.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
