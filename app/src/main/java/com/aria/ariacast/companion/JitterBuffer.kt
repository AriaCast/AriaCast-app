package com.aria.ariacast.companion

/**
 * Smooths the Bluetooth board's audio into a steady 20 ms cadence.
 *
 * A2DP and the SPP return channel deliver audio in bursts (radio retransmissions, a busy
 * 2.4 GHz band, other Bluetooth links on the phone), while receivers want one frame every
 * 20 ms. Frames are queued as they arrive and [next] is called on a fixed clock:
 *
 * - it waits for [prebufferFrames] before playing, so short gaps are absorbed;
 * - an empty queue while playing yields silence, at most [maxSilenceFrames] in a row, after
 *   which it goes idle and prebuffers again (the phone paused or the link stalled);
 * - a queue grown more than [maxExtraFrames] beyond the prebuffer target loses its oldest
 *   frame, so the phone's clock running slightly fast can't build up latency.
 *
 * Thread-safe: frames are pushed from the event loop and pulled by the pacer.
 */
class JitterBuffer(
    private val frameBytes: Int,
    private val prebufferFrames: Int = 8,
    private val maxExtraFrames: Int = 10,
    private val maxSilenceFrames: Int = 10,
) {
    private val queue = ArrayDeque<ByteArray>()
    private var playing = false
    private var starved = 0

    var underruns = 0L
        private set
    var drops = 0L
        private set

    @Synchronized fun push(frame: ByteArray) { queue.addLast(frame) }

    @Synchronized fun clear() {
        queue.clear()
        playing = false
        starved = 0
    }

    @get:Synchronized val size: Int get() = queue.size

    /** The frame to play now: audio, silence while starved, or null when idle (nothing to play). */
    @Synchronized fun next(): ByteArray? {
        if (!playing) {
            if (queue.size < prebufferFrames) return null
            playing = true
            starved = 0
        }
        if (queue.size > prebufferFrames + maxExtraFrames) {
            queue.removeFirst()
            drops++
        }
        queue.removeFirstOrNull()?.let { starved = 0; return it }
        if (++starved > maxSilenceFrames) {
            playing = false
            return null
        }
        underruns++
        return ByteArray(frameBytes)
    }
}
