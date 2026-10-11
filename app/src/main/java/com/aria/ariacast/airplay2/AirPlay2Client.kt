package com.aria.ariacast.airplay2

import com.aria.ariacast.airplay.AirPlayAudio
import com.aria.ariacast.airplay.AirPlayAuthRequiredException
import com.aria.ariacast.airplay.AirPlayBusyException
import com.aria.ariacast.airplay.AirPlaySenderIdentity
import com.aria.ariacast.airplay.AirPlaySessionListener
import com.aria.ariacast.airplay.AirPlayStreamer
import com.aria.ariacast.airplay.AirPlayTimingUnsupportedException
import com.aria.ariacast.airplay.AirPlayTxt
import com.aria.ariacast.airplay.AirPlayVolume
import com.aria.ariacast.airplay.Bytes
import com.aria.ariacast.airplay.Dmap
import com.aria.ariacast.airplay.HapCipher
import com.aria.ariacast.airplay.NtpTimingResponder
import com.aria.ariacast.airplay.ProtoLog
import com.aria.ariacast.airplay.PtpMaster
import com.aria.ariacast.airplay.RtpAudioSender
import com.aria.ariacast.airplay.RtpPacketizer
import com.aria.ariacast.airplay.RtpSyncMode
import com.aria.ariacast.airplay.RtspCodec
import com.aria.ariacast.airplay.RtspConnection
import com.aria.ariacast.airplay.RtspException
import com.aria.ariacast.airplay.RtspMessage
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

/**
 * AirPlay 2 sender for realtime (type 96) ALAC streams.
 *
 *  1. GET /info
 *  2. pairing: pair-verify with stored credentials, else transient pairing
 *     (HKP 4, "3939" or the access password), else PIN pairing after
 *     /pair-pin-start - see [HapPairing]
 *  3. everything after pairing is HAP-encrypted ([HapCipher.forControl])
 *  4. SETUP (session: timing protocol, gets the event port) → event channel
 *     ([HapCipher.forEvents]) → SETUP (stream: shk, ALAC 44.1k, gets data &
 *     control ports) → RECORD → [SETPEERS] → SET_PARAMETER volume
 *  5. RTP audio encrypted with ChaCha20-Poly1305 under `shk`, sync packets, retransmits,
 *     POST /feedback keep-alives, DMAP metadata / artwork / progress, TEARDOWN.
 *
 * Timing: Apple receivers accept NTP (we answer their timing requests). Receivers
 * that only do PTP (shairport-sync + nqptp) get PTP when [ptpMaster] is available
 * (the PTP ports are privileged); otherwise connect() throws
 * [AirPlayTimingUnsupportedException] so the caller can fall back to AirPlay 1.
 */
class AirPlay2Client(
    override val host: String,
    private val port: Int,
    private val identity: AirPlaySenderIdentity,
    private val txt: Map<String, String> = emptyMap(),
    private val credentials: HapCredentials? = null,
    /** One-time code read off the receiver, when (re)pairing. */
    private val pin: String? = null,
    /** Access-control password configured on the receiver, if any. */
    private val password: String? = null,
    private val initialVolumeDb: Double = -15.0,
    private val ptpMaster: PtpMaster? = null,
    /** Force a timing protocol instead of trying NTP then PTP (tests). */
    private val forceTiming: Timing? = null,
    var listener: AirPlaySessionListener? = null,
    /** Called after a PIN pairing with the credentials to persist. */
    private val onPaired: (HapCredentials) -> Unit = {},
    /** Called when stored credentials were rejected and should be forgotten. */
    private val onCredentialsRejected: () -> Unit = {}
) : AirPlayStreamer {

    enum class Timing { NTP, PTP }

    companion object {
        private const val TAG = "AirPlay2Client"
        private const val FEEDBACK_INTERVAL_MS = 2000L
        private const val AUDIO_FORMAT_ALAC_44100_16_2 = 0x40000L
        private const val COMPRESSION_ALAC = 2L
        private const val STREAM_REALTIME = 96L
    }

    private val random = SecureRandom()
    private lateinit var rtsp: RtspConnection
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private var sessionUri = ""
    private val streamConnectionId = random.nextLong() and Long.MAX_VALUE
    private val ssrc = random.nextInt()
    private val shk = ByteArray(32).also { random.nextBytes(it) }

    private var sharedSecret: ByteArray? = null
    private var timingSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private var timingResponder: NtpTimingResponder? = null
    private var sender: RtpAudioSender? = null
    private var eventSocket: Socket? = null
    private var eventThread: Thread? = null
    private var feedbackThread: Thread? = null
    @Volatile private var closed = false
    @Volatile private var tornDown = false
    private var audioCounter = 0L

    var info: Map<String, Any?> = emptyMap(); private set
    var timing: Timing = Timing.NTP; private set
    var pairingMode: String = "none"; private set

    override var extraDelayMs: Int = 0
        set(value) { field = value; sender?.extraDelayMs = value }

    fun connect() {
        try {
            val timings = when {
                forceTiming != null -> listOf(forceTiming)
                ptpMaster != null -> listOf(Timing.NTP, Timing.PTP)
                else -> listOf(Timing.NTP)
            }
            var lastTimingError: Exception? = null
            for (t in timings) {
                try {
                    openAndPair()
                    startSession(t)
                    return
                } catch (e: AirPlayTimingUnsupportedException) {
                    lastTimingError = e
                    ProtoLog.i(TAG, "$host: ${t.name} timing refused (${e.message})")
                    resetConnection()
                }
            }
            throw lastTimingError ?: AirPlayTimingUnsupportedException("no timing protocol accepted")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun resetConnection() {
        sender?.stop(); sender = null
        timingResponder?.stop(); timingResponder = null
        listOf(timingSocket, controlSocket, dataSocket).forEach { it?.close() }
        timingSocket = null; controlSocket = null; dataSocket = null
        try { eventSocket?.close() } catch (_: Exception) {}
        eventSocket = null
        if (::rtsp.isInitialized) rtsp.close()
    }

    private fun reopen() {
        if (::rtsp.isInitialized) rtsp.close()
        rtsp = RtspConnection(host, if (port > 0) port else 7000).also { it.connect() }
        sessionUri = "rtsp://${rtsp.localUrlHost}/$sessionUuid"
        rtsp.defaultHeaders.apply {
            put("User-Agent", "AirPlay/550.10")
            put("Client-Instance", identity.dacpId)
            put("DACP-ID", identity.dacpId)
            put("Active-Remote", identity.activeRemote)
            put("X-Apple-Device-ID", "0x${identity.deviceIdHex}")
            put("X-Apple-ProtocolVersion", "1")
        }
    }

    private fun pairing() = HapPairing(rtsp, identity.pairingId, identity.pairingSeed, random)

    private fun openAndPair() {
        reopen()

        // 1. /info (unencrypted)
        try {
            val r = rtsp.request("GET", "/info")
            if (r.isSuccess && r.body.isNotEmpty()) info = BinaryPlist.decode(r.body)
        } catch (e: IllegalArgumentException) {
            ProtoLog.w(TAG, "$host: unreadable /info: ${e.message}")
        }
        val statusFlags = (info["statusFlags"] as? Long)
            ?: AirPlayTxt.flags(txt["flags"] ?: txt["sf"])
        ProtoLog.d(TAG, "$host info: model=${info["model"]} statusFlags=0x${statusFlags.toString(16)}")

        // 2. pairing
        sharedSecret = when {
            pin != null -> {
                val creds = try { pairing().pairSetup(pin) } catch (e: HapPairingException) {
                    if (e.isAuthenticationError) throw AirPlayAuthRequiredException(AirPlayAuthRequiredException.Kind.PIN, host, "wrong code")
                    throw e
                }
                onPaired(creds)
                pairingMode = "pin"
                pairing().verify(creds)
            }
            credentials != null -> try {
                pairingMode = "verify"
                pairing().verify(credentials)
            } catch (e: HapPairingException) {
                ProtoLog.w(TAG, "$host: stored pairing rejected (${e.message})")
                onCredentialsRejected()
                reopen() // a failed verify can leave the receiver's pairing state mid-way
                transientOrRequestPin(statusFlags)
            }
            else -> transientOrRequestPin(statusFlags)
        }

        // 3. encrypt the control channel from here on
        rtsp.enableEncryption(HapCipher.forControl(sharedSecret!!))
    }

    private fun transientOrRequestPin(statusFlags: Long): ByteArray {
        pairingMode = "transient"
        val secrets = listOfNotNull(password, HapPairing.TRANSIENT_PIN).distinct()
        var lastError: HapPairingException? = null
        for ((i, secret) in secrets.withIndex()) {
            // A failed SRP leaves the receiver's pair-setup state machine mid-way: start afresh.
            if (i > 0) reopen()
            try {
                return pairing().transient(secret)
            } catch (e: HapPairingException) {
                lastError = e
                if (e.httpCode == 453) throw AirPlayBusyException(host)
                ProtoLog.d(TAG, "$host: transient pairing (${if (secret == password) "password" else "default code"}) failed: ${e.message}")
            }
        }
        reopen()
        val passwordProtected = (statusFlags and AirPlayTxt.STATUS_PASSWORD_REQUIRED) != 0L || txt["pw"] == "true"
        if (passwordProtected && password == null) {
            throw AirPlayAuthRequiredException(AirPlayAuthRequiredException.Kind.PASSWORD, host, "password required")
        }
        // Transient pairing refused: the receiver wants a one-time code. Ask it to show one.
        try {
            val r = rtsp.request("POST", "/pair-pin-start")
            ProtoLog.d(TAG, "$host pair-pin-start -> ${r.code}")
        } catch (e: Exception) {
            ProtoLog.w(TAG, "$host pair-pin-start failed: ${e.message}")
        }
        throw AirPlayAuthRequiredException(
            if (passwordProtected) AirPlayAuthRequiredException.Kind.PASSWORD else AirPlayAuthRequiredException.Kind.PIN,
            host, "pairing required (${lastError?.message})"
        )
    }

    private fun startSession(t: Timing) {
        timing = t
        val local = rtsp.localAddress
        val localIp = local.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        val remoteIp = rtsp.remoteAddress.hostAddress?.substringBefore('%') ?: host

        // 4a. SETUP session
        val timingSock = DatagramSocket(0).also { timingSocket = it }
        if (t == Timing.NTP) timingResponder = NtpTimingResponder(timingSock, host).also { it.start() }
        val session = linkedMapOf<String, Any?>(
            "deviceID" to identity.deviceId,
            "sessionUUID" to sessionUuid,
            "timingProtocol" to t.name,
            "isMultiSelectAirPlay" to true,
            "groupContainsGroupLeader" to false,
            "groupUUID" to UUID.nameUUIDFromBytes(identity.deviceId.toByteArray()).toString().uppercase(),
            "macAddress" to identity.deviceId,
            "model" to identity.model,
            "name" to identity.name,
            "osName" to "Android",
            "sourceVersion" to "550.10",
            "senderSupportsRelay" to false,
            "statsCollectionEnabled" to false
        )
        if (t == Timing.NTP) {
            session["timingPort"] = timingSock.localPort.toLong()
        } else {
            val master = ptpMaster ?: throw AirPlayTimingUnsupportedException("no PTP master")
            session["timingPeerInfo"] = mapOf("Addresses" to listOf(localIp), "ID" to localIp)
            session["timingPeerList"] = listOf(mapOf("Addresses" to listOf(localIp), "ID" to localIp))
            master.addPeer(rtsp.remoteAddress)
            master.start()
        }
        val r1 = rtsp.request("SETUP", sessionUri, BinaryPlist.encode(session), "application/x-apple-binary-plist")
        if (r1.code == 453) throw AirPlayBusyException(host)
        val sessionReply = decodeOrEmpty(r1)
        val eventPort = (sessionReply["eventPort"] as? Long)?.toInt() ?: 0
        if (!r1.isSuccess || eventPort <= 0) {
            throw AirPlayTimingUnsupportedException("SETUP session with ${t.name} -> ${r1.code}, eventPort=$eventPort")
        }
        r1.header("Session")?.substringBefore(';')?.trim()?.let { rtsp.defaultHeaders["Session"] = it }

        // 4b. event channel
        connectEvents(eventPort)

        // 4c. SETUP stream
        val controlSock = DatagramSocket(0).also { controlSocket = it }
        dataSocket = DatagramSocket(0)
        val stream = mapOf("streams" to listOf(linkedMapOf<String, Any?>(
            "audioFormat" to AUDIO_FORMAT_ALAC_44100_16_2,
            "audioMode" to "default",
            "controlPort" to controlSock.localPort.toLong(),
            "ct" to COMPRESSION_ALAC,
            "isMedia" to true,
            "latencyMax" to 88200L,
            "latencyMin" to 11025L,
            "shk" to shk,
            "spf" to AirPlayAudio.FRAMES_PER_PACKET.toLong(),
            "sr" to AirPlayAudio.SAMPLE_RATE.toLong(),
            "type" to STREAM_REALTIME,
            "supportsDynamicStreamID" to false,
            "streamConnectionID" to streamConnectionId
        )))
        val r2 = rtsp.request("SETUP", sessionUri, BinaryPlist.encode(stream), "application/x-apple-binary-plist")
        if (!r2.isSuccess) throw AirPlayTimingUnsupportedException("SETUP stream -> ${r2.code}")
        val stream0 = (decodeOrEmpty(r2)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw RtspException("SETUP stream: no streams in reply")
        val dataPort = (stream0["dataPort"] as? Long)?.toInt() ?: throw RtspException("SETUP stream: no dataPort")
        val controlPort = (stream0["controlPort"] as? Long)?.toInt() ?: throw RtspException("SETUP stream: no controlPort")
        ProtoLog.d(TAG, "$host stream: data=$dataPort control=$controlPort event=$eventPort timing=${t.name}")

        // 4d. RECORD
        val firstSeq = random.nextInt(0x10000)
        val firstRtp = random.nextInt()
        val r3 = rtsp.request("RECORD", sessionUri, headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$firstSeq;rtptime=${firstRtp.toUInt()}"
        ))
        if (!r3.isSuccess) throw RtspException("RECORD rejected: ${r3.code}", r3.code)

        if (t == Timing.PTP) {
            val peers = BinaryPlist.encodeValue(listOf(localIp, remoteIp))
            val r4 = rtsp.request("SETPEERS", sessionUri, peers, "/peer-list-changed")
            ProtoLog.d(TAG, "$host SETPEERS -> ${r4.code}")
        }

        setVolume(initialVolumeDb)

        // 5. audio
        val packetizer = RtpPacketizer { seq, rtp, marker, pcm ->
            val header = ByteArray(12)
            header[0] = 0x80.toByte()
            header[1] = (if (marker) 0xE0 else 0x60).toByte()
            Bytes.putU16(header, 2, seq)
            Bytes.putU32(header, 4, rtp)
            Bytes.putU32(header, 8, ssrc)
            val counter = audioCounter++
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[4 + i] = (counter ushr (8 * i)).toByte()
            val payload = AirPlayAudio.alacUncompressed(pcm)
            val sealed = AirPlay2Crypto.chacha20Poly1305Seal(shk, nonce, payload, 0, payload.size, header.copyOfRange(4, 12))
            val packet = ByteArray(12 + sealed.size + 8)
            System.arraycopy(header, 0, packet, 0, 12)
            System.arraycopy(sealed, 0, packet, 12, sealed.size)
            System.arraycopy(nonce, 4, packet, 12 + sealed.size, 8)
            packet
        }
        sender = RtpAudioSender(
            name = host,
            receiver = rtsp.remoteAddress,
            dataPort = dataPort,
            controlPort = controlPort,
            dataSocket = dataSocket!!,
            controlSocket = controlSock,
            packetizer = packetizer,
            syncMode = if (t == Timing.PTP) RtpSyncMode.Ptp(ptpMaster!!.clockId) else RtpSyncMode.Ntp,
            initialSequence = firstSeq,
            initialRtpTime = firstRtp
        ).also { it.extraDelayMs = extraDelayMs; it.start() }

        startFeedback()
        ProtoLog.i(TAG, "$host streaming (AirPlay 2, ${t.name}, pairing=$pairingMode)")
    }

    private fun decodeOrEmpty(r: RtspMessage): Map<String, Any?> =
        if (r.body.isEmpty()) emptyMap() else try { BinaryPlist.decode(r.body) } catch (e: Exception) { emptyMap() }

    private fun connectEvents(eventPort: Int) {
        val secret = sharedSecret ?: return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(rtsp.remoteAddress, eventPort), 5000)
            s.tcpNoDelay = true
            eventSocket = s
            val cipher = HapCipher.forEvents(secret)
            val input = cipher.decryptingStream(BufferedInputStream(s.getInputStream()))
            val output = s.getOutputStream()
            eventThread = thread(name = "ap2-events-$host", isDaemon = true) {
                try {
                    while (!closed) {
                        val msg = RtspCodec.read(input) ?: break
                        handleEvent(msg, cipher, output)
                    }
                } catch (e: Exception) {
                    if (!closed) ProtoLog.d(TAG, "$host event channel closed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            ProtoLog.w(TAG, "$host: event channel connect failed: ${e.message}")
        }
    }

    private fun handleEvent(msg: RtspMessage, cipher: HapCipher, output: OutputStream) {
        if (msg.isResponse) return
        ProtoLog.d(TAG, "$host event: ${msg.startLine}")
        if (msg.body.isNotEmpty() && msg.header("Content-Type")?.contains("plist") == true) {
            try {
                val dict = BinaryPlist.decode(msg.body)
                when (dict["type"]) {
                    "updateInfo", "updateMRNowPlayingInfo", "updateMRSupportedCommands" -> {}
                    "volume" -> (dict["value"] as? Number)?.toDouble()?.let { listener?.onRemoteVolume(it) }
                    "command", "sendMediaRemoteCommand" -> {
                        val name = (dict["name"] ?: (dict["params"] as? Map<*, *>)?.get("command"))?.toString()
                        if (name != null) listener?.onRemoteCommand(name)
                    }
                }
                (dict["params"] as? Map<*, *>)?.get("volume")?.let { v ->
                    (v as? Number)?.toDouble()?.let { listener?.onRemoteVolume(it) }
                }
            } catch (_: Exception) {}
        }
        val reply = RtspCodec.encode("RTSP/1.0 200 OK",
            listOfNotNull(msg.header("CSeq")?.let { "CSeq" to it }, "Content-Length" to "0", "Audio-Latency" to "0"), null)
        synchronized(output) {
            output.write(cipher.encrypt(reply))
            output.flush()
        }
    }

    private fun startFeedback() {
        feedbackThread = thread(name = "ap2-feedback-$host", isDaemon = true) {
            var failures = 0
            while (!closed) {
                try { Thread.sleep(FEEDBACK_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (closed) break
                try {
                    val r = rtsp.request("POST", "/feedback")
                    failures = if (r.isSuccess || r.code == 0) 0 else failures + 1
                    if (r.code == 0) throw RtspException("no response")
                } catch (e: Exception) {
                    if (!closed) {
                        ProtoLog.w(TAG, "$host connection lost: ${e.message}")
                        listener?.onDisconnected(e.message ?: "connection lost")
                    }
                    break
                }
                if (failures >= 3) {
                    listener?.onDisconnected("feedback rejected")
                    break
                }
            }
        }
    }

    override fun writePcm(pcm: ByteArray) {
        sender?.write(pcm)
    }

    private fun setParameter(contentType: String, body: ByteArray, rtpInfo: Boolean = false) {
        if (closed || !::rtsp.isInitialized) return
        val headers = if (rtpInfo) mapOf("RTP-Info" to "rtptime=${(sender?.currentRtpTime() ?: 0).toUInt()}") else emptyMap()
        try {
            val r = rtsp.request("SET_PARAMETER", sessionUri, body, contentType, headers)
            if (!r.isSuccess) ProtoLog.d(TAG, "$host SET_PARAMETER $contentType -> ${r.code}")
        } catch (e: Exception) {
            ProtoLog.w(TAG, "$host SET_PARAMETER $contentType failed: ${e.message}")
        }
    }

    override fun setVolume(db: Double) {
        val clamped = if (db <= AirPlayVolume.MIN_DB - 0.5) AirPlayVolume.MUTE_DB else db.coerceIn(AirPlayVolume.MIN_DB, AirPlayVolume.MAX_DB)
        setParameter("text/parameters", "volume: ${AirPlayVolume.format(clamped)}\r\n".toByteArray())
    }

    private var itemId = 1L

    override fun setMetadata(title: String?, artist: String?, album: String?, durationMs: Long?) {
        setParameter("application/x-dmap-tagged", Dmap.trackInfo(title, artist, album, durationMs = durationMs, itemId = itemId++), rtpInfo = true)
    }

    override fun setArtwork(image: ByteArray, mimeType: String) {
        if (image.isNotEmpty()) setParameter(mimeType, image, rtpInfo = true)
    }

    override fun setProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val now = (sender?.currentRtpTime() ?: return).toLong() and 0xFFFFFFFFL
        val start = (now - positionMs * AirPlayAudio.SAMPLE_RATE / 1000) and 0xFFFFFFFFL
        val end = (start + durationMs * AirPlayAudio.SAMPLE_RATE / 1000) and 0xFFFFFFFFL
        setParameter("text/parameters", "progress: $start/$now/$end\r\n".toByteArray())
    }

    override fun flush() {
        val s = sender ?: return
        val seq = s.nextSequence()
        val rtp = s.nextRtpTime()
        s.flush()
        try {
            rtsp.request("FLUSH", sessionUri, headers = mapOf("RTP-Info" to "seq=$seq;rtptime=${rtp.toUInt()}"))
        } catch (e: Exception) {
            ProtoLog.w(TAG, "$host FLUSH failed: ${e.message}")
        }
    }

    override fun teardown() {
        if (tornDown || !::rtsp.isInitialized || !rtsp.isConnected) return
        tornDown = true
        sender?.stop()
        try {
            rtsp.setReadTimeout(2000)
            // Tear down the stream, then the session.
            rtsp.request("TEARDOWN", sessionUri, BinaryPlist.encode(mapOf("streams" to listOf(mapOf("type" to STREAM_REALTIME)))),
                "application/x-apple-binary-plist")
            rtsp.request("TEARDOWN", sessionUri)
        } catch (e: Exception) {
            ProtoLog.d(TAG, "$host TEARDOWN: ${e.message}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        feedbackThread?.interrupt()
        sender?.stop()
        timingResponder?.stop()
        if (timing == Timing.PTP && ::rtsp.isInitialized) ptpMaster?.removePeer(rtsp.remoteAddress)
        listOf(timingSocket, controlSocket, dataSocket).forEach { it?.close() }
        try { eventSocket?.close() } catch (_: Exception) {}
        if (::rtsp.isInitialized) rtsp.close()
    }

    /** Test access to the RTP engine. */
    internal val rtpSender: RtpAudioSender? get() = sender

    override fun statsSummary(): String {
        val s = sender ?: return "not streaming"
        return "sent=${s.packetsSent} resent=${s.packetsResent} underruns=${s.underruns} " +
            "drift(-${s.framesDropped}/+${s.framesInserted}) timing=${timing.name}/${timingResponder?.requestsAnswered ?: 0}"
    }
}
