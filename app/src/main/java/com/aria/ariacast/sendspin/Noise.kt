package com.aria.ariacast.sendspin

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise Protocol Framework (revision 34), just the parts Sendspin uses:
 * pattern `KKpsk2`, DH 25519, cipher ChaChaPoly, hash SHA256.
 * Verified against the cacophony test vector in NoiseTest.
 */
object Noise {
    const val PROTOCOL = "Noise_KKpsk2_25519_ChaChaPoly_SHA256"
    const val DHLEN = 32
    const val HASHLEN = 32
    const val TAGLEN = 16
    /** A Noise transport message is at most 65535 bytes. */
    const val MAX_MESSAGE = 65535

    private val random = SecureRandom()

    class KeyPair(val private: ByteArray, val public: ByteArray) {
        companion object {
            fun generate(): KeyPair = fromPrivate(ByteArray(32).also { X25519.generatePrivateKey(random, it) })
            fun fromPrivate(priv: ByteArray): KeyPair {
                val pub = ByteArray(32)
                X25519.generatePublicKey(priv, 0, pub, 0)
                return KeyPair(priv.copyOf(), pub)
            }
        }
    }

    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        require(X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) { "X25519 produced an all-zero shared secret" }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        data.forEach(mac::update)
        return mac.doFinal()
    }

    /** Noise HKDF with 2 or 3 outputs. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val o1 = hmac(temp, byteArrayOf(1))
        val o2 = hmac(temp, o1, byteArrayOf(2))
        if (outputs == 2) return listOf(o1, o2)
        val o3 = hmac(temp, o2, byteArrayOf(3))
        return listOf(o1, o2, o3)
    }

    /** CipherState with ChaChaPoly (nonce = 4 zero bytes + 64-bit little-endian counter). */
    class CipherState(private var key: ByteArray? = null) {
        var nonce: Long = 0
            private set

        val hasKey: Boolean get() = key != null

        fun initializeKey(k: ByteArray?) { key = k; nonce = 0 }

        private fun aead(encrypt: Boolean, ad: ByteArray, input: ByteArray): ByteArray {
            val k = key!!
            check(nonce != -1L) { "nonce exhausted" }
            val n = ByteArray(12)
            for (i in 0 until 8) n[4 + i] = (nonce ushr (8 * i)).toByte()
            val c = ChaCha20Poly1305()
            c.init(encrypt, AEADParameters(KeyParameter(k), TAGLEN * 8, n, ad))
            val out = ByteArray(c.getOutputSize(input.size))
            var len = c.processBytes(input, 0, input.size, out, 0)
            len += c.doFinal(out, len) // throws InvalidCipherTextException on a bad tag
            nonce++
            return if (len == out.size) out else out.copyOf(len)
        }

        fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray = if (key == null) plaintext else aead(true, ad, plaintext)
        fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray = if (key == null) ciphertext else aead(false, ad, ciphertext)
    }

    class SymmetricState(protocolName: String) {
        var ck: ByteArray
        var h: ByteArray
        val cipher = CipherState()

        init {
            val name = protocolName.toByteArray(Charsets.US_ASCII)
            h = if (name.size <= HASHLEN) name.copyOf(HASHLEN) else sha256(name)
            ck = h.copyOf()
        }

        fun mixKey(ikm: ByteArray) {
            val (newCk, tempK) = hkdf(ck, ikm, 2)
            ck = newCk
            cipher.initializeKey(tempK.copyOf(32))
        }

        fun mixHash(data: ByteArray) { h = sha256(h, data) }

        fun mixKeyAndHash(ikm: ByteArray) {
            val (newCk, tempH, tempK) = hkdf(ck, ikm, 3)
            ck = newCk
            mixHash(tempH)
            cipher.initializeKey(tempK.copyOf(32))
        }

        fun encryptAndHash(p: ByteArray): ByteArray = cipher.encryptWithAd(h, p).also(::mixHash)
        fun decryptAndHash(c: ByteArray): ByteArray = cipher.decryptWithAd(h, c).also { mixHash(c) }

        /** (initiator->responder, responder->initiator) */
        fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf(ck, ByteArray(0), 2)
            return CipherState(k1.copyOf(32)) to CipherState(k2.copyOf(32))
        }
    }

    /** Result of a finished handshake: the two transport ciphers and the handshake hash. */
    class Transport(val send: CipherState, val receive: CipherState, val handshakeHash: ByteArray)

    /**
     * KKpsk2 handshake, either role:
     *   -> s           (pre-message: initiator static known to responder)
     *   <- s           (pre-message: responder static known to initiator)
     *   -> e, es, ss
     *   <- e, ee, se, psk
     * In Sendspin the server is the initiator and the client the responder.
     */
    class KKpsk2Handshake(
        private val initiator: Boolean,
        private val s: KeyPair,
        private val rs: ByteArray,
        prologue: ByteArray,
        private val fixedEphemeral: KeyPair? = null, // test vectors only
    ) {
        val ss = SymmetricState(PROTOCOL)
        private var e: KeyPair? = null
        private var re: ByteArray? = null
        /** Must be set before the second message is written or read. */
        var psk: ByteArray? = null

        init {
            ss.mixHash(prologue)
            val initiatorStatic = if (initiator) s.public else rs
            val responderStatic = if (initiator) rs else s.public
            ss.mixHash(initiatorStatic)
            ss.mixHash(responderStatic)
        }

        /** Initiator: write message 1. */
        fun writeMessage1(payload: ByteArray): ByteArray {
            check(initiator)
            val eph = fixedEphemeral ?: KeyPair.generate()
            e = eph
            ss.mixHash(eph.public); ss.mixKey(eph.public) // psk mode: e also mixes the key
            ss.mixKey(dh(eph.private, rs))   // es
            ss.mixKey(dh(s.private, rs))     // ss
            return eph.public + ss.encryptAndHash(payload)
        }

        /** Responder: read message 1, returns its payload. */
        fun readMessage1(message: ByteArray): ByteArray {
            check(!initiator)
            require(message.size >= DHLEN + TAGLEN) { "Noise message 1 too short" }
            val remote = message.copyOfRange(0, DHLEN)
            re = remote
            ss.mixHash(remote); ss.mixKey(remote)
            ss.mixKey(dh(s.private, remote)) // es (responder side)
            ss.mixKey(dh(s.private, rs))     // ss
            return ss.decryptAndHash(message.copyOfRange(DHLEN, message.size))
        }

        /** Responder: write message 2 and finish. */
        fun writeMessage2(payload: ByteArray): Pair<ByteArray, Transport> {
            check(!initiator)
            val eph = fixedEphemeral ?: KeyPair.generate()
            e = eph
            ss.mixHash(eph.public); ss.mixKey(eph.public)
            ss.mixKey(dh(eph.private, re!!))  // ee
            ss.mixKey(dh(eph.private, rs))    // se (responder side: DH(e, rs))
            ss.mixKeyAndHash(psk ?: error("PSK not set"))
            val msg = eph.public + ss.encryptAndHash(payload)
            val (c1, c2) = ss.split()
            return msg to Transport(send = c2, receive = c1, handshakeHash = ss.h.copyOf())
        }

        /** Initiator: read message 2 and finish. Returns its payload. */
        fun readMessage2(message: ByteArray): Pair<ByteArray, Transport> {
            check(initiator)
            require(message.size >= DHLEN + TAGLEN) { "Noise message 2 too short" }
            val remote = message.copyOfRange(0, DHLEN)
            re = remote
            ss.mixHash(remote); ss.mixKey(remote)
            ss.mixKey(dh(e!!.private, remote)) // ee
            ss.mixKey(dh(s.private, remote))   // se (initiator side: DH(s, re))
            ss.mixKeyAndHash(psk ?: error("PSK not set"))
            val payload = ss.decryptAndHash(message.copyOfRange(DHLEN, message.size))
            val (c1, c2) = ss.split()
            return payload to Transport(send = c1, receive = c2, handshakeHash = ss.h.copyOf())
        }
    }
}
