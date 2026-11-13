package com.aria.ariacast.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameChunkerTest {
    @Test fun rechunksArbitrarySizesWithoutLoss() {
        val c = FrameChunker(10)
        val out = mutableListOf<ByteArray>()
        val input = ByteArray(95) { it.toByte() }
        listOf(3, 17, 1, 40, 34).fold(0) { off, n -> c.push(input, off, n) { out += it }; off + n }
        assertEquals(9, out.size)
        assertEquals(5, c.pendingBytes)
        assertEquals((0 until 90).map { it.toByte() }, out.flatMap { it.toList() })
    }

    @Test fun frameSizeIs20ms() {
        assertEquals(3840, PcmFormat.FRAME_BYTES)
        assertEquals(192, PcmFormat.bytesForMillis(1))
    }
}
