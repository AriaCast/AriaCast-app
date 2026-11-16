package com.aria.ariacast.dlna

/** UPnP AV MediaRenderer descriptions, SOAP envelopes and parsing helpers (pure, unit-tested). */
object Dlna {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** What discovery needs from a remote device description: who it is and where to control it. */
    data class RemoteRenderer(val udn: String?, val friendlyName: String, val avControl: String, val rcControl: String?)

    /**
     * Parses a UPnP device description fetched from an SSDP LOCATION. Returns null unless the
     * device (or one embedded in it, as on Sonos) offers AVTransport: routers, NAS media
     * servers and repeaters answer SSDP too but can't play anything. [RemoteRenderer.udn] is
     * the root device's UDN, stable across a device's several SSDP replies; control URLs are raw.
     */
    fun parseRemoteDescription(xml: String): RemoteRenderer? {
        fun tag(t: String, src: String) = Regex("<$t>([\\s\\S]*?)</$t>").find(src)?.groupValues?.get(1)?.let(::xmlUnescape)?.trim()
        val name = tag("friendlyName", xml)?.takeIf { it.isNotEmpty() } ?: return null
        val services = Regex("<service>([\\s\\S]*?)</service>").findAll(xml).map { it.groupValues[1] }.toList()
        fun control(kind: String) = services.firstOrNull { tag("serviceType", it)?.contains(":service:$kind:") == true }
            ?.let { tag("controlURL", it) }?.takeIf { it.isNotEmpty() }
        val av = control("AVTransport") ?: return null
        return RemoteRenderer(tag("UDN", xml)?.takeIf { it.isNotEmpty() }, name, av, control("RenderingControl"))
    }

    fun serviceType(service: String) = when (service) {
        "AVTransport" -> AVT
        "RenderingControl" -> RC
        else -> CM
    }

    /** (ST/NT, USN) pairs this device answers and announces. */
    fun searchTargets(udn: String): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "$udn::upnp:rootdevice",
        udn to udn,
        DEVICE_TYPE to "$udn::$DEVICE_TYPE",
        AVT to "$udn::$AVT",
        RC to "$udn::$RC",
        CM to "$udn::$CM",
    )

    fun deviceDescription(name: String, udn: String) = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$DEVICE_TYPE</deviceType>
<friendlyName>${xmlEscape(name)}</friendlyName>
<manufacturer>AriaCast</manufacturer>
<manufacturerURL>https://github.com/AriaCast/AriaCast-app</manufacturerURL>
<modelName>AriaCast Receiver</modelName>
<modelNumber>2.0</modelNumber>
<UDN>$udn</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<serviceList>
${service("AVTransport")}
${service("RenderingControl")}
${service("ConnectionManager")}
</serviceList>
</device>
</root>"""

    private fun service(s: String) = "<service><serviceType>${serviceType(s)}</serviceType><serviceId>urn:upnp-org:serviceId:$s</serviceId>" +
        "<SCPDURL>/$s.xml</SCPDURL><controlURL>/$s/control</controlURL><eventSubURL>/$s/event</eventSubURL></service>"

    fun soapResponse(service: String, action: String, out: Map<String, String>): String {
        val args = out.entries.joinToString("") { (k, v) -> "<$k>$v</$k>" }
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="${serviceType(service)}">$args</u:${action}Response></s:Body></s:Envelope>"""
    }

    fun soapFault(code: Int, description: String) = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

    /** Value of the SOAP argument [name] (namespace prefixes ignored), still XML-escaped. */
    fun arg(body: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?$name>").find(body)?.groupValues?.get(1)
            ?: if (Regex("<(?:\\w+:)?$name\\s*/>").containsMatchIn(body)) "" else null

    fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    data class Didl(val title: String?, val artist: String?, val album: String?, val artUrl: String?, val isVideo: Boolean)

    fun parseDidl(didl: String): Didl {
        fun tag(t: String) = Regex("<$t[^>]*>([\\s\\S]*?)</$t>").find(didl)?.groupValues?.get(1)?.let(::xmlUnescape)?.trim()
        val cls = tag("upnp:class") ?: ""
        return Didl(tag("dc:title"), tag("upnp:artist") ?: tag("dc:creator"), tag("upnp:album"), tag("upnp:albumArtURI"),
            cls.startsWith("object.item.videoItem"))
    }

    fun looksLikeVideo(uri: String): Boolean = uri.substringBefore('?').substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "webm", "m3u8", "mov", "ts", "avi")

    fun hms(seconds: Double): String {
        val t = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    }

    fun parseHms(s: String): Double? {
        val p = s.trim().split(":")
        if (p.size != 3) return null
        return (p[0].toDoubleOrNull() ?: return null) * 3600 + (p[1].toDoubleOrNull() ?: return null) * 60 + (p[2].toDoubleOrNull() ?: return null)
    }

    const val SINK_PROTOCOLS = "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*," +
        "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16:*,http-get:*:audio/ogg:*,http-get:*:audio/opus:*," +
        "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/webm:*,http-get:*:application/x-mpegURL:*,http-get:*:video/mp2t:*"

    private fun scpd(actions: String, vars: String) = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>$actions</actionList><serviceStateTable>$vars</serviceStateTable></scpd>"""

    private fun action(name: String, vararg args: Triple<String, String, String>) =
        "<action><name>$name</name><argumentList>" + args.joinToString("") { (n, dir, v) ->
            "<argument><name>$n</name><direction>$dir</direction><relatedStateVariable>$v</relatedStateVariable></argument>"
        } + "</argumentList></action>"

    private fun v(name: String, type: String = "string", events: Boolean = false) =
        "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType></stateVariable>"

    private fun i(n: String, v: String) = Triple(n, "in", v)
    private fun o(n: String, v: String) = Triple(n, "out", v)
    private val ID = i("InstanceID", "A_ARG_TYPE_InstanceID")

    val SCPD_AVTRANSPORT = scpd(
        action("SetAVTransportURI", ID, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")) +
            action("Play", ID, i("Speed", "TransportPlaySpeed")) + action("Pause", ID) + action("Stop", ID) +
            action("Seek", ID, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")) +
            action("GetTransportInfo", ID, o("CurrentTransportState", "TransportState"), o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed")) +
            action("GetPositionInfo", ID, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"), o("TrackMetaData", "CurrentTrackMetaData"),
                o("TrackURI", "CurrentTrackURI"), o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
                o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition")) +
            action("GetMediaInfo", ID, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"), o("CurrentURI", "AVTransportURI"),
                o("CurrentURIMetaData", "AVTransportURIMetaData"), o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
                o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"), o("WriteStatus", "RecordMediumWriteStatus")) +
            action("GetTransportSettings", ID, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")) +
            action("GetCurrentTransportActions", ID, o("Actions", "CurrentTransportActions")),
        listOf("A_ARG_TYPE_InstanceID" to "ui4", "AVTransportURI" to "string", "AVTransportURIMetaData" to "string", "TransportPlaySpeed" to "string",
            "A_ARG_TYPE_SeekMode" to "string", "A_ARG_TYPE_SeekTarget" to "string", "TransportState" to "string", "TransportStatus" to "string",
            "CurrentTrack" to "ui4", "CurrentTrackDuration" to "string", "CurrentTrackMetaData" to "string", "CurrentTrackURI" to "string",
            "RelativeTimePosition" to "string", "AbsoluteTimePosition" to "string", "RelativeCounterPosition" to "i4", "AbsoluteCounterPosition" to "i4",
            "NumberOfTracks" to "ui4", "CurrentMediaDuration" to "string", "NextAVTransportURI" to "string", "NextAVTransportURIMetaData" to "string",
            "PlaybackStorageMedium" to "string", "RecordStorageMedium" to "string", "RecordMediumWriteStatus" to "string", "CurrentPlayMode" to "string",
            "CurrentRecordQualityMode" to "string", "CurrentTransportActions" to "string").joinToString("") { (n, t) -> v(n, t) } + v("LastChange", events = true),
    )

    val SCPD_RENDERING = scpd(
        action("GetVolume", ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume")) +
            action("SetVolume", ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume")) +
            action("GetMute", ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute")) +
            action("SetMute", ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute")),
        v("A_ARG_TYPE_InstanceID", "ui4") + v("A_ARG_TYPE_Channel") + v("Volume", "ui2") + v("Mute", "boolean") + v("LastChange", events = true),
    )

    val SCPD_CONNECTION = scpd(
        action("GetProtocolInfo", o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")) +
            action("GetCurrentConnectionIDs", o("ConnectionIDs", "CurrentConnectionIDs")) +
            action("GetCurrentConnectionInfo", i("ConnectionID", "A_ARG_TYPE_ConnectionID"), o("RcsID", "A_ARG_TYPE_RcsID"),
                o("AVTransportID", "A_ARG_TYPE_AVTransportID"), o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                o("Direction", "A_ARG_TYPE_Direction"), o("Status", "A_ARG_TYPE_ConnectionStatus")),
        listOf("SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs", "A_ARG_TYPE_ProtocolInfo", "A_ARG_TYPE_ConnectionManager",
            "A_ARG_TYPE_Direction", "A_ARG_TYPE_ConnectionStatus").joinToString("") { v(it) } +
            v("A_ARG_TYPE_ConnectionID", "i4") + v("A_ARG_TYPE_RcsID", "i4") + v("A_ARG_TYPE_AVTransportID", "i4"),
    )
}
