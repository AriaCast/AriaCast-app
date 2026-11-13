package com.aria.ariacast.companion

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.nio.ByteBuffer

/**
 * Low-level access to one AriaCompanion board through Android's USB host API.
 *
 * Only the vendor interface is claimed; the UAC2 speaker interfaces stay with Android's
 * own USB audio support. Commands are EP0 vendor requests; audio arrives on bulk IN and is
 * read on a dedicated thread with several [UsbRequest]s kept in flight.
 */
class CompanionUsb private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
) {
    @Volatile private var reading = false
    private var readerThread: Thread? = null

    private val ifaceIndex: Int get() = iface.id

    fun getInfo(): CompanionInfo? {
        val buf = ByteArray(CompanionProtocol.INFO_BYTES)
        val n = connection.controlTransfer(CompanionProtocol.REQTYPE_IN, CompanionProtocol.REQ_GET_INFO, 0, ifaceIndex, buf, buf.size, TIMEOUT_MS)
        return if (n >= CompanionProtocol.INFO_BYTES) CompanionInfo.parse(buf) else null
    }

    fun getStats(): CompanionStats? {
        val buf = ByteArray(CompanionProtocol.STATS_BYTES)
        val n = connection.controlTransfer(CompanionProtocol.REQTYPE_IN, CompanionProtocol.REQ_GET_STATS, 0, ifaceIndex, buf, buf.size, TIMEOUT_MS)
        return if (n >= CompanionProtocol.STATS_BYTES) CompanionStats.parse(buf) else null
    }

    fun setMode(mode: Int): Boolean = command(CompanionProtocol.REQ_SET_MODE, mode)
    fun heartbeat(timeoutSeconds: Int): Boolean = command(CompanionProtocol.REQ_HEARTBEAT, timeoutSeconds.coerceIn(1, 10))
    fun rebootToBootloader(): Boolean = command(CompanionProtocol.REQ_REBOOT_BOOTLOADER, 0)
    fun resetStats(): Boolean = command(CompanionProtocol.REQ_RESET_STATS, 0)

    private fun command(request: Int, value: Int): Boolean =
        connection.controlTransfer(CompanionProtocol.REQTYPE_OUT, request, value, ifaceIndex, null, 0, TIMEOUT_MS) >= 0

    /**
     * Starts reading bulk IN on a background thread. [onBytes] is called on that thread
     * with a buffer that is only valid during the call. [onError] fires once if the device
     * goes away (e.g. it re-enumerated or was unplugged).
     */
    fun startReading(onBytes: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (reading) return
        reading = true
        readerThread = Thread({
            val requests = List(IN_FLIGHT) {
                UsbRequest().apply {
                    initialize(connection, bulkIn)
                    clientData = ByteBuffer.allocate(TRANSFER_BYTES)
                }
            }
            val scratch = ByteArray(TRANSFER_BYTES)
            try {
                requests.forEach { r -> if (!r.queue(r.clientData as ByteBuffer)) error("queue failed") }
                while (reading) {
                    val done = try { connection.requestWait(500) } catch (_: java.util.concurrent.TimeoutException) { null }
                        ?: continue
                    val bb = done.clientData as ByteBuffer
                    val n = bb.position()
                    if (n > 0) {
                        bb.flip()
                        bb.get(scratch, 0, n)
                        onBytes(scratch, n)
                    }
                    bb.clear()
                    if (reading && !done.queue(bb)) error("re-queue failed")
                }
            } catch (e: Exception) {
                if (reading) {
                    Log.w(TAG, "Bulk reader stopped: ${e.message}")
                    onError(e.message ?: "USB read failed")
                }
            } finally {
                requests.forEach { try { it.cancel(); it.close() } catch (_: Exception) {} }
            }
        }, "AriaCompanion-bulk").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stopReading() {
        reading = false
        readerThread?.join(1000)
        readerThread = null
    }

    fun close() {
        stopReading()
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "CompanionUsb"
        private const val TIMEOUT_MS = 1000
        private const val IN_FLIGHT = 4
        private const val TRANSFER_BYTES = 16 * 1024

        /** The vendor interface of an AriaCompanion board, or null if [device] isn't one. */
        fun findVendorInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == CompanionProtocol.IFACE_CLASS &&
                    it.interfaceSubclass == CompanionProtocol.IFACE_SUBCLASS &&
                    it.interfaceProtocol == CompanionProtocol.IFACE_PROTOCOL
            }

        fun isCompanion(device: UsbDevice): Boolean = findVendorInterface(device) != null

        fun isBootloader(device: UsbDevice): Boolean =
            device.vendorId == CompanionProtocol.VID_RPI && device.productId == CompanionProtocol.PID_RP2040_BOOTROM

        /** Opens the board. The caller must already hold USB permission for [device]. */
        fun open(usbManager: UsbManager, device: UsbDevice): CompanionUsb? {
            val iface = findVendorInterface(device) ?: return null
            val bulkIn = (0 until iface.endpointCount).map(iface::getEndpoint).firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            } ?: return null
            val connection = usbManager.openDevice(device) ?: return null
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return null
            }
            return CompanionUsb(device, connection, iface, bulkIn)
        }
    }
}
