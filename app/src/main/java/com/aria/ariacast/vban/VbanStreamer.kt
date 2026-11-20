package com.aria.ariacast.vban

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends PCM to one VBAN receiver as AUDIO packets. VBAN has no session, handshake or
 * clock: the receiver follows the incoming data rate, so each capture frame is split
 * into packets of at most 256 samples and sent right away.
 */
class VbanStreamer(
    private val address: InetAddress,
    private val port: Int,
    streamName: String,
    sampleRate: Int,
    private val channels: Int,
    private val socket: DatagramSocket = DatagramSocket().apply { broadcast = true },
) : Closeable {
    private val bytesPerFrame = channels * 2
    private val samplesPerPacket = minOf(VbanProtocol.MAX_SAMPLES, VbanProtocol.MAX_DATA_SIZE / bytesPerFrame)
    private val packet = ByteArray(VbanProtocol.HEADER_SIZE + samplesPerPacket * bytesPerFrame)
    private var frameCounter = 0

    var packetsSent = 0L
        private set

    init {
        val srIndex = VbanProtocol.sampleRateIndex(sampleRate)
        require(srIndex >= 0) { "VBAN can't carry $sampleRate Hz" }
        require(channels in 1..256) { "VBAN carries 1 to 256 channels" }
        VbanProtocol.writeHeader(packet, VbanProtocol.PROTOCOL_AUDIO or srIndex, 0, channels - 1,
            VbanProtocol.DATATYPE_INT16 or VbanProtocol.CODEC_PCM, VbanProtocol.sanitizeStreamName(streamName), 0)
    }

    /** Sends 16-bit little-endian interleaved [pcm]; a trailing partial frame is dropped. */
    fun send(pcm: ByteArray) {
        val length = pcm.size - pcm.size % bytesPerFrame
        var offset = 0
        while (offset < length) {
            val samples = minOf(samplesPerPacket, (length - offset) / bytesPerFrame)
            val size = samples * bytesPerFrame
            packet[5] = (samples - 1).toByte()
            VbanProtocol.writeFrameCounter(packet, frameCounter++)
            System.arraycopy(pcm, offset, packet, VbanProtocol.HEADER_SIZE, size)
            socket.send(DatagramPacket(packet, VbanProtocol.HEADER_SIZE + size, address, port))
            packetsSent++
            offset += size
        }
    }

    override fun close() = socket.close()
}
