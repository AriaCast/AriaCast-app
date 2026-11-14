package com.aria.ariacast.raop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polyphase FIR resampler.
 * Default (l=147, m=160): 48000→44100 Hz.
 * For 44100→48000 Hz pass (l=160, m=147).
 */
class AudioResampler(
    private val l: Int = 147,
    private val m: Int = 160,
    private val channels: Int = 2
) {
    private val tapsPerPhase = 24
    private val filter: FloatArray

    private val history = Array(channels) { FloatArray(tapsPerPhase) }
    private var phase = 0

    init {
        val totalTaps = tapsPerPhase * l
        filter = FloatArray(totalTaps)

        val cutoff = 1.0f / maxOf(l, m).toFloat()
        val center = (totalTaps - 1) / 2.0f

        for (i in 0 until totalTaps) {
            val x = i.toFloat() - center
            val sinc = if (x == 0f) 1f else sin(PI.toFloat() * cutoff * x) / (PI.toFloat() * cutoff * x)
            val window = 0.54f - 0.46f * cos(2f * PI.toFloat() * i / (totalTaps - 1))
            filter[i] = sinc * window
        }

        // Normalize each polyphase partition to unity gain
        for (phase in 0 until l) {
            var sum = 0f
            for (i in 0 until tapsPerPhase) {
                sum += filter[phase + i * l]
            }
            if (sum != 0f) {
                for (i in 0 until tapsPerPhase) {
                    filter[phase + i * l] /= sum
                }
            }
        }
    }

    fun resample(input: ByteArray): ByteArray {
        val inputSamples = input.size / (2 * channels)
        // The phase carried over from the previous call can add one output sample beyond
        // in*l/m (always when upsampling); size for it and grow if ever needed.
        val outputSamples = (inputSamples.toLong() * l / m).toInt() + 2
        var output = ByteArray(outputSamples * 2 * channels)

        var outIdx = 0
        var inIdx = 0

        while (inIdx < inputSamples) {
            for (c in 0 until channels) {
                val sample = ((input[inIdx * 4 + c * 2 + 1].toInt() shl 8) or (input[inIdx * 4 + c * 2].toInt() and 0xFF)).toShort().toFloat()
                for (i in tapsPerPhase - 1 downTo 1) {
                    history[c][i] = history[c][i - 1]
                }
                history[c][0] = sample
            }

            while (phase < l) {
                if (outIdx + 2 * channels > output.size) output = output.copyOf(output.size * 2)
                for (c in 0 until channels) {
                    var sum = 0f
                    for (i in 0 until tapsPerPhase) {
                        sum += history[c][i] * filter[phase + i * l]
                    }
                    val outSample = sum.toInt().coerceIn(-32768, 32767).toShort()
                    output[outIdx++] = (outSample.toInt() and 0xFF).toByte()
                    output[outIdx++] = (outSample.toInt() shr 8).toByte()
                }
                phase += m
            }

            phase -= l
            inIdx++
        }

        return output.copyOf(outIdx)
    }
}
