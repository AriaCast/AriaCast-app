package com.aria.ariacast.airplay2

import org.junit.Assert.*
import org.junit.Test

class BinaryPlistTest {

    @Test
    fun `encode and decode round-trip simple strings`() {
        val input = mapOf("title" to "Test Track", "artist" to "Test Artist")
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("Test Track", decoded["title"])
        assertEquals("Test Artist", decoded["artist"])
    }

    @Test
    fun `encode and decode round-trip with long string`() {
        val longString = "A".repeat(20) // > 15 chars exercises extended length encoding
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("key" to longString)))
        assertEquals(longString, decoded["key"])
    }

    @Test
    fun `encode and decode round-trip with Long values`() {
        val input = mapOf("port" to 7000L, "zero" to 0L, "big" to 70000L)
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals(7000L, decoded["port"])
        assertEquals(0L, decoded["zero"])
        assertEquals(70000L, decoded["big"])
    }

    @Test
    fun `encode and decode round-trip with ByteArray value`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0xAB.toByte())
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("raw" to data)))
        assertArrayEquals(data, decoded["raw"] as? ByteArray)
    }

    @Test
    fun `encode and decode empty ByteArray`() {
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("empty" to ByteArray(0))))
        assertArrayEquals(ByteArray(0), decoded["empty"] as? ByteArray)
    }

    @Test
    fun `multiple DMAP keys round-trip`() {
        val input = linkedMapOf<String, Any?>(
            "dmap.itemname" to "Song Title",
            "daap.songartist" to "Artist Name",
            "daap.songalbum" to "Album Name"
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("Song Title", decoded["dmap.itemname"])
        assertEquals("Artist Name", decoded["daap.songartist"])
        assertEquals("Album Name", decoded["daap.songalbum"])
    }

    @Test
    fun `encoded bytes start with bplist00 magic`() {
        val encoded = BinaryPlist.encode(mapOf("k" to "v"))
        assertEquals("bplist00", encoded.copyOf(8).toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on non-plist data`() {
        BinaryPlist.decode("not a plist".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on too short data`() {
        BinaryPlist.decode(ByteArray(10))
    }

    @Test
    fun `nested containers, unicode, doubles, booleans and negatives round-trip`() {
        val input = linkedMapOf<String, Any?>(
            "streams" to listOf(mapOf("type" to 96L, "shk" to ByteArray(32) { it.toByte() }, "isMedia" to true)),
            "name" to "Küche ♫ Speaker",
            "volume" to -12.5,
            "offset" to -3L,
            "flag" to false
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        val stream = (decoded["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(96L, stream["type"])
        assertArrayEquals(ByteArray(32) { it.toByte() }, stream["shk"] as ByteArray)
        assertEquals(true, stream["isMedia"])
        assertEquals("Küche ♫ Speaker", decoded["name"])
        assertEquals(-12.5, decoded["volume"] as Double, 0.0)
        assertEquals(-3L, decoded["offset"])
        assertEquals(false, decoded["flag"])
    }

    @Test
    fun `identical values are encoded as distinct objects`() {
        // The old encoder deduplicated by hashCode alone, so values with colliding hashes
        // silently referenced the wrong object; equal values must still decode correctly.
        val input = linkedMapOf<String, Any?>("a" to "same", "b" to "same", "c" to listOf("same", "same"))
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("same", decoded["a"])
        assertEquals("same", decoded["b"])
        assertEquals(listOf("same", "same"), decoded["c"])
    }

    @Test
    fun `list roots and more than 255 objects encode correctly`() {
        val big = (0 until 300).map { "item$it" }
        val decoded = BinaryPlist.decodeValue(BinaryPlist.encodeValue(big))
        assertEquals(big, decoded)
    }
}
