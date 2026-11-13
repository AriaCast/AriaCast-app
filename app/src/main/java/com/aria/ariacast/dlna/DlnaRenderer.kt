package com.aria.ariacast.dlna

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aria.ariacast.DeviceIdentity
import com.aria.ariacast.receiver.MetadataUpdate
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.receiver.ReceiverService
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID

/**
 * A UPnP AV MediaRenderer, so any DLNA controller (and AriaCast's own video casting) can
 * play audio or video on this device. SSDP for discovery, SOAP control for AVTransport,
 * RenderingControl and ConnectionManager, playback through Media3 ExoPlayer.
 * Eventing (GENA) is acknowledged but not sent; controllers fall back to polling.
 */
class DlnaRenderer private constructor(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null
    private var ssdp: MulticastSocket? = null
    @Volatile private var name = "AriaCast"

    val udn: String by lazy { "uuid:" + UUID.nameUUIDFromBytes(("aria-dlna-" + DeviceIdentity.id(app)).toByteArray()) }

    private var _player: ExoPlayer? = null
    /** The ExoPlayer the Receive screen attaches a video surface to (main thread only). */
    val player: ExoPlayer? get() = _player

    private val _isVideo = MutableStateFlow(false)
    val isVideo: StateFlow<Boolean> = _isVideo.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var currentUri: String = ""
    private var currentMeta: String = ""
    private var transportState = "NO_MEDIA_PRESENT"

    fun start(friendlyName: String) {
        if (engine != null) return
        name = friendlyName
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        main.post {
            _player = ExoPlayer.Builder(app).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { updateTransportState() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { updateTransportState() }
                })
            }
        }
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            routing {
                get("/description.xml") { call.respondText(Dlna.deviceDescription(name, udn), ContentType.Text.Xml) }
                get("/AVTransport.xml") { call.respondText(Dlna.SCPD_AVTRANSPORT, ContentType.Text.Xml) }
                get("/RenderingControl.xml") { call.respondText(Dlna.SCPD_RENDERING, ContentType.Text.Xml) }
                get("/ConnectionManager.xml") { call.respondText(Dlna.SCPD_CONNECTION, ContentType.Text.Xml) }
                post("/{service}/control") {
                    val service = call.parameters["service"] ?: ""
                    val action = call.request.header("SOAPACTION")?.trim('"')?.substringAfter('#') ?: ""
                    val body = call.receiveText()
                    val result = runCatching { withContext(Dispatchers.Main) { handle(service, action, body) } }
                    result.onSuccess { call.respondText(Dlna.soapResponse(service, action, it), ContentType.parse("text/xml; charset=\"utf-8\"")) }
                        .onFailure { call.respondText(Dlna.soapFault(401, "Invalid Action"), ContentType.Text.Xml, HttpStatusCode.InternalServerError) }
                }
                // GENA: accept subscriptions so strict controllers proceed; no events are sent.
                route("/{service}/event") {
                    method(HttpMethod("SUBSCRIBE")) { handle {
                        call.response.header("SID", "uuid:" + UUID.randomUUID())
                        call.response.header("TIMEOUT", "Second-1800")
                        call.respondText("", status = HttpStatusCode.OK)
                    } }
                    method(HttpMethod("UNSUBSCRIBE")) { handle { call.respondText("", status = HttpStatusCode.OK) } }
                }
            }
        }.start(wait = false)
        startSsdp()
        _running.value = true
    }

    fun stop() {
        sendNotify(alive = false)
        try { ssdp?.close() } catch (_: Exception) {}
        ssdp = null
        engine?.stop(200, 500)
        engine = null
        scope.cancel()
        main.post { _player?.release(); _player = null; _isVideo.value = false }
        ReceiverHub.get(app).onExternalSenderGone(SENDER)
        _running.value = false
    }

    // ---------------------------------------------------------------- SOAP actions (main thread)

    private fun handle(service: String, action: String, body: String): Map<String, String> {
        val p = _player ?: error("player not ready")
        return when (service to action) {
            "AVTransport" to "SetAVTransportURI" -> {
                currentUri = Dlna.xmlUnescape(Dlna.arg(body, "CurrentURI") ?: error("no URI"))
                currentMeta = Dlna.xmlUnescape(Dlna.arg(body, "CurrentURIMetaData") ?: "")
                val didl = Dlna.parseDidl(currentMeta)
                _isVideo.value = didl.isVideo || Dlna.looksLikeVideo(currentUri)
                p.setMediaItem(MediaItem.Builder().setUri(currentUri).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(didl.title).setArtist(didl.artist).build()).build())
                p.prepare()
                val hub = ReceiverHub.get(app)
                hub.onExternalSender(SENDER)
                hub.onMetadata(MetadataUpdate(didl.title, didl.artist, didl.album, didl.artUrl, null, 0, false))
                transportState = "STOPPED"
                emptyMap()
            }
            "AVTransport" to "Play" -> { p.play(); emptyMap() }
            "AVTransport" to "Pause" -> { p.pause(); emptyMap() }
            "AVTransport" to "Stop" -> {
                p.stop()
                transportState = "STOPPED"
                ReceiverHub.get(app).onExternalSenderGone(SENDER)
                _isVideo.value = false
                emptyMap()
            }
            "AVTransport" to "Seek" -> {
                Dlna.parseHms(Dlna.arg(body, "Target") ?: "")?.let { p.seekTo((it * 1000).toLong()) }
                emptyMap()
            }
            "AVTransport" to "GetTransportInfo" -> mapOf("CurrentTransportState" to transportState, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "AVTransport" to "GetPositionInfo" -> {
                val dur = if (p.duration > 0) Dlna.hms(p.duration / 1000.0) else "0:00:00"
                val pos = Dlna.hms(p.currentPosition / 1000.0)
                mapOf("Track" to "1", "TrackDuration" to dur, "TrackMetaData" to Dlna.xmlEscape(currentMeta), "TrackURI" to Dlna.xmlEscape(currentUri),
                    "RelTime" to pos, "AbsTime" to pos, "RelCount" to "2147483647", "AbsCount" to "2147483647")
            }
            "AVTransport" to "GetMediaInfo" -> mapOf("NrTracks" to if (currentUri.isEmpty()) "0" else "1",
                "MediaDuration" to if (p.duration > 0) Dlna.hms(p.duration / 1000.0) else "0:00:00",
                "CurrentURI" to Dlna.xmlEscape(currentUri), "CurrentURIMetaData" to Dlna.xmlEscape(currentMeta),
                "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "AVTransport" to "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "AVTransport" to "GetCurrentTransportActions" -> mapOf("Actions" to "Play,Pause,Stop,Seek")
            "RenderingControl" to "GetVolume" -> mapOf("CurrentVolume" to (p.volume * 100).toInt().toString())
            "RenderingControl" to "SetVolume" -> { Dlna.arg(body, "DesiredVolume")?.toIntOrNull()?.let { p.volume = it.coerceIn(0, 100) / 100f }; emptyMap() }
            "RenderingControl" to "GetMute" -> mapOf("CurrentMute" to if (p.volume == 0f) "1" else "0")
            "RenderingControl" to "SetMute" -> { if (Dlna.arg(body, "DesiredMute") in setOf("1", "true")) p.volume = 0f; emptyMap() }
            "ConnectionManager" to "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to Dlna.SINK_PROTOCOLS)
            "ConnectionManager" to "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "ConnectionManager" to "GetCurrentConnectionInfo" -> mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
            else -> error("unsupported $service#$action")
        }
    }

    private fun updateTransportState() {
        val p = _player ?: return
        transportState = when {
            p.isPlaying -> "PLAYING"
            p.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
            p.playbackState == Player.STATE_ENDED -> "STOPPED"
            p.playbackState == Player.STATE_READY -> "PAUSED_PLAYBACK"
            else -> if (currentUri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
        }
        ReceiverHub.get(app).onMetadata(MetadataUpdate(isPlaying = p.isPlaying, positionMs = p.currentPosition, durationMs = p.duration.takeIf { it > 0 }))
        if (p.playbackState == Player.STATE_ENDED) ReceiverHub.get(app).onExternalSenderGone(SENDER)
    }

    // ---------------------------------------------------------------- SSDP

    private fun location(): String? = ReceiverService.localIpv4()?.let { "http://$it:$PORT/description.xml" }

    private fun startSsdp() {
        scope.launch {
            try {
                val group = InetAddress.getByName(SSDP_ADDR)
                val sock = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(SSDP_PORT)) }
                val nif = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { n ->
                    n.isUp && !n.isLoopback && n.supportsMulticast() && n.inetAddresses.toList().any { it.address.size == 4 }
                }
                if (nif != null) sock.joinGroup(InetSocketAddress(group, SSDP_PORT), nif) else @Suppress("DEPRECATION") sock.joinGroup(group)
                ssdp = sock
                launch { while (isActive) { sendNotify(alive = true); delay(30_000) } }
                val buf = ByteArray(2048)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: Exception) { if (!isActive) break else continue }
                    val msg = String(pkt.data, 0, pkt.length)
                    if (!msg.startsWith("M-SEARCH")) continue
                    val st = msg.lineSequence().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: continue
                    val loc = location() ?: continue
                    Dlna.searchTargets(udn).filter { st == "ssdp:all" || st == it.first }.forEach { (target, usn) ->
                        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $loc\r\nSERVER: Android/1 UPnP/1.0 AriaCast/2.0\r\nST: $target\r\nUSN: $usn\r\n\r\n"
                        runCatching { sock.send(DatagramPacket(reply.toByteArray(), reply.length, pkt.address, pkt.port)) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSDP stopped: ${e.message}")
            }
        }
    }

    private fun sendNotify(alive: Boolean) {
        val sock = ssdp ?: return
        val loc = location() ?: return
        val group = InetAddress.getByName(SSDP_ADDR)
        Dlna.searchTargets(udn).forEach { (nt, usn) ->
            val msg = if (alive)
                "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $loc\r\nNT: $nt\r\nNTS: ssdp:alive\r\nSERVER: Android/1 UPnP/1.0 AriaCast/2.0\r\nUSN: $usn\r\n\r\n"
            else "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nNT: $nt\r\nNTS: ssdp:byebye\r\nUSN: $usn\r\n\r\n"
            runCatching { sock.send(DatagramPacket(msg.toByteArray(), msg.length, group, SSDP_PORT)) }
        }
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        const val PORT = 49494
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        const val SENDER = "DLNA"
        const val KEY_ENABLED = "dlna_renderer_enabled"

        @Volatile private var instance: DlnaRenderer? = null
        fun get(context: Context): DlnaRenderer =
            instance ?: synchronized(this) { instance ?: DlnaRenderer(context.applicationContext).also { instance = it } }
    }
}
