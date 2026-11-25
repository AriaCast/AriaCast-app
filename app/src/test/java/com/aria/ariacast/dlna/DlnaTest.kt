package com.aria.ariacast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {
    private val setUri = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>
<CurrentURI>http://10.0.0.2:8092/media/abc?x=1&amp;y=2</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Holiday &amp;amp; Friends&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI></s:Body></s:Envelope>"""

    @Test fun parsesSetAvTransportUri() {
        val uri = Dlna.xmlUnescape(Dlna.arg(setUri, "CurrentURI")!!)
        assertEquals("http://10.0.0.2:8092/media/abc?x=1&y=2", uri)
        val didl = Dlna.parseDidl(Dlna.xmlUnescape(Dlna.arg(setUri, "CurrentURIMetaData")!!))
        assertEquals("Holiday & Friends", didl.title)
        assertTrue(didl.isVideo)
    }

    @Test fun emptyArgumentIsEmptyNotMissing() {
        assertEquals("", Dlna.arg("<u:X><CurrentURIMetaData/></u:X>", "CurrentURIMetaData"))
        assertEquals(null, Dlna.arg("<u:X></u:X>", "CurrentURIMetaData"))
    }

    @Test fun timeFormatRoundTrips() {
        assertEquals("1:02:03", Dlna.hms(3723.4))
        assertEquals(3723.0, Dlna.parseHms("1:02:03")!!, 0.0)
        assertEquals(null, Dlna.parseHms("12"))
    }

    @Test fun descriptionAdvertisesAllServices() {
        val d = Dlna.deviceDescription("Kitchen <tablet>", "uuid:1")
        assertTrue(d.contains("<friendlyName>Kitchen &lt;tablet&gt;</friendlyName>"))
        listOf("AVTransport", "RenderingControl", "ConnectionManager").forEach { assertTrue(d.contains("<controlURL>/$it/control</controlURL>")) }
        assertEquals(6, Dlna.searchTargets("uuid:1").size)
    }

    @Test fun soapResponseNamesTheAction() {
        val r = Dlna.soapResponse("AVTransport", "GetTransportInfo", mapOf("CurrentTransportState" to "PLAYING"))
        assertTrue(r.contains("<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><CurrentTransportState>PLAYING</CurrentTransportState>"))
    }

    private fun svc(type: String, control: String) =
        "<service><serviceType>$type</serviceType><serviceId>x</serviceId><controlURL>$control</controlURL></service>"

    @Test fun remoteDescriptionFindsEmbeddedRenderer() {
        // Sonos-style: a root ZonePlayer with the renderer embedded under it.
        val xml = """<root><device><deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
<friendlyName>Living Room &amp; Kitchen</friendlyName><UDN>uuid:RINCON_1</UDN><serviceList>${svc("urn:schemas-upnp-org:service:DeviceProperties:1", "/DeviceProperties/Control")}</serviceList>
<deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>Media Server</friendlyName><UDN>uuid:RINCON_1_MS</UDN>
<serviceList>${svc("urn:schemas-upnp-org:service:ContentDirectory:1", "/MediaServer/ContentDirectory/Control")}</serviceList></device>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Renderer</friendlyName><UDN>uuid:RINCON_1_MR</UDN>
<serviceList>${svc("urn:schemas-upnp-org:service:RenderingControl:1", "/MediaRenderer/RenderingControl/Control")}${svc("urn:schemas-upnp-org:service:AVTransport:1", "/MediaRenderer/AVTransport/Control")}</serviceList></device></deviceList></device></root>"""
        val r = Dlna.parseRemoteDescription(xml)!!
        assertEquals("uuid:RINCON_1", r.udn)
        assertEquals("Living Room & Kitchen", r.friendlyName)
        assertEquals("/MediaRenderer/AVTransport/Control", r.avControl)
        assertEquals("/MediaRenderer/RenderingControl/Control", r.rcControl)
    }

    @Test fun remoteDescriptionRejectsDevicesThatCantPlay() {
        val gateway = """<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:2</deviceType><friendlyName>InternetGatewayDeviceV2 - FRITZ!Box 7530</friendlyName>
<UDN>uuid:gw</UDN><serviceList>${svc("urn:schemas-upnp-org:service:Layer3Forwarding:1", "/igd2upnp/control/Layer3Forwarding")}</serviceList></device></root>"""
        val mediaServer = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS</friendlyName><UDN>uuid:nas</UDN>
<serviceList>${svc("urn:schemas-upnp-org:service:ContentDirectory:1", "/cd")}${svc("urn:schemas-upnp-org:service:ConnectionManager:1", "/cm")}</serviceList></device></root>"""
        assertEquals(null, Dlna.parseRemoteDescription(gateway))
        assertEquals(null, Dlna.parseRemoteDescription(mediaServer))
    }

    @Test fun remoteDescriptionReadsManufacturerAndModel() {
        val xml = """<root><device><friendlyName>Den</friendlyName><manufacturer>Linkplay Technology Inc.</manufacturer>
<modelName>WiiM Pro Plus</modelName><serviceList>${svc("urn:schemas-upnp-org:service:AVTransport:1", "/avt")}</serviceList></device></root>"""
        val r = Dlna.parseRemoteDescription(xml)!!
        assertEquals("Linkplay Technology Inc.", r.manufacturer)
        assertEquals("WiiM Pro Plus", r.modelName)
    }

    @Test fun remoteDescriptionAcceptsNewerAvTransportVersions() {
        val xml = """<root><device><friendlyName>TV</friendlyName><serviceList>${svc("urn:schemas-upnp-org:service:AVTransport:3", "http://10.0.0.5:9197/avt")}</serviceList></device></root>"""
        val r = Dlna.parseRemoteDescription(xml)!!
        assertEquals(null, r.udn)
        assertEquals("http://10.0.0.5:9197/avt", r.avControl)
        assertEquals(null, r.rcControl)
    }
}
