package com.aria.ariacast.raop

import org.junit.Assert.*
import org.junit.Test

class AudioResamplerTest {
    @Test
    fun testResampleSize() {
        val resampler = AudioResampler(channels = 2)
        // 160 input samples -> 147 output samples
        // Each sample set is 4 bytes (2 channels * 2 bytes/sample)
        val input = ByteArray(160 * 4)
        val output = resampler.resample(input)
        assertEquals(147 * 4, output.size)
    }

    @Test
    fun testResampleLargeBuffer() {
        val resampler = AudioResampler(channels = 2)
        val input = ByteArray(3840) // 20ms at 48kHz
        val output = resampler.resample(input)
        // 3840 bytes = 960 samples
        // 960 * 147 / 160 = 6 * 147 = 882 samples
        // 882 samples * 4 bytes/sample = 3528 bytes
        assertEquals(3528, output.size)
    }
}
