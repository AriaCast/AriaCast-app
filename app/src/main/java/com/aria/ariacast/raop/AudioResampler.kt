package com.aria.ariacast.raop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance polyphase FIR resampler for 48000 Hz to 44100 Hz conversion.
 * Ratio: 160:147 (Input:Output) -> 147 samples out for 160 samples in.
 */
class AudioResampler(
    private val channels: Int = 2
) {
    private val l = 147 // Upsampling factor
    private val m = 160 // Downsampling factor
    private val tapsPerPhase = 24
    private val filter: FloatArray
    
    // History buffer for each channel
    private val history = Array(channels) { FloatArray(tapsPerPhase) }
    private var inputPtr = 0
    private var phase = 0

    init {
        val totalTaps = tapsPerPhase * l
        filter = FloatArray(totalTaps)
        
        // Design a low-pass filter for the upsampled rate
        val cutoff = 1.0f / m.toFloat() // Normalized cutoff
        val center = (totalTaps - 1) / 2.0f
        
        for (i in 0 until totalTaps) {
            val x = i.toFloat() - center
            val sinc = if (x == 0f) 1f else sin(PI.toFloat() * cutoff * x) / (PI.toFloat() * cutoff * x)
            // Hamming window
            val window = 0.54f - 0.46f * cos(2f * PI.toFloat() * i / (totalTaps - 1))
            filter[i] = sinc * window * l // Multiply by L to maintain gain
        }
    }

    /**
     * Resamples 16-bit PCM data.
     * @param input Input buffer (16-bit PCM, interleaved)
     * @return Resampled buffer (16-bit PCM, interleaved)
     */
    fun resample(input: ByteArray): ByteArray {
        val inputSamples = input.size / (2 * channels)
        val outputSamples = (inputSamples.toLong() * l / m).toInt()
        val output = ByteArray(outputSamples * 2 * channels)
        
        var outIdx = 0
        var inIdx = 0
        
        while (inIdx < inputSamples) {
            // Process one input sample set (all channels)
            for (c in 0 until channels) {
                val sample = ((input[inIdx * 4 + c * 2 + 1].toInt() shl 8) or (input[inIdx * 4 + c * 2].toInt() and 0xFF)).toShort().toFloat()
                
                // Shift history
                for (i in tapsPerPhase - 1 downTo 1) {
                    history[c][i] = history[c][i - 1]
                }
                history[c][0] = sample
            }
            
            // Produce zero or more output samples
            while (phase < l) {
                // Calculate output for current phase
                for (c in 0 until channels) {
                    var sum = 0f
                    for (i in 0 until tapsPerPhase) {
                        val filterIdx = phase + i * l
                        sum += history[c][i] * filter[filterIdx]
                    }
                    
                    val outSample = sum.toInt().coerceIn(-32768, 32767).toShort()
                    output[outIdx++] = (outSample.toInt() and 0xFF).toByte()
                    output[outIdx++] = (outSample.toInt() shr 8).toByte()
                }
                
                phase += m
                if (outIdx >= output.size) break
            }
            
            phase -= l
            inIdx++
        }
        
        return output.copyOf(outIdx)
    }
}
