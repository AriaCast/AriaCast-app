package com.aria.ariacast.raop

import org.junit.Assert.*
import org.junit.Test

class RaopCryptoTest {
    @Test
    fun testEncryptAesKey() {
        val aesKey = ByteArray(16) { 0x42.toByte() }
        val encrypted = RaopCrypto.encryptAesKey(aesKey)
        // 2048-bit RSA produces 256-byte output
        assertEquals(256, encrypted.size)
        // OAEP is randomised
        assertFalse(encrypted.contentEquals(RaopCrypto.encryptAesKey(aesKey)))
    }

    @Test
    fun `AirPort public key is the real 2048-bit key`() {
        val n = RaopCrypto.airportPublicKey.modulus
        assertEquals(2048, n.bitLength())
        assertTrue(n.toString(16).uppercase().startsWith("E7D744F2A2E2788B6C1F55A08EB70544"))
        assertEquals(65537, RaopCrypto.airportPublicKey.exponent.toInt())
    }

    @Test
    fun testEncryptAudio() {
        val aesKey = ByteArray(16) { 0x01.toByte() }
        val iv = ByteArray(16) { 0x02.toByte() }
        val crypto = RaopCrypto()
        crypto.initAes(aesKey, iv)

        val data = ByteArray(16) { 0x00.toByte() }
        val encrypted1 = crypto.encryptAudio(data)
        val encrypted2 = crypto.encryptAudio(data)

        // In RAOP, IV is reset for every packet, so same data should give same ciphertext
        assertArrayEquals(encrypted1, encrypted2)
        assertNotEquals(data[0], encrypted1[0])
    }

    @Test
    fun `trailing partial block stays in the clear and length is unchanged`() {
        val crypto = RaopCrypto().apply { initAes(ByteArray(16) { 1 }, ByteArray(16) { 2 }) }
        val payload = ByteArray(1412) { (it * 13).toByte() } // one ALAC frame
        val enc = crypto.encryptAudio(payload)
        assertEquals(payload.size, enc.size)
        val whole = 1412 / 16 * 16
        assertFalse(payload.copyOf(whole).contentEquals(enc.copyOf(whole)))
        assertArrayEquals(payload.copyOfRange(whole, payload.size), enc.copyOfRange(whole, enc.size))
    }

    @Test
    fun `garbage Apple-Response does not verify`() {
        assertFalse(RaopCrypto.verifyAppleResponse("AAAA", ByteArray(16), ByteArray(4), ByteArray(6)))
    }
}
