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
        assertTrue(url.startsWith("https://github.com/AriaCast/known-speakers/issues/new?"))
        val q = query(url)
        assertEquals("speaker-report.yml", q["template"])
        assertEquals("Linkplay WiiM Pro Plus", q["speaker"])
        assertEquals("Speaker: Linkplay WiiM Pro Plus", q["title"])
        assertEquals("DLNA", q["protocol"])
        assertEquals("Speaker: x", q["details"])
        assertEquals("2.0.0", q["version"])
    }

    @Test fun theStatusIsOnlySetWhenKnown() {
        assertNull(query(SpeakerIssue.url(wiim, "d", null))["status"])
        assertEquals("Unsupported", query(SpeakerIssue.url(wiim, "d", null, SpeakerIssue.STATUS_UNSUPPORTED))["status"])
    }

    @Test fun protocolsUseTheFormsWords() {
        assertEquals("AirPlay 2", SpeakerIssue.protocolLabel("AirPlay2"))
        assertEquals("AirPlay 1", SpeakerIssue.protocolLabel("AirPlay"))
        assertEquals("No local protocol", SpeakerIssue.protocolLabel(null))
        assertEquals("Other", SpeakerIssue.protocolLabel("SomethingNew"))
    }

    @Test fun everyProtocolTheAppUsesIsAnOptionInTheForm() {
        // A copy of the known-speakers repository's form: keep it in step with that file.
        val form = TestSpeakers.text("speaker-report-form.yml")
        listOf("AirPlay2", "AirPlay", "DLNA", "Google Cast", null, "x").forEach {
            assertTrue("${SpeakerIssue.protocolLabel(it)} is not an option in the form", form.contains("- ${SpeakerIssue.protocolLabel(it)}\n"))
        }
        listOf(SpeakerIssue.STATUS_SUPPORTED, SpeakerIssue.STATUS_QUIRKS, SpeakerIssue.STATUS_UNSUPPORTED).forEach {
            assertTrue("$it is not an option in the form", form.contains("- $it\n"))
        }
    }

    @Test fun textThatLooksLikeUrlSyntaxIsEncoded() {
        val nasty = DeviceIdentity("DLNA", "a&b=c#d", "Mod&el=1#")
        val q = query(SpeakerIssue.url(nasty, "line one\nline two & more = # ?", null))
        assertEquals("Mod&el=1#", q["speaker"])
        assertEquals("line one\nline two & more = # ?", q["details"])
        assertFalse(SpeakerIssue.url(nasty, "x", null).contains(" "))
    }

    @Test fun aLongDetailIsCutSoTheAddressStillWorks() {
        val url = SpeakerIssue.url(wiim, "y".repeat(50_000), "2.0.0")
        assertTrue(url.length <= SpeakerIssue.MAX_URL)
        assertEquals("Linkplay WiiM Pro Plus", query(url)["speaker"])
        assertTrue(query(url)["details"]!!.isNotEmpty())
    }

    @Test fun aSpeakerThatSaysNothingAboutItselfIsLeftForTheReporterToName() {
        // "Kitchen" is what its owner called it, not what it is: the form must ask for the model.
        assertEquals("", SpeakerIssue.speakerName(DeviceIdentity("AirPlay2", "Kitchen")))
        val q = query(SpeakerIssue.url(DeviceIdentity("AirPlay2", "Kitchen"), "d", null))
        assertNull(q["speaker"])
        assertNull(q["title"])
        assertEquals("AirPlay 2", q["protocol"])
    }

    @Test fun softwareReceiversAreNotOffered() {
        listOf("AriaCast", "Snapcast", "VBAN", "Sendspin").forEach { assertEquals("Other", SpeakerIssue.protocolLabel(it)) }
    }

    @Test fun whatTheUserWroteGoesInTheSymptomsField() {
        val q = query(SpeakerIssue.url(wiim, "d", null, SpeakerIssue.STATUS_QUIRKS, "  Stops after one track\nneeds a manual play  "))
        assertEquals("Stops after one track\nneeds a manual play", q["symptoms"])
        assertEquals("Supported with quirks", q["status"])
    }

    @Test fun anEmptyOrOversizedNoteIsHandled() {
        assertNull(query(SpeakerIssue.url(wiim, "d", null, null, "   "))["symptoms"])
        assertEquals(SpeakerIssue.MAX_SYMPTOMS, query(SpeakerIssue.url(wiim, "d", null, null, "z".repeat(5000)))["symptoms"]!!.length)
    }

    @Test fun theSymptomsStayWhenTheDetailsAreCut() {
        val url = SpeakerIssue.url(wiim, "y".repeat(50_000), null, null, "It stops")
        assertTrue(url.length <= SpeakerIssue.MAX_URL)
        assertEquals("It stops", query(url)["symptoms"])
    }
}
