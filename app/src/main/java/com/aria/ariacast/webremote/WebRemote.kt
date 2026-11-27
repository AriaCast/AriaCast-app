package com.aria.ariacast.webremote

import com.aria.ariacast.net.startOrNull
import android.content.Context
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastDestination
import com.aria.ariacast.CastState
import com.aria.ariacast.DiscoveryManager
import com.aria.ariacast.MediaCommand
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.receiver.ReceiverService
import com.aria.ariacast.sendspin.SendspinCodec
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Web remote: a small React page (web-remote/, built into assets/web) served by the phone
 * at http://<ip>:8930, so any browser on the network can see what's playing, change the
 * volume, control playback, move the music between speakers and switch the receiver.
 * Every API call needs the access token from the link shown in the app.
 */
object WebRemote {
    const val PORT = 8930
    private const val KEY_ENABLED = "web_remote_enabled"
    private const val KEY_TOKEN = "web_remote_token"

    /** Set by AudioCastService while it exists. */
    @Volatile var service: AudioCastService? = null

    private var engine: ApplicationEngine? = null
    private var discovery: DiscoveryManager? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, null)
        ?: SendspinCodec.b64url(ByteArray(18).also(SecureRandom()::nextBytes)).also { prefs(context).edit().putString(KEY_TOKEN, it).apply() }

    /** Makes a new token; links shared earlier stop working. */
    fun resetToken(context: Context) { prefs(context).edit().remove(KEY_TOKEN).apply() }

    fun link(context: Context): String? = ReceiverService.localIpv4()?.let { "http://$it:$PORT/#t=${token(context)}" }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(context) else stop()
    }

    @Synchronized
    fun start(context: Context) {
        if (engine != null) return
        val app = context.applicationContext
        discovery = DiscoveryManager(app).also { it.startDiscovery() }
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            routing {
                get("/") { asset(app, "index.html") }
                get("/assets/{file}") { asset(app, "assets/" + (call.parameters["file"] ?: "")) }
                get("/api/state") { if (authorized(app)) call.respondText(state(app), ContentType.Application.Json) }
                get("/api/artwork") {
                    if (!authorized(app)) return@get
                    val bytes = service?.artwork?.value ?: ReceiverHub.get(app).artwork.value
                    if (bytes != null) call.respondBytes(bytes, ContentType.Image.JPEG) else call.respondText("", status = HttpStatusCode.NotFound)
                }
                post("/api/{action}") {
                    if (!authorized(app)) return@post
                    val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                    val s = service
                    val ok = withContext(Dispatchers.Main) {
                        when (call.parameters["action"]) {
                            "stop" -> { app.startService(android.content.Intent(app, AudioCastService::class.java).setAction(AudioCastService.ACTION_STOP)); true }
                            "volume" -> body?.get("percent")?.jsonPrimitive?.intOrNull?.let { s?.setVolume(it); s != null } ?: false
                            "media" -> body?.get("command")?.jsonPrimitive?.contentOrNull?.let { c ->
                                val cmd = when (c) { "play" -> MediaCommand.PLAY; "pause" -> MediaCommand.PAUSE; "next" -> MediaCommand.NEXT; "previous" -> MediaCommand.PREVIOUS; else -> MediaCommand.TOGGLE }
                                s?.sendMediaCommand(cmd); s != null
                            } ?: false
                            "receiver" -> body?.get("on")?.jsonPrimitive?.booleanOrNull?.let { on ->
                                if (on) ReceiverHub.get(app).start() else ReceiverHub.get(app).stop(); true
                            } ?: false
                            "speaker-add", "speaker-remove" -> {
                                val host = body?.get("host")?.jsonPrimitive?.contentOrNull
                                val platform = body?.get("platform")?.jsonPrimitive?.contentOrNull
                                val server = discovery?.servers?.value?.firstOrNull { it.host == host && it.platform == platform }
                                val active = s?.activeDestinations?.value?.firstOrNull { it.host == host && it.platform == platform }
                                when {
                                    s == null || s.state.value != CastState.CASTING -> false
                                    call.parameters["action"] == "speaker-add" && server != null -> {
                                        s.addDestination(CastDestination(server.name, server.host, server.port, server.platform, extra = server.extra)); true
                                    }
                                    call.parameters["action"] == "speaker-remove" && active != null -> { s.removeDestination(active); true }
                                    else -> false
                                }
                            }
                            else -> false
                        }
                    }
                    call.respondText("""{"ok":$ok}""", ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.Conflict)
                }
            }
        }.startOrNull("WebRemote", "Web remote on port $PORT")
        if (engine == null) {
            discovery?.stopDiscovery()
            discovery = null
        }
    }

    @Synchronized
    fun stop() {
        engine?.stop(200, 500)
        engine = null
        discovery?.stopDiscovery()
        discovery = null
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, ApplicationCall>.authorized(app: Context): Boolean {
        val given = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
        // Constant-time comparison: the token is the only thing between the LAN and the remote.
        val ok = MessageDigest.isEqual(given.toByteArray(), token(app).toByteArray())
        if (!ok) call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return ok
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, ApplicationCall>.asset(app: Context, path: String) {
        if (path.contains("..")) return call.respondText("", status = HttpStatusCode.NotFound)
        val bytes = runCatching { app.assets.open("web/$path").use { it.readBytes() } }.getOrNull()
            ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val type = when (path.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html; "js" -> ContentType.Application.JavaScript; "css" -> ContentType.Text.CSS
            "svg" -> ContentType.Image.SVG; else -> ContentType.Application.OctetStream
        }
        call.respondBytes(bytes, type)
    }

    private fun state(app: Context): String {
        val s = service
        val receiver = ReceiverHub.get(app)
        val active = s?.activeDestinations?.value ?: emptyList()
        val meta = s?.metadata?.value
        return buildJsonObject {
            put("state", (s?.state?.value ?: CastState.OFF).name)
            put("volume", s?.volumePercent?.value ?: 0)
            putJsonObject("track") {
                put("title", meta?.title); put("artist", meta?.artist); put("album", meta?.album); put("playing", meta?.isPlaying ?: false)
                put("hasArtwork", s?.artwork?.value != null)
            }
            putJsonArray("active") { active.forEach { d -> add(buildJsonObject { put("name", d.name); put("host", d.host); put("platform", d.platform) }) } }
            putJsonArray("speakers") {
                discovery?.servers?.value?.filter { srv -> active.none { it.host == srv.host && it.platform == srv.platform } }?.forEach { srv ->
                    add(buildJsonObject { put("name", srv.name); put("host", srv.host); put("platform", srv.platform) })
                }
            }
            putJsonObject("receiver") {
                put("running", receiver.running.value)
                put("name", receiver.name)
                put("sender", receiver.sender.value)
                put("title", receiver.metadata.value.title)
                put("artist", receiver.metadata.value.artist)
            }
        }.toString()
    }
}
