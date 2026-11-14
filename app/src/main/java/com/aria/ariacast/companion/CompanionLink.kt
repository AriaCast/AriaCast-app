package com.aria.ariacast.companion

/**
 * One connection to an AriaCompanion board, whatever carries it: the RP2040 over USB
 * ([CompanionUsb]) or the ESP32 over Bluetooth SPP ([CompanionBt]). Both speak the same
 * chunk stream and the same commands (docs/ariacompanion-2.md §4, docs/ariacompanion-bt.md §3),
 * so [CompanionHub] drives either one the same way. Calls block; use them off the main thread.
 */
interface CompanionLink {
    enum class Kind { USB, BLUETOOTH }

    val kind: Kind
    /** Stable identity for logs and to tell boards apart (USB device path or Bluetooth address). */
    val id: String

    fun getInfo(): CompanionInfo?
    fun getStats(): CompanionStats?
    fun setMode(mode: Int): Boolean
    fun heartbeat(timeoutSeconds: Int): Boolean
    fun resetStats(): Boolean

    /**
     * Starts delivering the board's byte stream to [onBytes] on a background thread (the
     * buffer is valid only during the call). [onError] fires once if the link goes away.
     */
    fun startReading(onBytes: (ByteArray, Int) -> Unit, onError: (String) -> Unit)
    fun close()

    /** Replies that come back inside the chunk stream (the Bluetooth board answers GET_INFO/GET_STATS there). */
    fun onReply(event: CompanionEvent) {}
}
