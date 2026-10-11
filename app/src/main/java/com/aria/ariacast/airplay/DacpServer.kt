package com.aria.ariacast.airplay

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * DACP ("Digital Audio Control Protocol") remote-control endpoint.
 *
 * Every AirPlay request we send carries `DACP-ID` and `Active-Remote`. Receivers
 * with remote-control support (AirPort Express IR remotes, Apple TV, AV
 * receivers, shairport-sync's MPRIS/D-Bus/MQTT interfaces) browse
 * `_dacp._tcp` for the service `iTunes_Ctrl_<DACP-ID>` and send plain HTTP
 * requests such as `GET /ctrl-int/1/playpause` with an `Active-Remote` header
 * that must match ours. The mDNS advertisement is done by the Android service
 * (NsdManager); this class is only the HTTP side.
 */
class DacpServer(
    private val activeRemote: String,
    private val onCommand: (Command) -> Unit
) {
    sealed class Command {
        object Play : Command()
        object Pause : Command()
        object PlayPause : Command()
        object Stop : Command()
        object Next : Command()
        object Previous : Command()
        object VolumeUp : Command()
        object VolumeDown : Command()
        object MuteToggle : Command()
        /** dmcp.device-volume / dmcp.volume: AirPlay dB, or 0-100 percent. */
        data class SetVolume(val value: Double, val isPercent: Boolean) : Command()
        object ShuffleToggle : Command()
        object RepeatAdvance : Command()
    }

    companion object {
        private const val TAG = "DacpServer"
        const val SERVICE_TYPE = "_dacp._tcp"
        fun serviceName(dacpId: String) = "iTunes_Ctrl_$dacpId"
        fun txtRecords(dacpId: String) = mapOf("txtvers" to "1", "Ver" to "131075", "DbId" to dacpId, "OSsi" to "0x1F5")
    }

    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    fun start(port: Int = 0): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        thread(name = "dacp-server", isDaemon = true) {
            while (running) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(name = "dacp-client", isDaemon = true) { serve(client) }
            }
        }
        ProtoLog.d(TAG, "listening on ${s.localPort}")
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val input = s.getInputStream().buffered()
                val out = s.getOutputStream()
                while (true) {
                    val req = RtspCodec.read(input) ?: break
                    val (status, body) = handle(req)
                    val headers = mutableListOf(
                        "Date" to java.util.Date().toString(),
                        "DAAP-Server" to "AriaCast/1.0",
                        "Content-Type" to "application/x-dmap-tagged",
                        "Content-Length" to body.size.toString()
                    )
                    out.write(RtspCodec.encode("HTTP/1.1 $status", headers, body))
                    out.flush()
                    if (req.header("Connection").equals("close", true)) break
                }
            } catch (_: Exception) {}
        }
    }

    internal fun handle(req: RtspMessage): Pair<String, ByteArray> {
        val remote = req.header("Active-Remote")
        if (remote != null && remote != activeRemote) {
            ProtoLog.w(TAG, "rejecting request with foreign Active-Remote $remote")
            return "403 Forbidden" to ByteArray(0)
        }
        val path = req.uri.substringBefore('?')
        val query = req.uri.substringAfter('?', "")
        val command: Command? = when (path) {
            "/ctrl-int/1/play" -> Command.Play
            "/ctrl-int/1/pause" -> Command.Pause
            "/ctrl-int/1/playpause" -> Command.PlayPause
            "/ctrl-int/1/stop" -> Command.Stop
            "/ctrl-int/1/nextitem", "/ctrl-int/1/beginff" -> Command.Next
            "/ctrl-int/1/previtem", "/ctrl-int/1/beginrew" -> Command.Previous
            "/ctrl-int/1/volumeup" -> Command.VolumeUp
            "/ctrl-int/1/volumedown" -> Command.VolumeDown
            "/ctrl-int/1/mutetoggle" -> Command.MuteToggle
            "/ctrl-int/1/shuffle_songs" -> Command.ShuffleToggle
            "/ctrl-int/1/setproperty" -> parseSetProperty(query)
            "/ctrl-int/1/playresume", "/ctrl-int/1/playspec" -> Command.Play
            "/ctrl-int/1/discrete-pause" -> Command.Pause
            "/server-info", "/login", "/ctrl-int", "/ctrl-int/1/getproperty", "/ctrl-int/1/playstatusupdate" -> null
            else -> return "404 Not Found" to ByteArray(0)
        }
        command?.let {
            ProtoLog.d(TAG, "command $path")
            try { onCommand(it) } catch (e: Exception) { ProtoLog.w(TAG, "command handler failed", e) }
        }
        val body = when (path) {
            "/server-info" -> Dmap.Builder().container("msrv", Dmap.Builder()
                .u32("mstt", 200).u32("mpro", 0x00020000).u32("apro", 0x00030000)
                .string("minm", "AriaCast").u8("mslr", 0).u32("mstm", 1800).build()).build()
            "/login" -> Dmap.Builder().container("mlog", Dmap.Builder().u32("mstt", 200).u32("mlid", 1).build()).build()
            else -> ByteArray(0)
        }
        return (if (body.isEmpty()) "204 No Content" else "200 OK") to body
    }

    private fun parseSetProperty(query: String): Command? {
        for (part in query.split('&')) {
            val k = part.substringBefore('=')
            val v = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").toDoubleOrNull() ?: continue
            when (k) {
                "dmcp.device-volume" -> return Command.SetVolume(v, isPercent = false)
                "dmcp.volume" -> return Command.SetVolume(v, isPercent = true)
                "dacp.repeatstate" -> return Command.RepeatAdvance
                "dacp.shufflestate" -> return Command.ShuffleToggle
            }
        }
        return null
    }
}
