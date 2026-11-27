package com.aria.ariacast.scan

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aria.ariacast.quirks.SpeakerIssue

class ScanParsingTest {
    private fun description(name: String = "Echo Show 5", extra: String = "", services: List<String> = emptyList()) =
        """<?xml version="1.0"?><root><device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
<friendlyName>$name</friendlyName><manufacturer>Amazon</manufacturer><modelName>Echo Show 5</modelName>$extra
<serviceList>${services.joinToString("") { "<service><serviceType>$it</serviceType></service>" }}</serviceList></device></root>"""

    @Test fun anSsdpAnswerIsReadWhateverTheCaseOfItsHeaders() {
        val reply = ScanParsing.ssdpReply("HTTP/1.1 200 OK\r\nCache-Control: max-age=1800\r\nlocation: http://192.168.1.9:8008/desc.xml\r\nSERVER: Linux/3.14 UPnP/1.0\r\nST: ssdp:all\r\n\r\n")!!
        assertEquals("http://192.168.1.9:8008/desc.xml", reply.location)
        assertEquals("Linux/3.14 UPnP/1.0", reply.server)
    }

    @Test fun anAnswerThatDoesNotSayWhereTheDeviceIsIsIgnored() {
        assertNull(ScanParsing.ssdpReply("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n"))
        assertNull(ScanParsing.ssdpReply(""))
        assertNull(ScanParsing.ssdpReply("LOCATION:\r\n"))
    }

    @Test fun aDescriptionGivesTheDevice() {
        val d = ScanParsing.description(description(services = listOf("urn:schemas-upnp-org:service:RenderingControl:1")), "192.168.1.9", "Linux")!!
        assertEquals("Echo Show 5", d.friendlyName)
        assertEquals("Amazon", d.manufacturer)
        assertEquals("Echo Show 5", d.modelName)
        assertEquals(listOf("urn:schemas-upnp-org:service:RenderingControl:1"), d.services)
        assertEquals("192.168.1.9", d.host)
    }

    @Test fun aDescriptionWithoutANameOrTooBigIsNotADevice() {
        assertNull(ScanParsing.description("<root><device></device></root>", "h", null))
        assertNull(ScanParsing.description(description(extra = "x".repeat(ScanParsing.MAX_DESCRIPTION_BYTES)), "h", null))
    }

    @Test fun textFromTheDeviceIsUnescapedCleanedAndCapped() {
        val d = ScanParsing.description(description(name = "Kitchen &amp; Den\u0007" + "x".repeat(300)), "h", null)!!
        assertTrue(d.friendlyName.startsWith("Kitchen & Den"))
        assertTrue(d.friendlyName.none { it < ' ' })
        assertEquals(80, d.friendlyName.length)
    }

    @Test fun aRendererIsSomethingAriaCastAlreadyPlaysTo() {
        val renderer = ScanParsing.description(description(services = listOf("urn:schemas-upnp-org:service:AVTransport:1")), "h", null)!!
        assertTrue(renderer.isRenderer)
        assertTrue(ScanParsing.otherDevices(listOf(renderer), emptyList()).isEmpty())
    }

    @Test fun mdnsServicesAreGroupedByNameAndTheirTypesSorted() {
        val found = ScanParsing.otherDevices(
            emptyList(),
            listOf(MdnsFound("Echo Show 5-3f2", "_spotify-connect._tcp"), MdnsFound("Echo Show 5-3f2", "_amzn-wplay._tcp"), MdnsFound("Echo Show 5-3f2", "_amzn-wplay._tcp"), MdnsFound("Kitchen Hub", "_hap._tcp")),
        )
        assertEquals(listOf("Echo Show 5-3f2", "Kitchen Hub"), found.map { it.name })
        assertEquals(listOf("_amzn-wplay._tcp", "_spotify-connect._tcp"), found.first().mdnsTypes)
    }

    @Test fun aDeviceSeenByBothWaysIsOneDevice() {
        val ssdp = ScanParsing.description(description(name = "Echo Show 5-3f2"), "192.168.1.9", "Linux")!!
        val found = ScanParsing.otherDevices(listOf(ssdp), listOf(MdnsFound("Echo Show 5-3f2", "_spotify-connect._tcp")))
        assertEquals(1, found.size)
        assertEquals("Amazon", found.single().manufacturer)
        assertEquals(listOf("_spotify-connect._tcp"), found.single().mdnsTypes)
        assertEquals("192.168.1.9", found.single().host)
    }

    @Test fun speakersTheAppAlreadyListsAreLeftOut() {
        val found = ScanParsing.otherDevices(
            emptyList(),
            listOf(MdnsFound("Living Room", "_sonos._tcp"), MdnsFound("Echo Show 5-3f2", "_amzn-wplay._tcp")),
            usableNames = listOf("living room", ""),
        )
        assertEquals(listOf("Echo Show 5-3f2"), found.map { it.name })
    }

    @Test fun aNameLessServiceIsIgnored() {
        assertTrue(ScanParsing.otherDevices(emptyList(), listOf(MdnsFound("  ", "_hap._tcp"))).isEmpty())
    }

    @Test fun theFingerprintSaysWhatWasFoundAndShortensAddresses() {
        val d = OtherDevice("Echo Show 5-3f2", "Amazon", "Echo Show 5", listOf("_amzn-wplay._tcp"), "urn:x:device:Basic:1", listOf("urn:x:service:Foo:1"), "Linux", "192.168.1.9")
        val text = ScanParsing.fingerprint(d)
        assertTrue(text.contains("Name: Echo Show 5-3f2"))
        assertTrue(text.contains("mDNS: _amzn-wplay._tcp"))
        assertTrue(text.contains("UPnP services: urn:x:service:Foo:1"))
        assertTrue(text.contains("Address: 192.168.x.x"))
        assertFalse(text.contains("192.168.1.9"))
    }

    @Test fun theListShowsWhatKindOfThingItAnnounced() {
        assertEquals("Spotify Connect, Amazon · Amazon Echo Show 5", ScanParsing.summary(OtherDevice("n", "Amazon", "Echo Show 5", listOf("_spotify-connect._tcp", "_amzn-wplay._tcp"))))
        assertEquals("UPnP", ScanParsing.summary(OtherDevice("n")))
    }

    @Test fun aFoundDeviceBecomesAReportWithNoProtocol() {
        val d = OtherDevice("Echo Show 5-3f2", "Amazon", "Echo Show 5")
        val url = SpeakerIssue.url(ScanParsing.identity(d), ScanParsing.fingerprint(d), "2.0.0", symptoms = "AriaCast does not find it as a speaker.")
        val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Amazon Echo Show 5", q["speaker"])
        assertEquals("No local protocol", q["protocol"])
        assertNull(q["status"])
    }
}
