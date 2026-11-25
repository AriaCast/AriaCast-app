package com.aria.ariacast.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** How one speaker should sound. All-zero and no leveling means "leave the audio alone". */
data class SpeakerSound(
    val bassDb: Int = 0,
    val trebleDb: Int = 0,
    /** Overall level of this speaker, to even out rooms that play louder than others. */
    val trimDb: Int = 0,
    /** Evens out quiet and loud passages (night mode). */
    val leveling: Boolean = false,
) {
    val isFlat: Boolean get() = bassDb == 0 && trebleDb == 0 && trimDb == 0 && !leveling

    fun clamped() = SpeakerSound(bassDb.coerceIn(RANGE), trebleDb.coerceIn(RANGE), trimDb.coerceIn(RANGE), leveling)

    companion object {
        val RANGE = -12..12
    }
}

/**
 * Per-speaker sound shaping on 48 kHz stereo 16-bit PCM frames: a bass shelf, a treble shelf,
 * a level trim and an optional leveler (compressor with make-up gain). A soft clip at the end
 * keeps boosts from wrapping around.
 *
 * Frames on the audio bus are shared by every sink, so [process] never edits its input: it
 * returns the same array when the speaker is flat and a new one otherwise. One instance per
 * consumer; not thread-safe. [soundProvider] is read on every frame, so changes apply live.
 */
class SpeakerDsp(private val soundProvider: () -> SpeakerSound) {
    private var applied: SpeakerSound? = null
    private val bass = arrayOf(Biquad(), Biquad())
    private val treble = arrayOf(Biquad(), Biquad())
    private var trimGain = 1f
    private var leveling = false
    private var envelope = 0f

    fun process(frame: ByteArray): ByteArray {
        val sound = soundProvider()
        if (sound.isFlat) {
            if (applied?.isFlat == false) resetState()
            applied = sound
            return frame
        }
        if (sound != applied) configure(sound)

        val out = ByteArray(frame.size)
        val frames = frame.size / PcmFormat.BYTES_PER_FRAME
        val shape = sound.bassDb != 0 || sound.trebleDb != 0
        for (i in 0 until frames) {
            val base = i * PcmFormat.BYTES_PER_FRAME
            var l = readSample(frame, base) / FULL_SCALE
            var r = readSample(frame, base + 2) / FULL_SCALE
            if (shape) {
                l = treble[0].run(bass[0].run(l)); r = treble[1].run(bass[1].run(r))
            }
            var gain = trimGain
            if (leveling) gain *= levelerGain(maxOf(abs(l), abs(r)))
            writeSample(out, base, softClip(l * gain))
            writeSample(out, base + 2, softClip(r * gain))
        }
        return out
    }

    private fun configure(sound: SpeakerSound) {
        val first = applied == null
        applied = sound
        trimGain = dbToLinear(sound.trimDb.toFloat())
        if (!leveling && sound.leveling) envelope = 0f
        leveling = sound.leveling
        for (ch in 0..1) {
            bass[ch].lowShelf(BASS_HZ, sound.bassDb.toDouble())
            treble[ch].highShelf(TREBLE_HZ, sound.trebleDb.toDouble())
            if (first) { bass[ch].clear(); treble[ch].clear() }
        }
    }

    private fun resetState() {
        bass.forEach { it.clear() }
        treble.forEach { it.clear() }
        envelope = 0f
    }

    /** Compressor gain for the next sample; the envelope follows the louder channel. */
    private fun levelerGain(level: Float): Float {
        val coeff = if (level > envelope) ATTACK else RELEASE
        envelope += (level - envelope) * coeff
        val envDb = 20f * log10(envelope.coerceAtLeast(1e-5f))
        val over = envDb - LEVELER_THRESHOLD_DB
        val reduction = if (over > 0f) over * (1f - 1f / LEVELER_RATIO) else 0f
        return dbToLinear(LEVELER_MAKEUP_DB - reduction)
    }

    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun clear() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
            return y.toFloat()
        }

        /** RBJ audio-EQ-cookbook shelves with slope 1. */
        fun lowShelf(freq: Double, gainDb: Double) = shelf(freq, gainDb, low = true)
        fun highShelf(freq: Double, gainDb: Double) = shelf(freq, gainDb, low = false)

        private fun shelf(freq: Double, gainDb: Double, low: Boolean) {
            if (gainDb == 0.0) { b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0; return }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * freq / PcmFormat.SAMPLE_RATE
            val cosW = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0)
            val k = 2 * sqrt(a) * alpha
            val n0: Double; val n1: Double; val n2: Double; val d0: Double; val d1: Double; val d2: Double
            if (low) {
                n0 = a * ((a + 1) - (a - 1) * cosW + k)
                n1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                n2 = a * ((a + 1) - (a - 1) * cosW - k)
                d0 = (a + 1) + (a - 1) * cosW + k
                d1 = -2 * ((a - 1) + (a + 1) * cosW)
                d2 = (a + 1) + (a - 1) * cosW - k
            } else {
                n0 = a * ((a + 1) + (a - 1) * cosW + k)
                n1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                n2 = a * ((a + 1) + (a - 1) * cosW - k)
                d0 = (a + 1) - (a - 1) * cosW + k
                d1 = 2 * ((a - 1) - (a + 1) * cosW)
                d2 = (a + 1) - (a - 1) * cosW - k
            }
            b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0; a1 = d1 / d0; a2 = d2 / d0
        }
    }

    companion object {
        const val BASS_HZ = 150.0
        const val TREBLE_HZ = 5_000.0
        private const val FULL_SCALE = 32768f
        private const val LEVELER_THRESHOLD_DB = -24f
        private const val LEVELER_RATIO = 3f
        private const val LEVELER_MAKEUP_DB = 6f
        private val ATTACK = 1f - exp(-1f / (0.005f * PcmFormat.SAMPLE_RATE))
        private val RELEASE = 1f - exp(-1f / (0.250f * PcmFormat.SAMPLE_RATE))

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

        /** Transparent up to 0.8 of full scale, then a smooth curve that never exceeds 1.0. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            return if (a <= 0.8f) x else sign(x) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
        }

        private fun readSample(pcm: ByteArray, at: Int): Float =
            ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toFloat()

        private fun writeSample(pcm: ByteArray, at: Int, value: Float) {
            val s = (value * FULL_SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[at] = s.toByte()
            pcm[at + 1] = (s shr 8).toByte()
        }
    }
}
