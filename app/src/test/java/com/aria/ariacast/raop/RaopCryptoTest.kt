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
}
