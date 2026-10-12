package com.aria.ariacast.airplay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class RtpAudioSenderTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val receiverData = DatagramSocket(0, loopback).apply { soTimeout = 2000 }
    private val receiverControl = DatagramSocket(0, loopback).apply { soTimeout = 2000 }
    private val ourData = DatagramSocket(0, loopback)
    private val ourControl = DatagramSocket(0, loopback)
    private var sender: RtpAudioSender? = null

    @After
    fun tearDown() {
        sender?.stop()
        listOf(receiverData, receiverControl, ourData, ourControl).forEach { it.close() }
    }

    private val packetizer = RtpPacketizer { seq, rtp, marker, pcm ->
        val p = ByteArray(12 + pcm.size)
        p[0] = 0x80.toByte(); p[1] = (if (marker) 0xE0 else 0x60).toByte()
        Bytes.putU16(p, 2, seq); Bytes.putU32(p, 4, rtp); Bytes.putU32(p, 8, 0x1234)
        System.arraycopy(pcm, 0, p, 12, pcm.size)
        p
    }

    private fun newSender(mode: RtpSyncMode = RtpSyncMode.Ntp) = RtpAudioSender(
        "test", loopback, receiverData.localPort, receiverControl.localPort, ourData, ourControl,
        packetizer, mode, initialSequence = 65530, initialRtpTime = -1000
    ).also { sender = it }

    private fun receive(socket: DatagramSocket): ByteArray? = try {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        socket.receive(p)
        buf.copyOf(p.length)
    } catch (e: SocketTimeoutException) { null }

    private fun pcm(frames: Int, value: Int): ByteArray = ByteArray(frames * 4) { if (it % 2 == 0) value.toByte() else 0 }

    @Test
    fun `packets are paced at 44100 Hz with continuous sequence and timestamps`() {
        val s = newSender()
        s.write(pcm(44100, 1)) // 1 s of audio, more than the prefill
        s.start()
        val first = receive(receiverData)!!
        assertEquals(0xE0, first[1].toInt() and 0xFF) // marker on the first packet
        val t0 = System.nanoTime()
        var prevSeq = Bytes.u16(first, 2)
        var prevTs = Bytes.u32(first, 4)
        repeat(124) {
            val p = receive(receiverData)!!
            assertEquals(0x60, p[1].toInt() and 0xFF)
            val seq = Bytes.u16(p, 2)
            assertEquals((prevSeq + 1) and 0xFFFF, seq) // wraps past 65535
            assertEquals(prevTs + 352, Bytes.u32(p, 4))
            prevSeq = seq; prevTs = Bytes.u32(p, 4)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        // 124 packets x 352 frames = 989.8 ms of audio
        assertEquals(989.8, elapsedMs, 60.0)
    }

    @Test
    fun `NTP sync packets mark the first one and track the RTP timeline`() {
        val s = newSender()
        s.write(pcm(8820, 1))
        s.start()
        val first = receive(receiverControl)!!
        assertEquals(20, first.size)
        assertEquals(0x90, first[0].toInt() and 0xFF)
        assertEquals(0xD4, first[1].toInt() and 0xFF)
        val rtpNow = Bytes.u32(first, 16)
        assertEquals(RtpAudioSender.DEFAULT_LATENCY_FRAMES, rtpNow - Bytes.u32(first, 4))
        val ntpNanos = AirPlayClock.fromNtp(Bytes.u64(first, 8))
        assertEquals(AirPlayClock.nowNanos().toDouble(), ntpNanos.toDouble(), 50e6)
        val second = receive(receiverControl)!!
        assertEquals(0x80, second[0].toInt() and 0xFF)
        // one second later the timeline has advanced by ~44100 frames
        assertEquals(44100.0, (Bytes.u32(second, 16) - rtpNow).toDouble(), 900.0)
    }

    @Test
    fun `PTP sync packets carry nanoseconds and the clock id`() {
        val s = newSender(RtpSyncMode.Ptp(0x0102030405060708L))
        s.extraDelayMs = 100
        val p = s.buildSync(AirPlayClock.nowNanos(), first = true)
        assertEquals(28, p.size)
        assertEquals(0xD7, p[1].toInt() and 0xFF)
        assertEquals(0x0102030405060708L, Bytes.u64(p, 20))
        assertEquals(RtpAudioSender.DEFAULT_LATENCY_FRAMES + 4410, Bytes.u32(p, 16) - Bytes.u32(p, 4))
    }

    @Test
    fun `resend requests are answered with the original packets`() {
        val s = newSender()
        s.write(pcm(44100, 3))
        s.start()
        val packets = (0 until 20).map { receive(receiverData)!! }
        val wanted = packets[5]
        val seq = Bytes.u16(wanted, 2)
        val req = ByteArray(8).also {
            it[0] = 0x80.toByte(); it[1] = 0xD5.toByte(); Bytes.putU16(it, 2, 1); Bytes.putU16(it, 4, seq); Bytes.putU16(it, 6, 2)
        }
        receiverControl.send(DatagramPacket(req, req.size, loopback, ourControl.localPort))
        var resent: ByteArray? = null
        val deadline = System.currentTimeMillis() + 2000
        while (resent == null && System.currentTimeMillis() < deadline) {
            val p = receive(receiverControl) ?: break
            if ((p[1].toInt() and 0xFF) == 0xD6) resent = p
        }
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xD6.toByte(), 0, 1), resent!!.copyOf(4))
        assertArrayEquals(wanted, resent.copyOfRange(4, resent.size))
    }

    @Test
    fun `underruns send silence and rebuffer instead of slowing the audio down`() {
        val s = newSender()
        s.write(pcm(4410, 9)) // exactly the 100 ms cushion
        s.start()
        var audio = 0; var silent = 0
        repeat(40) {
            val p = receive(receiverData)!!
            if (p[12].toInt() == 9) audio++ else silent++
        }
        assertEquals(13, audio) // 4410 frames = 12 full packets + a partial one padded with silence
        assertTrue(silent >= 27)
        assertEquals(1, s.underruns)
        assertEquals(0, s.framesInserted) // no pitch-changing frame repeats
    }

    @Test
    fun `a capture clock running fast is absorbed by dropping single frames`() {
        val s = newSender()
        s.start()
        // feed 2% fast for 4 s: far beyond crystal drift, so the overflow guard and the
        // per-packet corrections both have to work
        val start = System.nanoTime()
        var frames = 0L
        while (System.nanoTime() - start < 4_000_000_000L) {
            s.write(pcm(900, 1))
            frames += 900
            val due = start + frames * 1_000_000_000L / 44982 // 44100 * 1.02
            while (System.nanoTime() < due) Thread.sleep(1)
            while (receive(receiverData.also { it.soTimeout = 1 }) != null) {}
        }
        // Corrections are capped at one frame per 8 packets (~15/s), engaging once the
        // smoothed level passes target + 30 ms.
        assertTrue("frames dropped: ${s.framesDropped}", s.framesDropped > 10)
        assertEquals(0, s.framesInserted)
    }
}

class NtpTimingResponderTest {
    @Test
    fun `answers timing requests with origin, receive and transmit times`() {
        val ours = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val theirs = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 2000 }
        val responder = NtpTimingResponder(ours, "test").also { it.start() }
        try {
            val req = ByteArray(32)
            req[0] = 0x80.toByte(); req[1] = 0xD2.toByte(); Bytes.putU16(req, 2, 7)
            Bytes.putU64(req, 24, 0x1122334455667788L)
            theirs.send(DatagramPacket(req, req.size, InetAddress.getLoopbackAddress(), ours.localPort))
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            theirs.receive(p)
            assertEquals(32, p.length)
            assertEquals(0xD3, buf[1].toInt() and 0xFF)
            assertEquals(0x1122334455667788L, Bytes.u64(buf, 8))
            val recv = AirPlayClock.fromNtp(Bytes.u64(buf, 16))
            val sent = AirPlayClock.fromNtp(Bytes.u64(buf, 24))
            assertTrue(sent >= recv)
            assertEquals(AirPlayClock.nowNanos().toDouble(), sent.toDouble(), 100e6)
        } finally {
            responder.stop(); ours.close(); theirs.close()
        }
    }
}

class DacpServerTest {
    private fun request(server: DacpServer, path: String, remote: String = "777"): String {
        java.net.Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nActive-Remote: $remote\r\nConnection: close\r\n\r\n".toByteArray())
            return s.getInputStream().bufferedReader().readLine()
        }
    }

    @Test
    fun `maps remote-control requests and rejects foreign remotes`() {
        val got = java.util.concurrent.LinkedBlockingQueue<DacpServer.Command>()
        val server = DacpServer("777") { got.add(it) }
        server.start()
        try {
            assertEquals("HTTP/1.1 204 No Content", request(server, "/ctrl-int/1/playpause"))
            assertEquals(DacpServer.Command.PlayPause, got.poll(1, java.util.concurrent.TimeUnit.SECONDS))
            request(server, "/ctrl-int/1/nextitem")
            assertEquals(DacpServer.Command.Next, got.poll(1, java.util.concurrent.TimeUnit.SECONDS))
            request(server, "/ctrl-int/1/setproperty?dmcp.device-volume=-15.000000")
            assertEquals(DacpServer.Command.SetVolume(-15.0, isPercent = false), got.poll(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("HTTP/1.1 403 Forbidden", request(server, "/ctrl-int/1/pause", remote = "666"))
            assertEquals("HTTP/1.1 404 Not Found", request(server, "/nope"))
            assertEquals("HTTP/1.1 200 OK", request(server, "/server-info"))
            assertTrue(got.isEmpty())
        } finally {
            server.stop()
        }
    }
}
