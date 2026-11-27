package com.aria.ariacast.quirks

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerIssueTest {
    private val wiim = DeviceIdentity("DLNA", "Den", "WiiM Pro Plus", "Linkplay")

    /** The query of [url] as a map, decoded the way GitHub decodes it. */
    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test fun pointsAtTheFormWithItsFieldIds() {
        val url = SpeakerIssue.url(wiim, "Speaker: x", "2.0.0")
        assertTrue(url.startsWith("https://github.com/AriaCast/AriaCast-app/issues/new?"))
        val q = query(url)
        assertEquals("speaker-report.yml", q["template"])
        assertEquals("Linkplay WiiM Pro Plus", q["speaker"])
        assertEquals("Speaker: Linkplay WiiM Pro Plus", q["title"])
        assertEquals("DLNA", q["protocol"])
        assertEquals("Speaker: x", q["fingerprint"])
        assertEquals("2.0.0", q["version"])
    }

    @Test fun theVerdictIsOnlySetWhenKnown() {
        assertNull(query(SpeakerIssue.url(wiim, "d", null))["verdict"])
        assertEquals("Does not work", query(SpeakerIssue.url(wiim, "d", null, SpeakerIssue.VERDICT_FAILS))["verdict"])
    }

    @Test fun protocolsUseTheFormsWords() {
        assertEquals("AirPlay 2", SpeakerIssue.protocolLabel("AirPlay2"))
        assertEquals("AirPlay 1", SpeakerIssue.protocolLabel("AirPlay"))
        assertEquals("No local protocol", SpeakerIssue.protocolLabel(null))
        assertEquals("Other", SpeakerIssue.protocolLabel("SomethingNew"))
    }

    @Test fun everyProtocolTheAppUsesIsAnOptionInTheForm() {
        val form = java.io.File("../.github/ISSUE_TEMPLATE/speaker-report.yml").readText()
        listOf("AirPlay2", "AirPlay", "DLNA", "Google Cast", "Snapcast", "Sendspin", "AriaCast", "VBAN", null, "x").forEach {
            assertTrue("${SpeakerIssue.protocolLabel(it)} is not an option in the form", form.contains("- ${SpeakerIssue.protocolLabel(it)}\n"))
        }
        listOf(SpeakerIssue.VERDICT_WORKS, SpeakerIssue.VERDICT_CATCH, SpeakerIssue.VERDICT_FAILS).forEach {
            assertTrue("$it is not an option in the form", form.contains("- $it\n"))
        }
    }

    @Test fun textThatLooksLikeUrlSyntaxIsEncoded() {
        val nasty = DeviceIdentity("DLNA", "a&b=c#d")
        val q = query(SpeakerIssue.url(nasty, "line one\nline two & more = # ?", null))
        assertEquals("a&b=c#d", q["speaker"])
        assertEquals("line one\nline two & more = # ?", q["fingerprint"])
        assertFalse(SpeakerIssue.url(nasty, "x", null).contains(" "))
    }

    @Test fun aLongDetailIsCutSoTheAddressStillWorks() {
        val url = SpeakerIssue.url(wiim, "y".repeat(50_000), "2.0.0")
        assertTrue(url.length <= SpeakerIssue.MAX_URL)
        assertEquals("Linkplay WiiM Pro Plus", query(url)["speaker"])
        assertTrue(query(url)["fingerprint"]!!.isNotEmpty())
    }

    @Test fun aSpeakerThatNamesNothingIsCalledByItsName() {
        assertEquals("Kitchen", SpeakerIssue.speakerName(DeviceIdentity("AirPlay2", "Kitchen")))
    }
}
