package com.aria.ariacast.airplay2

import java.io.ByteArrayOutputStream

object TlvUtil {
    const val TLV_METHOD = 0x00
    const val TLV_IDENTIFIER = 0x01
    const val TLV_SALT = 0x02
    const val TLV_PUBLIC_KEY = 0x03
    const val TLV_PROOF = 0x04
    const val TLV_ENCRYPTED_DATA = 0x05
    const val TLV_STATE = 0x06
    const val TLV_ERROR = 0x07
    const val TLV_RETRY_DELAY = 0x08
    const val TLV_CERTIFICATE = 0x09
    const val TLV_SIGNATURE = 0x0A
    const val TLV_PERMISSIONS = 0x0B
    const val TLV_FRAGMENT_DATA = 0x0C
    const val TLV_FRAGMENT_LAST = 0x0D
    const val TLV_FLAGS = 0x13

    // Standard HAP TLV8: type(1B) + length(1B, max 255) + value.
    // Values > 255 bytes are split into consecutive chunks of the same type.
    fun build(vararg pairs: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in pairs) {
            if (value.isEmpty()) {
                out.write(type and 0xFF)
                out.write(0)
            } else {
                var offset = 0
                while (offset < value.size) {
                    val chunkSize = minOf(255, value.size - offset)
                    out.write(type and 0xFF)
                    out.write(chunkSize)
                    out.write(value, offset, chunkSize)
                    offset += chunkSize
                }
            }
        }
        return out.toByteArray()
    }

    fun buildSingle(type: Int, value: ByteArray): ByteArray = build(type to value)

    fun parse(data: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var i = 0
        var lastType = -1
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > data.size) break
            val value = data.copyOfRange(i, i + len)
            i += len
            val list = result.getOrPut(type) { mutableListOf() }
            if (type == lastType && list.isNotEmpty()) {
                // Reassemble HAP TLV8 fragments (consecutive same-type chunks)
                val prev = list.removeLast()
                val combined = ByteArray(prev.size + value.size)
                System.arraycopy(prev, 0, combined, 0, prev.size)
                System.arraycopy(value, 0, combined, prev.size, value.size)
                list.add(combined)
            } else {
                list.add(value)
            }
            lastType = type
        }
        return result
    }

    fun getFirst(data: ByteArray, type: Int): ByteArray? {
        return parse(data)[type]?.firstOrNull()
    }
}
