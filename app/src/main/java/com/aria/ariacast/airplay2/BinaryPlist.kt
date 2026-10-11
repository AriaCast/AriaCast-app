package com.aria.ariacast.airplay2

import java.io.ByteArrayOutputStream

/**
 * Apple binary property list ("bplist00") encoder/decoder - the body format of
 * AirPlay 2 SETUP / GET /info / event messages and AirPlay 1 legacy pairing.
 *
 * Supported types: dictionaries (String keys), lists, strings (ASCII and
 * UTF-16), integers (Int/Long; negatives as 8-byte), floating point, booleans,
 * byte arrays and null.
 */
object BinaryPlist {

    fun encode(root: Map<String, Any?>): ByteArray = encodeValue(root)

    /** Encodes any supported value as the plist root (e.g. a list, for SETPEERS). */
    fun encodeValue(root: Any?): ByteArray {
        // Count objects first: the reference width depends on the total.
        val all = ArrayList<Any?>()
        flatten(root, all)
        val count = all.size
        val refSize = if (count <= 0xFF) 1 else if (count <= 0xFFFF) 2 else 4

        // Each object gets the next index; a container's children are emitted after
        // it (keys then values), mirroring flatten()'s order.
        val encoded = ArrayList<ByteArray?>(count)
        fun emit(value: Any?): Int {
            val index = encoded.size
            encoded.add(null)
            encoded[index] = when (value) {
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { emit(it.key.toString()) }
                    val valueRefs = entries.map { emit(it.value) }
                    ByteArrayOutputStream().apply {
                        writeMarker(this, 0xD, entries.size)
                        keyRefs.forEach { writeSized(this, it.toLong(), refSize) }
                        valueRefs.forEach { writeSized(this, it.toLong(), refSize) }
                    }.toByteArray()
                }
                is List<*> -> {
                    val refs = value.map { emit(it) }
                    ByteArrayOutputStream().apply {
                        writeMarker(this, 0xA, refs.size)
                        refs.forEach { writeSized(this, it.toLong(), refSize) }
                    }.toByteArray()
                }
                else -> scalarBytes(value)
            }
            return index
        }
        emit(root)

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(encoded.size)
        encoded.forEachIndexed { i, bytes -> offsets[i] = out.size().toLong(); out.write(bytes!!) }
        val offsetTableStart = out.size().toLong()
        val offsetSize = when {
            offsetTableStart <= 0xFF -> 1
            offsetTableStart <= 0xFFFF -> 2
            else -> 4
        }
        offsets.forEach { writeSized(out, it, offsetSize) }
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        writeSized(out, encoded.size.toLong(), 8)
        writeSized(out, 0L, 8)               // root object index
        writeSized(out, offsetTableStart, 8)
        return out.toByteArray()
    }

    private fun flatten(value: Any?, out: MutableList<Any?>) {
        out.add(value)
        when (value) {
            is Map<*, *> -> {
                value.keys.forEach { out.add(it.toString()) }
                value.values.forEach { flatten(it, out) }
            }
            is List<*> -> value.forEach { flatten(it, out) }
        }
    }

    private fun scalarBytes(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)
            is Float, is Double -> {
                out.write(0x23)
                writeSized(out, java.lang.Double.doubleToRawLongBits((value as Number).toDouble()), 8)
            }
            is Number -> writeInt(out, value.toLong())
            is ByteArray -> { writeMarker(out, 0x4, value.size); out.write(value) }
            is String -> {
                if (value.all { it.code < 0x80 }) {
                    writeMarker(out, 0x5, value.length)
                    out.write(value.toByteArray(Charsets.US_ASCII))
                } else {
                    writeMarker(out, 0x6, value.length) // UTF-16 code units
                    out.write(value.toByteArray(Charsets.UTF_16BE))
                }
            }
            else -> return scalarBytes(value.toString())
        }
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..0xFF -> { out.write(0x10); writeSized(out, v, 1) }
            v in 0..0xFFFF -> { out.write(0x11); writeSized(out, v, 2) }
            v in 0..0xFFFFFFFFL -> { out.write(0x12); writeSized(out, v, 4) }
            else -> { out.write(0x13); writeSized(out, v, 8) } // negatives are 8-byte two's complement
        }
    }

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, count: Int) {
        if (count < 15) {
            out.write((type shl 4) or count)
        } else {
            out.write((type shl 4) or 0xF)
            writeInt(out, count.toLong())
        }
    }

    private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    @Suppress("UNCHECKED_CAST")
    fun decode(data: ByteArray): Map<String, Any?> = decodeValue(data) as? Map<String, Any?> ?: emptyMap()

    fun decodeValue(data: ByteArray): Any? {
        if (data.size < 40) throw IllegalArgumentException("too short")
        if (String(data, 0, 8, Charsets.US_ASCII) != "bplist00") throw IllegalArgumentException("not a binary plist")
        val t = data.size - 32
        val offsetSize = data[t + 6].toInt() and 0xFF
        val refSize = data[t + 7].toInt() and 0xFF
        val numObjects = readSized(data, t + 8, 8).toInt()
        val rootIndex = readSized(data, t + 16, 8).toInt()
        val tableOffset = readSized(data, t + 24, 8).toInt()
        if (offsetSize !in 1..8 || refSize !in 1..8 || numObjects < 0 || tableOffset < 8 ||
            tableOffset + numObjects.toLong() * offsetSize > t) {
            throw IllegalArgumentException("corrupt trailer")
        }
        val offsets = IntArray(numObjects) { readSized(data, tableOffset + it * offsetSize, offsetSize).toInt() }

        fun count(pos: Int, low: Int): Pair<Int, Int> {
            if (low != 0xF) return low to pos
            val marker = data[pos].toInt() and 0xFF
            val bytes = 1 shl (marker and 0xF)
            return readSized(data, pos + 1, bytes).toInt() to pos + 1 + bytes
        }

        fun read(index: Int, depth: Int): Any? {
            if (depth > 64) throw IllegalArgumentException("plist nested too deeply")
            if (index !in offsets.indices) throw IllegalArgumentException("bad object reference $index")
            val off = offsets[index]
            val marker = data[off].toInt() and 0xFF
            val low = marker and 0xF
            return when (marker shr 4) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> {
                    val bytes = 1 shl low
                    val v = readSized(data, off + 1, minOf(bytes, 8))
                    // 1/2/4-byte ints are unsigned, 8-byte are signed - readSized gives both.
                    v
                }
                0x2 -> {
                    val bytes = 1 shl low
                    if (bytes == 4) java.lang.Float.intBitsToFloat(readSized(data, off + 1, 4).toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(readSized(data, off + 1, 8))
                }
                0x3 -> java.lang.Double.longBitsToDouble(readSized(data, off + 1, 8)) // date (seconds since 2001)
                0x4 -> { val (n, p) = count(off + 1, low); data.copyOfRange(p, p + n) }
                0x5 -> { val (n, p) = count(off + 1, low); String(data, p, n, Charsets.US_ASCII) }
                0x6 -> { val (n, p) = count(off + 1, low); String(data, p, n * 2, Charsets.UTF_16BE) }
                0x7 -> { val (n, p) = count(off + 1, low); String(data, p, n, Charsets.UTF_8) }
                0x8 -> readSized(data, off + 1, low + 1) // UID
                0xA, 0xC -> {
                    val (n, p) = count(off + 1, low)
                    (0 until n).map { read(readSized(data, p + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (n, p) = count(off + 1, low)
                    val dict = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val k = read(readSized(data, p + i * refSize, refSize).toInt(), depth + 1)?.toString() ?: ""
                        dict[k] = read(readSized(data, p + (n + i) * refSize, refSize).toInt(), depth + 1)
                    }
                    dict
                }
                else -> null
            }
        }
        return read(rootIndex, 0)
    }

    private fun readSized(data: ByteArray, offset: Int, bytes: Int): Long {
        if (offset < 0 || offset + bytes > data.size) throw IllegalArgumentException("plist read out of bounds")
        var v = 0L
        for (i in 0 until bytes) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }
}
