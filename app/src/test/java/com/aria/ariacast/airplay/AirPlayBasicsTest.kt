package com.aria.ariacast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AirPlayAudioTest {
    /** Decodes an uncompressed ALAC frame back to little-endian PCM (inverse of the encoder). */
    private fun decodeVerbatim(frame: ByteArray): ByteArray {
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((frame[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        assertEquals("ID_CPE", 1, read(3))
        read(4); read(12)
        val partial = read(1)
        assertEquals("bytes shifted", 0, read(2))
        assertEquals("escape (uncompressed) flag", 1, read(1))
        val frames = if (partial == 1) read(32) else AirPlayAudio.FRAMES_PER_PACKET
        val out = ByteArray(frames * 4)
        for (i in 0 until frames * 2) {
            val s = read(16)
            out[i * 2] = s.toByte(); out[i * 2 + 1] = (s ushr 8).toByte()
        }
        assertEquals("ID_END", 7, read(3))
        return out
    }

    @Test
    fun `uncompressed ALAC frame carries the samples verbatim`() {
        val pcm = ByteArray(AirPlayAudio.PACKET_PCM_BYTES) { (it * 31 + 7).toByte() }
        val frame = AirPlayAudio.alacUncompressed(pcm)
        assertEquals(1412, frame.size) // 23 header bits + 352*32 + 3 end bits, rounded up
        assertArrayEquals(pcm, decodeVerbatim(frame))
    }

    @Test
    fun `short ALAC frames announce their length`() {
        val pcm = ByteArray(100 * 4) { it.toByte() }
        assertArrayEquals(pcm, decodeVerbatim(AirPlayAudio.alacUncompressed(pcm)))
    }

    @Test
    fun `L16 is big-endian`() {
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), AirPlayAudio.l16(byteArrayOf(0x34, 0x12, 0x78, 0x56)))
    }

    @Test
    fun `volume maps like iTunes with mute at zero`() {
        assertEquals(AirPlayVolume.MUTE_DB, AirPlayVolume.percentToDb(0), 0.0)
        assertEquals(-30.0 + 0.3, AirPlayVolume.percentToDb(1), 1e-9)
        assertEquals(-15.0, AirPlayVolume.percentToDb(50), 1e-9)
        assertEquals(0.0, AirPlayVolume.percentToDb(100), 1e-9)
        assertEquals(0, AirPlayVolume.dbToPercent(-144.0))
        assertEquals(50, AirPlayVolume.dbToPercent(-15.0))
        assertEquals(100, AirPlayVolume.dbToPercent(0.0))
        assertEquals("-12.500000", AirPlayVolume.format(-12.5))
    }

    @Test
    fun `TXT parsing handles lists, split feature words and hex flags`() {
        assertEquals(setOf(0, 3, 5), AirPlayTxt.intList("0,3,5"))
        assertEquals(emptySet<Int>(), AirPlayTxt.intList(null))
        val f = AirPlayTxt.features("0x5A7FFFF7,0x1E")
        assertEquals(0x1E5A7FFFF7L, f)
        assertTrue(AirPlayTxt.hasFeature(f, AirPlayTxt.FEATURE_AUDIO))
        assertTrue(AirPlayTxt.hasFeature(f, 33))
        assertFalse(AirPlayTxt.hasFeature(f, 32))
        assertEquals(0x204L, AirPlayTxt.flags("0x204"))
        assertEquals(4L, AirPlayTxt.flags("4"))
    }
}

class DmapTest {
    @Test
    fun `track info is wrapped in an mlit container`() {
        val bytes = Dmap.trackInfo("Title", "Artist", "Album", durationMs = 1234, itemId = 9)
        val outer = Dmap.parse(bytes)
        val inner = Dmap.parse(outer.getValue("mlit"))
        assertEquals("Title", String(inner.getValue("minm")))
        assertEquals("Artist", String(inner.getValue("asar")))
        assertEquals("Album", String(inner.getValue("asal")))
        assertArrayEquals(byteArrayOf(0, 0, 4, 0xD2.toByte()), inner.getValue("astm"))
        assertEquals(9L, Bytes.u64(inner.getValue("mper"), 0))
        assertEquals(2, inner.getValue("mikd")[0].toInt())
    }

    @Test
    fun `missing fields are omitted, not sent empty`() {
        val inner = Dmap.parse(Dmap.parse(Dmap.trackInfo("T", null, null)).getValue("mlit"))
        assertFalse(inner.containsKey("asar"))
        assertFalse(inner.containsKey("asal"))
        assertFalse(inner.containsKey("astm"))
    }

    @Test
    fun `multi-byte UTF-8 lengths are byte counts`() {
        val inner = Dmap.parse(Dmap.parse(Dmap.trackInfo("Für Elise ♫", null, null)).getValue("mlit"))
        assertEquals("Für Elise ♫", String(inner.getValue("minm"), Charsets.UTF_8))
    }
}

class ClockTest {
    @Test
    fun `NTP conversion round-trips and uses the 1900 epoch`() {
        val unix = 1_700_000_000_123_456_789L
        val ntp = AirPlayClock.toNtp(unix)
        assertEquals(1_700_000_000L + AirPlayClock.NTP_EPOCH_OFFSET_SECONDS, ntp ushr 32)
        assertEquals(unix.toDouble(), AirPlayClock.fromNtp(ntp).toDouble(), 2.0)
    }

    @Test
    fun `clock is monotonic`() {
        var last = AirPlayClock.nowNanos()
        repeat(10_000) {
            val now = AirPlayClock.nowNanos()
            assertTrue(now >= last)
            last = now
        }
    }

    @Test
    fun `PTP timestamps are 48-bit seconds plus nanoseconds`() {
        val b = ByteArray(10)
        PtpMaster.putTimestamp(b, 0, 0x1_0000_0002L * 1_000_000_000L + 999_999_999L)
        assertEquals(1, Bytes.u16(b, 0))
        assertEquals(2, Bytes.u32(b, 2))
        assertEquals(999_999_999, Bytes.u32(b, 6))
    }
}

class RtspTest {
    @Test
    fun `codec parses responses with bodies and leaves the stream aligned`() {
        val wire = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 5\r\nSession: ABC;timeout=60\r\n\r\nhello" +
            "RTSP/1.0 401 Unauthorized\r\nCSeq: 4\r\nWWW-Authenticate: Digest realm=\"raop\", nonce=\"n1\"\r\n\r\n"
        val input = ByteArrayInputStream(wire.toByteArray())
        val first = RtspCodec.read(input)!!
        assertEquals(200, first.code)
        assertEquals("hello", String(first.body))
        assertEquals("ABC;timeout=60", first.header("session"))
        val second = RtspCodec.read(input)!!
        assertEquals(401, second.code)
        assertEquals("Unauthorized", second.reason)
        assertNull(RtspCodec.read(input))
    }

    @Test
    fun `codec parses requests`() {
        val msg = RtspCodec.read(ByteArrayInputStream("GET /ctrl-int/1/playpause HTTP/1.1\r\nActive-Remote: 42\r\n\r\n".toByteArray()))!!
        assertFalse(msg.isResponse)
        assertEquals("GET", msg.method)
        assertEquals("/ctrl-int/1/playpause", msg.uri)
    }

    @Test
    fun `digest response matches RFC 2617 (no qop)`() {
        val d = DigestAuth("Mufasa", "Circle Of Life")
        assertTrue(d.challenge("Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        val header = d.authorization("GET", "/dir/index.html")!!
        assertTrue(header, header.contains("response=\"670fd8c2df070c60b045671b8b24ff02\""))
    }

    @Test
    fun `digest for an AirPlay ANNOUNCE`() {
        val d = DigestAuth("iTunes", "secret")
        d.challenge("Digest realm=\"raop\", nonce=\"abc123\"")
        assertTrue(d.authorization("ANNOUNCE", "rtsp://10.0.0.2/1234")!!.contains("4db0d2c6cd1b021fa28ebad75ff0a36b"))
        // The upper-case variant applies to the intermediate hashes as well.
        d.upperCaseHex = true
        assertTrue(d.authorization("ANNOUNCE", "rtsp://10.0.0.2/1234")!!.contains("2FB499244B2A757719095741C5F7912E"))
    }
}

class HapCipherTest {
    private val secret = ByteArray(64) { it.toByte() }

    @Test
    fun `client and receiver ciphers interoperate across multiple blocks and messages`() {
        val client = HapCipher.forControl(secret)
        val server = HapCipher.forControlServer(secret)
        val messages = listOf(ByteArray(10) { 1 }, ByteArray(2500) { (it % 251).toByte() }, ByteArray(1024) { 7 })
        val wire = ByteArrayOutputStream()
        messages.forEach { wire.write(client.encrypt(it)) }
        val plain = server.decryptingStream(ByteArrayInputStream(wire.toByteArray())).readBytes()
        assertArrayEquals(messages.fold(ByteArray(0)) { a, b -> a + b }, plain)
        // 2500 bytes = 1024 + 1024 + 452: three blocks, each 2 + n + 16 bytes
        assertEquals(10 + 18 + 2500 + 3 * 18 + 1024 + 18, wire.size())
    }

    @Test(expected = Exception::class)
    fun `tampered ciphertext is rejected`() {
        val enc = HapCipher.forControl(secret).encrypt("SETUP rtsp://x RTSP/1.0\r\n\r\n".toByteArray())
        enc[5] = (enc[5].toInt() xor 1).toByte()
        HapCipher.forControlServer(secret).readBlock(ByteArrayInputStream(enc))
    }

    @Test
    fun `event channel keys are the mirror image of the control channel's`() {
        // On the event channel the receiver writes with "Events-Write", which we read.
        val ours = HapCipher.forEvents(secret)
        val receiverWrites = HapCipher(
            com.aria.ariacast.airplay2.AirPlay2Crypto.hkdfSha512("Events-Salt".toByteArray(), secret, "Events-Write-Encryption-Key".toByteArray(), 32),
            ByteArray(32)
        )
        val msg = "POST /command RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray()
        assertArrayEquals(msg, ours.readBlock(ByteArrayInputStream(receiverWrites.encrypt(msg))))
    }
}
