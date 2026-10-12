package com.aria.ariacast.raop

import com.aria.ariacast.airplay.RtspCodec
import com.aria.ariacast.airplay.RtspConnection
import com.aria.ariacast.airplay.RtspException
import com.aria.ariacast.airplay.RtspMessage
import com.aria.ariacast.airplay2.AirPlay2Crypto
import com.aria.ariacast.airplay2.BinaryPlist
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Exercises legacy (Apple TV 2/3 onscreen-code) pairing against a fake receiver
 * that implements the receiver side of the algorithm independently.
 */
class RaopLegacyPairingTest {
    private val pin = "4821"
    private val clientSeed = ByteArray(32) { (it + 1).toByte() }
    private val clientPub = AirPlay2Crypto.getEd25519PublicKey(AirPlay2Crypto.ed25519KeyPairFromSeed(clientSeed))

    private class FakeAppleTv(val pin: String) {
        val server = ServerSocket(0)
        val serverIdentity = AirPlay2Crypto.generateEd25519KeyPair()
        var pairedKey: ByteArray? = null
        var pinShown = false
        private val random = SecureRandom()
        private val N = LegacySrp.N
        private val g = LegacySrp.g

        private fun h(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-1").run { parts.forEach { update(it) }; digest() }
        private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)
        private fun min(n: BigInteger) = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        private fun pad(n: BigInteger) = min(n).let { ByteArray(256 - it.size) + it }

        // SRP state
        private var user = ""
        private val salt = ByteArray(16).also { random.nextBytes(it) }
        private var b = BigInteger.ZERO
        private var B = BigInteger.ZERO
        private var v = BigInteger.ZERO
        private var K = ByteArray(0)
        // verify state
        private var ctr: Cipher? = null
        private var verifyClientCurve = ByteArray(0)
        private var verifyServerCurve = ByteArray(0)
        private var verifyClientEd = ByteArray(0)

        fun start() = thread(isDaemon = true) {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) {
                    s.use {
                        val input = s.getInputStream().buffered()
                        while (true) {
                            val req = RtspCodec.read(input) ?: break
                            val (code, body) = try { handle(req) } catch (e: Exception) { 470 to ByteArray(0) }
                            val headers = listOf("CSeq" to (req.header("CSeq") ?: "0"))
                            s.getOutputStream().write(RtspCodec.encode("RTSP/1.0 $code X", headers, body))
                        }
                    }
                }
            }
        }

        private fun handle(req: RtspMessage): Pair<Int, ByteArray> = when (req.uri) {
            "/pair-pin-start" -> { pinShown = true; 200 to ByteArray(0) }
            "/pair-setup-pin" -> setup(BinaryPlist.decode(req.body))
            "/pair-verify" -> verify(req.body)
            else -> 404 to ByteArray(0)
        }

        private fun setup(p: Map<String, Any?>): Pair<Int, ByteArray> {
            if (p["method"] == "pin") {
                user = p["user"] as String
                val x = BigInteger(1, h(salt, h("$user:$pin".toByteArray())))
                v = g.modPow(x, N)
                b = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })
                val k = BigInteger(1, h(pad(N), pad(g)))
                B = k.multiply(v).add(g.modPow(b, N)).mod(N)
                return 200 to BinaryPlist.encode(mapOf("pk" to min(B), "salt" to salt))
            }
            if (p.containsKey("proof")) {
                val aBytes = p["pk"] as ByteArray
                val A = BigInteger(1, aBytes)
                val u = BigInteger(1, h(pad(A), pad(B)))
                val S = A.multiply(v.modPow(u, N)).mod(N).modPow(b, N)
                K = h(min(S), byteArrayOf(0, 0, 0, 0)) + h(min(S), byteArrayOf(0, 0, 0, 1))
                val hn = h(min(N)); val hg = h(min(g))
                val m1 = h(ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }, h(user.toByteArray()), salt, aBytes, min(B), K)
                if (!m1.contentEquals(p["proof"] as ByteArray)) return 470 to ByteArray(0)
                return 200 to BinaryPlist.encode(mapOf("proof" to h(aBytes, m1, K)))
            }
            if (p.containsKey("epk")) {
                val key = sha512("Pair-Setup-AES-Key".toByteArray() + K).copyOf(16)
                val iv = sha512("Pair-Setup-AES-IV".toByteArray() + K).copyOf(16)
                iv[15] = (iv[15] + 1).toByte()
                val gcm = Cipher.getInstance("AES/GCM/NoPadding")
                gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                pairedKey = gcm.doFinal((p["epk"] as ByteArray) + (p["authTag"] as ByteArray))
                return 200 to ByteArray(0)
            }
            return 400 to ByteArray(0)
        }

        private fun verify(body: ByteArray): Pair<Int, ByteArray> {
            if (body[0] == 1.toByte()) {
                verifyClientCurve = body.copyOfRange(4, 36)
                verifyClientEd = body.copyOfRange(36, 68)
                if (!verifyClientEd.contentEquals(pairedKey)) return 470 to ByteArray(0)
                val eph = AirPlay2Crypto.generateCurve25519KeyPair()
                verifyServerCurve = AirPlay2Crypto.getPublicKeyBytes(eph)
                val shared = AirPlay2Crypto.curve25519Agree(eph.private as X25519PrivateKeyParameters, verifyClientCurve)
                val key = sha512("Pair-Verify-AES-Key".toByteArray() + shared).copyOf(16)
                val iv = sha512("Pair-Verify-AES-IV".toByteArray() + shared).copyOf(16)
                ctr = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                val sig = AirPlay2Crypto.ed25519Sign(serverIdentity, verifyServerCurve + verifyClientCurve)
                return 200 to (verifyServerCurve + ctr!!.update(sig))
            }
            val clientSig = ctr!!.update(body.copyOfRange(4, 68))
            val ok = AirPlay2Crypto.ed25519Verify(verifyClientEd, verifyClientCurve + verifyServerCurve, clientSig)
            return (if (ok) 200 else 470) to ByteArray(0)
        }
    }

    private fun conn(tv: FakeAppleTv) = RtspConnection("127.0.0.1", tv.server.localPort).also { it.connect() }

    @Test
    fun `pin pairing hands over our key and pair-verify then succeeds`() {
        val tv = FakeAppleTv(pin).also { it.start() }
        try {
            conn(tv).also { RaopLegacyPairing.startPinDisplay(it); it.close() }
            assertTrue(tv.pinShown)
            conn(tv).also { RaopLegacyPairing.pairSetup(it, "AriaCastPairing", pin, clientSeed); it.close() }
            assertArrayEquals(clientPub, tv.pairedKey)
            conn(tv).also { RaopLegacyPairing.pairVerify(it, clientSeed); it.close() }
        } finally {
            tv.server.close()
        }
    }

    @Test
    fun `wrong pin is rejected`() {
        val tv = FakeAppleTv(pin).also { it.start() }
        try {
            conn(tv).use { RaopLegacyPairing.pairSetup(it, "AriaCastPairing", "0000", clientSeed) }
            fail("wrong PIN accepted")
        } catch (e: RtspException) {
            assertEquals(470, e.code)
        } finally {
            tv.server.close()
        }
    }

    @Test
    fun `pair-verify with an unknown key is rejected`() {
        val tv = FakeAppleTv(pin).also { it.start() }
        try {
            conn(tv).use { RaopLegacyPairing.pairVerify(it, clientSeed) }
            fail("unpaired key accepted")
        } catch (e: RtspException) {
            assertEquals(470, e.code)
        } finally {
            tv.server.close()
        }
    }

    private inline fun <T> RtspConnection.use(block: (RtspConnection) -> T): T = try { block(this) } finally { close() }
}
