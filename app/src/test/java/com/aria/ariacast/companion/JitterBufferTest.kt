package com.aria.ariacast.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private fun frame(v: Int) = ByteArray(4) { v.toByte() }

    @Test fun waitsForPrebufferThenPlaysInOrder() {
        val j = JitterBuffer(frameBytes = 4, prebufferFrames = 3)
        j.push(frame(1)); j.push(frame(2))
        assertNull(j.next())                  // still prebuffering
        j.push(frame(3))
        assertArrayEquals(frame(1), j.next())
        assertArrayEquals(frame(2), j.next())
        assertArrayEquals(frame(3), j.next())
    }

    @Test fun fillsShortGapsWithSilenceThenGoesIdle() {
        val j = JitterBuffer(frameBytes = 4, prebufferFrames = 1, maxSilenceFrames = 2)
        j.push(frame(7))
        assertArrayEquals(frame(7), j.next())
        assertArrayEquals(ByteArray(4), j.next()) // gap: silence
        assertArrayEquals(ByteArray(4), j.next())
        assertNull(j.next())                      // too long: idle until it refills
        assertEquals(2L, j.underruns)
        j.push(frame(8))
        assertArrayEquals(frame(8), j.next())
    }

    @Test fun dropsOldestWhenLatencyBuildsUp() {
        val j = JitterBuffer(frameBytes = 4, prebufferFrames = 2, maxExtraFrames = 1)
        repeat(5) { j.push(frame(it)) }           // 5 > 2 + 1
        assertArrayEquals(frame(1), j.next())     // frame 0 dropped
        assertEquals(1L, j.drops)
    }

    @Test fun burstyArrivalPlaysSteadily() {
        // 3 frames every 60 ms on average, with an 80 ms hole: no underrun with 8 frames of prebuffer.
        val j = JitterBuffer(frameBytes = 4, prebufferFrames = 8)
        var played = 0
        for (tick in 0 until 300) {
            if (tick % 3 == 0 && tick !in 150..153) repeat(3) { j.push(frame(1)) }
            if (tick in 154..154) repeat(6) { j.push(frame(1)) } // the late burst
            if (j.next() != null) played++
        }
        assertEquals(0L, j.underruns)
    }
}
