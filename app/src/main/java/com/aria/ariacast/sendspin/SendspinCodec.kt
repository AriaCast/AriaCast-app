package com.aria.ariacast.sendspin

import java.util.Base64
import kotlin.math.abs

/** Encodings and constants from the Sendspin spec (connection.md, pairing.md). */
object SendspinCodec {
    private val b64urlEnc = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDec = Base64.getUrlDecoder()

    fun b64url(bytes: ByteArray): String = b64urlEnc.encodeToString(bytes)
    fun unb64url(s: String): ByteArray = b64urlDec.decode(s)

    /** A 43-char base64url id (Curve25519 key, SHA-256 output) decoded to 32 bytes, or null. */
    fun decodeId(s: String?): ByteArray? {
        if (s == null || s.length != 43 || !s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return runCatching { unb64url(s) }.getOrNull()?.takeIf { it.size == 32 }
    }

    /** Sentinel PSK = SHA-256("sendspin-sentinel-psk-v1"), a published constant. */
    val SENTINEL_PSK: ByteArray = Noise.sha256("sendspin-sentinel-psk-v1".toByteArray())

    /** psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK)) */
    fun pskId(psk: ByteArray): String = b64url(Noise.sha256("sendspin-psk-id-v1".toByteArray(), psk))

    // ---- pairing tokens: "SP:" || version || base32(payload), '=' stripped, '2' -> '9'

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun pairingToken(version: Char, payload: ByteArray): String = "SP:$version" + base32(payload).replace('2', '9')

    /** Version-0 token: the client's static public key followed by its pairing PSK. */
    fun pairingPskToken(clientKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientKey.size == 32 && pairingPsk.size == 32)
        return pairingToken('0', clientKey + pairingPsk)
    }

    /** Decodes a token leniently, as the spec requires. Returns (version, payload). */
    fun decodeToken(input: String): Pair<Char, ByteArray> {
        var s = input.trim().uppercase()
        if (s.startsWith("SP:")) s = s.substring(3)
        require(s.isNotEmpty()) { "empty token" }
        val version = s[0]
        require(version == '0' || version == '1') { "unknown token version $version" }
        val body = s.substring(1).replace('9', '2')
        return version to unbase32(body)
    }

    fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun unbase32(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s.trimEnd('=')) {
            val v = B32.indexOf(c)
            require(v >= 0) { "invalid base32 character '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/**
 * Sendspin time filter (messaging.md, "Time filter"): a 2-D Kalman filter tracking the
 * offset (server clock - client clock) and drift. All times are microseconds.
 */
class TimeFilter {
    private val processStdDev = 0.0
    private val driftProcessStdDev = 1e-11
    private val forgetFactor = 2.0
    private val adaptiveCutoff = 3.0
    private val minSamples = 100
    private val driftSignificance = 2.0
    private val maxErrorScale = 0.5

    var offset = 0.0; private set
    var drift = 0.0; private set
    private var pOO = Double.POSITIVE_INFINITY
    private var pOD = 0.0
    private var pDD = 0.0
    var lastUpdate = 0L; private set
    var count = 0; private set
    private var useDrift = false

    /** Feeds one exchange: T1 client sent, T2 server received, T3 server sent, T4 client received. */
    fun update(t1: Long, t2: Long, t3: Long, t4: Long) {
        val measurement = ((t2 - t1) + (t3 - t4)) / 2
        val maxError = ((t4 - t1) - (t3 - t2)) / 2
        updateMeasurement(measurement.toDouble(), maxError.toDouble(), t4)
    }

    fun updateMeasurement(measurement: Double, maxError: Double, timeAdded: Long) {
        if (timeAdded <= lastUpdate || maxError <= 0) return
        val dt = (timeAdded - lastUpdate).toDouble()
        lastUpdate = timeAdded
        val r = (maxErrorScale * maxError).let { it * it }
        when (count) {
            0 -> { offset = measurement; pOO = r; drift = 0.0; count = 1; return }
            1 -> {
                drift = (measurement - offset) / dt
                pDD = (pOO + r) / (dt * dt)
                offset = measurement; pOO = r; count = 2
                return
            }
        }
        val offsetP = offset + drift * dt
        var pOOp = pOO + 2 * pOD * dt + pDD * dt * dt + processStdDev * processStdDev * dt
        var pODp = pOD + pDD * dt
        var pDDp = pDD + driftProcessStdDev * driftProcessStdDev * dt
        val y = measurement - offsetP
        if (count < minSamples) count++
        else if (abs(y) > adaptiveCutoff * maxError) {
            val f = forgetFactor * forgetFactor
            pOOp *= f; pODp *= f; pDDp *= f
        }
        val s = pOOp + r
        val kO = pOOp / s
        val kD = pODp / s
        offset = offsetP + kO * y
        drift += kD * y
        pOO = pOOp - kO * pOOp
        pOD = pODp - kD * pOOp
        pDD = pDDp - kD * pODp
        useDrift = drift * drift > driftSignificance * driftSignificance * pDD
    }

    private fun d() = if (useDrift) drift else 0.0

    fun serverTime(clientUs: Long): Long = clientUs + roundAway(offset + d() * (clientUs - lastUpdate))
    fun clientTime(serverUs: Long): Long = roundAway((serverUs - offset + d() * lastUpdate) / (1 + d()))

    private fun roundAway(x: Double): Long = if (x >= 0) (x + 0.5).toLong() else -((-x + 0.5).toLong())
}
