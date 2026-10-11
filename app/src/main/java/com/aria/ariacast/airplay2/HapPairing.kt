package com.aria.ariacast.airplay2

import com.aria.ariacast.airplay.ProtoLog
import com.aria.ariacast.airplay.RtspConnection
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/** What we remember about a receiver after a successful PIN pairing. */
data class HapCredentials(
    /** The receiver's pairing identifier (HAP "Identifier" from M6). */
    val receiverId: String,
    /** The receiver's Ed25519 long-term public key. */
    val receiverPublicKey: ByteArray
) {
    fun serialize(): String = receiverId + ":" + receiverPublicKey.joinToString("") { "%02x".format(it) }

    companion object {
        fun deserialize(s: String?): HapCredentials? {
            if (s.isNullOrEmpty()) return null
            val idx = s.lastIndexOf(':')
            if (idx <= 0) return null
            val hex = s.substring(idx + 1)
            if (hex.length != 64) return null
            return try {
                HapCredentials(s.substring(0, idx), ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            } catch (e: NumberFormatException) { null }
        }
    }
}

class HapPairingException(message: String, val tlvError: Int = 0, val httpCode: Int = 0) : Exception(message) {
    /** kTLVError_Authentication: wrong PIN / password. */
    val isAuthenticationError: Boolean get() = tlvError == 2
}

/**
 * HomeKit-style pairing over AirPlay 2's /pair-setup and /pair-verify.
 *
 *  - transient: SRP with the fixed code "3939" (or the receiver's access
 *    password), stopping after M4; the SRP session key becomes the shared secret.
 *  - PIN: full pair-setup M1-M6 exchanging long-term Ed25519 keys, so later
 *    sessions only need pair-verify.
 *  - verify: X25519 ECDH authenticated by both long-term keys; the ECDH secret
 *    becomes the shared secret.
 */
class HapPairing(
    private val conn: RtspConnection,
    private val pairingId: String,
    private val pairingSeed: ByteArray,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        private const val TAG = "HapPairing"
        const val TRANSIENT_PIN = "3939"
    }

    private fun post(path: String, body: ByteArray, hkp: String): ByteArray {
        val resp = conn.request("POST", path, body, "application/octet-stream", mapOf("X-Apple-HKP" to hkp))
        if (!resp.isSuccess) throw HapPairingException("$path failed: ${resp.code} ${resp.reason}", httpCode = resp.code)
        val parsed = TlvUtil.parse(resp.body)
        parsed[TlvUtil.TLV_ERROR]?.firstOrNull()?.let {
            val err = if (it.isNotEmpty()) it[0].toInt() and 0xFF else 1
            throw HapPairingException("$path returned TLV error $err", tlvError = err)
        }
        return resp.body
    }

    /** Transient pairing; returns the 64-byte SRP session key. */
    fun transient(pin: String = TRANSIENT_PIN): ByteArray {
        val srp = SRP6aClient(pin, "Pair-Setup", random)
        val m2 = post("/pair-setup", srp.buildM1(transient = true), "4")
        val m3 = srp.processM2(m2) ?: throw HapPairingException("invalid M2")
        val m4 = post("/pair-setup", m3, "4")
        if (!srp.verifyM4(m4)) throw HapPairingException("receiver proof (M4) invalid", tlvError = 2)
        ProtoLog.d(TAG, "${conn.host}: transient pairing ok")
        return srp.sharedKeyBytes ?: throw HapPairingException("no session key")
    }

    /** Full pair-setup with the code shown by the receiver. */
    fun pairSetup(pin: String): HapCredentials {
        val srp = SRP6aClient(pin, "Pair-Setup", random)
        val m2 = post("/pair-setup", srp.buildM1(transient = false), "3")
        val m3 = srp.processM2(m2) ?: throw HapPairingException("invalid M2")
        val m4 = post("/pair-setup", m3, "3")
        if (!srp.verifyM4(m4)) throw HapPairingException("wrong PIN (M4 proof invalid)", tlvError = 2)
        val identity = AirPlay2Crypto.ed25519KeyPairFromSeed(pairingSeed)
        val m5 = srp.buildM5(identity, pairingId) ?: throw HapPairingException("could not build M5")
        val m6 = post("/pair-setup", m5, "3")
        val (id, pk) = srp.verifyM6(m6) ?: throw HapPairingException("receiver signature (M6) invalid")
        ProtoLog.i(TAG, "${conn.host}: paired with ${String(id, Charsets.UTF_8)}")
        return HapCredentials(String(id, Charsets.UTF_8), pk)
    }

    /**
     * Pair-verify; returns the 32-byte X25519 shared secret. When [credentials] is
     * known the receiver's signature is checked against its long-term key.
     */
    fun verify(credentials: HapCredentials?): ByteArray {
        val identity = AirPlay2Crypto.ed25519KeyPairFromSeed(pairingSeed)
        val ephemeral = AirPlay2Crypto.generateCurve25519KeyPair()
        val ourPub = AirPlay2Crypto.getPublicKeyBytes(ephemeral)

        val r1 = TlvUtil.parse(post("/pair-verify", TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_PUBLIC_KEY to ourPub
        ), "3"))
        val theirPub = r1[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull()?.takeIf { it.size == 32 }
            ?: throw HapPairingException("pair-verify M2: no public key")
        val encrypted = r1[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull()?.takeIf { it.size >= 16 }
            ?: throw HapPairingException("pair-verify M2: no encrypted data")

        val shared = AirPlay2Crypto.curve25519Agree(ephemeral.private as X25519PrivateKeyParameters, theirPub)
        val key = AirPlay2Crypto.hkdfSha512("Pair-Verify-Encrypt-Salt".toByteArray(), shared, "Pair-Verify-Encrypt-Info".toByteArray(), 32)

        val inner = try {
            TlvUtil.parse(AirPlay2Crypto.chacha20Poly1305Open(key, nonce("PV-Msg02"), encrypted, 0, encrypted.size, ByteArray(0)))
        } catch (e: Exception) {
            throw HapPairingException("pair-verify M2 decryption failed")
        }
        val theirId = inner[TlvUtil.TLV_IDENTIFIER]?.firstOrNull() ?: throw HapPairingException("pair-verify M2: no identifier")
        val theirSig = inner[TlvUtil.TLV_SIGNATURE]?.firstOrNull() ?: throw HapPairingException("pair-verify M2: no signature")
        if (credentials != null) {
            if (!AirPlay2Crypto.ed25519Verify(credentials.receiverPublicKey, theirPub + theirId + ourPub, theirSig)) {
                throw HapPairingException("pair-verify: receiver signature doesn't match its paired key")
            }
        }

        val idBytes = pairingId.toByteArray(Charsets.UTF_8)
        val signature = AirPlay2Crypto.ed25519Sign(identity, ourPub + idBytes + theirPub)
        val m3inner = TlvUtil.build(TlvUtil.TLV_IDENTIFIER to idBytes, TlvUtil.TLV_SIGNATURE to signature)
        val m3sealed = AirPlay2Crypto.chacha20Poly1305Seal(key, nonce("PV-Msg03"), m3inner, 0, m3inner.size, ByteArray(0))
        post("/pair-verify", TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_ENCRYPTED_DATA to m3sealed
        ), "3")
        ProtoLog.d(TAG, "${conn.host}: pair-verify ok")
        return shared
    }

    private fun nonce(label: String): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(label.toByteArray(Charsets.UTF_8), 0, n, 4, 8)
        return n
    }
}
