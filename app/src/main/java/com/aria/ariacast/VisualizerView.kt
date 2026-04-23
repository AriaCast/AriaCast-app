package com.aria.ariacast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bytes: ByteArray? = null
    private var magnitudes: FloatArray = FloatArray(64)
    
    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_blue)

    fun updateVisualizer(newBytes: ByteArray) {
        bytes = newBytes
        processFrequencies(newBytes)
        invalidate()
    }

    private fun processFrequencies(data: ByteArray) {
        if (data.size < 128) return
        
        // Simple FFT-like approach: Calculate magnitudes for frequency bands
        // Since we don't have a full FFT library here, we'll use a simplified grouped average
        // to represent frequency distribution.
        val numBands = 64
        val samplesPerBand = (data.size / 2) / numBands
        
        val newMagnitudes = FloatArray(numBands)
        for (i in 0 until numBands) {
            var sum = 0f
            for (j in 0 until samplesPerBand) {
                val idx = (i * samplesPerBand + j) * 2
                if (idx + 1 >= data.size) break
                
                // PCM 16-bit LE
                val sample = ((data[idx + 1].toInt() shl 8) or (data[idx].toInt() and 0xFF)).toShort()
                sum += Math.abs(sample.toFloat())
            }
            // Normalize and apply a simple logarithmic scale for better visualization
            val avg = sum / samplesPerBand
            val scaled = (log10(avg.coerceAtLeast(1f)) / log10(32768f))
            newMagnitudes[i] = scaled.coerceIn(0f, 1f)
        }
        
        // Smooth transitions
        for (i in 0 until numBands) {
            magnitudes[i] = magnitudes[i] * 0.6f + newMagnitudes[i] * 0.4f
        }
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        barPaint.color = accentColor

        val numBands = magnitudes.size
        val gap = 4f
        val barWidth = (width - (numBands + 1) * gap) / numBands
        
        for (i in 0 until numBands) {
            val magnitude = magnitudes[i]
            val barHeight = magnitude * height * 0.8f
            
            val x = gap + i * (barWidth + gap)
            val y = height - barHeight
            
            // Draw a rounded bar
            canvas.drawRoundRect(x, y, x + barWidth, height, 8f, 8f, barPaint)
        }
    }
}
