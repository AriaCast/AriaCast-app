package com.aria.ariacast.airplay2

import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

class NeedsPinException(val host: String) : Exception("PIN needed for host $host")

class AirPlay2Client(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val dacpId: String,
    private val activeRemote: String,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val frameSize: Int = 352,
    private val password: String? = null,
    private val txtPk: ByteArray? = null
) {
    interface EventListener {
        fun onVolumeChange(db: Double) {}
        fun onRemoteCommand(command: String) {}
    }

    companion object {
        private const val TAG = "AirPlay2Client"
        private const val PUBKEY_3072_SIZE = 384
    }

    var eventListener: EventListener? = null

    private val secureRandom = SecureRandom()
    private val socket = Socket()
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var localAddress: String = "0.0.0.0"
    private var sessionUrl: String = ""
    private val sessionUuid = UUID.randomUUID()
    private var cseq = 0

    private val timingSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    private var audioSocket: DatagramSocket? = null
    private var eventSocket: Socket? = null
    private var eventPort = 0

    private var timingRemotePort = 0
    private var controlRemotePort = 0
    private var audioRemotePort = 0

    private var sequence = 0
    private var rtpTimestamp = 0
    private var syncPacketSent = false
    private val ssrc = secureRandom.nextInt().toUInt().toLong()
    private val latencyFrames = 11025

    private var sharedSecret: ByteArray? = null
    private var ecdhKeyPair: AsymmetricCipherKeyPair? = null
    private var cipherKeys: AirPlay2Crypto.PairKeysResult? = null
    private var supportsEncryption = false
    private var deviceEd25519PubKey: ByteArray? = null
    private var sessionId: String? = null
    private var didTransientPairing = false

    @Volatile private var running = false
    private var syncThread: Thread? = null
    private var keepAliveThread: Thread? = null

    class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray?)

    fun connect(): Boolean {
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            output = socket.getOutputStream()
            input = socket.getInputStream()
            localAddress = socket.localAddress.hostAddress ?: "0.0.0.0"
            val urlHost = if (localAddress.contains(':')) "[$localAddress]" else localAddress
            sessionUrl = "rtsp://$urlHost/$sessionUuid"
            sequence = secureRandom.nextInt(0xFFFF)
            rtpTimestamp = secureRandom.nextInt()

            if (txtPk != null) {
                deviceEd25519PubKey = txtPk
                Log.d(TAG, "Device pk from TXT, length=${txtPk.size}")
            }

            val info = getInfo() ?: return false
            val statusFlags = info["statusFlags"] as? Long ?: 0L
            Log.d(TAG, "Device info: flags=$statusFlags")

            if (deviceEd25519PubKey == null && info["pk"] != null) {
                val pkRaw = info["pk"]
                deviceEd25519PubKey = when (pkRaw) {
                    is ByteArray -> pkRaw
                    is String -> android.util.Base64.decode(pkRaw, android.util.Base64.NO_WRAP)
                    else -> null
                }
                if (deviceEd25519PubKey != null) {
                    Log.d(TAG, "Device pk from /info, length=${deviceEd25519PubKey!!.size}")
                }
            }
            Log.d(TAG, "hasPk=${deviceEd25519PubKey != null}")

            // If the device needs pairing, do pair-setup
            // Transient: no password → default to "3939" (matches owntone - see pair_homekit.c:1177)
            // Full: password provided → use as-is
            val effectivePassword = password
            if (effectivePassword != null || needsPairing(statusFlags)) {
                Log.d(TAG, "Pair-setup: starting (password=${effectivePassword ?: "3939 (transient)"})")
                if (!doPairSetup(effectivePassword)) {
                    if (password == null) {
                        throw NeedsPinException(host)
                    }
                    return false
                }
            }

            if (didTransientPairing) {
                Log.d(TAG, "Transient pairing: skipping pair-verify")
            } else if (deviceEd25519PubKey != null) {
                if (!doPairVerify()) {
                    Log.w(TAG, "Pair-verify failed, continuing anyway")
                }
            } else {
                Log.w(TAG, "No device pk available, skipping pair-verify")
            }

            if (!sendSetupSession()) return false
            if (!sendRecord()) return false
            connectEventPort()
            if (!sendSetupStream()) return false

            setVolume(0.0)

            running = true
            startSyncLoop()
            startKeepAliveLoop()
            Log.d(TAG, "AirPlay 2 connected successfully")
            return true
        } catch (e: NeedsPinException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            close()
            return false
        }
    }

    private fun needsPairing(statusFlags: Long): Boolean {
        val bit2 = (statusFlags shr 2) and 1
        val bit3 = (statusFlags shr 3) and 1
        val bit9 = (statusFlags shr 9) and 1
        return (bit2 or bit3 or bit9) != 0L
    }

    private fun getInfo(): Map<String, Any>? {
        val resp = sendHttpRequest("GET", "/info", null, null)
        if (resp.code != 200) return null
        val body = resp.body ?: return null
        Log.d(TAG, "GET /info body size=${body.size}, magic=${body.copyOf(8).toString(Charsets.UTF_8)}")
        return try {
            val result = BinaryPlist.decode(body)
            Log.d(TAG, "Binary plist parsed, keys=${result.keys.joinToString(",")}")
            if (result.containsKey("pk")) {
                val pkVal = result["pk"]
                Log.d(TAG, "pk type=${pkVal?.javaClass?.simpleName}, value=${if (pkVal is ByteArray) "ByteArray(${pkVal.size})" else pkVal}")
            }
            if (result.containsKey("statusFlags")) {
                Log.d(TAG, "statusFlags=${result["statusFlags"]}")
            }
            result.toMutableMap() as Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Binary plist parse failed: ${e.message}", e)
            val bodyStr = body.toString(Charsets.UTF_8)
            parseXmlPlist(bodyStr)
        }
    }

    private fun parseXmlPlist(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val statusRegex = Regex("<key>statusFlags</key>\\s*<integer>(\\d+)</integer>", RegexOption.DOT_MATCHES_ALL)
        val pkRegex = Regex("<key>pk</key>\\s*<data>\\s*([A-Za-z0-9+/=]+)\\s*</data>", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("<key>name</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        val modelRegex = Regex("<key>model</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        statusRegex.find(xml)?.let { result["statusFlags"] = it.groupValues[1].toLong() }
        pkRegex.find(xml)?.let { result["pk"] = it.groupValues[1].replace(Regex("\\s+"), "") }
        nameRegex.find(xml)?.let { result["name"] = it.groupValues[1] }
        modelRegex.find(xml)?.let { result["model"] = it.groupValues[1] }
        return result
    }

    private fun doPairSetup(effectivePassword: String?): Boolean {
        Log.d(TAG, "Pair-setup: starting (password=${effectivePassword ?: "3939 (transient)"})")

        // Always try transient (HKP 4) first: this covers devices that show a PIN on screen
        // (statusFlags bit 9). Fall back to full (HKP 3) only if transient is rejected with 470.
        // Without a password, skip full pairing (nothing to authenticate with).
        for (attemptTransient in listOf(true, false)) {
            val attemptPin = effectivePassword ?: if (attemptTransient) "3939" else return false
            Log.d(TAG, "Pair-setup attempt: transient=$attemptTransient pin=$attemptPin")
            val srp = SRP6aClient(attemptPin, "Pair-Setup", secureRandom)
            val m1 = srp.buildM1()

            val resp1 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m1, attemptTransient)
            Log.d(TAG, "Pair-setup M1 response: ${resp1.code}")
            if (resp1.code == 200) {
                val m3 = srp.processM2(resp1.body ?: return false) ?: return false
                val resp2 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m3, attemptTransient)
                if (resp2.code != 200) { Log.e(TAG, "pair-setup M3 failed: ${resp2.code}"); continue }

                if (!srp.verifyM4(resp2.body ?: return false)) {
                    Log.e(TAG, "pair-setup M4 verification failed"); continue
                }

                if (!attemptTransient) {
                    val ed25519KeyPair = AirPlay2Crypto.generateEd25519KeyPair()
                    val deviceIdHex = deviceId.replace(":", "")
                    val m5 = srp.buildM5(ed25519KeyPair, deviceIdHex) ?: continue
                    val resp3 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m5, false)
                    if (resp3.code != 200) { Log.e(TAG, "pair-setup M5 failed: ${resp3.code}"); continue }
                    val serverKey = srp.verifyM6(resp3.body ?: return false)
                    if (serverKey == null) { Log.e(TAG, "pair-setup M6 verification failed"); continue }
                    deviceEd25519PubKey = serverKey
                }

                sharedSecret = srp.sharedKeyBytes
                if (sharedSecret != null) {
                    didTransientPairing = attemptTransient
                    Log.d(TAG, "Pair-setup complete (transient=$attemptTransient), shared secret length=${sharedSecret!!.size}")
                    return true
                }
            } else if (resp1.code == 470) {
                Log.d(TAG, "${if (attemptTransient) "Transient" else "Full"} pairing rejected (470), ${if (attemptTransient) "retrying with full" else "giving up"}")
                continue
            } else {
                Log.e(TAG, "pair-setup M1 failed: ${resp1.code}")
                return false
            }
        }
        Log.e(TAG, "Pair-setup: all attempts failed")
        return false
    }

    private fun doPairVerify(): Boolean {
        Log.d(TAG, "Pair-verify: starting")

        val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        ecdhKeyPair = keyPair
        val clientPub = AirPlay2Crypto.getPublicKeyBytes(keyPair)

        val m1 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_PUBLIC_KEY to clientPub
        )
        val resp1 = sendHttpRequest("POST", "/pair-verify", "application/pairing+tlv8", m1)
        Log.d(TAG, "pair-verify M1 response: ${resp1.code}")
        if (resp1.code != 200) { Log.e(TAG, "pair-verify M1 failed: ${resp1.code}"); return false }

        val respBody = resp1.body
        if (respBody == null) { Log.e(TAG, "pair-verify M1 no body"); return false }
        val parsed1 = TlvUtil.parse(respBody)
        val stateByte = parsed1[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0)
        if (stateByte == null) { Log.e(TAG, "pair-verify M1 no state TLV"); return false }
        if (stateByte != 2.toByte()) { Log.e(TAG, "pair-verify: unexpected state ${stateByte.toInt()}"); return false }

        val serverPubKey = parsed1[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull()
        if (serverPubKey == null) { Log.e(TAG, "pair-verify M1 no public key TLV"); return false }
        val encryptedData = parsed1[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull()
        if (encryptedData == null) { Log.e(TAG, "pair-verify M1 no encrypted data TLV"); return false }

        // ECDH shared = curve25519(client_eph_priv, server_eph_pub)
        val shared = AirPlay2Crypto.curve25519Agree(
            keyPair.private as X25519PrivateKeyParameters, serverPubKey
        )

        // HKDF decrypt key: HKDF(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32)
        val sessionKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            shared,
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
            32
        )

        // Fixed nonce: 00 00 00 00 "PV-Msg02"
        val nonce = ByteArray(12)
        System.arraycopy("PV-Msg02".toByteArray(Charsets.UTF_8), 0, nonce, 4, 8)

        // Server response: ciphertext || tag (no nonce)
        if (encryptedData.size < 16) { Log.e(TAG, "pair-verify M2 encrypted data too short"); return false }
        val ciphertext = encryptedData.copyOfRange(0, encryptedData.size - 16)
        val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

        val decrypted = try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(sessionKey, nonce, ciphertext, ByteArray(0), tag)
        } catch (e: Exception) {
            Log.e(TAG, "pair-verify M2 decrypt failed", e); return false
        }
        Log.d(TAG, "M2 decrypted size=${decrypted.size}")

        // Parse decrypted inner TLV: {Identifier, Signature}
        val innerParsed = TlvUtil.parse(decrypted)
        val serverId = innerParsed[TlvUtil.TLV_IDENTIFIER]?.firstOrNull()
        val serverSignature = innerParsed[TlvUtil.TLV_SIGNATURE]?.firstOrNull()
        if (serverId == null || serverSignature == null) {
            Log.e(TAG, "pair-verify M2 missing identifier or signature in decrypted data")
            return false
        }
        Log.d(TAG, "M2 serverId=${serverId.toString(Charsets.UTF_8)} sigSize=${serverSignature.size}")

        // Verify: signature(server_eph_pub || server_id || client_eph_pub) against serverEd25519Pub
        // Use deviceEd25519PubKey from /info or txtPk as the server's Ed25519 public key
        // If we have it, verify; else skip verification (dev mode)
        val verifyInfo = serverPubKey + serverId + clientPub
        val serverEd25519Pub = this.deviceEd25519PubKey
        if (serverEd25519Pub != null) {
            val valid = AirPlay2Crypto.ed25519Verify(serverEd25519Pub, verifyInfo, serverSignature)
            Log.d(TAG, "M2 signature verify result=$valid")
        } else {
            Log.w(TAG, "No server Ed25519 public key, skipping M2 signature verification")
        }

        // Build M3: TLV{Identifier: our_device_id, Signature: ed25519(client_eph_pub || device_id || server_eph_pub)}
        val deviceIdHex = deviceId.replace(":", "")
        val deviceIdBytes = deviceIdHex.toByteArray(Charsets.UTF_8)
        val edKeyPair = AirPlay2Crypto.generateEd25519KeyPair()
        val m3SignData = clientPub + deviceIdBytes + serverPubKey
        val m3Sig = AirPlay2Crypto.ed25519Sign(edKeyPair, m3SignData)

        val m3DeviceInfoTlv = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to deviceIdBytes,
            TlvUtil.TLV_SIGNATURE to m3Sig
        )

        // Encrypt with fixed nonce: 00 00 00 00 "PV-Msg03"
        val m3Nonce = ByteArray(12)
        System.arraycopy("PV-Msg03".toByteArray(Charsets.UTF_8), 0, m3Nonce, 4, 8)
        val (m3Ciphertext, m3Tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            sessionKey, m3Nonce, m3DeviceInfoTlv, ByteArray(0)
        )
        val m3Encrypted = m3Ciphertext + m3Tag

        val m3 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_ENCRYPTED_DATA to m3Encrypted
        )
        Log.d(TAG, "pair-verify M3 request size=${m3.size}")
        val resp3 = sendHttpRequest("POST", "/pair-verify", "application/pairing+tlv8", m3)
        Log.d(TAG, "pair-verify M3 response: ${resp3.code}")
        if (resp3.code != 200) { Log.e(TAG, "pair-verify M3 failed: ${resp3.code}"); return false }

        val pairSalt = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
        val encKey = AirPlay2Crypto.hkdfSha512(pairSalt, shared, "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8), 32)
        val decKey = AirPlay2Crypto.hkdfSha512(pairSalt, shared, "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8), 32)

        cipherKeys = AirPlay2Crypto.PairKeysResult(encKey, decKey, 0L, 0L)
        supportsEncryption = true
        Log.d(TAG, "Pair-verify complete")
        return true
    }

    private fun sendSetupSession(): Boolean {
        val plist = BinaryPlist.makeSessionPlist(sessionUuid, deviceId, timingSocket.localPort)
        Log.d(TAG, "SETUP session: ${sessionUrl}, plist size=${plist.size}")
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        Log.d(TAG, "SETUP session response: code=${resp.code}, headers=${resp.headers}")
        if (resp.code != 200) { Log.e(TAG, "SETUP session failed: ${resp.code}"); return false }
        sessionId = resp.headers["Session"]?.substringBefore(";")?.trim()
        Log.d(TAG, "SETUP session: sessionId=$sessionId")
        val transport = resp.headers["Transport"] ?: ""
        parseTransportResponse(transport)
        resp.body?.let { parseSessionResponse(it) }
        return sessionId != null
    }

    private fun sendRecord(): Boolean {
        val resp = sendRtspRequest("RECORD", sessionUrl, null, null)
        Log.d(TAG, "RECORD response: code=${resp.code}")
        return resp.code == 200 || resp.code == 201
    }

    private fun sendSetupStream(): Boolean {
        val secret = sharedSecret ?: ByteArray(32).also { secureRandom.nextBytes(it) }
        val shk = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-AES-Key".toByteArray(Charsets.UTF_8),
            secret,
            "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8),
            32
        )

        val plist = BinaryPlist.makeStreamPlist(
            controlPort = controlSocket.localPort,
            sharedSecret = shk,
            streamConnectionId = sessionUuid.mostSignificantBits
        )
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        if (resp.code != 200) return false
        val transport = resp.headers["Transport"] ?: ""
        parseTransportResponse(transport)
        audioSocket = DatagramSocket(0)
        return true
    }

    private fun parseSessionResponse(body: ByteArray) {
        try {
            val dict = BinaryPlist.decode(body)
            val port = (dict["eventPort"] as? Long)?.toInt()
            if (port != null && port > 0) {
                eventPort = port
                Log.d(TAG, "Event port: $eventPort")
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSessionResponse failed: ${e.message}")
        }
    }

    private fun parseTransportResponse(transport: String) {
        transport.split(";").forEach { part ->
            when {
                part.trim().startsWith("server_port=") ->
                    audioRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                part.trim().startsWith("control_port=") ->
                    controlRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                part.trim().startsWith("timing_port=") ->
                    timingRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
            }
        }
        Log.d(TAG, "Transport: audio=$audioRemotePort ctrl=$controlRemotePort timing=$timingRemotePort")
    }

    private fun alacEncodeUncompressed(pcm: ByteArray): ByteArray {
        // Replicates alac_encode_uncompressed from alac_wrapper.cpp in pure Kotlin.
        // Writes a 23-bit ALAC uncompressed frame header, then byte-swaps each
        // little-endian stereo sample to big-endian, all packed into a bit stream.
        val out = ByteArray(3 + pcm.size + 1)
        var p = 0
        var bpos = 0

        fun writeBits(v: Int, blen: Int) {
            val lb = 8 - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and 0xFF
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) { p++; bpos = 0 } else bpos += blen
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and 0xFF)).toByte()
                p++
                out[p] = ((v shl (8 + rb)) and 0xFF).toByte()
                bpos = -rb
            }
        }

        writeBits(1, 3); writeBits(0, 4); writeBits(0, 8)
        writeBits(0, 4); writeBits(0, 1); writeBits(0, 2); writeBits(1, 1)

        var i = 0
        while (i < pcm.size) {
            writeBits(pcm[i + 1].toInt() and 0xFF, 8)  // L high byte
            writeBits(pcm[i + 0].toInt() and 0xFF, 8)  // L low byte
            writeBits(pcm[i + 3].toInt() and 0xFF, 8)  // R high byte
            writeBits(pcm[i + 2].toInt() and 0xFF, 8)  // R low byte
            i += 4
        }
        writeBits(7, 3)
        return out
    }

    fun sendAudioFrame(pcm: ByteArray): Boolean {
        try {
            val payload = alacEncodeUncompressed(pcm)
            val packet = buildRtpPacket(payload)
            val socket = audioSocket ?: return false

            val keys = cipherKeys
            if (supportsEncryption && keys != null) {
                val counter = synchronized(this) { keys.encryptionCounter++ }
                val nonce = ByteArray(12)
                for (i in 0..7) nonce[4 + i] = ((counter shr (i * 8)) and 0xFF).toByte()
                val aad = packet.copyOfRange(4, 12)
                val payloadEnc = packet.drop(12).toByteArray()
                val (encrypted, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                    keys.encryptionKey, nonce, payloadEnc, aad
                )
                val finalPacket = packet.copyOfRange(0, 12) + encrypted + tag + nonce.copyOfRange(4, 12)
                val dp = DatagramPacket(finalPacket, finalPacket.size, InetAddress.getByName(host), audioRemotePort)
                socket.send(dp)
            } else {
                val dp = DatagramPacket(packet, packet.size, InetAddress.getByName(host), audioRemotePort)
                socket.send(dp)
            }

            sequence = (sequence + 1) and 0xFFFF
            rtpTimestamp += frameSize
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
            return false
        }
    }

    private fun buildRtpPacket(payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60.toByte()
        header[2] = ((sequence shr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = rtpTimestamp
        header[4] = ((ts shr 24) and 0xFF).toByte()
        header[5] = ((ts shr 16) and 0xFF).toByte()
        header[6] = ((ts shr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        val ssrcInt = ssrc.toInt()
        header[8] = ((ssrcInt shr 24) and 0xFF).toByte()
        header[9] = ((ssrcInt shr 16) and 0xFF).toByte()
        header[10] = ((ssrcInt shr 8) and 0xFF).toByte()
        header[11] = (ssrcInt and 0xFF).toByte()
        return header + payload
    }

    private fun sendSyncPacket() {
        val now = currentNtpTime()
        val rtpTsLatency = (rtpTimestamp - latencyFrames).coerceAtLeast(0)
        val packetSize = if (supportsEncryption) 28 else 20
        val packet = ByteArray(packetSize)
        packet[0] = (if (syncPacketSent) 0x80 else 0x90).toByte()
        packet[1] = (0xD0 or (if (supportsEncryption) 0x07 else 0x04)).toByte()
        packet[2] = 0
        packet[3] = 0
        syncPacketSent = true
        writeUInt32(packet, 4, rtpTsLatency)
        writeUInt64(packet, 8, now)
        writeUInt32(packet, 16, rtpTimestamp)
        if (supportsEncryption && packetSize >= 28) {
            writeUInt64(packet, 20, ptpClockId)
        }
        try {
            val dp = DatagramPacket(packet, packet.size, InetAddress.getByName(host), controlRemotePort)
            controlSocket.send(dp)
        } catch (_: Exception) {}
    }

    fun setVolume(db: Double) {
        val body = "volume: ${"%.6f".format(java.util.Locale.US, db)}\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", bodyBytes)
    }

    fun sendMetadata(title: String?, artist: String?, album: String?, artworkBytes: ByteArray? = null) {
        val dict = linkedMapOf<String, Any?>()
        if (!title.isNullOrEmpty()) dict["dmap.itemname"] = title
        if (!artist.isNullOrEmpty()) dict["daap.songartist"] = artist
        if (!album.isNullOrEmpty()) dict["daap.songalbum"] = album
        if (dict.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl,
                "application/x-apple-binary-plist", BinaryPlist.encode(dict))
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl, "image/jpeg", artworkBytes)
        }
    }

    fun sendProgress(positionMs: Long, durationMs: Long) {
        val start = 0L
        val current = (positionMs * sampleRate / 1000L) + rtpTimestamp
        val end = (durationMs * sampleRate / 1000L)
        val body = "progress: $start/$current/$end\r\n".toByteArray(Charsets.UTF_8)
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", body)
    }

    private fun connectEventPort() {
        if (eventPort <= 0) return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, eventPort), 5000)
            eventSocket = s
            startEventReadLoop(s)
            Log.d(TAG, "Event port $eventPort connected")
        } catch (e: Exception) {
            Log.w(TAG, "Event port connect failed: ${e.message}")
        }
    }

    private fun startEventReadLoop(s: Socket) {
        thread(name = "ap2-event", isDaemon = true) {
            try {
                val input = s.getInputStream()
                while (running) {
                    val msg = readEventMessage(input) ?: break
                    dispatchEventMessage(msg)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Event loop ended: ${e.message}")
            }
        }
    }

    private data class EventMessage(val method: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun readEventMessage(input: InputStream): EventMessage? {
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }
        if (headerLines.isEmpty()) return null
        val method = headerLines[0].substringBefore(' ')
        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val parts = headerLines[i].split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
        }
        return EventMessage(method, headers, body)
    }

    private fun dispatchEventMessage(msg: EventMessage) {
        Log.d(TAG, "Event: ${msg.method} ct=${msg.headers["Content-Type"]}")
        val body = msg.body ?: return
        if (msg.headers["Content-Type"]?.contains("apple-binary-plist") != true) return
        try {
            val dict = BinaryPlist.decode(body)
            when (dict["type"]) {
                "volume" -> {
                    val db = when (val v = dict["value"]) {
                        is Double -> v
                        is Long -> v.toDouble()
                        else -> return
                    }
                    Log.d(TAG, "Event volume: $db dB")
                    eventListener?.onVolumeChange(db)
                }
                "command" -> {
                    val name = dict["name"] as? String ?: return
                    Log.d(TAG, "Event command: $name")
                    eventListener?.onRemoteCommand(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Event dispatch failed: ${e.message}")
        }
    }

    fun close() {
        running = false
        syncThread?.interrupt()
        keepAliveThread?.interrupt()
        audioSocket?.close()
        try { eventSocket?.close() } catch (_: Exception) {}
        controlSocket.close()
        timingSocket.close()
        try { socket.close() } catch (_: Exception) {}
    }

    private fun sendHttpRequest(method: String, path: String, contentType: String?, body: ByteArray?): HttpResponse {
        return sendRequest("$method $path HTTP/1.1", contentType, body)
    }

    private fun sendPairingRequest(method: String, path: String, contentType: String?, body: ByteArray?, isTransient: Boolean): HttpResponse {
        return sendRequest("$method $path HTTP/1.1", contentType, body,
            if (isTransient) "X-Apple-HKP" to "4" else "X-Apple-HKP" to "3")
    }

    private fun sendRtspRequest(method: String, url: String, contentType: String?, body: ByteArray?): HttpResponse {
        return sendRequest("$method $url RTSP/1.0", contentType, body)
    }

    private fun sendRequest(requestLine: String, contentType: String?, body: ByteArray?, extraHeader: Pair<String, String>? = null): HttpResponse {
        synchronized(this) {
            val out = output ?: return HttpResponse(0, emptyMap(), null)
            val req = buildRequest(requestLine, contentType, body, extraHeader)
            out.write(req.toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()
            return readResponse()
        }
    }

    private fun buildRequest(requestLine: String, contentType: String?, body: ByteArray?, extraHeader: Pair<String, String>? = null): String {
        val sb = StringBuilder()
        sb.append("$requestLine\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: AriaCast/1.0\r\n")
        sb.append("Client-Instance: ${deviceId.replace(":", "")}\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        if (sessionId != null) sb.append("Session: $sessionId\r\n")
        if (extraHeader != null) sb.append("${extraHeader.first}: ${extraHeader.second}\r\n")
        if (contentType != null && body != null) {
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun readResponse(): HttpResponse {
        val input = input ?: return HttpResponse(0, emptyMap(), null)
        val headers = linkedMapOf<String, String>()
        var statusCode = 0
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()

        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }

        if (headerLines.isEmpty()) return HttpResponse(0, emptyMap(), null)

        val statusRegex = Regex("\\w+/\\d\\.\\d (\\d+)")
        statusRegex.find(headerLines.first())?.let {
            statusCode = it.groupValues[1].toIntOrNull() ?: 0
        }

        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
            if (offset != contentLength) body = null
        }

        return HttpResponse(statusCode, headers, body)
    }

    private fun currentNtpTime(): Long {
        val ms = System.currentTimeMillis()
        val seconds = ms / 1000 + 2208988800L
        val fraction = ((ms % 1000) * 0x100000000L) / 1000
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun writeUInt32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeUInt64(buf: ByteArray, off: Int, v: Long) {
        buf[off] = ((v shr 56) and 0xFF).toByte()
        buf[off + 1] = ((v shr 48) and 0xFF).toByte()
        buf[off + 2] = ((v shr 40) and 0xFF).toByte()
        buf[off + 3] = ((v shr 32) and 0xFF).toByte()
        buf[off + 4] = ((v shr 24) and 0xFF).toByte()
        buf[off + 5] = ((v shr 16) and 0xFF).toByte()
        buf[off + 6] = ((v shr 8) and 0xFF).toByte()
        buf[off + 7] = (v and 0xFF).toByte()
    }

    private fun startSyncLoop() {
        syncThread = thread(name = "ap2-sync") {
            while (running) { sendSyncPacket(); Thread.sleep(1000) }
        }
    }

    private fun startKeepAliveLoop() {
        keepAliveThread = thread(name = "ap2-keepalive") {
            while (running) {
                try {
                    sendRtspRequest("POST", "$sessionUrl/feedback", null, ByteArray(0))
                } catch (_: Exception) {}
                Thread.sleep(25000)
            }
        }
    }

    private var ptpClockId = secureRandom.nextLong()

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
