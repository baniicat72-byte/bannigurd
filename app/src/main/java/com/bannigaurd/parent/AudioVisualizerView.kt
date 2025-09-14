package com.bannigaurd.parent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioVisualizerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var amplitudes: ShortArray? = null
    private val paint = Paint()
    private val linePaint = Paint()

    init {
        paint.color = Color.parseColor("#9C27B0")
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND

        linePaint.color = Color.parseColor("#4A4E5A")
        linePaint.strokeWidth = 3f
        linePaint.strokeCap = Paint.Cap.ROUND
    }

    fun updateVisualizer(bytes: ByteArray) {
        val shorts = ShortArray(bytes.size / 2)
        // 16-bit PCM के लिए बाइट्स को शॉर्ट्स में बदलें
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        amplitudes = shorts
        invalidate()
    }

    fun release() {
        amplitudes = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw center line
        canvas.drawLine(0f, centerY, width, centerY, linePaint)

        if (amplitudes == null || amplitudes!!.isEmpty()) {
            return
        }

        val barCount = 64
        val barWidth = width / barCount
        val maxAmplitude = 32767f // Max value for a 16-bit short

        amplitudes?.let { shorts ->
            for (i in 0 until barCount) {
                val index = (shorts.size.toFloat() / barCount * i).toInt()
                if (index < shorts.size) {
                    val magnitude = Math.abs(shorts[index].toFloat())
                    // बार की ऊंचाई को आयाम के आधार पर स्केल करें
                    val barHeight = (magnitude / maxAmplitude) * height * 0.9f

                    val x = i * barWidth + barWidth / 2
                    val top = centerY - barHeight / 2
                    val bottom = centerY + barHeight / 2

                    // लाइन को ऊपर और नीचे दोनों तरफ ड्रा करें
                    canvas.drawLine(x, top, x, bottom, paint)
                }
            }
        }
    }
}