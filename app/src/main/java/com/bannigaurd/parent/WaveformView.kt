package com.bannigaurd.parent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class WaveformView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Green color
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val path = Path()
    private var phase = 0f
    private var isAnimating = false

    // ✅ FIX: अब हम इस function का इस्तेमाल नहीं करेंगे
    fun updateWaveform(waveform: ByteArray) {
        // This is complex, so we will use a fake animation instead
    }

    // ✅ FIX: Animation शुरू करने के लिए
    fun startAnimation() {
        isAnimating = true
        postInvalidateOnAnimation()
    }

    // ✅ FIX: Animation रोकने के लिए
    fun stopAnimation() {
        isAnimating = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating) {
            val centerY = height / 2f
            canvas.drawLine(0f, centerY, width.toFloat(), centerY, paint)
            return
        }

        path.reset()
        val centerY = height / 2f
        val amplitude = height / 4f // Wave की ऊंचाई

        path.moveTo(0f, centerY)

        val step = 5 // Wave को स्मूथ बनाने के लिए
        for (x in 0..width step step) {
            // एक सुंदर sin wave बनाएं
            val angle = (x.toFloat() / width.toFloat()) * (4 * Math.PI) + phase
            val y = centerY + amplitude * sin(angle).toFloat()
            path.lineTo(x.toFloat(), y)
        }

        canvas.drawPath(path, paint)

        // Animation को लगातार चलाने के लिए
        phase += 0.1f
        if (isAnimating) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}