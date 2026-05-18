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

    fun build(vararg pairs: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in pairs) {
            out.write(type and 0xFF)
            out.write((value.size shr 8) and 0xFF)
            out.write(value.size and 0xFF)
            out.write(value)
        }
        return out.toByteArray()
    }

    fun buildSingle(type: Int, value: ByteArray): ByteArray {
        return ByteArrayOutputStream().apply {
            write(type and 0xFF)
            write((value.size shr 8) and 0xFF)
            write(value.size and 0xFF)
            write(value)
        }.toByteArray()
    }

    fun parse(data: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var i = 0
        while (i + 3 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            i += 3
            if (i + len > data.size) break
            if (len > 0) {
                result.getOrPut(type) { mutableListOf() }.add(data.copyOfRange(i, i + len))
            } else {
                result.getOrPut(type) { mutableListOf() }.add(ByteArray(0))
            }
            i += len
        }
        return result
    }

    fun getFirst(data: ByteArray, type: Int): ByteArray? {
        return parse(data)[type]?.firstOrNull()
    }
}
