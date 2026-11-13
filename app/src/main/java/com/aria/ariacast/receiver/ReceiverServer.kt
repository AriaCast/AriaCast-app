package com.aria.ariacast.receiver

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The AriaCast native-protocol receiver (port of AriaCast-Receiver-for-Android's server).
 *
 * Endpoints are unchanged so every existing AriaCast sender works with it:
 * `/audio` (binary PCM in), `/control`, `/metadata` (WebSockets), `/listen` (PCM out to
 * monitors), `/artwork`, `/image/artwork`, `POST /metadata`, `GET /api/metadata`,
 * `POST /api/command`. Audio and metadata go to [listener]; the server keeps no player.
 */
class ReceiverServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onSenderConnected(remote: String)
        fun onSenderDisconnected(remote: String)
        fun onPcm(data: ByteArray)
        fun onMetadata(update: MetadataUpdate)
        fun onCommand(action: String)
        fun currentMetadata(): ReceiverMetadata
        fun currentArtwork(): ByteArray?
    }

    private var engine: ApplicationEngine? = null
    private val controlClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val metadataClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val listenClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) { maxFrameSize = 1L shl 20 }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }
            routing {
                webSocket("/audio") {
                    val remote = call.request.origin.remoteHost
                    send(Frame.Text(json.encodeToString(HandshakeResponse.serializer(), HandshakeResponse("READY", SAMPLE_RATE, CHANNELS, FRAME_BYTES))))
                    listener.onSenderConnected(remote)
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val data = frame.data
                            listener.onPcm(data)
                            if (listenClients.isNotEmpty()) {
                                listenClients.forEach { c -> scope.launch { runCatching { c.send(Frame.Binary(true, data)) } } }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio socket closed: ${e.message}")
                    } finally {
                        listener.onSenderDisconnected(remote)
                    }
                }

                webSocket("/control") {
                    controlClients.add(this)
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            runCatching { json.decodeFromString(ControlMessage.serializer(), frame.readText()) }
                                .getOrNull()?.let { msg -> (msg.action ?: msg.command)?.let(listener::onCommand) }
                        }
                    } finally {
                        controlClients.remove(this)
                    }
                }

                webSocket("/metadata") {
                    metadataClients.add(this)
                    runCatching { send(Frame.Text(metadataMessage(listener.currentMetadata()))) }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            runCatching {
                                val root = json.parseToJsonElement(frame.readText()).jsonObject
                                if (root["type"]?.jsonPrimitive?.contentOrNull == "update") {
                                    root["data"]?.jsonObject?.let { listener.onMetadata(parseMetadata(it)) }
                                }
                            }
                        }
                    } finally {
                        metadataClients.remove(this)
                    }
                }

                webSocket("/listen") {
                    listenClients.add(this)
                    try {
                        send(Frame.Text("""{"sample_rate":$SAMPLE_RATE,"channels":$CHANNELS}"""))
                        for (@Suppress("UNUSED_VARIABLE") frame in incoming) { /* monitors only listen */ }
                    } finally {
                        listenClients.remove(this)
                    }
                }

                get("/artwork") { respondArtwork() }
                get("/image/artwork") { respondArtwork() }

                post("/metadata") {
                    try {
                        val body = json.parseToJsonElement(call.receiveText()).jsonObject
                        val data = body["data"]?.jsonObject ?: body
                        listener.onMetadata(parseMetadata(data))
                        call.respondText("""{"success":true}""", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText("""{"error":"bad request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }

                get("/api/metadata") {
                    call.respondText(json.encodeToString(ReceiverMetadata.serializer(), listener.currentMetadata()), ContentType.Application.Json)
                }

                post("/api/command") {
                    try {
                        val msg = json.decodeFromString(ControlMessage.serializer(), call.receiveText())
                        (msg.action ?: msg.command)?.let(listener::onCommand)
                        call.respondText("""{"success":true}""", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText("""{"error":"bad request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.respondArtwork() {
        val bytes = listener.currentArtwork()
        if (bytes != null) call.respondBytes(bytes, ContentType.Image.JPEG) else call.respondText("", status = HttpStatusCode.NotFound)
    }

    /** Pushes the current metadata to every /metadata subscriber. */
    fun broadcastMetadata(meta: ReceiverMetadata) {
        val text = metadataMessage(meta)
        metadataClients.forEach { c -> scope.launch { runCatching { c.send(Frame.Text(text)) } } }
    }

    /** Sends a transport command (play, pause, next...) back to the connected sender(s). */
    fun sendCommandToSenders(action: String) {
        val text = json.encodeToString(ControlMessage.serializer(), ControlMessage(action = action))
        controlClients.forEach { c -> scope.launch { runCatching { c.send(Frame.Text(text)) } } }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        controlClients.clear(); metadataClients.clear(); listenClients.clear()
    }

    private fun metadataMessage(meta: ReceiverMetadata) =
        """{"type":"metadata","data":${json.encodeToString(ReceiverMetadata.serializer(), meta)}}"""

    companion object {
        private const val TAG = "ReceiverServer"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_BYTES = 3840

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        /** Accepts both snake_case (receiver protocol) and camelCase (AriaCast sender) keys. */
        fun parseMetadata(data: JsonObject): MetadataUpdate {
            fun str(vararg keys: String) = keys.firstNotNullOfOrNull { data[it]?.jsonPrimitive?.contentOrNull }
            fun long(vararg keys: String) = keys.firstNotNullOfOrNull { data[it]?.jsonPrimitive?.longOrNull }
            fun bool(vararg keys: String) = keys.firstNotNullOfOrNull { data[it]?.jsonPrimitive?.booleanOrNull }
            return MetadataUpdate(
                title = str("title"),
                artist = str("artist"),
                album = str("album"),
                artworkUrl = str("artwork_url", "artworkUrl"),
                durationMs = long("duration_ms", "durationMs"),
                positionMs = long("position_ms", "positionMs"),
                isPlaying = bool("is_playing", "isPlaying"),
            )
        }
    }
}
