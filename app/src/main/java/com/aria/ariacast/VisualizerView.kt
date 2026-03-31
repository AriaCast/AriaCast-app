package com.aria.ariacast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bytes: ByteArray? = null
    private val paint = Paint().apply {
        strokeWidth = 4f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_blue)

    fun updateVisualizer(newBytes: ByteArray) {
        bytes = newBytes
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bytes ?: return
        if (data.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        
        paint.color = accentColor
        barPaint.color = accentColor
        barPaint.alpha = 100

        // Draw a simple waveform
        val step = (data.size / 2) / 128 // Show ~128 points
        val gap = width / 128f
        
        for (i in 0 until 128) {
            val byteIdx = i * step * 2
            if (byteIdx + 1 >= data.size) break
            
            // PCM 16-bit LE to short
            val sample = ((data[byteIdx + 1].toInt() shl 8) or (data[byteIdx].toInt() and 0xFF)).toShort()
            val amplitude = (sample.toFloat() / 32768f) * centerY
            
            val x = i * gap
            canvas.drawLine(x, centerY - amplitude, x, centerY + amplitude, paint)
            
            // Subtle background bars
            canvas.drawRect(x, centerY - Math.abs(amplitude), x + gap * 0.8f, centerY + Math.abs(amplitude), barPaint)
        }
    }
}
