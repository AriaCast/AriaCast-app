package com.aria.ariacast.cast

import com.aria.ariacast.airplay.ProtoLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Now-playing info shown on the Cast device. */
data class CastMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val imageUrl: String?
)

interface CastListener {
    /** Device volume changed (0.0-1.0), e.g. from the Home app or the speaker's buttons. */
    fun onVolume(level: Double, muted: Boolean) {}

    /** Playback was controlled from the device side: "play", "pause", "stop". */
    fun onRemoteCommand(command: String) {}

    /** The connection or the receiver app went away. */
    fun onDisconnected(reason: String) {}
}

/**
 * Google Cast (CastV2) sender: TLS on port 8009, length-prefixed protobuf
 * [CastMessage]s carrying JSON on namespaces:
 *
 *  - tp.connection - CONNECT/CLOSE virtual connections (to "receiver-0" and to the app)
 *  - tp.heartbeat  - PING/PONG every 5 s, both directions
 *  - receiver      - LAUNCH / STOP apps, GET_STATUS, SET_VOLUME
 *  - media         - LOAD the stream, MEDIA_STATUS, QUEUE_UPDATE metadata, PLAY/PAUSE/STOP
 *
 * The audio itself is pulled by the device over HTTP from the app's stream server;
 * this client launches the Default Media Receiver and points it at that URL.
 */
class CastClient(
    val host: String,
    val port: Int = 8009,
    var listener: CastListener? = null,
    /** Lets tests talk to a fake receiver without TLS. */
    private val useTls: Boolean = true
) {
    companion object {
        private const val TAG = "CastClient"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        const val RECEIVER_ID = "receiver-0"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val senderId = "sender-" + (SecureRandom().nextInt() and 0x7FFFFFFF)
    private lateinit var socket: Socket
    private lateinit var output: OutputStream
    private val writeLock = Any()
    private val requestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<JsonObject>>()
    @Volatile private var closed = false
    @Volatile private var lastReceivedNanos = System.nanoTime()
    private var reader: Thread? = null
    private var heartbeat: Thread? = null

    @Volatile var transportId: String? = null; private set
    @Volatile var appSessionId: String? = null; private set
    @Volatile var mediaSessionId: Int? = null; private set
    @Volatile private var currentItemId: Int? = null
    @Volatile var volumeLevel: Double = -1.0; private set
    @Volatile var muted: Boolean = false; private set
    @Volatile var playerState: String? = null; private set
    private var loadedUrl: String? = null
    private var loadedContentType: String? = null
    /** Set while we expect a state change we caused, so it isn't reported as a remote command. */
    @Volatile private var expectedState: String? = null

    fun connect() {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5000)
        raw.tcpNoDelay = true
        socket = if (useTls) {
            // Cast devices present self-signed certificates (rotated every day or two), so
            // there is nothing to validate against; only stream URLs and metadata cross it.
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply { startHandshake() }
        } else raw
        output = socket.getOutputStream()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        reader = thread(name = "cast-read-$host", isDaemon = true) { readLoop(input) }
        send(RECEIVER_ID, NS_CONNECTION, buildJsonObject { put("type", "CONNECT"); put("origin", JsonObject(emptyMap())) })
        heartbeat = thread(name = "cast-heartbeat-$host", isDaemon = true) { heartbeatLoop() }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > CastMessage.MAX_MESSAGE_SIZE) throw IOException("bad frame length $len")
                val buf = ByteArray(len)
                input.readFully(buf)
                lastReceivedNanos = System.nanoTime()
                handle(CastMessage.decode(buf))
            }
        } catch (e: Exception) {
            if (!closed) {
                ProtoLog.w(TAG, "$host: connection lost: ${e.message}")
                failPending(e)
                listener?.onDisconnected(e.message ?: "connection lost")
            }
        }
    }

    private fun heartbeatLoop() {
        while (!closed) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS) } catch (_: InterruptedException) { return }
            if (closed) return
            if (System.nanoTime() - lastReceivedNanos > TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_TIMEOUT_MS)) {
                ProtoLog.w(TAG, "$host: heartbeat timeout")
                listener?.onDisconnected("heartbeat timeout")
                close()
                return
            }
            try {
                send(RECEIVER_ID, NS_HEARTBEAT, buildJsonObject { put("type", "PING") })
            } catch (e: Exception) {
                if (!closed) listener?.onDisconnected(e.message ?: "send failed")
                return
            }
        }
    }

    private fun failPending(e: Exception) {
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    private fun handle(msg: CastMessage) {
        val payload = msg.payloadUtf8 ?: return
        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (e: Exception) { return }
        val type = obj.str("type")
        when (msg.namespace) {
            NS_HEARTBEAT -> if (type == "PING") {
                send(msg.sourceId, NS_HEARTBEAT, buildJsonObject { put("type", "PONG") }, from = msg.destinationId)
            }
            NS_CONNECTION -> if (type == "CLOSE") {
                if (msg.sourceId == transportId) {
                    ProtoLog.i(TAG, "$host: receiver app closed the connection")
                    transportId = null
                    if (!closed) listener?.onDisconnected("receiver app closed")
                }
            }
            NS_RECEIVER -> {
                if (type == "RECEIVER_STATUS") handleReceiverStatus(obj)
            }
            NS_MEDIA -> {
                if (type == "MEDIA_STATUS") handleMediaStatus(obj)
            }
        }
        obj["requestId"]?.jsonPrimitive?.intOrNull?.let { id -> pending.remove(id)?.complete(obj) }
    }

    private fun handleReceiverStatus(obj: JsonObject) {
        val status = obj["status"]?.jsonObject ?: return
        status["volume"]?.jsonObject?.let { v ->
            val level = v["level"]?.jsonPrimitive?.doubleOrNull
            val m = v["muted"]?.jsonPrimitive?.booleanOrNull ?: false
            if (level != null && (level != volumeLevel || m != muted)) {
                // Reported on connect too, so the phone's volume slider starts from the
                // speaker's actual level instead of jumping it on the first key press.
                volumeLevel = level
                muted = m
                listener?.onVolume(level, m)
            }
        }
        val apps = status["applications"]?.jsonArray
        val ours = apps?.firstOrNull { it.jsonObject.str("appId") == DEFAULT_MEDIA_RECEIVER }?.jsonObject
        if (appSessionId != null && ours?.str("sessionId") != appSessionId) {
            // Someone launched another app (or stopped ours) on the device.
            ProtoLog.i(TAG, "$host: our receiver app is no longer running")
            appSessionId = null
            transportId = null
            if (!closed) listener?.onDisconnected("receiver app stopped")
        }
    }

    private fun handleMediaStatus(obj: JsonObject) {
        val status = obj["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
        status["mediaSessionId"]?.jsonPrimitive?.intOrNull?.let { mediaSessionId = it }
        status["currentItemId"]?.jsonPrimitive?.intOrNull?.let { currentItemId = it }
        val state = status.str("playerState") ?: return
        val previous = playerState
        playerState = state
        if (state == expectedState) { expectedState = null; return }
        if (previous != null && previous != state) {
            when (state) {
                "PAUSED" -> listener?.onRemoteCommand("pause")
                "PLAYING" -> if (previous == "PAUSED") listener?.onRemoteCommand("play")
                "IDLE" -> if (status.str("idleReason") in setOf("CANCELLED", "INTERRUPTED")) listener?.onRemoteCommand("stop")
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun send(destination: String, namespace: String, payload: JsonObject, from: String = senderId) {
        val bytes = CastMessage(from, destination, namespace, payloadUtf8 = payload.toString()).encode()
        synchronized(writeLock) {
            output.write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            output.write(bytes)
            output.flush()
        }
    }

    /** Sends a request carrying a fresh requestId and waits for the reply with the same id. */
    private fun request(destination: String, namespace: String, payload: JsonObject): JsonObject {
        val id = requestId.getAndIncrement()
        val future = java.util.concurrent.CompletableFuture<JsonObject>()
        pending[id] = future
        send(destination, namespace, JsonObject(payload + ("requestId" to JsonPrimitive(id))))
        return try {
            future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IOException("${payload["type"]} failed: ${e.cause?.message}", e.cause)
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.remove(id)
            throw IOException("${payload["type"]}: no reply from $host")
        }
    }

    fun getStatus(): JsonObject = request(RECEIVER_ID, NS_RECEIVER, buildJsonObject { put("type", "GET_STATUS") })

    /** Launches (or joins) the Default Media Receiver and connects to it. */
    fun launch(appId: String = DEFAULT_MEDIA_RECEIVER) {
        val status = getStatus()
        var app = findApp(status, appId)
        if (app == null) {
            val reply = request(RECEIVER_ID, NS_RECEIVER, buildJsonObject { put("type", "LAUNCH"); put("appId", appId) })
            if (reply.str("type") == "LAUNCH_ERROR") throw IOException("launch failed: ${reply.str("reason")}")
            app = findApp(reply, appId)
            var tries = 0
            while (app == null && tries++ < 10) { // some devices answer before the app is up
                Thread.sleep(500)
                app = findApp(getStatus(), appId)
            }
        }
        app ?: throw IOException("receiver app $appId did not start")
        transportId = app.str("transportId")
        appSessionId = app.str("sessionId")
        send(transportId!!, NS_CONNECTION, buildJsonObject { put("type", "CONNECT"); put("origin", JsonObject(emptyMap())) })
        ProtoLog.d(TAG, "$host: app $appId running, transport=$transportId")
    }

    private fun findApp(reply: JsonObject, appId: String): JsonObject? =
        reply["status"]?.jsonObject?.get("applications")?.jsonArray
            ?.map { it.jsonObject }?.firstOrNull { it.str("appId") == appId }

    private fun mediaInfo(url: String, contentType: String, metadata: CastMetadata?): JsonObject = buildJsonObject {
        put("contentId", url)
        put("contentUrl", url)
        put("contentType", contentType)
        put("streamType", "LIVE")
        put("metadata", buildJsonObject {
            put("metadataType", 3) // MusicTrackMediaMetadata
            put("title", metadata?.title ?: "AriaCast")
            metadata?.artist?.let { put("artist", it) }
            metadata?.album?.let { put("albumName", it) }
            metadata?.imageUrl?.let { url -> put("images", buildJsonArray { add(buildJsonObject { put("url", url) }) }) }
        })
    }

    /** Starts playback of [url]; returns once the device reports a media session. */
    fun load(url: String, contentType: String, metadata: CastMetadata?) {
        val transport = transportId ?: throw IOException("no receiver app")
        loadedUrl = url
        loadedContentType = contentType
        expectedState = "PLAYING"
        val reply = request(transport, NS_MEDIA, buildJsonObject {
            put("type", "LOAD")
            put("media", mediaInfo(url, contentType, metadata))
            put("autoplay", true)
            put("currentTime", 0)
        })
        when (reply.str("type")) {
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> throw IOException("LOAD rejected: ${reply.str("type")} ${reply.str("reason") ?: ""}")
        }
        ProtoLog.i(TAG, "$host: loaded $url (mediaSession=$mediaSessionId)")
    }

    /** Updates the displayed metadata of the running live stream without reloading it. */
    fun updateMetadata(metadata: CastMetadata) {
        val transport = transportId ?: return
        val session = mediaSessionId ?: return
        val url = loadedUrl ?: return
        val item = currentItemId
        try {
            val payload = buildJsonObject {
                put("type", "QUEUE_UPDATE")
                put("mediaSessionId", session)
                if (item != null) put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("itemId", item)
                        put("media", mediaInfo(url, loadedContentType ?: "audio/wav", metadata))
                    })
                })
            }
            send(transport, NS_MEDIA, JsonObject(payload + ("requestId" to JsonPrimitive(requestId.getAndIncrement()))))
        } catch (e: Exception) {
            ProtoLog.w(TAG, "$host: metadata update failed: ${e.message}")
        }
    }

    fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        volumeLevel = clamped
        send(RECEIVER_ID, NS_RECEIVER, buildJsonObject {
            put("type", "SET_VOLUME"); put("requestId", requestId.getAndIncrement())
            put("volume", buildJsonObject { put("level", clamped) })
        })
    }

    fun setMuted(mute: Boolean) {
        muted = mute
        send(RECEIVER_ID, NS_RECEIVER, buildJsonObject {
            put("type", "SET_VOLUME"); put("requestId", requestId.getAndIncrement())
            put("volume", buildJsonObject { put("muted", mute) })
        })
    }

    private fun mediaCommand(type: String, expect: String?) {
        val transport = transportId ?: return
        val session = mediaSessionId ?: return
        expectedState = expect
        send(transport, NS_MEDIA, buildJsonObject {
            put("type", type); put("requestId", requestId.getAndIncrement()); put("mediaSessionId", session)
        })
    }

    fun play() = mediaCommand("PLAY", "PLAYING")
    fun pause() = mediaCommand("PAUSE", "PAUSED")

    /** Stops our receiver app (leaving the device idle) - best effort. */
    fun stop() {
        try {
            mediaCommand("STOP", "IDLE")
            appSessionId?.let { sid ->
                appSessionId = null
                send(RECEIVER_ID, NS_RECEIVER, buildJsonObject {
                    put("type", "STOP"); put("requestId", requestId.getAndIncrement()); put("sessionId", sid)
                })
            }
            transportId?.let { send(it, NS_CONNECTION, buildJsonObject { put("type", "CLOSE") }) }
            send(RECEIVER_ID, NS_CONNECTION, buildJsonObject { put("type", "CLOSE") })
        } catch (e: Exception) {
            ProtoLog.d(TAG, "$host stop: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        heartbeat?.interrupt()
        failPending(IOException("closed"))
        try { socket.close() } catch (_: Exception) {}
    }
}
