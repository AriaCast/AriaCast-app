package com.aria.ariacast.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** Builds a complete RTP packet (header + possibly encrypted payload) for one block of PCM. */
fun interface RtpPacketizer {
    fun build(sequence: Int, rtpTime: Int, marker: Boolean, pcm: ByteArray): ByteArray
}

/** How sync packets on the control channel express "now". */
sealed class RtpSyncMode {
    /** AirPlay 1 / AirPlay 2 NTP: 0xD4 packets carrying an NTP timestamp. */
    object Ntp : RtpSyncMode()

    /** AirPlay 2 PTP: 0xD7 packets carrying PTP nanoseconds and the master clock id. */
    class Ptp(val clockId: Long) : RtpSyncMode()
}

/**
 * Streams PCM to an AirPlay receiver as RTP over UDP.
 *
 * Audio is written in whatever chunks the capture produces; a pacer thread sends
 * exactly one 352-frame packet every 352/44100 s on [AirPlayClock], so the RTP
 * timeline is locked to the same clock the sync packets and timing replies
 * describe. That's what lets the receiver (and several receivers in a group)
 * schedule playback precisely. The capture clock isn't the system clock, so a
 * small FIFO absorbs delivery jitter and is held near its target level by
 * dropping or repeating single frames - inaudible, unlike letting the timeline
 * slip until the receiver discards late packets.
 */
class RtpAudioSender(
    private val name: String,
    private val receiver: InetAddress,
    private val dataPort: Int,
    private val controlPort: Int,
    private val dataSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val packetizer: RtpPacketizer,
    private val syncMode: RtpSyncMode,
    initialSequence: Int,
    initialRtpTime: Int,
    private val latencyFrames: Int = DEFAULT_LATENCY_FRAMES,
    private val sampleRate: Int = AirPlayAudio.SAMPLE_RATE,
    private val framesPerPacket: Int = AirPlayAudio.FRAMES_PER_PACKET,
    private val bufferTargetMs: Int = 100
) {
    companion object {
        private const val TAG = "RtpAudioSender"

        /** "rtp_now - latency" offset; receivers add their own ~11025 frames on top (2 s total). */
        const val DEFAULT_LATENCY_FRAMES = 77175
        private const val HISTORY = 1024
        private const val SYNC_INTERVAL_NANOS = 1_000_000_000L
    }

    private val bytesPerFrame = AirPlayAudio.BYTES_PER_FRAME
    private val packetBytes = framesPerPacket * bytesPerFrame

    // --- FIFO ---------------------------------------------------------------------------
    private val fifoLock = ReentrantLock()
    private val fifoNotEmpty = fifoLock.newCondition()
    private val fifo = ByteArray(sampleRate * bytesPerFrame * 4) // 4 s
    private var fifoRead = 0
    private var fifoSize = 0
    private val targetFrames = sampleRate * bufferTargetMs / 1000
    private val hysteresisFrames = sampleRate * 30 / 1000
    private val overflowFrames = targetFrames + sampleRate // > 1 s above target: resync
    private var levelEma = -1.0

    // --- timeline -------------------------------------------------------------------------
    @Volatile private var running = false
    private var pacer: Thread? = null
    private var controlThread: Thread? = null
    @Volatile private var anchorNanos = 0L
    @Volatile private var anchorRtp = initialRtpTime
    @Volatile private var packetIndex = 0L
    @Volatile private var sequence = initialSequence and 0xFFFF
    @Volatile private var markNext = true
    @Volatile private var firstSync = true

    /** Test hook: return true to "lose" the packet with this sequence number on first send. */
    @Volatile internal var dropFilter: ((Int) -> Boolean)? = null

    /** Extra per-receiver delay (multiroom alignment), added to the latency in sync packets. */
    @Volatile var extraDelayMs: Int = 0

    // --- retransmission history -----------------------------------------------------------
    private val history = arrayOfNulls<ByteArray>(HISTORY)
    private val historySeq = IntArray(HISTORY) { -1 }

    // --- stats ----------------------------------------------------------------------------
    @Volatile var packetsSent = 0L; private set
    @Volatile var packetsResent = 0L; private set
    @Volatile var underruns = 0L; private set
    @Volatile var framesDropped = 0L; private set
    @Volatile var framesInserted = 0L; private set

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        controlThread = thread(name = "rtp-ctrl-$name", isDaemon = true) { controlLoop() }
        pacer = thread(name = "rtp-pace-$name", isDaemon = true, priority = Thread.MAX_PRIORITY) { pacerLoop() }
    }

    fun stop() {
        running = false
        fifoLock.withLock { fifoNotEmpty.signalAll() }
        pacer?.interrupt()
        pacer?.join(1000)
        controlThread?.join(1000)
    }

    /** Queues little-endian s16 stereo PCM at [sampleRate]. */
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        fifoLock.withLock {
            var off = offset
            var len = length - length % bytesPerFrame
            val free = fifo.size - fifoSize
            if (len > free) { // drop oldest
                val drop = (len - free).let { it + (bytesPerFrame - it % bytesPerFrame) % bytesPerFrame }
                discardLocked(drop)
            }
            if (len > fifo.size) { off += len - fifo.size; len = fifo.size }
            var writePos = (fifoRead + fifoSize) % fifo.size
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, fifo.size - writePos)
                System.arraycopy(pcm, off, fifo, writePos, n)
                off += n; remaining -= n
                writePos = (writePos + n) % fifo.size
            }
            fifoSize += len
            fifoNotEmpty.signalAll()
        }
    }

    private fun discardLocked(bytes: Int) {
        val n = minOf(bytes, fifoSize)
        fifoRead = (fifoRead + n) % fifo.size
        fifoSize -= n
    }

    private fun readLocked(dst: ByteArray, dstOff: Int, bytes: Int): Int {
        val n = minOf(bytes, fifoSize)
        var copied = 0
        while (copied < n) {
            val c = minOf(n - copied, fifo.size - fifoRead)
            System.arraycopy(fifo, fifoRead, dst, dstOff + copied, c)
            fifoRead = (fifoRead + c) % fifo.size
            copied += c
        }
        fifoSize -= n
        return n
    }

    private var rebuffering = true
    private var packetsSinceCorrection = 0

    /**
     * Takes one packet's worth of PCM.
     *
     *  - Underrun: whatever is left goes out padded with silence, then silence is sent
     *    until the FIFO is back at its target level (rebuffering), so playback resumes
     *    with its jitter cushion rather than limping along on an empty buffer.
     *  - Clock drift: when the smoothed level strays from the target, one frame is
     *    dropped (averaged into its neighbour) or repeated - at most every 8th packet,
     *    i.e. a correction of up to ~355 ppm, far beyond real crystal drift yet far
     *    below anything audible.
     *  - Overflow (a stall followed by a burst): skip straight back to the target.
     */
    private fun nextPcm(): ByteArray {
        val out = ByteArray(packetBytes)
        fifoLock.withLock {
            var level = fifoSize / bytesPerFrame
            if (rebuffering) {
                if (level < targetFrames) return out // silence while the cushion builds
                rebuffering = false
                levelEma = level.toDouble()
            }
            if (level > overflowFrames) {
                val excess = level - targetFrames
                discardLocked(excess * bytesPerFrame)
                framesDropped += excess
                level = targetFrames
                levelEma = targetFrames.toDouble()
            }
            if (level < framesPerPacket) {
                readLocked(out, 0, fifoSize)
                underruns++
                rebuffering = true
                return out
            }
            levelEma += (level - levelEma) * 0.01
            packetsSinceCorrection++
            val canCorrect = packetsSinceCorrection >= 8
            when {
                canCorrect && levelEma > targetFrames + hysteresisFrames && level > framesPerPacket + 1 -> {
                    // Capture clock running fast: consume one extra frame.
                    readLocked(out, 0, packetBytes - bytesPerFrame)
                    val scratch = ByteArray(bytesPerFrame * 2)
                    readLocked(scratch, 0, bytesPerFrame * 2)
                    for (ch in 0 until 2) {
                        val x = s16(scratch, ch * 2)
                        val y = s16(scratch, bytesPerFrame + ch * 2)
                        putS16(out, packetBytes - bytesPerFrame + ch * 2, (x + y) / 2)
                    }
                    framesDropped++
                    levelEma -= 1.0
                    packetsSinceCorrection = 0
                }
                canCorrect && levelEma < targetFrames - hysteresisFrames -> {
                    // Capture clock running slow: repeat one frame.
                    readLocked(out, 0, packetBytes - bytesPerFrame)
                    System.arraycopy(out, packetBytes - 2 * bytesPerFrame, out, packetBytes - bytesPerFrame, bytesPerFrame)
                    framesInserted++
                    levelEma += 1.0
                    packetsSinceCorrection = 0
                }
                else -> readLocked(out, 0, packetBytes)
            }
        }
        return out
    }

    private fun s16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)
    private fun putS16(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte() }

    private fun dueNanos(index: Long): Long =
        anchorNanos + index * framesPerPacket * 1_000_000_000L / sampleRate

    /** RTP timestamp of the frame being sent at [nanos] on the pacer's timeline. */
    fun rtpTimeAt(nanos: Long): Int {
        if (anchorNanos == 0L) return anchorRtp
        val frames = (nanos - anchorNanos) * sampleRate / 1_000_000_000L
        return (anchorRtp.toLong() + frames).toInt()
    }

    /** RTP time "now" - what metadata/progress RTP-Info headers should reference. */
    fun currentRtpTime(): Int = rtpTimeAt(AirPlayClock.nowNanos())

    /** The next sequence number / RTP time to be sent, for RECORD / FLUSH RTP-Info headers. */
    fun nextSequence(): Int = sequence
    fun nextRtpTime(): Int = (anchorRtp.toLong() + packetIndex * framesPerPacket).toInt()

    /** Drops queued audio and marks the next packet as the start of a new burst (after FLUSH). */
    fun flush() {
        fifoLock.withLock { fifoSize = 0; levelEma = -1.0; rebuffering = true }
        markNext = true
    }

    private fun pacerLoop() {
        try {
            // Let a little audio accumulate so the first packets aren't already underruns.
            val prefillDeadline = System.nanoTime() + 500_000_000L
            fifoLock.withLock {
                while (running && fifoSize / bytesPerFrame < targetFrames && System.nanoTime() < prefillDeadline) {
                    fifoNotEmpty.awaitNanos(20_000_000L)
                }
            }
            if (!running) return
            anchorNanos = AirPlayClock.nowNanos()
            packetIndex = 0
            sendSync()
            var nextSync = anchorNanos + SYNC_INTERVAL_NANOS
            while (running) {
                val due = dueNanos(packetIndex)
                val now = AirPlayClock.nowNanos()
                if (now < due) {
                    LockSupport.parkNanos(due - now)
                    continue
                }
                if (now - due > 1_000_000_000L) {
                    // The thread was frozen for over a second (e.g. the device dozed):
                    // jump the timeline forward instead of bursting a backlog of stale audio.
                    val skip = (now - due) * sampleRate / 1_000_000_000L / framesPerPacket
                    packetIndex += skip
                    ProtoLog.w(TAG, "$name: pacer stalled, skipped $skip packets")
                    continue
                }
                val rtp = (anchorRtp.toLong() + packetIndex * framesPerPacket).toInt()
                val seq = sequence
                val packet = packetizer.build(seq, rtp, markNext, nextPcm())
                markNext = false
                remember(seq, packet)
                try {
                    if (dropFilter?.invoke(seq) != true) dataSocket.send(DatagramPacket(packet, packet.size, receiver, dataPort))
                    packetsSent++
                } catch (e: Exception) {
                    if (running) ProtoLog.w(TAG, "$name: audio send failed: ${e.message}")
                }
                sequence = (seq + 1) and 0xFFFF
                packetIndex++
                if (now >= nextSync) {
                    sendSync()
                    nextSync += SYNC_INTERVAL_NANOS
                    if (nextSync < now) nextSync = now + SYNC_INTERVAL_NANOS
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Exception) {
            ProtoLog.e(TAG, "$name: pacer crashed", e)
        }
    }

    private fun remember(seq: Int, packet: ByteArray) {
        synchronized(history) {
            history[seq % HISTORY] = packet
            historySeq[seq % HISTORY] = seq
        }
    }

    /** Builds the sync packet for "now" (exposed for tests). */
    internal fun buildSync(nowNanos: Long, first: Boolean): ByteArray {
        val rtpNow = rtpTimeAt(nowNanos)
        val latency = latencyFrames + extraDelayMs.coerceAtLeast(0) * sampleRate / 1000
        return when (syncMode) {
            is RtpSyncMode.Ntp -> ByteArray(20).also {
                it[0] = (if (first) 0x90 else 0x80).toByte()
                it[1] = 0xD4.toByte()
                Bytes.putU16(it, 2, 0x0007)
                Bytes.putU32(it, 4, rtpNow - latency)
                Bytes.putU64(it, 8, AirPlayClock.toNtp(nowNanos))
                Bytes.putU32(it, 16, rtpNow)
            }
            is RtpSyncMode.Ptp -> ByteArray(28).also {
                it[0] = (if (first) 0x90 else 0x80).toByte()
                it[1] = 0xD7.toByte()
                Bytes.putU16(it, 2, 0x0004)
                Bytes.putU32(it, 4, rtpNow - latency)
                Bytes.putU64(it, 8, nowNanos)
                Bytes.putU32(it, 16, rtpNow)
                Bytes.putU64(it, 20, syncMode.clockId)
            }
        }
    }

    private fun sendSync() {
        val packet = buildSync(AirPlayClock.nowNanos(), firstSync)
        firstSync = false
        try {
            controlSocket.send(DatagramPacket(packet, packet.size, receiver, controlPort))
        } catch (e: Exception) {
            if (running) ProtoLog.w(TAG, "$name: sync send failed: ${e.message}")
        }
    }

    private fun controlLoop() {
        val buf = ByteArray(1500)
        try { controlSocket.soTimeout = 500 } catch (_: SocketException) { return }
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                controlSocket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) ProtoLog.w(TAG, "$name: control socket closed: ${e.message}")
                return
            }
            if (pkt.length >= 8 && (buf[1].toInt() and 0x7F) == 0x55) {
                val first = Bytes.u16(buf, 4)
                val count = Bytes.u16(buf, 6).coerceAtMost(HISTORY)
                resend(first, count, pkt.address, pkt.port)
            }
        }
    }

    private fun resend(first: Int, count: Int, address: InetAddress, port: Int) {
        var sent = 0
        for (i in 0 until count) {
            val seq = (first + i) and 0xFFFF
            val original = synchronized(history) {
                if (historySeq[seq % HISTORY] == seq) history[seq % HISTORY] else null
            } ?: continue
            val out = ByteArray(4 + original.size)
            out[0] = 0x80.toByte(); out[1] = 0xD6.toByte(); out[2] = 0x00; out[3] = 0x01
            System.arraycopy(original, 0, out, 4, original.size)
            try {
                controlSocket.send(DatagramPacket(out, out.size, address, port))
                sent++
            } catch (_: Exception) {}
        }
        packetsResent += sent
        ProtoLog.d(TAG, "$name: resend request seq=$first count=$count -> resent $sent")
    }
}

/**
 * Answers the receiver's NTP-style timing requests (RTP payload type 0x52) so it
 * can measure the offset between its clock and [AirPlayClock].
 */
class NtpTimingResponder(private val socket: DatagramSocket, private val name: String) {
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile var requestsAnswered = 0L; private set

    fun start() {
        running = true
        worker = thread(name = "ntp-$name", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.join(1000)
    }

    private fun loop() {
        val buf = ByteArray(128)
        try { socket.soTimeout = 500 } catch (_: SocketException) { return }
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                return
            }
            val received = AirPlayClock.ntpNow()
            if (pkt.length < 32 || (buf[1].toInt() and 0x7F) != 0x52) continue
            val reply = buildReply(buf, received, AirPlayClock.ntpNow())
            try {
                socket.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                requestsAnswered++
            } catch (_: Exception) {}
        }
    }

    companion object {
        fun buildReply(request: ByteArray, receivedNtp: Long, sendNtp: Long): ByteArray {
            val reply = ByteArray(32)
            reply[0] = 0x80.toByte()
            reply[1] = 0xD3.toByte()
            Bytes.putU16(reply, 2, 0x0007)
            // reference (origin) time = the request's transmit time
            System.arraycopy(request, 24, reply, 8, 8)
            Bytes.putU64(reply, 16, receivedNtp)
            Bytes.putU64(reply, 24, sendNtp)
            return reply
        }
    }
}
