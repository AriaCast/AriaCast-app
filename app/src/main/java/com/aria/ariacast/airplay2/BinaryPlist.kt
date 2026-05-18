package com.aria.ariacast.airplay2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.experimental.and

object BinaryPlist {

    private sealed class PlistNode {
        data class PDict(val entries: List<Pair<String, PlistNode>>) : PlistNode()
        data class PArray(val items: List<PlistNode>) : PlistNode()
        data class PString(val value: String) : PlistNode()
        data class PUint(val value: Long) : PlistNode()
        data class PBool(val value: Boolean) : PlistNode()
        data class PData(val value: ByteArray) : PlistNode()
    }

    fun encode(root: Map<String, Any?>): ByteArray {
        val node = toNode(root)
        val objects = mutableListOf<PlistNode>()
        val offsetMap = mutableMapOf<Int, Int>()
        collectObjects(node, objects, offsetMap)
        val objectsOut = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        for (obj in objects) {
            offsets.add(objectsOut.size())
            writeObject(obj, objects, objectsOut)
        }
        val offsetSize = if (objectsOut.size() < 256) 1 else if (objectsOut.size() < 65536) 2 else 4
        val numObjects = objects.size
        val trailerOffset = objectsOut.size() + numObjects * offsetSize
        for (off in offsets) {
            when (offsetSize) {
                1 -> objectsOut.write(off)
                2 -> objectsOut.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(off.toShort()).array())
                4 -> objectsOut.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(off).array())
            }
        }
        val header = "bplist00".toByteArray()
        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0)     // unused
            putShort(offsetSize.toShort())
            putShort(1)     // object ref size (always 1 for <256 objects)
            putShort(0)     // unused
            putLong(numObjects.toLong())
            putLong(0)      // root = first object
            putLong(trailerOffset.toLong())
        }.array()
        return ByteArrayOutputStream().apply {
            write(header)
            write(objectsOut.toByteArray())
            write(trailer)
        }.toByteArray()
    }

    private fun collectObjects(node: PlistNode, list: MutableList<PlistNode>, seen: MutableMap<Int, Int>) {
        val hc = node.hashCode()
        if (hc in seen) return
        seen[hc] = list.size
        list.add(node)
        when (node) {
            is PlistNode.PDict -> node.entries.forEach { (k, v) -> collectObjects(PlistNode.PString(k), list, seen); collectObjects(v, list, seen) }
            is PlistNode.PArray -> node.items.forEach { collectObjects(it, list, seen) }
            else -> {}
        }
    }

    private fun toNode(value: Any?): PlistNode = when (value) {
        is Map<*, *> -> PlistNode.PDict(value.entries.map { (k, v) -> k.toString() to toNode(v) })
        is List<*> -> PlistNode.PArray(value.map { toNode(it) })
        is String -> PlistNode.PString(value)
        is Number -> PlistNode.PUint(value.toLong())
        is Boolean -> PlistNode.PBool(value)
        is ByteArray -> PlistNode.PData(value)
        else -> PlistNode.PString(value.toString())
    }

    private fun findIndex(node: PlistNode, objects: List<PlistNode>): Int {
        val hc = node.hashCode()
        for (i in objects.indices) {
            if (objects[i].hashCode() == hc && objects[i] == node) return i
        }
        return 0
    }

    private fun writeObject(obj: PlistNode, objects: List<PlistNode>, out: ByteArrayOutputStream) {
        when (obj) {
            is PlistNode.PDict -> {
                val keys = obj.entries.map { findIndex(PlistNode.PString(it.first), objects) }
                val values = obj.entries.map { findIndex(it.second, objects) }
                val size = obj.entries.size
                if (size < 15) {
                    out.write(0x0D or (size shl 4))
                } else {
                    out.write(0x0D or (0x0F shl 4))
                    writeInt(size, out)
                }
                keys.forEach { writeIntRef(it, objects.size, out) }
                values.forEach { writeIntRef(it, objects.size, out) }
            }
            is PlistNode.PArray -> {
                val size = obj.items.size
                if (size < 15) {
                    out.write(0x0F or (size shl 4))
                } else {
                    out.write(0x0F or (0x0F shl 4))
                    writeInt(size, out)
                }
                obj.items.forEach { writeIntRef(findIndex(it, objects), objects.size, out) }
            }
            is PlistNode.PString -> {
                val raw = obj.value.toByteArray(Charsets.UTF_8)
                val len = raw.size
                if (len < 15) {
                    out.write(0x50 or len)
                } else {
                    out.write(0x50 or 0x0F)
                    writeInt(len, out)
                }
                out.write(raw)
            }
            is PlistNode.PUint -> {
                val v = obj.value
                when {
                    v in 0..0xFF -> { out.write(0x10 or 0); out.write(v.toInt()) }
                    v in 0..0xFFFF -> { out.write(0x10 or 1); out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(v.toShort()).array()) }
                    v in 0..0xFFFFFFFFL -> { out.write(0x10 or 2); out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()) }
                    else -> { out.write(0x10 or 3); out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()) }
                }
            }
            is PlistNode.PBool -> out.write(if (obj.value) 0x09 else 0x08)
            is PlistNode.PData -> {
                val len = obj.value.size
                if (len < 15) {
                    out.write(0x40 or len)
                } else {
                    out.write(0x40 or 0x0F)
                    writeInt(len, out)
                }
                out.write(obj.value)
            }
        }
    }

    private fun writeInt(value: Int, out: ByteArrayOutputStream) {
        if (value < 0x0F) {
            out.write(value)
        } else if (value < 0x100) {
            out.write(0x10); out.write(value)
        } else {
            out.write(0x11)
            out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array())
        }
    }

    private fun writeIntRef(index: Int, numObjects: Int, out: ByteArrayOutputStream) {
        if (numObjects <= 256) {
            out.write(index)
        } else {
            out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(index.toShort()).array())
        }
    }

    fun encodeSet(uuid: UUID): ByteArray = encode(mapOf(
        "deviceID" to "00:00:00:00:00:00",
        "sessionUUID" to uuid.toString().uppercase(),
        "timingPort" to 0L,
        "timingProtocol" to "NTP"
    ))

    fun makeSessionPlist(uuid: UUID, deviceId: String, timingPort: Int): ByteArray = encode(mapOf(
        "deviceID" to deviceId,
        "sessionUUID" to uuid.toString().uppercase(),
        "timingPort" to timingPort.toLong(),
        "timingProtocol" to "NTP"
    ))

    fun decode(data: ByteArray): Map<String, Any?> {
        if (data.size < 40) throw IllegalArgumentException("too short")
        val magic = data.copyOf(8).toString(Charsets.UTF_8)
        if (magic != "bplist00") throw IllegalArgumentException("not a binary plist")

        val trailer = data.copyOfRange(data.size - 32, data.size)
        val buf = ByteBuffer.wrap(trailer).order(ByteOrder.BIG_ENDIAN)
        val _unused6 = ByteArray(6); buf.get(_unused6)
        val offsetIntSize = buf.get().toInt() and 0xFF
        val objectRefSize = buf.get().toInt() and 0xFF
        val numObjects = buf.getLong()
        val rootIndex = buf.getLong().toInt()
        val offsetTableOffset = buf.getLong().toInt()

        val objectOffsets = IntArray(numObjects.toInt())
        for (i in 0 until numObjects.toInt()) {
            objectOffsets[i] = readSizedInt(data, offsetTableOffset + i * offsetIntSize, offsetIntSize)
        }

        fun readRef(offset: Int): Int = readSizedInt(data, offset, objectRefSize)

        fun resolveCount(dataPos: Int, count: Int): Pair<Int, Int> {
            if (count != 0x0F) return count to dataPos
            val marker = data[dataPos].toInt() and 0xFF
            val valBytes = 1 shl (marker and 0x0F)
            val resolved = readSizedInt(data, dataPos + 1, valBytes)
            return resolved to (dataPos + 1 + valBytes)
        }

        fun readObject(objIndex: Int): Any? {
            val off = objectOffsets[objIndex]
            val marker = data[off].toInt() and 0xFF
            val objType = marker shr 4
            val objSize = marker and 0x0F
            return when (objType) {
                0x00 -> when (marker) {
                    0x00 -> null; 0x08 -> false; 0x09 -> true; else -> null
                }
                0x01 -> {
                    val bytes = 1 shl objSize
                    if (bytes <= 4) readSizedInt(data, off + 1, bytes).toLong()
                    else readSizedLong(data, off + 1, bytes)
                }
                0x02, 0x03 -> readSizedInt(data, off + 1, 1 shl objSize).toDouble()
                0x04 -> {
                    val (cnt, pos) = resolveCount(off + 1, objSize)
                    data.copyOfRange(pos, pos + cnt)
                }
                0x05 -> {
                    val (cnt, pos) = resolveCount(off + 1, objSize)
                    String(data, pos, cnt, Charsets.US_ASCII)
                }
                0x06 -> {
                    val (cnt, pos) = resolveCount(off + 1, objSize)
                    String(data, pos, cnt * 2, Charsets.UTF_16BE)
                }
                0x08 -> {
                    val (cnt, pos) = resolveCount(off + 1, objSize)
                    (0 until cnt).map { readObject(readRef(pos + it * objectRefSize)) }
                }
                0x0D -> {
                    val (cnt, pos) = resolveCount(off + 1, objSize)
                    val keys = (0 until cnt).map { readObject(readRef(pos + it * objectRefSize))?.toString() ?: "" }
                    val vStart = pos + cnt * objectRefSize
                    val values = (0 until cnt).map { readObject(readRef(vStart + it * objectRefSize)) }
                    val dict = linkedMapOf<String, Any?>()
                    for (j in 0 until cnt) dict[keys[j]] = values[j]
                    dict
                }
                else -> null
            }
        }

        val root = readObject(rootIndex)
        @Suppress("UNCHECKED_CAST")
        return (root as? Map<String, Any?>) ?: emptyMap()
    }

    private fun readSizedInt(data: ByteArray, offset: Int, bytes: Int): Int {
        var v = 0
        for (i in 0 until bytes) {
            v = (v shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return v
    }

    private fun readSizedLong(data: ByteArray, offset: Int, bytes: Int): Long {
        var v = 0L
        for (i in 0 until bytes) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readCount(data: ByteArray, offset: Int): Int {
        val marker = data[offset].toInt() and 0xFF
        val bytes = 1 shl (marker and 0x0F)
        return readSizedInt(data, offset + 1, bytes)
    }

    fun makeStreamPlist(
        controlPort: Int,
        sharedSecret: ByteArray,
        streamConnectionId: Long,
        sampleRate: Int = 44100,
        spf: Int = 352,
        payloadType: Int = 96
    ): ByteArray = encode(mapOf(
        "streams" to listOf(mapOf(
            "audioFormat" to 262144L,
            "audioMode" to "default",
            "controlPort" to controlPort.toLong(),
            "ct" to 2L,
            "isMedia" to true,
            "latencyMax" to 88200L,
            "latencyMin" to 11025L,
            "shk" to sharedSecret,
            "spf" to spf.toLong(),
            "sr" to sampleRate.toLong(),
            "type" to payloadType.toLong(),
            "supportsDynamicStreamID" to false,
            "streamConnectionID" to streamConnectionId
        ))
    ))
}
