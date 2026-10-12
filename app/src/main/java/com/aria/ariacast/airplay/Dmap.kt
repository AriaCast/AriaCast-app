package com.aria.ariacast.airplay

import java.io.ByteArrayOutputStream

/**
 * DMAP ("Digital Media Access Protocol") tagged encoding, used by AirPlay for
 * now-playing metadata: SET_PARAMETER with Content-Type application/x-dmap-tagged
 * and an `mlit` (listing item) container wrapping the individual tags. Receivers
 * (shairport-sync, AirPort Express, Apple TV...) skip the 8-byte container
 * header and walk the tags inside, so the container is mandatory.
 */
object Dmap {
    class Builder {
        private val out = ByteArrayOutputStream()

        private fun header(tag: String, length: Int) {
            require(tag.length == 4) { "DMAP tags are 4 characters" }
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.write(length ushr 24); out.write(length ushr 16); out.write(length ushr 8); out.write(length)
        }

        fun string(tag: String, value: String?): Builder {
            if (value.isNullOrEmpty()) return this
            val bytes = value.toByteArray(Charsets.UTF_8)
            header(tag, bytes.size)
            out.write(bytes)
            return this
        }

        fun u8(tag: String, value: Int): Builder { header(tag, 1); out.write(value); return this }

        fun u16(tag: String, value: Int): Builder {
            header(tag, 2); out.write(value ushr 8); out.write(value); return this
        }

        fun u32(tag: String, value: Long): Builder {
            header(tag, 4)
            out.write((value ushr 24).toInt()); out.write((value ushr 16).toInt())
            out.write((value ushr 8).toInt()); out.write(value.toInt())
            return this
        }

        fun u64(tag: String, value: Long): Builder {
            header(tag, 8)
            for (i in 7 downTo 0) out.write((value ushr (8 * i)).toInt())
            return this
        }

        fun container(tag: String, content: ByteArray): Builder {
            header(tag, content.size); out.write(content); return this
        }

        fun build(): ByteArray = out.toByteArray()
    }

    /**
     * The `mlit` block for one track. [itemId] lets receivers tell a new track
     * from a re-send of the current one (they cache artwork by it).
     */
    fun trackInfo(
        title: String?,
        artist: String?,
        album: String?,
        genre: String? = null,
        durationMs: Long? = null,
        itemId: Long = 0L
    ): ByteArray {
        val items = Builder()
            .u8("mikd", 2) // item kind: 2 = audio
            .string("minm", title ?: "")
            .string("asar", artist)
            .string("asal", album)
            .string("asgn", genre)
            .u64("mper", itemId)
        if (durationMs != null && durationMs > 0) items.u32("astm", durationMs)
        return Builder().container("mlit", items.build()).build()
    }

    /** Parses a flat sequence of tags (one level) into tag -> raw bytes; used by tests and DACP. */
    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var i = offset
        val end = offset + length
        while (i + 8 <= end) {
            val tag = String(data, i, 4, Charsets.US_ASCII)
            val len = Bytes.u32(data, i + 4)
            if (len < 0 || i + 8 + len > end) break
            result[tag] = data.copyOfRange(i + 8, i + 8 + len)
            i += 8 + len
        }
        return result
    }
}
