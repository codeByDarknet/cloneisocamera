package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.mycam.camera.AspectRatio

/**
 * Drawn on top of the preview: the rule-of-thirds grid and, for square photos, the dark bands
 * that hide what will be cropped away.
 */
class FrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var showGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var aspect: AspectRatio = AspectRatio.RATIO_4_3
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint().apply {
        color = 0x59FFFFFF
        strokeWidth = 1f
    }
    private val maskPaint = Paint().apply { color = 0xB3000000.toInt() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        var top = 0f
        var bottom = h
        if (aspect == AspectRatio.RATIO_1_1 && h > w) {
            top = (h - w) / 2
            bottom = top + w
            canvas.drawRect(0f, 0f, w, top, maskPaint)
            canvas.drawRect(0f, bottom, w, h, maskPaint)
        }
        if (showGrid) {
            val frameH = bottom - top
            for (i in 1..2) {
                val x = w * i / 3
                val y = top + frameH * i / 3
                canvas.drawLine(x, top, x, bottom, gridPaint)
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
        }
    }
}
