package com.aria.ariacast.vban

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class VbanTest {

    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun u32(buf: ByteArray, at: Int) = (0 until 4).fold(0L) { acc, i -> acc or (u8(buf[at + i]).toLong() shl (8 * i)) }

    @Test
    fun `sample rate indices follow VBAN_SRList`() {
        assertEquals(3, VbanProtocol.sampleRateIndex(48000))
        assertEquals(16, VbanProtocol.sampleRateIndex(44100))
        assertEquals(7, VbanProtocol.sampleRateIndex(8000))
        assertEquals(20, VbanProtocol.sampleRateIndex(705600))
        assertEquals(-1, VbanProtocol.sampleRateIndex(47999))
    }

    @Test
    fun `stream names are printable ASCII, at most 16 characters, never empty`() {
        assertEquals("Stream1", VbanProtocol.sanitizeStreamName(null))
        assertEquals("Stream1", VbanProtocol.sanitizeStreamName("  éé "))
        assertEquals("PhoneStream-0123", VbanProtocol.sanitizeStreamName("PhoneéStream-0123456789"))
    }

    @Test
    fun `streamer splits a 20 ms frame into 256-sample int16 stereo packets`() {
        val receiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 2000 }
        val streamer = VbanStreamer(InetAddress.getLoopbackAddress(), receiver.localPort, "AriaCast", 48000, 2)
        try {
            // 960 stereo 16-bit samples, sent twice to check the counter keeps growing.
            val frame = ByteArray(3840) { (it % 251).toByte() }
            streamer.send(frame)
            streamer.send(frame + byteArrayOf(1, 2, 3)) // trailing partial sample is dropped
            assertEquals(8, streamer.packetsSent)

            val samples = listOf(256, 256, 256, 192)
            for (counter in 0 until 8) {
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                receiver.receive(packet)
                val n = samples[counter % 4]
                val offset = samples.take(counter % 4).sum() * 4
                assertEquals(VbanProtocol.HEADER_SIZE + n * 4, packet.length)
                assertTrue(packet.length <= VbanProtocol.HEADER_SIZE + VbanProtocol.MAX_DATA_SIZE)
                assertEquals("VBAN", String(buf, 0, 4, Charsets.US_ASCII))
                assertEquals(0x03, u8(buf[4]))            // AUDIO sub protocol, 48000 Hz
                assertEquals(n - 1, u8(buf[5]))           // nb samples - 1
                assertEquals(1, u8(buf[6]))               // nb channels - 1
                assertEquals(0x01, u8(buf[7]))            // INT16, PCM codec
                assertEquals("AriaCast", String(buf, 8, 16, Charsets.US_ASCII).trimEnd('\u0000'))
                assertEquals(counter.toLong(), u32(buf, 24))
                assertArrayEquals(frame.copyOfRange(offset, offset + n * 4), buf.copyOfRange(28, packet.length))
            }
        } finally {
            streamer.close()
            receiver.close()
        }
    }

    @Test
    fun `ping request is a SERVICE identification packet with PING0 data`() {
        val p = VbanProtocol.pingRequest(42, "Pixel 9", "AriaCast", "pixel")
        assertEquals(VbanProtocol.HEADER_SIZE + VbanProtocol.PING0_SIZE, p.size)
        assertEquals(0x60, u8(p[4]))
        assertEquals(0, u8(p[5]))      // function PING0, not a reply
        assertEquals(0, u8(p[6]))      // service IDENTIFICATION
        assertEquals(0, u8(p[7]))
        assertEquals(42L, u32(p, 24))
        assertEquals(VbanProtocol.PING_TYPE_TRANSMITTER.toLong(), u32(p, 28))
        assertEquals(48000L, u32(p, 28 + 12))
        assertEquals("Pixel 9", String(p, 28 + 164, 7, Charsets.US_ASCII))
        // Our own request must not be mistaken for a reply.
        assertNull(VbanProtocol.parsePingReply(p, p.size))
    }

    @Test
    fun `ping reply from a receiver is parsed`() {
        val reply = VbanProtocol.pingRequest(7, "Realtek", "VBAN Mixer", "DESKTOP-1")
        reply[5] = VbanProtocol.FNCT_REPLY.toByte()
        reply[28] = (VbanProtocol.PING_TYPE_RECEPTOR or 0x20).toByte() // receptor + virtual mixer
        "Studio PC".toByteArray().copyInto(reply, 28 + 420)
        val parsed = VbanProtocol.parsePingReply(reply, reply.size)!!
        assertEquals(7, parsed.frame)
        assertTrue(parsed.receivesAudio)
        assertEquals("Studio PC", parsed.displayName)
        assertEquals("VBAN Mixer", parsed.application)

        // A transmitter-only device can't be cast to; a truncated packet isn't a reply.
        reply[28] = VbanProtocol.PING_TYPE_TRANSMITTER.toByte()
        assertFalse(VbanProtocol.parsePingReply(reply, reply.size)!!.receivesAudio)
        assertNull(VbanProtocol.parsePingReply(reply, 100))
    }
}
