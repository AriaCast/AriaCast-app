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

    @Test
    fun upsamples44kTo48kAcrossIrregularBlocks() {
        // AriaCompanion BT: SBC decodes 44.1 kHz in blocks of 128 samples per frame, several
        // frames per packet. Upsampling used to write past the output buffer.
        val resampler = AudioResampler(l = 160, m = 147, channels = 2)
        var inSamples = 0L
        var outSamples = 0L
        repeat(500) { i ->
            val frames = 1 + i % 7
            val input = ByteArray(frames * 128 * 4)
            inSamples += frames * 128
            outSamples += resampler.resample(input).size / 4
        }
        val expected = inSamples * 160 / 147
        assertTrue("got $outSamples, expected ~$expected", kotlin.math.abs(outSamples - expected) <= 2)
    }

    @Test
    fun downsampleKeepsLongRunRate() {
        val resampler = AudioResampler(channels = 2)
        var out = 0L
        repeat(1000) { out += resampler.resample(ByteArray(3840)).size / 4 }
        assertEquals(882_000L, out)
    }
}
