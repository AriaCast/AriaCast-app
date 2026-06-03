package com.aria.ariacast.airplay2

import org.junit.Assert.*
import org.junit.Test

class TlvUtilTest {

    @Test
    fun `build and parse single entry`() {
        val data = byteArrayOf(1, 2, 3)
        val parsed = TlvUtil.parse(TlvUtil.build(TlvUtil.TLV_STATE to data))
        assertArrayEquals(data, parsed[TlvUtil.TLV_STATE]?.firstOrNull())
    }

    @Test
    fun `build and parse multiple entries`() {
        val state = byteArrayOf(1)
        val method = byteArrayOf(0)
        val parsed = TlvUtil.parse(TlvUtil.build(
            TlvUtil.TLV_STATE to state,
            TlvUtil.TLV_METHOD to method
        ))
        assertArrayEquals(state, parsed[TlvUtil.TLV_STATE]?.firstOrNull())
        assertArrayEquals(method, parsed[TlvUtil.TLV_METHOD]?.firstOrNull())
    }

    @Test
    fun `build and parse empty value`() {
        val parsed = TlvUtil.parse(TlvUtil.build(TlvUtil.TLV_FLAGS to ByteArray(0)))
        assertArrayEquals(ByteArray(0), parsed[TlvUtil.TLV_FLAGS]?.firstOrNull())
    }

    @Test
    fun `getFirst returns correct value`() {
        val pk = ByteArray(32) { it.toByte() }
        val built = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_PUBLIC_KEY to pk
        )
        assertArrayEquals(pk, TlvUtil.getFirst(built, TlvUtil.TLV_PUBLIC_KEY))
    }

    @Test
    fun `getFirst returns null for missing type`() {
        val built = TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(1))
        assertNull(TlvUtil.getFirst(built, TlvUtil.TLV_SIGNATURE))
    }

    @Test
    fun `parse handles 384-byte public key via chunking`() {
        val bigValue = ByteArray(384) { (it and 0xFF).toByte() }
        val built = TlvUtil.build(TlvUtil.TLV_PUBLIC_KEY to bigValue)
        // 384 bytes split into 255 + 129: each chunk has 2-byte header
        assertEquals(2 + 255 + 2 + 129, built.size)
        assertArrayEquals(bigValue, TlvUtil.parse(built)[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull())
    }

    @Test
    fun `parse empty bytes returns empty map`() {
        assertTrue(TlvUtil.parse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `wire format is standard HAP TLV8 with 1-byte length`() {
        val value = byteArrayOf(0x12, 0x34, 0x56)
        val built = TlvUtil.build(TlvUtil.TLV_PROOF to value)
        assertEquals(5, built.size)
        assertEquals(TlvUtil.TLV_PROOF, built[0].toInt() and 0xFF)
        assertEquals(3, built[1].toInt() and 0xFF)  // 1-byte length
        assertEquals(0x12.toByte(), built[2])
        assertEquals(0x34.toByte(), built[3])
        assertEquals(0x56.toByte(), built[4])
    }

    @Test
    fun `buildSingle produces identical result to build with one entry`() {
        val value = ByteArray(16) { 0xAB.toByte() }
        val viaMulti = TlvUtil.build(TlvUtil.TLV_ENCRYPTED_DATA to value)
        val viaSingle = TlvUtil.buildSingle(TlvUtil.TLV_ENCRYPTED_DATA, value)
        assertArrayEquals(viaMulti, viaSingle)
    }

    @Test
    fun `round-trip preserves all known TLV types`() {
        val types = listOf(
            TlvUtil.TLV_METHOD,
            TlvUtil.TLV_IDENTIFIER,
            TlvUtil.TLV_SALT,
            TlvUtil.TLV_PUBLIC_KEY,
            TlvUtil.TLV_STATE,
            TlvUtil.TLV_SIGNATURE
        )
        val pairs = types.mapIndexed { i, t -> t to byteArrayOf(i.toByte()) }.toTypedArray()
        val built = TlvUtil.build(*pairs)
        val parsed = TlvUtil.parse(built)
        types.forEachIndexed { i, t ->
            assertArrayEquals("TLV type $t", byteArrayOf(i.toByte()), parsed[t]?.firstOrNull())
        }
    }
}
