package com.aria.ariacast.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The ESP32 AriaCompanion board over Bluetooth SPP (docs/ariacompanion-bt.md §3).
 *
 * Commands are 4-byte frames written to the RFCOMM socket; everything the board sends,
 * including the replies to GET_INFO / GET_STATS, arrives in the chunk stream. The hub parses
 * that stream and hands the replies back through [onReply], which unblocks [getInfo]/[getStats].
 */
class CompanionBt private constructor(
    val device: BluetoothDevice,
    private val socket: BluetoothSocket,
) : CompanionLink {
    override val kind = CompanionLink.Kind.BLUETOOTH
    override val id: String get() = device.address

    private val output = socket.outputStream
    private val writeLock = Any()
    private val infoReplies = LinkedBlockingQueue<CompanionInfo>(1)
    private val statsReplies = LinkedBlockingQueue<CompanionStats>(1)
    @Volatile private var reading = false
    private var readerThread: Thread? = null

    private fun send(command: Int, value: Int): Boolean = try {
        synchronized(writeLock) {
            output.write(byteArrayOf(CompanionProtocol.MAGIC.toByte(), command.toByte(), value.toByte(), (value shr 8).toByte()))
            output.flush()
        }
        true
    } catch (e: IOException) {
        false
    }

    override fun getInfo(): CompanionInfo? {
        infoReplies.clear()
        if (!send(CompanionProtocol.CMD_GET_INFO, 0)) return null
        return infoReplies.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    override fun getStats(): CompanionStats? {
        statsReplies.clear()
        if (!send(CompanionProtocol.CMD_GET_STATS, 0)) return null
        return statsReplies.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    override fun setMode(mode: Int): Boolean = send(CompanionProtocol.CMD_SET_MODE, mode)
    override fun heartbeat(timeoutSeconds: Int): Boolean = send(CompanionProtocol.CMD_HEARTBEAT, timeoutSeconds.coerceIn(1, 10))
    override fun resetStats(): Boolean = send(CompanionProtocol.CMD_RESET_STATS, 0)

    override fun onReply(event: CompanionEvent) {
        when (event) {
            is CompanionEvent.Info -> { infoReplies.clear(); infoReplies.offer(event.info) }
            is CompanionEvent.Stats -> { statsReplies.clear(); statsReplies.offer(event.stats) }
            else -> {}
        }
    }

    override fun startReading(onBytes: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (reading) return
        reading = true
        readerThread = Thread({
            val buf = ByteArray(4096)
            val input = socket.inputStream
            try {
                while (reading) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("link closed")
                    if (n > 0) onBytes(buf, n)
                }
            } catch (e: IOException) {
                if (reading) {
                    Log.w(TAG, "Bluetooth link lost: ${e.message}")
                    onError(e.message ?: "Bluetooth link lost")
                }
            }
        }, "AriaCompanion-bt").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun close() {
        reading = false
        try { socket.close() } catch (_: IOException) {}
        readerThread?.join(1000)
        readerThread = null
    }

    companion object {
        private const val TAG = "CompanionBt"
        private const val REPLY_TIMEOUT_MS = 1500L
        private val SPP = UUID.fromString(CompanionProtocol.SPP_UUID)

        /** True for a bonded device that looks like an AriaCompanion BT board. */
        @SuppressLint("MissingPermission")
        fun looksLikeCompanion(device: BluetoothDevice): Boolean =
            runCatching { device.name?.startsWith(CompanionProtocol.BT_NAME_PREFIX) == true }.getOrDefault(false)

        /**
         * Connects the SPP channel. Blocks for up to ~10 s when the board is off or out of
         * range; returns null then. The caller must hold BLUETOOTH_CONNECT (Android 12+).
         */
        @SuppressLint("MissingPermission")
        fun open(device: BluetoothDevice): CompanionBt? {
            val socket = try { device.createRfcommSocketToServiceRecord(SPP) } catch (e: Exception) { return null }
            return try {
                socket.connect()
                CompanionBt(device, socket)
            } catch (e: Exception) {
                try { socket.close() } catch (_: IOException) {}
                null
            }
        }
    }
}
