package com.aria.ariacast.video

import com.aria.ariacast.ExtraFields
import com.aria.ariacast.Server
import com.aria.ariacast.cast.CastClient
import java.net.HttpURLConnection
import java.net.URL

/** Something on the network that can play a video URL. */
interface VideoTarget {
    val name: String
    /** Starts playback of [url]; [subtitleUrl] is WebVTT, used where the protocol supports it. */
    fun load(url: String, mime: String, title: String, subtitleUrl: String?)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    /** (position, duration) in seconds, or null if unknown. */
    fun progress(): Pair<Double, Double>?
    fun stop()

    companion object {
        /** Video-capable targets among discovered speakers. */
        fun supports(server: Server) = server.platform in setOf("Google Cast", "DLNA", "AirPlay", "AirPlay2")

        fun forServer(server: Server): VideoTarget = when (server.platform) {
            "Google Cast" -> CastVideoTarget(server)
            "DLNA" -> DlnaVideoTarget(server)
            else -> AirPlayVideoTarget(server)
        }
    }
}

/** Google Cast: the Default Media Receiver plays MP4/WebM/HLS from a URL, with subtitles and seeking. */
class CastVideoTarget(private val server: Server) : VideoTarget {
    override val name = server.name
    private val client = CastClient(server.host, server.port.takeIf { it > 0 } ?: 8009)

    override fun load(url: String, mime: String, title: String, subtitleUrl: String?) {
        client.connect()
        client.launch()
        client.loadFile(url, mime, title, subtitleUrl)
    }
    override fun play() = client.play()
    override fun pause() = client.pause()
    override fun seek(seconds: Double) = client.seek(seconds)
    override fun progress(): Pair<Double, Double>? {
        client.requestMediaStatus()
        return client.currentTimeSeconds to client.durationSeconds
    }
    override fun stop() { client.stop(); client.close() }
}

/** DLNA / UPnP AV: SetAVTransportURI with a video DIDL item, then Play. */
class DlnaVideoTarget(private val server: Server) : VideoTarget {
    override val name = server.name
    private val controlUrl = ExtraFields.get(server.extra, "av_control") ?: error("This DLNA device did not advertise AVTransport")

    override fun load(url: String, mime: String, title: String, subtitleUrl: String?) {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/"><item id="0" parentID="-1" restricted="1"><dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${xml(url)}</res>${subtitleUrl?.let { "<sec:CaptionInfoEx sec:type=\"vtt\">${xml(it)}</sec:CaptionInfoEx>" } ?: ""}</item></DIDL-Lite>"""
        soap("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl)}</CurrentURIMetaData>")
        play()
    }
    override fun play() { soap("Play", "<Speed>1</Speed>") }
    override fun pause() { soap("Pause", "") }
    override fun seek(seconds: Double) { soap("Seek", "<Unit>REL_TIME</Unit><Target>${hms(seconds)}</Target>") }
    override fun progress(): Pair<Double, Double>? {
        val r = soap("GetPositionInfo", "")
        val pos = r.substringAfter("<RelTime>", "").substringBefore("</RelTime>")
        val dur = r.substringAfter("<TrackDuration>", "").substringBefore("</TrackDuration>")
        return parseHms(pos)?.let { p -> p to (parseHms(dur) ?: 0.0) }
    }
    override fun stop() { runCatching { soap("Stop", "") } }

    private fun soap(action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("$action failed: HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    companion object {
        fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun hms(seconds: Double): String {
            val t = seconds.toLong().coerceAtLeast(0)
            return "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
        }
        fun parseHms(s: String): Double? {
            val parts = s.trim().split(":")
            if (parts.size != 3) return null
            val h = parts[0].toDoubleOrNull() ?: return null
            val m = parts[1].toDoubleOrNull() ?: return null
            val sec = parts[2].toDoubleOrNull() ?: return null
            return h * 3600 + m * 60 + sec
        }
    }
}

/**
 * AirPlay video (the HTTP `/play` API). Works with Apple TVs that allow unpaired access and
 * with most third-party AirPlay video receivers; Apple TVs that require pairing for every
 * request refuse it (docs/ROADMAP-2.0.md, "Video").
 */
class AirPlayVideoTarget(private val server: Server) : VideoTarget {
    override val name = server.name
    // Video lives on the AirPlay HTTP port (7000), not the RAOP audio port.
    private val base = "http://${server.host}:${if (server.platform == "AirPlay2" && server.port > 0) server.port else 7000}"

    override fun load(url: String, mime: String, title: String, subtitleUrl: String?) {
        val body = "Content-Location: $url\nStart-Position: 0\n"
        val code = post("/play", body, "text/parameters")
        if (code == 401 || code == 403 || code == 470) error("This AirPlay device requires pairing for video")
        if (code !in 200..299) error("AirPlay /play failed: HTTP $code")
    }
    override fun play() { post("/rate?value=1.000000", "", null) }
    override fun pause() { post("/rate?value=0.000000", "", null) }
    override fun seek(seconds: Double) { post("/scrub?position=%.3f".format(seconds), "", null) }
    override fun progress(): Pair<Double, Double>? = runCatching {
        val c = URL("$base/scrub").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val d = text.substringAfter("duration:", "").substringBefore('\n').trim().toDouble()
        val p = text.substringAfter("position:", "").substringBefore('\n').trim().toDouble()
        p to d
    }.getOrNull()
    override fun stop() { runCatching { post("/stop", "", null) } }

    private fun post(path: String, body: String, type: String?): Int {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "AirPlay/320.20")
            type?.let { c.setRequestProperty("Content-Type", it) }
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.responseCode
        } finally { c.disconnect() }
    }
}
