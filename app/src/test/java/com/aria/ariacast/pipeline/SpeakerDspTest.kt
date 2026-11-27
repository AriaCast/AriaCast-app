package com.aria.ariacast.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpeakerDspTest {
    private val frameSamples = PcmFormat.FRAME_BYTES / PcmFormat.BYTES_PER_FRAME

    /** Runs [seconds] of a stereo sine through [dsp] and returns the RMS of the second half. */
    private fun rmsOut(dsp: SpeakerDsp, freq: Double, amplitude: Double, seconds: Int = 1): Double {
        val frames = seconds * 50
        var sum = 0.0
        var count = 0
        var n = 0
        repeat(frames) { f ->
            val pcm = ByteArray(PcmFormat.FRAME_BYTES)
            for (i in 0 until frameSamples) {
                val v = (amplitude * 32767 * sin(2 * PI * freq * n++ / PcmFormat.SAMPLE_RATE)).toInt()
                for (ch in 0..1) {
                    val at = i * 4 + ch * 2
                    pcm[at] = v.toByte(); pcm[at + 1] = (v shr 8).toByte()
                }
            }
            val out = dsp.process(pcm)
            if (f >= frames / 2) {
                for (i in 0 until out.size / 2) {
                    val s = ((out[2 * i].toInt() and 0xFF) or (out[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                    sum += s * s; count++
                }
            }
        }
        return sqrt(sum / count) / 32768
    }

    private fun db(ratio: Double) = 20 * log10(ratio)
    private fun dspOf(sound: SpeakerSound) = SpeakerDsp { sound }

    @Test fun flatSpeakerGetsTheSameFrameBack() {
        val frame = ByteArray(PcmFormat.FRAME_BYTES) { it.toByte() }
        val dsp = dspOf(SpeakerSound())
        assertSame(frame, dsp.process(frame))
    }

    @Test fun neverEditsTheSharedInputFrame() {
        val frame = ByteArray(PcmFormat.FRAME_BYTES) { (it % 100).toByte() }
        val copy = frame.copyOf()
        val out = dspOf(SpeakerSound(trimDb = -6)).process(frame)
        assertNotSame(frame, out)
        assertTrue(frame.contentEquals(copy))
    }

    @Test fun trimScalesTheLevel() {
        val reference = rmsOut(dspOf(SpeakerSound()), 1000.0, 0.2)
        val quieter = rmsOut(dspOf(SpeakerSound(trimDb = -6)), 1000.0, 0.2)
        assertEquals(-6.0, db(quieter / reference), 0.2)
    }

    @Test fun bassBoostLiftsLowsAndLeavesHighsAlone() {
        val dsp = { SpeakerDsp { SpeakerSound(bassDb = 9) } }
        val inLow = 0.1 / sqrt(2.0)
        assertEquals(9.0, db(rmsOut(dsp(), 40.0, 0.1, 2) / inLow), 1.5)
        assertEquals(0.0, db(rmsOut(dsp(), 4000.0, 0.1) / inLow), 0.5)
    }

    @Test fun trebleCutLowersHighsAndLeavesLowsAlone() {
        val dsp = { SpeakerDsp { SpeakerSound(trebleDb = -9) } }
        val inLevel = 0.1 / sqrt(2.0)
        assertEquals(-9.0, db(rmsOut(dsp(), 12000.0, 0.1) / inLevel), 1.5)
        assertEquals(0.0, db(rmsOut(dsp(), 300.0, 0.1) / inLevel), 0.5)
    }

    @Test fun boostsSoftClipInsteadOfWrapping() {
        val out = ByteArray(PcmFormat.FRAME_BYTES)
        for (i in 0 until out.size / 2) { out[2 * i] = 0xFF.toByte(); out[2 * i + 1] = 0x7F } // +32767
        val result = dspOf(SpeakerSound(trimDb = 12)).process(out)
        for (i in 0 until result.size / 2) {
            val s = ((result[2 * i].toInt() and 0xFF) or (result[2 * i + 1].toInt() shl 8)).toShort().toInt()
            assertTrue("sample $i wrapped: $s", s > 30_000)
        }
    }

    @Test fun levelerNarrowsTheGapBetweenQuietAndLoud() {
        val quietIn = 0.02
        val loudIn = 0.5
        val plainGap = db(loudIn / quietIn)
        val levelled = SpeakerSound(leveling = true)
        val quiet = rmsOut(dspOf(levelled), 1000.0, quietIn, 2)
        val loud = rmsOut(dspOf(levelled), 1000.0, loudIn, 2)
        val gap = db(loud / quiet)
        assertTrue("gap $gap dB should be well under $plainGap dB", gap < plainGap - 6)
        assertTrue("quiet passages should come up", quiet > quietIn / sqrt(2.0))
    }

    @Test fun changesApplyLiveAndReturnToFlat() {
        var sound = SpeakerSound(trimDb = -12)
        val dsp = SpeakerDsp { sound }
        val frame = ByteArray(PcmFormat.FRAME_BYTES) { if (it % 2 == 1) 0x20 else 0 } // constant 0x2000 samples
        val quiet = dsp.process(frame)
        sound = SpeakerSound()
        assertSame(frame, dsp.process(frame))
        assertTrue(abs(quiet[1].toInt()) < abs(frame[1].toInt()))
    }

    @Test fun clampsUserValuesToRange() {
        val s = SpeakerSound(bassDb = 40, trebleDb = -40, trimDb = 13).clamped()
        assertEquals(12, s.bassDb); assertEquals(-12, s.trebleDb); assertEquals(12, s.trimDb)
    }
}
