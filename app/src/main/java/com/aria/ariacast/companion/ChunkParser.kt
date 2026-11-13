package com.aria.ariacast.companion

import com.aria.ariacast.companion.CompanionProtocol.AUDIO_PREFIX_BYTES
import com.aria.ariacast.companion.CompanionProtocol.HEADER_BYTES
import com.aria.ariacast.companion.CompanionProtocol.MAGIC
import com.aria.ariacast.companion.CompanionProtocol.MAX_PAYLOAD

sealed interface CompanionEvent {
    data class Format(val sampleRate: Int, val bits: Int, val subslotBytes: Int, val channels: Int) : CompanionEvent
    class Audio(val seq: Long, val sofFrame: Int, val pcm: ByteArray) : CompanionEvent
    data class Stream(val active: Boolean) : CompanionEvent
    data class Overrun(val droppedBytes: Long) : CompanionEvent
    data class Mode(val mode: Int) : CompanionEvent
}

/**
 * Parses the bulk IN byte stream into [CompanionEvent]s (docs/ariacompanion-2.md §4.1).
 *
 * A USB transfer may hold several chunks or only part of one, so bytes are buffered across
 * [feed] calls. When the header doesn't look valid the parser drops one byte and scans for
 * the next plausible header ([resyncs] counts these). Pure Kotlin, no Android dependencies.
 */
class ChunkParser {
    private var buf = ByteArray(16 * 1024)
    private var len = 0
    private var lastSeq: Long = -1

    var resyncs = 0L
        private set
    /** Chunks missing according to the sequence numbers. */
    var seqGaps = 0L
        private set

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<CompanionEvent> {
        ensureCapacity(len + length)
        System.arraycopy(data, offset, buf, len, length)
        len += length

        val events = ArrayList<CompanionEvent>(4)
        var pos = 0
        while (len - pos >= HEADER_BYTES) {
            val magic = buf[pos].toInt() and 0xFF
            val type = buf[pos + 1].toInt() and 0xFF
            val payload = (buf[pos + 2].toInt() and 0xFF) or ((buf[pos + 3].toInt() and 0xFF) shl 8)
            if (magic != MAGIC || payload > MAX_PAYLOAD || type !in KNOWN_TYPES) {
                pos++
                resyncs++
                continue
            }
            if (len - pos < HEADER_BYTES + payload) break // wait for the rest
            val seq = readU32(pos + 4)
            if (lastSeq >= 0) {
                val expected = (lastSeq + 1) and 0xFFFF_FFFFL
                if (seq != expected) seqGaps += (seq - expected) and 0xFFFF_FFFFL
            }
            lastSeq = seq
            decode(type, seq, pos + HEADER_BYTES, payload)?.let(events::add)
            pos += HEADER_BYTES + payload
        }
        // Compact: keep only the unconsumed tail
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
        return events
    }

    fun reset() {
        len = 0
        lastSeq = -1
    }

    private fun decode(type: Int, seq: Long, p: Int, payload: Int): CompanionEvent? = when (type) {
        CompanionProtocol.CHUNK_AUDIO -> if (payload < AUDIO_PREFIX_BYTES) null else {
            val sof = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
            val pcm = buf.copyOfRange(p + AUDIO_PREFIX_BYTES, p + payload)
            CompanionEvent.Audio(seq, sof, pcm)
        }
        CompanionProtocol.CHUNK_FORMAT -> if (payload < 8) null else CompanionEvent.Format(
            sampleRate = readU32(p).toInt(),
            bits = buf[p + 4].toInt() and 0xFF,
            subslotBytes = buf[p + 5].toInt() and 0xFF,
            channels = buf[p + 6].toInt() and 0xFF,
        )
        CompanionProtocol.CHUNK_STREAM -> if (payload < 1) null else CompanionEvent.Stream(buf[p].toInt() != 0)
        CompanionProtocol.CHUNK_OVERRUN -> if (payload < 4) null else CompanionEvent.Overrun(readU32(p))
        CompanionProtocol.CHUNK_MODE -> if (payload < 1) null else CompanionEvent.Mode(buf[p].toInt() and 0xFF)
        else -> null
    }

    private fun readU32(i: Int): Long =
        ((buf[i].toLong() and 0xFF) or ((buf[i + 1].toLong() and 0xFF) shl 8) or
            ((buf[i + 2].toLong() and 0xFF) shl 16) or ((buf[i + 3].toLong() and 0xFF) shl 24))

    private fun ensureCapacity(n: Int) {
        if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2))
    }

    private companion object {
        val KNOWN_TYPES = setOf(
            CompanionProtocol.CHUNK_AUDIO, CompanionProtocol.CHUNK_FORMAT, CompanionProtocol.CHUNK_STREAM,
            CompanionProtocol.CHUNK_OVERRUN, CompanionProtocol.CHUNK_MODE,
        )
    }
}

/**
 * Fills gaps in the USB timeline with silence. The board stamps each AUDIO chunk with the
 * USB frame number (1 ms) of its first sample; when the host sends nothing for a few frames
 * the next chunk's frame number jumps ahead, and those milliseconds were silence.
 */
class SofGapFiller(private val sampleRate: Int = 48_000, private val bytesPerFrame: Int = 4) {
    private var expectedSof = -1
    private var carrySamples = 0 // samples beyond whole milliseconds, carried to the next chunk

    /** Max silence inserted for one gap; longer gaps mean the stream restarted. */
    var maxGapMs = 200

    /** Returns how many bytes of silence to insert before this chunk's PCM. */
    fun silenceBefore(sofFrame: Int, pcmBytes: Int): Int {
        val samples = pcmBytes / bytesPerFrame
        var silence = 0
        if (expectedSof >= 0) {
            val gapMs = Math.floorMod(sofFrame - expectedSof, CompanionProtocol.SOF_MODULO)
            if (gapMs in 1..maxGapMs) silence = gapMs * (sampleRate / 1000) * bytesPerFrame
        }
        val total = carrySamples + samples
        val perMs = sampleRate / 1000
        expectedSof = (sofFrame + total / perMs) % CompanionProtocol.SOF_MODULO
        carrySamples = total % perMs
        return silence
    }

    fun reset() {
        expectedSof = -1
        carrySamples = 0
    }
}
