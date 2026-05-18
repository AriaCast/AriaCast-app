package com.aria.ariacast.raop

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.internal.and
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RaopClient(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val dacpId: String,
    private val activeRemote: String,
    private val sampleRate: Int,
    private val channels: Int,
    private val frameSize: Int,
    private val sharedSecret: String? = null
) {
    private val tag = "RaopClient"

    private val rtspSocket = Socket()
    private var rtspOutput: OutputStream? = null
    private var rtspInput: BufferedReader? = null
    private var localAddress: String = "0.0.0.0"
    private var sessionUrl: String = ""
    private var sessionId: String? = null
    private var cseq = 0
    private var rsaAesKeyB64: String = ""
    private var aesIvB64: String = ""
    private var aesCipher: Cipher? = null
    private var aesKey: ByteArray = ByteArray(16)
    private var aesIv: ByteArray = ByteArray(16)

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    private var audioRemotePort = 0
    private var controlRemotePort = 0
    private var timingRemotePort = 0

    private var sequence = 0
    private var rtpTimestamp = 0
    private var syncPacketSent = false
    private val ssrc = SecureRandom().nextInt().toUInt().toLong()
    private val latencyFrames = 11025
    private val sessionNum = SecureRandom().nextInt()

    @Volatile private var running = false
    private var timingThread: Thread? = null
    private var syncThread: Thread? = null
    private var keepAliveThread: Thread? = null

    private val nativeAlac = NativeAlac()
    private var alacHandle: Long = 0

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            rtspSocket.connect(InetSocketAddress(host, port), 5000)
            rtspSocket.tcpNoDelay = true
            rtspSocket.soTimeout = 5000
            rtspOutput = rtspSocket.getOutputStream()
            rtspInput = BufferedReader(InputStreamReader(rtspSocket.getInputStream()))
            localAddress = rtspSocket.localAddress.hostAddress ?: "0.0.0.0"
            sessionUrl = "rtsp://$localAddress/${SecureRandom().nextInt(Int.MAX_VALUE).toUInt()}"
            sequence = SecureRandom().nextInt(0xFFFF)
            rtpTimestamp = SecureRandom().nextInt()

            setupCrypto()
            sendOptions()
            setupRtpSockets()
            sendAnnounce()
            sendSetup()
            sendRecord()

            alacHandle = nativeAlac.createEncoder(sampleRate, channels, frameSize)
            running = true
            startTimingLoop()
            startSyncLoop()
            startKeepAliveLoop()
            true
        } catch (e: Exception) {
            Log.e(tag, "RAOP connect failed", e)
            close()
            false
        }
    }

suspend fun sendAudioFrame(pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val encoded = ByteArray(pcm.size + 4096)
        val alacSize = nativeAlac.encode(alacHandle, pcm, pcm.size, encoded)
        if (alacSize <= 0) return@withContext false

        val rtpHeader = ByteArray(12)
        rtpHeader[0] = 0x80.toByte()
        rtpHeader[1] = 0x60.toByte()
        rtpHeader[2] = ((sequence shr 8) and 0xFF).toByte()
        rtpHeader[3] = (sequence and 0xFF).toByte()
        val ts = rtpTimestamp
        rtpHeader[4] = ((ts shr 24) and 0xFF).toByte()
        rtpHeader[5] = ((ts shr 16) and 0xFF).toByte()
        rtpHeader[6] = ((ts shr 8) and 0xFF).toByte()
        rtpHeader[7] = (ts and 0xFF).toByte()
        val ssrcInt = ssrc.toInt()
        rtpHeader[8] = ((ssrcInt shr 24) and 0xFF).toByte()
        rtpHeader[9] = ((ssrcInt shr 16) and 0xFF).toByte()
        rtpHeader[10] = ((ssrcInt shr 8) and 0xFF).toByte()
        rtpHeader[11] = (ssrcInt and 0xFF).toByte()

        val payload = encoded.copyOfRange(0, alacSize)
        val encrypted = encryptAesCbc(payload)
        val packet = ByteArray(rtpHeader.size + encrypted.size)
        System.arraycopy(rtpHeader, 0, packet, 0, rtpHeader.size)
        System.arraycopy(encrypted, 0, packet, rtpHeader.size, encrypted.size)

        val target = InetAddress.getByName(host)
        val dp = DatagramPacket(packet, packet.size, target, audioRemotePort)
        audioSocket?.send(dp)

        sequence = (sequence + 1) and 0xFFFF
        rtpTimestamp += frameSize
        true
    }

    fun sendMetadata(title: String?, artist: String?, album: String?) {
        val dmap = DmapEncoder.encode(title, artist, album)
        if (dmap.isEmpty()) return
        sendRtspRequest(
            method = "SET_PARAMETER",
            headers = mapOf(
                "Content-Type" to "application/x-dmap-tagged",
                "Content-Length" to dmap.size.toString()
            ),
            body = dmap
        )
    }

    fun setVolumeDb(db: Float) {
        val body = "volume: ${"%.6f".format(Locale.US, db)}\r\n"
        sendRtspRequest(
            method = "SET_PARAMETER",
            headers = mapOf(
                "Content-Type" to "text/parameters",
                "Content-Length" to body.toByteArray().size.toString()
            ),
            body = body.toByteArray()
        )
    }

    fun close() {
        try {
            running = false
            timingThread?.interrupt()
            syncThread?.interrupt()
            keepAliveThread?.interrupt()
            if (alacHandle != 0L) {
                nativeAlac.destroyEncoder(alacHandle)
                alacHandle = 0L
            }
            audioSocket?.close()
            controlSocket?.close()
            timingSocket?.close()
            rtspSocket.close()
        } catch (_: Exception) {
        }
    }

    private fun setupCrypto() {
        val random = SecureRandom()
        random.nextBytes(aesKey)
        random.nextBytes(aesIv)

        val rsa = RaopKeys.getPublicKey()
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, rsa)
        val rsaEncrypted = cipher.doFinal(aesKey)
        rsaAesKeyB64 = Base64.encodeToString(rsaEncrypted, Base64.NO_WRAP).trimEnd('=')
        aesIvB64 = Base64.encodeToString(aesIv, Base64.NO_WRAP).trimEnd('=')

        aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
        aesCipher?.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
    }

    private fun sendOptions() {
        val output = rtspOutput ?: return
        val request = StringBuilder()
        request.append("OPTIONS * RTSP/1.0\r\n")
        request.append("CSeq: ${++cseq}\r\n")
        request.append("User-Agent: AirPlay/366.0\r\n")
        request.append("\r\n")
        output.write(request.toString().toByteArray())
        output.flush()
        readRtspResponse()
    }

    private fun sendAnnounce() {
        val sdp = buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionNum 0 IN IP4 $localAddress\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 $localAddress\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 AppleLossless\r\n")
            append("a=fmtp:96 ${frameSize} 0 16 40 10 14 ${channels} 255 0 0 ${sampleRate}\r\n")
            append("a=rsaaeskey:$rsaAesKeyB64\r\n")
            append("a=aesiv:$aesIvB64\r\n")
        }

        sendRtspRequest(
            method = "ANNOUNCE",
            headers = mapOf(
                "Content-Type" to "application/sdp",
                "Content-Length" to sdp.toByteArray().size.toString()
            ),
            body = sdp.toByteArray()
        )
    }

    private fun setupRtpSockets() {
        timingSocket = DatagramSocket(0)
        controlSocket = DatagramSocket(0)
        audioSocket = DatagramSocket(0)
    }

    private fun sendSetup() {
        val controlPort = controlSocket?.localPort ?: 0
        val timingPort = timingSocket?.localPort ?: 0

        val transport = "RTP/AVP/UDP;unicast;mode=record;control_port=$controlPort;timing_port=$timingPort"
        sendRtspRequest(
            method = "SETUP",
            headers = mapOf(
                "Transport" to transport
            )
        ) { headers ->
            val session = headers["Session"]?.substringBefore(";")?.trim()
            if (session != null) sessionId = session
            val transportHeader = headers["Transport"] ?: return@sendRtspRequest
            transportHeader.split(";").forEach { part ->
                when {
                    part.trim().startsWith("server_port=") -> audioRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                    part.trim().startsWith("control_port=") -> controlRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                    part.trim().startsWith("timing_port=") -> timingRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                }
            }
        }
    }

    private fun sendRecord() {
        sendRtspRequest(
            method = "RECORD",
            headers = mapOf(
                "Range" to "npt=0-",
                "RTP-Info" to "seq=$sequence;rtptime=$rtpTimestamp"
            )
        )
    }

    private fun startTimingLoop() {
        timingThread = thread(name = "raop-timing") {
            try {
                while (running) {
                    sendTimingRequest()
                    Thread.sleep(3000)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startSyncLoop() {
        syncThread = thread(name = "raop-sync") {
            try {
                while (running) {
                    sendSyncPacket()
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startKeepAliveLoop() {
        keepAliveThread = thread(name = "raop-keepalive") {
            try {
                while (running) {
                    val body = "progress: 0/0/0\r\n"
                    sendRtspRequest(
                        method = "SET_PARAMETER",
                        headers = mapOf(
                            "Content-Type" to "text/parameters",
                            "Content-Length" to body.toByteArray().size.toString()
                        ),
                        body = body.toByteArray()
                    )
                    Thread.sleep(25000)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendTimingRequest() {
        val socket = timingSocket ?: return
        val targetPort = timingRemotePort
        if (targetPort == 0) return

        val packet = ByteArray(32)
        packet[0] = 0x80.toByte()
        packet[1] = 0xD2.toByte()
        packet[2] = 0
        packet[3] = 7

        val origin = currentNtpTime()
        writeUInt64(packet, 8, origin)
        writeUInt64(packet, 16, 0)
        writeUInt64(packet, 24, 0)

        val dp = DatagramPacket(packet, packet.size, InetAddress.getByName(host), targetPort)
        socket.send(dp)
    }

    private fun sendSyncPacket() {
        val socket = controlSocket ?: return
        val targetPort = controlRemotePort
        if (targetPort == 0) return

        val now = currentNtpTime()
        val rtpTimestampLatency = (rtpTimestamp - latencyFrames).coerceAtLeast(0)

        val packet = ByteArray(20)
        packet[0] = (if (syncPacketSent) 0x80 else 0x90).toByte()
        packet[1] = 0xD4.toByte()
        packet[2] = 0
        packet[3] = 7
        syncPacketSent = true

        writeUInt32(packet, 4, rtpTimestampLatency)
        writeUInt64(packet, 8, now)
        writeUInt32(packet, 16, rtpTimestamp)

        val dp = DatagramPacket(packet, packet.size, InetAddress.getByName(host), targetPort)
        socket.send(dp)
    }

    private fun sendRtspRequest(
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        onHeaders: ((Map<String, String>) -> Unit)? = null
    ) {
        val output = rtspOutput ?: return
        val request = StringBuilder()
        val url = if (method == "OPTIONS") "*" else sessionUrl
        request.append("$method $url RTSP/1.0\r\n")
        request.append("CSeq: ${++cseq}\r\n")
        request.append("User-Agent: AirPlay/366.0\r\n")
        request.append("Client-Instance: ${deviceId.replace(":", "")}\r\n")
        request.append("X-Apple-Device-ID: 0x${deviceId.replace(":", "")}\r\n")
        request.append("DACP-ID: $dacpId\r\n")
        request.append("Active-Remote: $activeRemote\r\n")
        request.append("X-Apple-Client-Name: AriaCast\r\n")
        sessionId?.let { request.append("Session: $it\r\n") }
        if (sharedSecret != null && method == "ANNOUNCE") {
            val challenge = buildAppleChallenge()
            request.append("Apple-Challenge: $challenge\r\n")
        }
        headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
        request.append("\r\n")

        output.write(request.toString().toByteArray())
        if (body != null) output.write(body)
        output.flush()

        val responseHeaders = readRtspResponse()
        onHeaders?.invoke(responseHeaders)
    }

    private fun readRtspResponse(): Map<String, String> {
        val input = rtspInput ?: return emptyMap()
        val headers = linkedMapOf<String, String>()
        var line: String?
        while (true) {
            line = input.readLine() ?: break
            if (line!!.isEmpty()) break
            if (line!!.startsWith("RTSP/")) continue
            val parts = line!!.split(":", limit = 2)
            if (parts.size == 2) headers[parts[0].trim()] = parts[1].trim()
        }
        return headers
    }

    private fun buildAppleChallenge(): String {
        val seed = ByteArray(16)
        SecureRandom().nextBytes(seed)
        return Base64.encodeToString(seed, Base64.NO_WRAP)
    }

    private fun encryptAesCbc(input: ByteArray): ByteArray {
        val block = 16
        val padLen = ((input.size + block - 1) / block) * block
        val padded = if (padLen == input.size) input else input.copyOf(padLen)
        return try {
            val cipher = aesCipher ?: Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            cipher.doFinal(padded)
        } catch (e: Exception) {
            padded
        }
    }

    private fun currentNtpTime(): Long {
        val ms = System.currentTimeMillis()
        val seconds = ms / 1000 + 2208988800L
        val fraction = ((ms % 1000) * 0x100000000L / 1000)
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUInt64(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 56) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 48) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 40) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 32) and 0xFF).toByte()
        buffer[offset + 4] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 5] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 6] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 7] = (value and 0xFF).toByte()
    }
}
