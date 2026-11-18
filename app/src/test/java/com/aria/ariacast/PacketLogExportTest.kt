package com.aria.ariacast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketLogExportTest {
    private val env = PacketLogExport.Environment(
        "2.0.1", "F-Droid", "Android 17 (API 37)", "Google Pixel 8 Pro",
        listOf("AirPlay" to false, "AirPlay 2" to true, "DLNA" to true),
    )

    private fun log(t: String, msg: String, dir: PacketDirection = PacketDirection.IN, count: Int = 1) =
        PacketLog(t, dir, PacketType.HANDSHAKE, msg, count = count)

    // PacketLogger keeps the newest entry first.
    private val newestFirst = listOf(
        log("23:24:36.401", "Wohnzimmer after 30s: packets sent=3748, underruns=0"),
        log("23:24:06.377", "[AirPlay2Client] 192.168.178.53 streaming (AirPlay 2, NTP)"),
        log("23:24:06.300", "[AirPlay2Client] 192.168.178.53 SET_PARAMETER text/dmap failed: Socket closed", count = 3),
        log("23:24:05.000", PacketLogExport.speakerLine("Wohnzimmer", "AirPlay2", "192.168.178.53", 7000,
            "model=Amp;srcvers=366.0;features=0x4A7FDFD5,0xBC157FDE;pk=abc"), PacketDirection.OUT),
        log("23:24:05.000", "${PacketLogExport.SESSION_MARKER} (1 speaker(s))", PacketDirection.OUT),
        log("23:20:00.000", "[RaopClient] 192.168.178.50 connection lost: old session"),
        log("23:19:59.000", "${PacketLogExport.SESSION_MARKER} (1 speaker(s))", PacketDirection.OUT),
    )

    @Test fun lastSessionStartsAtTheLastMarker() {
        val r = PacketLogExport.build(env, newestFirst, PacketLogExport.Scope.LAST_SESSION, "2026-11-18 08:00")
        assertTrue(r.contains("| Log | last cast session, 5 lines"))
        assertFalse("earlier session left out", r.contains("old session"))
        // Oldest first in the full log.
        assertTrue(r.indexOf("Cast session started") < r.indexOf("streaming (AirPlay 2"))
    }

    @Test fun everythingKeepsEarlierSessions() {
        val r = PacketLogExport.build(env, newestFirst, PacketLogExport.Scope.ALL, "now")
        assertTrue(r.contains("old session"))
        assertTrue(r.contains("| Log | everything, 7 lines"))
    }

    @Test fun headerSpeakersAndProblems() {
        val r = PacketLogExport.build(env, newestFirst, PacketLogExport.Scope.LAST_SESSION, "now")
        assertTrue(r.contains("| App | 2.0.1 (F-Droid) |"))
        assertTrue(r.contains("| Protocols on | AirPlay 2, DLNA |"))
        // The whole features value, which the in-app list cuts off; pk isn't a reported key.
        assertTrue(r.contains("- `Wohnzimmer · AirPlay2 · 192.168.178.53:7000 · model=Amp srcvers=366.0 features=0x4A7FDFD5,0xBC157FDE`"))
        assertFalse(r.contains("pk=abc"))
        assertTrue(r.contains("#### Possible problems (1)"))
        assertTrue(r.contains("x3 [AirPlay2Client] 192.168.178.53 SET_PARAMETER text/dmap failed"))
        assertTrue(r.contains("<details><summary>Full log (5 lines, oldest first)</summary>"))
    }

    @Test fun noMarkerFallsBackToEverything() {
        val r = PacketLogExport.build(env, listOf(log("1", "hello")), PacketLogExport.Scope.LAST_SESSION, "now")
        assertTrue(r.contains("everything (no cast session in the log)"))
        assertTrue(r.contains("None spotted in the log."))
    }

    @Test fun codeFencesInMessagesCantBreakTheBlock() {
        val line = PacketLogExport.line(log("1", "a```b"))
        assertFalse(line.contains("```"))
        assertTrue(line.endsWith("a'''b"))
    }
}
