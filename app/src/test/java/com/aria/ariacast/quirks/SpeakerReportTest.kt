package com.aria.ariacast.quirks

import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLog
import com.aria.ariacast.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeakerReportTest {
    @Before fun loadTheList() { TestSpeakers.load() }

    private fun line(message: String, device: String? = null, count: Int = 1, time: String = "10:00:00.000") =
        PacketLog(time, PacketDirection.OUT, PacketType.HANDSHAKE, message, 0, count, device)

    // PacketLogger keeps the newest line first.
    private fun logs(vararg oldestFirst: PacketLog) = oldestFirst.toList().asReversed()

    private val context = SpeakerReport.Context(app = "AriaCast 2.0.0", android = "14 (API 34)", device = "Google Pixel 8")
    private val wiim = DeviceIdentity("DLNA", "Den", "WiiM Pro Plus", "Linkplay")

    @Test fun addressesAreShortenedButVersionsAreNot() {
        assertEquals("http://192.168.x.x:8092/s and 10.0.x.x", SpeakerReport.maskAddresses("http://192.168.1.20:8092/s and 10.0.3.4"))
        assertEquals("version 1.1.9 and 2.0.0", SpeakerReport.maskAddresses("version 1.1.9 and 2.0.0"))
    }

    @Test fun keepsTaggedLinesAndLinesThatNameTheSpeakerOldestFirst() {
        val result = SpeakerReport.logFor(
            logs(
                line("first, tagged", device = "10.0.0.5", time = "1"),
                line("unrelated", device = "10.0.0.9", time = "2"),
                line("[AirPlay2Client] 10.0.0.5 info: model=X", time = "3"),
                line("Added Den to the cast", time = "4"),
                line("no speaker here", time = "5"),
            ),
            host = "10.0.0.5", name = "Den",
        )
        assertEquals(listOf("first, tagged", "[AirPlay2Client] 10.0.0.5 info: model=X", "Added Den to the cast"), result.map { it.message })
    }

    @Test fun anEmptyNameDoesNotMatchEverything() {
        val result = SpeakerReport.logFor(logs(line("anything")), host = "10.0.0.5", name = "")
        assertTrue(result.isEmpty())
    }

    @Test fun keepsOnlyTheNewestLines() {
        val many = (1..SpeakerReport.MAX_LOG_LINES + 50).map { line("n$it", device = "h", time = "$it") }
        val result = SpeakerReport.logFor(many.asReversed(), host = "h", name = "")
        assertEquals(SpeakerReport.MAX_LOG_LINES, result.size)
        assertEquals("n${SpeakerReport.MAX_LOG_LINES + 50}", result.last().message)
        assertEquals("n51", result.first().message)
    }

    @Test fun reportListsEverythingAndHidesAddresses() {
        val text = SpeakerReport.build(
            SpeakerReport.Context("AriaCast 2.0.0", "14 (API 34)", "Google Pixel 8", castState = "ERROR, last error: boom", settings = "delay=350 ms"),
            wiim, "192.168.1.20", DeviceQuirks.matching(wiim),
            logs(line("Audio socket connected to Den (192.168.1.20:12889)", device = "192.168.1.20", count = 3, time = "10:00:01.250")),
        )
        assertTrue(text.contains("App: AriaCast 2.0.0"))
        assertTrue(text.contains("Phone: Google Pixel 8, Android 14 (API 34)"))
        assertTrue(text.contains("Speaker: protocol=DLNA, manufacturer=Linkplay, model=WiiM Pro Plus, name=Den"))
        assertTrue(text.contains("Known: WiiM (tested on Pro Plus), DLNA: Works"))
        assertTrue(text.contains("Cast: ERROR, last error: boom"))
        assertTrue(text.contains("Settings: delay=350 ms"))
        assertTrue(text.contains("10:00:01.250 OUT HANDSHAKE x3 Audio socket connected to Den (192.168.x.x:12889)"))
        assertFalse(text.contains("192.168.1.20"))
    }

    @Test fun aSpeakerNeverCastToStillGetsAReport() {
        val text = SpeakerReport.build(context, DeviceIdentity("AirPlay2", "Bedroom", "Strange1,1"), "10.0.0.7", emptyList(), emptyList())
        assertTrue(text.contains("Known: nothing recorded for this speaker yet"))
        assertTrue(text.contains("No log lines for this speaker"))
    }

    @Test fun theSummaryIsTheReportWithoutTheLog() {
        val summary = SpeakerReport.summary(
            SpeakerReport.Context("AriaCast 2.0.0", "14 (API 34)", "Google Pixel 8", castState = "CASTING"),
            wiim, DeviceQuirks.matching(wiim),
        )
        assertTrue(summary.startsWith("App: AriaCast 2.0.0"))
        assertTrue(summary.contains("Speaker: protocol=DLNA, manufacturer=Linkplay, model=WiiM Pro Plus, name=Den"))
        assertTrue(summary.contains("Cast: CASTING"))
        assertFalse(summary.contains("Log"))
    }
}
