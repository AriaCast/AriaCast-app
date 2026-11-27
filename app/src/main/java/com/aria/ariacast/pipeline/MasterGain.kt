package com.aria.ariacast.pipeline

/**
 * One gain stage on the audio bus, ahead of every sink: a short fade-in when a cast starts and
 * the sleep timer's fade-out. Because it sits before the bus, it works the same for every
 * protocol and every source.
 *
 * The gain moves in a straight line from its previous value to the new one across each frame,
 * so there are no steps (clicks) at frame edges. Frames are 16-bit little-endian stereo PCM.
 * [process] is called from the single audio-source coroutine; the timer controls may be
 * called from any thread.
 */
class MasterGain(
    private val clock: () -> Long = System::currentTimeMillis,
    private val fadeInMs: Long = FADE_IN_MS,
    private val fadeOutMs: Long = FADE_OUT_MS,
) {
    @Volatile private var fadeInStartedAt = NONE
    @Volatile private var timerEndsAt = NONE
    @Volatile private var timerFadeMs = fadeOutMs
    private var lastGain = 1f

    /** Call when a cast starts: the next frames fade in from silence. */
    fun beginSession() {
        fadeInStartedAt = clock()
        lastGain = 0f
    }

    /** Stops the audio after [durationMs], fading out over the last part of it. */
    fun startTimer(durationMs: Long) {
        require(durationMs > 0) { "durationMs must be positive" }
        // A short timer fades over at most half of its length, so it still plays for a while.
        timerFadeMs = minOf(fadeOutMs, durationMs / 2)
        timerEndsAt = clock() + durationMs
    }

    fun cancelTimer() {
        timerEndsAt = NONE
    }

    /** Milliseconds until the timer ends, or null when no timer runs. */
    fun remainingMs(): Long? = timerEndsAt.takeIf { it != NONE }?.let { (it - clock()).coerceAtLeast(0) }

    /** True once the timer has run out. */
    fun timerExpired(): Boolean = remainingMs() == 0L

    /** The gain wanted right now, from 0 to 1. */
    fun targetGain(): Float {
        var g = 1f
        val inStart = fadeInStartedAt
        if (inStart != NONE && fadeInMs > 0) {
            g = minOf(g, ((clock() - inStart).toFloat() / fadeInMs).coerceIn(0f, 1f))
        }
        remainingMs()?.let { left ->
            if (timerFadeMs > 0) g = minOf(g, (left.toFloat() / timerFadeMs).coerceIn(0f, 1f))
            else if (left == 0L) g = 0f
        }
        return g
    }

    /** Applies the gain to [frame] in place. */
    fun process(frame: ByteArray) {
        val target = targetGain()
        val from = lastGain
        lastGain = target
        if (from == 1f && target == 1f) return // the usual case: nothing to do
        applyRamp(frame, from, target)
    }

    companion object {
        const val FADE_IN_MS = 1_000L
        const val FADE_OUT_MS = 30_000L
        private const val NONE = Long.MIN_VALUE

        /** Scales [pcm] by a gain that moves linearly from [from] to [to] across the frame. */
        fun applyRamp(pcm: ByteArray, from: Float, to: Float) {
            val frames = pcm.size / PcmFormat.BYTES_PER_FRAME
            if (frames == 0) return
            for (i in 0 until frames) {
                val g = from + (to - from) * (i + 1) / frames
                for (ch in 0 until PcmFormat.CHANNELS) {
                    val at = i * PcmFormat.BYTES_PER_FRAME + ch * PcmFormat.BYTES_PER_SAMPLE
                    val sample = ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
                    val scaled = (sample * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    pcm[at] = scaled.toByte()
                    pcm[at + 1] = (scaled shr 8).toByte()
                }
            }
        }
    }
}
