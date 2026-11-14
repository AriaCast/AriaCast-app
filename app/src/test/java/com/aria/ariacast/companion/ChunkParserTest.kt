package com.aria.ariacast.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ChunkParserTest {
    private var seq = 0L
    private fun chunk(type: Int, payload: ByteArray, s: Long = seq++): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xAC); out.write(type); out.write(payload.size and 0xFF); out.write(payload.size shr 8)
        for (i in 0 until 4) out.write(((s shr (8 * i)) and 0xFF).toInt())
        out.write(payload)
        return out.toByteArray()
    }
    private fun audio(sof: Int, pcm: ByteArray) =
        chunk(CompanionProtocol.CHUNK_AUDIO, byteArrayOf((sof and 0xFF).toByte(), (sof shr 8).toByte(), 0, 0) + pcm)

    @Test fun parsesEveryChunkType() {
        val p = ChunkParser()
        val fmt = byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 16, 2, 2, 0) // 48000
        val bytes = chunk(CompanionProtocol.CHUNK_MODE, byteArrayOf(1)) +
            chunk(CompanionProtocol.CHUNK_FORMAT, fmt) +
            chunk(CompanionProtocol.CHUNK_STREAM, byteArrayOf(1)) +
            audio(1234, byteArrayOf(1, 2, 3, 4)) +
            chunk(CompanionProtocol.CHUNK_OVERRUN, byteArrayOf(0x00, 0x01, 0, 0))
        val ev = p.feed(bytes)
        assertEquals(CompanionEvent.Mode(1), ev[0])
        assertEquals(CompanionEvent.Format(48000, 16, 2, 2), ev[1])
        assertEquals(CompanionEvent.Stream(true), ev[2])
        val a = ev[3] as CompanionEvent.Audio
        assertEquals(1234, a.sofFrame)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.pcm)
        assertEquals(CompanionEvent.Overrun(256), ev[4])
        assertEquals(0L, p.resyncs)
        assertEquals(0L, p.seqGaps)
    }

    @Test fun parsesBluetoothChunks() {
        val p = ChunkParser()
        val cfg = byteArrayOf(0x44, 0xAC.toByte(), 0, 0, 3, 16, 8, 0, 2, 53, 0, 0) // 44100 Hz, joint, 16 blocks, 8 subbands
        val sbc = byteArrayOf(0x9C.toByte(), 0x31, 0x35, 0x00)
        val prefix = byteArrayOf(0x10, 0, 0, 0, 2, 0, 0, 0) // timestamp 16, 2 frames
        val info = byteArrayOf(1, 1, 0x00, 0x01, 0x18, 0, 48, 0)
        val ev = p.feed(
            chunk(CompanionProtocol.CHUNK_SBC_CONFIG, cfg) +
                chunk(CompanionProtocol.CHUNK_SBC_AUDIO, prefix + sbc) +
                chunk(CompanionProtocol.CHUNK_INFO, info)
        )
        assertEquals(CompanionEvent.SbcConfig(44100, 3, 16, 8, 53), ev[0])
        val a = ev[1] as CompanionEvent.SbcAudio
        assertEquals(16L, a.timestamp)
        assertEquals(2, a.frames)
        assertArrayEquals(sbc, a.data)
        val i = (ev[2] as CompanionEvent.Info).info
        assertEquals("1.0", i.firmwareVersionString)
        assertTrue(i.capabilities and CompanionProtocol.CAP_BLUETOOTH != 0)
    }

    @Test fun handlesChunksSplitAcrossTransfers() {
        val p = ChunkParser()
        val bytes = audio(5, ByteArray(384) { it.toByte() }) + audio(7, ByteArray(384))
        val events = mutableListOf<CompanionEvent>()
        bytes.toList().chunked(37).forEach { events += p.feed(it.toByteArray()) }
        assertEquals(2, events.size)
        assertEquals(384, (events[0] as CompanionEvent.Audio).pcm.size)
    }

    @Test fun resyncsAfterGarbage() {
        val p = ChunkParser()
        val ev = p.feed(byteArrayOf(1, 2, 3, 0xAC.toByte(), 0x77, 0, 0) + audio(1, ByteArray(8)))
        assertEquals(1, ev.size)
        assertTrue(p.resyncs > 0)
    }

    @Test fun countsSequenceGaps() {
        val p = ChunkParser()
        p.feed(chunk(CompanionProtocol.CHUNK_STREAM, byteArrayOf(1), 10))
        p.feed(chunk(CompanionProtocol.CHUNK_STREAM, byteArrayOf(1), 13))
        assertEquals(2L, p.seqGaps)
    }

    @Test fun gapFillerInsertsSilenceOnlyForRealGaps() {
        val g = SofGapFiller()
        val twoMs = 2 * 48 * 4
        assertEquals(0, g.silenceBefore(100, twoMs))
        assertEquals(0, g.silenceBefore(102, twoMs))           // contiguous
        assertEquals(3 * 192, g.silenceBefore(107, twoMs))     // 104..106 missing
        assertEquals(0, g.silenceBefore((109) % 2048, twoMs))
        assertEquals(0, g.silenceBefore(1500, twoMs))          // > maxGapMs: treated as a restart
    }

    @Test fun gapFillerWrapsAt2048() {
        val g = SofGapFiller()
        assertEquals(0, g.silenceBefore(2046, 2 * 192))
        assertEquals(0, g.silenceBefore(0, 2 * 192))
    }
}
