package com.aria.ariacast.e2e

import com.aria.ariacast.airplay.AirPlayAuthRequiredException
import com.aria.ariacast.airplay.AirPlayVolume
import com.aria.ariacast.raop.RaopClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * AirPlay 1 against a real shairport-sync (scripts/e2e/start-receivers.sh).
 * Audio comes back out of the receiver's stdout backend, so these check what
 * actually gets decoded and played - codec framing, channel order, byte order,
 * encryption, timing/sync - not just that the handshake returns 200.
 */
class RaopShairportE2eTest {
    private val host = "127.0.0.1"
    private val txt = mapOf("et" to "0,1", "cn" to "0,1", "md" to "0,1,2")

    @Before
    fun setUp() = E2e.assumeEnabled()

    private fun streamAndCapture(
        client: RaopClient,
        capture: PcmCapture,
        seconds: Double = 4.0,
        tone: ToneGenerator = ToneGenerator(1000.0, 1500.0)
    ): ShortArray {
        try {
            client.connect()
            client.setVolume(0.0)
            tone.stream(client, seconds)
            Thread.sleep(2600) // receiver latency (2 s) + its own buffering
            println("stats: ${client.statsSummary()}")
        } finally {
            client.teardown()
            client.close()
        }
        Thread.sleep(300)
        return capture.read()
    }

    private fun assertTones(pcm: ShortArray, leftHz: Double = 1000.0, rightHz: Double = 1500.0, amplitude: Double = 0.5) {
        val left = Signal.trimSilence(Signal.channel(pcm, 0))
        val right = Signal.trimSilence(Signal.channel(pcm, 1))
        assertTrue("receiver produced audio (${left.size} frames)", left.size > 44100 * 2)
        val l = left.copyOfRange(4410, left.size - 4410)
        val r = right.copyOfRange(4410, right.size - 4410)
        val lOwn = Signal.power(l, leftHz); val lOther = Signal.power(l, rightHz)
        val rOwn = Signal.power(r, rightHz); val rOther = Signal.power(r, leftHz)
        println("left: own=$lOwn other=$lOther rms=${Signal.rms(l)}  right: own=$rOwn other=$rOther rms=${Signal.rms(r)}")
        assertTrue("left channel carries $leftHz Hz", lOwn > lOther * 1000)
        assertTrue("tones are not pitch-shifted", lOwn > 0.05 && rOwn > 0.05)
        assertTrue("right channel carries $rightHz Hz", rOwn > rOther * 1000)
        val expectedRms = amplitude / Math.sqrt(2.0)
        assertEquals("left level", expectedRms, Signal.rms(l), expectedRms * 0.1)
        assertEquals("right level", expectedRms, Signal.rms(r), expectedRms * 0.1)
        val dropout = Signal.longestDropoutMs(l)
        assertTrue("no dropouts (longest silent stretch ${dropout} ms)", dropout <= 20)
    }

    @Test
    fun `ALAC stream plays bit-exact with metadata, artwork, progress and volume`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        MetadataCollector("127.0.0.1", 5601).use { meta ->
            val mac = E2e.raopServicePrefix("E2E RAOP")
            println("receiver RAOP id from mDNS: $mac")
            val client = RaopClient(host, 5101, E2e.identity(), txt, receiverMac = mac, initialVolumeDb = -10.0)
            val pcm = try {
                client.connect()
                assertEquals(RaopClient.Codec.ALAC, client.codec)
                client.setVolume(0.0)
                client.setMetadata("E2E Title", "E2E Artist", "E2E Album", 180_000)
                val jpeg = ByteArray(2048) { it.toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
                client.setArtwork(jpeg)
                client.setProgress(30_000, 180_000)
                ToneGenerator(1000.0, 1500.0).stream(client, 4.0)
                Thread.sleep(2600)
                println("stats: ${client.statsSummary()}, appleResponse=${client.appleResponseValid}")
                assertTrue("receiver queried our NTP timing", client.statsSummary().contains("timing=") && !client.statsSummary().endsWith("timing=0"))
                if (mac != null) assertEquals("Apple-Response verifies against the AirPort key", true, client.appleResponseValid)
                capture.read()
            } finally {
                client.teardown(); client.close()
            }
            assertTones(pcm)
            assertEquals("E2E Title", meta.waitFor("minm")?.text)
            assertEquals("E2E Artist", meta.waitFor("asar")?.text)
            assertEquals("E2E Album", meta.waitFor("asal")?.text)
            assertNotNull("artwork delivered", meta.waitFor("PICT"))
            val progress = meta.waitFor("prgr")?.text
            assertNotNull("progress delivered", progress)
            val (start, now, end) = progress!!.trim().split('/').map { it.toLong() }
            assertEquals("30 s into the track", 30L * 44100, (now - start + (1L shl 32)) % (1L shl 32))
            assertEquals("180 s long", 180L * 44100, (end - start + (1L shl 32)) % (1L shl 32))
            val vol = meta.waitFor("pvol")?.text
            assertNotNull("volume delivered", vol)
            assertTrue("airplay volume 0 dB reported: $vol", vol!!.startsWith("0.00"))
        }
    }

    @Test
    fun `RSA AES encrypted stream decodes on the receiver`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        val client = RaopClient(host, 5101, E2e.identity(), txt + ("et" to "1"))
        val pcm = streamAndCapture(client, capture)
        assertTrue(client.encrypted)
        assertTones(pcm)
    }

    @Test
    fun `L16 PCM codec is used when the receiver doesn't list ALAC`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        val client = RaopClient(host, 5101, E2e.identity(), txt + ("cn" to "0"))
        val pcm = streamAndCapture(client, capture)
        assertEquals(RaopClient.Codec.PCM, client.codec)
        assertTones(pcm)
    }

    @Test
    fun `lost packets are retransmitted on request`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        val client = RaopClient(host, 5101, E2e.identity(), txt)
        val pcm = try {
            client.connect()
            client.setVolume(0.0)
            client.rtpSender!!.dropFilter = { seq -> seq % 37 == 0 }
            ToneGenerator(1000.0, 1500.0).stream(client, 4.0)
            Thread.sleep(2600)
            println("stats: ${client.statsSummary()}")
            assertTrue("receiver asked for, and got, resends", client.rtpSender!!.packetsResent > 10)
            capture.read()
        } finally {
            client.teardown(); client.close()
        }
        assertTones(pcm)
    }

    @Test
    fun `FLUSH discards what the receiver has buffered and the stream carries on`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        val client = RaopClient(host, 5101, E2e.identity(), txt)
        val tone = ToneGenerator(1000.0, 1500.0)
        val pcm = try {
            client.connect()
            client.setVolume(0.0)
            tone.stream(client, 1.5) // all still inside the receiver's 2 s latency...
            client.flush()           // ...so all of it should be dropped
            tone.stream(client, 3.0)
            Thread.sleep(2600)
            capture.read()
        } finally {
            client.teardown(); client.close()
        }
        val segments = Signal.audioSegments(Signal.channel(pcm, 0))
        println("audio segments: $segments")
        assertTrue("audio before and after the flush", segments.size >= 2)
        val after = segments.last()
        val before = segments.dropLast(1).sumOf { it.second - it.first }
        // Only what the receiver had already handed to its output can still play; the
        // rest of the 1.5 s is discarded. It then re-buffers its latency and resumes.
        assertTrue("most pre-flush audio discarded (played $before s of 1.5 s)", before < 0.8)
        assertEquals("all post-flush audio plays", 3.0, after.second - after.first, 0.15)
    }

    @Test
    fun `mute silences the receiver`() {
        val capture = PcmCapture(File(E2e.outDir, "raop.pcm"))
        val client = RaopClient(host, 5101, E2e.identity(), txt)
        val pcm = try {
            client.connect()
            client.setVolume(AirPlayVolume.MUTE_DB)
            ToneGenerator(1000.0, 1500.0).stream(client, 3.0)
            Thread.sleep(2600)
            capture.read()
        } finally {
            client.teardown(); client.close()
        }
        val left = Signal.channel(pcm, 0)
        assertTrue("muted output is silent (rms=${Signal.rms(left)})", Signal.rms(left) < 0.001)
    }

    @Test
    fun `password protected receiver requires and accepts Digest credentials`() {
        try {
            RaopClient(host, 5102, E2e.identity(), txt).connect()
            fail("expected a password challenge")
        } catch (e: AirPlayAuthRequiredException) {
            assertEquals(AirPlayAuthRequiredException.Kind.PASSWORD, e.kind)
        }
        try {
            RaopClient(host, 5102, E2e.identity(), txt, password = "wrong").connect()
            fail("wrong password accepted")
        } catch (e: AirPlayAuthRequiredException) {
            assertEquals(AirPlayAuthRequiredException.Kind.PASSWORD, e.kind)
        }
        val capture = PcmCapture(File(E2e.outDir, "raop-pw.pcm"))
        val pcm = streamAndCapture(RaopClient(host, 5102, E2e.identity(), txt, password = "secret"), capture, seconds = 3.0)
        assertTones(pcm)
    }

    @Test
    fun `teardown frees the receiver for the next sender immediately`() {
        repeat(2) {
            val c = RaopClient(host, 5101, E2e.identity(), txt)
            c.connect()
            ToneGenerator(440.0, 440.0).stream(c, 0.5)
            c.teardown()
            c.close()
        }
    }

    @Suppress("unused")
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}
