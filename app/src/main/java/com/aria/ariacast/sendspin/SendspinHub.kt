package com.aria.ariacast.sendspin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.PacketType
import com.aria.ariacast.Server
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.receiver.MetadataUpdate
import com.aria.ariacast.receiver.ReceiverHub
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI shows about Sendspin. */
data class SendspinStatus(
    val running: Boolean = false,
    val serverName: String? = null,
    val paired: Boolean = false,
    val roles: Set<String> = emptySet(),
    val playing: Boolean = false,
    val sourceStreaming: Boolean = false,
    val lastEvent: String? = null,
)

/**
 * This device as a Sendspin client (Music Assistant's protocol): a WebSocket endpoint at
 * `ws://<ip>:8928/sendspin`, advertised as `_sendspin._tcp`, that servers connect to.
 * - player@v1: play what the server sends, in sync with its other speakers.
 * - source@v1: offer this device's audio (whatever AriaCast is casting to "Sendspin") as a
 *   Live Input. Only on paired connections, per the spec.
 * - metadata@v1: show what is playing on the Receive screen.
 */
class SendspinHub private constructor(private val app: Context) {
    val identity = SendspinIdentity(app)
    private val player = SendspinPlayer(app)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null
    private var nsd: NsdManager.RegistrationListener? = null
    @Volatile private var admitted: SendspinSession? = null
    private val sessions = java.util.concurrent.CopyOnWriteArraySet<SendspinSession>()
    private val prefs = app.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(SendspinStatus())
    val status: StateFlow<SendspinStatus> = _status.asStateFlow()

    private val _sourceTarget = MutableStateFlow<Server?>(null)
    /** A pseudo-speaker for the Send card: "<server> input", present while a server offers source@v1. */
    val sourceTarget: StateFlow<Server?> = _sourceTarget.asStateFlow()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    @Synchronized
    fun start(name: String) {
        if (engine != null) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(WebSockets) { maxFrameSize = 2L * 1024 * 1024 }
            routing {
                webSocket(PATH) {
                    // The spec forbids browser-origin connections.
                    if (call.request.headers["Origin"] != null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                        return@webSocket
                    }
                    val out = Channel<Frame>(capacity = 512)
                    val self = arrayOfNulls<SendspinSession>(1)
                    val session = SendspinSession(identity, config(name), object : SendspinSession.Io {
                        override fun sendText(text: String) { out.trySend(Frame.Text(text)) }
                        override fun sendBinary(bytes: ByteArray) { out.trySend(Frame.Binary(true, bytes)) }
                        override fun close() { out.close() }
                    }, listenerFor { self[0] })
                    self[0] = session
                    sessions += session
                    val writer = launch { for (f in out) send(f) ; close() }
                    val ticker = launch { while (isActive) { delay(200); session.tick() } }
                    session.start()
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> session.onText(frame.readText())
                                is Frame.Binary -> session.onBinary(frame.data)
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Sendspin connection error: ${e.message}")
                    } finally {
                        session.close("connection closed")
                        ticker.cancel()
                        writer.cancel()
                        sessions -= session
                    }
                }
            }
        }.start(wait = false)
        register(name)
        _status.value = SendspinStatus(running = true)
        // Keep Sendspin playback on the phone speaker while AriaCompanion is the default output.
        scope.launch { CompanionHub.get(app).speakerActive.collect { player.pinToBuiltInSpeaker(it) } }
        log("Sendspin client listening on port $PORT")
    }

    @Synchronized
    fun stop() {
        sessions.forEach { it.close("stopped") }
        sessions.clear()
        player.stop()
        nsd?.let { runCatching { app.getSystemService(NsdManager::class.java).unregisterService(it) } }
        nsd = null
        engine?.stop(200, 500)
        engine = null
        scope.cancel()
        admitted = null
        _sourceTarget.value = null
        _status.value = SendspinStatus()
    }

    private fun config(name: String) = SendspinSession.Config(
        name = name,
        productName = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        softwareVersion = "AriaCast " + runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
    )

    private fun register(name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_sendspin._tcp"
            port = PORT
            setAttribute("path", PATH)
            setAttribute("name", name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {}
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { log("Sendspin mDNS registration failed ($code)") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        runCatching { app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = l }
    }

    private fun rank(acts: Set<String>) = when { "playback" in acts -> 2; "pairing" in acts -> 1; else -> 0 }

    private fun listenerFor(self: () -> SendspinSession?) = object : SendspinSession.Listener {
        override fun admit(session: SendspinSession, activities: Set<String>): Boolean {
            val current = admitted
            if (current == null || current === session || current !in sessions) { admitted = session; return true }
            // Multiple servers: the higher-ranked activity wins; ties go to the newcomer.
            return if (rank(activities) >= rank(current.activities)) {
                current.close("another_server")
                admitted = session
                true
            } else false
        }

        override fun onActivated(session: SendspinSession) {
            publish(session)
            // Offered whenever the connection is allowed to use the source role, not only while
            // the server is already playing: the server starts the input only after this device
            // reports a signal, and the signal comes from casting to this very target.
            _sourceTarget.value = if ("source@v1" in session.activeRoles)
                Server("${session.serverName.ifBlank { "Sendspin" }} · input", HOST_TOKEN, 0, "1.0", listOf("pcm"), 48000, 2, PLATFORM)
            else null
        }

        override fun onPlayerStart(format: PcmFormat) {
            player.volume = identity.volume; player.muted = identity.muted
            player.start(format)
            ReceiverHub.get(app).onExternalSender("Sendspin")
            update { it.copy(playing = true) }
        }
        override fun onPlayerAudio(localPlayUs: Long, pcm: ByteArray) = player.enqueue(localPlayUs, pcm)
        override fun onPlayerClear(fromLocalUs: Long?) = player.clear(fromLocalUs)
        override fun onPlayerEnd() {
            player.stop()
            ReceiverHub.get(app).onExternalSenderGone("Sendspin")
            update { it.copy(playing = false) }
        }
        override fun onVolume(volume: Int, muted: Boolean) { player.volume = volume; player.muted = muted }
        override fun onSourceStart(): Boolean { update { it.copy(sourceStreaming = true) }; log("Music Assistant started this device's input"); return true }
        override fun onSourceStop() { update { it.copy(sourceStreaming = false) } }
        override fun onMetadata(meta: SendspinMetadata?) {
            if (meta == null) return
            ReceiverHub.get(app).onMetadata(MetadataUpdate(meta.title, meta.artist, meta.album, meta.artworkUrl,
                meta.durationMs, meta.progressMs, meta.speed > 0))
        }
        override fun onPaired(serverName: String) { log("Paired with $serverName"); update { it.copy(paired = true, lastEvent = "paired") } }
        override fun onClosed(reason: String) {
            val me = self()
            if (me != null && admitted === me) {
                admitted = null
                _sourceTarget.value = null
                update { SendspinStatus(running = engine != null, lastEvent = reason) }
            }
            log("Sendspin connection closed: $reason")
        }
    }

    private fun publish(s: SendspinSession) = update {
        it.copy(serverName = s.serverName, paired = s.isPaired, roles = s.activeRoles, sourceStreaming = s.sourceStreaming)
    }

    private inline fun update(f: (SendspinStatus) -> SendspinStatus) { _status.value = f(_status.value) }

    // ---------------------------------------------------------------- source role

    /** Called by AudioCastService while it casts to [sourceTarget]. */
    fun setSourceSignal(present: Boolean) { admitted?.setSourceSignal(present) }

    /** One 20 ms frame of 48 kHz/16-bit stereo from the cast pipeline. */
    fun pushSourceFrame(frame: ByteArray) {
        val s = admitted ?: return
        if (!s.sourceStreaming) return
        s.sendSourceAudio(System.nanoTime() / 1000 - 20_000, frame)
    }

    fun forgetServer(serverId: String) {
        identity.removeRecord(serverId)
        sessions.firstOrNull { it.serverId == serverId }?.close("forgotten")
    }

    private fun log(msg: String) = PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, msg)

    companion object {
        private const val TAG = "SendspinHub"
        const val PORT = 8928
        const val PATH = "/sendspin"
        const val PLATFORM = "Sendspin"
        const val HOST_TOKEN = "sendspin"
        const val KEY_ENABLED = "sendspin_enabled"

        @Volatile private var instance: SendspinHub? = null
        fun get(context: Context): SendspinHub =
            instance ?: synchronized(this) { instance ?: SendspinHub(context.applicationContext).also { instance = it } }
    }
}
