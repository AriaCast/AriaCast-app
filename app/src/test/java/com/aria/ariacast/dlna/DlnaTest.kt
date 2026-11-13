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
}
