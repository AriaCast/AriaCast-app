package com.aria.ariacast.sendspin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.security.SecureRandom

/** What a session needs to persist (implemented by SendspinIdentity, faked in tests). */
interface SendspinStore {
    val clientKeys: Noise.KeyPair
    val pairingPsk: ByteArray
    val unpairedAccess: Boolean
    /** Long-term PSK for [serverId], if paired. */
    fun longTermPsk(serverId: String): ByteArray?
    /** All (serverId, long-term PSK) records. */
    fun records(): List<Pair<String, ByteArray>>
    fun saveRecord(serverId: String, serverName: String, psk: ByteArray)
    fun removeRecord(serverId: String)
    var lastPlaybackServer: String?
    var volume: Int
    var muted: Boolean
}

data class PcmFormat(val codec: String, val sampleRate: Int, val channels: Int, val bitDepth: Int)

data class SendspinMetadata(
    val title: String?, val artist: String?, val album: String?, val artworkUrl: String?,
    val progressMs: Long?, val durationMs: Long?, val speed: Int, val serverTimestampUs: Long,
)

/**
 * One Sendspin connection, client side (the server is the Noise initiator; this device is
 * the responder). Implements the core messages, the Pairing PSK flow, re-handshakes,
 * clock sync, and the player@v1, source@v1 and metadata@v1 roles.
 * Transport-agnostic: the hub feeds WebSocket messages in and sends what [io] emits.
 */
class SendspinSession(
    private val store: SendspinStore,
    private val config: Config,
    private val io: Io,
    private val listener: Listener,
    private val clockUs: () -> Long = { System.nanoTime() / 1000 },
) {
    data class Config(
        val name: String,
        val productName: String?,
        val manufacturer: String?,
        val softwareVersion: String?,
        val player: Boolean = true,
        val source: Boolean = true,
        val metadata: Boolean = true,
        val playerFormats: List<PcmFormat> = listOf(PcmFormat("pcm", 48000, 2, 16), PcmFormat("pcm", 44100, 2, 16)),
        val bufferCapacity: Int = 1_000_000,
        val requiredLeadTimeMs: Int = 300,
        val minBufferMs: Int = 150,
    )

    interface Io {
        fun sendText(text: String)
        fun sendBinary(bytes: ByteArray)
        fun close()
    }

    interface Listener {
        /** Called on the first server/activate; return false to reject the connection (another server is active). */
        fun admit(session: SendspinSession, activities: Set<String>): Boolean = true
        fun onActivated(session: SendspinSession) {}
        fun onPlayerStart(format: PcmFormat) {}
        fun onPlayerAudio(localPlayUs: Long, pcm: ByteArray) {}
        fun onPlayerClear(fromLocalUs: Long?) {}
        fun onPlayerEnd() {}
        fun onVolume(volume: Int, muted: Boolean) {}
        fun onSourceStart(): Boolean = false
        fun onSourceStop() {}
        fun onMetadata(meta: SendspinMetadata?) {}
        fun onGroup(name: String, playing: Boolean) {}
        fun onPaired(serverName: String) {}
        fun onClosed(reason: String) {}
    }

    private enum class Phase { STARTING, AWAIT_SERVER_INIT, AWAIT_NOISE1, TRANSPORT, CLOSED }

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val lock = Any()
    private var phase = Phase.STARTING

    private var clientInitBytes = ByteArray(0)
    var serverId: String? = null; private set
    private var serverKey: ByteArray? = null
    var serverName: String = ""; private set
    private var handshake: Noise.KKpsk2Handshake? = null
    private var send: Noise.CipherState? = null
    private var receive: Noise.CipherState? = null
    private var handshakeHash: ByteArray? = null
    /** 'lt', 'pr' or 'sn': which PSK keyed this session. */
    var matchedCategory: String? = null; private set

    var activities: Set<String> = emptySet(); private set
    var activeRoles: Set<String> = emptySet(); private set
    private var activatedOnce = false
    private var pairingIndex = 0
    private var pendingLtPsk: ByteArray? = null

    private var playerStreamActive = false
    private var sourceStreamOpen = false
    private var stateSent = false
    var sourceSignal = false; private set

    // fragments
    private var fragType = -1
    private var fragBuffer: java.io.ByteArrayOutputStream? = null

    // clock sync
    val timeFilter = TimeFilter()
    private var burstRemaining = 0
    private var burstBest: Triple<Double, Double, Long>? = null
    private var timeOutstandingSince = 0L
    private var lastBurstStart = 0L
    private var bursts = 0

    val isPaired: Boolean get() = matchedCategory == "lt"

    // ------------------------------------------------------------------ outgoing

    fun start() {
        val init = buildJsonObject {
            put("type", "client/init")
            putJsonObject("payload") {
                put("client_id", SendspinCodec.b64url(store.clientKeys.public))
                put("version", 1)
                put("suite", "25519_ChaChaPoly_SHA256")
            }
        }.toString()
        clientInitBytes = init.toByteArray(Charsets.UTF_8)
        phase = Phase.AWAIT_SERVER_INIT
        io.sendText(init)
    }

    private fun sendJson(type: String, payload: JsonObject) {
        val body = buildJsonObject { put("type", type); put("payload", payload) }.toString().toByteArray(Charsets.UTF_8)
        sendBinaryMessage(0, body)
    }

    private fun sendBinaryMessage(type: Int, body: ByteArray) = synchronized(lock) {
        val s = send ?: return
        require(body.size + 1 + Noise.TAGLEN <= Noise.MAX_MESSAGE) { "message too large" }
        io.sendBinary(s.encryptWithAd(ByteArray(0), byteArrayOf(type.toByte()) + body))
    }

    private fun goodbye(reason: String) {
        runCatching { sendJson("client/goodbye", buildJsonObject { put("reason", reason) }) }
        close(reason)
    }

    fun close(reason: String) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        if (playerStreamActive) { playerStreamActive = false; listener.onPlayerEnd() }
        if (sourceStreamOpen) { sourceStreamOpen = false; listener.onSourceStop() }
        io.close()
        listener.onClosed(reason)
    }

    // ------------------------------------------------------------------ incoming

    fun onText(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            val payload = obj["payload"]!!.jsonObject
            when {
                phase == Phase.AWAIT_SERVER_INIT && type == "server/init" -> onServerInit(text, payload)
                phase == Phase.AWAIT_SERVER_INIT && type == "server/error" -> close("server error: ${payload["reason"]?.jsonPrimitive?.contentOrNull}")
                phase == Phase.AWAIT_NOISE1 && type == "noise/handshake" -> onNoise1(payload)
                else -> close("unexpected cleartext $type") // silent failure
            }
        } catch (e: Exception) {
            close("bad cleartext message: ${e.message}")
        }
    }

    private fun onServerInit(raw: String, p: JsonObject) {
        val id = p["server_id"]?.jsonPrimitive?.contentOrNull
        val key = SendspinCodec.decodeId(id)
        if (key == null || p["version"]?.jsonPrimitive?.intOrNull != 1) return close("bad server/init")
        serverId = id
        serverKey = key
        handshake = Noise.KKpsk2Handshake(false, store.clientKeys, key, clientInitBytes + raw.toByteArray(Charsets.UTF_8))
        phase = Phase.AWAIT_NOISE1
    }

    private fun onNoise1(p: JsonObject) {
        val hs = handshake ?: return close("no handshake")
        val data = SendspinCodec.unb64url(p["data"]!!.jsonPrimitive.content)
        val inner = json.parseToJsonElement(String(hs.readMessage1(data), Charsets.UTF_8)).jsonObject
        val (psk, category) = selectPsk(inner, allowSentinelFallback = true) ?: return close("psk lookup failed")
        hs.psk = psk
        val (msg2, transport) = hs.writeMessage2("{}".toByteArray())
        io.sendText(buildJsonObject {
            put("type", "noise/handshake")
            putJsonObject("payload") { put("data", SendspinCodec.b64url(msg2)) }
        }.toString())
        synchronized(lock) {
            send = transport.send
            receive = transport.receive
        }
        handshakeHash = transport.handshakeHash
        matchedCategory = category
        pairingIndex = 0
        phase = Phase.TRANSPORT
    }

    /** Picks the PSK named by Noise message 1's payload. Returns (psk, category) or null. */
    private fun selectPsk(inner: JsonObject, allowSentinelFallback: Boolean): Pair<ByteArray, String>? {
        val pskId = inner["psk_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val category = inner["psk_category"]?.jsonPrimitive?.contentOrNull ?: return null
        if (category !in setOf("lt", "pr", "sn")) return null
        val found: ByteArray? = when (category) {
            "lt" -> store.longTermPsk(serverId!!)?.takeIf { SendspinCodec.pskId(it) == pskId }
                ?: store.records().firstOrNull { SendspinCodec.pskId(it.second) == pskId }?.let { (sid, psk) ->
                    if (sid != serverId) return null // stored server_id must match: a misbinding, not a miss
                    psk
                }
            "pr" -> store.pairingPsk.takeIf { SendspinCodec.pskId(it) == pskId }
            else -> SendspinCodec.SENTINEL_PSK.takeIf { SendspinCodec.pskId(it) == pskId }
        }
        return when {
            found != null -> found to category
            allowSentinelFallback -> SendspinCodec.SENTINEL_PSK to "sn"
            else -> null
        }
    }

    fun onBinary(bytes: ByteArray) {
        if (phase != Phase.TRANSPORT) return close("binary before transport")
        val plain = try {
            synchronized(lock) { receive!!.decryptWithAd(ByteArray(0), bytes) }
        } catch (e: Exception) {
            return close("AEAD failure")
        }
        if (plain.isEmpty()) return close("empty message")
        val type = plain[0].toInt() and 0xFF
        if (type == 1) return onFragment(plain)
        if (fragBuffer != null) return close("message during fragment")
        dispatch(type, plain, 1)
    }

    private fun onFragment(plain: ByteArray) {
        if (plain.size < 2) return close("bad fragment")
        val flags = plain[1].toInt() and 0xFF
        if (flags and 0xFC != 0) return close("bad fragment flags")
        val first = flags and 2 != 0
        val last = flags and 1 != 0
        var off = 2
        if (first) {
            if (fragBuffer != null || plain.size < 3) return close("bad fragment sequence")
            fragType = plain[2].toInt() and 0xFF
            if (fragType == 1) return close("nested fragment")
            fragBuffer = java.io.ByteArrayOutputStream()
            off = 3
        } else if (fragBuffer == null) return close("orphan fragment")
        fragBuffer!!.write(plain, off, plain.size - off)
        if (fragBuffer!!.size() > 8 * 1024 * 1024) return close("fragment too large")
        if (last) {
            val body = fragBuffer!!.toByteArray()
            fragBuffer = null
            dispatch(fragType, byteArrayOf(fragType.toByte()) + body, 1)
        }
    }

    private fun dispatch(type: Int, plain: ByteArray, off: Int) {
        when (type) {
            0 -> {
                val obj = try { json.parseToJsonElement(String(plain, off, plain.size - off, Charsets.UTF_8)).jsonObject } catch (e: Exception) { return close("bad JSON") }
                val t = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return close("no type")
                val payload = obj["payload"] as? JsonObject ?: return close("no payload")
                try { onJson(t, payload) } catch (e: Exception) { close("bad $t: ${e.message}") }
            }
            4 -> onPlayerAudio(plain, off)
            else -> {} // other roles' binary messages: ignored
        }
    }

    private fun onJson(type: String, p: JsonObject) {
        when (type) {
            "server/hello" -> {
                serverName = p["name"]?.jsonPrimitive?.contentOrNull?.take(64) ?: ""
                sendClientHello()
            }
            "server/activate" -> onActivate(p)
            "server/time" -> onServerTime(p)
            "noise/handshake" -> onRehandshake(p)
            "stream/start" -> onStreamStart(p)
            "stream/clear" -> onStreamClear(p)
            "stream/end" -> onStreamEnd(p)
            "server/command" -> onCommand(p)
            "server/state" -> onServerState(p)
            "group/update" -> listener.onGroup(
                p["group_name"]?.jsonPrimitive?.contentOrNull ?: "",
                p["playback_state"]?.jsonPrimitive?.contentOrNull == "playing",
            )
            "server/pair-finalize" -> onPairFinalize()
            "pair/abort" -> pendingLtPsk = null
            "server/unpair" -> if (isPaired) {
                serverId?.let(store::removeRecord)
                goodbye("unpaired")
            }
            else -> {} // unrecognized types are ignored
        }
    }

    private fun sendClientHello() {
        val hello = buildJsonObject {
            put("name", config.name.take(64))
            putJsonObject("device_info") {
                config.productName?.let { put("product_name", it.take(64)) }
                config.manufacturer?.let { put("manufacturer", it.take(64)) }
                config.softwareVersion?.let { put("software_version", it.take(64)) }
            }
            putJsonArray("supported_roles") {
                if (config.player) add(JsonPrimitive("player@v1"))
                if (config.source) add(JsonPrimitive("source@v1"))
                if (config.metadata) add(JsonPrimitive("metadata@v1"))
            }
            if (config.player) putJsonObject("player@v1_support") {
                putJsonArray("supported_formats") {
                    config.playerFormats.forEach { f ->
                        add(buildJsonObject { put("codec", f.codec); put("channels", f.channels); put("sample_rate", f.sampleRate); put("bit_depth", f.bitDepth) })
                    }
                }
                put("buffer_capacity", config.bufferCapacity)
            }
            // Required for every listed role that has one; servers don't activate a role without it.
            // line_sense: client/state reports whether a signal is present.
            if (config.source) putJsonObject("source@v1_support") {
                putJsonObject("features") { put("line_sense", true) }
            }
            putJsonObject("supported_pair_methods") {
                putJsonObject("pairing_psk") { putJsonArray("locations") { add(JsonPrimitive("device")) } }
            }
            putJsonObject("unpaired_access") { put("enabled", store.unpairedAccess) }
        }
        sendJson("client/hello", hello)
    }

    // ------------------------------------------------------------------ activation

    private fun allowedSets(): Set<Set<String>> = when (matchedCategory) {
        "lt" -> setOf(emptySet(), setOf("playback"))
        else -> buildSet {
            add(emptySet()); add(setOf("pairing"))
            if (store.unpairedAccess) { add(setOf("playback")); add(setOf("playback", "pairing")) }
        }
    }

    private fun onActivate(p: JsonObject) {
        val acts = p["activities"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val roles: Set<String>? = (p["active_roles"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
        val newRoles = roles ?: if (activatedOnce) activeRoles else emptySet()
        val allowed = allowedSets()
        val playbackCapable = (acts + "playback") in allowed
        val admissible = acts in allowed && (newRoles.isEmpty() || playbackCapable)
        if (!admissible) {
            val unpaired = matchedCategory != "lt"
            if (unpaired && !store.unpairedAccess) {
                val withAccess = setOf(emptySet(), setOf("pairing"), setOf("playback"), setOf("playback", "pairing"))
                if (acts in withAccess) return goodbye("pairing_required")
            }
            return goodbye("unauthorized")
        }
        if (!activatedOnce && !listener.admit(this, acts)) return goodbye("concurrent_attempt")

        val effectiveRoles = (if (playbackCapable) newRoles else emptySet()).filter { it in supportedRoles() }.toSet()
        val removed = activeRoles - effectiveRoles
        val playbackDropped = "playback" in activities && "playback" !in acts
        if ((("player@v1" in removed) || playbackDropped) && playerStreamActive) { playerStreamActive = false; listener.onPlayerEnd() }
        // The source role doesn't depend on the playback activity: servers activate a capture
        // device with no activities at all, so only losing the role ends its stream.
        if ("source@v1" in removed && sourceStreamOpen) endSourceStream()
        if ("metadata@v1" in removed) listener.onMetadata(null)
        val added = effectiveRoles - activeRoles
        activities = acts
        activeRoles = effectiveRoles
        val first = !activatedOnce
        activatedOnce = true
        if ("playback" in acts) serverId?.let { store.lastPlaybackServer = it }
        if (first) startTimeSync()
        listener.onActivated(this)

        if ("pairing" in acts) {
            pairingIndex++
            val method = p["pairing"]?.jsonObject?.get("method")?.jsonPrimitive?.contentOrNull
            if (method != "pairing_psk" || matchedCategory != "pr") {
                sendJson("pair/abort", buildJsonObject { put("reason", "method_not_supported") })
            } else {
                val lt = ByteArray(32).also(random::nextBytes)
                pendingLtPsk = lt
                sendJson("client/pair-init", buildJsonObject { put("pairing_index", pairingIndex) })
                sendJson("client/pair-finalize", buildJsonObject { put("long_term_psk", SendspinCodec.b64url(lt)) })
            }
        }
        if (stateSent && added.any { it == "player@v1" || it == "source@v1" }) sendState()
        else if (!stateSent && activeRoles.isNotEmpty() && timeFilter.count >= 1) sendState()
    }

    private fun supportedRoles() = buildSet {
        if (config.player) add("player@v1")
        if (config.source) add("source@v1")
        if (config.metadata) add("metadata@v1")
    }

    private fun onPairFinalize() {
        val lt = pendingLtPsk ?: return
        pendingLtPsk = null
        store.saveRecord(serverId!!, serverName, lt)
        listener.onPaired(serverName)
    }

    /** In-band re-handshake: server sends Noise message 1 inside the encrypted channel. */
    private fun onRehandshake(p: JsonObject) {
        val data = SendspinCodec.unb64url(p["data"]!!.jsonPrimitive.content)
        val hs = Noise.KKpsk2Handshake(false, store.clientKeys, serverKey!!, handshakeHash!!)
        val inner = json.parseToJsonElement(String(hs.readMessage1(data), Charsets.UTF_8)).jsonObject
        val (psk, category) = selectPsk(inner, allowSentinelFallback = false) ?: return close("re-handshake psk miss")
        hs.psk = psk
        val (msg2, transport) = hs.writeMessage2("{}".toByteArray())
        // Message 2 still travels under the old keys; everything after uses the new ones.
        sendJson("noise/handshake", buildJsonObject { put("data", SendspinCodec.b64url(msg2)) })
        synchronized(lock) {
            send = transport.send
            receive = transport.receive
        }
        handshakeHash = transport.handshakeHash
        matchedCategory = category
        pairingIndex = 0
    }

    // ------------------------------------------------------------------ clock sync

    private fun startTimeSync() {
        burstRemaining = 8
        burstBest = null
        lastBurstStart = clockUs()
        sendTime()
    }

    private fun sendTime() {
        timeOutstandingSince = clockUs()
        sendJson("client/time", buildJsonObject { put("client_transmitted", timeOutstandingSince) })
    }

    private fun onServerTime(p: JsonObject) {
        val t4 = clockUs()
        val t1 = p["client_transmitted"]!!.jsonPrimitive.long
        val t2 = p["server_received"]!!.jsonPrimitive.long
        val t3 = p["server_transmitted"]!!.jsonPrimitive.long
        val measurement = ((t2 - t1) + (t3 - t4)) / 2
        val maxError = ((t4 - t1) - (t3 - t2)) / 2
        if (maxError > 0 && (burstBest == null || maxError < burstBest!!.second)) burstBest = Triple(measurement.toDouble(), maxError.toDouble(), t4)
        timeOutstandingSince = 0
        burstRemaining--
        if (burstRemaining > 0) sendTime() else finishBurst()
    }

    private fun finishBurst() {
        burstBest?.let { (m, e, t) -> timeFilter.updateMeasurement(m, e, t) }
        burstBest = null
        bursts++
        if (!stateSent && activeRoles.isNotEmpty() && timeFilter.count >= 1) sendState()
    }

    /** Called periodically by the hub (e.g. every 200 ms) to drive clock-sync bursts. */
    fun tick() {
        if (phase != Phase.TRANSPORT || !activatedOnce) return
        val now = clockUs()
        if (burstRemaining > 0 && timeOutstandingSince != 0L && now - timeOutstandingSince > 1_000_000) {
            // reply lost: move on to the next message of the burst
            burstRemaining--
            if (burstRemaining > 0) sendTime() else finishBurst()
            return
        }
        val interval = if (bursts < 5) 2_000_000L else 8_000_000L // bursts start at most 10 s apart
        if (burstRemaining == 0 && now - lastBurstStart >= interval) startTimeSync()
    }

    // ------------------------------------------------------------------ state

    fun sendState() {
        if (phase != Phase.TRANSPORT || !activatedOnce) return
        val payload = buildJsonObject {
            put("available", true)
            if ("player@v1" in activeRoles) putJsonObject("player") {
                put("volume", store.volume)
                put("muted", store.muted)
                put("output_delay_ms", 0)
                put("required_lead_time_ms", config.requiredLeadTimeMs)
                put("min_buffer_ms", config.minBufferMs)
                putJsonArray("supported_commands") { add(JsonPrimitive("volume")); add(JsonPrimitive("mute")) }
            }
            if ("source@v1" in activeRoles) putJsonObject("source") { put("signal", if (sourceSignal) "present" else "absent") }
        }
        sendJson("client/state", payload)
        stateSent = true
    }

    /** Whether this device has audio to offer the server's source input. */
    fun setSourceSignal(present: Boolean) {
        if (present == sourceSignal) return
        sourceSignal = present
        if (!present && sourceStreamOpen) endSourceStream()
        if (stateSent && "source@v1" in activeRoles) sendState()
    }

    // ------------------------------------------------------------------ player role

    private var playerFormat: PcmFormat? = null

    private fun onStreamStart(p: JsonObject) {
        val pl = p["player"] as? JsonObject ?: return
        if ("player@v1" !in activeRoles || "playback" !in activities) return
        val f = PcmFormat(pl["codec"]!!.jsonPrimitive.content, pl["sample_rate"]!!.jsonPrimitive.int,
            pl["channels"]!!.jsonPrimitive.int, pl["bit_depth"]?.jsonPrimitive?.intOrNull ?: 16)
        if (f !in config.playerFormats) return close("stream/start with a format we did not offer")
        playerFormat = f
        playerStreamActive = true
        listener.onPlayerStart(f)
    }

    private fun rolesOf(p: JsonObject): Set<String>? = (p["roles"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()

    private fun onStreamClear(p: JsonObject) {
        val roles = rolesOf(p)
        if (roles != null && "player" !in roles) return
        if (!playerStreamActive) return
        val from = p["from_timestamp"]?.jsonPrimitive?.longOrNull
        listener.onPlayerClear(from?.let { timeFilter.clientTime(it) })
    }

    private fun onStreamEnd(p: JsonObject) {
        val roles = rolesOf(p)
        if ((roles == null || "player" in roles) && playerStreamActive) {
            playerStreamActive = false
            listener.onPlayerEnd()
        }
    }

    private fun onPlayerAudio(plain: ByteArray, off: Int) {
        if (plain.size - off < 12) return close("short audio chunk")
        if (!playerStreamActive || "player@v1" !in activeRoles) return
        val ts = ByteBuffer.wrap(plain, off, 8).long
        val pcm = plain.copyOfRange(off + 12, plain.size)
        listener.onPlayerAudio(timeFilter.clientTime(ts), pcm)
    }

    private fun onCommand(p: JsonObject) {
        (p["player"] as? JsonObject)?.takeIf { "player@v1" in activeRoles }?.let { c ->
            when (c["command"]?.jsonPrimitive?.contentOrNull) {
                "volume" -> c["volume"]?.jsonPrimitive?.intOrNull?.let { store.volume = it.coerceIn(0, 100) }
                "mute" -> c["mute"]?.jsonPrimitive?.booleanOrNull?.let { store.muted = it }
                else -> return@let
            }
            listener.onVolume(store.volume, store.muted)
            sendState()
        }
        (p["source"] as? JsonObject)?.takeIf { "source@v1" in activeRoles }?.let { c ->
            when (c["command"]?.jsonPrimitive?.contentOrNull) {
                "start" -> if (!sourceStreamOpen) {
                    if (sourceSignal && listener.onSourceStart()) {
                        sendJson("client-stream/start", buildJsonObject {
                            putJsonObject("source") { put("codec", "pcm"); put("channels", 2); put("sample_rate", 48000); put("bit_depth", 16) }
                        })
                        sourceStreamOpen = true
                    } else {
                        // decline
                        sendJson("client-stream/end", buildJsonObject { putJsonArray("roles") { add(JsonPrimitive("source")) } })
                    }
                }
                "stop" -> if (sourceStreamOpen) endSourceStream()
            }
        }
    }

    private fun endSourceStream() {
        sourceStreamOpen = false
        runCatching { sendJson("client-stream/end", buildJsonObject { putJsonArray("roles") { add(JsonPrimitive("source")) } }) }
        listener.onSourceStop()
    }

    /** Sends one chunk of captured 48 kHz/16-bit stereo PCM captured at local time [captureLocalUs]. */
    fun sendSourceAudio(captureLocalUs: Long, pcm: ByteArray) {
        if (!sourceStreamOpen) return
        val ts = timeFilter.serverTime(captureLocalUs)
        sendBinaryMessage(12, ByteBuffer.allocate(8).putLong(ts).array() + pcm)
    }

    val sourceStreaming: Boolean get() = sourceStreamOpen

    // ------------------------------------------------------------------ metadata role

    private fun onServerState(p: JsonObject) {
        val m = p["metadata"] as? JsonObject ?: return
        if ("metadata@v1" !in activeRoles) return
        val progress = m["progress"] as? JsonObject
        listener.onMetadata(SendspinMetadata(
            title = m["title"]?.jsonPrimitive?.contentOrNull,
            artist = m["artist"]?.jsonPrimitive?.contentOrNull,
            album = m["album"]?.jsonPrimitive?.contentOrNull,
            artworkUrl = m["artwork_url"]?.jsonPrimitive?.contentOrNull,
            progressMs = progress?.get("track_progress")?.jsonPrimitive?.longOrNull,
            durationMs = progress?.get("track_duration")?.jsonPrimitive?.longOrNull,
            speed = progress?.get("playback_speed")?.jsonPrimitive?.intOrNull ?: 1000,
            serverTimestampUs = m["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
        ))
    }
}
