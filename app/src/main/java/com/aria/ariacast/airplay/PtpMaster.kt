package com.aria.ariacast.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * A minimal, send-only IEEE 1588 / 802.1AS grandmaster announcing [AirPlayClock].
 *
 * AirPlay 2 receivers built on shairport-sync + nqptp only accept PTP timing, and
 * nqptp only trusts PTP packets whose source port equals the PTP port (319 event,
 * 320 general). Ordinary Android apps can't bind ports below 1024, so this is
 * opportunistic: [tryCreate] returns null when the ports can't be bound (the
 * usual case on a non-rooted phone) and the AirPlay 2 client then uses NTP
 * timing or falls back to AirPlay 1.
 *
 * It sends Announce (1/s), and two-step Sync + Follow_Up (8/s) carrying the
 * clock. Followers that would send Delay_Req (Apple devices) aren't served -
 * nqptp doesn't use them.
 */
class PtpMaster private constructor(
    private val eventSocket: DatagramSocket,
    private val generalSocket: DatagramSocket,
    val clockId: Long
) {
    companion object {
        private const val TAG = "PtpMaster"
        const val EVENT_PORT = 319
        const val GENERAL_PORT = 320

        fun tryCreate(): PtpMaster? {
            var event: DatagramSocket? = null
            return try {
                event = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(EVENT_PORT)) }
                val general = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(GENERAL_PORT)) }
                val id = SecureRandom().nextLong() and 0x7FFFFFFFFFFFFFFFL
                PtpMaster(event, general, id)
            } catch (e: Exception) {
                event?.close()
                ProtoLog.d(TAG, "PTP ports unavailable (${e.message}); PTP timing disabled")
                null
            }
        }

        /** PTP timestamp: 48-bit seconds + 32-bit nanoseconds. */
        internal fun putTimestamp(buf: ByteArray, off: Int, nanos: Long) {
            val seconds = nanos / 1_000_000_000L
            val ns = (nanos % 1_000_000_000L).toInt()
            Bytes.putU16(buf, off, (seconds ushr 32).toInt())
            Bytes.putU32(buf, off + 2, seconds.toInt())
            Bytes.putU32(buf, off + 6, ns)
        }
    }

    private val peers = CopyOnWriteArraySet<InetAddress>()
    @Volatile private var running = false
    private var worker: Thread? = null
    private var announceSeq = 0
    private var syncSeq = 0

    fun addPeer(address: InetAddress) { peers.add(address) }
    fun removePeer(address: InetAddress) { peers.remove(address) }
    val hasPeers: Boolean get() = peers.isNotEmpty()

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "ptp-master", isDaemon = true) {
            var tick = 0
            while (running) {
                try {
                    if (tick % 8 == 0) sendAnnounce()
                    sendSyncAndFollowUp()
                } catch (e: Exception) {
                    if (running) ProtoLog.w(TAG, "PTP send failed: ${e.message}")
                }
                tick++
                try { Thread.sleep(125) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(500)
        eventSocket.close()
        generalSocket.close()
    }

    private fun header(type: Int, length: Int, seq: Int, control: Int, logPeriod: Int, flags: Int): ByteArray {
        val b = ByteArray(length)
        b[0] = (0x10 or type).toByte()     // transportSpecific = 1 (802.1AS), messageType
        b[1] = 0x02                         // PTP version 2
        Bytes.putU16(b, 2, length)
        b[4] = 0                            // domain
        Bytes.putU16(b, 6, flags)
        Bytes.putU64(b, 20, clockId)        // sourcePortIdentity.clockIdentity
        Bytes.putU16(b, 28, 1)              // sourcePortIdentity.portNumber
        Bytes.putU16(b, 30, seq)
        b[32] = control.toByte()
        b[33] = logPeriod.toByte()
        return b
    }

    private fun sendAnnounce() {
        val b = header(type = 0x0B, length = 64, seq = announceSeq++ and 0xFFFF, control = 5, logPeriod = 0, flags = 0x0008)
        // originTimestamp (34..43) left zero, as allowed
        Bytes.putU16(b, 44, 37)             // currentUtcOffset
        b[47] = 248.toByte()                // grandmasterPriority1 (Apple profile default)
        Bytes.putU32(b, 48, 0xF8FE436A.toInt()) // clockClass 248, accuracy 0xFE, variance
        b[52] = 248.toByte()                // grandmasterPriority2
        Bytes.putU64(b, 53, clockId)        // grandmasterIdentity
        Bytes.putU16(b, 61, 0)              // stepsRemoved
        b[63] = 0xA0.toByte()               // timeSource: internal oscillator
        sendTo(generalSocket, b, GENERAL_PORT)
    }

    private fun sendSyncAndFollowUp() {
        val seq = syncSeq++ and 0xFFFF
        val sync = header(type = 0x00, length = 44, seq = seq, control = 0, logPeriod = -3, flags = 0x0208) // two-step
        val sentAt = AirPlayClock.nowNanos()
        sendTo(eventSocket, sync, EVENT_PORT)
        val follow = header(type = 0x08, length = 76, seq = seq, control = 2, logPeriod = -3, flags = 0x0008)
        putTimestamp(follow, 34, sentAt)
        // 802.1AS Follow_Up information TLV
        Bytes.putU16(follow, 44, 0x0003)    // ORGANIZATION_EXTENSION
        Bytes.putU16(follow, 46, 28)
        follow[48] = 0x00; follow[49] = 0x80.toByte(); follow[50] = 0xC2.toByte()
        follow[51] = 0x00; follow[52] = 0x00; follow[53] = 0x01
        sendTo(generalSocket, follow, GENERAL_PORT)
    }

    private fun sendTo(socket: DatagramSocket, data: ByteArray, port: Int) {
        for (peer in peers) socket.send(DatagramPacket(data, data.size, peer, port))
    }
}
