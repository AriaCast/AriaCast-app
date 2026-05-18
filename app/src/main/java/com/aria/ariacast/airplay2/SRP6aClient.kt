package com.aria.ariacast.airplay2

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import java.math.BigInteger
import java.security.SecureRandom

class SRP6aClient(
    private val pin: String,
    private val username: String = "Pair-Setup",
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        val N: BigInteger = SRP6StandardGroups.rfc5054_3072.n
        val g: BigInteger = SRP6StandardGroups.rfc5054_3072.g
        private const val PUBKEY_3072_SIZE = 384
    }

    private val digest = SHA512Digest()
    private val digestSize = digest.digestSize

    private var a: BigInteger? = null
    private var A: BigInteger? = null
    private var Araw: ByteArray? = null
    private var x: BigInteger? = null
    private var S: BigInteger? = null
    private var K: ByteArray? = null
    private var B: BigInteger? = null
    private var M1: ByteArray? = null
    var sharedKeyBytes: ByteArray? = null
        private set
    private var salt: ByteArray? = null

    fun buildM1(): ByteArray {
        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_METHOD to byteArrayOf(0)
        )
    }

    fun processM2(m2Tlv: ByteArray): ByteArray? {
        val parsed = TlvUtil.parse(m2Tlv)
        val state = parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0) ?: return null
        if (state != 2.toByte()) return null
        val salt = parsed[TlvUtil.TLV_SALT]?.firstOrNull() ?: return null
        val serverB = parsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull() ?: return null
        if (salt.size != 16) return null
        this.salt = salt
        this.B = BigInteger(1, serverB)
        return buildM3(salt, serverB)
    }

    private fun buildM3(salt: ByteArray, serverB: ByteArray): ByteArray {
        val passwordBytes = pin.toByteArray(Charsets.UTF_8)
        val usernameBytes = username.toByteArray(Charsets.UTF_8)

        val innerHash = hash(usernameBytes + byteArrayOf(':'.code.toByte()) + passwordBytes)
        val xBytes = hash(salt + innerHash)
        this.x = BigInteger(1, xBytes)
        val xv = this.x!!

        val aBytes = ByteArray(64).also(random::nextBytes)
        val av = BigInteger(1, aBytes).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        this.a = av
        val Av = g.modPow(av, N)
        this.A = Av
        this.Araw = bigIntToMinimal(Av)
        val Araw = this.Araw!!

        val Bv = this.B!!
        val Bravo = bigIntToMinimal(Bv)

        // k = H_nn_pad(SHA512, N, g, N_len) -> pad384(N) || pad384(g)
        val kBytes = hash(bigIntToFixed(N, PUBKEY_3072_SIZE) + bigIntToFixed(g, PUBKEY_3072_SIZE))
        val k = BigInteger(1, kBytes)

        // u = H_nn_pad(SHA512, A, B, N_len) -> pad384(A) || pad384(B)
        val uBytes = hash(bigIntToFixed(Av, PUBKEY_3072_SIZE) + bigIntToFixed(Bv, PUBKEY_3072_SIZE))
        val u = BigInteger(1, uBytes)

        val gx = g.modPow(xv, N)
        val kgx = k.multiply(gx).mod(N)
        val base = Bv.subtract(kgx).mod(N)
        val exponent = av.add(u.multiply(xv))
        val Sv = base.modPow(exponent, N)
        this.S = Sv

        val Kv = hash(bigIntToMinimal(Sv))
        this.K = Kv
        this.sharedKeyBytes = Kv

        val hn = hash(bigIntToMinimal(N))
        val hg = hash(bigIntToMinimal(g))
        val hnXorHg = xorBytes(hn, hg)
        val hi = hash(usernameBytes)

        val M1v = hash(hnXorHg + hi + salt + Araw + Bravo + Kv)
        this.M1 = M1v

        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_PUBLIC_KEY to Araw,
            TlvUtil.TLV_PROOF to M1v
        )
    }

    fun verifyM4(m4Tlv: ByteArray): Boolean {
        val parsed = TlvUtil.parse(m4Tlv)
        val state = parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0) ?: return false
        if (state != 4.toByte()) return false
        val serverProof = parsed[TlvUtil.TLV_PROOF]?.firstOrNull() ?: return false

        val a = this.M1 ?: return false
        val b = this.K ?: return false
        val expectedM2 = hash(this.Araw!! + a + b)
        return expectedM2.contentEquals(serverProof)
    }

    fun buildM5(ed25519KeyPair: AsymmetricCipherKeyPair, deviceId: String): ByteArray? {
        val k = sharedKeyBytes ?: return null
        val edPub = AirPlay2Crypto.getEd25519PublicKey(ed25519KeyPair)
        val saltSet = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8)
        val infoSet = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8)
        val encKey = AirPlay2Crypto.hkdfSha512(saltSet, k, infoSet, 32)

        // Build device info: {Identifier: device_id, Signature: ed25519_sign(device_x || device_id || public_key)}
        val deviceX = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt".toByteArray(),
            k,
            "Pair-Setup-Controller-Sign-Info".toByteArray(),
            32
        )
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8)
        val signData = deviceX + deviceIdBytes + edPub
        val signature = AirPlay2Crypto.ed25519Sign(ed25519KeyPair, signData)

        val deviceInfoTlv = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to deviceIdBytes,
            TlvUtil.TLV_SIGNATURE to signature
        )

        // Append public key TLV after device info TLV
        val pubKeyTlv = TlvUtil.build(
            TlvUtil.TLV_PUBLIC_KEY to edPub
        )
        val plaintext = deviceInfoTlv + pubKeyTlv

        // Fixed nonce: 00 00 00 00 "PS-Msg05"
        val nonce = ByteArray(12)
        val msgNonce = "PS-Msg05".toByteArray(Charsets.UTF_8)
        System.arraycopy(msgNonce, 0, nonce, 4, msgNonce.size)

        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(encKey, nonce, plaintext, ByteArray(0))

        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(5),
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag)
        )
    }

    fun buildM5Raw(ed25519PubKey: ByteArray, ed25519Seed: ByteArray, deviceId: String): ByteArray? {
        val k = sharedKeyBytes ?: return null
        val saltSet = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8)
        val infoSet = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8)
        val encKey = AirPlay2Crypto.hkdfSha512(saltSet, k, infoSet, 32)

        val deviceX = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt".toByteArray(),
            k,
            "Pair-Setup-Controller-Sign-Info".toByteArray(),
            32
        )
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8)
        val signData = deviceX + deviceIdBytes + ed25519PubKey
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ed25519Seed, 0))
        signer.update(signData, 0, signData.size)
        val signature = signer.generateSignature()

        val deviceInfoTlv = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to deviceIdBytes,
            TlvUtil.TLV_SIGNATURE to signature
        )
        val pubKeyTlv = TlvUtil.build(
            TlvUtil.TLV_PUBLIC_KEY to ed25519PubKey
        )
        val plaintext = deviceInfoTlv + pubKeyTlv
        val nonce = ByteArray(12)
        val msgNonce = "PS-Msg05".toByteArray(Charsets.UTF_8)
        System.arraycopy(msgNonce, 0, nonce, 4, msgNonce.size)
        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(encKey, nonce, plaintext, ByteArray(0))
        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(5),
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag)
        )
    }

    fun verifyM6(m6Tlv: ByteArray): ByteArray? {
        val k = sharedKeyBytes ?: return null
        val saltSet = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8)
        val infoSet = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8)
        val encKey = AirPlay2Crypto.hkdfSha512(saltSet, k, infoSet, 32)

        val parsed = TlvUtil.parse(m6Tlv)
        val state = parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0) ?: return null
        if (state != 6.toByte()) return null
        val encryptedData = parsed[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull() ?: return null
        if (encryptedData.size < 16) return null

        val ciphertext = encryptedData.copyOfRange(0, encryptedData.size - 16)
        val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

        val nonce = ByteArray(12)
        val msgNonce = "PS-Msg06".toByteArray(Charsets.UTF_8)
        System.arraycopy(msgNonce, 0, nonce, 4, msgNonce.size)

        val decrypted = try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(encKey, nonce, ciphertext, ByteArray(0), tag)
        } catch (e: Exception) {
            return null
        }

        // Parse decrypted TLV: {Identifier, PublicKey, Signature}
        val innerParsed = TlvUtil.parse(decrypted)
        val deviceId = innerParsed[TlvUtil.TLV_IDENTIFIER]?.firstOrNull() ?: return null
        val pk = innerParsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull() ?: return null
        val signature = innerParsed[TlvUtil.TLV_SIGNATURE]?.firstOrNull() ?: return null

        // Verify signature: device_x = HKDF(session_key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val deviceX = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
            k,
            "Pair-Setup-Accessory-Sign-Info".toByteArray(),
            32
        )
        val verifyData = deviceX + deviceId + pk
        val valid = AirPlay2Crypto.ed25519Verify(pk, verifyData, signature)
        if (!valid) return null

        return pk
    }

    private fun hash(data: ByteArray): ByteArray {
        val out = ByteArray(digestSize)
        digest.update(data, 0, data.size)
        digest.doFinal(out, 0)
        return out
    }

    private fun bigIntToMinimal(n: BigInteger): ByteArray {
        val arr = n.toByteArray()
        return if (arr.size > 1 && arr[0] == 0.toByte()) {
            arr.copyOfRange(1, arr.size)
        } else {
            arr
        }
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        val result = ByteArray(len)
        for (i in 0 until len) result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return result
    }

    private fun bigIntToFixed(n: BigInteger, size: Int): ByteArray {
        val raw = bigIntToMinimal(n)
        if (raw.size == size) return raw
        val result = ByteArray(size)
        val dstOffset = size - raw.size
        System.arraycopy(raw, 0, result, dstOffset, raw.size)
        return result
    }

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
