package com.aria.ariacast.pipeline

/**
 * Re-chunks a stream of PCM byte arrays of any size into fixed-size frames.
 *
 * Sources that receive audio in arbitrary packet sizes (USB bulk transfers, network
 * receivers) push their bytes through this so every frame on the audio bus has exactly
 * [frameBytes] bytes. Not thread-safe: one chunker per producer.
 */
class FrameChunker(private val frameBytes: Int = PcmFormat.FRAME_BYTES) {
    private var pending = ByteArray(frameBytes)
    private var filled = 0

    /** Bytes currently held back waiting for a full frame. */
    val pendingBytes: Int get() = filled

    /** Appends [length] bytes from [data] at [offset] and calls [onFrame] for every completed frame. */
    inline fun push(data: ByteArray, offset: Int = 0, length: Int = data.size, onFrame: (ByteArray) -> Unit) {
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val n = append(data, pos, remaining)
            pos += n
            remaining -= n
            takeFrameIfFull()?.let(onFrame)
        }
    }

    /** Copies as many bytes as fit into the pending frame and returns how many were taken. */
    fun append(data: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, frameBytes - filled)
        System.arraycopy(data, offset, pending, filled, n)
        filled += n
        return n
    }

    /** Returns the completed frame (and starts a new one), or null if it isn't full yet. */
    fun takeFrameIfFull(): ByteArray? {
        if (filled < frameBytes) return null
        val frame = pending
        pending = ByteArray(frameBytes)
        filled = 0
        return frame
    }

    /** Drops any partially filled frame. */
    fun reset() {
        filled = 0
    }
}
