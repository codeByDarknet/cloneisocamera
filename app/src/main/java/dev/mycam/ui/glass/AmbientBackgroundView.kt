package dev.mycam.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Fills the screen behind everything with a very dark, very soft version of the backdrop, so
 * the glass controls outside the preview still pick up the colors of the scene.
 */
class AmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var backdrop: GlassBackdrop? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val cm = ColorMatrix().apply { setSaturation(1.5f) }
        cm.postConcat(ColorMatrix().apply { setScale(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f) })
        colorFilter = ColorMatrixColorFilter(cm)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop = GlassBackdrop.find(this)?.also { it.register(this) }
    }

    override fun onDetachedFromWindow() {
        backdrop?.unregister(this)
        backdrop = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        val bitmap = backdrop?.bitmap ?: return
        // Center-crop the tiny frame over the whole view; bilinear upscaling does the blur.
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * 1.2f
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - bitmap.width * scale) / 2, (height - bitmap.height * scale) / 2)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private companion object {
        const val BRIGHTNESS = 0.28f
    }
}
