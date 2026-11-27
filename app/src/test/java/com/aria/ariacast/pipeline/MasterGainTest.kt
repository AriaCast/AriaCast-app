package com.aria.ariacast.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterGainTest {
    private var now = 1_000_000L
    private fun gain(fadeIn: Long = 1_000, fadeOut: Long = 30_000) = MasterGain({ now }, fadeIn, fadeOut)

    /** A frame of constant full-scale-ish samples, so the gain is easy to read back. */
    private fun frame(value: Int = 10_000) = ByteArray(PcmFormat.FRAME_BYTES).also {
        for (i in 0 until it.size / 2) {
            it[2 * i] = value.toByte(); it[2 * i + 1] = (value shr 8).toByte()
        }
    }

    private fun sampleAt(pcm: ByteArray, index: Int) =
        ((pcm[2 * index].toInt() and 0xFF) or (pcm[2 * index + 1].toInt() shl 8)).toShort().toInt()

    @Test fun passesAudioUntouchedWhenIdle() {
        val g = gain()
        val f = frame()
        g.process(f)
        assertTrue(f.contentEquals(frame()))
    }

    @Test fun fadeInStartsSilentAndReachesFullVolume() {
        val g = gain()
        g.beginSession()
        assertEquals(0f, g.targetGain(), 0.001f)
        now += 500
        assertEquals(0.5f, g.targetGain(), 0.001f)
        now += 600
        assertEquals(1f, g.targetGain(), 0.001f)
    }

    @Test fun firstFrameOfAFadeInRampsFromZero() {
        val g = gain()
        g.beginSession()
        val f = frame()
        g.process(f)
        // Gain is 0 at the start of the session, so the ramp begins at silence...
        assertTrue(sampleAt(f, 0) in 0..10)
        // ...and rises across the frame.
        assertTrue(sampleAt(f, PcmFormat.FRAME_BYTES / 2 - 2) >= sampleAt(f, 0))
    }

    @Test fun timerFadesOutOverTheLastSeconds() {
        val g = gain()
        g.startTimer(60_000)
        assertEquals(1f, g.targetGain(), 0.001f)
        now += 30_000 // 30 s left: the fade starts
        assertEquals(1f, g.targetGain(), 0.001f)
        now += 15_000 // 15 s left: halfway down
        assertEquals(0.5f, g.targetGain(), 0.001f)
        now += 15_000
        assertEquals(0f, g.targetGain(), 0.001f)
        assertTrue(g.timerExpired())
    }

    @Test fun shortTimerFadesOverHalfItsLength() {
        val g = gain()
        g.startTimer(10_000)
        now += 5_000
        assertEquals(1f, g.targetGain(), 0.001f)
        now += 2_500
        assertEquals(0.5f, g.targetGain(), 0.001f)
    }

    @Test fun cancelRestoresFullVolume() {
        val g = gain()
        g.startTimer(60_000)
        now += 59_000
        assertTrue(g.targetGain() < 0.1f)
        g.cancelTimer()
        assertEquals(1f, g.targetGain(), 0.001f)
        assertNull(g.remainingMs())
        assertFalse(g.timerExpired())
    }

    @Test fun remainingCountsDown() {
        val g = gain()
        assertNull(g.remainingMs())
        g.startTimer(90_000)
        now += 10_000
        assertEquals(80_000L, g.remainingMs())
    }

    @Test fun rampDoesNotClipOrWrap() {
        val f = frame(32_767)
        MasterGain.applyRamp(f, 1f, 1f)
        assertEquals(32_767, sampleAt(f, 0))
        val neg = frame(-32_768)
        MasterGain.applyRamp(neg, 1f, 0f)
        assertEquals(0, sampleAt(neg, PcmFormat.FRAME_BYTES / 2 - 1))
        assertTrue(sampleAt(neg, 0) <= 0)
    }
}
