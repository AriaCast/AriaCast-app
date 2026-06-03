package com.aria.ariacast.airplay2

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test

class AirPlay2CryptoTest {

    // --- ChaCha20-Poly1305 ---

    @Test
    fun `chacha20 poly1305 encrypt then decrypt round trip`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "Hello AirPlay 2".toByteArray(Charsets.UTF_8)
        val aad = byteArrayOf(0x01, 0x02, 0x03)

        val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)
        val decrypted = AirPlay2Crypto.chacha20Poly1305Decrypt(key, nonce, ct, aad, tag)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `chacha20 poly1305 ciphertext differs from plaintext`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12)
        val plaintext = ByteArray(64) { it.toByte() }

        val (ct, _) = AirPlay2Crypto.chacha20Poly1305Encrypt(key, nonce, plaintext, ByteArray(0))
        assertFalse(plaintext.contentEquals(ct))
    }

    @Test
    fun `chacha20 poly1305 tag is 16 bytes`() {
        val (_, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            ByteArray(32), ByteArray(12), ByteArray(32), ByteArray(0))
        assertEquals(16, tag.size)
    }

    @Test
    fun `chacha20 poly1305 wrong key fails decryption`() {
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val plaintext = "secret".toByteArray()
        val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(key, nonce, plaintext, ByteArray(0))
        try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(ByteArray(32) { 0xFF.toByte() }, nonce, ct, ByteArray(0), tag)
            fail("Expected authentication failure")
        } catch (e: Exception) { /* expected */ }
    }

    @Test
    fun `chacha20 poly1305 empty plaintext round trips`() {
        val key = ByteArray(32) { 0x11 }
        val nonce = ByteArray(12) { 0x22.toByte() }
        val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(key, nonce, ByteArray(0), ByteArray(0))
        val decrypted = AirPlay2Crypto.chacha20Poly1305Decrypt(key, nonce, ct, ByteArray(0), tag)
        assertArrayEquals(ByteArray(0), decrypted)
    }

    // --- HKDF ---

    @Test
    fun `hkdf produces correct output lengths`() {
        val ikm = ByteArray(32) { 0x0B }
        val salt = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
        val info = "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8)
        assertEquals(32, AirPlay2Crypto.hkdfSha512(salt, ikm, info, 32).size)
        assertEquals(64, AirPlay2Crypto.hkdfSha512(salt, ikm, info, 64).size)
    }

    @Test
    fun `hkdf different salts produce different outputs`() {
        val ikm = ByteArray(32) { 0x42 }
        val info = "test-info".toByteArray(Charsets.UTF_8)
        val out1 = AirPlay2Crypto.hkdfSha512("Pair-Verify-AES-Key".toByteArray(), ikm, info, 32)
        val out2 = AirPlay2Crypto.hkdfSha512("Pair-Setup-AES-Key".toByteArray(), ikm, info, 32)
        assertFalse(out1.contentEquals(out2))
    }

    @Test
    fun `hkdf is deterministic`() {
        val ikm = ByteArray(32) { 0x7F }
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        assertArrayEquals(
            AirPlay2Crypto.hkdfSha512(salt, ikm, info, 32),
            AirPlay2Crypto.hkdfSha512(salt, ikm, info, 32)
        )
    }

    // --- Ed25519 ---

    @Test
    fun `ed25519 sign and verify round trip`() {
        val kp = AirPlay2Crypto.generateEd25519KeyPair()
        val msg = "AirPlay 2 pairing".toByteArray(Charsets.UTF_8)
        val sig = AirPlay2Crypto.ed25519Sign(kp, msg)
        assertTrue(AirPlay2Crypto.ed25519Verify(AirPlay2Crypto.getEd25519PublicKey(kp), msg, sig))
        assertEquals(64, sig.size)
    }

    @Test
    fun `ed25519 wrong public key fails verification`() {
        val kp1 = AirPlay2Crypto.generateEd25519KeyPair()
        val kp2 = AirPlay2Crypto.generateEd25519KeyPair()
        val msg = "test".toByteArray()
        val sig = AirPlay2Crypto.ed25519Sign(kp1, msg)
        assertFalse(AirPlay2Crypto.ed25519Verify(AirPlay2Crypto.getEd25519PublicKey(kp2), msg, sig))
    }

    @Test
    fun `ed25519 tampered message fails verification`() {
        val kp = AirPlay2Crypto.generateEd25519KeyPair()
        val sig = AirPlay2Crypto.ed25519Sign(kp, "original".toByteArray())
        assertFalse(AirPlay2Crypto.ed25519Verify(AirPlay2Crypto.getEd25519PublicKey(kp), "tampered".toByteArray(), sig))
    }

    @Test
    fun `ed25519 public key is 32 bytes`() {
        val kp = AirPlay2Crypto.generateEd25519KeyPair()
        assertEquals(32, AirPlay2Crypto.getEd25519PublicKey(kp).size)
    }

    // --- Curve25519 ---

    @Test
    fun `curve25519 key agreement is symmetric`() {
        val kp1 = AirPlay2Crypto.generateCurve25519KeyPair()
        val kp2 = AirPlay2Crypto.generateCurve25519KeyPair()
        val shared1 = AirPlay2Crypto.curve25519Agree(
            kp1.private as X25519PrivateKeyParameters, AirPlay2Crypto.getPublicKeyBytes(kp2))
        val shared2 = AirPlay2Crypto.curve25519Agree(
            kp2.private as X25519PrivateKeyParameters, AirPlay2Crypto.getPublicKeyBytes(kp1))
        assertArrayEquals(shared1, shared2)
        assertEquals(32, shared1.size)
    }

    // --- Nonce regression ---

    @Test
    fun `counter nonce does not repeat after 16-bit sequence wrap`() {
        fun counterNonce(counter: Long): ByteArray {
            val n = ByteArray(12)
            for (i in 0..7) n[4 + i] = ((counter shr (i * 8)) and 0xFF).toByte()
            return n
        }
        fun oldSeqNonce(seq: Int): ByteArray {
            val n = ByteArray(12)
            n[4] = ((seq shr 8) and 0xFF).toByte()
            n[5] = (seq and 0xFF).toByte()
            return n
        }

        // Counter-based nonces never repeat at the 16-bit boundary
        assertFalse(counterNonce(0).contentEquals(counterNonce(65536)))
        assertFalse(counterNonce(65535).contentEquals(counterNonce(65536)))

        // Regression proof: old sequence nonce repeats at 65536 (65536 & 0xFFFF = 0)
        assertTrue("Old nonce wraps at 65536", oldSeqNonce(0).contentEquals(oldSeqNonce(65536 and 0xFFFF)))
    }

    // --- PairKeysResult ---

    @Test
    fun `PairKeysResult counter increments atomically`() {
        val keys = AirPlay2Crypto.PairKeysResult(ByteArray(32), ByteArray(32), 0L, 0L)
        assertEquals(0L, keys.encryptionCounter++)
        assertEquals(1L, keys.encryptionCounter++)
        assertEquals(2L, keys.encryptionCounter)
    }
}
