package com.aria.ariacast.raop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DmapEncoder {
    fun encode(title: String?, artist: String?, album: String?): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, "minm", title)
        writeTag(out, "asar", artist)
        writeTag(out, "asal", album)
        return out.toByteArray()
    }

    private fun writeTag(out: ByteArrayOutputStream, tag: String, value: String?) {
        if (value.isNullOrEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(tag.toByteArray(Charsets.US_ASCII))
        val len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        out.write(len)
        out.write(bytes)
    }
}
