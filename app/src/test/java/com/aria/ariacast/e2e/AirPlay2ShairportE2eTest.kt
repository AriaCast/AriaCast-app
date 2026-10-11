package com.aria.ariacast.e2e

import com.aria.ariacast.airplay.AirPlayTimingUnsupportedException
import com.aria.ariacast.airplay.PtpMaster
import com.aria.ariacast.airplay2.AirPlay2Client
import com.aria.ariacast.airplay2.HapCredentials
import com.aria.ariacast.raop.RaopClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * AirPlay 2 against shairport-sync built --with-airplay-2 + nqptp, running in
 * the "ap2e2e" network namespace at 10.200.0.2 (scripts/e2e/start-receivers.sh).
 * The test JVM acts as the PTP grandmaster (it runs as root, so it can bind
 * 319/320) - exercising pairing, the HAP-encrypted control and event channels,
 * ChaCha20-Poly1305 audio and PTP sync end to end.
 */
class AirPlay2ShairportE2eTest {
    private val host = "10.200.0.2"
    private var ptp: PtpMaster? = null

    @Before
    fun setUp() {
        E2e.assumeEnabled()
        Assume.assumeTrue("AirPlay 2 receiver not running", File(E2e.outDir, "ap2.pid").exists())
    }

    @After
    fun tearDown() {
        ptp?.stop()
        ptp = null
        Thread.sleep(500)
    }

    private fun ptpMaster(): PtpMaster {
        val m = PtpMaster.tryCreate()
        Assume.assumeTrue("PTP ports not bindable (run as root)", m != null)
        ptp = m
        return m!!
    }

    private fun assertTones(pcm: ShortArray) {
        val left = Signal.trimSilence(Signal.channel(pcm, 0))
        val right = Signal.trimSilence(Signal.channel(pcm, 1))
        assertTrue("receiver produced audio (${left.size} frames)", left.size > 44100 * 2)
        val l = left.copyOfRange(8820, left.size - 4410)
        val r = right.copyOfRange(8820, right.size - 4410)
        println("left: own=${Signal.power(l, 1000.0)} other=${Signal.power(l, 1500.0)} rms=${Signal.rms(l)}" +
            "  right: own=${Signal.power(r, 1500.0)} other=${Signal.power(r, 1000.0)} rms=${Signal.rms(r)} dropout=${Signal.longestDropoutMs(l)}ms")
        assertTrue("left channel carries 1000 Hz", Signal.power(l, 1000.0) > Signal.power(l, 1500.0) * 1000)
        assertTrue("right channel carries 1500 Hz", Signal.power(r, 1500.0) > Signal.power(r, 1000.0) * 1000)
        // A pure, unshifted tone keeps nearly all its energy in the Goertzel bin
        // (amplitude 0.5 -> 0.0625); any pitch error over seconds of audio collapses it.
        assertTrue("1000 Hz is not pitch-shifted", Signal.power(l, 1000.0) > 0.05)
        assertTrue("1500 Hz is not pitch-shifted", Signal.power(r, 1500.0) > 0.05)
        assertEquals("level", 0.5 / Math.sqrt(2.0), Signal.rms(l), 0.035)
        assertTrue("no dropouts", Signal.longestDropoutMs(l) <= 20)
    }

    @Test
    fun `NTP timing is refused after a working transient pairing and encrypted SETUP`() {
        // shairport-sync only implements PTP for AirPlay 2: getting this specific error
        // (rather than a dropped connection) proves pair-setup, the HAP channel and the
        // SETUP plist all worked.
        val client = AirPlay2Client(host, 7000, E2e.identity(), forceTiming = AirPlay2Client.Timing.NTP)
        try {
            client.connect()
            throw AssertionError("expected NTP to be refused")
        } catch (e: AirPlayTimingUnsupportedException) {
            println("refused as expected: ${e.message}")
        } finally {
            client.close()
        }
        assertEquals("transient", client.pairingMode)
        assertEquals("Shairport Sync", client.info["model"]?.toString()?.let { if (it.contains("Shairport", true)) "Shairport Sync" else it })
    }

    @Test
    fun `PTP stream with transient pairing plays on the receiver with metadata`() {
        val master = ptpMaster()
        val capture = PcmCapture(File(E2e.outDir, "ap2.pcm"))
        MetadataCollector("10.200.0.1", 5603).use { meta ->
            val client = AirPlay2Client(host, 7000, E2e.identity(), ptpMaster = master, forceTiming = AirPlay2Client.Timing.PTP)
            val pcm = try {
                client.connect()
                client.setVolume(0.0)
                client.setMetadata("AP2 Title", "AP2 Artist", "AP2 Album", 200_000)
                Thread.sleep(1500) // let nqptp lock onto our clock
                ToneGenerator(1000.0, 1500.0).stream(client, 5.0)
                Thread.sleep(3000)
                println("stats: ${client.statsSummary()}")
                capture.read()
            } finally {
                client.teardown(); client.close()
            }
            assertTones(pcm)
            assertEquals("AP2 Title", meta.waitFor("minm")?.text)
            assertEquals("AP2 Artist", meta.waitFor("asar")?.text)
        }
    }

    @Test
    fun `PIN pairing stores credentials that pair-verify accepts on the next session`() {
        val master = ptpMaster()
        var saved: HapCredentials? = null
        // shairport-sync's pair-setup code is fixed at 3939
        val first = AirPlay2Client(host, 7000, E2e.identity(), pin = "3939", ptpMaster = master,
            forceTiming = AirPlay2Client.Timing.PTP, onPaired = { saved = it })
        try { first.connect() } finally { first.teardown(); first.close() }
        assertNotNull("credentials saved", saved)
        assertEquals("pin", first.pairingMode)
        Thread.sleep(500)

        val capture = PcmCapture(File(E2e.outDir, "ap2.pcm"))
        val second = AirPlay2Client(host, 7000, E2e.identity(), credentials = HapCredentials.deserialize(saved!!.serialize()),
            ptpMaster = master, forceTiming = AirPlay2Client.Timing.PTP)
        val pcm = try {
            second.connect()
            assertEquals("verify", second.pairingMode)
            second.setVolume(0.0)
            Thread.sleep(1500)
            ToneGenerator(1000.0, 1500.0).stream(second, 4.0)
            Thread.sleep(3000)
            capture.read()
        } finally {
            second.teardown(); second.close()
        }
        assertTones(pcm)
    }

    @Test
    fun `lost AirPlay 2 packets are retransmitted`() {
        val master = ptpMaster()
        val capture = PcmCapture(File(E2e.outDir, "ap2.pcm"))
        val client = AirPlay2Client(host, 7000, E2e.identity(), ptpMaster = master, forceTiming = AirPlay2Client.Timing.PTP)
        val pcm = try {
            client.connect()
            client.setVolume(0.0)
            Thread.sleep(1500)
            client.rtpSender!!.dropFilter = { seq -> seq % 41 == 0 }
            ToneGenerator(1000.0, 1500.0).stream(client, 4.0)
            Thread.sleep(3000)
            println("stats: ${client.statsSummary()}")
            assertTrue("resends served", client.rtpSender!!.packetsResent > 5)
            capture.read()
        } finally {
            client.teardown(); client.close()
        }
        assertTones(pcm)
    }

    @Test
    fun `AirPlay 1 fallback works on the AirPlay 2 receiver's port`() {
        // What the service does when AirPlay 2 timing is refused and PTP isn't available.
        val capture = PcmCapture(File(E2e.outDir, "ap2.pcm"))
        val client = RaopClient(host, 7000, E2e.identity(), mapOf("md" to "0,1,2", "et" to "0,1", "cn" to "0,1"))
        val pcm = try {
            client.connect()
            client.setVolume(0.0)
            ToneGenerator(1000.0, 1500.0).stream(client, 4.0)
            Thread.sleep(3000)
            capture.read()
        } finally {
            client.teardown(); client.close()
        }
        assertTones(pcm)
    }
}
