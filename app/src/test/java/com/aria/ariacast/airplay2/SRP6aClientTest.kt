package com.aria.ariacast.airplay2

import org.junit.Assert.*
import org.junit.Test

class SRP6aClientTest {

    @Test
    fun `buildM1 contains STATE=1 and METHOD=0`() {
        val srp = SRP6aClient("3939")
        val parsed = TlvUtil.parse(srp.buildM1())
        assertEquals(1.toByte(), parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0))
        assertEquals(0.toByte(), parsed[TlvUtil.TLV_METHOD]?.firstOrNull()?.get(0))
    }

    @Test
    fun `buildM1 contains no unexpected TLV types`() {
        val srp = SRP6aClient("1234")
        val parsed = TlvUtil.parse(srp.buildM1())
        assertTrue(parsed.keys.all { it == TlvUtil.TLV_STATE || it == TlvUtil.TLV_METHOD })
    }

    @Test
    fun `sharedKeyBytes is null before pairing`() {
        assertNull(SRP6aClient("3939").sharedKeyBytes)
    }

    @Test
    fun `processM2 returns null when state byte is wrong`() {
        val srp = SRP6aClient("3939")
        srp.buildM1()
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(5),         // wrong state (should be 2)
            TlvUtil.TLV_SALT to ByteArray(16),
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 returns null when salt is wrong size`() {
        val srp = SRP6aClient("3939")
        srp.buildM1()
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(8),            // should be 16 bytes
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 returns null on empty body`() {
        val srp = SRP6aClient("3939")
        srp.buildM1()
        assertNull(srp.processM2(ByteArray(0)))
    }

    @Test
    fun `verifyM4 returns false on wrong proof`() {
        val srp = SRP6aClient("3939")
        srp.buildM1()
        // Synthesize a fake M2 with correct state and sizes so processM2 proceeds
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(16) { it.toByte() },
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        val m3 = srp.processM2(fakeM2) // runs SRP math on fake B
        assertNotNull("processM2 should succeed with valid sizes", m3)

        // Now send a wrong M4 (random proof)
        val fakeM4 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to ByteArray(64) { 0xFF.toByte() } // wrong proof
        )
        assertFalse(srp.verifyM4(fakeM4))
    }

    @Test
    fun `buildM1 result is parseable as TLV`() {
        val srp = SRP6aClient("0000")
        val m1 = srp.buildM1()
        val parsed = TlvUtil.parse(m1)
        assertFalse(parsed.isEmpty())
    }

    @Test
    fun `different pins produce same M1 structure`() {
        // M1 is always the same structure regardless of PIN (PIN is used in M3)
        val m1a = TlvUtil.parse(SRP6aClient("1234").buildM1())
        val m1b = TlvUtil.parse(SRP6aClient("5678").buildM1())
        assertArrayEquals(m1a[TlvUtil.TLV_STATE]?.firstOrNull(), m1b[TlvUtil.TLV_STATE]?.firstOrNull())
        assertArrayEquals(m1a[TlvUtil.TLV_METHOD]?.firstOrNull(), m1b[TlvUtil.TLV_METHOD]?.firstOrNull())
    }
}
