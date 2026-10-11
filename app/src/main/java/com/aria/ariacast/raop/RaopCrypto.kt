package com.aria.ariacast.raop

import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import java.math.BigInteger
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay 1 (RAOP) audio encryption: a random AES-128 key, RSA-OAEP(SHA-1)
 * encrypted to the public key every AirPort Express (and every RAOP receiver
 * that supports et=1) has baked in, announced in the SDP; audio payloads are
 * then AES-128-CBC encrypted with the IV reset for every packet.
 */
class RaopCrypto {
    companion object {
        /** Modulus of the AirPort Express RSA key (2048 bit, e = 65537). */
        private const val AIRPORT_MODULUS_B64 =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9u" +
            "cRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf" +
            "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZ" +
            "eCXg/IdTQ+x4IRdiXNv5hEew=="

        val airportPublicKey: RSAKeyParameters by lazy {
            RSAKeyParameters(false, BigInteger(1, Base64.getDecoder().decode(AIRPORT_MODULUS_B64)), BigInteger.valueOf(65537))
        }

        /** RSA-OAEP (SHA-1, MGF1-SHA-1) - what the `a=rsaaeskey` SDP attribute carries. */
        fun encryptAesKey(aesKey: ByteArray): ByteArray {
            val oaep = OAEPEncoding(RSAEngine())
            oaep.init(true, airportPublicKey)
            return oaep.processBlock(aesKey, 0, aesKey.size)
        }

        /**
         * Checks an `Apple-Response` header against the challenge we sent. A receiver
         * holding the AirPort private key signs (PKCS#1 v1.5 type 1) the challenge
         * followed by its own IP address and MAC (the hex prefix of its RAOP service
         * name), zero-padded to at least 32 bytes.
         */
        fun verifyAppleResponse(response: String, challenge: ByteArray, receiverIp: ByteArray, receiverMac: ByteArray): Boolean {
            return try {
                val sig = Base64.getDecoder().decode(padBase64(response.trim()))
                val pkcs1 = PKCS1Encoding(RSAEngine())
                pkcs1.init(false, airportPublicKey)
                val recovered = pkcs1.processBlock(sig, 0, sig.size)
                val raw = challenge + receiverIp + receiverMac
                val expected = if (raw.size < 32) raw.copyOf(32) else raw
                recovered.contentEquals(expected)
            } catch (e: Exception) {
                false
            }
        }

        fun padBase64(s: String): String = s + "=".repeat((4 - s.length % 4) % 4)
    }

    private lateinit var secretKeySpec: SecretKeySpec
    private lateinit var ivSpec: IvParameterSpec
    // Reused across packets (re-initialised each time to reset the IV); only the RTP
    // pacer thread encrypts, so no locking is needed.
    private val cipher: Cipher by lazy { Cipher.getInstance("AES/CBC/NoPadding") }

    fun initAes(aesKey: ByteArray, iv: ByteArray) {
        secretKeySpec = SecretKeySpec(aesKey, "AES")
        ivSpec = IvParameterSpec(iv)
    }

    /**
     * Encrypts one audio payload. Only the whole 16-byte blocks are encrypted; the
     * trailing partial block is sent in the clear (that's how every receiver
     * decrypts it - padding would change the payload length and corrupt the frame).
     */
    fun encryptAudio(data: ByteArray): ByteArray {
        val whole = data.size and 0xF.inv()
        val out = data.copyOf()
        if (whole > 0) {
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
            cipher.doFinal(data, 0, whole, out, 0)
        }
        return out
    }
}
