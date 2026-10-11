package com.aria.ariacast.raop

import com.aria.ariacast.airplay.ProtoLog
import com.aria.ariacast.airplay.RtspConnection
import com.aria.ariacast.airplay.RtspException
import com.aria.ariacast.airplay2.AirPlay2Crypto
import com.aria.ariacast.airplay2.BinaryPlist
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Legacy" AirPlay pairing, used by AirPlay 1 era receivers (Apple TV 2/3 and
 * friends) when "Require onscreen code" is enabled: the receiver answers RTSP
 * requests with 470 until the sender has paired with the 4-digit code shown on
 * screen, and then proves its identity with /pair-verify on every connection.
 *
 * Pairing: POST /pair-pin-start (shows the code), then three /pair-setup-pin
 * binary-plist exchanges - SRP-6a (SHA-1, RFC 5054 2048-bit group, user = our
 * pairing id, password = the code) followed by handing over our Ed25519 public
 * key under AES-128-GCM keyed from the SRP session key.
 *
 * Verify: an X25519 exchange; we sign both ephemeral keys with our long-term
 * Ed25519 key and send the signature AES-128-CTR encrypted with keys derived
 * from the shared secret.
 */
object RaopLegacyPairing {
    private const val TAG = "RaopLegacyPairing"
    private val random = SecureRandom()

    /** Asks the receiver to display its pairing code. */
    fun startPinDisplay(conn: RtspConnection) {
        val resp = conn.request("POST", "/pair-pin-start", protocol = "RTSP/1.0")
        if (!resp.isSuccess) throw RtspException("pair-pin-start rejected: ${resp.code}", resp.code)
    }

    /** Runs pair-setup-pin; on success the receiver remembers [identitySeed]'s public key. */
    fun pairSetup(conn: RtspConnection, pairingId: String, pin: String, identitySeed: ByteArray) {
        val identity = AirPlay2Crypto.ed25519KeyPairFromSeed(identitySeed)
        val publicKey = AirPlay2Crypto.getEd25519PublicKey(identity)

        val r1 = post(conn, BinaryPlist.encode(mapOf("method" to "pin", "user" to pairingId)))
        val pkB = r1["pk"] as? ByteArray ?: throw RtspException("pair-setup-pin: no server public key")
        val salt = r1["salt"] as? ByteArray ?: throw RtspException("pair-setup-pin: no salt")

        val srp = LegacySrp(pairingId, pin, random)
        val (pkA, m1) = srp.respond(salt, pkB)
        val r2 = post(conn, BinaryPlist.encode(mapOf("pk" to pkA, "proof" to m1)))
        val m2 = r2["proof"] as? ByteArray ?: throw RtspException("pair-setup-pin: wrong PIN (no proof)", 470)
        if (!srp.verify(m2)) throw RtspException("pair-setup-pin: server proof mismatch", 470)

        val k = srp.sessionKey
        val aesKey = sha512("Pair-Setup-AES-Key".toByteArray() + k).copyOf(16)
        val aesIv = sha512("Pair-Setup-AES-IV".toByteArray() + k).copyOf(16)
        aesIv[15] = (aesIv[15] + 1).toByte()
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, aesIv))
        val sealed = gcm.doFinal(publicKey)
        post(conn, BinaryPlist.encode(mapOf(
            "epk" to sealed.copyOfRange(0, sealed.size - 16),
            "authTag" to sealed.copyOfRange(sealed.size - 16, sealed.size)
        )), expectBody = false)
        ProtoLog.i(TAG, "legacy pairing with ${conn.host} complete")
    }

    /** Proves our identity; throws if the receiver doesn't accept it (i.e. we're not paired). */
    fun pairVerify(conn: RtspConnection, identitySeed: ByteArray) {
        val identity = AirPlay2Crypto.ed25519KeyPairFromSeed(identitySeed)
        val edPublic = AirPlay2Crypto.getEd25519PublicKey(identity)
        val ephemeral = AirPlay2Crypto.generateCurve25519KeyPair()
        val curvePublic = AirPlay2Crypto.getPublicKeyBytes(ephemeral)

        val m1 = byteArrayOf(1, 0, 0, 0) + curvePublic + edPublic
        val r1 = conn.request("POST", "/pair-verify", m1, "application/octet-stream")
        if (!r1.isSuccess || r1.body.size < 96) throw RtspException("pair-verify rejected: ${r1.code}", r1.code)
        val serverCurve = r1.body.copyOfRange(0, 32)
        val serverSigEncrypted = r1.body.copyOfRange(32, 96)

        val shared = AirPlay2Crypto.curve25519Agree(ephemeral.private as X25519PrivateKeyParameters, serverCurve)
        val key = sha512("Pair-Verify-AES-Key".toByteArray() + shared).copyOf(16)
        val iv = sha512("Pair-Verify-AES-IV".toByteArray() + shared).copyOf(16)
        val ctr = Cipher.getInstance("AES/CTR/NoPadding")
        ctr.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        ctr.update(serverSigEncrypted) // advance the keystream past the receiver's signature
        val signature = AirPlay2Crypto.ed25519Sign(identity, curvePublic + serverCurve)
        val encrypted = ctr.update(signature)

        val r2 = conn.request("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + encrypted, "application/octet-stream")
        if (!r2.isSuccess) throw RtspException("pair-verify signature rejected: ${r2.code}", r2.code)
        ProtoLog.d(TAG, "legacy pair-verify with ${conn.host} ok")
    }

    private fun post(conn: RtspConnection, body: ByteArray, expectBody: Boolean = true): Map<String, Any?> {
        val resp = conn.request("POST", "/pair-setup-pin", body, "application/x-apple-binary-plist")
        if (!resp.isSuccess) throw RtspException("pair-setup-pin failed: ${resp.code}", resp.code)
        if (!expectBody || resp.body.isEmpty()) return emptyMap()
        return BinaryPlist.decode(resp.body)
    }

    private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)
}

/**
 * The SRP-6a flavour used by legacy AirPlay pairing: SHA-1, RFC 5054 2048-bit
 * group, k = H(N | PAD(g)), u = H(PAD(A) | PAD(B)), x = H(s | H(I ":" P)) and
 * Apple's 40-byte session key K = H(S | 0x00000000) | H(S | 0x00000001).
 */
class LegacySrp(private val username: String, private val password: String, private val random: SecureRandom) {
    companion object {
        val N: BigInteger = SRP6StandardGroups.rfc5054_2048.n
        val g: BigInteger = SRP6StandardGroups.rfc5054_2048.g
        private const val N_BYTES = 256
    }

    lateinit var sessionKey: ByteArray; private set
    private lateinit var aBytes: ByteArray
    private lateinit var m1: ByteArray

    fun respond(salt: ByteArray, serverPublic: ByteArray): Pair<ByteArray, ByteArray> {
        val B = BigInteger(1, serverPublic)
        if (B.mod(N) == BigInteger.ZERO) throw IllegalArgumentException("invalid server public key")
        val a = BigInteger(1, ByteArray(32).also(random::nextBytes))
        val A = g.modPow(a, N)
        val k = BigInteger(1, h(pad(N) + pad(g)))
        val u = BigInteger(1, h(pad(A) + pad(B)))
        if (u == BigInteger.ZERO) throw IllegalArgumentException("invalid u")
        val x = BigInteger(1, h(salt + h("$username:$password".toByteArray(Charsets.UTF_8))))
        val S = B.subtract(k.multiply(g.modPow(x, N))).mod(N).modPow(a.add(u.multiply(x)), N)
        val sBytes = minimal(S)
        sessionKey = h(sBytes + byteArrayOf(0, 0, 0, 0)) + h(sBytes + byteArrayOf(0, 0, 0, 1))
        aBytes = minimal(A)
        val hN = h(minimal(N))
        val hg = h(minimal(g))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hg[it].toInt()).toByte() }
        m1 = h(xor + h(username.toByteArray(Charsets.UTF_8)) + salt + aBytes + minimal(B) + sessionKey)
        return aBytes to m1
    }

    fun verify(serverProof: ByteArray): Boolean = h(aBytes + m1 + sessionKey).contentEquals(serverProof)

    private fun h(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    private fun minimal(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun pad(n: BigInteger): ByteArray {
        val m = minimal(n)
        return if (m.size >= N_BYTES) m else ByteArray(N_BYTES - m.size) + m
    }
}
