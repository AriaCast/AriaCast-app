package com.aria.ariacast.cast

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The CastV2 wire message (cast_channel.proto `CastMessage`), hand-encoded so the
 * app doesn't need a protobuf runtime:
 *
 *   1 protocol_version (enum, CASTV2_1_0 = 0)   2 source_id (string)
 *   3 destination_id (string)                   4 namespace (string)
 *   5 payload_type (enum, STRING = 0, BINARY = 1)
 *   6 payload_utf8 (string)                     7 payload_binary (bytes)
 *
 * On the socket each message is preceded by its length as a 32-bit big-endian int.
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
    val protocolVersion: Int = 0
) {
    val isBinary: Boolean get() = payloadBinary != null

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, protocolVersion.toLong())
        writeBytesField(out, 2, sourceId.toByteArray(Charsets.UTF_8))
        writeBytesField(out, 3, destinationId.toByteArray(Charsets.UTF_8))
        writeBytesField(out, 4, namespace.toByteArray(Charsets.UTF_8))
        if (payloadBinary != null) {
            writeVarintField(out, 5, 1)
            writeBytesField(out, 7, payloadBinary)
        } else {
            writeVarintField(out, 5, 0)
            writeBytesField(out, 6, (payloadUtf8 ?: "").toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_MESSAGE_SIZE = 64 * 1024

        fun decode(data: ByteArray): CastMessage {
            var pos = 0
            var version = 0
            var source = ""
            var dest = ""
            var ns = ""
            var utf8: String? = null
            var binary: ByteArray? = null
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("varint too long")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> {
                        val v = varint()
                        if (field == 1) version = v.toInt()
                    }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("bad length")
                        val bytes = data.copyOfRange(pos, pos + len)
                        pos += len
                        when (field) {
                            2 -> source = String(bytes, Charsets.UTF_8)
                            3 -> dest = String(bytes, Charsets.UTF_8)
                            4 -> ns = String(bytes, Charsets.UTF_8)
                            6 -> utf8 = String(bytes, Charsets.UTF_8)
                            7 -> binary = bytes
                        }
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> throw IOException("unsupported wire type")
                }
            }
            return CastMessage(source, dest, ns, utf8, binary, version)
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
            writeVarint(out, (field shl 3).toLong())
            writeVarint(out, value)
        }

        private fun writeBytesField(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
            writeVarint(out, ((field shl 3) or 2).toLong())
            writeVarint(out, value.size.toLong())
            out.write(value)
        }
    }
}
